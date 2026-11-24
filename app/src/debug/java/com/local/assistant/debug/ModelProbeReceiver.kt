package com.local.assistant.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.genai.prompt.Content
import com.google.mlkit.genai.prompt.Feature
import com.local.assistant.AssistantApplication
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.llm.AiCoreBackend
import com.local.assistant.llm.ChatSpec
import com.local.assistant.llm.GenEvent
import com.local.assistant.llm.LlmBackend
import com.local.assistant.llm.PromptAttachment
import com.local.assistant.llm.Sampling
import com.local.assistant.llm.ToolResult
import com.local.assistant.memory.prompt.Instructions
import com.local.assistant.memory.tools.ToolCatalog
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Debug builds only. Runs the same cases through Gemma 4 (LiteRT-LM) and Gemini Nano (AICore),
 * with the app's own instructions and tools, and writes what each did to files/model-probe.md.
 *
 *   adb shell am broadcast -a com.local.assistant.debug.MODEL_PROBE -p com.local.assistant.dev [--es only nano|gemma] [--es nano preview|stable|preview_fast]
 *
 * AICore runs only for an app in the foreground, so open the app first.
 */
class ModelProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as AssistantApplication).container
        val only = intent.getStringExtra("only")
        val nanoChoice = intent.getStringExtra("nano")
        val temperature = intent.getStringExtra("temp")?.toDoubleOrNull()
        val cases = intent.getStringExtra("cases")?.split(',')?.map { it.trim() }?.toSet()
        container.appScope.launch {
            val out = StringBuilder()
            fun log(line: String) {
                Log.i(TAG, line)
                out.append(line).append('\n')
            }
            try {
                ModelProbe(context, container.llmBackend, container.settings.systemPrompt, ::log, cases, temperature).run(only, nanoChoice)
            } catch (e: Throwable) {
                log("PROBE FAILED: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "Probe failed", e)
            } finally {
                File(context.filesDir, "model-probe.md").writeText(out.toString())
                Log.i(TAG, "PROBE DONE")
            }
        }
    }

    companion object {
        const val TAG = "ModelProbe"
    }
}

private class ModelProbe(
    private val context: Context,
    private val gemma: LlmBackend,
    private val persona: String,
    private val log: (String) -> Unit,
    /** Only these chat cases, and no one-shot ones; all when null. */
    private val only: Set<String>? = null,
    /** In place of chat's usual temperature, when given. */
    private val temperature: Double? = null,
) {
    data class Turn(val text: String, val image: Boolean = false)
    data class Case(val id: String, val expect: String, val turns: List<Turn>)

    private val now = "[Now: Tuesday 24 November 2026, 10:15]"
    private val system = Instructions.render(persona, withTools = true)
    private val tools = ToolCatalog.declarations(web = false)

    private val chatCases = listOf(
        Case("reminder", "add_task, tomorrow 18:00", listOf(Turn("Remind me to call mom tomorrow at 6pm"))),
        Case("alarm_ml", "set_alarm 07:00 (Malayalam)", listOf(Turn("നാളെ രാവിലെ 7 മണിക്ക് എന്നെ ഉണർത്തണം"))),
        Case("timer", "set_timer 10 min", listOf(Turn("start a 10 minute timer for the pasta"))),
        Case("fact", "save_fact allergy, then uses it", listOf(Turn("Please remember that I'm allergic to peanuts."), Turn("Is a Snickers bar safe for me?"))),
        Case("no_tool", "plain answer, no tool", listOf(Turn("In two sentences, why is the sky blue?"))),
        Case("arith", "17:25", listOf(Turn("My train leaves at 14:35 and the trip takes 2 hours 50 minutes. When do I arrive?"))),
        Case("writing", "short, polite message", listOf(Turn("Write a short WhatsApp message to my landlord saying the rent will be 3 days late this month."))),
        Case("image", "reads: dentist Fri 28 Nov 4:30 PM, Dr. Rao", listOf(Turn("What does this note say, and when is it?", image = true))),
    )

    suspend fun run(only: String?, nanoChoice: String?) {
        log("# Model probe, ${java.util.Date()}")
        log("")
        temperature?.let { log("Chat temperature: $it") }
        log("System prompt: ${system.length} chars; ${tools.size} tools, ${tools.sumOf { it.length }} chars of declarations")
        log("")

        // What AICore has, for each choice of model.
        log("## AICore")
        val choices = listOf("preview" to (true to false), "preview_fast" to (true to true), "stable" to (false to false))
        val found = choices.map { (name, choice) ->
            val backend = AiCoreBackend(preview = { choice.first }, fast = { choice.second })
            val report = runCatching { backend.inspect() }.getOrElse { log("- $name: inspect failed: ${it.javaClass.simpleName}: ${it.message}"); null }
            if (report != null) log("- $name: $report")
            Triple(name, backend, report)
        }
        val chosen = found.firstOrNull { it.first == nanoChoice && it.third?.available == true }
            ?: found.firstOrNull { it.third?.available == true }
        log("")

        if (only != "gemma") {
            if (chosen == null) {
                log("No Nano is available on this phone; nothing to compare.")
            } else {
                val (name, nano, report) = chosen
                log("## Gemini Nano ($name: ${report?.baseModel})")
                tokenCount(nano)
                runBackend("nano", nano)
            }
        }
        if (only != "nano") {
            log("## Gemma 4 E4B (LiteRT-LM)")
            val loading = SystemClock.elapsedRealtime()
            val ready = gemma.ensureReady()
            log("Engine ready=$ready in ${SystemClock.elapsedRealtime() - loading} ms; ${gemma.capabilities}")
            if (ready) runBackend("gemma", gemma)
        }
    }

    /** Whether the app's instructions and tools fit Nano's window at all. */
    private suspend fun tokenCount(nano: AiCoreBackend) {
        val report = nano.inspect()
        val tools = if (report.has(Feature.FUNCTION_CALLING)) tools.mapNotNull(AiCoreBackend::declaration) else emptyList()
        val request = nano.request(
            system, report.has(Feature.SYSTEM_PROMPT),
            listOf(Content.Builder().role(Content.Role.USER).text("$now\nhi").build()),
            tools, 256, Sampling.CHAT,
        )
        val counted = runCatching { nano.client().countTokens(request).totalTokens }
        log("Prompt with instructions + tools + 'hi': ${counted.getOrElse { "count failed: ${it.message}" }} tokens of ${report.tokenLimit}")
        val bare = runCatching {
            nano.client().countTokens(nano.request("", true, listOf(Content.Builder().role(Content.Role.USER).text("hi").build()), emptyList(), 256, Sampling.CHAT)).totalTokens
        }
        log("Bare 'hi': ${bare.getOrNull()} tokens")
        log("")
    }

    private suspend fun runBackend(label: String, backend: LlmBackend) {
        // A first, small request: what a cold start costs.
        val cold = SystemClock.elapsedRealtime()
        val warm = runCatching { withTimeout(120_000) { backend.complete("Answer in one word.", "Say hi.", null, 16, Sampling.BACKGROUND) } }
        log("Cold start: ${SystemClock.elapsedRealtime() - cold} ms -> ${warm.getOrElse { "ERROR ${it.javaClass.simpleName}: ${it.message}" }.take(80)}")
        log("")

        for (case in chatCases.filter { only == null || it.id in only }) {
            log("### [$label] ${case.id} (expect: ${case.expect})")
            runCatching { withTimeout(180_000) { chat(backend, case) } }
                .onFailure { log("ERROR ${it.javaClass.simpleName}: ${it.message}") }
            log("")
        }

        if (only != null) return
        log("### [$label] summary (complete)")
        timed {
            backend.complete(
                "Summarize the conversation in 3 short bullet points.",
                CONVERSATION, null, 300, Sampling.BACKGROUND,
            )
        }
        log("")
        log("### [$label] extraction (complete, JSON)")
        timed {
            backend.complete(
                "Extract facts about the user worth remembering.",
                "My wife Priya's birthday is March 3rd, and I work as a nurse at Aster Medcity in Kochi. Oh and I hate coriander.",
                EXTRACT_SCHEMA, 300, Sampling.BACKGROUND,
            )
        }
        log("")
    }

    private suspend fun timed(block: suspend () -> String) {
        val start = SystemClock.elapsedRealtime()
        val result = runCatching { withTimeout(120_000) { block() } }
        val ms = SystemClock.elapsedRealtime() - start
        result.onSuccess { log("${ms} ms, ${it.length} chars:\n```\n${it.trim()}\n```") }
            .onFailure { log("ERROR after $ms ms: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private suspend fun chat(backend: LlmBackend, case: Case) {
        val open = SystemClock.elapsedRealtime()
        val session = backend.openChat(
            ChatSpec(
                systemPrefix = system,
                history = emptyList(),
                toolDeclarations = if (backend.capabilities?.tools == true) tools else emptyList(),
                sampling = temperature?.let { Sampling.CHAT.copy(temperature = it) } ?: Sampling.CHAT,
                maxOutputTokens = 512,
                prefillOnOpen = true,
            ),
        )
        log("opened in ${SystemClock.elapsedRealtime() - open} ms")
        try {
            for (turn in case.turns) {
                val start = SystemClock.elapsedRealtime()
                var first = 0L
                val reply = StringBuilder()
                val calls = mutableListOf<String>()
                var events = if (turn.image) {
                    session.send("$now\n${turn.text}", PromptAttachment(noteImage().path, AttachmentKind.IMAGE))
                } else {
                    session.send("$now\n${turn.text}")
                }
                var rounds = 0
                while (true) {
                    var asked: List<com.local.assistant.llm.ToolCall> = emptyList()
                    events.collect { event ->
                        when (event) {
                            is GenEvent.TextDelta -> {
                                if (first == 0L) first = SystemClock.elapsedRealtime()
                                reply.append(event.text)
                            }
                            is GenEvent.ToolCalls -> asked = event.calls
                            is GenEvent.Error -> reply.append(" <ERROR ${event.cause.javaClass.simpleName}: ${event.cause.message}>")
                            is GenEvent.Done -> event.stats?.let { s -> if (s.tokensPerSecond > 0) calls += "~${"%.1f".format(s.tokensPerSecond)} tok/s" }
                        }
                    }
                    if (asked.isEmpty() || rounds++ >= 3) break
                    asked.forEach { calls += "${it.name}${it.arguments}" }
                    events = session.sendToolResults(asked.map { ToolResult(it.name, fakeResult(it.name)) })
                }
                val total = SystemClock.elapsedRealtime() - start
                log("> ${turn.text}${if (turn.image) " [image]" else ""}")
                log("first text ${if (first > 0) first - start else -1} ms, total $total ms; ${calls.joinToString("; ").ifEmpty { "no tool calls" }}")
                log("reply: ${reply.toString().trim().replace("\n", "\n       ")}")
            }
        } finally {
            session.close()
        }
    }

    /** What a tool would say if it had run: enough for the model to finish its reply. */
    private fun fakeResult(name: String): String = when (name) {
        ToolCatalog.ADD_TASK -> """{"ok":true,"id":41,"title":"Call mom","due":"2026-11-25 18:00"}"""
        ToolCatalog.SET_ALARM -> """{"ok":true,"time":"07:00","day":"tomorrow"}"""
        ToolCatalog.SET_TIMER -> """{"ok":true,"minutes":10,"label":"pasta"}"""
        ToolCatalog.SAVE_FACT -> """{"ok":true,"id":7}"""
        ToolCatalog.CALCULATE -> """{"ok":true,"result":"17:25"}"""
        ToolCatalog.SEARCH_MEMORY -> """{"ok":true,"results":["The user is allergic to peanuts."]}"""
        ToolCatalog.GET_UPCOMING -> """{"ok":true,"items":[]}"""
        else -> """{"ok":true}"""
    }

    /** A handwritten-looking note, drawn here so no real photo is needed. */
    private fun noteImage(): File {
        val file = File(context.cacheDir, "probe-note.png")
        if (file.isFile) return file
        val bitmap = Bitmap.createBitmap(768, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(255, 248, 200))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 30, 90); textSize = 46f }
        listOf("Dentist appointment", "Fri 28 Nov, 4:30 PM", "Dr. Rao, Smile Clinic", "bring old X-rays").forEachIndexed { i, line ->
            canvas.drawText(line, 50f, 120f + i * 90f, paint)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private companion object {
        val CONVERSATION = """
            User: I'm planning a trip to Munnar next weekend with my two kids, they're 6 and 9.
            Assistant: That sounds lovely! Munnar is great in November. Are you driving from Kochi?
            User: Yes, from Kochi. We'd leave Saturday morning and come back Sunday evening. The kids get carsick on winding roads though.
            Assistant: The Kochi–Munnar road has many hairpin bends. Leave early, stop at Cheeyappara waterfalls and Valara for breaks, and keep ginger candy or travel-sickness bands handy.
            User: Good idea. Where should we stay? Budget is around 6000 rupees a night.
            Assistant: In that range, look at homestays near Chithirapuram or Pallivasal; they often have family rooms and garden space for kids.
            User: And what can the kids do there?
            Assistant: Eravikulam National Park to see Nilgiri tahr, the Tea Museum, boating at Mattupetty Dam, and the Kundala lake. Eravikulam needs online tickets in advance.
            User: OK, I'll book Eravikulam tickets tonight. Also remind me my daughter needs her inhaler packed.
        """.trimIndent()

        val EXTRACT_SCHEMA = """
            {"type":"object","properties":{"facts":{"type":"array","items":{"type":"object","properties":{"subject":{"type":"string"},"fact":{"type":"string"}},"required":["subject","fact"]}}},"required":["facts"]}
        """.trimIndent()
    }
}
