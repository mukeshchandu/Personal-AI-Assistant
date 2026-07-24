package com.naomi.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Wraps Android's built-in TextToSpeech — Naomi's "mouth".
 * Zero dependencies, works offline once the system voice data is installed.
 *
 * Supports an [onDone] callback (delivered on the main thread) so the app can, e.g.,
 * start listening again right after Naomi finishes asking a question.
 */
class Speaker(context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingOnDone: (() -> Unit)? = null
    private var ready = false

    private val tts = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) { ready = true; pickVoice() }
    }.also {
        it.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                pendingOnDone?.let { cb -> pendingOnDone = null; mainHandler.post(cb) }
            }
            @Deprecated("deprecated in API 21")
            override fun onError(utteranceId: String?) { pendingOnDone = null }
            override fun onError(utteranceId: String?, errorCode: Int) { pendingOnDone = null }
        })
    }

    private fun pickVoice() {
        val voices = tts.voices ?: run {
            tts.language = Locale("en", "IN")
            return
        }
        // Prefer: offline, English, female label, highest quality. en-IN > en-US > any English.
        fun score(v: Voice): Int {
            if (v.isNetworkConnectionRequired) return -1
            if (v.locale.language != "en") return -1
            val n = v.name.lowercase(Locale.getDefault())
            val isFemale = n.contains("female") || n.contains("-f-") || n.contains("_f_") ||
                n.contains("f-local") || n.contains("sfg") || n.contains("sfc")
            if (!isFemale) return -1
            val localeBonus = when (v.locale.country.uppercase()) {
                "IN" -> 200
                "US" -> 100
                "GB" -> 50
                else -> 0
            }
            return v.quality + localeBonus
        }
        val best = voices.maxByOrNull { score(it) }?.takeIf { score(it) >= 0 }
        if (best != null) {
            tts.voice = best
            android.util.Log.d("Naomi", "TTS voice: ${best.name} quality=${best.quality}")
        } else {
            tts.language = Locale("en", "IN")
            android.util.Log.d("Naomi", "TTS: no female voice found, using en-IN locale")
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ready || text.isBlank()) { onDone?.let { mainHandler.post(it) }; return }
        pendingOnDone = onDone
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "naomi-utterance")
    }

    fun stop() {
        pendingOnDone = null
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
