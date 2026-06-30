package com.naomi.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Wraps Android's built-in SpeechRecognizer.
 *
 * This is the "ears" of Naomi. It is free, on-device-capable (works offline if the
 * user has installed an offline language pack in system settings), and needs no API key.
 *
 * Later upgrade: swap this for Vosk (the engine Dicio uses) for guaranteed offline STT.
 */
class VoiceInput(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    /**
     * Start listening. [onResult] fires with the recognised text, [onError] with a message.
     * Both are called on the main thread.
     */
    fun listen(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition isn't available on this device.")
            return
        }

        // Recreate per-use; SpeechRecognizer is single-shot and finicky if reused.
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                    if (text.isNullOrBlank()) onError("I didn't catch that.")
                    else onResult(text)
                }

                override fun onError(error: Int) = onError(describeError(error))

                // Unused callbacks
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            // Prefer offline recognition when a language pack is available.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer?.startListening(intent)
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "I didn't understand that."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "I need microphone permission."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error during recognition."
        else -> "Speech recognition error ($code)."
    }
}
