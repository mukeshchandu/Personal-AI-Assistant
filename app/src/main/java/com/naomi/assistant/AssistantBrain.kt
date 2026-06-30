package com.naomi.assistant

import android.content.Context

/**
 * Orchestrates the two brains. This is the heart of the hybrid design you asked for:
 *
 *   1. Try the OFFLINE router first (timers, alarms, time/date, open app).
 *   2. If it can't handle the request, fall back to the CLOUD (Gemini).
 *
 * So Naomi's core utility keeps working with no internet, and gets smart when online.
 */
class AssistantBrain(context: Context, geminiApiKey: String) {

    private val router = CommandRouter(context.applicationContext)
    private val gemini = GeminiClient(geminiApiKey)

    /** Returns the text Naomi should speak back. */
    suspend fun handle(userText: String): String {
        return when (val result = router.tryHandle(userText)) {
            is CommandRouter.Result.Handled -> result.reply
            CommandRouter.Result.NotHandled -> gemini.ask(userText)
        }
    }
}
