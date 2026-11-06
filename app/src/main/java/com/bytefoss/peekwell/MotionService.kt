package com.bytefoss.peekwell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.toArgb
import java.util.concurrent.Executors

/** Watches every camera's ONVIF events and shows the popup on motion. Restart it to reload the camera list. */
class MotionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    // Starting and stopping watchers runs here, one change at a time, so an old watcher has released its pull point
    // before a new one asks the camera for one (a C320WS serves one at a time).
    private val control = Executors.newSingleThreadExecutor()
    private val watchers = mutableListOf<Watcher>() // only touched on the control thread
    private var started = false
    private lateinit var overlay: Overlay
    private lateinit var announcer: Announcer
    private var cameraCount = 0
    // main thread only: per camera host, when the last event came and what this visit has been labelled
    private val lastEvent = HashMap<String, Long>()
    private val visitKind = HashMap<String, String>()
    private var lastDiscoveryAt = 0L // main thread only: when findMovedCameras last ran

    override fun onCreate() {
        super.onCreate()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(NotificationChannel("watch", "Motion alerts", NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, "watch")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Watching cameras for motion")
            .setColor(AppColors.primary.toArgb())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, notification)
        overlay = Overlay(this)
        announcer = Announcer(this) // the speech engine warms up now, not on the first alert
    }

    /** Opening the app starts this too; only a first start or a "reload" extra (the camera list changed) restarts. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.hasExtra("test")) {
            // "Test alert" in the camera's settings: the most important kind that's switched on, with the sound
            // quiet hours would give it now, as if the camera had sent it; past the visit rule, so it always plays
            val cams = Cam.loadCameras(this)
            val cameraIndex = intent.getIntExtra("test", 0)
            if (cameraIndex in cams.indices) {
                val cam = cams[cameraIndex]
                main.post {
                    val kind = Cam.findFirstKindOn(cam.listKinds(), cam.kindsOff)
                    val sound = Cam.getSoundNow(this)
                    Log.i(TAG, "${cam.host}: test alert $kind, sound $sound")
                    if (kind == null) return@post // every kind switched off: nothing would alert
                    announcer.announce(kind, if (cams.size > 1) cam.name else null, sound)
                    overlay.show(cam, kind)
                }
            }
            if (started) return START_STICKY
        }
        val reload = intent?.getBooleanExtra("reload", false) ?: false
        if (started && !reload) return START_STICKY
        started = true
        val cams = Cam.loadCameras(this)
        cameraCount = cams.size
        control.execute {
            stopWatchers()
            for (cam in cams) {
                val watcher = Watcher(cam) { kinds -> main.post { showAlert(cam, kinds) } }
                watcher.onLost = { main.post(::findMovedCameras) }
                watcher.onProblem = { problem ->
                    main.post { if (problem == null) alertProblems.remove(cam.host) else alertProblems[cam.host] = problem }
                }
                watchers += watcher
                watcher.start()
            }
        }
        // learn the stable ID of any camera without one, so it can be found again if its address changes
        Thread({ runCatching { Cam.updateFromDiscovery(this) } }, "discovery").start()
        return START_STICKY
    }

    /**
     * A camera stopped answering for a while: its router may have given it a new address. Discovery finds it under its
     * stable ID; when one moved, the watchers restart with the new addresses. At most every 5 minutes.
     */
    private fun findMovedCameras() {
        val now = SystemClock.elapsedRealtime()
        if (lastDiscoveryAt != 0L && now - lastDiscoveryAt < 5 * 60_000L) return
        lastDiscoveryAt = now
        Thread({
            val isMoved = runCatching { Cam.updateFromDiscovery(this) }.getOrDefault(false)
            Log.i(TAG, if (isMoved) "a camera moved to a new address; watching it there" else "no camera found at a new address")
            if (isMoved) main.post { syncService(this, true) }
        }, "discovery").start()
    }

    override fun onDestroy() {
        control.execute(::stopWatchers) // the process usually lives on long enough to release the pull points
        control.shutdown()
        overlay.hide(false) // no slide-out: the service is going away
        announcer.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Stops every watcher and waits (up to 15 s) until each has released its pull point. */
    private fun stopWatchers() {
        for (watcher in watchers) watcher.stop()
        for (watcher in watchers) if (!watcher.join(15_000)) Log.w(TAG, "a watcher didn't stop in time")
        watchers.clear()
        main.post { alertProblems.clear() }
    }

    /**
     * One event. The pop-up stays up while events keep coming. It's announced once per visit, and again whenever the
     * visit turns out to be something more important (motion, then a vehicle, then a person).
     */
    private fun showAlert(cam: Cam, kinds: List<String>) {
        // the most important kind the TV should react to; the rest of the reply was switched off in the settings
        // a detection the camera didn't list becomes one of its choices, switched on
        chooseLearnedKinds(cam.kinds, kinds)?.let { learned ->
            Log.i(TAG, "${cam.host}: sends ${kinds - cam.kinds.toSet()} too, added to its choices")
            cam.kinds = learned
            val cams = Cam.loadCameras(this)
            cams.firstOrNull { it.host == cam.host }?.let {
                it.kinds = learned
                Cam.saveCameras(this, cams)
            }
        }
        val kind = Cam.findFirstKindOn(kinds, cam.kindsOff)
        if (kind == null) {
            Log.d(TAG, "${cam.host}: $kinds switched off for the TV, ignored")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val last = lastEvent.put(cam.host, now)
        val newVisit = last == null || now - last > VISIT_GAP_MS
        val before = if (newVisit) null else visitKind[cam.host]
        val shown = chooseVisitLabel(before, kind)
        val chime = shown != before
        visitKind[cam.host] = shown
        if (chime) {
            Log.i(TAG, "${cam.host}: $shown")
            // the camera's name only helps when there's more than one
            announcer.announce(shown, if (cameraCount > 1) cam.name else null, Cam.getSoundNow(this))
        }
        overlay.show(cam, shown)
    }

    companion object {
        private const val TAG = "peekwell"
        /** A detection after this long without one is a new visit: it chimes again. */
        private const val VISIT_GAP_MS = 30_000L

        /** Why alerts aren't working, per camera host, for the grid to show; main thread only. */
        @JvmField val alertProblems = mutableStateMapOf<String, String>()

        /** Runs the service while alerts are on and there are cameras; reload restarts it with a changed list. */
        @JvmStatic
        fun syncService(ctx: Context, reload: Boolean) {
            val service = Intent(ctx, MotionService::class.java)
            if (Cam.isAlertsOn(ctx) && Cam.loadCameras(ctx).isNotEmpty()) ctx.startForegroundService(service.putExtra("reload", reload))
            else ctx.stopService(service)
        }

        /**
         * The visit's label after an event of this kind: the more important of the two (see Onvif.rank), so a person
         * walking past a parked car stays "Person". before is null at the start of a visit. A changed label is
         * announced.
         */
        @JvmStatic
        fun chooseVisitLabel(before: String?, kind: String): String =
            if (before == null || Onvif.rankLabel(kind) < Onvif.rankLabel(before)) kind else before

        /**
         * A camera's choices with the detections it just sent that it hadn't offered, most important first; null when
         * there are none new, or it was never asked (its choices are then every kind we know, and an unknown one
         * still alerts).
         */
        @JvmStatic
        fun chooseLearnedKinds(offered: List<String>, detected: List<String>): List<String>? {
            if (offered.isEmpty() || offered.containsAll(detected)) return null
            return (offered + detected).distinct().sortedBy(Onvif::rankLabel)
        }
    }
}
