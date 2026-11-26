# Gemma 4 E2B on the GPU and the NPU, against E4B (24 Nov 2026)

Measured on the OnePlus 15 (CPH2745, Snapdragon 8 Elite Gen 5 / SM8850, 16 GB, Android 16) with
LiteRT-LM 0.19.1, the runtime the app ships, through the debug build's probes
(`EngineBench` for speed, `ToolBench` for tool calls). Nothing here changes the release app: the
one change to main code, `LlmService.bench`, is null outside the debug probes.

## Speed

Each message opens a new conversation, the way a new chat does, with Luna's real instructions and
17 tool declarations: 2,489 tokens the model has to read before the user's words. Context window
4,096 tokens, chat sampling (temperature 1.0, top-K 64). Two rounds of three messages per setup.
Every run below started on a cooled phone (thermal status 0, CPU under 50 °C) with Luna in front.
Both GPU files run their text decoder in fp32, as Luna patches them to (`ModelPrecision`: exact
digits past 2,048 tokens, at the cost of slower reading).

| | E4B, GPU + MTP (Luna today) | E2B, GPU + MTP | E2B, NPU |
|---|---|---|---|
| Model file | 3.66 GB | 2.59 GB | 2.61 GB (uncompiled) |
| Engine load | 7.6 s | 5.5 s (11.8 s the first load after the fp32 patch, which rebuilds its GPU programs) | **159–194 s** (compiled on the phone at every load) |
| Reading instructions + tools (2,489 tokens) | 4.3–4.8 s (520–590 tok/s) | 1.5 s (1,640–1,670 tok/s) | **0.58 s** (4,350–4,590 tok/s; 1.35 s the first time) |
| First word after the user's message | 0.37–0.50 s | 0.15–0.18 s | **0.06–0.07 s** |
| Writing speed | 20–24 tok/s | 34–51 tok/s | 46–49 tok/s |
| "Remind me to call mom tomorrow at 6pm", sent to call made | 0.9–1.7 s | 0.5–1.3 s | 0.56 s |
| 250-word answer, sent to last word | 11.4–13.5 s | 7.6–7.8 s | **6.9 s** |

In a new chat the user waits for the reading and the first word together: about 5 s on E4B today,
1.7 s on E2B's GPU, 0.65 s on the NPU. (Luna can read the instructions before the user sends,
when it opens a chat early; then only the first word is left.)

Multi-token prediction (MTP: the file's small drafter guesses ahead) mattered more for E2B than
for E4B: E2B wrote long answers at 43–51 tok/s with it and 34–35 without; E4B moved within the
noise. Without the fp32 patch E2B read the instructions in 1.3 s (1,830–1,950 tok/s). The NPU file
has no drafter and no precision setting to patch.

## Tool calls

Luna's 32-case tool benchmark (the same one Nano was tuned on), 21 tools, chat sampling. Each case
is a whole turn: the call, its (canned) result, and the reply.

| | Right calls | Clean replies | Time a case | Failed |
|---|---|---|---|---|
| E4B, GPU + MTP | 29/32 | 30/32 | 12.6 s | doc_number, msg_name_only, remind_arrive |
| E2B, GPU + MTP | 25/32 | 29/32 | 6.2 s | photos_with_friend, doc_number, msg_voice_misheard, msg_name_only, remind_arrive, file, fact |
| E2B, NPU (4,096-token window) | **7/32** | 14/32 | 1.5 s once loaded | 25 of 32 |
| Nano 4 fast (AICore, tuned prompt; earlier today, without code_long) | 27/31 | | 1.9 s | doc_number, msg_name_only, remind_arrive, fact |

E2B's misses are of a different kind from E4B's. E4B's three are the cases every model here
fails. E2B also:
- read the inbox when asked to send a message ("Sun high to Uma on WhatsApp", the misheard voice case),
- searched the inbox and gave an empty reply for "message Umma",
- opened the file manager instead of reading the résumé,
- folded the friend's name into the photo search's free text instead of the people field,
- twice wrote a tool call the runtime couldn't parse (`Failed to parse tool calls`; once in the
  reply to the inbox case, once as the whole save_fact turn).

On the NPU, E2B's tool calls fall apart, though its prose in the speed runs read fine. Most calls
were malformed enough that the runtime couldn't parse them (`call:find_photos{person:<|"|>me,when:last month}`),
and the ones that parsed often had invented tool names: `find_find_photos`, `get_get_upcoming`,
`phone_tool_call`, `call_set_alarm`, `in_box`, `add_note`. "Tell me a short joke" became
`add_task{title=tell me a short joke}`. The first case also took the 3-minute compile and then timed
out. The NPU file is quantized differently from the GPU one, which is the likely cause; at the
app's own 8K window the NPU build loaded and then gave no answer in 4 minutes.

E2B on the GPU was also weaker in its replies where it had to work something out: "6,240 steps …
your goal of 8,000 … 441 more steps to go" (it's 1,760), and a timer reply that claimed a task had
been made too. All of this was measured with Luna's prompt as it is, tuned for E4B and Nano, not
for E2B.

## Getting the NPU to run

LiteRT-LM's Android library already has `Backend.NPU(nativeLibraryDir)`; what the app lacked was
the vendor libraries. The debug build made with `-Pnpu` adds them (see `app/build.gradle.kts`):

- `com.google.ai.edge.litert:litert-npu-runtime-qualcomm:2.4.0` (Google Maven): LiteRT's Qualcomm
  dispatch library and on-device compiler plugin, 1.4 MB.
- `com.qualcomm.qti:qnn-runtime:2.52.0` (Maven Central, Qualcomm's own): the QNN HTP runtime with
  the stub/skel pairs for Hexagon V68–V85, about 80 MB. 2.52 is the QAIRT version LiteRT 2.4.0
  is built against.
- Native libraries extracted at install (`useLegacyPackaging`): the NPU loads its half from a
  real file.

What went wrong on the way:

- **2.3.2 of the Qualcomm runtime doesn't fit LiteRT-LM 0.19.1.** Its libraries export the old
  plugin entry point (`LiteRtCreateCompilerPlugin`); 0.19.1 looks for the versioned one
  (`LiteRtCompilerPluginQueryInterface`) that 2.4.0 has. With 2.3.2 the model "loaded" in 7 s
  without the NPU.
- **There is no SM8850 build of the model.** litert-community publishes E2B compiled for Tensor
  G5/G6, QCS8275 and Intel, plus `_NPU_Uncompiled`. The uncompiled file compiles on the phone, at
  every load: 159 s cool, 194 s warm. No compiled copy is cached (the cache folder only ever held
  the GPU's). A real app would compile it ahead of time for each chip (LiteRT's AOT tooling, Linux)
  or ship through Play for On-device AI, which picks the file per chip.
- **Parts of the graph stay on the CPU.** QNN rejected 28 ops while compiling: 26
  `ElementWiseSelect` (an attention mask built for the 4,096-token window, which can't broadcast
  against the 128-token prefill chunks) and 2 `Gather`; the embedding lookup isn't supported by
  the Qualcomm compiler at all. It still ran at the speeds above.
- **The NPU build fills in tool arguments differently.** In every speed run its reminder call was
  `add_task{label=…}` where the tool's field is `title`; the GPU builds of the same model said
  `title`. It was the first sign of what the tool benchmark then showed (7/32).
- Android logged SELinux denials for `/dev/fastrpc-cdsp` from the app; the NPU session opened
  anyway (`libQnnHtpV81Skel.so … qnn_2_52_0`).

## Things that skew numbers on this phone

- **Heat.** After 15 minutes of back-to-back runs the phone sat at thermal status 2 and E4B's
  writing speed dropped from ~20 to ~15 tok/s. The final runs waited for the phone to cool first.
- **Luna in the background.** A run with the launcher in front stalled for 25 minutes mid-reply
  and never finished, though Luna's process was alive, in its foreground service and not frozen.
  Why isn't established; the later runs brought Luna to the front first and none stalled. Since
  Local chats fall back to Gemma when Luna isn't in front, this is worth checking on its own.
- **E2B on the GPU once wrote gibberish.** One E2B GPU + MTP run (20:50) wrote random
  multilingual tokens for every prompt and took 12 s to load instead of 5.5 s. It ran during a phone
  call (the call screen in front, the display off). The same file and setup gave clean answers at
  20:07 before it, and in the tool benchmark (21:26) and the rerun (22:04) after it. The cause
  isn't known; it didn't come back.
- **Luna patches whatever file it loads.** `LlmService` switches a file's text decoder to fp32 on
  load, in place. The tool benchmark loaded the E2B GPU file through it, so that copy on the phone is
  now patched (the NPU file has no such setting). The speed numbers above were taken after that.

## What it means for Luna

- **E2B on the GPU is the step that's ready.** In a new chat the wait before the first word drops
  from about 5 s to about 1.7 s, and replies are written about twice as fast, from a file a gigabyte
  smaller, with nothing new in the app but the file. What it costs is tool-call accuracy with
  today's prompt: 25/32 against E4B's 29/32, with misses of a worse kind (wrong tool, unparseable
  call). Nano is built on E2B too and reached 27/31 once its prompt was tuned (`NanoPrompt`: tool
  docs, examples, focus, temperature 0.1); the same tuning on E2B is the obvious next measurement.
- **The NPU isn't usable yet, for two reasons.** It is the fastest by far: it reads Luna's
  instructions 2.7× faster than E2B on the GPU and 8× faster than E4B, and writes at 46–49 tok/s.
  But the only file that runs on this chip compiles for 2.5–3 minutes at every load, and its tool
  calls are mostly broken (7/32). It becomes worth another look with a file compiled ahead of time
  for SM8850, and that file's tool calls would need measuring again before anything relied on it.
  The 80 MB QNN runtime stays out of normal builds until then.
- **No other runtime is needed for either.** The NPU path is LiteRT-LM's own `Backend.NPU`, with
  Google's and Qualcomm's published libraries.

## Running it again

Build the NPU variant of the debug app and install it:

```bash
./gradlew :app:assembleDebug -Pnpu
```

Fetch a model file on the phone itself (adb pushes about 3 MB/s over wireless debugging; the phone
fetched at 9–10 MB/s):

```bash
adb shell "am broadcast -a com.local.assistant.debug.MODEL_PROBE -p com.local.assistant.dev --es mode fetch --es url https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/2e12bfd869b2962984f97a1f2a26807316d534cc/gemma-4-E2B-it_NPU_Uncompiled.litertlm --es name gemma-4-E2B-it_NPU_Uncompiled.litertlm --es sha256 b4f86de05f54bda24e8633ef20375b81b3fcc5dace7c6ac9f4269644267ca4aa"
```

Speed, with Luna open on the phone:

```bash
adb shell "am broadcast -a com.local.assistant.debug.MODEL_PROBE -p com.local.assistant.dev --es mode engine --es model /data/user/0/com.local.assistant.dev/files/bench/gemma-4-E2B-it_NPU_Uncompiled.litertlm --es backend npu --ei rounds 2"
```

Tool calls on a chosen file (held in memory only, so the app's own model and settings aren't
touched; add `--es backend npu` for the NPU, which runs at 4,096 tokens):

```bash
adb shell "am broadcast -a com.local.assistant.debug.MODEL_PROBE -p com.local.assistant.dev --es mode tools --es variants none --ez gemma true --es model /data/user/0/com.local.assistant.dev/files/bench/gemma-4-E2B-it.litertlm"
```

Reports land in the app's `files/model-probe.md`:

```bash
adb shell run-as com.local.assistant.dev cat files/model-probe.md
```

Files used (litert-community/gemma-4-E2B-it-litert-lm at revision 2e12bfd8):

| File | Bytes | SHA-256 |
|---|---|---|
| gemma-4-E2B-it.litertlm | 2,588,147,712 | 181938105e0eefd1…39a63c |
| gemma-4-E2B-it_NPU_Uncompiled.litertlm | 2,614,578,293 | b4f86de05f54bda2…67ca4aa |
| gemma-4-E4B-it.litertlm (the app's, revision 71064b01) | 3,659,530,240 | 0b2a8980…52e0 |

Both E2B files are on the phone in the debug app's `files/bench/` (5.2 GB). The GPU one there no
longer matches its checksum: Luna's fp32 patch changed two bytes of its header.
