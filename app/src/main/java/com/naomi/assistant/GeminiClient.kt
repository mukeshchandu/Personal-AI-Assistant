package com.naomi.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The CLOUD brain: calls the Gemini API for anything the offline router can't handle.
 *
 * Uses a plain REST call (no SDK) to keep dependencies minimal. The API key is read from
 * BuildConfig.GEMINI_API_KEY, which you set in local.properties (see README) — never hard-code it.
 *
 * Later upgrade: on-device Gemini Nano (AICore / ML Kit GenAI) so this also works offline.
 */
class GeminiClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val systemPrompt = """
        You are Naomi, a concise, friendly voice assistant on a phone.
        Replies are spoken aloud, so keep them short and natural — one or two sentences.
        Do not use markdown, lists, or emoji.
    """.trimIndent()

    /** Returns Naomi's reply text, or a friendly error string. Safe to call off the main thread. */
    suspend fun ask(userText: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext "I'm not connected to the cloud yet — my API key is missing."
        }
        try {
            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey"

            val body = JSONObject().apply {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                })
                put("contents", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", userText)))
                }))
            }.toString()

            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext "Cloud request failed (${resp.code})."
                }
                parseReply(raw) ?: "I didn't get a clear answer."
            }
        } catch (e: Exception) {
            "I couldn't reach the cloud: ${e.message}"
        }
    }

    private fun parseReply(raw: String): String? = try {
        JSONObject(raw)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.trim()
            ?.ifBlank { null }
    } catch (e: Exception) {
        null
    }

    companion object {
        // Change to whatever Gemini model you prefer / have access to.
        private const val MODEL = "gemini-2.5-flash"
    }
}
