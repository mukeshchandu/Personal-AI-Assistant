package com.naomi.assistant

import android.Manifest
import android.app.SearchManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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

    /**
     * Preferred music app — set from MemoryStore before each routing call.
     * When set (e.g. "Wynk"), play requests without an explicit app target use it.
     */
    var preferredMusicApp: String = ""

    sealed interface Result {
        data class Handled(val reply: String) : Result
        data object NotHandled : Result

        /**
         * Naomi should speak [prompt], listen again, and feed the user's reply to [onAnswer],
         * which returns the next Result (another Ask to re-prompt, or a Handled to finish).
         * Used for follow-ups like "WhatsApp or text?" before sending.
         */
        data class Ask(val prompt: String, val onAnswer: (String) -> Result) : Result
    }

    fun tryHandle(rawInput: String): Result {
        // Speech-to-text sometimes spells numbers out ("two minutes"); turn those into
        // digits ("2 minutes") so the parsers below work either way.
        val input = wordsToDigits(rawInput.lowercase(Locale.getDefault()).trim())
        android.util.Log.d("Naomi", "Router input: \"$input\"")

        return when {
            input.contains("timer") -> handleTimer(input)
            input.contains("alarm") -> handleAlarm(input)
            input.matches(Regex(".*\\bwhat('?s| is)? the time\\b.*")) ||
                input.contains("what time is it") -> Result.Handled(currentTime())
            input.contains("what") && input.contains("date") -> Result.Handled(currentDate())

            // --- music ---
            input.contains("pause") || input.contains("stop the music") || input.contains("stop music") ->
                mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused.")
            input.contains("skip") || (input.contains("next") &&
                (input.contains("song") || input.contains("track") || input.contains("music"))) ->
                mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "Skipping ahead.")
            input.contains("previous") ->
                mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Going back.")
            input.contains("resume") || input == "play" || input == "play music" ->
                mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Playing.")
            Regex("\\bplay\\b").containsMatchIn(input) -> handlePlay(input)

            // --- WhatsApp calls (must precede messaging, which also matches "whatsapp") ---
            Regex("\\bvideo\\s*call\\b").containsMatchIn(input) ||
                ((input.contains("whatsapp") || input.contains("whats app")) && input.contains("video")) ->
                handleWhatsAppCall(extractCallTarget(input), video = true)
            (input.contains("whatsapp") || input.contains("whats app")) &&
                Regex("\\bcall\\b").containsMatchIn(input) ->
                handleWhatsAppCall(extractCallTarget(input), video = false)

            // --- messaging ---
            Regex("\\b(message|text|whatsapp|dm)\\b").containsMatchIn(input) -> handleMessage(input)

            // --- calendar ---
            input.contains("calendar") || input.contains("my schedule") ||
                (input.contains("event") && input.contains("today")) -> handleCalendarRead()
            input.contains("remind me to") || input.contains("add event") ||
                input.contains("create event") || input.contains("schedule a") ->
                handleCalendarCreate(input)

            // --- course watcher ---
            input.contains("course") && (input.contains("check") || input.contains("new") ||
                input.contains("added") || input.contains("available") || input.contains("any") ||
                input.contains("watcher")) ->
                executeCourseCheck()

            // --- system settings by voice ("take me to accessibility settings") ---
            // Requires an opener verb so it won't grab "settings" in unrelated commands.
            input.contains("settings") && (input.contains("open") || input.contains("go to") ||
                input.contains("take me to") || input.contains("show") || input.contains("launch") ||
                input.contains("bring up")) -> handleSettings(input)

            // --- navigation / maps / web search (everyday, offline system intents) ---
            input.contains("navigate to") || input.contains("directions to") ||
                input.contains("take me to") || input.contains("drive to") ->
                executeNavigate(firstAfter(input, "navigate to", "directions to", "take me to", "drive to"))
            input.contains("near me") || input.contains("nearby") ->
                executeMapsSearch(cleanPlace(input))
            (input.startsWith("google ") || input == "google" || input.contains("search for") ||
                Regex("\\b(search the web|look up|look it up)\\b").containsMatchIn(input) ||
                Regex("\\bsearch\\b").containsMatchIn(input)) &&
                // Not a "search X in <app>" request — those are handled by the app below.
                !Regex("\\b(swiggy|zomato|uber|ola|rapido|youtube|amazon|flipkart)\\b").containsMatchIn(input) ->
                executeWebSearch(firstAfter(input, "search for", "google", "look up", "search"))

            // --- rides / food / notes / email / device controls ---
            input.contains("uber") || input.contains("ola") || input.contains("rapido") ||
                ((input.contains("book") || input.contains("get me")) &&
                    (input.contains("cab") || input.contains("ride") || input.contains("taxi"))) ->
                handleRide(input)
            input.contains("swiggy") || input.contains("zomato") ||
                (input.contains("order") && input.contains("food")) ->
                handleFood(input)
            input.contains("take a note") || input.contains("make a note") ||
                input.contains("note that") || input.contains("write down") || input.contains("note down") ->
                handleNote(input)
            Regex("\\bemail\\b").containsMatchIn(input) || input.contains("send a mail") ->
                handleEmail(input)
            (input.contains("stop recording") || input.contains("stop the recording") ||
                input.contains("end recording") || input.contains("finish recording")) ->
                executeStopRecording()
            (input.contains("start recording") || input.contains("record my voice") ||
                input.contains("voice memo") || input.contains("voice note") ||
                input.contains("record a voice") || input.contains("start a recording")) ->
                executeStartRecording()
            input.contains("flashlight") || input.contains("flash light") || input.contains("torch") ->
                executeTorch(!(input.contains("off")))
            input.contains("battery") || input.contains("how much charge") || input.contains("charge left") ->
                executeBatteryLevel()
            Regex("\\bwi-?fi\\b").containsMatchIn(input) -> executeWifiSettings()
            input.contains("bluetooth") -> executeBluetooth(!input.contains("off"))

            // --- daily: sound & focus ---
            input.contains("volume") || input.contains("louder") || input.contains("quieter") ||
                input.contains("mute") -> handleVolume(input)
            input.contains("do not disturb") || input.contains("ringer") ||
                input.contains("silent mode") || input.contains("vibrate mode") ||
                input == "silent" || input == "vibrate" -> handleRinger(input)

            // Match "call" / "open" anywhere, so "hey can you call balaji" still works.
            Regex("\\bcall\\b").containsMatchIn(input) -> handleCall(extractAfter(input, "call"))
            Regex("\\bopen\\b").containsMatchIn(input) -> handleOpenApp(extractAfter(input, "open"))
            else -> Result.NotHandled
        }
    }

    private fun handleTimer(input: String): Result =
        parseDurationSeconds(input)?.let { executeTimer(it) }
            ?: Result.Handled("How long should the timer be?")

    private fun handleAlarm(input: String): Result =
        parseClockTime(input)?.let { (h, m) -> executeAlarm(h, m) }
            ?: Result.Handled("What time should I set the alarm for?")

    // --- public structured actions (used by both the keyword router and the LLM) ---

    fun executeTimer(seconds: Int): Result {
        if (seconds <= 0) return Result.Handled("How long should the timer be?")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(intent, "Timer set for ${humanDuration(seconds)}.")
    }

    fun executeAlarm(hour: Int, minute: Int): Result {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val label = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
        return launch(intent, "Alarm set for $label.")
    }

    fun executeCall(name: String): Result = handleCall(name)

    fun executeWhatsAppCall(name: String, video: Boolean): Result = handleWhatsAppCall(name, video)

    fun executeOpenApp(name: String): Result = handleOpenApp(name)

    fun executeCalendarRead(): Result = handleCalendarRead()

    fun executeMusicControl(control: String): Result = when (control.lowercase()) {
        "pause" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused.")
        "next" -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "Skipping ahead.")
        "previous" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Going back.")
        else -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Playing.")
    }

    fun executePlay(query: String, app: String): Result {
        if (query.isBlank()) return mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Playing.")

        // Explicit app target
        val (explicitPkg, where) = when {
            app.contains("spotify") -> "com.spotify.music" to " on Spotify"
            app.contains("saavn") || app.contains("jio") -> "com.jio.media.jiobeats" to " on JioSaavn"
            app.contains("gaana") -> "com.gaana" to " on Gaana"
            app.contains("wynk") -> "com.wynk.music" to " on Wynk"
            app.contains("youtube") -> "com.google.android.apps.youtube.music" to " on YouTube Music"
            app.contains("resso") -> "com.resso.app" to " on Resso"
            else -> null to ""
        }

        val targetPkg = explicitPkg ?: run {
            // Honour the user's stored "my music app is X" preference first.
            val pref = preferredMusicApp.lowercase()
            val prefPkg = if (pref.isNotBlank()) APP_TO_PKG.entries
                .firstOrNull { (name, _) -> pref.contains(name) || name.contains(pref) }?.value
            else null

            // Fall back to first installed app in preference order.
            prefPkg ?: APP_TO_PKG.values
                .firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }
        }

        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, query)
            targetPkg?.let { setPackage(it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(intent, "Playing $query$where.")
    }

    /** Google (or default) web search for [query]. Falls back to the browser if no search app. */
    fun executeWebSearch(query: String): Result {
        if (query.isBlank()) return Result.Handled("What should I search for?")
        val search = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(search)
            Result.Handled("Searching for $query.")
        } catch (e: Exception) {
            executeOpenUrl("https://www.google.com/search?q=${Uri.encode(query)}", "Searching for $query.")
        }
    }

    /** Turn-by-turn navigation to [destination] in Google Maps (falls back to a map pin). */
    fun executeNavigate(destination: String): Result {
        if (destination.isBlank()) return Result.Handled("Where do you want to go?")
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}")).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(nav)
            Result.Handled("Starting navigation to $destination.")
        } catch (e: Exception) {
            launch(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "Opening directions to $destination."
            )
        }
    }

    /** Find places on the map: "coffee near me", "petrol pumps", a landmark, etc. */
    fun executeMapsSearch(query: String): Result {
        if (query.isBlank()) return Result.Handled("What should I look for on the map?")
        return launch(
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            "Showing $query on the map."
        )
    }

    /** Open a website in the browser. */
    fun executeOpenUrl(url: String, reply: String = "Opening that link."): Result {
        val fixed = if (url.startsWith("http")) url else "https://$url"
        return launch(
            Intent(Intent.ACTION_VIEW, Uri.parse(fixed)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            reply
        )
    }

    /** Book a ride — opens Uber/Ola/Rapido with the dropoff pre-filled where supported. */
    fun executeRide(destination: String, app: String): Result {
        return when {
            // Rapido/Ola have no reliable public deep link for the drop location, so we open
            // the app and drive the UI with the Accessibility service: tap the drop field, type.
            app.contains("rapido") -> openRideWithDestination(
                "com.rapido.passenger", "Rapido", destination,
                "where to|drop|enter drop|drop location|where are you going|destination|search"
            )
            app.contains("ola") -> openRideWithDestination(
                "com.olacabs.customer", "Ola", destination,
                "where to|drop|enter drop|drop location|destination|search"
            )
            else -> { // Uber — supports a formatted_address deep link
                if (destination.isNotBlank()) {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(
                                "uber://?action=setPickup&pickup=my_location&dropoff[formatted_address]=${Uri.encode(destination)}"
                            )).setPackage("com.ubercab").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        return Result.Handled("Opening Uber to $destination.")
                    } catch (_: Exception) {}
                }
                openAppOrStore("com.ubercab", "Uber")
            }
        }
    }

    /**
     * Opens a ride app; if a destination is given and the Accessibility service is on, it drives
     * the app's own UI — taps the drop-location field and types the destination — since these
     * apps expose no external deep link for the drop. Without accessibility, it just opens the app.
     */
    private fun openRideWithDestination(
        pkg: String, label: String, destination: String, tapTargets: String
    ): Result {
        val installed = context.packageManager.getLaunchIntentForPackage(pkg) != null
        val opened = openAppOrStore(pkg, label)
        if (installed && destination.isNotBlank() && opened is Result.Handled) {
            if (WhatsAppSender.isEnabled) {
                // Give the app time to cold-start before poking its search field.
                val h = Handler(Looper.getMainLooper())
                h.postDelayed({ WhatsAppSender.perform("tap", tapTargets) }, 4200)
                h.postDelayed({ WhatsAppSender.perform("type", destination) }, 6000)
                return Result.Handled("Opening $label — setting your drop to $destination.")
            }
            return Result.Handled(
                "Opening $label. Turn on Naomi in Accessibility and I can fill the drop for you next time."
            )
        }
        return opened
    }

    /** Open a food-delivery app (Swiggy/Zomato); if a dish is named, search for it in-app. */
    fun executeFood(query: String, app: String): Result {
        val (label, result) = if (app.contains("zomato"))
            "Zomato" to openFirstInstalled(listOf("com.application.zomato", "com.application.zomato.district"), "Zomato")
        else
            "Swiggy" to openFirstInstalled(listOf("in.swiggy.android", "com.swiggy.android"), "Swiggy")

        if (result is Result.Handled && query.isNotBlank() && WhatsAppSender.isEnabled) {
            // Best-effort in-app search: wait for the app to load, tap the search box, type the dish.
            val h = Handler(Looper.getMainLooper())
            val searchTargets = "search|search for|find|what's|craving|dishes|restaurants|grocery"
            h.postDelayed({ WhatsAppSender.perform("tap", searchTargets) }, 3800)
            h.postDelayed({ WhatsAppSender.perform("type", query) }, 5400)
            return Result.Handled("Opening $label and searching for $query.")
        }
        return result
    }

    /** Save a quick note in Google Keep (or a chooser if Keep isn't installed). */
    fun executeNote(text: String): Result {
        if (text.isBlank()) return Result.Handled("What should the note say?")
        val keep = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage("com.google.android.keep")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(keep)
            Result.Handled("Saving that note in Keep.")
        } catch (e: Exception) {
            launch(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                "Here's your note — pick where to save it."
            )
        }
    }

    /** Starts in-app voice recording via [VoiceRecorder]. Mic must be free before calling. */
    fun executeStartRecording(): Result = Result.Handled(VoiceRecorder.start(context))

    /** Stops an in-progress recording. */
    fun executeStopRecording(): Result = Result.Handled(VoiceRecorder.stop())

    /** Compose an email. [to] may be an address or a contact name (looked up). */
    fun executeEmail(to: String, subject: String, body: String): Result {
        val address = when {
            to.contains("@") -> to
            to.isNotBlank() -> lookupEmail(to) ?: ""
            else -> ""
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(address)}")).apply {
            if (subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
            if (body.isNotBlank()) putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(intent, if (address.isBlank()) "Opening a new email." else "Opening an email to $to.")
    }

    /** Turn the camera flashlight on or off. */
    fun executeTorch(on: Boolean): Result {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val id = cm.cameraIdList.firstOrNull { camId ->
                cm.getCameraCharacteristics(camId)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return Result.Handled("This phone has no flashlight.")
            cm.setTorchMode(id, on)
            Result.Handled(if (on) "Flashlight on." else "Flashlight off.")
        } catch (e: Exception) {
            Result.Handled("I couldn't toggle the flashlight.")
        }
    }

    /** Speak the current battery percentage. */
    fun executeBatteryLevel(): Result {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return Result.Handled(
            if (pct in 0..100) "Battery is at $pct percent." else "I couldn't read the battery level."
        )
    }

    /** Open Wi-Fi controls (modern Android blocks silent toggling, so we open the panel). */
    fun executeWifiSettings(): Result {
        val intent = if (android.os.Build.VERSION.SDK_INT >= 29)
            Intent(Settings.Panel.ACTION_WIFI) else @Suppress("DEPRECATION") Intent(Settings.ACTION_WIFI_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(intent, "Here are your Wi-Fi controls.")
    }

    /**
     * Turn Bluetooth on with a one-tap system consent dialog (the most a non-system app may do).
     * Turning it OFF isn't allowed silently on modern Android, so that opens settings.
     */
    fun executeBluetooth(on: Boolean): Result {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager)?.adapter
            ?: return Result.Handled("This device has no Bluetooth.")
        if (!on) {
            return launch(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "Opening Bluetooth settings — tap to turn it off."
            )
        }
        if (adapter.isEnabled) return Result.Handled("Bluetooth is already on.")
        return launch(
            Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            "Turning on Bluetooth."
        )
    }

    /** True if the device's location (GPS) master switch is on. */
    fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    /** Launches the first installed package among [pkgs]; if none, opens the Play Store. */
    private fun openFirstInstalled(pkgs: List<String>, label: String): Result {
        for (pkg in pkgs) {
            val li = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(li); return Result.Handled("Opening $label.") }
            catch (e: Exception) { /* try next */ }
        }
        return launch(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${pkgs.first()}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            "$label isn't installed — opening the Play Store."
        )
    }

    /** Launches an installed app by package; if missing, opens its Play Store page. */
    private fun openAppOrStore(pkg: String, label: String): Result {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(launchIntent); Result.Handled("Opening $label.") }
            catch (e: Exception) { Result.Handled("I couldn't open $label.") }
        } else {
            launch(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "$label isn't installed — opening the Play Store."
            )
        }
    }

    fun executeCalendarCreate(title: String): Result {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(intent, "Opening your calendar to add \"$title\" — set the time and save.")
    }

    fun executeMessage(name: String, body: String, channel: MsgChannel): Result {
        if (name.isBlank() || body.isBlank()) {
            return Result.Handled("Tell me who to message and what to say.")
        }
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return openAppSettings("I need Contacts permission. Opening Naomi's settings — grant Contacts and come back.")
        }
        val contact = lookupContact(name)
            ?: return Result.Handled("I couldn't find a contact named $name. Who should I message?")

        return when (channel) {
            MsgChannel.WHATSAPP -> confirmAndSend(contact, body, viaWhatsApp = true)
            MsgChannel.SMS -> confirmAndSend(contact, body, viaWhatsApp = false)
            MsgChannel.ASK -> askWhichApp(contact, body)
        }
    }

    /**
     * Reads the message back AND asks which app — picking one confirms + sends, so the user
     * can catch a mis-heard message and say "cancel" to redo.
     */
    private fun askWhichApp(contact: Pair<String, String>, body: String): Result =
        Result.Ask("Send \"$body\" to ${contact.first}? Say WhatsApp or text — or change message, change contact, or cancel.") { answer ->
            handleMessageAnswer(answer, contact, body, viaWhatsApp = null)
        }

    /** Reads the message back on the chosen app and waits for confirmation before sending. */
    private fun confirmAndSend(
        contact: Pair<String, String>, body: String, viaWhatsApp: Boolean
    ): Result {
        val app = if (viaWhatsApp) "WhatsApp" else "a text message"
        return Result.Ask("Send \"$body\" to ${contact.first} on $app? Say yes — or change message, change contact, or cancel.") { answer ->
            handleMessageAnswer(answer, contact, body, viaWhatsApp)
        }
    }

    /**
     * Interprets the user's reply at a message confirmation step. Supports send, choose-app,
     * change-message, change-contact, and cancel. [viaWhatsApp] null = app not chosen yet.
     */
    private fun handleMessageAnswer(
        answer: String, contact: Pair<String, String>, body: String, viaWhatsApp: Boolean?
    ): Result {
        val a = answer.lowercase(Locale.getDefault())
        return when {
            isChangeContact(a) -> changeContact("Who should I send it to?", body, viaWhatsApp)
            isChangeMessage(a) -> changeMessage(contact, body, viaWhatsApp)
            isNo(a) -> Result.Handled("Okay, cancelled.")
            a.contains("whatsapp") || a.contains("whats app") || a.contains("dm") ->
                sendWhatsApp(contact, body)
            a.contains("text") || a.contains("sms") ||
                a.contains("normal") || a.contains("regular") ->
                sendSms(contact.second, body, contact.first)
            // "yes/send" only sends if we already know the app; otherwise re-ask which app.
            viaWhatsApp != null && isYes(a) ->
                if (viaWhatsApp) sendWhatsApp(contact, body)
                else sendSms(contact.second, body, contact.first)
            viaWhatsApp != null -> confirmAndSend(contact, body, viaWhatsApp) // unclear — re-ask
            else -> askWhichApp(contact, body) // unclear — re-ask
        }
    }

    /** Capture a new message for the same contact, then return to the confirm step. */
    private fun changeMessage(
        contact: Pair<String, String>, oldBody: String, viaWhatsApp: Boolean?
    ): Result = Result.Ask("Sure — what's the new message?") { spoken ->
        val newBody = spoken.replace(Regex("^(say|send|tell (her|him|them))\\s+"), "").trim()
        when {
            newBody.isBlank() -> changeMessage(contact, oldBody, viaWhatsApp)
            viaWhatsApp == null -> askWhichApp(contact, newBody)
            else -> confirmAndSend(contact, newBody, viaWhatsApp)
        }
    }

    /** Capture a new recipient, keeping the same message, then return to the confirm step. */
    private fun changeContact(prompt: String, body: String, viaWhatsApp: Boolean?): Result =
        Result.Ask(prompt) { spoken ->
            val newName = spoken.replace(Regex("^(to|send to|it's|its)\\s+"), "").trim()
            when {
                isNo(newName) -> Result.Handled("Okay, cancelled.")
                newName.isBlank() -> changeContact("Who should I send it to?", body, viaWhatsApp)
                else -> {
                    val c = lookupContact(newName)
                    when {
                        c == null -> changeContact(
                            "I couldn't find $newName. Who should I send it to? Or say cancel.",
                            body, viaWhatsApp
                        )
                        viaWhatsApp == null -> askWhichApp(c, body)
                        else -> confirmAndSend(c, body, viaWhatsApp)
                    }
                }
            }
        }

    private fun isChangeMessage(a: String): Boolean =
        listOf("change the message", "change message", "change my message", "edit the message",
            "edit message", "different message", "say it again", "rephrase", "correct the message")
            .any { a.contains(it) }

    private fun isChangeContact(a: String): Boolean =
        listOf("change the contact", "change contact", "change the name", "change name",
            "wrong person", "wrong contact", "different person", "different contact", "someone else")
            .any { a.contains(it) }

    private fun isYes(a: String): Boolean =
        Regex("\\b(yes|yeah|yep|yup|ok|okay|sure|send|correct|fine|do it|go ahead)\\b")
            .containsMatchIn(a)

    private fun isNo(a: String): Boolean =
        Regex("\\b(no|nope|cancel|stop|don't|dont|wrong|nah|wait)\\b").containsMatchIn(a)

    private fun sendWhatsApp(contact: Pair<String, String>, body: String): Result {
        val (display, number) = contact
        val digits = number.filter { it.isDigit() }
        val uri = Uri.parse("https://wa.me/$digits?text=${Uri.encode(body)}")
        // If the accessibility service is on, arm it so it taps Send once WhatsApp opens.
        val autoSend = isAccessibilityEnabled()
        if (autoSend) WhatsAppSender.arm()
        return launch(
            Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            if (autoSend) "Sending to $display on WhatsApp."
            else "Opening WhatsApp to $display — tap send. To auto-send, enable Naomi in Accessibility settings."
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = "${context.packageName}/${WhatsAppSender::class.java.name}"
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    /** Returns the text after the first of [keywords] that appears, or "" if none match. */
    private fun firstAfter(input: String, vararg keywords: String): String {
        for (k in keywords) {
            val r = extractAfter(input, k)
            if (r.isNotBlank()) return r
        }
        return ""
    }

    /** Strips command/filler words from a "find X near me" request down to the place itself. */
    private fun cleanPlace(input: String): String =
        input.replace(
            Regex("\\b(hey|naomi|can you|find|show me|where can i|get me|the|nearest|nearby|near me|closest|a|an|some|please|me|for)\\b"),
            " "
        ).replace(Regex("\\s+"), " ").trim()

    /** Pulls the words after [keyword], stripping trailing politeness ("please", "now"). */
    private fun extractAfter(input: String, keyword: String): String {
        val m = Regex("\\b$keyword\\b\\s+(.+)").find(input) ?: return ""
        return m.groupValues[1]
            .replace(Regex("\\b(please|now|right now|for me|thanks|thank you)\\b"), "")
            .trim()
    }

    // --- music ---------------------------------------------------------------

    /** Sends a media key to whatever music app is active (Spotify, JioSaavn, etc.). */
    private fun mediaKey(keyCode: Int, reply: String): Result {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return Result.Handled(reply)
    }

    /**
     * "play <something>" -> universal play-from-search intent that Spotify, JioSaavn,
     * YT Music etc. all understand. "on spotify"/"on jiosaavn" targets that specific app.
     */
    private fun handlePlay(input: String): Result {
        var query = extractAfter(input, "play")
        val app = when {
            query.contains("spotify") -> "spotify"
            query.contains("saavn") || query.contains("jio") -> "jiosaavn"
            else -> ""
        }
        query = query
            .replace(Regex("\\b(on|from)\\s+(the\\s+)?(spotify|jio\\s?saavn|jio|saavn)\\b"), "")
            .replace(Regex("^(the |a |some )?(song|track|music)\\b\\s*"), "") // drop "the song" filler
            .trim()
        return executePlay(query, app)
    }

    // --- messaging -----------------------------------------------------------

    /** "message/text/whatsapp <name> saying <body>" -> opens the chat pre-filled. */
    private fun handleMessage(input: String): Result {
        // Explicit channel if they named one; otherwise ASK which app.
        val channel = when {
            input.contains("whatsapp") || input.contains("dm") -> MsgChannel.WHATSAPP
            input.contains("sms") || input.contains("text message") ||
                Regex("\\btext\\b").containsMatchIn(input) -> MsgChannel.SMS
            else -> MsgChannel.ASK
        }

        // Split on the first "that / saying / to say": everything before is the recipient
        // (after we strip command + filler words), everything after is the message body.
        val sep = Regex("\\b(that|saying|to say)\\b").find(input)
            ?: return Result.Handled("Tell me who and what — like \"message Balaji saying I'm running late\".")
        val before = input.substring(0, sep.range.first)
        val body = input.substring(sep.range.last + 1).trim()
        val name = before
            .replace(
                Regex("\\b(hey|naomi|can you|could you|would you|will you|please|send|a|an|the|message|text|whatsapp|dm|to)\\b"),
                " "
            )
            .replace(Regex("\\s+"), " ").trim()

        if (name.isBlank() || body.isBlank()) {
            return Result.Handled("Tell me who and what — like \"message Balaji saying I'm running late\".")
        }
        return executeMessage(name, body, channel)
    }

    private fun sendSms(number: String, body: String, display: String): Result {
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            return openAppSettings("I need SMS permission. Opening Naomi's settings — grant SMS and come back.")
        }
        return try {
            val sms = if (android.os.Build.VERSION.SDK_INT >= 31)
                context.getSystemService(android.telephony.SmsManager::class.java)
            else
                @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
            sms.sendTextMessage(number, null, body, null, null)
            Result.Handled("Sent to $display.")
        } catch (e: Exception) {
            Result.Handled("I couldn't send it: ${e.message}")
        }
    }

    // --- calendar ------------------------------------------------------------

    private fun handleCalendarRead(): Result {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            return openAppSettings("I need Calendar permission. Opening Naomi's settings — grant Calendar and come back.")
        }
        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + 24L * 60 * 60 * 1000

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, dayStart)
        ContentUris.appendId(builder, dayEnd)

        val events = mutableListOf<String>()
        context.contentResolver.query(
            builder.build(),
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
            null, null, "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(0) ?: "untitled event"
                val at = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(c.getLong(1)))
                events.add("$title at $at")
            }
        }
        return Result.Handled(
            if (events.isEmpty()) "You have nothing on your calendar today."
            else "Today you have: ${events.joinToString("; ")}."
        )
    }

    private fun handleCalendarCreate(input: String): Result {
        val title = listOf("remind me to", "schedule a", "create event", "add event")
            .map { extractAfter(input, it) }
            .firstOrNull { it.isNotBlank() } ?: input
        return executeCalendarCreate(title)
    }

    private fun handleRide(input: String): Result {
        val app = when {
            input.contains("ola") -> "ola"
            input.contains("rapido") -> "rapido"
            else -> "uber"
        }
        return executeRide(firstAfter(input, "to"), app)
    }

    private fun handleFood(input: String): Result {
        val app = if (input.contains("zomato")) "zomato" else "swiggy"
        // Strip command/app filler down to the dish/restaurant to search for (may be blank).
        val query = input
            .replace(Regex("\\b(search for|search|order|food|from|in|on|me|please|a|the|some|for)\\b"), " ")
            .replace(Regex("\\b(swiggy|zomato)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return executeFood(query, app)
    }

    private fun handleNote(input: String): Result {
        val text = firstAfter(
            input, "take a note that", "make a note that", "note that",
            "write down", "note down", "take a note", "make a note", "note"
        )
        return executeNote(text)
    }

    private fun handleEmail(input: String): Result {
        val sep = Regex("\\b(saying|about|that)\\b").find(input)
        val to = (if (sep != null) input.substring(0, sep.range.first) else input)
            .replace(Regex("\\b(hey|naomi|can you|please|send|an|a|email|mail|to)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val body = if (sep != null) input.substring(sep.range.last + 1).trim() else ""
        return executeEmail(to, "", body)
    }

    /** First email address for a contact whose name matches [name]. */
    private fun lookupEmail(name: String): String? {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return null
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"), null
        )?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    private fun handleCall(name: String): Result {
        if (name.isBlank()) return Result.Handled("Who should I call?")
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return openAppSettings("I need Contacts permission to call someone. Opening Naomi's settings — grant Contacts and come back.")
        }
        val matches = lookupContacts(name)
        return when {
            matches.isEmpty() -> Result.Handled("I couldn't find a contact named $name. Who should I call?")
            matches.size == 1 -> dialContact(matches[0])
            else -> {
                val candidates = matches.take(3)
                val options = candidates.mapIndexed { i, (n, _) -> "${i + 1}. $n" }.joinToString(", ")
                Result.Ask("I found ${candidates.size} contacts: $options. Which one?") { reply ->
                    // Convert spoken number words ("one" -> "1") and accept ordinals / "number 2".
                    val a = wordsToDigits(reply.trim().lowercase(Locale.getDefault()))
                    val ordinal = when {
                        a.contains("first") -> 1
                        a.contains("second") -> 2
                        a.contains("third") -> 3
                        else -> null
                    }
                    val idx = ordinal ?: Regex("\\d+").find(a)?.value?.toIntOrNull()
                    val chosen = when {
                        isNo(a) -> null
                        idx != null && idx in 1..candidates.size -> candidates[idx - 1]
                        else -> candidates.find { (n, _) -> n.lowercase(Locale.getDefault()).contains(a) }
                    }
                    when {
                        chosen != null -> dialContact(chosen)
                        isNo(a) -> Result.Handled("Okay, cancelled.")
                        else -> Result.Handled("I didn't catch that. Call cancelled.")
                    }
                }
            }
        }
    }

    /**
     * Places a WhatsApp voice or video call by firing the contact's WhatsApp calling data row
     * (WhatsApp registers a "voip.call" / "video.call" row per contact it knows). Falls back to a
     * question — phrased so the brain's Groq fuzzy-name resolver can retry — when the name misses.
     */
    private fun handleWhatsAppCall(name: String, video: Boolean): Result {
        val kind = if (video) "video" else "voice"
        if (name.isBlank()) return Result.Handled("Who should I $kind call on WhatsApp?")
        if (context.packageManager.getLaunchIntentForPackage(WHATSAPP_PKG) == null)
            return Result.Handled("WhatsApp doesn't seem to be installed.")
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return openAppSettings("I need Contacts permission to place a WhatsApp call. Opening Naomi's settings — grant Contacts and come back.")
        }
        val mime = if (video) WA_VIDEO_MIME else WA_VOICE_MIME
        val row = findWhatsAppDataRow(name, mime)
            ?: return Result.Handled("I couldn't find a contact named $name. Who should I call on WhatsApp?")
        val (dataId, display) = row
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.withAppendedPath(ContactsContract.Data.CONTENT_URI, dataId), mime)
            setPackage(WHATSAPP_PKG)
            // Run the call in its own document task that's kept OUT of Recents. Otherwise this
            // intent becomes the base intent of the WhatsApp task and Android re-fires it — placing
            // the call again — whenever the user reopens WhatsApp from Recents.
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        }
        return launch(intent, "Starting a WhatsApp $kind call with $display.")
    }

    /** First WhatsApp calling data row (id + display name) for a contact matching [name]. */
    private fun findWhatsAppDataRow(name: String, mime: String): Pair<String, String>? {
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DISPLAY_NAME),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.Data.DISPLAY_NAME} LIKE ?",
            arrayOf(mime, "%$name%"),
            "${ContactsContract.Data.DISPLAY_NAME} ASC"
        )?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getString(0) ?: return null
                val display = c.getString(1) ?: name
                return id to display
            }
        }
        return null
    }

    /** The contact from a call request, stripped of "to", "on whatsapp", and audio/video/voice. */
    private fun extractCallTarget(input: String): String =
        extractAfter(input, "call")
            .replace(Regex("\\b(on\\s+)?whats\\s?app\\b"), " ")
            .replace(Regex("\\b(audio|video|voice)\\b"), " ")
            .replace(Regex("^\\s*to\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()

    private fun dialContact(contact: Pair<String, String>): Result {
        val (displayName, number) = contact
        val uri = Uri.fromParts("tel", number, null)
        return if (hasPermission(Manifest.permission.CALL_PHONE)) {
            launch(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Calling $displayName.")
        } else {
            launch(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Opening the dialer for $displayName.")
        }
    }

    /** Returns all contacts whose display name contains [name], deduplicated by name.
     *  Plain substring search only — if this misses, the brain asks Groq to resolve the
     *  (possibly mis-heard) name against the full contact list, then retries. */
    private fun lookupContacts(name: String): List<Pair<String, String>> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val results = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val display = cursor.getString(0) ?: continue
                val number = cursor.getString(1) ?: continue
                if (seen.add(display.lowercase(Locale.getDefault()))) {
                    results.add(display to number)
                }
            }
        }
        return results
    }

    /** Returns the first matching contact (used by messaging flows). */
    private fun lookupContact(name: String): Pair<String, String>? = lookupContacts(name).firstOrNull()

    /**
     * All distinct contact display names — passed to Groq for fuzzy name resolution when the
     * exact LIKE query returns nothing (e.g. "surbhi" vs the stored name "surabhi").
     */
    fun getAllContactNames(): List<String> {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return emptyList()
        val names = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                if (seen.add(n.lowercase(Locale.getDefault()))) names.add(n)
            }
        }
        return names
    }

    /** Open the CourseWatcher app; fall back to the IITM portal if not installed. */
    fun executeCourseCheck(): Result {
        for (pkg in listOf("com.naomi.coursewatcher", "com.iitm.coursewatcher",
                           "com.chandu.coursewatcher")) {
            val li = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(li)
                return Result.Handled("Opening Course Watcher — check it for any newly added courses.")
            } catch (_: Exception) {}
        }
        // Course Watcher not installed: open IITM add/drop portal directly.
        return executeOpenUrl(
            "https://ecampus.iitm.ac.in/student/AddDropCourses.aspx",
            "Course Watcher isn't installed. Opening the IITM add/drop portal — check for new courses there."
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Opens Naomi's app settings page so the user can grant the missing permission directly. */
    private fun openAppSettings(message: String): Result {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}
        return Result.Handled(message)
    }

    private fun handleOpenApp(name: String): Result {
        if (name.isBlank()) return Result.NotHandled
        val pm = context.packageManager
        // Best-effort: match an installed app whose label contains the spoken name.
        val match = pm.getInstalledApplications(0).firstOrNull { appInfo ->
            pm.getApplicationLabel(appInfo).toString().lowercase(Locale.getDefault())
                .contains(name)
        } ?: return Result.Handled("I couldn't find an app called $name. Which app did you mean?")

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

    /** Opens a specific system settings screen by voice (accessibility, wifi, sound, …). */
    private fun handleSettings(input: String): Result {
        // Accessibility gets a special cascade so we reach Naomi's service page when possible.
        if (input.contains("accessibility")) {
            val component = "${context.packageName}/${WhatsAppSender::class.java.name}"
            val attempts = listOf(
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(":settings:fragment_args_key", component),
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .putExtra(":settings:fragment_args_key", component),
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
            for (i in attempts) {
                try {
                    context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return Result.Handled("Opening accessibility settings.")
                } catch (_: Exception) {}
            }
            return Result.Handled("I couldn't open accessibility settings.")
        }

        val (action, label) = when {
            input.contains("bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth settings"
            Regex("\\bwi-?fi\\b").containsMatchIn(input) -> Settings.ACTION_WIFI_SETTINGS to "Wi-Fi settings"
            input.contains("sound") || input.contains("volume") -> Settings.ACTION_SOUND_SETTINGS to "sound settings"
            input.contains("display") || input.contains("brightness") -> Settings.ACTION_DISPLAY_SETTINGS to "display settings"
            input.contains("location") || input.contains("gps") -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "location settings"
            input.contains("battery") -> Settings.ACTION_BATTERY_SAVER_SETTINGS to "battery settings"
            input.contains("date") || input.contains("time") -> Settings.ACTION_DATE_SETTINGS to "date & time settings"
            input.contains("app") -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS to "app settings"
            else -> Settings.ACTION_SETTINGS to "settings"
        }
        val intent = if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            Intent(action, Uri.parse("package:${context.packageName}"))
        else Intent(action)
        return launch(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Opening $label.")
    }

    /** Media-volume control via AudioManager: up/down/mute/unmute/max/"set to N percent". */
    private fun handleVolume(input: String): Result {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return Result.Handled("I can't reach the volume controls.")
        val stream = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream)
        val flags = AudioManager.FLAG_SHOW_UI
        val pct = Regex("(\\d{1,3})\\s*(?:percent|%)").find(input)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            input.contains("unmute") -> {
                am.setStreamVolume(stream, (max / 2).coerceAtLeast(1), flags); Result.Handled("Unmuted.")
            }
            input.contains("mute") -> { am.setStreamVolume(stream, 0, flags); Result.Handled("Muted.") }
            input.contains("max") || input.contains("full") || input.contains("hundred") -> {
                am.setStreamVolume(stream, max, flags); Result.Handled("Volume's at max.")
            }
            pct != null -> {
                val level = (max * pct.coerceIn(0, 100) / 100.0).roundToInt().coerceIn(0, max)
                am.setStreamVolume(stream, level, flags); Result.Handled("Volume set to $pct percent.")
            }
            input.contains("down") || input.contains("lower") || input.contains("quieter") ||
                input.contains("decrease") || input.contains("reduce") -> {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, flags); Result.Handled("Turning it down.")
            }
            else -> {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, flags); Result.Handled("Turning it up.")
            }
        }
    }

    /** Ringer / Do-Not-Disturb: silent / vibrate / ring. Routes to DND access if the OS blocks it. */
    private fun handleRinger(input: String): Result {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return Result.Handled("I can't reach the sound settings.")
        val (mode, reply) = when {
            input.contains("off") || input.contains("ring") || input.contains("normal") ||
                input.contains("loud") -> AudioManager.RINGER_MODE_NORMAL to "Ringer's back on."
            input.contains("vibrate") -> AudioManager.RINGER_MODE_VIBRATE to "Vibrate mode on."
            else -> AudioManager.RINGER_MODE_SILENT to "Silent mode on."
        }
        return try {
            am.ringerMode = mode
            Result.Handled(reply)
        } catch (e: SecurityException) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
            Result.Handled("I need Do Not Disturb access first — I've opened that setting, enable Naomi and try again.")
        }
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

    /**
     * Converts spelled-out numbers up to 99 into digits, e.g.
     * "set a timer for twenty five minutes" -> "set a timer for 25 minutes".
     */
    private fun wordsToDigits(text: String): String {
        val tokens = text.split(Regex("\\s+")).toMutableList()
        val out = StringBuilder()
        var i = 0
        while (i < tokens.size) {
            val word = tokens[i].trim(',', '.')
            val units = UNITS[word]
            val tens = TENS[word]
            when {
                // "twenty five" -> 25
                tens != null && i + 1 < tokens.size && UNITS[tokens[i + 1].trim(',', '.')] != null -> {
                    out.append(tens + UNITS[tokens[i + 1].trim(',', '.')]!!).append(' ')
                    i += 2
                }
                tens != null -> { out.append(tens).append(' '); i++ }
                units != null -> { out.append(units).append(' '); i++ }
                else -> { out.append(tokens[i]).append(' '); i++ }
            }
        }
        return out.toString().trim()
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

    companion object {
        /** Music app display-name → package, in preference order for auto-detect. */
        val APP_TO_PKG = linkedMapOf(
            "spotify"       to "com.spotify.music",
            "youtube music" to "com.google.android.apps.youtube.music",
            "yt music"      to "com.google.android.apps.youtube.music",
            "jiosaavn"      to "com.jio.media.jiobeats",
            "jio saavn"     to "com.jio.media.jiobeats",
            "saavn"         to "com.jio.media.jiobeats",
            "gaana"         to "com.gaana",
            "wynk"          to "com.wynk.music",
            "resso"         to "com.resso.app",
            "samsung music" to "com.samsung.android.app.music",
            "music"         to "com.android.music",
        )

        private val UNITS = mapOf(
            "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
            "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
            "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
            "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
            "eighteen" to 18, "nineteen" to 19
        )
        private val TENS = mapOf(
            "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
            "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
        )
    }
}

/** Which app to send a message through. ASK = let the user choose by voice. */
enum class MsgChannel { SMS, WHATSAPP, ASK }

private const val WHATSAPP_PKG = "com.whatsapp"
// WhatsApp registers these contact data rows; firing one starts the call directly.
private const val WA_VOICE_MIME = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
private const val WA_VIDEO_MIME = "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
