# Cloud mode: hybrid inference with Firebase AI Logic

Local Assistant runs entirely on the phone (Gemini Nano through Android's AICore). Cloud mode adds Gemini
through Firebase AI Logic, **per chat and only when the user switches it on**. The rule it keeps:

> The cloud model is only ever shown what was already said to it.

- **Cloud chats** see only messages sent in Cloud mode, plus anything learned only from them:
  facts, reminders, events, notes, saved images and archived exchanges.
- **Local chats** see everything, Cloud data included.
- **Auto chats** (the default once agreed to) run on the phone and send Gemini only questions
  the phone's model writes for it, with only what the answer needs ([Auto mode](#auto-mode-luna-decides)).
- **The switch (Local · Auto · Cloud) can be used at any time.** Each message keeps the mode it
  was sent in, so switching a chat to Cloud sends none of what came before.

## Auto mode: Luna decides

> The cloud knows the world. Luna knows you, and decides what the cloud needs to hear.

In an Auto chat the phone's model (Gemini Nano) answers, with all of the user's own
things: photos, messages, files, health, memory. Gemini is one of its tools, `ask_gemini`, for
what a phone model does poorly: long writing, code, translation, planning, comparing, detailed
explanations, live facts.

1. **The router** (`AutoRouter`, rules only, no model call) picks a lane for each message:
   - **On the phone only:** locked, offline, Cloud not set up, the day's allowance
     (`auto_daily_asks`) used, or an ID, card or account number in the message.
   - **Phone first, may ask** (the default): anything personal, a message naming someone Luna
     knows, a command, small talk, or anything unclear. The phone's model answers, or calls
     `ask_gemini` with a question it writes to stand on its own. **It decides what Gemini is
     told:** what the answer needs from the chat and from what it knows of the user
     (`about_user`: "vegetarian, budget about 1500 rupees"), a name only when the answer uses it,
     and never phone numbers, addresses or ID numbers. Gemini answers from exactly that.
   - **Ask first:** self-contained requests worth the cloud (long writing, code, translation,
     planning, live facts, a follow-up to the last answer from Gemini; explanations too when the
     phone is hot or the battery low), naming no one Luna knows. The app asks Gemini itself with
     the user's words, with no model hop on the phone.
2. **ID and card numbers never go.** Whoever wrote the question, `Veil.withhold` replaces them
   with `[withheld]` before it is sent. (A first version also swapped names and places for
   placeholders like `[Person 1]`; Gemini copied the pattern into a made-up story, so the phone's
   model now chooses what to say instead.)
3. **The paste guard:** a question that copies 12 or more words out of a message, file or health
   reading the turn read waits for the user: "Send / Keep on phone", showing exactly what would go.
   Health readings are never sent at all.
4. **Firebase AI Logic hybrid inference:** a plain question goes `PREFER_IN_CLOUD`, so if the
   cloud can't be reached as it's sent, Gemini Nano answers on the phone instead. One that needs
   the web goes `ONLY_IN_CLOUD` with Grounding with Google Search.

Each reply says where it ran and why ("On this phone · used your messages", "Asked Gemini ·
planning"). Tapping it opens **What Gemini saw**: the question exactly as it went, and what Luna
told Gemini about the user. Every question is also in the ledger, with a "Gemini today: N of M"
meter.

**Auto's guarantee:** Gemini sees only questions Luna writes for it, with only what Luna decides
the answer needs. ID and card numbers are never sent. Messages, photos, files and health records
are never sent as they are. Every question shows under its reply and in "What the cloud has seen".

Code: `hybrid/AutoRouter.kt`, `Veil.kt`, `VeilSource.kt`, `CloudAsk.kt`, `AskTools.kt`,
`AskConfirm.kt`, `HybridSignals.kt`; the sheet is `ui/chat/GeminiSawSheet.kt`. Tests:
`app/src/test/.../hybrid/AutoRouterTest.kt` and `VeilTest.kt`.

## How the guarantee is enforced

This is not a filter applied to the prompt at the end. A Cloud chat is built from its own
sources, and each one can only read Cloud data:

| Piece | Local chat | Cloud chat |
|---|---|---|
| History | every message in the chat | `messages.cloud = 1` only, no rolling or session summaries |
| Memory (facts, agenda) | `MemoryRepository` | `MemoryRepository(cloudOnly = true)`: every query has `cloud = 1` |
| Recall (archive) | `ArchiveRepository` | `CloudArchive`: exchanges whose message was sent in Cloud mode |
| Notes, saved images | `UserNotes`, `SavedImages` | the same classes with `cloudOnly = true` |
| Tools | all | `CloudScope.TOOLS`. `ToolExecutor` refuses anything else, whatever the model asks. No calls, messages, photos, files, health, inbox or screenshots. |
| Writes | stored as local | stored with `cloud = 1`. Editing the words of a cloud item in a Local chat makes it local again. |

Every request is written to `files/cloud-ledger.jsonl` as it is sent. The **What the cloud has
seen** screen shows it word for word, with message counts and a "Keep on phone" action for
anything a Cloud chat can recall.

Code: `app/src/main/java/com/local/assistant/hybrid/` (`CloudScope`, `FirebaseBackend`,
`HybridSetup`, `CloudLedger`, `GeminiSearch`). The UI is in `ui/chat/CloudMode.kt` and
`ui/privacy/CloudLedgerScreen.kt`.

## Where each answer comes from

Every reply shows a badge saying where it came from.

| Situation | Model | Firebase AI Logic mode |
|---|---|---|
| Local chat | Gemini Nano on the phone (AICore, ML Kit GenAI Prompt API) | none: never touches Firebase |
| Cloud chat | Gemini (`cloud_model`, default `gemini-3.5-flash-lite`) | `ONLY_IN_CLOUD`, with function calling over the app's tools |
| Cloud chat, offline or quota spent | Gemini Nano on the phone (AICore) | `ONLY_ON_DEVICE`, with the instructions and recent turns in one prompt and no tools |
| Document attached to a Cloud message | Nano first if short enough, otherwise Gemini | `PREFER_ON_DEVICE` / `ONLY_IN_CLOUD` |
| `web_lookup` in a Cloud chat | Gemini with Grounding with Google Search, or the user's Tavily key if set | `ONLY_IN_CLOUD` |
| Auto chat | Gemini Nano on the phone | none, unless it asks Gemini |
| Auto chat's question to Gemini (`ask_gemini`) | Gemini (`cloud_model`), with what the phone's model chose to send | `PREFER_IN_CLOUD`: Nano on the phone if the cloud can't be reached |
| Auto chat's question needing the web | Gemini with Grounding with Google Search | `ONLY_IN_CLOUD` |

## Setup

1. Create a Firebase project and enable **Firebase AI Logic** with the Gemini Developer API.
2. Register three Android apps:
   - `com.local.assistant` (release)
   - `com.local.assistant.dev` (debug)
   - `com.local.assistant.demo` (`-Pdemo`)
3. Download `google-services.json` into `app/`. Without it, the app builds and runs as before,
   and the switch says Cloud isn't set up.
4. Set up App Check, and enforce it for Firebase AI Logic:
   - **Play builds:** the Play Integrity provider.
   - **Debug and demo builds:** the debug provider. Put a UUID in `local.properties` as
     `firebase.appCheckDebugToken=…` and register it in the console under App Check → Manage
     debug tokens. The demo APK carries that token, so anyone who installs it can reach the
     model. Set per-user quotas in Firebase AI Logic, and revoke the token when judging is over.
5. Optionally, add these Remote Config parameters (the defaults are built in):
   - `cloud_enabled` (bool): turns Cloud mode off everywhere.
   - `cloud_model`, default `gemini-3.5-flash-lite` (was `gemini-3.8-flash`; lite for now, to spend fewer tokens)
   - `lite_model`, default `gemini-3.5-flash-lite`
   - `auto_daily_asks`, default 40: how many questions Auto chats may send Gemini a day.
   - `nano_model_option`: `stable`, `preview` (Nano 4, needs the AICore Developer Preview) or
     `preview_fast`.
6. Keep `app/google-services.json`, `local.properties` and `keystore.properties` out of git.

## Data model

Database v10 (`MIGRATION_9_10`) adds:

- `chats.cloud`: where the chat's next message goes.
- `messages.cloud` and `messages.servedBy`.
- `cloud` on `facts`, `tasks`, `events`, `notes` and `user_notes`.

Archived chunks inherit the flag by joining their message, and note chunks by joining their note.

Database v11 (`MIGRATION_10_11`) adds `chats.auto` (an Auto chat) and `messages.route` (why an
Auto reply came from where it did). An Auto question as it went, what it told Gemini about the
user, and Gemini's answer are kept in the chat's tool row, on the phone only.

## What was built during the contest (draft for the submission form)

Local Assistant already existed as a fully on-device Android assistant: Gemma 4, memory, tools,
photo and file search. During #BuildHybridAI I added Cloud mode with Firebase AI Logic:

- A per-chat Local/Cloud switch. Each message keeps the mode it was sent in.
- Cloud chats on Gemini with the app's own tool calling. App Check (Play Integrity, or the debug
  provider for the demo) and Remote Config for the model choice.
- A hybrid fallback to Gemini Nano on the phone when the cloud can't be reached, and
  `PREFER_ON_DEVICE` reading for documents.
- A privacy model enforced in the data layer: cloud-scoped repositories, SQL-level `cloud = 1`
  filters, and a tool allowlist.
- An in-app ledger of every word sent to the cloud.
- Auto mode: the phone's model decides per message and asks Gemini only questions it writes,
  with only what the phone's model decides the answer needs (IDs never sent), a paste guard,
  a "What Gemini saw" view, and `PREFER_IN_CLOUD` hybrid inference falling back to Nano.
- The phone's own model moved from Gemma 4 E4B (a 3.66 GB download, ~10 s to load) to Gemini
  Nano, which Android keeps: chats, the assistant overlay (on the lock screen too), photo and
  document reading, summaries and the "While you were away" brief. AICore answers only an app
  in front, so memory upkeep that needs the model runs when Luna is opened, not overnight.
