# Luna: a private AI assistant for Android, hybrid by design

Luna is a personal assistant that runs on your phone. **Gemini Nano** answers on the device and calls 22 tools:
memory and notes, reminders by time or place, calendar, alarms and timers, calls and texts, phone settings,
photos, files, messages, Health Connect, web search, and asking Gemini. When a question needs a bigger model,
Luna asks **Gemini through Firebase AI Logic**, with a question that Nano writes. Your messages, photos and ID
numbers stay on the phone, and every word that left it is in a ledger you can read.

Built for **#BuildHybridAI 2026**.

## Try it

1. Download the APK from [Releases](https://github.com/taslimmuhammed/Luna/releases) and install it on an
   **arm64 Android phone** (Android 8 or later). It installs as "Luna" (`com.local.assistant.demo`), next to any
   other build.
2. **Local** and **Auto** modes need **Gemini Nano through AICore**. We tested on a OnePlus 15 with Nano 4. On a
   phone without Nano, Luna says so, and **Cloud** mode still works.
3. No keys are needed. The test build carries an App Check debug token, and Cloud and Auto ask you to sign in
   with your own Google account before anything goes to the cloud.
4. First run downloads EmbeddingGemma 2 (about 370 MB) for photo and memory search. The Kokoro voice (about
   345 MB) is optional, under Settings.

Things to try (the switch above the message box picks **Local**, **Auto** or **Cloud**):

- **Local:** "Turn on the torch and set a 5 minute timer", or "Remind me to pick up the cake when I reach
  [a place near you]".
- **Auto:** "Plan my day in Munnar on Saturday. Keep it relaxed, with good vegetarian food." Then tap the
  reply's label to see **What Gemini saw**.
- **Auto:** "My passport number is K1234567. Remind me to renew it in January." It stays on the phone, and the
  number stays hidden until your fingerprint.
- **Auto, offline:** turn on airplane mode and ask anything. Gemini Nano answers on the phone.
- **Cloud:** "Remind me to book a cab to the airport on Friday at 6 am". Then open the menu, then **What the
  cloud has seen**, to read every request word for word.

## Three modes, chosen per chat

Each message keeps the mode it was sent in, so switching a chat to Cloud sends none of what came before.

| Mode | What runs | What the cloud sees |
|---|---|---|
| **Local** | Gemini Nano on the phone | nothing |
| **Auto** (the default) | Gemini Nano, which calls `ask_gemini` only when a question needs Gemini (long writing, code, planning, live facts) | a question Nano writes, with only what the answer needs (`about_user: "vegetarian; likes relaxed activities"`) |
| **Cloud** | Gemini with Luna's own tools, through function calling | only what was said in Cloud mode |

The privacy rules are enforced in code, not asked of a prompt:

- ID, card and account numbers never leave the phone: `Veil` replaces any that reach a question with
  `[withheld]`.
- A question that copies 12 or more words out of a message, file or health reading waits for **Send / Keep on
  phone**. Health readings are never sent.
- Cloud chats are built from cloud-scoped repositories (`cloud = 1` in every query) and a tool allowlist.
- Every request is written to a ledger, shown in the app as **What the cloud has seen**.
- On the phone, IDs, cards, passwords and document photos stay masked until your fingerprint.

## Firebase AI Logic hybrid inference

| What's running | Inference mode | Code |
|---|---|---|
| Auto's `ask_gemini` question | `PREFER_IN_CLOUD` in `OnDeviceConfig`: if the cloud can't be reached, Gemini Nano answers on the phone | `hybrid/CloudAsk.kt` |
| A question that needs the web | Gemini in the cloud, with Grounding with Google Search | `hybrid/GeminiSearch.kt` |
| A Cloud chat | `ONLY_IN_CLOUD`, with function calling over Luna's tools | `hybrid/FirebaseBackend.kt` |
| A Cloud chat while offline or over quota | `ONLY_ON_DEVICE` | `hybrid/HybridSetup.kt` |
| A document attached to a Cloud message | `PREFER_ON_DEVICE` when short enough, otherwise `ONLY_IN_CLOUD` | `hybrid/FirebaseBackend.kt` |

Also from Firebase: **App Check** (Play Integrity, or the debug provider for the test build), **Remote Config**
(`cloud_model`, `auto_daily_asks`, `nano_model_option`, and `cloud_enabled` as a kill switch) and
**Authentication** (Google sign-in for the cloud modes). The cloud model is `gemini-3.5-flash-lite`. On the phone,
Firebase uses whichever Gemini Nano AICore has: the stable one, or Nano 4 from the AICore preview
(`OnDeviceModelOption.PREVIEW`).

`AutoRouter` (rules only, no model call) picks a lane for every Auto message: phone only (offline, locked, an ID
number in the message, or the day's allowance used), phone first, or ask Gemini first. Local chats run Gemini
Nano directly through the ML Kit GenAI Prompt API (`llm/NanoBackend.kt`, `llm/AiCoreBackend.kt`).

[HYBRID.md](HYBRID.md) has the full design: the routing lanes, how cloud scoping is enforced, the data model and
the Firebase setup.

## On-device models

- **Gemini Nano** through AICore: chat, tool calls, reading photos and documents, summaries.
- **EmbeddingGemma 2** (text and vision, LiteRT-LM): photo and memory search by meaning.
- **Kokoro-82M** through sherpa-onnx: the voice.
- **microWakeWord**: "Hey Luna".
- **YuNet and SFace**: faces in photos, matched to contacts.
- **ML Kit text recognition** and Android's on-device **speech recognition**.

[MODEL-BENCH.md](MODEL-BENCH.md) compares the on-device models tried along the way.

## Build from source

Needs JDK 17 and the Android SDK (compile and target SDK 36). The build downloads the sherpa-onnx library from
its GitHub release.

```bash
./gradlew :app:assembleDebug
```

Cloud and Auto modes need your own Firebase project:

1. Enable **Firebase AI Logic** with the Gemini Developer API, and **Authentication** with the Google provider.
2. Register the Android apps `com.local.assistant`, `com.local.assistant.dev` (debug) and
   `com.local.assistant.demo` (the test build), each with your signing key's SHA-1.
3. Download `google-services.json` into `app/`. Without it the app still builds and runs, and the switch says
   Cloud isn't set up.
4. For debug and test builds, put a UUID in `local.properties` as `firebase.appCheckDebugToken=…` and register it
   under App Check, Manage debug tokens.

The test build (release, arm64 only, signed with the debug key) is made with:

```bash
./gradlew :app:demoApk -Pdemo
```

It's copied to `apk/LocalAssistant.apk`. `google-services.json`, `local.properties` and keystores stay out of
git.

## Code layout

`app/src/main/java/com/local/assistant/`:

| Package | What's in it |
|---|---|
| `hybrid/` | Firebase AI Logic, Auto routing, `Veil`, the cloud ledger |
| `llm/` | Gemini Nano through AICore, prompts, tool-call repair |
| `memory/` | facts, notes, archive and the 22 tools |
| `gallery/` | photo search, faces and places |
| `assist/` | the overlay you open by holding the power button |
| `wake/`, `voice/` | the wake word, speech in and out |
| `ui/` | Jetpack Compose screens |

## License

[Apache License 2.0](LICENSE). The models and libraries Luna uses keep their own licenses, listed in the app
under Settings, About. The Kokoro voice's pronunciation comes from eSpeak NG (GPL-3.0), through sherpa-onnx.
