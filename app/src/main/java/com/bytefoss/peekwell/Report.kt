package com.bytefoss.peekwell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Surface
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.annotation.WorkerThread
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * "Report a problem": checks each camera now and writes a short report that a user sends as a GitHub issue by
 * scanning a QR code with their phone. Peekwell itself sends nothing. The report never holds a username, password or
 * full address: a camera's address becomes "camera", any other address keeps only its last number.
 */
object Report {
    private const val ISSUES_URL = "https://github.com/bytefoss/peekwell/issues/new"
    private const val MAX_URL_BYTES = 1500 // a QR code a phone still reads off a TV across the room
    private const val VIDEO_TEST_MS = 12_000L
    private val IPV4 = Regex("""\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b""")
    private val LOGIN_IN_URL = Regex("""(//)[^/@\s]+@""")

    /** The last video problem per camera address ("14:02 ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, gave up"). */
    private val lastVideoProblems = ConcurrentHashMap<String, String>()

    fun noteVideoProblem(host: String, problem: String) {
        lastVideoProblems[host] = SimpleDateFormat("HH:mm", Locale.US).format(Date()) + " " + problem.removePrefix("ERROR_CODE_")
    }

    fun getLastVideoProblem(host: String): String = lastVideoProblems[host] ?: "none"

    /** The report's first lines: the app, the TV and the settings that matter. */
    fun describeTv(ctx: Context, cams: List<Cam>): String {
        val app = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return "Peekwell ${app.versionName} (${app.longVersionCode}) · Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "${cams.size} camera(s) · motion alerts ${if (Cam.isAlertsOn(ctx)) "on" else "off"} · pop-up permission " +
            if (Settings.canDrawOverlays(ctx)) "granted" else "missing"
    }

    /**
     * Asks one camera over ONVIF whether it answers, who it is, its video profiles and what it can detect. Slow when
     * a camera doesn't answer (10 s per request), so off the main thread. Works on a copy: the saved camera is
     * never changed.
     */
    @WorkerThread
    fun checkOnvif(cam: Cam): List<String> {
        val probe = Cam().also {
            it.host = cam.host
            it.user = cam.user
            it.pass = cam.pass
        }
        val onvif = Onvif(probe)
        val reached = runCatching { onvif.syncClock() }
        if (reached.isFailure) return listOf("ONVIF: no answer (${describeError(reached.exceptionOrNull()!!)})")
        val lines = mutableListOf("ONVIF: answers")
        lines += runCatching { onvif.describeDevice() }.getOrElse { listOf("Device: sign-in failed (${describeError(it)})") }
        lines += "Video profiles: " + runCatching { onvif.findServiceAddresses().joinToString(", ") }
            .getOrElse { "not found (${describeError(it)})" }
        lines += "Stream addresses: HD ${withoutLogin(probe.streamUrlHd)}, SD ${withoutLogin(probe.streamUrlSd)}"
        lines += "Detections: " + runCatching { onvif.listEventKinds().joinToString(", ").ifEmpty { "none listed" } }
            .getOrElse { "not available (${describeError(it)})" }
        return lines
    }

    /**
     * Opens the camera's small stream for up to 12 s and decodes it off screen, with RTP over TCP (what the app
     * uses) or Media3's default (UDP first): how long the first picture took and its format, or what went wrong.
     */
    @MainThread
    @OptIn(UnstableApi::class)
    suspend fun testVideo(ctx: Context, cam: Cam, isOverTcp: Boolean): String {
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val player = ExoPlayer.Builder(ctx).build()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val outcome = withTimeoutOrNull(VIDEO_TEST_MS) {
                suspendCancellableCoroutine { result ->
                    player.addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            if (result.isActive) result.resume("picture in ${SystemClock.elapsedRealtime() - startedAt} ms")
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            val cause = error.cause?.let { " (" + describeError(it) + ")" } ?: ""
                            if (result.isActive) result.resume(error.errorCodeName.removePrefix("ERROR_CODE_") + cause)
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED && result.isActive) result.resume("the stream ended")
                        }
                    })
                    player.setVideoSurface(surface)
                    player.volume = 0f
                    player.setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(isOverTcp)
                        .createMediaSource(MediaItem.fromUri(cam.getStreamUrl(false))))
                    player.playWhenReady = true
                    player.prepare()
                }
            } ?: "no picture in ${VIDEO_TEST_MS / 1000} s"
            val format = player.videoFormat?.let { " (${it.sampleMimeType?.removePrefix("video/")} ${it.width}x${it.height})" }
            return outcome + (format ?: "")
        } finally {
            player.release()
            surface.release()
            texture.release()
        }
    }

    /** The whole report, with every camera address masked and any login removed (see sanitize). */
    fun buildReport(header: String, cameraSections: List<Pair<Cam, List<String>>>): String {
        val text = StringBuilder(header)
        cameraSections.forEachIndexed { i, (_, lines) ->
            text.append("\n\nCamera ${i + 1}")
            lines.forEach { text.append("\n  ").append(it) }
        }
        return sanitize(text.toString(), cameraSections.map { it.first })
    }

    /**
     * No logins, passwords or full addresses: each camera's address becomes "camera", any other address keeps only
     * its last number, a login inside a URL is dropped, and a camera's username or password anywhere becomes "***".
     */
    @JvmStatic
    fun sanitize(text: String, cams: List<Cam>): String {
        var clean = LOGIN_IN_URL.replace(text, "$1")
        for (cam in cams) {
            for (secret in listOf(cam.pass, cam.user)) {
                if (secret.length >= 3) clean = Regex("""(?<![\w])""" + Regex.escape(secret) + """(?![\w])""").replace(clean, "***")
            }
            clean = clean.replace(cam.host.substringBefore(':'), "camera")
        }
        return IPV4.replace(clean) { "x.x.x." + it.groupValues[4] }
    }

    /**
     * A link that opens a new GitHub issue with the report filled in, short enough for a QR code a phone reads off a
     * TV: when it isn't, the report loses lines from its end.
     */
    @JvmStatic
    fun buildIssueUrl(report: String): String {
        fun encode(text: String) = URLEncoder.encode(text, "UTF-8")
        fun issueUrl(body: String) = "$ISSUES_URL?title=" + encode("Camera problem report") + "&body=" +
            encode("What happens:\n\n(describe it here)\n\n```\n$body\n```")
        var lines = report.lines()
        var url = issueUrl(report)
        while (url.toByteArray().size > MAX_URL_BYTES && lines.size > 1) {
            lines = lines.dropLast(1)
            url = issueUrl(lines.joinToString("\n") + "\n… shortened; the full report is on the TV")
        }
        return url
    }

    /** text as a QR code of sizePx square, black on white with a quiet border. */
    fun makeQrCode(text: String, sizePx: Int): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx) { if (matrix[it % sizePx, it / sizePx]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }

    /** What went wrong in a few words: the deepest cause, the common ones in plain words, so the QR code stays small. */
    private fun describeError(error: Throwable): String {
        if (Watcher.isAuthError(error)) return "login refused"
        val cause = generateSequence(error) { it.cause }.last()
        val message = cause.message.orEmpty()
        return when {
            cause is ConnectException || "ECONNREFUSED" in message -> "connection refused"
            cause is SocketTimeoutException || "ETIMEDOUT" in message -> "timed out"
            cause is UnknownHostException -> "unknown address"
            "EHOSTUNREACH" in message || "ENETUNREACH" in message -> "unreachable"
            else -> cause.javaClass.simpleName + (if (message.isEmpty()) "" else ": " + message.take(60))
        }
    }

    private fun withoutLogin(streamUrl: String): String =
        if (streamUrl.isEmpty()) "none" else "rtsp://" + streamUrl.removePrefix("rtsp://").substringAfter('@')
}
