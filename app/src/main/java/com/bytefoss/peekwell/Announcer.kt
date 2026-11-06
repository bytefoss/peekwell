package com.bytefoss.peekwell

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * Says what the camera saw ("Person detected") with the TV's text-to-speech engine. Falls back to a chime when the TV
 * has no engine, the engine isn't ready yet, or speaking fails, so an alert is never silent.
 */
class Announcer(ctx: Context) : TextToSpeech.OnInitListener {
    // Media usage at the TV's volume, and no audio focus request: asking for focus makes many TV apps pause the
    // show underneath. TVs often have no notification sound and may mute that stream.
    private val sounds = SoundPool.Builder().setMaxStreams(1)
        .setAudioAttributes(buildAudioAttributes(AudioAttributes.CONTENT_TYPE_SONIFICATION)).build()
    private val tonePerson = sounds.load(ctx, R.raw.tone_person, 1)
    private val toneOther = sounds.load(ctx, R.raw.tone_other, 1)
    @Volatile private var ready = false
    @Volatile private var asked = 0L // when the last announcement was asked for, to log how long speech took to start
    // Announcements waiting their turn as (kind, phrase), and when the engine started the one it's saying (0 = idle).
    // The engine gets one at a time so a person can be put ahead of what's waiting. Both guarded by the list
    private val waitingAnnouncements = ArrayList<Pair<String, String>>()
    private var speakingSince = 0L
    private val tts = TextToSpeech(ctx, this)

    init {
        tts.setAudioAttributes(buildAudioAttributes(AudioAttributes.CONTENT_TYPE_SPEECH))
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {
                Log.i("peekwell", "speaking $id ${SystemClock.elapsedRealtime() - asked} ms after the alert")
            }

            override fun onDone(id: String) {
                Log.i("peekwell", "announced $id in ${SystemClock.elapsedRealtime() - asked} ms")
                synchronized(waitingAnnouncements) { speakNextAnnouncement() }
            }

            override fun onError(id: String, code: Int) {
                Log.w("peekwell", "speech failed ($code), chiming instead")
                playChime(id)
                synchronized(waitingAnnouncements) { speakNextAnnouncement() }
            }

            @Deprecated("still required by the abstract class")
            override fun onError(id: String) = onError(id, -1)
        })
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w("peekwell", "no text-to-speech on this TV; alerts chime")
            return
        }
        val offlineVoice = findOfflineVoice(tts.voices, Locale.getDefault())
        ready = if (offlineVoice != null) {
            tts.setVoice(offlineVoice) == TextToSpeech.SUCCESS
        } else { // no installed voice in this language: the TV's language, else US English, as the engine sees fit
            var lang = tts.setLanguage(Locale.getDefault())
            if (lang < TextToSpeech.LANG_AVAILABLE) lang = tts.setLanguage(Locale.US)
            lang >= TextToSpeech.LANG_AVAILABLE
        }
        Log.i("peekwell", "text-to-speech " + if (ready) "ready: " + tts.voice.name else "has no usable voice; alerts chime")
    }

    /**
     * Announces kind ("Person", "Motion", ...) the way mode says (Cam.VOICE, CHIME or MUTE); camera is added to the
     * spoken phrase when not null.
     */
    fun announce(kind: String, camera: String?, mode: String) {
        if (mode == Cam.MUTE) return
        if (mode == Cam.CHIME) {
            playChime(kind)
            return
        }
        val text = buildPhrase(kind) + if (camera != null) " at $camera" else ""
        synchronized(waitingAnnouncements) {
            asked = SystemClock.elapsedRealtime()
            addAnnouncementInLine(waitingAnnouncements, kind, text)
            // An engine that never reported the end of a phrase mustn't silence every later alert
            if (speakingSince == 0L || asked - speakingSince > STUCK_MS) speakNextAnnouncement()
        }
    }

    /** Hands the engine the next waiting announcement, or chimes it when the engine can't speak. Hold the list. */
    private fun speakNextAnnouncement() {
        while (waitingAnnouncements.isNotEmpty()) {
            val (kind, phrase) = waitingAnnouncements.removeAt(0)
            // The utterance id carries the kind, so a failed utterance chimes the right tone
            if (ready && tts.speak(phrase, TextToSpeech.QUEUE_ADD, null, kind) == TextToSpeech.SUCCESS) {
                speakingSince = SystemClock.elapsedRealtime()
                return
            }
            playChime(kind)
        }
        speakingSince = 0L
    }

    fun release() {
        tts.shutdown()
        sounds.release()
    }

    private fun playChime(kind: String) {
        Log.i("peekwell", "chimed $kind")
        sounds.play(if (kind == "Person") tonePerson else toneOther, 1f, 1f, 1, 0, 1f)
    }

    companion object {
        private const val STUCK_MS = 10_000L // far longer than any phrase takes to say

        /**
         * Puts an announcement in line: a person goes ahead of every waiting non-person announcement, behind
         * earlier persons; anything else goes last. The one being spoken isn't in the line, so it's never cut off.
         */
        @JvmStatic
        fun addAnnouncementInLine(
            waitingAnnouncements: MutableList<Pair<String, String>>, kind: String, phrase: String,
        ) {
            val firstOther = waitingAnnouncements.indexOfFirst { it.first != "Person" }
            val position = if (kind == "Person" && firstOther >= 0) firstOther else waitingAnnouncements.size
            waitingAnnouncements.add(position, kind to phrase)
        }

        /** What's said for each detector label (see Onvif.detected). */
        @JvmStatic
        fun buildPhrase(kind: String): String = if (kind == "Tamper") "Camera tampering detected" else "$kind detected"

        /**
         * A voice installed on the TV, so an alert never waits on the internet: a TV set to Indian English whose
         * engine only has that voice on its servers would otherwise try the network first (seconds) before falling
         * back. Prefers the TV's language and country, then the same language, then English; null when there's none.
         */
        fun findOfflineVoice(voices: Set<Voice>?, want: Locale): Voice? {
            var best: Voice? = null
            var bestScore = 0
            for (voice in voices ?: return null) {
                if (voice.isNetworkConnectionRequired) continue
                if (voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) continue
                val voiceLocale = voice.locale
                var score = when {
                    voiceLocale.language == want.language -> if (voiceLocale.country == want.country) 30 else 20
                    voiceLocale.language == "en" -> if (voiceLocale.country == "US") 11 else 10
                    else -> 0
                }
                if (score == 0) continue
                score = score * 1000 + voice.quality // then the better-sounding one
                if (score > bestScore) {
                    best = voice
                    bestScore = score
                }
            }
            return best
        }

        private fun buildAudioAttributes(contentType: Int): AudioAttributes =
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(contentType).build()
    }
}
