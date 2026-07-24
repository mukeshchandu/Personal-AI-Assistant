package com.naomi.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Wraps Android's built-in SpeechRecognizer — Naomi's "ears".
 *
 * Free, on-device-capable (works offline if an offline language pack is installed),
 * and needs no API key.
 *
 * Important: we keep ONE recognizer alive for the whole app and just reset it between
 * turns. Destroying + recreating it on every tap caused intermittent "server disconnected"
 * (error 11) on the first tap after a turn, because the old instance was still tearing down.
 *
 * Later upgrade: swap this for Vosk (the engine Dicio uses) for guaranteed offline STT.
 */
class VoiceInput(context: Context) {

    private var onResult: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var triedFallback = false
    private var triedClientRetry = false
    private val handler = Handler(Looper.getMainLooper())

    /** Fires when the user actually starts talking — used to cut off Naomi's TTS (barge-in). */
    var onSpeechStart: (() -> Unit)? = null

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            if (text.isNullOrBlank()) onError?.invoke("I didn't catch that.")
            else onResult?.invoke(text)
        }

        override fun onError(error: Int) {
            // ERROR_CLIENT (5): mic not fully released yet — retry after a short pause.
            if (error == SpeechRecognizer.ERROR_CLIENT && !triedClientRetry) {
                triedClientRetry = true
                recognizer?.cancel()
                handler.postDelayed({ recognizer?.startListening(buildIntent(useIndianEnglish = true)) }, 350)
                return
            }
            // If Indian English isn't installed (12/13), retry once in device default language.
            if (error in intArrayOf(12, 13) && !triedFallback) {
                triedFallback = true
                recognizer?.cancel()
                recognizer?.startListening(buildIntent(useIndianEnglish = false))
                return
            }
            onError?.invoke(describeError(error))
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { onSpeechStart?.invoke() }

        // Unused callbacks
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context))
            SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(listener)
            }
        else null

    private fun buildIntent(useIndianEnglish: Boolean) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // Prefer Indian English for accent accuracy; fall back to device default if absent.
            if (useIndianEnglish) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE)
            }
        }

    /**
     * Start listening. [onResult] fires with the recognised text, [onError] with a message.
     * Both are called on the main thread.
     */
    fun listen(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (recognizer == null) {
            onError("Speech recognition isn't available on this device.")
            return
        }
        this.onResult = onResult
        this.onError = onError
        triedFallback = false
        triedClientRetry = false
        // Reset any lingering state from the previous turn, then start fresh.
        recognizer.cancel()
        recognizer.startListening(buildIntent(useIndianEnglish = true))
    }

    /** Stop any in-flight recognition and drop callbacks so a late result can't fire after a reset. */
    fun cancel() {
        onResult = null
        onError = null
        recognizer?.cancel()
    }

    fun destroy() {
        recognizer?.destroy()
    }

    private companion object {
        // Change to your preferred BCP-47 tag, e.g. "en-US", "hi-IN" for Hindi.
        const val LANGUAGE = "en-IN"
    }

    private fun describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "I didn't understand that."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "I need microphone permission."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error during recognition."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        11 -> "One sec — try the mic again." // 11 = SERVER_DISCONNECTED
        12, 13 -> "My speech language pack isn't available — your phone may be low on storage."
        else -> "Speech recognition error ($code)."
    }
}
