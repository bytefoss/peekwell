package com.bytefoss.peekwell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The problem report: never a login or a full address, and a link short enough for a QR code. */
public class ReportTest {
    private static Cam cam(String host, String user, String pass) {
        Cam c = new Cam();
        c.host = host;
        c.user = user;
        c.pass = pass;
        return c;
    }

    @Test
    public void reportHasNoLoginOrFullAddress() {
        List<Cam> cams = Arrays.asList(cam("192.168.1.52", "viewer", "s3cret!pw"), cam("10.0.0.7:8080", "admin", "hunter22"));
        String raw = "Stream addresses: HD rtsp://viewer:s3cret!pw@192.168.1.52:554/stream1\n"
                + "Device: TP-Link C320WS · firmware 1.6.6\n"
                + "error: 401 for viewer at 192.168.1.52, also tried 10.0.0.7:8080\n"
                + "media at 192.168.1.200:80/onvif/media, password hunter22 refused\n"
                + "1 camera(s), cameras viewed by viewers";
        String clean = Report.sanitize(raw, cams);
        for (String secret : Arrays.asList("s3cret!pw", "hunter22", "192.168.1.52", "10.0.0.7", "192.168.1.200")) {
            assertFalse("leaked " + secret + " in:\n" + clean, clean.contains(secret));
        }
        assertTrue(clean, clean.contains("rtsp://camera:554/stream1")); // login dropped, address masked, path kept
        assertTrue(clean, clean.contains("x.x.x.200:80")); // another host keeps only its last number
        assertTrue(clean, clean.contains("password *** refused"));
        assertTrue(clean, clean.contains("error: 401 for *** at camera")); // a username on its own is hidden
        assertTrue(clean, clean.contains("cameras viewed by viewers")); // but not inside other words
        assertTrue(clean, clean.contains("TP-Link C320WS · firmware 1.6.6"));
    }

    @Test
    public void issueLinkFitsAQrCode() {
        StringBuilder longReport = new StringBuilder("Peekwell 1.3");
        for (int i = 0; i < 200; i++) longReport.append("\n  Video profiles: H264 2304x1296, H264 640x360 #").append(i);
        String url = Report.buildIssueUrl(longReport.toString());
        assertTrue(url.getBytes().length <= 1500);
        assertTrue(url.startsWith("https://github.com/bytefoss/peekwell/issues/new?title="));
        assertTrue(url.contains("shortened"));

        String shortUrl = Report.buildIssueUrl("Peekwell 1.3\n\nCamera 1\n  ONVIF: answers");
        assertFalse(shortUrl.contains("shortened"));
        assertTrue(shortUrl.contains("ONVIF%3A+answers"));
    }

    @Test
    public void shortLoginsAreLeftAlone() {
        // a two-letter login would match too much ordinary text to be worth hiding
        assertEquals("ab camera", Report.sanitize("ab 1.2.3.4", Collections.singletonList(cam("1.2.3.4", "ab", "cd"))));
    }
}
