# Naomi — voice assistant (first slice)

A hybrid Android voice assistant:

- **Offline-first:** timers, alarms, time/date, and "open <app>" work with no internet and no API key.
- **Cloud-smart:** anything else goes to the **Gemini API** for a real answer.
- **Voice in / voice out:** Android `SpeechRecognizer` (ears) + `TextToSpeech` (mouth).

```
Tap 🎤 → SpeechRecognizer → AssistantBrain ┬─ CommandRouter (offline)  → reply
                                            └─ GeminiClient (cloud)     → reply → TextToSpeech 🔊
```

## Code map

| File | Role |
|------|------|
| `MainActivity.kt`  | UI + wiring: mic button, permission, transcript |
| `VoiceInput.kt`    | Speech-to-text (the "ears") |
| `Speaker.kt`       | Text-to-speech (the "mouth") |
| `CommandRouter.kt` | **Offline brain** — add more local commands here |
| `GeminiClient.kt`  | **Cloud brain** — Gemini REST call |
| `AssistantBrain.kt`| Decides offline-vs-cloud |

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

### 5. Add your Gemini API key (never hard-code it)
Get a free key at https://aistudio.google.com → "Get API key".

In the project root `local.properties` (this file is git-ignored — keep it private):
```
GEMINI_API_KEY=your_key_here
```

Expose it to the app — in `app/build.gradle.kts`, inside `android { defaultConfig { } }`:
```kotlin
val geminiKey = project.rootProject.file("local.properties").let { f ->
    if (f.exists()) java.util.Properties().apply { load(f.inputStream()) }
        .getProperty("GEMINI_API_KEY", "") else ""
}
buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")
```

### 6. Run
Plug in an Android phone (USB debugging on) or start the emulator → press **Run ▶**.
Grant the microphone prompt, tap **Talk**, and try:
- "set a timer for 2 minutes"  *(offline)*
- "what time is it"            *(offline)*
- "open settings"             *(offline)*
- "tell me a joke"            *(cloud → Gemini)*

---

## Roadmap (next increments)
1. **"Hey Naomi" wake word** — Porcupine or openWakeWord, so you don't tap.
2. **Fully-offline STT** — Vosk (the engine Dicio uses) instead of SpeechRecognizer.
3. **Offline cloud-quality brain** — Gemini Nano via AICore / ML Kit GenAI.
4. **More actions** — calendar, messages, music, smart home (add handlers to `CommandRouter`).
5. **Background/always-listening service** — foreground service + notification.
