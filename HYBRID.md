# Cloud mode: hybrid inference with Firebase AI Logic

Local Assistant runs entirely on the phone (Gemma 4 through LiteRT-LM). Cloud mode adds Gemini
through Firebase AI Logic, **per chat and only when the user switches it on**. The rule it keeps:

> The cloud model is only ever shown what was already said to it.

- **Cloud chats** see only messages sent in Cloud mode, plus anything learned only from them:
  facts, reminders, events, notes, saved images and archived exchanges.
- **Local chats** see everything, Cloud data included.
- **The switch can be used at any time.** Each message keeps the mode it was sent in, so
  switching a chat to Cloud sends none of what came before.

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
| Local chat | Gemma 4 on the phone (LiteRT-LM) | none: never touches Firebase |
| Cloud chat | Gemini (`cloud_model`, default `gemini-3.8-flash`) | `ONLY_IN_CLOUD`, with function calling over the app's tools |
| Cloud chat, offline or quota spent | Gemini Nano on the phone (AICore) | `ONLY_ON_DEVICE`, with the instructions and recent turns in one prompt and no tools |
| Document attached to a Cloud message | Nano first if short enough, otherwise Gemini | `PREFER_ON_DEVICE` / `ONLY_IN_CLOUD` |
| `web_lookup` in a Cloud chat | Gemini with Grounding with Google Search, or the user's Tavily key if set | `ONLY_IN_CLOUD` |

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
   - `cloud_model`, default `gemini-3.8-flash`
   - `lite_model`, default `gemini-3.5-flash-lite`
   - `nano_model_option`: `stable`, `preview` (Nano 4, needs the AICore Developer Preview) or
     `preview_fast`.
6. Keep `app/google-services.json`, `local.properties` and `keystore.properties` out of git.

## Data model

Database v10 (`MIGRATION_9_10`) adds:

- `chats.cloud`: where the chat's next message goes.
- `messages.cloud` and `messages.servedBy`.
- `cloud` on `facts`, `tasks`, `events`, `notes` and `user_notes`.

Archived chunks inherit the flag by joining their message, and note chunks by joining their note.

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
