package com.naomi.assistant

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The OFFLINE brain.
 *
 * Before we ever call the cloud, we try to handle the request locally with simple
 * pattern matching + Android system intents. These work with no internet and no API key:
 *   - "set a timer for 10 minutes"
 *   - "set an alarm for 7 30"
 *   - "what time is it" / "what's the date"
 *   - "open <app>"  (best-effort by app name)
 *
 * If nothing matches, we return NotHandled and the caller falls back to Gemini.
 *
 * Keep this list growing — every command you add here is one more thing Naomi can do offline.
 */
class CommandRouter(private val context: Context) {

    sealed interface Result {
        data class Handled(val reply: String) : Result
        data object NotHandled : Result
    }

    fun tryHandle(rawInput: String): Result {
        val input = rawInput.lowercase(Locale.getDefault()).trim()

        return when {
            input.contains("timer") -> handleTimer(input)
            input.contains("alarm") -> handleAlarm(input)
            input.matches(Regex(".*\\bwhat('?s| is)? the time\\b.*")) ||
                input.contains("what time is it") -> Result.Handled(currentTime())
            input.contains("what") && input.contains("date") -> Result.Handled(currentDate())
            input.startsWith("open ") -> handleOpenApp(input.removePrefix("open ").trim())
            else -> Result.NotHandled
        }
    }

    private fun handleTimer(input: String): Result {
        val seconds = parseDurationSeconds(input)
            ?: return Result.Handled("How long should the timer be?")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(intent, "Timer set for ${humanDuration(seconds)}.")
    }

    private fun handleAlarm(input: String): Result {
        val (hour, minute) = parseClockTime(input)
            ?: return Result.Handled("What time should I set the alarm for?")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val label = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
        return launch(intent, "Alarm set for $label.")
    }

    private fun handleOpenApp(name: String): Result {
        if (name.isBlank()) return Result.NotHandled
        val pm = context.packageManager
        // Best-effort: match an installed app whose label contains the spoken name.
        val match = pm.getInstalledApplications(0).firstOrNull { appInfo ->
            pm.getApplicationLabel(appInfo).toString().lowercase(Locale.getDefault())
                .contains(name)
        } ?: return Result.Handled("I couldn't find an app called $name.")

        val launch = pm.getLaunchIntentForPackage(match.packageName)
            ?: return Result.Handled("I can't open $name.")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return Result.Handled("Opening ${pm.getApplicationLabel(match)}.")
    }

    private fun launch(intent: Intent, reply: String): Result = try {
        context.startActivity(intent)
        Result.Handled(reply)
    } catch (e: Exception) {
        Result.Handled("I couldn't do that: ${e.message}")
    }

    // --- tiny parsers (intentionally simple; improve as needed) ---

    /** "10 minutes", "1 hour", "30 seconds" -> total seconds. */
    private fun parseDurationSeconds(input: String): Int? {
        val m = Regex("(\\d+)\\s*(hour|hr|minute|min|second|sec)").find(input) ?: return null
        val value = m.groupValues[1].toIntOrNull() ?: return null
        return when {
            m.groupValues[2].startsWith("hour") || m.groupValues[2] == "hr" -> value * 3600
            m.groupValues[2].startsWith("min") -> value * 60
            else -> value
        }
    }

    /** "7 30", "seven thirty"(digits only for now), "at 6" -> (hour, minute) 24h. */
    private fun parseClockTime(input: String): Pair<Int, Int>? {
        val m = Regex("(\\d{1,2})\\s*[:\\s]\\s*(\\d{2})").find(input)
        if (m != null) {
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h in 0..23 && min in 0..59) return h to min
        }
        // bare hour like "set an alarm for 6"
        val h = Regex("for\\s+(\\d{1,2})\\b").find(input)?.groupValues?.get(1)?.toIntOrNull()
        if (h != null && h in 0..23) return h to 0
        return null
    }

    private fun humanDuration(seconds: Int): String = when {
        seconds % 3600 == 0 -> "${seconds / 3600} hour(s)"
        seconds % 60 == 0 -> "${seconds / 60} minute(s)"
        else -> "$seconds seconds"
    }

    private fun currentTime(): String =
        "It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) + "."

    private fun currentDate(): String =
        "Today is " + SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
            .format(Calendar.getInstance().time) + "."
}
