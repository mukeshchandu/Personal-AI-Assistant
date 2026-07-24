package com.naomi.assistant

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * On-device LLM brain (Gemma 3 1B int4) via MediaPipe LLM Inference — fully offline,
 * free, no quota. Used as the conversational fallback when the offline keyword router
 * can't handle a request.
 *
 * Drop the pre-built model at:
 *   /sdcard/Android/data/com.naomi.assistant/files/gemma.task
 * Download from: kaggle.com/models/google/gemma-3/tfLite  (gemma3-1b-it-int4)
 * adb push gemma3-1b-it-int4.task /sdcard/Android/data/com.naomi.assistant/files/gemma.task
 */
class LocalBrain(private val context: Context) {

    @Volatile private var engine: LlmInference? = null

    /**
     * Prefer the bigger Gemma 3 4B model if it's been pushed (gemma-4b.task); otherwise use
     * the 1B (gemma.task). This makes upgrading a one-step drop-in: push the 4B file and
     * restart — no code change — and it falls back to 1B if the 4B isn't there.
     */
    fun modelFile(): File {
        val dir = context.getExternalFilesDir(null)
        val big = File(dir, MODEL_NAME_4B)
        return if (big.exists()) big else File(dir, MODEL_NAME)
    }

    /** True if the model file is on the device (so we can prefer it over the cloud). */
    val isAvailable: Boolean get() = modelFile().exists()

    private fun ensureLoaded(): LlmInference? {
        engine?.let { return it }
        val f = modelFile()
        if (!f.exists()) return null
        android.util.Log.d("Naomi", "Loading on-device model: ${f.name} (${f.length() / 1_000_000}MB)")
        return try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(f.absolutePath)
                .setMaxTokens(512)
                .build()
            LlmInference.createFromOptions(context, options).also { engine = it }
        } catch (e: Exception) {
            android.util.Log.e("Naomi", "LocalBrain load failed: ${e.message}")
            null
        }
    }

    /** Returns a spoken-style reply, or null if the model isn't available / failed. */
    suspend fun chat(userText: String): String? = withContext(Dispatchers.IO) {
        val llm = ensureLoaded() ?: return@withContext null
        try {
            // Gemma instruction-tuned chat template.
            val prompt = buildString {
                append("<start_of_turn>user\n")
                append("You are Naomi, a concise friendly voice assistant. ")
                append("Reply in one or two spoken sentences, no markdown.\n\n")
                append(userText)
                append("<end_of_turn>\n")
                append("<start_of_turn>model\n")
            }
            llm.generateResponse(prompt)?.trim()?.ifBlank { null }
        } catch (e: Exception) {
            android.util.Log.e("Naomi", "LocalBrain generate failed: ${e.message}")
            null
        }
    }

    /**
     * Tool-calling router: turns the user's words into ONE structured action (the same JSON
     * schema GeminiClient uses), or a "chat" answer from Gemma's own world knowledge.
     * Always returns an object — if the model doesn't emit valid JSON, its text becomes a chat
     * reply, so general questions still get answered.
     */
    suspend fun route(userText: String): JSONObject? = withContext(Dispatchers.IO) {
        val llm = ensureLoaded() ?: return@withContext null
        try {
            val prompt = buildString {
                append("<start_of_turn>user\n")
                append(ROUTE_PROMPT)
                append("\n\nUser request: ").append(userText).append("\n")
                append("Respond with only the JSON object.")
                append("<end_of_turn>\n")
                append("<start_of_turn>model\n")
            }
            val raw = llm.generateResponse(prompt)?.trim() ?: return@withContext null
            android.util.Log.d("Naomi", "Gemma intent: $raw")
            parseIntent(raw)
        } catch (e: Exception) {
            android.util.Log.e("Naomi", "LocalBrain route failed: ${e.message}")
            null
        }
    }

    /** Pull the first {...} block as JSON; otherwise treat the whole reply as spoken chat. */
    private fun parseIntent(raw: String): JSONObject {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start in 0 until end) {
            try { return JSONObject(raw.substring(start, end + 1)) } catch (_: Exception) {}
        }
        return JSONObject()
            .put("action", "chat")
            .put("reply", raw.ifBlank { "Sorry, I didn't catch that." })
    }

    companion object {
        const val MODEL_NAME = "gemma.task"        // Gemma 3 1B int4 (~529 MB)
        const val MODEL_NAME_4B = "gemma-4b.task"  // Gemma 3 4B int4 (~3 GB) — preferred if present

        private val ROUTE_PROMPT = """
            You are the brain of Naomi, a phone voice assistant. Decide what the user wants and
            output ONE JSON object, nothing else. Pick the action and include only its fields:
              {"action":"call","name":"<contact>"}
              {"action":"send_sms","name":"<contact>","message":"<text>"}
              {"action":"whatsapp","name":"<contact>","message":"<text>"}
              {"action":"play_music","query":"<song or artist>","app":""}
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
              {"action":"chat","reply":"<short spoken answer>"}
            For any question, fact, or small talk, use "chat" and answer it yourself in one or two
            spoken sentences (no markdown). Never invent a contact the user didn't name.
            Examples:
            User request: call mom -> {"action":"call","name":"mom"}
            User request: set a timer for five minutes -> {"action":"set_timer","seconds":300}
            User request: google the latest cricket score -> {"action":"web_search","query":"latest cricket score"}
            User request: take me to the airport -> {"action":"navigate","destination":"airport"}
            User request: find coffee near me -> {"action":"maps_search","query":"coffee"}
            User request: book an uber to the mall -> {"action":"ride","destination":"the mall","app":"uber"}
            User request: order food on swiggy -> {"action":"order_food","query":"","app":"swiggy"}
            User request: note that I parked on level 3 -> {"action":"note","text":"parked on level 3"}
            User request: turn on the flashlight -> {"action":"flashlight","state":"on"}
            User request: what's my battery -> {"action":"battery"}
            User request: weather in Mumbai -> {"action":"weather","city":"Mumbai"}
            User request: what's the capital of Japan -> {"action":"chat","reply":"It's Tokyo."}
        """.trimIndent()
    }
}
