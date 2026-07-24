package com.naomi.assistant

import android.content.Context
import org.json.JSONObject

/** What Naomi should say back, and whether she should immediately listen again. */
data class Reply(val text: String, val listenAgain: Boolean = false)

/**
 * Orchestrates understanding + action, plus multi-turn follow-ups:
 *
 *   1. If Naomi just asked something (e.g. "WhatsApp or text?"), feed this turn to that follow-up.
 *   2. Otherwise the FAST + FREE keyword router handles common commands (no Gemini call).
 *   3. If it can't, ask Gemini to interpret the words into a structured command, or chat.
 *
 * Both the keyword router and the LLM execute through the same CommandRouter.execute* methods.
 */
class AssistantBrain(context: Context, geminiApiKey: String, groqApiKey: String) {

    private val router = CommandRouter(context.applicationContext)
    private val localBrain = LocalBrain(context.applicationContext)
    private val groq = GroqClient(groqApiKey)
    private val gemini = GeminiClient(geminiApiKey)
    private val weather = WeatherClient()
    val memory = MemoryStore(context.applicationContext)

    /** When true (and online), hard/unknown requests route to the cloud model for a smarter
     *  answer. When false, everything stays on-device (Gemma). Set from the UI toggle. */
    @Volatile var smartMode = false

    // A follow-up awaiting the user's next reply (e.g. choosing the messaging app).
    private var pending: ((String) -> CommandRouter.Result)? = null
    private var pendingTries = 0

    // Rolling conversation memory (user, naomi) so follow-up answers resolve in context.
    private val history = ArrayDeque<Pair<String, String>>()
    private val maxHistory = 6

    // True when Naomi's last reply was an open question (not a structured Ask) and we're
    // waiting for the user's answer to route conversationally through Groq.
    private var expectingFollowUp = false
    private var followUpStreak = 0
    private val maxFollowUpStreak = 8

    /** True while Naomi is waiting for the user's next answer — structured OR conversational.
     *  The UI uses this to decide whether to reopen the mic after speaking. */
    val hasPending: Boolean get() = pending != null || expectingFollowUp

    /**
     * Public entry point. Runs the turn, then decides whether Naomi's reply is a question that
     * should reopen the mic, and records the exchange so the next turn has conversational context.
     */
    suspend fun handle(userText: String): Reply {
        val wasFollowUp = expectingFollowUp
        val reply = handleInternal(userText, wasFollowUp)

        // Any reply that reads as a question keeps the conversation going (mic reopens).
        val question = reply.listenAgain || isQuestion(reply.text)

        // A structured Ask manages its own re-listen via `pending`; only arm the conversational
        // path when there's no structured follow-up already queued.
        if (question && pending == null) {
            followUpStreak++
            // Guard against a runaway question loop if the model keeps asking.
            expectingFollowUp = followUpStreak <= maxFollowUpStreak
        } else {
            expectingFollowUp = false
            if (!question) followUpStreak = 0
        }

        // Remember this exchange for the next turn's context.
        history.addLast(userText to reply.text)
        while (history.size > maxHistory) history.removeFirst()

        return reply.copy(listenAgain = question || reply.listenAgain)
    }

    /** A reply is treated as a question (→ reopen mic) when it ends with a question mark. */
    private fun isQuestion(text: String): Boolean = text.trim().endsWith("?")

    private suspend fun handleInternal(userText: String, wasFollowUp: Boolean): Reply {
        pending?.let { onAnswer ->
            val result = onAnswer(userText)
            if (result is CommandRouter.Result.Ask) {
                pendingTries++
                if (pendingTries >= 5) { reset(); return Reply("Okay, let's leave it for now.") }
            }
            return resultToReply(result) { reset(); Reply("Okay, never mind.") }
        }

        val lower = userText.lowercase()

        // 0a. DAILY BRIEFING: greeting + date + weather + today's calendar, in one go.
        if (Regex("\\b(good morning|morning briefing|brief me|briefing|start my day|how'?s my day|what'?s my day|the rundown|catch me up)\\b")
                .containsMatchIn(lower)) {
            return Reply(dailyBriefing())
        }

        // 0b. WEATHER: needs a live network lookup (async), so it can't live in the sync router.
        if (Regex("\\b(weather|temperature|forecast)\\b").containsMatchIn(lower)) {
            return Reply(weather.forecast(extractCity(userText)))
        }

        // Feed persisted preferences into the router before each turn.
        router.preferredMusicApp = memory.get("music app") ?: ""

        // 1. FAST + FREE + PRIVATE: keyword router handles common commands on-device, no LLM call.
        val routed = router.tryHandle(userText)
        if (routed !is CommandRouter.Result.NotHandled) {
            // If a contact wasn't found, try Groq fuzzy name matching and retry once.
            if (routed is CommandRouter.Result.Handled &&
                routed.reply.startsWith("I couldn't find a contact named")) {
                val spokenName = Regex("named (.+)\\.").find(routed.reply)?.groupValues?.get(1)
                if (spokenName != null) {
                    val allNames = router.getAllContactNames()
                    val corrected = groq.resolveContact(spokenName, allNames)
                    if (corrected != null) {
                        android.util.Log.i("Naomi", "Fuzzy contact: \"$spokenName\" → \"$corrected\"")
                        val fixedText = userText.replace(
                            Regex("(?i)\\b${Regex.escape(spokenName)}\\b"), corrected
                        )
                        val retried = router.tryHandle(fixedText)
                        if (retried !is CommandRouter.Result.NotHandled)
                            return resultToReply(retried) { Reply("I couldn't do that.") }
                    }
                }
            }
            return resultToReply(routed) { Reply("I couldn't do that.") }
        }

        // 2. LLM TOOL-CALLING: the model picks the action (or answers from world knowledge).
        //    Smart mode + online → cloud (most capable); otherwise → on-device Gemma.
        //    Mid-conversation follow-ups ALSO go to Groq (with history) so contextual answers
        //    like "Balaji" or "the Taylor Swift one" resolve to the right action, even if smart
        //    mode is off. Routine commands are handled above, so they never leave the device.
        val useCloud = smartMode || wasFollowUp
        val ctx = history.toList()
        val intent: JSONObject? =
            (if (useCloud) groq.interpret(userText, ctx) ?: gemini.interpret(userText) else null)
                ?: localBrain.route(userText)
        if (intent != null) return dispatch(intent, userText)

        // 3. Last resort: cloud chat (with context) if online/allowed, else a graceful decline.
        if (useCloud) return Reply(groq.ask(userText, ctx))
        return Reply("Sorry, I didn't catch that — I can do timers, alarms, calls, music, messages, calendar, and answer questions.")
    }

    /**
     * A spoken morning briefing: time-appropriate greeting, today's date, the weather for the
     * user's stored home city (a "city"/"home" fact), and today's calendar events.
     * Reuses the existing weather + calendar plumbing so it stays consistent with those commands.
     */
    private suspend fun dailyBriefing(): String {
        val sb = StringBuilder()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        sb.append(
            when {
                hour < 12 -> "Good morning!"
                hour < 17 -> "Good afternoon!"
                else -> "Good evening!"
            }
        )
        val df = java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault())
        sb.append(" It's ${df.format(java.util.Date())}.")

        // Weather — only if the user has told Naomi their city (fact: "city" / "home city" / "home").
        val city = memory.get("city") ?: memory.get("home city") ?: memory.get("home")
        if (!city.isNullOrBlank()) {
            val w = weather.forecast(city)
            if (!w.startsWith("Which") && !w.startsWith("I couldn't find")) sb.append(" $w")
        }

        // Today's calendar.
        val cal = router.executeCalendarRead()
        if (cal is CommandRouter.Result.Handled && cal.reply.isNotBlank()) sb.append(" ${cal.reply}")

        return sb.toString()
    }

    /** Words after "in/at/for", stripped of weather/time filler — the city for a weather query. */
    private fun extractCity(text: String): String {
        val m = Regex("\\b(?:in|at|for)\\s+([a-zA-Z .]+)").find(text.lowercase()) ?: return ""
        return m.groupValues[1]
            .replace(Regex("\\b(weather|temperature|forecast|today|tomorrow|now|right now|please)\\b"), "")
            .replace(Regex("\\s+"), " ").trim()
    }

    /** Executes a structured command from an LLM (see GeminiClient.INTENT_PROMPT). */
    private suspend fun dispatch(cmd: JSONObject, original: String): Reply {
        if (cmd.optString("action") == "chat") {
            return Reply(cmd.optString("reply").ifBlank { "Sorry, could you say that again?" })
        }
        if (cmd.optString("action") == "weather") {
            return Reply(weather.forecast(cmd.optString("city")))
        }
        val result = when (cmd.optString("action")) {
            "call" -> router.executeCall(memory.resolveContact(cmd.optString("name")))
            "whatsapp_call" -> router.executeWhatsAppCall(memory.resolveContact(cmd.optString("name")), video = false)
            "whatsapp_video" -> router.executeWhatsAppCall(memory.resolveContact(cmd.optString("name")), video = true)
            "send_sms" -> router.executeMessage(memory.resolveContact(cmd.optString("name")), cmd.optString("message"), MsgChannel.SMS)
            "whatsapp" -> router.executeMessage(memory.resolveContact(cmd.optString("name")), cmd.optString("message"), MsgChannel.WHATSAPP)
            "play_music" -> router.executePlay(cmd.optString("query"), cmd.optString("app"))
            "music_control" -> router.executeMusicControl(cmd.optString("control"))
            "set_timer" -> router.executeTimer(cmd.optInt("seconds"))
            "set_alarm" -> router.executeAlarm(cmd.optInt("hour"), cmd.optInt("minute"))
            "open_app" -> router.executeOpenApp(cmd.optString("name"))
            "web_search" -> router.executeWebSearch(cmd.optString("query"))
            "navigate" -> router.executeNavigate(cmd.optString("destination"))
            "maps_search" -> router.executeMapsSearch(cmd.optString("query"))
            "open_url" -> router.executeOpenUrl(cmd.optString("url"))
            "ride" -> router.executeRide(cmd.optString("destination"), cmd.optString("app"))
            "order_food" -> router.executeFood(cmd.optString("query"), cmd.optString("app"))
            "note" -> router.executeNote(cmd.optString("text"))
            "email" -> router.executeEmail(memory.resolveContact(cmd.optString("to")), cmd.optString("subject"), cmd.optString("body"))
            "flashlight" -> router.executeTorch(cmd.optString("state", "on") != "off")
            "battery" -> router.executeBatteryLevel()
            "wifi" -> router.executeWifiSettings()
            "bluetooth" -> router.executeBluetooth(cmd.optString("state", "on") != "off")
            "calendar_read" -> router.executeCalendarRead()
            "calendar_create" -> router.executeCalendarCreate(cmd.optString("title"))
            "voice_record_start" -> router.executeStartRecording()
            "voice_record_stop" -> router.executeStopRecording()
            "course_check" -> router.executeCourseCheck()
            else -> router.tryHandle(original) // unknown action → try the keyword router
        }
        return resultToReply(result) { Reply("I'm not sure how to do that yet.") }
    }

    private fun resultToReply(result: CommandRouter.Result, onNotHandled: () -> Reply): Reply =
        when (result) {
            is CommandRouter.Result.Handled -> { reset(); Reply(result.reply) }
            is CommandRouter.Result.Ask -> {
                pending = result.onAnswer
                Reply(result.prompt, listenAgain = true)
            }
            CommandRouter.Result.NotHandled -> { reset(); onNotHandled() }
        }

    private fun reset() { pending = null; pendingTries = 0; expectingFollowUp = false }

    /** Full conversational reset — drop any pending follow-up AND the conversation history.
     *  Called when the user backs out / leaves, so the next turn starts fresh. */
    fun cancel() { reset(); followUpStreak = 0; history.clear() }
}
