package com.bytefoss.peekwell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in for a Tapo C320WS (hw 2.0, fw 1.6.6) ONVIF event service, with the quirks measured on the real one:
 * - checks the WS-Security password digest
 * - serves only the oldest live pull point; pulls on any other one are dropped (connection closed, no answer)
 * - drops the first pull on each new pull point
 * - pull points expire unless renewed; Unsubscribe frees them at once
 * - while a detector is on, every pull answers at once with the same event, Value before Name
 */
final class FakeCamera implements AutoCloseable {
    final String user = "admin", pass = "s3cret";
    final AtomicInteger creates = new AtomicInteger(), pulls = new AtomicInteger(), droppedPulls = new AtomicInteger(),
            renews = new AtomicInteger(), unsubscribes = new AtomicInteger(), authFailures = new AtomicInteger();
    volatile boolean dropFirstPull = true;
    volatile String createPath; // where the last pull point was asked for
    volatile boolean requireHttpDigest; // like a camera that takes only an HTTP Digest login, not WS-Security

    private final ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool(); // long polls hold threads
    private final Object lock = new Object();
    private final Map<Integer, Sub> subs = new LinkedHashMap<>(); // oldest first
    private int nextId = 1024;
    private String activeFlag; // e.g. "IsPeople" while someone is in view

    private static final class Sub {
        long expiresAt;
        boolean pulledOnce;
    }

    FakeCamera() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        pool.execute(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    pool.execute(() -> serve(s));
                } catch (IOException e) {
                    return; // closed
                }
            }
        });
    }

    String service() {
        return base() + "/onvif/service";
    }

    Cam cam() {
        Cam c = new Cam();
        c.name = "Fake";
        c.host = "127.0.0.1";
        c.user = user;
        c.pass = pass;
        return c;
    }

    /** A detector turns on (stays on until clear()); waiting pulls answer at once. */
    void fire(String flag) {
        synchronized (lock) {
            activeFlag = flag;
            lock.notifyAll();
        }
    }

    void clear() {
        synchronized (lock) {
            activeFlag = null;
        }
    }

    /** A pull point nobody will renew or release, like one left by an app that was killed. */
    void orphan(int lifeS) {
        synchronized (lock) {
            Sub s = new Sub();
            s.expiresAt = now() + lifeS * 1000L;
            subs.put(nextId++, s);
        }
    }

    int liveSubs() {
        synchronized (lock) {
            expire();
            return subs.size();
        }
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // already closed
        }
        pool.shutdownNow();
    }

    private String base() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    private void serve(Socket x) {
        try (Socket s = x) {
            InputStream in = s.getInputStream();
            String head = readHead(in);
            String path = head.split(" ")[1];
            Matcher len = Pattern.compile("(?i)content-length:\\s*(\\d+)").matcher(head);
            byte[] body = new byte[len.find() ? Integer.parseInt(len.group(1)) : 0];
            for (int n = 0; n < body.length; ) n += Math.max(0, in.read(body, n, body.length - n));
            handle(s, path, head, new String(body, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            // client went away
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, state = 0; // count of \r\n\r\n matched so far
        while ((c = in.read()) >= 0) {
            b.write(c);
            state = (c == "\r\n\r\n".charAt(state)) ? state + 1 : (c == '\r' ? 1 : 0);
            if (state == 4) break;
        }
        return b.toString("UTF-8");
    }

    private void handle(Socket x, String path, String head, String body) throws IOException {
        if (body.contains("GetSystemDateAndTime")) {
            SimpleDateFormat f = new SimpleDateFormat("yyyy,M,d,H,m,s", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            String[] t = f.format(new Date()).split(",");
            reply(x, 200, "<tt:UTCDateTime><tt:Time><tt:Hour>" + t[3] + "</tt:Hour><tt:Minute>" + t[4]
                    + "</tt:Minute><tt:Second>" + t[5] + "</tt:Second></tt:Time><tt:Date><tt:Year>" + t[0]
                    + "</tt:Year><tt:Month>" + t[1] + "</tt:Month><tt:Day>" + t[2] + "</tt:Day></tt:Date></tt:UTCDateTime>");
            return;
        }
        if (requireHttpDigest && !isDigestLoginValid(head)) {
            authFailures.incrementAndGet();
            OutputStream o = x.getOutputStream();
            o.write(("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Digest realm=\"FakeCam\", qop=\"auth\", "
                    + "nonce=\"c0ffee\", opaque=\"0pq\", algorithm=MD5\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            o.flush();
            return;
        }
        if (!requireHttpDigest && !authorized(body)) {
            authFailures.incrementAndGet();
            reply(x, 400, "<SOAP-ENV:Fault><SOAP-ENV:Text xml:lang=\"en\">Authority failure</SOAP-ENV:Text></SOAP-ENV:Fault>");
            return;
        }
        if (body.contains("GetDeviceInformation")) {
            reply(x, 200, "<tds:Manufacturer>tp-link</tds:Manufacturer><tds:Model>C320WS</tds:Model>"
                    + "<tds:FirmwareVersion>1.6.6 Build 260917</tds:FirmwareVersion>");
            return;
        }
        // Addresses laid out like a non-Tapo camera: events and media on their own paths, the small profile listed
        // before the big one, and a profile without video
        if (body.contains("GetCapabilities")) {
            reply(x, 200, "<tds:Capabilities><tt:Device><tt:XAddr>" + base() + "/onvif/device_service</tt:XAddr></tt:Device>"
                    + "<tt:Events><tt:XAddr>" + base() + "/onvif/events</tt:XAddr></tt:Events>"
                    + "<tt:Media><tt:XAddr>" + base() + "/onvif/media</tt:XAddr></tt:Media></tds:Capabilities>");
            return;
        }
        // what it can detect, on its events address: a person, a face (a flag the app doesn't know) and motion
        if (body.contains("GetEventProperties") && path.equals("/onvif/events")) {
            StringBuilder topics = new StringBuilder("<wstop:TopicSet><tns1:RuleEngine>");
            for (String flag : new String[] {"IsPeople", "IsFace", "IsMotion"}) {
                topics.append("<").append(flag).append(" wstop:topic=\"true\"><tt:MessageDescription><tt:Data>")
                        .append("<tt:SimpleItemDescription Name=\"").append(flag).append("\" Type=\"xsd:boolean\"/>")
                        .append("</tt:Data></tt:MessageDescription></").append(flag).append(">");
            }
            reply(x, 200, topics.append("</tns1:RuleEngine></wstop:TopicSet>").toString());
            return;
        }
        if (body.contains("GetProfiles") && path.equals("/onvif/media")) {
            reply(x, 200, "<trt:Profiles token=\"audio\" fixed=\"true\"><tt:Name>audio</tt:Name></trt:Profiles>"
                    + profile("sub", 640, 360) + profile("main", 1920, 1080));
            return;
        }
        if (body.contains("GetStreamUri") && path.equals("/onvif/media")) {
            reply(x, 200, "<trt:MediaUri><tt:Uri>rtsp://someone:old@127.0.0.1:554/live/"
                    + Onvif.getTagText(body, "ProfileToken") + "</tt:Uri></trt:MediaUri>");
            return;
        }
        if (body.contains("CreatePullPointSubscription")) {
            creates.incrementAndGet();
            createPath = path;
            int id;
            synchronized (lock) {
                Sub s = new Sub();
                s.expiresAt = now() + seconds(body, "InitialTerminationTime") * 1000L;
                id = nextId++;
                subs.put(id, s);
            }
            reply(x, 200, "<tev:SubscriptionReference><wsa5:Address>" + base() + "/event-" + id + "_" + id
                    + "</wsa5:Address></tev:SubscriptionReference>");
            return;
        }
        Matcher m = Pattern.compile("/event-(\\d+)").matcher(path);
        int id = m.find() ? Integer.parseInt(m.group(1)) : -1;
        if (body.contains("<Unsubscribe")) {
            unsubscribes.incrementAndGet();
            synchronized (lock) {
                subs.remove(id);
            }
            reply(x, 200, "<wsnt:UnsubscribeResponse></wsnt:UnsubscribeResponse>");
            return;
        }
        if (body.contains("<Renew")) {
            renews.incrementAndGet();
            synchronized (lock) {
                expire();
                Sub s = subs.get(id);
                if (s == null) {
                    reply(x, 400, "<SOAP-ENV:Text>no such subscription</SOAP-ENV:Text>");
                    return;
                }
                s.expiresAt = now() + seconds(body, "TerminationTime") * 1000L;
            }
            reply(x, 200, "<wsnt:RenewResponse></wsnt:RenewResponse>");
            return;
        }
        if (body.contains("PullMessages")) {
            pulls.incrementAndGet();
            String flag;
            synchronized (lock) {
                expire();
                Sub s = subs.get(id);
                boolean served = s != null && subs.keySet().iterator().next() == id; // only the oldest is served
                boolean first = s != null && !s.pulledOnce;
                if (s != null) s.pulledOnce = true;
                if (!served || (first && dropFirstPull)) {
                    droppedPulls.incrementAndGet();
                    x.close(); // hang up without an answer, like the real camera
                    return;
                }
                long until = now() + seconds(body, "Timeout") * 1000L;
                while (activeFlag == null && now() < until) {
                    try {
                        lock.wait(Math.max(1, until - now()));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                flag = activeFlag;
            }
            reply(x, 200, flag == null ? "<tev:PullMessagesResponse></tev:PullMessagesResponse>"
                    : "<tev:PullMessagesResponse><wsnt:NotificationMessage><wsnt:Topic>tns1:RuleEngine/X</wsnt:Topic>"
                    + "<wsnt:Message><tt:Message PropertyOperation=\"Initialized\"><tt:Data>"
                    + "<tt:SimpleItem Value=\"true\" Name=\"" + flag + "\"></tt:SimpleItem>"
                    + "</tt:Data></tt:Message></wsnt:Message></wsnt:NotificationMessage></tev:PullMessagesResponse>");
            return;
        }
        reply(x, 400, "<SOAP-ENV:Text>unknown</SOAP-ENV:Text>");
    }

    private static String profile(String token, int width, int height) {
        return "<trt:Profiles token=\"" + token + "\" fixed=\"true\"><tt:Name>" + token + "</tt:Name>"
                + "<tt:VideoEncoderConfiguration token=\"enc_" + token + "\"><tt:Encoding>H264</tt:Encoding><tt:Resolution>"
                + "<tt:Width>" + width + "</tt:Width><tt:Height>" + height + "</tt:Height></tt:Resolution>"
                + "</tt:VideoEncoderConfiguration></trt:Profiles>";
    }

    private void expire() {
        long now = now();
        for (Iterator<Sub> it = subs.values().iterator(); it.hasNext(); ) if (it.next().expiresAt <= now) it.remove();
    }

    /** Base64(SHA-1(nonce + created + password)) must match, like the real camera's check. */
    private boolean authorized(String body) {
        try {
            String user = Onvif.getTagText(body, "Username"), digest = Onvif.getTagText(body, "Password");
            byte[] nonce = Base64.getDecoder().decode(Onvif.getTagText(body, "Nonce"));
            String created = Onvif.getTagText(body, "Created");
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            sha.update(nonce);
            sha.update(created.getBytes(StandardCharsets.UTF_8));
            sha.update(pass.getBytes(StandardCharsets.UTF_8));
            return this.user.equals(user) && Base64.getEncoder().encodeToString(sha.digest()).equals(digest);
        } catch (Exception e) {
            return false;
        }
    }

    /** The Authorization header's Digest answer must match one worked out from the password, as RFC 7616 says. */
    private boolean isDigestLoginValid(String head) {
        Matcher line = Pattern.compile("(?im)^authorization:\\s*Digest (.*)$").matcher(head);
        if (!line.find()) return false;
        Map<String, String> field = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^\\s,]*))").matcher(line.group(1));
        while (m.find()) field.put(m.group(1), m.group(2) != null ? m.group(2) : m.group(3));
        String ha1 = md5(user + ":FakeCam:" + pass), ha2 = md5("POST:" + field.get("uri"));
        String expected = md5(ha1 + ":c0ffee:" + field.get("nc") + ":" + field.get("cnonce") + ":auth:" + ha2);
        return user.equals(field.get("username")) && "0pq".equals(field.get("opaque")) && expected.equals(field.get("response"));
    }

    private static String md5(String text) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int seconds(String body, String tag) {
        Matcher m = Pattern.compile("PT(\\d+)S").matcher(Onvif.getTagText(body, tag));
        return m.find() ? Integer.parseInt(m.group(1)) : 60;
    }

    private static void reply(Socket x, int code, String inner) throws IOException {
        byte[] b = ("<?xml version=\"1.0\"?><SOAP-ENV:Envelope><SOAP-ENV:Body>" + inner
                + "</SOAP-ENV:Body></SOAP-ENV:Envelope>").getBytes(StandardCharsets.UTF_8);
        OutputStream o = x.getOutputStream();
        o.write(("HTTP/1.1 " + code + (code == 200 ? " OK" : " Bad Request") + "\r\nContent-Type: application/soap+xml"
                + "\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        o.write(b);
        o.flush();
    }

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }
}
