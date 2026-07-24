package com.naomi.assistant

import android.Manifest
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.text.format.DateUtils
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan2
import kotlin.math.hypot
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.animation.core.Animatable

/**
 * Naomi — voice assistant UI.
 *
 * Flow: wake word / tap -> listen (SpeechRecognizer) -> AssistantBrain (offline router,
 * on-device Gemma, or Gemini) -> speak the reply (TextToSpeech).
 *
 * The UI is a small four-screen app driven by [screen]:
 *   HOME     — the orb, live status and the conversation transcript.
 *   FACTS    — the things Naomi remembers, as editable cards.
 *   ADD_FACT — a dedicated page to add one fact (typed or spoken).
 *   SETTINGS — voice options + one-tap links to the OS permissions Naomi needs.
 */
class MainActivity : ComponentActivity() {

    /** What Naomi is doing right now — drives the animated orb. */
    enum class Mood { IDLE, LISTENING, THINKING, SPEAKING }

    /** The visible page. Everything but HOME has a back arrow. */
    enum class Screen { HOME, FACTS, ADD_FACT, SETTINGS }

    private lateinit var voice: VoiceInput
    private lateinit var speaker: Speaker
    private lateinit var brain: AssistantBrain

    private var status by mutableStateOf("Tap the orb or say \"Naomi\"")
    private var transcript by mutableStateOf("")
    private var wakeEnabled by mutableStateOf(false)
    private var showSplash by mutableStateOf(false) // set in onCreate based on launch type
    private var voiceTrained by mutableStateOf(false)
    private var mood by mutableStateOf(Mood.IDLE)
    private var smartMode by mutableStateOf(false)
    private var factMode by mutableStateOf(false)
    private var turnJob: Job? = null // the in-flight assistant turn, so we can cancel on reset

    // Power button (screen off) mid-interaction → stop everything and reset to idle.
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && isInteracting()) resetToInitial()
        }
    }

    private var screen by mutableStateOf(Screen.HOME)
    private var facts by mutableStateOf<Map<String, String>>(emptyMap())
    private var setup by mutableStateOf(SetupStatus())

    // Voice-match tuning (persisted): the ECAPA similarity threshold + the last observed score.
    private var voiceThreshold by mutableStateOf(WakeService.DEFAULT_SIMILARITY_THRESHOLD)
    private var lastSim by mutableStateOf(-1f)
    private var lastSimAt by mutableStateOf(0L)

    private val enrollment by lazy { VoiceEnrollment(this) }

    /** Blocks orb taps while Naomi is speaking the post-recording confirmation. */
    @Volatile private var recordingCooldown = false

    /** Guards a follow-up so only the first answer (barge-in OR after-question) is processed. */
    private val answerConsumed = java.util.concurrent.atomic.AtomicBoolean(true)

    private val neededPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.SEND_SMS,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    /** Action to run once the "turn on location" dialog closes (e.g. open Uber). */
    private var pendingAfterLocation: (() -> Unit)? = null

    /** Receives the result of the one-tap "turn on location" system dialog. */
    private val locationSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            // Proceed regardless of allow/deny — the target app will prompt again if still off.
            pendingAfterLocation?.invoke()
            pendingAfterLocation = null
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val micGranted = result[Manifest.permission.RECORD_AUDIO] == true
            status = if (micGranted) "Tap the orb or say \"Naomi\"" else "Microphone permission is required"
            refreshSetup()
        }

    /** Keep the screen awake while a conversation turn is in progress. */
    private fun enterMood(m: Mood) {
        mood = m
        if (m == Mood.IDLE) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show Naomi ON TOP of the lock screen and turn the display on when woken.
        // No requestDismissKeyguard — that pushes the PIN screen in front instead.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        voice = VoiceInput(this)
        // When the user starts talking (e.g. answering a follow-up), cut Naomi off immediately.
        voice.onSpeechStart = { speaker.stop() }
        speaker = Speaker(this)

        // A bare follow-up answer heard over Naomi's question (via the AEC wake mic).
        WakeService.answerCallback = { word -> onSpokenAnswer(word) }
        // BuildConfig.GEMINI_API_KEY comes from local.properties (see README).
        brain = AssistantBrain(this, BuildConfig.GEMINI_API_KEY, BuildConfig.GROQ_API_KEY)
        facts = brain.memory.all()

        // Ask for everything Naomi needs up front (mic to listen, contacts + phone to call).
        val missing = neededPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())

        voiceTrained = enrollment.isEnrolled

        // Show the splash only when the user taps the launcher icon, not on voice wake.
        showSplash = !intent.getBooleanExtra(WakeService.EXTRA_WAKE, false)

        // Restore the smart-mode (cloud) preference and apply it to the brain.
        val prefs = getSharedPreferences("naomi", MODE_PRIVATE)
        smartMode = prefs.getBoolean("smart", false)
        brain.smartMode = smartMode

        // Restore the tuned voice-match threshold and hand it to the wake service.
        voiceThreshold = prefs.getFloat("sim_threshold", WakeService.DEFAULT_SIMILARITY_THRESHOLD)
        WakeService.similarityThreshold = voiceThreshold
        lastSim = prefs.getFloat("last_similarity", -1f)
        lastSimAt = prefs.getLong("last_similarity_at", 0L)

        // Restore the "Hey Naomi" setting: if it was on (and mic granted), re-arm listening.
        // This also covers resuming after a reboot via the BootReceiver notification.
        if (getSharedPreferences("naomi", MODE_PRIVATE).getBoolean("wake", false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            WakeService.start(this)
            wakeEnabled = true
            status = "Say \"Naomi\" anytime…"
        }

        // Back button: mid-interaction it means "stop and reset", not "leave the app".
        onBackPressedDispatcher.addCallback(this) {
            if (isInteracting()) {
                resetToInitial()
                return@addCallback
            }
            when (screen) {
                Screen.ADD_FACT -> screen = Screen.FACTS
                Screen.FACTS, Screen.SETTINGS -> screen = Screen.HOME
                Screen.HOME -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }

        // Catch the power button (screen off) so leaving mid-interaction resets Naomi.
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            NaomiTheme {
                Box(Modifier.fillMaxSize()) {
                    NaomiApp(
                        screen        = screen,
                        mood          = mood,
                        status        = status,
                        transcript    = transcript,
                        wakeEnabled   = wakeEnabled,
                        smartMode     = smartMode,
                        voiceTrained  = voiceTrained,
                        factMode      = factMode,
                        facts         = facts,
                        setup         = setup,
                        voiceThreshold = voiceThreshold,
                        lastSim       = lastSim,
                        lastSimAt     = lastSimAt,
                        onThresholdChange = { onThresholdChange(it) },
                        onNavigate    = { screen = it },
                        onOrbTap      = { onMicTapped() },
                        onWakeToggle  = { toggleWake() },
                        onSmartToggle = { onSmartToggle(it) },
                        onTrain       = { trainVoice() },
                        onVoiceFact   = { startFactMode() },
                        onSaveFact    = { key, value -> saveFact(key, value); screen = Screen.FACTS },
                        onUpdateFact  = { old, key, value -> updateFact(old, key, value) },
                        onDeleteFact  = { key -> deleteFact(key) },
                        onOpenSetup   = { openSetup(it) },
                    )
                    // Splash overlay — shown on normal launcher open, skipped on voice wake.
                    if (showSplash) {
                        NaomiSplash(onComplete = { showSplash = false })
                    }
                }
            }
        }

        handleWakeIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        isForeground = true
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a system settings screen — refresh the setup checklist + facts.
        refreshSetup()
        facts = brain.memory.all()
        // Freshest voice-match score (WakeService writes it live in this same process).
        if (WakeService.lastSimilarity >= 0f) {
            lastSim = WakeService.lastSimilarity
            lastSimAt = WakeService.lastSimilarityAt
        } else {
            val prefs = getSharedPreferences("naomi", MODE_PRIVATE)
            lastSim = prefs.getFloat("last_similarity", -1f)
            lastSimAt = prefs.getLong("last_similarity_at", 0L)
        }
    }

    /** Persist and apply a new voice-match threshold from the Settings slider. */
    private fun onThresholdChange(value: Float) {
        val v = value.coerceIn(0.30f, 0.90f)
        voiceThreshold = v
        WakeService.similarityThreshold = v
        getSharedPreferences("naomi", MODE_PRIVATE).edit().putFloat("sim_threshold", v).apply()
    }

    override fun onStop() {
        super.onStop()
        // Off the UI now: next "Naomi" comes from lock/background, so verify the voice again.
        isForeground = false
    }

    /** Home or Recents — the user deliberately left. Stop everything and reset to idle.
     *  Note: this fires ONLY for a user-initiated leave, NOT when Naomi launches a target app
     *  (dialer, Spotify, etc.), so confirmation speech for those isn't cut off. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isInteracting()) resetToInitial()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    /** If the wake service launched us (or we were woken mid-speech), start a command turn. */
    private fun handleWakeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(WakeService.EXTRA_WAKE, false) != true) return
        intent.removeExtra(WakeService.EXTRA_WAKE)
        screen = Screen.HOME // voice wake always lands on the orb
        speaker.stop() // barge-in: cut off whatever Naomi was saying
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        }
    }

    // ── Facts ──────────────────────────────────────────────────────────────────

    private fun saveFact(key: String, value: String) {
        if (key.isBlank() || value.isBlank()) return
        brain.memory.put(key, value)
        facts = brain.memory.all()
    }

    private fun updateFact(oldKey: String, key: String, value: String) {
        if (key.isBlank() || value.isBlank()) return
        brain.memory.update(oldKey, key, value)
        facts = brain.memory.all()
    }

    private fun deleteFact(key: String) {
        brain.memory.remove(key)
        facts = brain.memory.all()
    }

    // ── System setup deep-links ──────────────────────────────────────────────────

    /** Snapshot of which OS-level permissions/access Naomi currently has. */
    data class SetupStatus(
        val mic: Boolean = false,
        val accessibility: Boolean = false,
        val battery: Boolean = false,
        val overlay: Boolean = false,
        val notifications: Boolean = false,
    )

    /** Which setup item a "Set up" button targets. */
    enum class Setup { MIC, ACCESSIBILITY, BATTERY, OVERLAY, NOTIFICATIONS, ALL_PERMISSIONS, ASSISTANT }

    private fun refreshSetup() {
        val pm = getSystemService(PowerManager::class.java)
        setup = SetupStatus(
            mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED,
            accessibility = isAccessibilityEnabled(),
            battery = pm?.isIgnoringBatteryOptimizations(packageName) ?: false,
            overlay = Settings.canDrawOverlays(this),
            notifications = NotificationManagerCompat.from(this).areNotificationsEnabled(),
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (WhatsAppSender.isEnabled) return true
        val flat = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return flat.split(':').any { it.contains(packageName, ignoreCase = true) }
    }

    /** Opens the relevant system screen so the user can grant one piece of access. */
    private fun openSetup(which: Setup) {
        val appUri = Uri.parse("package:$packageName")
        val intent = when (which) {
            Setup.MIC -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    permissionLauncher.launch(neededPermissions)
                    return
                }
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri)
            }
            // Handled entirely by openAccessibility() with its own fallback chain (which never
            // lands on the app-info page), so we return before the generic launcher below.
            Setup.ACCESSIBILITY -> { openAccessibility(); return }
            Setup.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, appUri)
            Setup.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, appUri)
            Setup.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            Setup.ALL_PERMISSIONS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri)
            Setup.ASSISTANT -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        }
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // Fall back to the app's own settings page if the specific screen is unavailable.
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
        }
    }

    /**
     * Opens the accessibility settings, preferring Naomi's own service page.
     * Cascades so a normal app can always get *somewhere* useful:
     *   1. The service's detail page (stock Android 11+ only — some OEMs block this for apps).
     *   2. The accessibility list, scrolled/highlighted to Naomi.
     *   3. The plain accessibility list.
     * Never falls through to the app-info page.
     */
    private fun openAccessibility() {
        val component = ComponentName(this, WhatsAppSender::class.java).flattenToString()
        val args = Bundle().apply { putString(":settings:fragment_args_key", component) }

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                        .putExtra(":settings:fragment_args_key", component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (_: Exception) {}
        }
        try {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .putExtra(":settings:fragment_args_key", component)
                    .putExtra(":settings:show_fragment_args", args)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        } catch (_: Exception) {}
        try {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}
    }

    private fun toggleWake() {
        val prefs = getSharedPreferences("naomi", MODE_PRIVATE)
        if (wakeEnabled) {
            WakeService.stop(this)
            wakeEnabled = false
            status = "Tap the orb or say \"Naomi\""
            prefs.edit().putBoolean("wake", false).apply()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(neededPermissions)
            return
        }
        WakeService.start(this)
        wakeEnabled = true
        status = "Say \"Naomi\" anytime…"
        // Remember the choice so BootReceiver re-arms listening after a reboot.
        prefs.edit().putBoolean("wake", true).apply()
        // Ask to be exempt from battery optimization so the OS doesn't freeze the wake service.
        requestBatteryExemption()
    }

    /** Prompts to exclude Naomi from battery optimization (Doze freezes the wake mic otherwise). */
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun onSmartToggle(on: Boolean) {
        smartMode = on
        brain.smartMode = on
        getSharedPreferences("naomi", MODE_PRIVATE).edit().putBoolean("smart", on).apply()
        status = if (on) "Smart mode on — cloud for hard questions" else "On-device only"
    }

    private fun isTurnOnLocation(lower: String): Boolean =
        Regex("\\b(location|gps)\\b").containsMatchIn(lower) &&
            Regex("\\b(on|enable|turn|start)\\b").containsMatchIn(lower)

    private fun needsLocation(lower: String): Boolean =
        Regex("\\b(uber|ola|rapido|cab|ride|taxi|swiggy|zomato|navigate|directions|near me)\\b")
            .containsMatchIn(lower) || lower.contains("take me to") || lower.contains("order food")

    /**
     * Ensures location is on, then runs [onReady]. If it's already on, [onReady] runs
     * immediately; if off, the one-tap system dialog shows and [onReady] runs once it closes —
     * so the caller can open the target app (Uber/Maps) AFTER the user enables location.
     */
    private fun ensureLocationOn(onReady: () -> Unit = {}) {
        val request = LocationSettingsRequest.Builder()
            .addLocationRequest(
                LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L).build()
            ).build()
        LocationServices.getSettingsClient(this)
            .checkLocationSettings(request)
            .addOnSuccessListener { onReady() } // already on → proceed straight away
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) {
                    pendingAfterLocation = onReady
                    try {
                        locationSettingsLauncher.launch(IntentSenderRequest.Builder(e.resolution).build())
                    } catch (_: Exception) {
                        pendingAfterLocation = null
                        onReady()
                    }
                } else {
                    onReady() // can't resolve here → just proceed
                }
            }
    }

    /** A screen-control command: tap a labelled element, scroll, or type into a field. */
    private data class ScreenCmd(val action: String, val target: String)

    /** Recognizes "place order / tap X / select X / scroll down / type X" style commands. */
    private fun parseScreenControl(lower: String): ScreenCmd? = when {
        Regex("\\bscroll\\b").containsMatchIn(lower) ->
            ScreenCmd("scroll", if (lower.contains("up")) "up" else "down")
        Regex("\\b(place (the )?order|place it|check ?out|confirm (the )?order|proceed)\\b").containsMatchIn(lower) ->
            ScreenCmd("tap", "place order|checkout|check out|proceed|confirm|place")
        Regex("\\b(tap|click|press|select|choose)\\b").containsMatchIn(lower) -> {
            val t = firstAfterVerb(lower, "tap", "click", "press", "select", "choose")
            if (t.isBlank()) null else ScreenCmd("tap", t)
        }
        lower.startsWith("type ") -> ScreenCmd("type", lower.removePrefix("type ").trim())
        else -> null
    }

    private fun firstAfterVerb(text: String, vararg verbs: String): String {
        for (v in verbs) {
            val m = Regex("\\b$v\\b\\s+(?:on |the )?(.+)").find(text)
            if (m != null) return m.groupValues[1]
                .replace(Regex("\\b(button|option|please|now)\\b"), "").trim()
        }
        return ""
    }

    /** Sends Naomi to the background to reveal the target app, then performs the screen action. */
    private fun performScreenControl(sc: ScreenCmd) {
        if (!WhatsAppSender.isEnabled) {
            enterMood(Mood.SPEAKING)
            status = "Enable Naomi in Accessibility to control apps."
            speaker.speak("To control apps, please enable Naomi in your accessibility settings.") {
                enterMood(Mood.IDLE)
            }
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
            if (wakeEnabled) WakeService.resume(this)
            return
        }
        enterMood(Mood.IDLE)
        status = "On it…"
        if (wakeEnabled) WakeService.resume(this)
        // Reveal the app that was open behind Naomi, then act on it once it's settled.
        moveTaskToBack(true)
        Handler(Looper.getMainLooper()).postDelayed({
            WhatsAppSender.perform(sc.action, sc.target)
        }, 750)
    }

    private fun onMicTapped() {
        // If we're mid-recording, the orb tap means "stop".
        if (VoiceRecorder.isRecording) {
            stopRecordingNow()
            return
        }
        // Ignore taps while speaking the post-recording confirmation.
        if (recordingCooldown) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        speaker.stop() // tapping also interrupts any ongoing speech
        startListening()
    }

    private fun stopRecordingNow() {
        val msg = VoiceRecorder.stop()
        transcript = "Naomi: $msg"
        enterMood(Mood.SPEAKING)
        recordingCooldown = true
        status = if (wakeEnabled) "Say \"Naomi\" anytime…" else "Tap the orb or say \"Naomi\""
        speaker.speak(msg) {
            recordingCooldown = false
            enterMood(Mood.IDLE)
            if (wakeEnabled) WakeService.resume(this@MainActivity)
        }
    }

    private fun startListening() {
        if (wakeEnabled) WakeService.pause(this) // free the mic from Vosk for this turn
        enterMood(Mood.LISTENING)
        status = "Listening…"
        voice.listen(
            onResult = { spoken ->
                android.util.Log.d("Naomi", "Heard: \"$spoken\"")
                transcript = "You: $spoken"
                val lower = spoken.lowercase()
                if (isTurnOnLocation(lower)) {
                    // Pure "turn on location" — no app to redirect to, just enable it.
                    ensureLocationOn()
                    enterMood(Mood.SPEAKING)
                    status = "Turning on location…"
                    speaker.speak("Turning on location.") { enterMood(Mood.IDLE) }
                    if (wakeEnabled) WakeService.resume(this@MainActivity)
                    return@listen
                }
                route(spoken, lower)
            },
            onError = { message ->
                android.util.Log.e("Naomi", "STT error: $message")
                enterMood(Mood.IDLE)
                status = message
                if (wakeEnabled) WakeService.resume(this@MainActivity)
            }
        )
    }

    /**
     * Asks a follow-up question and listens for the answer two ways at once:
     *  - DURING the question, the AEC wake mic listens for a bare answer ("yes/cancel/whatsapp…"),
     *    so the user can talk over Naomi with no wake word and without it self-triggering.
     *  - If they stay silent, the normal recognizer opens the moment she finishes.
     * Whichever fires first wins (guarded by [answerConsumed]).
     */
    private fun askFollowUp(text: String) {
        answerConsumed.set(false)
        enterMood(Mood.SPEAKING)
        status = "Listening after I speak…"
        // AEC answer mode catches barge-in ("yes/whatsapp/cancel") WHILE Naomi is speaking.
        WakeService.listenForAnswer(this)
        speaker.speak(text) {
            // ALWAYS open the full recognizer after the question — even if AEC fired on noise.
            // If the pending follow-up was already handled via barge-in, brain.hasPending is false
            // and startListening() won't try to process a stale answer.
            WakeService.pause(this)
            lifecycleScope.launch {
                delay(80)
                if (brain.hasPending) startListening()
            }
        }
    }

    /** A follow-up answer was spoken over the question (caught by the AEC wake mic). */
    private fun onSpokenAnswer(word: String) {
        if (!answerConsumed.compareAndSet(false, true)) return
        speaker.stop()
        WakeService.pause(this)
        runTurn(word)
    }

    /** Routes a recognized command to screen-control, location-gated, or a normal turn. */
    private fun route(spoken: String, lower: String) {
        val screenCmd = parseScreenControl(lower)
        if (screenCmd != null) {
            performScreenControl(screenCmd)
            return
        }
        if (needsLocation(lower)) ensureLocationOn { runTurn(spoken) } else runTurn(spoken)
    }

    /** Runs one assistant turn: think → act/answer → speak, re-arming wake afterward. */
    private fun runTurn(spoken: String) {
        enterMood(Mood.THINKING)
        status = "Thinking…"
        turnJob = lifecycleScope.launch {
            val reply = brain.handle(spoken)
            android.util.Log.d("Naomi", "Reply: \"${reply.text}\" (listenAgain=${reply.listenAgain})")
            transcript = "You: $spoken\n\nNaomi: ${reply.text}"
            if (reply.listenAgain) {
                askFollowUp(reply.text)
            } else {
                enterMood(Mood.SPEAKING)
                // Keep wake paused if recording is active — mic belongs to MediaRecorder.
                val resumeWake = wakeEnabled && !VoiceRecorder.isRecording
                status = when {
                    VoiceRecorder.isRecording -> "Recording… tap the orb to stop"
                    wakeEnabled -> "Say \"Naomi\" anytime…"
                    else -> "Tap the orb or say \"Naomi\""
                }
                speaker.speak(reply.text) { enterMood(Mood.IDLE) }
                if (resumeWake) WakeService.resume(this@MainActivity)
            }
        }
    }

    /** True when Naomi is actively doing something the user might want to abort. */
    private fun isInteracting(): Boolean =
        factMode || brain.hasPending ||
        mood == Mood.LISTENING || mood == Mood.THINKING || mood == Mood.SPEAKING

    /**
     * Hard stop: abort whatever Naomi is doing (speaking, listening, thinking, a pending
     * follow-up, fact entry) and return to the idle home state. Triggered by Back, Home/Recents,
     * and the power button (screen off) so leaving mid-interaction always cleanly resets.
     */
    private fun resetToInitial() {
        turnJob?.cancel(); turnJob = null
        speaker.stop()
        voice.cancel()
        answerConsumed.set(true)
        factMode = false
        brain.cancel()
        enterMood(Mood.IDLE)
        transcript = ""
        screen = Screen.HOME
        if (wakeEnabled && !VoiceRecorder.isRecording) {
            WakeService.resume(this)            // back to passive "Naomi" listening
            status = "Say \"Naomi\" anytime…"
        } else {
            WakeService.pause(this)
            status = if (VoiceRecorder.isRecording) "Recording… tap the orb to stop"
                     else "Tap the orb or say \"Naomi\""
        }
    }

    /**
     * One-shot fact-entry mode: listen once, try to parse a fact statement, store it.
     * Example: "my mom is Amma" → stores mom→Amma. Triggered from the Add Fact page.
     */
    private fun startFactMode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (factMode) return // already listening
        factMode = true
        if (wakeEnabled) WakeService.pause(this)
        speaker.stop()
        enterMood(Mood.LISTENING)
        status = "Say a fact — e.g. \"my mom is Amma\""
        voice.listen(
            onResult = { spoken ->
                factMode = false
                enterMood(Mood.SPEAKING)
                val confirmation = brain.memory.learnFromSpeech(spoken)
                    ?: "Sorry, I didn't understand that as a fact. Try saying \"my mom is Amma\"."
                android.util.Log.i("Naomi", "Fact input: \"$spoken\" → $confirmation")
                facts = brain.memory.all()
                if (facts.isNotEmpty()) screen = Screen.FACTS
                transcript = "You: $spoken\n\nNaomi: $confirmation"
                status = if (wakeEnabled) "Say \"Naomi\" anytime…" else "Tap the orb or say \"Naomi\""
                speaker.speak(confirmation) { enterMood(Mood.IDLE) }
                if (wakeEnabled) WakeService.resume(this)
            },
            onError = { message ->
                factMode = false
                enterMood(Mood.IDLE)
                status = message
                if (wakeEnabled) WakeService.resume(this)
            }
        )
    }

    /** Records "Naomi" 5 times and stores the user's voice fingerprint (ECAPA embedding). */
    private fun trainVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (!enrollment.isModelAvailable) {
            val externalDir = getExternalFilesDir(null)?.absolutePath ?: "/sdcard/Android/data/com.naomi.assistant/files"
            transcript = "Voice model missing.\n\nRun on your PC:\nadb push wespeaker_resnet34.onnx \"$externalDir/\""
            status = "Voice model not found — see instructions"
            speaker.speak("The voice model file is missing. Check the instructions on screen.")
            return
        }
        screen = Screen.HOME // show the orb during the guided enrollment
        val resumeWake = wakeEnabled
        if (resumeWake) WakeService.pause(this) // free the mic for enrollment

        lifecycleScope.launch {
            val samples = ArrayList<ShortArray>()
            repeat(5) { i ->
                enterMood(Mood.SPEAKING)
                status = "Say \"Naomi\" now (${i + 1}/5)"
                speaker.speak("Say Naomi")
                delay(1300) // let the prompt finish + you start speaking
                enterMood(Mood.LISTENING)
                status = "Listening… (${i + 1}/5)"
                val s = withContext(Dispatchers.Default) { recordSample(1500) }
                samples.add(s)
                delay(400)
            }
            enterMood(Mood.THINKING)
            status = "Saving your voice…"
            withContext(Dispatchers.Default) { enrollment.enroll(samples) }
            voiceTrained = enrollment.isEnrolled
            enterMood(Mood.IDLE)
            status = if (voiceTrained) "Voice trained ✓ — say \"Naomi\"" else "Training failed, try again"
            if (resumeWake) WakeService.resume(this@MainActivity)
        }
    }

    @Suppress("MissingPermission")
    private fun recordSample(ms: Int): ShortArray {
        val sr = 16000
        val min = AudioRecord.getMinBufferSize(
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, sr,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, sr)
        )
        val total = sr * ms / 1000
        val out = ShortArray(total)
        return try {
            rec.startRecording()
            var off = 0
            while (off < total) {
                val n = rec.read(out, off, total - off)
                if (n <= 0) break
                off += n
            }
            if (off == total) out else out.copyOf(off)
        } finally {
            try { rec.stop() } catch (e: Exception) {}
            rec.release()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        voice.destroy()
        speaker.shutdown()
        super.onDestroy()
    }

    companion object {
        /** True while the activity is on screen — WakeService skips voice verification then. */
        @Volatile
        var isForeground = false
    }
}

// ── Design Tokens ────────────────────────────────────────────────────────────
private val BgColor          = Color(0xFF0F131F)
private val PrimaryViolet    = Color(0xFFC6BFFF)
private val CyanAccent       = Color(0xFF00D9FF)
private val MagentaAccent    = Color(0xFFDE4DFF)
private val RecordingRed     = Color(0xFFFF4444)
private val SuccessGreen     = Color(0xFF3DD68C)
private val SurfaceHigh      = Color(0xFF262A37)
private val OnSurface        = Color(0xFFDFE2F3)
private val OnSurfaceVariant = Color(0xFFC8C4D7)
private val OutlineColor     = Color(0xFF928EA0)

private val GlassFill   = Color.White.copy(alpha = 0.08f)
private val GlassBorder = Color.White.copy(alpha = 0.12f)

// ── Fonts ─────────────────────────────────────────────────────────────────────
private val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage   = "com.google.android.gms",
    certificates      = R.array.com_google_android_gms_fonts_certs
)
private val SpaceGrotesk = FontFamily(
    Font(GoogleFont("Space Grotesk"), fontProvider, FontWeight.Medium),
    Font(GoogleFont("Space Grotesk"), fontProvider, FontWeight.SemiBold),
    Font(GoogleFont("Space Grotesk"), fontProvider, FontWeight.Bold),
)
private val InterFamily = FontFamily(
    Font(GoogleFont("Inter"), fontProvider, FontWeight.Normal),
    Font(GoogleFont("Inter"), fontProvider, FontWeight.Medium),
)

// ── Theme wrapper ─────────────────────────────────────────────────────────────
@Composable
private fun NaomiTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

// ── App shell: routes between the four screens ─────────────────────────────────
@Composable
private fun NaomiApp(
    screen: MainActivity.Screen,
    mood: MainActivity.Mood,
    status: String,
    transcript: String,
    wakeEnabled: Boolean,
    smartMode: Boolean,
    voiceTrained: Boolean,
    factMode: Boolean,
    facts: Map<String, String>,
    setup: MainActivity.SetupStatus,
    voiceThreshold: Float,
    lastSim: Float,
    lastSimAt: Long,
    onThresholdChange: (Float) -> Unit,
    onNavigate: (MainActivity.Screen) -> Unit,
    onOrbTap: () -> Unit,
    onWakeToggle: () -> Unit,
    onSmartToggle: (Boolean) -> Unit,
    onTrain: () -> Unit,
    onVoiceFact: () -> Unit,
    onSaveFact: (String, String) -> Unit,
    onUpdateFact: (String, String, String) -> Unit,
    onDeleteFact: (String) -> Unit,
    onOpenSetup: (MainActivity.Setup) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(BgColor)
    ) {
        AmbientBackground()

        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "screen"
        ) { current ->
            when (current) {
                MainActivity.Screen.HOME -> HomeScreen(
                    mood = mood, status = status, transcript = transcript,
                    factCount = facts.size,
                    onOrbTap = onOrbTap,
                    onNavigate = onNavigate
                )
                MainActivity.Screen.FACTS -> FactsScreen(
                    facts = facts,
                    onBack = { onNavigate(MainActivity.Screen.HOME) },
                    onAdd = { onNavigate(MainActivity.Screen.ADD_FACT) },
                    onUpdate = onUpdateFact,
                    onDelete = onDeleteFact,
                    onNavigate = onNavigate,
                )
                MainActivity.Screen.ADD_FACT -> AddFactScreen(
                    factMode = factMode,
                    onBack = { onNavigate(MainActivity.Screen.FACTS) },
                    onSave = onSaveFact,
                    onVoiceFact = onVoiceFact,
                )
                MainActivity.Screen.SETTINGS -> SettingsScreen(
                    wakeEnabled = wakeEnabled,
                    smartMode = smartMode,
                    voiceTrained = voiceTrained,
                    setup = setup,
                    voiceThreshold = voiceThreshold,
                    lastSim = lastSim,
                    lastSimAt = lastSimAt,
                    onThresholdChange = onThresholdChange,
                    onBack = { onNavigate(MainActivity.Screen.HOME) },
                    onWakeToggle = onWakeToggle,
                    onSmartToggle = onSmartToggle,
                    onTrain = onTrain,
                    onOpenSetup = onOpenSetup,
                    onNavigate = onNavigate,
                )
            }
        }
    }
}

// ── Ambient gradient blobs (shared background) ─────────────────────────────────
@Composable
private fun AmbientBackground() {
    Box(
        Modifier
            .size(380.dp)
            .offset((-60).dp, (-80).dp)
            .background(
                Brush.radialGradient(listOf(PrimaryViolet.copy(alpha = 0.07f), Color.Transparent)),
                CircleShape
            )
    )
    Box(
        Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.BottomEnd
    ) {
        Box(
            Modifier
                .size(300.dp)
                .offset(60.dp, 80.dp)
                .background(
                    Brush.radialGradient(listOf(CyanAccent.copy(alpha = 0.06f), Color.Transparent)),
                    CircleShape
                )
        )
    }
}

// ── HOME ────────────────────────────────────────────────────────────────────
@Composable
private fun HomeScreen(
    mood: MainActivity.Mood,
    status: String,
    transcript: String,
    factCount: Int,
    onOrbTap: () -> Unit,
    onNavigate: (MainActivity.Screen) -> Unit,
) {
    val isRecording = status.startsWith("Recording")

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LogoBadge()
            Spacer(Modifier.weight(1f))
            Text(
                "NAOMI",
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                letterSpacing = 4.sp,
                style = androidx.compose.ui.text.TextStyle(
                    brush = Brush.linearGradient(listOf(PrimaryViolet, CyanAccent))
                )
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onNavigate(MainActivity.Screen.SETTINGS) }) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = OnSurfaceVariant)
            }
        }

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                NaomiOrb(mood = mood, isRecording = isRecording, onTap = onOrbTap)
                Spacer(Modifier.height(16.dp))
                Text(
                    status.uppercase(),
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    letterSpacing = 1.5.sp,
                    color = when {
                        isRecording                          -> RecordingRed
                        mood == MainActivity.Mood.LISTENING -> CyanAccent
                        mood == MainActivity.Mood.THINKING  -> PrimaryViolet
                        mood == MainActivity.Mood.SPEAKING  -> MagentaAccent
                        else                                -> OnSurfaceVariant.copy(alpha = 0.7f)
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TranscriptCard(transcript = transcript)
            Spacer(Modifier.height(4.dp))
        }

        NaomiBottomBar(
            current = MainActivity.Screen.HOME,
            factCount = factCount,
            onNavigate = onNavigate
        )
    }
}

// ── FACTS ─────────────────────────────────────────────────────────────────────
@Composable
private fun FactsScreen(
    facts: Map<String, String>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onUpdate: (String, String, String) -> Unit,
    onDelete: (String) -> Unit,
    onNavigate: (MainActivity.Screen) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        ScreenTopBar(title = "What Naomi Remembers", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // Add button
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(PrimaryViolet.copy(alpha = 0.22f), CyanAccent.copy(alpha = 0.18f))
                        )
                    )
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null, onClick = onAdd
                    )
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = OnSurface, modifier = Modifier.size(20.dp))
                Text(
                    "Add a fact",
                    fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp, color = OnSurface
                )
            }

            if (facts.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🧠", fontSize = 40.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Nothing remembered yet",
                        fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp, color = OnSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Teach Naomi things like \"mom → Amma\" or \"home → HSR Layout\" so she understands you.",
                        fontFamily = InterFamily, fontSize = 13.sp,
                        color = OnSurfaceVariant.copy(alpha = 0.6f), textAlign = TextAlign.Center
                    )
                }
            } else {
                facts.forEach { (key, value) ->
                    FactCard(
                        factKey = key, factValue = value,
                        onSave = { k, v -> onUpdate(key, k, v) },
                        onDelete = { onDelete(key) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        NaomiBottomBar(
            current = MainActivity.Screen.FACTS,
            factCount = facts.size,
            onNavigate = onNavigate
        )
    }
}

/** A single fact shown as a card that flips into an inline editor when tapped. */
@Composable
private fun FactCard(
    factKey: String,
    factValue: String,
    onSave: (String, String) -> Unit,
    onDelete: () -> Unit,
) {
    var editing by remember(factKey, factValue) { mutableStateOf(false) }
    var keyText by remember(factKey) { mutableStateOf(factKey) }
    var valueText by remember(factValue) { mutableStateOf(factValue) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(GlassFill, RoundedCornerShape(16.dp))
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        if (!editing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        factKey.uppercase(),
                        fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium,
                        fontSize = 11.sp, letterSpacing = 1.sp, color = CyanAccent
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        factValue,
                        fontFamily = InterFamily, fontSize = 16.sp, color = OnSurface
                    )
                }
                IconButton(onClick = { editing = true }) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = OnSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = RecordingRed.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                }
            }
        } else {
            NaomiTextField(value = keyText, onValueChange = { keyText = it }, label = "Name (e.g. mom)")
            Spacer(Modifier.height(10.dp))
            NaomiTextField(value = valueText, onValueChange = { valueText = it }, label = "Value (e.g. Amma)")
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    text = "Save", icon = Icons.Outlined.Check, accent = SuccessGreen,
                    modifier = Modifier.weight(1f),
                    enabled = keyText.isNotBlank() && valueText.isNotBlank()
                ) { onSave(keyText, valueText); editing = false }
                PillButton(
                    text = "Cancel", icon = Icons.Outlined.Close, accent = OutlineColor,
                    modifier = Modifier.weight(1f), filled = false
                ) { keyText = factKey; valueText = factValue; editing = false }
            }
        }
    }
}

// ── ADD FACT ────────────────────────────────────────────────────────────────
@Composable
private fun AddFactScreen(
    factMode: Boolean,
    onBack: () -> Unit,
    onSave: (String, String) -> Unit,
    onVoiceFact: () -> Unit,
) {
    var keyText by remember { mutableStateOf("") }
    var valueText by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding(),
    ) {
        ScreenTopBar(title = "Add a Fact", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Teach Naomi something to remember. Use a short name and what it maps to.",
                fontFamily = InterFamily, fontSize = 14.sp,
                color = OnSurfaceVariant.copy(alpha = 0.7f)
            )

            NaomiTextField(value = keyText, onValueChange = { keyText = it }, label = "Name — e.g. mom, home, music app")
            NaomiTextField(value = valueText, onValueChange = { valueText = it }, label = "Value — e.g. Amma, HSR Layout, Wynk")

            PillButton(
                text = "Save fact", icon = Icons.Outlined.Check, accent = PrimaryViolet,
                modifier = Modifier.fillMaxWidth(),
                enabled = keyText.isNotBlank() && valueText.isNotBlank()
            ) { onSave(keyText, valueText) }

            // Divider "or"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(1.dp).background(GlassBorder))
                Text("  OR  ", fontFamily = SpaceGrotesk, fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.5f))
                Box(Modifier.weight(1f).height(1.dp).background(GlassBorder))
            }

            PillButton(
                text = if (factMode) "Listening…" else "Say it out loud",
                icon = Icons.Outlined.Mic,
                accent = CyanAccent,
                modifier = Modifier.fillMaxWidth(),
                filled = false
            ) { onVoiceFact() }
            Text(
                "Tip: say \"my mom is Amma\" and Naomi will store it for you.",
                fontFamily = InterFamily, fontSize = 12.sp,
                color = OnSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

// ── SETTINGS ────────────────────────────────────────────────────────────────
@Composable
private fun SettingsScreen(
    wakeEnabled: Boolean,
    smartMode: Boolean,
    voiceTrained: Boolean,
    setup: MainActivity.SetupStatus,
    voiceThreshold: Float,
    lastSim: Float,
    lastSimAt: Long,
    onThresholdChange: (Float) -> Unit,
    onBack: () -> Unit,
    onWakeToggle: () -> Unit,
    onSmartToggle: (Boolean) -> Unit,
    onTrain: () -> Unit,
    onOpenSetup: (MainActivity.Setup) -> Unit,
    onNavigate: (MainActivity.Screen) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        ScreenTopBar(title = "Settings", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            SectionLabel("Voice")
            SettingToggleRow(
                title = "Hey Naomi",
                subtitle = "Always-listening wake word",
                icon = Icons.Outlined.MicNone,
                accent = CyanAccent,
                checked = wakeEnabled,
                onToggle = { onWakeToggle() }
            )
            SettingToggleRow(
                title = "Smart Mode",
                subtitle = "Use the cloud for hard questions",
                icon = Icons.Outlined.AutoAwesome,
                accent = MagentaAccent,
                checked = smartMode,
                onToggle = { onSmartToggle(!smartMode) }
            )
            SettingActionRow(
                title = if (voiceTrained) "Re-train my voice" else "Train my voice",
                subtitle = if (voiceTrained) "Voice fingerprint saved" else "So only you can wake Naomi",
                icon = Icons.Outlined.RecordVoiceOver,
                accent = PrimaryViolet,
                done = voiceTrained,
                onClick = onTrain
            )
            VoiceMatchCard(
                threshold = voiceThreshold,
                lastSim = lastSim,
                lastSimAt = lastSimAt,
                onThresholdChange = onThresholdChange
            )

            Spacer(Modifier.height(8.dp))
            SectionLabel("Setup — permissions Naomi needs")
            SetupRow(
                title = "Microphone",
                subtitle = "Required to hear you",
                icon = Icons.Outlined.Mic,
                granted = setup.mic,
                onClick = { onOpenSetup(MainActivity.Setup.MIC) }
            )
            SetupRow(
                title = "App control",
                subtitle = "Accessibility — lets Naomi tap & type in apps",
                icon = Icons.Outlined.Accessibility,
                granted = setup.accessibility,
                onClick = { onOpenSetup(MainActivity.Setup.ACCESSIBILITY) }
            )
            SetupRow(
                title = "Unrestricted battery",
                subtitle = "Keeps \"Hey Naomi\" alive in the background",
                icon = Icons.Outlined.BatteryChargingFull,
                granted = setup.battery,
                onClick = { onOpenSetup(MainActivity.Setup.BATTERY) }
            )
            SetupRow(
                title = "Display over apps",
                subtitle = "Pops Naomi up when you call her",
                icon = Icons.Outlined.Layers,
                granted = setup.overlay,
                onClick = { onOpenSetup(MainActivity.Setup.OVERLAY) }
            )
            SetupRow(
                title = "Notifications",
                subtitle = "Shows the listening status",
                icon = Icons.Outlined.Notifications,
                granted = setup.notifications,
                onClick = { onOpenSetup(MainActivity.Setup.NOTIFICATIONS) }
            )
            SetupRow(
                title = "All app permissions",
                subtitle = "Contacts, phone, SMS, calendar, location",
                icon = Icons.Outlined.Lock,
                granted = null,
                onClick = { onOpenSetup(MainActivity.Setup.ALL_PERMISSIONS) }
            )
            SetupRow(
                title = "Set as default assistant",
                subtitle = "Optional — launch Naomi with the home gesture",
                icon = Icons.Outlined.AutoAwesome,
                granted = null,
                onClick = { onOpenSetup(MainActivity.Setup.ASSISTANT) }
            )
            Spacer(Modifier.height(12.dp))
        }

        NaomiBottomBar(
            current = MainActivity.Screen.SETTINGS,
            factCount = -1,
            onNavigate = onNavigate
        )
    }
}

// ── Shared building blocks ────────────────────────────────────────────────────

@Composable
private fun LogoBadge() {
    Box(
        Modifier
            .size(32.dp)
            .background(Brush.linearGradient(listOf(PrimaryViolet, CyanAccent)), CircleShape)
            .border(1.dp, GlassBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text("N", color = Color(0xFF0F131F), fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun ScreenTopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = OnSurface)
        }
        Text(
            title,
            fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp, color = OnSurface,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, letterSpacing = 1.5.sp,
        color = OnSurfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp, start = 4.dp)
    )
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(GlassFill, RoundedCornerShape(16.dp))
            .border(1.dp, if (checked) accent.copy(alpha = 0.4f) else GlassBorder, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, onClick = onToggle
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon, if (checked) accent else OutlineColor)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = OnSurface)
            Text(subtitle, fontFamily = InterFamily, fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.6f))
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accent.copy(alpha = 0.8f),
                uncheckedThumbColor = OutlineColor,
                uncheckedTrackColor = SurfaceHigh
            )
        )
    }
}

@Composable
private fun SettingActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    done: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(GlassFill, RoundedCornerShape(16.dp))
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon, accent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = OnSurface)
            Text(subtitle, fontFamily = InterFamily, fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.6f))
        }
        if (done) Icon(Icons.Outlined.Check, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(20.dp))
    }
}

/**
 * Voice-match tuning: a slider for the ECAPA similarity threshold plus a read-out of the last
 * observed score, so the user can fine-tune it themselves. The last score is compared live
 * against the slider position, so dragging shows whether that attempt would now pass.
 */
@Composable
private fun VoiceMatchCard(
    threshold: Float,
    lastSim: Float,
    lastSimAt: Long,
    onThresholdChange: (Float) -> Unit,
) {
    var pos by remember(threshold) { mutableStateOf(threshold) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(GlassFill, RoundedCornerShape(16.dp))
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(Icons.Outlined.GraphicEq, CyanAccent)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Voice match strictness", fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = OnSurface)
                Text("Higher = only your voice wakes Naomi", fontFamily = InterFamily, fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.6f))
            }
            Text(
                String.format(java.util.Locale.US, "%.2f", pos),
                fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = CyanAccent
            )
        }
        Slider(
            value = pos,
            onValueChange = { pos = it },
            onValueChangeFinished = { onThresholdChange(pos) },
            valueRange = 0.30f..0.90f,
            colors = SliderDefaults.colors(
                thumbColor = CyanAccent,
                activeTrackColor = CyanAccent.copy(alpha = 0.85f),
                inactiveTrackColor = SurfaceHigh
            )
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Lenient", fontFamily = InterFamily, fontSize = 10.sp, color = OnSurfaceVariant.copy(alpha = 0.5f))
            Text("Strict", fontFamily = InterFamily, fontSize = 10.sp, color = OnSurfaceVariant.copy(alpha = 0.5f))
        }
        Spacer(Modifier.height(10.dp))
        if (lastSim >= 0f) {
            val passed = lastSim >= pos
            val rel = DateUtils.getRelativeTimeSpanString(
                lastSimAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            ).toString()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Last wake attempt:", fontFamily = InterFamily, fontSize = 13.sp, color = OnSurfaceVariant)
                Text(
                    String.format(java.util.Locale.US, "%.2f", lastSim) + (if (passed) " · would match" else " · would reject"),
                    fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    color = if (passed) SuccessGreen else RecordingRed
                )
            }
            Text(rel, fontFamily = InterFamily, fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.5f))
        } else {
            Text(
                "No verified wake yet. Lock the phone and say \"Naomi\" — the score shows here.",
                fontFamily = InterFamily, fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.55f)
            )
        }
    }
}

/**
 * A permission/access row: shows a green tick when granted, or a "Set up" chip when not.
 * [granted] = null means we can't reliably detect it (e.g. runtime permission bundles).
 */
@Composable
private fun SetupRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    granted: Boolean?,
    onClick: () -> Unit,
) {
    val borderColor = when (granted) {
        true -> SuccessGreen.copy(alpha = 0.35f)
        else -> GlassBorder
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(GlassFill, RoundedCornerShape(16.dp))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon, if (granted == true) SuccessGreen else PrimaryViolet)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = OnSurface)
            Text(subtitle, fontFamily = InterFamily, fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.6f))
        }
        if (granted == true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Outlined.Check, contentDescription = "Enabled", tint = SuccessGreen, modifier = Modifier.size(18.dp))
            }
        } else {
            Text(
                "Set up",
                fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                color = BgColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(PrimaryViolet)
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
    }
}

@Composable
private fun RowIcon(icon: ImageVector, tint: Color) {
    Box(
        Modifier
            .size(38.dp)
            .background(tint.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** A rounded action button — filled (gradient-ish tint) or outlined. */
@Composable
private fun PillButton(
    text: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .then(
                if (filled) Modifier.background(accent.copy(alpha = 0.9f * alpha))
                else Modifier.border(1.5.dp, accent.copy(alpha = 0.6f * alpha), RoundedCornerShape(50))
            )
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null, onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, contentDescription = null,
            tint = (if (filled) BgColor else accent).copy(alpha = alpha),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            color = (if (filled) BgColor else accent).copy(alpha = alpha)
        )
    }
}

@Composable
private fun NaomiTextField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontFamily = InterFamily, fontSize = 13.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CyanAccent.copy(alpha = 0.7f),
            unfocusedBorderColor = GlassBorder,
            focusedTextColor = OnSurface,
            unfocusedTextColor = OnSurface,
            cursorColor = CyanAccent,
            focusedLabelColor = CyanAccent,
            unfocusedLabelColor = OnSurfaceVariant.copy(alpha = 0.6f),
            focusedContainerColor = GlassFill,
            unfocusedContainerColor = GlassFill,
        )
    )
}

// ── Bottom navigation bar (Home / Facts / Settings) ────────────────────────────
@Composable
private fun NaomiBottomBar(
    current: MainActivity.Screen,
    factCount: Int,
    onNavigate: (MainActivity.Screen) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(BgColor.copy(alpha = 0.6f))
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(GlassBorder, Color.Transparent)),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            )
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BottomNavItem(
            icon = Icons.Outlined.Home, label = "Home",
            selected = current == MainActivity.Screen.HOME,
            onClick = { onNavigate(MainActivity.Screen.HOME) }
        )
        BottomNavItem(
            icon = Icons.Outlined.Bookmark,
            label = if (factCount > 0) "Facts ($factCount)" else "Facts",
            selected = current == MainActivity.Screen.FACTS,
            onClick = { onNavigate(MainActivity.Screen.FACTS) }
        )
        BottomNavItem(
            icon = Icons.Outlined.Settings, label = "Settings",
            selected = current == MainActivity.Screen.SETTINGS,
            onClick = { onNavigate(MainActivity.Screen.SETTINGS) }
        )
    }
}

@Composable
private fun BottomNavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) CyanAccent else OutlineColor
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.5.sp, color = tint)
    }
}

// ── Transcript card ───────────────────────────────────────────────────────────
@Composable
private fun TranscriptCard(transcript: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 240.dp)
            .background(GlassFill, RoundedCornerShape(20.dp))
            .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
        contentAlignment = if (transcript.isBlank()) Alignment.Center else Alignment.TopStart
    ) {
        if (transcript.isBlank()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("💬", fontSize = 28.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your conversation appears here",
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    color = OnSurfaceVariant.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            val lines = transcript.split("\n\n")
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                lines.forEach { line ->
                    val isNaomi = line.startsWith("Naomi:")
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isNaomi) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .offset(y = 5.dp)
                                    .background(PrimaryViolet, CircleShape)
                            )
                        }
                        Text(
                            line,
                            fontFamily = InterFamily,
                            fontSize = 15.sp,
                            color = if (isNaomi) OnSurface else OnSurface.copy(alpha = 0.75f),
                            lineHeight = 22.sp
                        )
                    }
                }
            }
        }
    }
}

// ── Splash screen ─────────────────────────────────────────────────────────────
/**
 * One vertical strip of the "N" — the glyph is sliced into upright columns (Netflix-ribbon
 * style). Positions are offsets from the canvas centre, in px.
 */
private data class NStrip(
    val cx: Float,          // target centre X (relative to canvas centre)
    val yc: Float,          // target centre Y
    val halfH: Float,       // target half-height of the column
    val strokeW: Float,     // draw width of the column
    val flightColor: Color, // vivid colour while swirling in
    val restColor: Color,   // brand colour once assembled into the solid N
    val turns: Float,       // spiral revolutions on the way in (signed)
    val distNorm: Float,    // |cx| / (halfWidth): 0 at centre → 1 at outer edge
    val entryStart: Float,  // 0..1 stagger for the swirl-in
    val exitStart: Float    // 0..1 stagger for the spread-out
)

/** Palette the strips flash through while swirling in from the ring. */
private val SHARD_COLORS = listOf(
    Color(0xFF00D9FF), // cyan
    Color(0xFF8D7FFF), // violet
    Color(0xFFDE4DFF), // magenta
    Color(0xFF3DD68C), // green
    Color(0xFFFFB454), // amber
    Color(0xFFFF5C8A), // pink
    Color(0xFF5AA9FF), // blue
    Color(0xFFFFFFFF), // white spark
)

/** Slice the "N" into [K] vertical strips: full-height on the two bars, a descending band
 *  through the diagonal in the middle. */
private fun buildNStrips(s: Float): List<NStrip> {
    val h   = s * 0.72f          // glyph height
    val w   = s * 0.56f          // glyph width (outer edge to outer edge)
    val bar = w * 0.24f          // width of each vertical bar
    val k   = 50                 // number of vertical strips
    val stripW = w / k
    val innerL = -w / 2f + bar   // inner edge of the left bar
    val innerR =  w / 2f - bar   // inner edge of the right bar
    val bandHalf = bar * 0.72f   // vertical half-thickness of the diagonal band
    val halfW = w / 2f

    val out = ArrayList<NStrip>()
    for (i in 0 until k) {
        val cx = -w / 2f + (i + 0.5f) * stripW
        val yc: Float
        val halfH: Float
        if (cx <= innerL || cx >= innerR) {
            yc = 0f; halfH = h / 2f                          // left / right bar — full height
        } else {
            val f = (cx - innerL) / (innerR - innerL)        // 0..1 across the middle
            yc = -h / 2f + bandHalf + f * (h - 2f * bandHalf) // diagonal descends top-left→bottom-right
            halfH = bandHalf
        }
        val g = ((yc + h / 2f) / h).coerceIn(0f, 1f)
        out += NStrip(
            cx = cx, yc = yc, halfH = halfH, strokeW = stripW,
            flightColor = SHARD_COLORS[i % SHARD_COLORS.size],
            restColor = lerpColor(Color(0xFF8D7FFF), Color(0xFF00D9FF), g),
            turns = if (i % 2 == 0) 1.6f else -2.0f,
            distNorm = (abs(cx) / halfW).coerceIn(0f, 1f),
            entryStart = (abs(cx) / halfW) * 0.35f,           // centre assembles first, edges last
            exitStart  = (1f - abs(cx) / halfW) * 0.16f       // edges spread first
        )
    }
    return out
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red   = a.red   + (b.red   - a.red)   * t,
    green = a.green + (b.green - a.green) * t,
    blue  = a.blue  + (b.blue  - a.blue)  * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t
)

/**
 * Netflix-ribbon "N" splash:
 *  A) coloured vertical strips swirl out from a central ring and pack tightly into a solid N,
 *  B) hold as the colours settle into the brand gradient,
 *  C) the strips slide apart horizontally while growing taller — a 3D "exploding blinds" reveal.
 * Shown on manual launches only — not on voice wake, to avoid startup lag.
 */
@Composable
private fun NaomiSplash(onComplete: () -> Unit) {
    val entry   = remember { Animatable(0f) } // 0..1 swirl-in + assemble
    val settle  = remember { Animatable(0f) } // 0..1 flight→brand colour blend
    val exit    = remember { Animatable(0f) } // 0..1 spread-out + grow
    val screenAlpha = remember { Animatable(1f) }
    val glow    = remember { Animatable(0f) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        launch { glow.animateTo(1f, animationSpec = tween(900, easing = FastOutSlowInEasing)) }
        entry.animateTo(1f, animationSpec = tween(1150, easing = FastOutSlowInEasing))
        settle.animateTo(1f, animationSpec = tween(340, easing = LinearEasing))
        delay(320)
        launch {
            delay(300)
            screenAlpha.animateTo(0f, animationSpec = tween(460, easing = LinearEasing))
        }
        exit.animateTo(1f, animationSpec = tween(720, easing = FastOutSlowInEasing))
        onComplete()
    }

    Box(
        Modifier
            .fillMaxSize()
            .alpha(screenAlpha.value)
            .background(BgColor),
        contentAlignment = Alignment.Center
    ) {
        // Ambient radial glow that blooms with the assembly, then dims as the strips fly apart.
        Box(
            Modifier
                .size((200 * (0.4f + glow.value * 0.8f)).dp)
                .alpha(0.35f * glow.value * (1f - exit.value))
                .background(
                    Brush.radialGradient(listOf(PrimaryViolet.copy(alpha = 0.30f), Color.Transparent)),
                    CircleShape
                )
        )

        Canvas(Modifier.fillMaxSize()) {
            val s = size.minDimension * 0.5f
            val strips = buildNStrips(s)
            val cx0 = size.width / 2f
            val cy0 = size.height / 2f
            val twoPi = (Math.PI * 2f).toFloat()
            val ringR = s * 0.06f          // radius of the ring the strips start on

            // How far apart the strips fan, and how much taller they grow, during the exit.
            val spread = 2.4f
            val grow   = 2.2f

            for (st in strips) {
                // Phase A — swirl out from the ring and pack into the N (staggered per strip).
                val eRaw = ((entry.value - st.entryStart) / (1f - st.entryStart)).coerceIn(0f, 1f)
                val e = FastOutSlowInEasing.transform(eRaw)

                val targetRad = hypot(st.cx, st.yc)
                val targetAng = atan2(st.yc, st.cx)
                val rad = ringR + (targetRad - ringR) * e
                val ang = targetAng + (1f - e) * st.turns * twoPi

                var px = cx0 + rad * cos(ang)
                var py = cy0 + rad * sin(ang)
                var halfH = st.halfH * (0.05f + 0.95f * e)
                var alpha = eRaw
                val color = lerpColor(st.flightColor, st.restColor, settle.value)

                // Phase C — slide apart horizontally + grow taller (outer strips grow most → 3D).
                if (exit.value > 0f) {
                    val xRaw = ((exit.value - st.exitStart) / (1f - st.exitStart)).coerceIn(0f, 1f)
                    val xe = FastOutSlowInEasing.transform(xRaw)
                    px = cx0 + st.cx * (1f + spread * xe)
                    py = cy0 + st.yc
                    halfH = st.halfH * (1f + grow * xe * (0.6f + st.distNorm))
                    alpha = 1f - xe * xe
                }

                if (alpha <= 0.01f) continue
                drawLine(
                    color = color.copy(alpha = alpha.coerceIn(0f, 1f)),
                    start = Offset(px, py - halfH),
                    end   = Offset(px, py + halfH),
                    strokeWidth = st.strokeW,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

// ── Orbit stars ───────────────────────────────────────────────────────────────
private val DEG_TO_RAD = (Math.PI / 180.0).toFloat()

private data class OrbitStar(
    val rMult: Float, val a0Deg: Float, val yScale: Float,
    val dotR: Float, val alpha: Float,
    val tint: Color = Color.White, val reversed: Boolean = false
)

private val ORBIT_STARS = listOf(
    // inner ring (clockwise)
    OrbitStar(1.32f,   0f, 0.40f, 2.5f, 0.85f, Color(0xFF00D9FF)),
    OrbitStar(1.40f,  45f, 0.60f, 1.8f, 0.60f),
    OrbitStar(1.35f,  90f, 0.35f, 3.0f, 0.80f, Color(0xFFC6BFFF)),
    OrbitStar(1.44f, 135f, 0.55f, 2.0f, 0.55f),
    OrbitStar(1.38f, 180f, 0.45f, 2.5f, 0.90f, Color(0xFF00D9FF)),
    OrbitStar(1.42f, 225f, 0.50f, 1.5f, 0.60f, Color(0xFFC6BFFF)),
    OrbitStar(1.30f, 270f, 0.65f, 3.0f, 0.85f),
    OrbitStar(1.46f, 315f, 0.30f, 2.0f, 0.65f),
    // outer ring (counter-clockwise)
    OrbitStar(1.55f,  22f, 0.50f, 1.8f, 0.35f, Color(0xFF00D9FF), reversed = true),
    OrbitStar(1.60f, 112f, 0.40f, 2.0f, 0.30f, reversed = true),
    OrbitStar(1.52f, 202f, 0.55f, 1.5f, 0.35f, Color(0xFFC6BFFF), reversed = true),
    OrbitStar(1.58f, 292f, 0.45f, 1.8f, 0.28f, reversed = true),
)

// ── Voice Orb ─────────────────────────────────────────────────────────────────
@Composable
private fun NaomiOrb(
    mood: MainActivity.Mood,
    isRecording: Boolean,
    onTap: () -> Unit
) {
    val orbColor by animateColorAsState(
        targetValue = when {
            isRecording                          -> RecordingRed
            mood == MainActivity.Mood.LISTENING -> CyanAccent
            mood == MainActivity.Mood.THINKING  -> PrimaryViolet
            mood == MainActivity.Mood.SPEAKING  -> MagentaAccent
            else                                -> PrimaryViolet
        },
        animationSpec = tween(400),
        label = "orbColor"
    )

    val inf = rememberInfiniteTransition(label = "orb")

    // Idle / speaking / recording: gentle breathe
    val breathe by inf.animateFloat(
        initialValue = 0.95f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(2000, easing = EaseInOut), RepeatMode.Reverse),
        label = "breathe"
    )

    // Listening: three ring expansion values (staggered)
    val ring1 by inf.animateFloat(
        initialValue = 1f, targetValue = 2.4f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "ring1"
    )
    val ring2 by inf.animateFloat(
        initialValue = 1f, targetValue = 2.4f,
        animationSpec = infiniteRepeatable(tween(1800, delayMillis = 600, easing = LinearEasing), RepeatMode.Restart),
        label = "ring2"
    )
    val ring3 by inf.animateFloat(
        initialValue = 1f, targetValue = 2.4f,
        animationSpec = infiniteRepeatable(tween(1800, delayMillis = 1200, easing = LinearEasing), RepeatMode.Restart),
        label = "ring3"
    )

    // Thinking: spinning arc
    val arcRotation by inf.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "arcRot"
    )

    // Stars orbiting the orb (always active)
    val orbitAngle by inf.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Restart),
        label = "orbit"
    )

    val orbSize = 180.dp

    Box(
        Modifier.size(300.dp),
        contentAlignment = Alignment.Center
    ) {
        // Rings (LISTENING only)
        if (mood == MainActivity.Mood.LISTENING) {
            listOf(ring1, ring2, ring3).forEach { s ->
                val alpha = (1f - (s - 1f) / 1.4f).coerceIn(0f, 0.6f)
                Box(
                    Modifier
                        .size(orbSize)
                        .scale(s)
                        .border(2.dp, CyanAccent.copy(alpha = alpha), CircleShape)
                )
            }
        }

        // Ambient glow halo
        Box(
            Modifier
                .size(orbSize + 40.dp)
                .background(
                    Brush.radialGradient(
                        listOf(orbColor.copy(alpha = 0.18f), Color.Transparent)
                    ),
                    CircleShape
                )
        )

        // Orbiting stars
        Canvas(Modifier.size(300.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val orbPx = 90.dp.toPx()
            ORBIT_STARS.forEach { star ->
                val a = (if (star.reversed) -orbitAngle + star.a0Deg
                         else orbitAngle + star.a0Deg) * DEG_TO_RAD
                val r = orbPx * star.rMult
                val x = cx + r * cos(a)
                val y = cy + r * sin(a) * star.yScale
                drawCircle(
                    color = star.tint.copy(alpha = star.alpha),
                    radius = star.dotR.dp.toPx(),
                    center = Offset(x, y)
                )
            }
        }

        // Thinking: spinning arc on canvas
        if (mood == MainActivity.Mood.THINKING) {
            Canvas(Modifier.size(orbSize + 20.dp)) {
                rotate(arcRotation) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Color.Transparent, PrimaryViolet, Color.Transparent)),
                        startAngle = 0f,
                        sweepAngle = 160f,
                        useCenter = false,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }
        }

        // Core orb
        val coreScale = when (mood) {
            MainActivity.Mood.LISTENING -> 1f
            else                        -> breathe
        }
        Box(
            Modifier
                .size(orbSize)
                .scale(coreScale)
                .background(
                    Brush.radialGradient(
                        listOf(orbColor.copy(alpha = 0.9f), orbColor.copy(alpha = 0.5f))
                    ),
                    CircleShape
                )
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.3f), Color.White.copy(alpha = 0.05f))
                    ),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTap
                ),
            contentAlignment = Alignment.Center
        ) {
            // Inner glass highlight
            Box(
                Modifier
                    .size(orbSize * 0.55f)
                    .offset(x = (-12).dp, y = (-16).dp)
                    .background(
                        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.15f), Color.Transparent)),
                        CircleShape
                    )
            )
        }
    }
}
