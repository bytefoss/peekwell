package com.bytefoss.peekwell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/** The watcher against a fake C320WS: every way the alerts broke, or could break, on the real camera. */
public class WatcherTest {
    private FakeCamera camera;
    private final List<String> events = new CopyOnWriteArrayList<>();
    private Watcher watcher;

    @Before
    public void setUp() throws Exception {
        camera = new FakeCamera();
    }

    @After
    public void tearDown() {
        if (watcher != null) {
            watcher.stop();
            watcher.join(5_000);
        }
        camera.close();
    }

    /** Fast timings with the production ratios: renew well inside the life, back-off capped. */
    private Watcher watcher(Cam cam, Watcher.Listener l) {
        Watcher w = new Watcher(cam, new Onvif(cam, camera.service()), l);
        w.lifeS = 3;
        w.renewMs = 1_000;
        w.waitS = 1;
        w.paceMs = 100;
        w.retryMs = 100;
        w.blockedMs = 200;
        w.backoffMs = 200;
        w.backoffMaxMs = 800;
        w.authBackoffMs = 300;
        return w;
    }

    private Watcher start() {
        watcher = watcher(camera.cam(), k -> events.add(k.get(0)));
        watcher.start();
        return watcher;
    }

    private static void await(String what, long ms, BooleanSupplier ok) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("timed out waiting for: " + what);
            Thread.sleep(20);
        }
    }

    @Test
    public void personEventArrivesDespiteDroppedFirstPull() throws Exception {
        start();
        await("a served pull", 3_000, () -> camera.pulls.get() >= 2);
        camera.fire("IsPeople");
        await("Person event", 3_000, () -> events.contains("Person"));
        assertEquals("only the first pull was dropped", 1, camera.droppedPulls.get());
        assertEquals("kept one pull point", 1, camera.creates.get());
        assertEquals(0, camera.authFailures.get());
    }

    @Test
    public void otherDetectorsAreLabelled() throws Exception {
        start();
        camera.fire("IsLineCross");
        await("Line crossing", 3_000, () -> events.contains("Line crossing"));
    }

    @Test
    public void renewKeepsOnePullPointAlivePastItsLife() throws Exception {
        start();
        Thread.sleep(5_000); // life is 3 s
        assertEquals("never had to start over", 1, camera.creates.get());
        assertTrue("renewed", camera.renews.get() >= 3);
        camera.fire("IsMotion");
        await("Motion event after 5 s", 3_000, () -> events.contains("Motion"));
    }

    @Test
    public void floodIsPaced() throws Exception {
        start();
        camera.fire("IsPeople");
        await("Person", 3_000, () -> !events.isEmpty());
        int before = camera.pulls.get();
        Thread.sleep(2_000);
        int perSecond = (camera.pulls.get() - before) / 2;
        assertTrue("pulls while flooded: " + perSecond + "/s", perSecond <= 12); // pace 100 ms; unpaced it's hundreds
    }

    @Test
    public void stopReleasesThePullPointQuickly() throws Exception {
        Watcher w = watcher(camera.cam(), k -> events.add(k.get(0)));
        w.waitS = 8; // a long poll in flight: only cutting it short makes stop quick
        w.lifeS = 20;
        watcher = w;
        w.start();
        await("subscribed and pulling", 3_000, () -> camera.pulls.get() >= 2);
        long t0 = System.currentTimeMillis();
        w.stop();
        assertTrue("stopped", w.join(3_000));
        assertTrue("stop took " + (System.currentTimeMillis() - t0) + " ms", System.currentTimeMillis() - t0 < 1_500);
        assertEquals("pull point released, not left to expire", 0, camera.liveSubs());
        watcher = null;
    }

    @Test
    public void restartLikeSaveGetsEventsWithoutStall() throws Exception {
        Watcher old = start();
        await("first watcher pulling", 3_000, () -> camera.pulls.get() >= 2);
        old.stop();
        old.join(3_000);
        long t0 = System.currentTimeMillis();
        watcher = watcher(camera.cam(), k -> events.add(k.get(0)));
        watcher.start();
        camera.fire("IsPeople");
        await("Person after restart", 3_000, () -> events.contains("Person"));
        assertTrue("took " + (System.currentTimeMillis() - t0) + " ms", System.currentTimeMillis() - t0 < 1_500);
    }

    @Test
    public void recoversFromPullPointLeftByKilledApp() throws Exception {
        camera.orphan(3); // like the app killed mid-pull: blocks every other pull point until it expires
        long t0 = System.currentTimeMillis();
        start();
        camera.fire("IsPeople");
        await("Person once the leftover expires", 6_000, () -> events.contains("Person"));
        long took = System.currentTimeMillis() - t0;
        assertTrue("took " + took + " ms, leftover lives 3000", took >= 2_500 && took < 4_500);
    }

    @Test
    public void wrongPasswordKeepsRetryingWithoutCrashing() throws Exception {
        Cam bad = camera.cam();
        bad.pass = "wrong";
        watcher = watcher(bad, k -> events.add(k.get(0)));
        watcher.start();
        await("several attempts", 5_000, () -> camera.authFailures.get() >= 3);
        assertTrue("still running", !watcher.join(10));
        assertEquals(0, camera.liveSubs());
    }

    @Test
    public void wrongPasswordDoesNotHammerTheCamera() throws Exception {
        Cam bad = camera.cam();
        bad.pass = "wrong";
        watcher = watcher(bad, k -> events.add(k.get(0)));
        watcher.authBackoffMs = 3_000;
        watcher.start();
        Thread.sleep(2_500);
        assertEquals("one refused login, then a long wait", 1, camera.authFailures.get());
    }

    @Test
    public void authErrorsAreRecognised() {
        assertTrue(Watcher.isAuthError(new java.io.IOException("Authority failure")));
        assertTrue(Watcher.isAuthError(new java.io.IOException("Sender not Authorized")));
        assertTrue(Watcher.isAuthError(new java.io.IOException("HTTP 401")));
        assertTrue(!Watcher.isAuthError(new java.io.IOException("unexpected end of stream on Address@1")));
        assertTrue(!Watcher.isAuthError(new IllegalStateException((String) null)));
    }

    @Test
    public void cameraOfflineThenBack() throws Exception {
        Cam cam = camera.cam();
        int port = Integer.parseInt(camera.service().replaceAll(".*:(\\d+)/.*", "$1"));
        camera.close(); // camera rebooting / Wi-Fi gone
        watcher = new Watcher(cam, new Onvif(cam, "http://127.0.0.1:" + port + "/onvif/service"), k -> events.add(k.get(0)));
        watcher.backoffMs = 200;
        watcher.backoffMaxMs = 800;
        watcher.start();
        Thread.sleep(1_500); // fails a few times meanwhile
        assertTrue("still running while offline", !watcher.join(10));
        watcher.stop();
        assertTrue("stops promptly while backing off", watcher.join(1_500));
        watcher = null;
    }

    @Test
    public void camerasAreWatchedInParallelAndIndependently() throws Exception {
        try (FakeCamera second = new FakeCamera()) {
            Cam frontCam = camera.cam();
            Cam gateCam = second.cam();
            gateCam.name = "Gate";
            List<String> gateEvents = new CopyOnWriteArrayList<>();
            camera.orphan(3); // the front camera is blocked for 3 s by a leftover pull point
            watcher = watcher(frontCam, k -> events.add(k.get(0)));
            Watcher gateWatcher = new Watcher(gateCam, new Onvif(gateCam, second.service()), k -> gateEvents.add(k.get(0)));
            gateWatcher.blockedMs = 200;
            gateWatcher.retryMs = 100;
            gateWatcher.paceMs = 100;
            gateWatcher.lifeS = 3;
            gateWatcher.renewMs = 1_000;
            gateWatcher.waitS = 1;
            watcher.start();
            gateWatcher.start();
            try {
                camera.fire("IsPeople");
                second.fire("IsVehicle");
                await("gate alerts while the front camera is still blocked", 2_000, () -> gateEvents.contains("Vehicle"));
                assertTrue("front still blocked", events.isEmpty());
                await("front alerts once its leftover expires", 5_000, () -> events.contains("Person"));
                assertTrue("gate kept alerting meanwhile", gateEvents.size() > 3);
            } finally {
                gateWatcher.stop();
                gateWatcher.join(5_000);
            }
        }
    }

    @Test
    public void listenerCrashDoesNotEndWatching() throws Exception {
        boolean[] thrown = {false};
        watcher = watcher(camera.cam(), kinds -> {
            if (!thrown[0]) {
                thrown[0] = true;
                throw new IllegalStateException("bug in the alert code");
            }
            events.add(kinds.get(0));
        });
        watcher.start();
        camera.fire("IsPeople");
        await("Person after the crash", 5_000, () -> events.contains("Person"));
        assertTrue(thrown[0]);
    }

    @Test
    public void asksTheCameraWhereEventsAndVideoAreAndWatchesThere() throws Exception {
        Cam cam = camera.cam();
        Onvif onvif = new Onvif(cam, camera.service());
        onvif.syncClock();
        onvif.findServiceAddresses();
        assertEquals(camera.service().replace("/onvif/service", "/onvif/events"), cam.eventsUrl);
        assertEquals("rtsp://someone:old@127.0.0.1:554/live/main", cam.streamUrlHd); // the biggest profile
        assertEquals("rtsp://someone:old@127.0.0.1:554/live/sub", cam.streamUrlSd); // the smallest
        String hdStreamUrl = cam.getStreamUrl(true);
        assertTrue(hdStreamUrl, hdStreamUrl.endsWith("@127.0.0.1:554/live/main") && !hdStreamUrl.contains("old"));
        // what it can detect, asked at the events address it gave
        assertEquals(java.util.Arrays.asList("Person", "Face", "Motion"), onvif.listEventKinds());

        watcher = watcher(cam, k -> events.add(k.get(0)));
        watcher.start();
        camera.fire("IsPeople");
        await("an event from the events address", 5_000, () -> !events.isEmpty());
        assertEquals("/onvif/events", camera.createPath);
    }

    @Test
    public void reportsWhyAlertsArentWorkingOnlyWhenItChanges() throws Exception {
        List<String> problems = new java.util.concurrent.CopyOnWriteArrayList<>();
        Cam wrongLogin = camera.cam();
        wrongLogin.pass = "wrong";
        watcher = watcher(wrongLogin, k -> events.add(k.get(0)));
        watcher.onProblem = problem -> {
            problems.add(String.valueOf(problem));
            return kotlin.Unit.INSTANCE;
        };
        watcher.start();
        await("a few refused logins", 5_000, () -> camera.authFailures.get() >= 3);
        assertEquals(java.util.Arrays.asList("Alerts can't sign in to the camera"), problems); // once, not on every retry
        watcher.stop();
        watcher.join(5_000);

        problems.clear();
        camera.orphan(3); // a pull point left behind: the camera is busy until it expires
        watcher = watcher(camera.cam(), k -> events.add(k.get(0)));
        watcher.busyMs = 1_000; // busy past this: someone else's, not a leftover
        watcher.onProblem = problem -> {
            problems.add(String.valueOf(problem));
            return kotlin.Unit.INSTANCE;
        };
        watcher.start();
        await("alerts working again", 10_000, () -> problems.contains("null"));
        assertEquals(java.util.Arrays.asList(Watcher.STARTING, "Another device is using this camera's alerts", "null"), problems);
    }

    @Test
    public void logsInWithHttpDigestWhenTheCameraAsksForIt() throws Exception {
        camera.requireHttpDigest = true;
        Cam cam = camera.cam();
        Onvif onvif = new Onvif(cam, camera.service());
        onvif.syncClock();
        assertTrue(onvif.getModel().startsWith("tp-link C320WS"));

        watcher = watcher(cam, k -> events.add(k.get(0)));
        watcher.start();
        camera.fire("IsPeople");
        await("an event through the Digest login", 5_000, () -> !events.isEmpty());

        Cam wrongLogin = camera.cam();
        wrongLogin.pass = "wrong";
        try {
            new Onvif(wrongLogin, camera.service()).getModel();
            throw new AssertionError("a wrong password was accepted");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage(), Watcher.isAuthError(e)); // shown as a wrong login, not a network problem
        }
    }

    @Test
    public void saysOnceWhenTheCameraHasBeenUnreachableForAWhile() throws Exception {
        int closedPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            closedPort = probe.getLocalPort(); // nothing listens here once it's closed: like a camera that moved
        }
        java.util.concurrent.atomic.AtomicInteger lost = new java.util.concurrent.atomic.AtomicInteger();
        Cam cam = camera.cam();
        watcher = watcher(cam, k -> events.add(k.get(0)));
        watcher = new Watcher(cam, new Onvif(cam, "http://127.0.0.1:" + closedPort + "/onvif/service"), k -> events.add(k.get(0)));
        watcher.backoffMs = 100;
        watcher.backoffMaxMs = 200;
        watcher.lostMs = 500;
        watcher.onLost = () -> {
            lost.incrementAndGet();
            return kotlin.Unit.INSTANCE;
        };
        watcher.start();
        await("the camera reported lost", 5_000, () -> lost.get() > 0);
        Thread.sleep(1_000); // still unreachable: not reported again
        assertEquals(1, lost.get());
    }
}
