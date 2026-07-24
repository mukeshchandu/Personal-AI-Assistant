# Naomi — a personal voice assistant for Android

A hands-free, offline-first ("Jarvis"-style) voice assistant. Say **"Naomi"** and ask it to set a
timer, call someone, message on WhatsApp, play music, open apps, check the weather, and more — most
of it works with no internet. When you need a real answer it uses a cloud brain (Groq), and it can
even fall back to a fully on-device LLM.

- **Offline-first:** timers, alarms, time/date, calls, WhatsApp voice/video calls, SMS & WhatsApp
  messages, music, maps/navigation, rides, food, notes, torch, Wi-Fi/Bluetooth, volume, calendar,
  and "open \<app\>" — all handled on-device by the keyword router.
- **Cloud-smart:** anything else is parsed into an action or answered by the **Groq API** (optional,
  free key), with a Gemini fallback.
- **Hands-free:** an offline **"Naomi" wake word** (Vosk) launches it from the lock screen.
- **Multi-turn:** when it asks a question, the mic reopens for your answer.

```
"Naomi" / tap 🎤 → SpeechRecognizer → AssistantBrain ┬─ CommandRouter  (offline actions)
                                                      ├─ Groq / Gemini  (cloud intent + chat)
                                                      └─ LocalBrain      (offline Gemma)  → TextToSpeech 🔊
```

---

## 📲 Just want to try it? (no build needed)

Download the latest **`app-debug.apk`** from the [**Releases**](../../releases) page and install it on
an Android phone (Android 8.0+). You'll need to allow "install from unknown sources".

> The published APK has **cloud features off** (it ships with no API key). Offline commands, the
> "Naomi" wake word, and the on-device brain all work. To enable the smart/cloud brain, build from
> source with your own free Groq key (below).

---

## 🛠 Build from source

**Requirements:** Android Studio (latest), a phone with Android 8.0+ (API 26) and USB debugging on.

```bash
git clone https://github.com/mukeshchandu/Personal-AI-Assistant.git
cd Personal-AI-Assistant
```

Open the folder in Android Studio and press **Run ▶** (or `./gradlew :app:assembleDebug`).
It builds and runs as-is — offline features work immediately.

### Enable the cloud brain (optional)
Get a free key at https://console.groq.com → "API Keys" (Gemini optional: https://aistudio.google.com).
Create a `local.properties` file in the project root (it's git-ignored — never commit it):

```
sdk.dir=/path/to/your/Android/sdk
GROQ_API_KEY=your_groq_key_here
GEMINI_API_KEY=your_gemini_key_here
```

Rebuild, and turn on **Smart mode** in the app.

---

## First run

Grant the mic (and, for calls/messages, contacts + phone) permissions. Then try:
- "set a timer for 2 minutes"  *(offline)*
- "call \<contact\>" / "WhatsApp video call \<contact\>"  *(offline)*
- "what's the weather in Bangalore"
- "play \<song\>"  •  "open settings"
- Turn on the **"Naomi" wake word** and say "Naomi" from the lock screen.

The wake word and voice models are bundled in `app/src/main/assets/` (Vosk + ONNX), so it works out
of the box. The larger on-device chat model (Gemma) is optional and loaded from device storage
separately.

---

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

## Notes & privacy

- Your API keys live only in `local.properties` (git-ignored) and are compiled into *your* build —
  they're never in the source or the published APK.
- The "Naomi" wake word runs fully on-device (Vosk); audio for wake detection isn't sent anywhere.
- This is a personal/hobby project — the WhatsApp calling and accessibility features depend on those
  apps' current layouts and may need tweaks over time.

## Roadmap
1. **Per-voice wake** — finish tuning speaker verification (`SpeakerVerifier` / `VoiceEnrollment`).
2. **Bigger on-device model** — Phi-3.5 / newer Gemma once it fits the task-model size limit.
3. **True WhatsApp auto-send** — via the accessibility service, beyond pre-filled chats.
