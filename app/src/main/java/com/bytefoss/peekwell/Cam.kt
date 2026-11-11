package com.bytefoss.peekwell

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/** One camera and its ONVIF login, kept in the app's private preferences. */
class Cam {
    @JvmField var name = ""
    @JvmField var host = "" // an IP address, whose ONVIF answers on port 2020; ip:port for any other port
    @JvmField var user = ""
    @JvmField var pass = ""
    // Where its events and video are, as the camera said when it was saved (Onvif.findServiceAddresses); empty when
    // it wasn't asked, and then the common ones: :2020/onvif/service, and RTSP's stream1 and stream2 on :554
    @JvmField var eventsUrl = ""
    @JvmField var streamUrlHd = ""
    @JvmField var streamUrlSd = ""
    // The detections it said it can send (Onvif.listEventKinds), more added when it sends one it didn't list; empty
    // when it wasn't asked, and then the ones we know. And those switched off for the TV
    @JvmField var kinds: List<String> = emptyList()
    @JvmField var kindsOff: MutableSet<String> = HashSet()
    // Its stable ID from ONVIF discovery, "" until learned: how it's found again when its address changes
    @JvmField var deviceId = ""
    // Paused from the grid: no alerts from it until the user resumes them
    @JvmField var isPaused = false

    /**
     * Moves the camera to a new address (its router gave it another IP), in the addresses it said it uses for events
     * and video too.
     */
    fun moveTo(address: String) {
        // only the host part of each address (after "://" and any login), not an IP that's also in its path
        val oldHost = Regex("(://(?:[^/@]*@)?)" + Regex.escape(host.substringBefore(':')) + "(?=[:/]|$)")
        val newIp = address.substringBefore(':')
        fun movedUrl(url: String) = oldHost.replaceFirst(url, "$1$newIp")
        eventsUrl = movedUrl(eventsUrl)
        streamUrlHd = movedUrl(streamUrlHd)
        streamUrlSd = movedUrl(streamUrlSd)
        host = address
    }

    /** The detections to choose from for this camera, most important first. */
    fun listKinds(): List<String> = kinds.ifEmpty { Onvif.listLabels() }

    /** The full-resolution stream, or the small one for tiles and the popup. */
    fun getStreamUrl(hd: Boolean): String {
        val streamUrl = if (hd) streamUrlHd else streamUrlSd
        // any login the camera put in its address gives way to the one saved here
        val address = streamUrl.removePrefix("rtsp://").substringAfter('@')
            .ifEmpty { host.substringBefore(':') + ":554/" + if (hd) "stream1" else "stream2" }
        return "rtsp://" + Uri.encode(user) + ":" + Uri.encode(pass) + "@" + address
    }

    companion object {
        const val VOICE = "voice"
        const val CHIME = "chime"
        const val MUTE = "mute"

        @JvmStatic
        fun loadCameras(ctx: Context): MutableList<Cam> {
            val cams = mutableListOf<Cam>()
            try {
                val camerasJson = JSONArray(getPrefs(ctx).getString("cams", "[]"))
                for (i in 0 until camerasJson.length()) {
                    val cameraJson = camerasJson.getJSONObject(i)
                    cams += Cam().apply {
                        name = cameraJson.optString("name")
                        host = cameraJson.optString("host")
                        user = cameraJson.optString("user")
                        pass = cameraJson.optString("pass")
                        eventsUrl = cameraJson.optString("events")
                        streamUrlHd = cameraJson.optString("stream_hd")
                        streamUrlSd = cameraJson.optString("stream_sd")
                        kinds = readStrings(cameraJson.optJSONArray("kinds"))
                        // a camera saved before choices were per camera starts with the choice that was for all
                        kindsOff = cameraJson.optJSONArray("kinds_off")?.let { HashSet(readStrings(it)) } ?: getKindsOff(ctx)
                        deviceId = cameraJson.optString("device_id")
                        isPaused = cameraJson.optBoolean("paused")
                    }
                }
            } catch (ignored: JSONException) {
                // unreadable list: start empty rather than crash
            }
            return cams
        }

        @JvmStatic
        fun saveCameras(ctx: Context, cams: List<Cam>) {
            val camerasJson = JSONArray()
            for (camera in cams) {
                camerasJson.put(JSONObject().put("name", camera.name).put("host", camera.host).put("user", camera.user)
                    .put("pass", camera.pass).put("events", camera.eventsUrl).put("stream_hd", camera.streamUrlHd)
                    .put("stream_sd", camera.streamUrlSd).put("kinds", JSONArray(camera.kinds))
                    .put("kinds_off", JSONArray(camera.kindsOff.sorted())).put("device_id", camera.deviceId).put("paused", camera.isPaused))
            }
            getPrefs(ctx).edit().putString("cams", camerasJson.toString()).apply()
        }

        /**
         * The logins the saved cameras use, one per username and password, each with the names of the cameras that
         * share it: the one most cameras share first, then the one of the most recently added camera.
         */
        @JvmStatic
        fun listSavedLogins(cams: List<Cam>): List<Pair<Pair<String, String>, List<String>>> =
            cams.withIndex().filter { it.value.user.isNotEmpty() && it.value.pass.isNotEmpty() }
                .groupBy { it.value.user to it.value.pass }.entries
                .sortedWith(compareByDescending<Map.Entry<Pair<String, String>, List<IndexedValue<Cam>>>> { it.value.size }
                    .thenByDescending { sharing -> sharing.value.maxOf { it.index } })
                .map { (login, sharing) -> login to sharing.map { it.value.name } }

        /** Motion alerts, for all cameras. On until the user turns them off. */
        @JvmStatic
        fun isAlertsOn(ctx: Context): Boolean = getPrefs(ctx).getBoolean("alerts", true)

        @JvmStatic
        fun setAlerts(ctx: Context, on: Boolean) = getPrefs(ctx).edit().putBoolean("alerts", on).apply()

        /** How alerts sound, for all cameras: VOICE (the default), CHIME or MUTE. */
        @JvmStatic
        fun getAlertSound(ctx: Context): String {
            val prefs = getPrefs(ctx)
            prefs.getString("sound_mode", null)?.let { return it }
            return if (prefs.getBoolean("sound", true)) VOICE else MUTE // the on/off switch this setting replaced
        }

        @JvmStatic
        fun setAlertSound(ctx: Context, mode: String) = getPrefs(ctx).edit().putString("sound_mode", mode).apply()

        /**
         * One ONVIF discovery pass over the saved cameras: learns the stable ID of any camera answering at its address
         * that hasn't one yet, and moves any found under its ID at another address. Saves what changed; true when a
         * camera moved. Takes about 3 s: call it off the main thread.
         */
        @JvmStatic
        fun updateFromDiscovery(ctx: Context): Boolean {
            val cams = loadCameras(ctx)
            if (cams.isEmpty()) return false
            val idsBefore = cams.map { it.deviceId }
            val moved = applyDiscovery(cams, Onvif.discoverCameras(3_000))
            val learned = cams.indices.count { idsBefore[it].isEmpty() && cams[it].deviceId.isNotEmpty() }
            if (learned > 0) Log.i("peekwell", "learned the stable ID of $learned camera(s)")
            for (cam in moved) Log.i("peekwell", "${cam.name} moved to ${cam.host}")
            if (moved.isNotEmpty() || learned > 0) saveCameras(ctx, cams)
            return moved.isNotEmpty()
        }

        /** What one discovery pass (address, model, ID) changes in cams, in place; the cameras that moved. */
        @JvmStatic
        fun applyDiscovery(cams: List<Cam>, found: List<Triple<String, String, String>>): List<Cam> {
            val moved = mutableListOf<Cam>()
            for (cam in cams) {
                if (cam.deviceId.isEmpty()) {
                    cam.deviceId = found.firstOrNull { it.first.substringBefore(':') == cam.host.substringBefore(':') }?.third.orEmpty()
                    continue
                }
                val now = found.firstOrNull { it.third == cam.deviceId } ?: continue
                if (now.first.substringBefore(':') != cam.host.substringBefore(':')) {
                    cam.moveTo(now.first)
                    moved += cam
                }
            }
            return moved
        }

        private fun readStrings(array: JSONArray?): List<String> = (0 until (array?.length() ?: 0)).map { array!!.getString(it) }

        /** Kinds switched off for the TV, as chosen for all cameras before choices moved to each camera. */
        @JvmStatic
        fun getKindsOff(ctx: Context): MutableSet<String> =
            HashSet(getPrefs(ctx).getStringSet("kinds_off", emptySet()) ?: emptySet())

        /** The most important of kinds (ordered as Onvif.listDetectedLabels() gives them) that isn't switched off; null if none. */
        @JvmStatic
        fun findFirstKindOn(kinds: List<String>, off: Set<String>): String? = kinds.firstOrNull { it !in off }

        /** Quiet hours: from/to in minutes after midnight; the sound during them is CHIME or MUTE. */
        @JvmStatic
        fun isQuietOn(ctx: Context): Boolean = getPrefs(ctx).getBoolean("quiet_on", false)

        @JvmStatic
        fun getQuietFrom(ctx: Context): Int = getPrefs(ctx).getInt("quiet_from", 23 * 60)

        @JvmStatic
        fun getQuietTo(ctx: Context): Int = getPrefs(ctx).getInt("quiet_to", 7 * 60)

        @JvmStatic
        fun getQuietSound(ctx: Context): String = getPrefs(ctx).getString("quiet_sound", MUTE) ?: MUTE

        @JvmStatic
        fun setQuiet(ctx: Context, on: Boolean, from: Int, to: Int, sound: String) =
            getPrefs(ctx).edit().putBoolean("quiet_on", on).putInt("quiet_from", from).putInt("quiet_to", to)
                .putString("quiet_sound", sound).apply()

        /** Whether minute-of-day now falls in [from, to); a range that passes midnight (23:00-07:00) wraps. */
        @JvmStatic
        fun isInQuietHours(now: Int, from: Int, to: Int): Boolean {
            if (from == to) return false // an empty range, not all day
            return if (from < to) now >= from && now < to else now >= from || now < to
        }

        /** The sound to use: quiet hours only ever make it quieter (Voice -> Chime -> Mute), never louder. */
        @JvmStatic
        fun chooseSound(normal: String, quiet: Boolean, quietSound: String): String {
            if (!quiet) return normal
            val order = listOf(VOICE, CHIME, MUTE)
            return if (order.indexOf(quietSound) > order.indexOf(normal)) quietSound else normal
        }

        /** The sound for an alert right now, with quiet hours applied. */
        @JvmStatic
        fun getSoundNow(ctx: Context): String {
            val calendar = Calendar.getInstance()
            val now = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
            val quiet = isQuietOn(ctx) && isInQuietHours(now, getQuietFrom(ctx), getQuietTo(ctx))
            return chooseSound(getAlertSound(ctx), quiet, getQuietSound(ctx))
        }

        @JvmStatic
        fun formatClock(minutes: Int): String = String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

        /** Whether the pop-up shows what was detected ("● Person"). On by default. */
        @JvmStatic
        fun isLabelShown(ctx: Context): Boolean = getPrefs(ctx).getBoolean("show_label", true)

        @JvmStatic
        fun setShowLabel(ctx: Context, on: Boolean) = getPrefs(ctx).edit().putBoolean("show_label", on).apply()

        /** The pop-up's corner: "bottom_right" (the default), "bottom_left", "top_right" or "top_left". */
        @JvmStatic
        fun getAlertPosition(ctx: Context): String = getPrefs(ctx).getString("alert_position", "bottom_right") ?: "bottom_right"

        @JvmStatic
        fun setAlertPosition(ctx: Context, position: String) = getPrefs(ctx).edit().putString("alert_position", position).apply()

        /** The pop-up's size: "small", "medium" (the default, 30% of the screen's width) or "large". */
        @JvmStatic
        fun getAlertSize(ctx: Context): String = getPrefs(ctx).getString("alert_size", "medium") ?: "medium"

        @JvmStatic
        fun setAlertSize(ctx: Context, size: String) = getPrefs(ctx).edit().putString("alert_size", size).apply()

        /** Pause from Settings: every camera stays quiet for minutes, then alerts come back; 0 resumes them now. */
        @JvmStatic
        fun pauseAll(ctx: Context, minutes: Int) = getPrefs(ctx).edit()
            .putLong("paused_until", if (minutes == 0) 0L else System.currentTimeMillis() + minutes * 60_000L)
            .putInt("pause_minutes", minutes).apply()

        /** Until when (System.currentTimeMillis) every camera is paused; 0 when it isn't, or the pause is over. */
        @JvmStatic
        fun getPausedUntil(ctx: Context): Long {
            val until = getPrefs(ctx).getLong("paused_until", 0L)
            return if (until > System.currentTimeMillis()) until else 0L
        }

        /** The pause chosen in Settings while it lasts, in minutes; 0 when there's none. */
        @JvmStatic
        fun getPauseMinutes(ctx: Context): Int = if (getPausedUntil(ctx) > 0) getPrefs(ctx).getInt("pause_minutes", 0) else 0

        private fun getPrefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("cams", Context.MODE_PRIVATE)
    }
}
