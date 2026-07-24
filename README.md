# Naomi — a personal voice assistant

A hybrid Android ("Jarvis"-style) voice assistant, offline-first with a cloud brain when needed.

- **Offline-first:** timers, alarms, time/date, calls, messages, music, maps, torch, Wi-Fi/Bluetooth,
  calendar, and "open \<app\>" work with no internet — handled by the on-device keyword router.
- **Cloud-smart:** anything else is parsed into an action or answered by the **Groq API** (with a
  Gemini fallback), and a fully-offline on-device **Gemma** brain backs it up when there's no network.
- **Hands-free:** an offline **"Naomi" wake word** (Vosk) launches it from the lock screen, and it
  holds **multi-turn conversations** — when it asks a question, the mic reopens for your answer.
- **Voice in / voice out:** Android `SpeechRecognizer` (ears) + `TextToSpeech` (mouth).

```
"Naomi" / tap 🎤 → SpeechRecognizer → AssistantBrain ┬─ CommandRouter  (offline actions)
                                                      ├─ Groq / Gemini  (cloud intent + chat)
                                                      └─ LocalBrain      (offline Gemma)  → TextToSpeech 🔊
```

## Code map

See **[`FILES.md`](FILES.md)** for a one-line description of every source file. The essentials:

| File | Role |
|------|------|
| `MainActivity.kt`   | Compose UI + wiring: orb, screens, wake intents, follow-ups, splash |
| `VoiceInput.kt`     | Speech-to-text (the "ears") |
| `Speaker.kt`        | Text-to-speech (the "mouth") |
| `WakeService.kt`    | Offline "Naomi" wake word + follow-up listening |
| `CommandRouter.kt`  | **Offline brain** + all device actions — add more commands here |
| `GroqClient.kt`     | **Cloud brain** — intent parsing + chat |
| `LocalBrain.kt`     | **Offline LLM** fallback (Gemma) |
| `AssistantBrain.kt` | Orchestrates router ↔ cloud ↔ offline, and multi-turn follow-ups |

---

## One-time setup

### 1. Finish Android Studio's first launch
When Android Studio opens, accept the defaults — it downloads the Android SDK and lets you
create an emulator. (This GUI step can't be automated.)

### 2. Create the project
`New Project` → **Empty Activity** (the Compose one). Set:
- **Name:** `Naomi`
- **Package name:** `com.naomi.assistant`
- **Minimum SDK:** API 26 (Android 8.0)
- **Build language:** Kotlin DSL (`build.gradle.kts`)

This generates a guaranteed-buildable skeleton on *your* machine (correct plugin/Kotlin
versions for your install — which is why we don't hand-write them here).

### 3. Drop in Naomi's code
Replace the generated `app/src/main/java/com/naomi/assistant/` and `AndroidManifest.xml`
with the files from this folder (same paths). Delete the auto-generated `ui/theme` files only
if they cause conflicts — otherwise leave them.

### 4. Add the dependencies
In `app/build.gradle.kts`, inside `dependencies { }`:

```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")   // Gemini REST call
implementation("androidx.activity:activity-compose:1.9.3")
implementation(platform("androidx.compose:compose-bom:2024.10.00"))
implementation("androidx.compose.material3:material3")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
```
(Android Studio will flag newer versions — accept its suggestions.)

Enable Compose + BuildConfig in the same file, inside `android { }`:

```kotlin
buildFeatures {
    compose = true
    buildConfig = true
}
```

### 5. Add your API keys (never hard-code them)
- **Groq** (primary cloud brain) — free key at https://console.groq.com → "API Keys".
- **Gemini** (optional fallback) — free key at https://aistudio.google.com → "Get API key".

In the project root `local.properties` (git-ignored — keep it private):
```
GROQ_API_KEY=your_groq_key_here
GEMINI_API_KEY=your_gemini_key_here
```

Expose them to the app — in `app/build.gradle.kts`, inside `android { defaultConfig { } }`:
```kotlin
val props = project.rootProject.file("local.properties").let { f ->
    if (f.exists()) java.util.Properties().apply { load(f.inputStream()) } else java.util.Properties()
}
buildConfigField("String", "GROQ_API_KEY",   "\"${props.getProperty("GROQ_API_KEY", "")}\"")
buildConfigField("String", "GEMINI_API_KEY", "\"${props.getProperty("GEMINI_API_KEY", "")}\"")
```

### 6. Run
Plug in an Android phone (USB debugging on) or start the emulator → press **Run ▶**.
Grant the microphone prompt, tap **Talk**, and try:
- "set a timer for 2 minutes"  *(offline)*
- "what time is it"            *(offline)*
- "open settings"             *(offline)*
- "tell me a joke"            *(cloud → Gemini)*

---

## Status

Shipped since the first slice:
- ✅ **"Naomi" wake word** — offline via Vosk, launches from lock/background.
- ✅ **Background always-listening** — `WakeService` foreground mic service.
- ✅ **Many more actions** — calls, WhatsApp voice/video calls, messages, music, maps, rides,
  food, notes, email, torch, Wi-Fi/Bluetooth, calendar (all in `CommandRouter`).
- ✅ **Multi-turn conversations** — a question reopens the mic; answers route through Groq with context.
- ✅ **On-device brain** — Gemma 3 1B via MediaPipe for an offline fallback.

Roadmap:
1. **Per-voice wake** — finish tuning speaker verification (`SpeakerVerifier` / `VoiceEnrollment`).
2. **Bigger on-device model** — Phi-3.5 / newer Gemma once it fits the 2 GB task limit.
3. **True WhatsApp auto-send** — via the accessibility service, beyond pre-filled chats.
