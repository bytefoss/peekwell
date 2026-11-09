package com.bytefoss.peekwell

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource

/**
 * A camera's RTSP stream playing into a view, with a small buffer and retries while the view is up. A live stream can
 * stop three ways: an error, the camera ending it (Media3 then just reports it ended), or a stall with no data. Each
 * is retried: quickly for the first QUICK_RETRY_MS of failing, then it has given up (the screen says so and offers
 * to retry) and only tries every SLOW_RETRY_MS, so a camera that rebooted still comes back on its own.
 */
@OptIn(UnstableApi::class) // RtspMediaSource and the custom LoadControl are media3's "unstable" API
class Live(private val ctx: Context, private val cam: Cam, private val hd: Boolean, private val target: View, private val isMuted: Boolean) {
    private val handler = Handler(Looper.getMainLooper())
    private var released = false
    private var failingSince = 0L // when this run of failures began; 0 while it plays
    /** Failing for longer than the quick retries last. */
    var hasGivenUp = false
        private set
    /** Told each time the stream stops: with the error, or null when the camera ended it or it stalled. */
    @JvmField var onStreamLost: (PlaybackException?) -> Unit = {}
    private val listeners = mutableListOf<Player.Listener>() // the screen's, moved to each new player
    private val ownListener = object : Player.Listener {
        override fun onPlayerError(e: PlaybackException) = loseStream(e)

        override fun onPlaybackStateChanged(state: Int) {
            // a C320WS sometimes stops sending, and its stream then "ends" without an error
            if (state == Player.STATE_ENDED) loseStream(null)
            handler.removeCallbacks(stallCheck)
            if (state == Player.STATE_BUFFERING) handler.postDelayed(stallCheck, STALL_MS)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) return
            failingSince = 0L
            hasGivenUp = false
        }
    }
    /**
     * Paused by the viewer: the last picture stays and nothing is retried. Resuming starts a fresh player, so the
     * viewer sees the camera as it is now rather than where the paused stream stopped.
     */
    var isPaused = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                handler.removeCallbacksAndMessages(null)
                player.playWhenReady = false
            } else {
                retryNow()
            }
        }
    private val stallCheck = Runnable { if (!released && !isPaused && !player.isPlaying) loseStream(null) }
    // A fresh player for every try: one whose playback thread died (Media3 can't parse something a camera sent; CamApp
    // keeps that from crashing the app) never answers again. It counts as lost if it isn't playing within STALL_MS,
    // even when it never reports anything
    private val restart = Runnable {
        if (released || isPaused) return@Runnable
        player.release()
        player = buildPlayer()
        handler.postDelayed(stallCheck, STALL_MS)
    }
    private var player: ExoPlayer = buildPlayer()

    /** Listens to the player, now and after every retry. */
    fun addListener(listener: Player.Listener) {
        listeners += listener
        player.addListener(listener)
    }

    private fun buildPlayer(): ExoPlayer {
        // live view: start after half a second of video instead of the default 2.5 s
        val newPlayer = ExoPlayer.Builder(ctx)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 2000, 300, 300).build())
            .build()
        if (target is TextureView) newPlayer.setVideoTextureView(target) else newPlayer.setVideoSurfaceView(target as SurfaceView)
        // RTP over TCP: UDP packets get lost on Wi-Fi and the picture smears
        newPlayer.setMediaSource(
            RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(cam.getStreamUrl(hd)))
        )
        newPlayer.addListener(ownListener)
        listeners.forEach(newPlayer::addListener)
        if (isMuted) newPlayer.volume = 0f
        newPlayer.playWhenReady = true
        newPlayer.prepare()
        return newPlayer
    }

    /** Tries again now, with a fresh round of quick retries: the viewer asked. */
    fun retryNow() {
        handler.removeCallbacksAndMessages(null)
        failingSince = 0L
        hasGivenUp = false
        restart.run()
    }

    /** Wi-Fi blips and camera reboots: keep trying while someone is looking. */
    private fun loseStream(error: PlaybackException?) {
        if (isPaused) return // a camera that drops a paused stream is simply started again on resume
        val now = SystemClock.elapsedRealtime()
        if (failingSince == 0L) failingSince = now
        hasGivenUp = now - failingSince >= QUICK_RETRY_MS
        handler.removeCallbacks(restart)
        handler.postDelayed(restart, if (hasGivenUp) SLOW_RETRY_MS else 2_000L)
        onStreamLost(error)
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        player.release()
    }

    companion object {
        private const val QUICK_RETRY_MS = 10_000L
        private const val SLOW_RETRY_MS = 30_000L
        private const val STALL_MS = 15_000L // waiting this long for data counts as lost
    }
}
