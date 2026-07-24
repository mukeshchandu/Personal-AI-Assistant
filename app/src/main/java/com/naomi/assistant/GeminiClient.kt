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

        IMPORTANT: You cannot perform device actions yourself (calling, texting, setting
        alarms/timers, opening apps, playing music, controlling settings). The phone handles
        those separately. So NEVER claim you did such an action or that one is in progress
        (do not say "calling…", "I've set it", etc.). If asked to do something you can't,
        briefly say you can't do that yet. You CAN answer questions and have conversations.
    """.trimIndent()

    /** Returns Naomi's reply text, or a friendly error string. Safe to call off the main thread. */
    suspend fun ask(userText: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext "I'm not connected to the cloud yet — my API key is missing."
        }
        try {
            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

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
                .header("X-goog-api-key", apiKey)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    android.util.Log.e("Naomi", "Gemini ${resp.code}: $raw")
                    return@withContext "Cloud request failed (${resp.code})."
                }
                parseReply(raw) ?: "I didn't get a clear answer."
            }
        } catch (e: java.net.UnknownHostException) {
            "I'm offline right now, so I can't reach the cloud for that."
        } catch (e: Exception) {
            "I couldn't reach the cloud: ${e.message}"
        }
    }

    /**
     * Asks Gemini to turn the user's words into a structured command (JSON).
     * Returns null when offline or on any failure, so the caller can fall back to
     * the offline keyword router.
     */
    suspend fun interpret(userText: String): JSONObject? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        try {
            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
            val body = JSONObject().apply {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", INTENT_PROMPT)))
                })
                put("contents", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", userText)))
                }))
                // Force pure-JSON output so we can parse it directly.
                put("generationConfig", JSONObject().put("response_mime_type", "application/json"))
            }.toString()

            val request = Request.Builder()
                .url(url)
                .header("X-goog-api-key", apiKey)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    android.util.Log.e("Naomi", "Interpret ${resp.code}: $raw")
                    return@withContext null
                }
                val text = parseReply(raw) ?: return@withContext null
                android.util.Log.d("Naomi", "Intent JSON: $text")
                JSONObject(text)
            }
        } catch (e: Exception) {
            android.util.Log.e("Naomi", "interpret failed: ${e.message}")
            null
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
        private const val MODEL = "gemini-flash-latest"

        private val INTENT_PROMPT = """
            You are the intent parser for Naomi, a phone voice assistant. Convert the user's
            (possibly messy, Indian-English) request into ONE JSON object. Output JSON only.

            Pick "action" and include only that action's fields:
              {"action":"call","name":"<contact>"}
              {"action":"send_sms","name":"<contact>","message":"<text>"}
              {"action":"whatsapp","name":"<contact>","message":"<text>"}
              {"action":"play_music","query":"<song or artist>","app":"<spotify|jiosaavn|>"}
              {"action":"music_control","control":"<pause|resume|next|previous>"}
              {"action":"set_timer","seconds":<integer>}
              {"action":"set_alarm","hour":<0-23>,"minute":<0-59>}
              {"action":"open_app","name":"<app name>"}
              {"action":"web_search","query":"<text>"}
              {"action":"navigate","destination":"<place>"}
              {"action":"maps_search","query":"<place or type of place>"}
              {"action":"open_url","url":"<website>"}
              {"action":"ride","destination":"<place>","app":"<uber|ola|rapido|>"}
              {"action":"order_food","query":"<food or restaurant>","app":"<swiggy|zomato|>"}
              {"action":"note","text":"<note text>"}
              {"action":"email","to":"<contact or address>","subject":"<text>","body":"<text>"}
              {"action":"weather","city":"<city>"}
              {"action":"flashlight","state":"<on|off>"}
              {"action":"battery"}
              {"action":"wifi"}
              {"action":"bluetooth"}
              {"action":"calendar_read"}
              {"action":"calendar_create","title":"<text>"}
              {"action":"voice_record_start"}
              {"action":"voice_record_stop"}
              {"action":"chat","reply":"<short, friendly spoken answer>"}

            Rules: Correct obvious mis-hearings of names/songs. Only use voice_record_start when
            the user explicitly says "start recording", "record my voice", "voice memo", or similar
            — NOT for any general use of the word "record" or "recording" in conversation.
            For any question, small talk, or
            anything not matching an action, use "chat" and write Naomi's spoken reply (one or two
            sentences, no markdown). Never invent a contact the user didn't mention.
        """.trimIndent()
    }
}
