# File guide

A brief map of every source file in `app/src/main/java/com/naomi/assistant/`.

## Core flow

| File | What it does |
|------|--------------|
| `MainActivity.kt` | The whole UI + wiring (Jetpack Compose). Renders the animated orb, the screens (home / facts / settings), the splash animation, and the transcript. Handles mic taps, permissions, wake-word launch intents, follow-up listening & barge-in, and the "power / back / home → stop everything" reset. |
| `AssistantBrain.kt` | The orchestrator. Runs each turn through: keyword router → LLM (cloud or on-device) → chat fallback. Manages multi-turn follow-ups, conversation history, and "reply-is-a-question → reopen the mic". |
| `CommandRouter.kt` | The offline/keyword brain **and** every device action: timers, alarms, time/date, calls, WhatsApp voice/video calls, SMS & WhatsApp messages, music, maps/navigation, rides, food, notes, email, torch, Wi-Fi, Bluetooth, volume, calendar, voice recording, and opening apps/settings. Shared by both the keyword path and the LLM path. |

## Ears & mouth

| File | What it does |
|------|--------------|
| `VoiceInput.kt` | Speech-to-text ("ears") over Android `SpeechRecognizer`. Prefers en-IN, fires `onSpeechStart` for barge-in, and can `cancel()` mid-turn. |
| `Speaker.kt` | Text-to-speech ("mouth") over Android `TextToSpeech`. Picks a female en-IN voice and calls back when done (so Naomi can listen again). |
| `WakeService.kt` | Foreground mic service. Offline "Naomi" wake-word spotting (Vosk) plus a restricted-grammar listener for follow-up answers. Adds echo/noise cancellation, survives screen-off, and launches the UI from lock/background via a full-screen intent. Optionally gates the wake on the user's own voice. |

## Brains

| File | What it does |
|------|--------------|
| `GroqClient.kt` | Cloud brain via Groq (OpenAI-compatible). Parses requests into structured JSON actions (tool-calling), answers open questions, and fuzzy-resolves mis-heard contact names. Takes conversation history for context. |
| `GeminiClient.kt` | Legacy cloud brain via the Gemini REST API. Kept as a fallback behind Groq. |
| `LocalBrain.kt` | Fully-offline on-device LLM (Gemma 3 1B int4 via MediaPipe). The conversational fallback when smart mode is off or there's no network. |

## Memory & extras

| File | What it does |
|------|--------------|
| `MemoryStore.kt` | Persistent user facts (`naomi_facts.json`), e.g. `mom → Amma`, `home → HSR Layout`. Normalises keys and resolves spoken names to real contacts. |
| `WeatherClient.kt` | Free weather lookup via open-meteo.com (geocode a city → current temp + condition), returned as one spoken sentence. |
| `VoiceRecorder.kt` | In-app voice memo recorder — saves M4A files to public storage via MediaStore. |
| `WhatsAppSender.kt` | Accessibility service. Auto-taps WhatsApp's Send button after Naomi opens a chat, and provides general on-screen control (tap / scroll / type by voice) for other apps. |
| `BootReceiver.kt` | Re-arms the wake listener after a reboot, if the user had it enabled. |

## Voice verification (per-user wake)

| File | What it does |
|------|--------------|
| `SpeakerVerifier.kt` | Speaker embedder backed by an ECAPA-TDNN ONNX model — turns audio into a voice fingerprint. |
| `VoiceEnrollment.kt` | Stores the user's voice as an averaged neural embedding and verifies incoming audio by cosine similarity. |
| `FBankExtractor.kt` | Kaldi-compatible 80-dim log-mel filterbank feature extractor feeding the verifier. Pure Kotlin, no deps. |

## Theme

| File | What it does |
|------|--------------|
| `ui/theme/Color.kt` | Colour palette. |
| `ui/theme/Theme.kt` | Compose Material theme. |
| `ui/theme/Type.kt` | Typography (fonts). |

---

> Not in this repo (intentionally): Gradle build files, `local.properties` (holds your API keys),
> and the bundled model assets (Vosk wake model, ONNX voice model) — regenerate/add these locally.
> See `README.md` for setup.
