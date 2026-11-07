package com.bytefoss.peekwell

import android.app.Application
import android.util.Log

/**
 * Keeps one camera's bad stream from taking alerts down with it. Media3 1.4.1's RTSP client throws on its own player
 * thread when a camera sends a message it can't parse (seen once from a C320WS: IllegalArgumentException in
 * RtspMessageUtil.parseRequest). Uncaught, that kills the whole app, the alert watcher included. Caught here, only
 * that player's video stops; the next popup, or opening the screen again, starts a fresh one.
 */
class CamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val next = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            if (thread.name.startsWith("ExoPlayer:")) {
                Log.e("peekwell", "video player thread died; that video stops, alerts keep running", e)
            } else {
                next?.uncaughtException(thread, e)
            }
        }
    }
}
