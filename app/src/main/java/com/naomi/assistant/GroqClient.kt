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
 * Cloud brain via Groq (OpenAI-compatible chat completions) — free, fast, generous limits.
 * Used in "smart mode" for the tool-calling router and open-ended questions.
 *
 * Key is read from BuildConfig.GROQ_API_KEY (set in local.properties, git-ignored).
 */
class GroqClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val chatSystem = """
        You are Naomi, a concise, friendly voice assistant on a phone. Replies are spoken aloud,
        so keep them short and natural — one or two sentences. No markdown, lists, or emoji.
        You cannot perform device actions yourself (calling, texting, alarms, opening apps); the
        phone handles those separately, so never claim you did one. You CAN answer questions.
    """.trimIndent()

    /** Open-ended spoken reply, or a friendly error string.
     *  [history] is the recent conversation as (user, naomi) pairs so replies stay in context. */
    suspend fun ask(userText: String, history: List<Pair<String, String>> = emptyList()): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "My Groq key is missing."
        try {
            val content = complete(chatSystem, userText, jsonMode = false, history = history)
            content?.trim()?.ifBlank { null } ?: "I didn't get a clear answer."
        } catch (e: java.net.UnknownHostException) {
            "I'm offline right now, so I can't reach the cloud for that."
        } catch (e: Exception) {
            "I couldn't reach the cloud: ${e.message}"
        }
    }

    /**
     * Fuzzy-matches a spoken contact name against a list of real contact names.
     * Returns the best-matching real name, or null if nothing is close enough.
     * Used when the exact LIKE query finds no contacts (e.g. "surbhi" → "surabhi").
     */
    suspend fun resolveContact(spoken: String, candidates: List<String>): String? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank() || candidates.isEmpty()) return@withContext null
            try {
                val list = candidates.take(200).joinToString(", ")
                val system = "You are a contact name resolver. Given a spoken (possibly mis-heard) name and a real contact list, return ONLY the exact name from the list that best matches the spoken name. If nothing is close enough, return NONE."
                val user = "Spoken: \"$spoken\"\nContacts: $list"
                val result = complete(system, user, jsonMode = false)?.trim()
                if (result.isNullOrBlank() || result == "NONE") null else result
            } catch (e: Exception) {
                null
            }
        }

    /** Turns the user's words into a structured command (JSON), or null on failure.
     *  [history] lets follow-up answers resolve in context (e.g. after Naomi asked "who?"). */
    suspend fun interpret(userText: String, history: List<Pair<String, String>> = emptyList()): JSONObject? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        try {
            val text = complete(INTENT_PROMPT, userText, jsonMode = true, history = history) ?: return@withContext null
            android.util.Log.d("Naomi", "Groq intent: $text")
            JSONObject(text)
        } catch (e: Exception) {
            android.util.Log.e("Naomi", "Groq interpret failed: ${e.message}")
            null
        }
    }

    private fun complete(
        system: String,
        user: String,
        jsonMode: Boolean,
        history: List<Pair<String, String>> = emptyList()
    ): String? {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
        // Replay recent turns so the model has conversational context for the current one.
        for ((u, a) in history) {
            if (u.isNotBlank()) messages.put(JSONObject().put("role", "user").put("content", u))
            if (a.isNotBlank()) messages.put(JSONObject().put("role", "assistant").put("content", a))
        }
        messages.put(JSONObject().put("role", "user").put("content", user))

        val body = JSONObject().apply {
            put("model", MODEL)
            put("temperature", if (jsonMode) 0.0 else 0.4)
            if (jsonMode) put("response_format", JSONObject().put("type", "json_object"))
            put("messages", messages)
        }.toString()

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                android.util.Log.e("Naomi", "Groq ${resp.code}: $raw")
                return null
            }
            return JSONObject(raw)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                ?.ifBlank { null }
        }
    }

    companion object {
        // Solid general model with good JSON-mode support and generous free limits.
        private const val MODEL = "llama-3.3-70b-versatile"

        private val INTENT_PROMPT = """
            You are the intent parser for Naomi, a phone voice assistant. Convert the user's
            (possibly messy, Indian-English) request into ONE JSON object. Output JSON only.

            Pick "action" and include only that action's fields:
              {"action":"call","name":"<contact>"}
              {"action":"whatsapp_call","name":"<contact>"}
              {"action":"whatsapp_video","name":"<contact>"}
              {"action":"send_sms","name":"<contact>","message":"<text>"}
              {"action":"whatsapp","name":"<contact>","message":"<text>"}
              {"action":"play_music","query":"<song or artist>","app":"<spotify|jiosaavn|gaana|wynk|youtube|>"}
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
              {"action":"bluetooth","state":"<on|off>"}
              {"action":"calendar_read"}
              {"action":"calendar_create","title":"<text>"}
              {"action":"voice_record_start"}
              {"action":"voice_record_stop"}
              {"action":"course_check"}
              {"action":"chat","reply":"<short, friendly spoken answer>"}

            Extra rules:
            - CALLS: a plain phone call is "call". A WhatsApp voice/audio call is "whatsapp_call".
              A video call (or a WhatsApp video call) is "whatsapp_video". Examples:
              "call mom" → call; "whatsapp call mom" / "audio call mom on whatsapp" → whatsapp_call;
              "video call mom" / "make a whatsapp video call to mom" → whatsapp_video.
            - CONVERSATION CONTEXT: earlier turns may show Naomi asked a follow-up question
              ("Who should I call?", "Which song?", "WhatsApp or text?"). When the latest user
              message is the ANSWER to such a question, emit the FULL action it completes — carry
              over the details from earlier turns. E.g. Naomi asked "Who should I call?" and the
              user says "Balaji" → {"action":"call","name":"Balaji"}. Naomi asked "Which song?"
              and the user says "Blinding Lights" → {"action":"play_music","query":"Blinding Lights"}.
            - "course_check": use when user asks about new/available courses, IITM add/drop, or course watcher.
            - "play_music": always try to correct song/artist names that sound Indian or Hindi.
            - For call/message, correct obviously mis-heard Indian names (surbhi→surabhi, balaji→balaji, etc.).

            Rules: Correct obvious mis-hearings of names/songs. Only use voice_record_start when
            the user explicitly says "start recording", "record my voice", "voice memo", or similar
            — NOT for any general use of the word "record" or "recording" in conversation.
            For any question, small talk, or
            anything not matching an action, use "chat" and write Naomi's spoken reply (one or two
            sentences, no markdown). Never invent a contact the user didn't mention.
        """.trimIndent()
    }
}
