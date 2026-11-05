package com.bytefoss.peekwell

import android.util.Log
import java.io.IOException
import java.util.Locale

/**
 * Watches one camera's ONVIF events on its own thread and reports each one to a listener (on that thread).
 *
 * Built for cameras that behave like a C320WS: it serves one pull point at a time, it often drops the first pull on a new
 * one, and while someone is in view it answers every pull at once with the same event. So: one short-lived pull point,
 * kept alive with Renew; retry a dropped pull before giving it up; pace pulls while events stream in; and release the
 * pull point on stop, so the next watcher (or app) isn't locked out.
 */
class Watcher(private val cam: Cam, private val onvif: Onvif, private val listener: Listener) {
    fun interface Listener {
        /** The labels that fired in one reply, most important first (see Onvif.listDetectedLabels()); never empty. */
        fun onEvent(kinds: List<String>)
    }

    constructor(cam: Cam, listener: Listener) : this(cam, Onvif(cam), listener)

    // Timings; tests shorten them. Worst case between renewals: renewMs + one pull (waitS + 10 s read timeout) = 30 s,
    // which must stay under lifeS.
    @JvmField var lifeS = 40              // a pull point left behind by a crash blocks the camera at most this long
    @JvmField var renewMs = 10_000L
    @JvmField var waitS = 10              // how long the camera may hold a pull open when nothing happens
    @JvmField var paceMs = 1_000L         // pause after a pull that carried an event
    @JvmField var retryMs = 1_000L        // pause before retrying a failed pull on the same pull point
    @JvmField var maxFails = 3            // failed pulls in a row before starting over with a new pull point
    @JvmField var blockedMs = 5_000L      // pull point granted but its pulls refused: one left behind is in the way, gone within lifeS
    @JvmField var busyMs = 60_000L        // blocked longer than any leftover lasts: another device holds the camera's alerts
    @JvmField var backoffMs = 5_000L      // camera unreachable: first wait, doubling up to backoffMaxMs
    @JvmField var backoffMaxMs = 30_000L
    @JvmField var authBackoffMs = 300_000L // wrong login: retry rarely, some cameras lock the account after repeated failures

    /**
     * Told why alerts aren't working ("Can't reach the camera", ...), and null once events arrive again; only when that
     * changes, on the watcher's thread.
     */
    @JvmField var onProblem: (String?) -> Unit = {}
    /** Told once when the camera has been unreachable for lostMs: it may have a new address. On the watcher's thread. */
    @JvmField var onLost: () -> Unit = {}
    @JvmField var lostMs = 60_000L
    private var reportedProblem: String? = null

    private val thread = Thread(::watchEvents, "motion-" + cam.host).apply { isDaemon = true }
    @Volatile private var stopped = false

    fun start() = thread.start()

    /** Asks the watcher to stop: cuts short its pull, after which it releases its pull point and exits. */
    fun stop() {
        stopped = true
        onvif.abortPull()
        thread.interrupt() // wakes it from a pause
    }

    /** Waits until the watcher has exited and released its pull point. */
    fun join(ms: Long): Boolean {
        try {
            thread.join(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return !thread.isAlive
    }

    private fun watchEvents() {
        var backoff = backoffMs
        var blockedSince = 0L // when the camera began refusing our pulls; 0 while it isn't
        var unreachableSince = 0L // when it stopped answering at all; 0 while it answers
        var isLostReported = false
        while (!stopped) {
            var sub: String? = null
            try {
                onvif.syncClock()
                sub = onvif.subscribe(lifeS)
                Log.i(TAG, "${cam.host}: watching for motion")
                var renewAt = getNowMs() + renewMs
                var fails = 0
                while (!stopped) {
                    val kinds: List<String>
                    try {
                        if (getNowMs() >= renewAt) {
                            onvif.renew(sub, lifeS)
                            renewAt = getNowMs() + renewMs
                        }
                        kinds = onvif.pullEvents(sub, waitS)
                        fails = 0
                        reportProblem(null)
                        blockedSince = 0L
                        unreachableSince = 0L
                        isLostReported = false
                        backoff = backoffMs // only a pull that worked resets the back-off
                    } catch (e: IOException) {
                        if (stopped) break
                        // a C320WS often drops the first pull on a new pull point, then answers the rest
                        if (++fails >= maxFails) throw e
                        Log.w(TAG, "${cam.host}: pull failed (${e.message}), retrying")
                        pauseFor(retryMs)
                        continue
                    }
                    if (kinds.isNotEmpty()) {
                        listener.onEvent(kinds)
                        pauseFor(paceMs) // while someone is in view, every pull comes back at once with the same event
                    }
                }
            } catch (e: Exception) {
                // IOException, and RuntimeException too: a bug or a camera's odd answer must not end the watching for good
                if (stopped) break
                val wait = when {
                    isAuthError(e) -> authBackoffMs.also { reportProblem("Alerts can't sign in to the camera") }
                    // the camera serves one pull point at a time: after an update or a crash, ours left behind
                    // holds it for up to lifeS, which is normal and passes; longer, it's someone else's
                    sub != null -> blockedMs.also {
                        if (blockedSince == 0L) blockedSince = getNowMs()
                        reportProblem(if (getNowMs() - blockedSince < busyMs) STARTING else "Another device is using this camera's alerts")
                    }
                    else -> backoff.also {
                        backoff = minOf(backoff * 2, backoffMaxMs)
                        reportProblem("Alerts can't reach the camera")
                        if (unreachableSince == 0L) unreachableSince = getNowMs()
                        if (!isLostReported && getNowMs() - unreachableSince >= lostMs) {
                            isLostReported = true
                            onLost()
                        }
                    }
                }
                Log.w(TAG, "${cam.host}: $e, retrying in ${wait / 1000} s")
                pauseFor(wait)
            } finally {
                // clear stop()'s interrupt first: Android's HTTP stack would fail the Unsubscribe on it, and a pull
                // point left behind locks the camera for lifeS
                Thread.interrupted()
                if (sub != null) onvif.unsubscribe(sub)
            }
        }
        Log.i(TAG, "${cam.host}: stopped watching")
    }

    private fun reportProblem(problem: String?) {
        if (problem == reportedProblem) return
        reportedProblem = problem
        onProblem(problem)
    }

    private fun pauseFor(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            // stop() woke us; the loop checks stopped
        }
    }

    companion object {
        private const val TAG = "peekwell"

        /** Not a problem, just not working yet: the camera's alerts are still held by our own leftover. */
        const val STARTING = "Starting alerts…"

        /** The camera refused the login: "Authority failure", "Not Authorized", HTTP 401. */
        @JvmStatic
        fun isAuthError(e: Throwable): Boolean {
            val message = e.message.toString().lowercase(Locale.ROOT)
            return "authori" in message || "http 401" in message
        }

        private fun getNowMs(): Long = System.nanoTime() / 1_000_000
    }
}
