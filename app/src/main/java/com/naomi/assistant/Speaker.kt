package com.naomi.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Wraps Android's built-in TextToSpeech — Naomi's "mouth".
 * Zero dependencies, works offline once the system voice data is installed.
 *
 * Later upgrade: Piper for a more natural / custom voice.
 */
class Speaker(context: Context) {

    private var ready = false
    private val tts = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
        }
    }.also { it.language = Locale.getDefault() }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "naomi-utterance")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
