package com.bytefoss.peekwell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

/** A video player thread's crash is contained; any other thread's still reaches the normal crash handler. */
public class CamAppTest {
    private final Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();

    @After
    public void restore() {
        Thread.setDefaultUncaughtExceptionHandler(original);
    }

    private static void crashOn(String threadName) throws InterruptedException {
        Thread t = new Thread(() -> {
            throw new IllegalArgumentException("unparseable RTSP message");
        }, threadName);
        t.start();
        t.join();
    }

    @Test
    public void playerThreadCrashIsContained() throws Exception {
        AtomicReference<String> reachedNext = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> reachedNext.set(t.getName()));
        new CamApp().onCreate();

        crashOn("ExoPlayer:Playback");
        assertNull("contained, not passed on to the crash handler", reachedNext.get());

        crashOn("motion-192.168.1.50");
        assertEquals("other threads crash as before", "motion-192.168.1.50", reachedNext.get());
    }
}
