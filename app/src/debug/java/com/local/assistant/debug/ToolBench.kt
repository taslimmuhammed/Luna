package com.local.assistant.debug

import android.os.SystemClock
import com.local.assistant.llm.AiCoreBackend
import com.local.assistant.llm.ChatSpec
import com.local.assistant.llm.GenEvent
import com.local.assistant.llm.LlmBackend
import com.local.assistant.llm.NanoPrompt
import com.local.assistant.llm.Sampling
import com.local.assistant.llm.ToolCall
import com.local.assistant.llm.ToolResult
import com.local.assistant.memory.prompt.Instructions
import com.local.assistant.memory.prompt.PromptAssembler
import com.local.assistant.memory.tools.ToolCatalog
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Debug builds only: does the model pick the right tool, with the right arguments, for what
 * people actually say? Each case is scored; each tuning of Nano ([NanoPrompt.Tuning]) runs every
 * case, with every tool the app can declare and its full instructions.
 *
 *   adb shell am broadcast -a com.local.assistant.debug.MODEL_PROBE -p com.local.assistant.dev --es mode tools [--es variants plain,docs+examples] [--es cases a,b] [--ez gemma true] [--ez auto true]
 *
 * [auto]: as an Auto chat, with ask_gemini declared and its rule, and its own cases ([AUTO_CASES])
 * after the rest: does the model ask Gemini when it should, with roles for names, and still use
 * the phone's tools for the user's own things?
 */
class ToolBench(
    private val gemma: LlmBackend,
    private val persona: String,
    private val log: (String) -> Unit,
    /** Chat's sampling, or another to compare (Nano's backend caps the temperature). */
    private val sampling: Sampling = Sampling.CHAT,
    private val auto: Boolean = false,
) {
    /** What a right call looks like: [tool] (null: no call at all) and a pattern per argument. */
    data class Expect(val tool: String?, val args: Map<String, String> = emptyMap(), val absent: Map<String, String> = emptyMap())

    data class Case(
        val id: String,
        /** Each a user message; only the last is scored. */
        val turns: List<String>,
        val expect: List<Expect>,
        /** Sent as a voice message, as the phone wrote it down. */
        val voice: Boolean = false,
    )

    private val now = "[Now: Tue 24 Nov 2026, 10:15]"
    private val system = Instructions.render(persona, withTools = true, photoSearch = true, healthData = true, fileSearch = true, inbox = true, ask = auto)
    private val tools = ToolCatalog.declarations(web = false, photos = true, health = true, files = true, inbox = true, ask = auto)

    suspend fun run(variants: List<NanoPrompt.Tuning>, cases: Set<String>?, gemma: Boolean) {
        val chosen = (if (auto) CASES + AUTO_CASES else CASES).filter { cases == null || it.id in cases }
        log("# Tool benchmark${if (auto) " (Auto chat)" else ""}, ${java.util.Date()}: ${chosen.size} cases, ${tools.size} tools, $sampling")
        log("")
        val scores = mutableListOf<String>()
        for (tuning in variants) {
            val nano = AiCoreBackend(tuning = { tuning })
            if (withTimeoutOrNull(30_000) { nano.ensureReady() } != true) {
                log("Nano isn't available: ${nano.report}")
                return
            }
            scores += bench("nano $tuning", nano, chosen)
        }
        if (gemma) {
            this.gemma.ensureReady()
            scores += bench("gemma", this.gemma, chosen)
        }
        log("## Scores")
        scores.forEach(log)
    }

    private suspend fun bench(label: String, backend: LlmBackend, cases: List<Case>): String {
        log("## $label")
        var passed = 0
        var cleanReplies = 0
        var ms = 0L
        var throttled = 0
        val failed = mutableListOf<String>()
        for (case in cases) {
            var start = SystemClock.elapsedRealtime()
            var result = runCatching { withTimeout(120_000) { play(backend, case) } }
            // AICore's per-app quota (ErrorCode 9) refills with time: wait, and try again.
            var waits = 0
            while (result.getOrNull()?.second?.contains("ErrorCode 9") == true && waits++ < 6) {
                throttled++
                log("    (throttled by AICore; waiting 30 s)")
                delay(30_000)
                start = SystemClock.elapsedRealtime()
                result = runCatching { withTimeout(120_000) { play(backend, case) } }
            }
            val took = SystemClock.elapsedRealtime() - start
            delay(PACE_MS)
            ms += took
            val (calls, reply) = result.getOrElse { emptyList<ToolCall>() to "<ERROR ${it.javaClass.simpleName}: ${it.message}>" }
            val ok = case.expect.any { matches(it, calls) }
            val clean = BAD_REPLY.none { it.containsMatchIn(reply) }
            if (ok) passed++ else failed += case.id
            if (clean) cleanReplies++
            log("- ${if (ok) "PASS" else "FAIL"} ${case.id} ($took ms): ${calls.joinToString("; ") { "${it.name}${it.arguments}" }.ifEmpty { "no call" }}")
            log("    reply${if (clean) "" else " [BAD]"} (${reply.length} chars): ${reply.replace("\n", " ").take(220)}${if (reply.length > 220) " … " + reply.replace("\n", " ").takeLast(80) else ""}")
        }
        log("")
        return "- $label: $passed/${cases.size} right calls, $cleanReplies/${cases.size} clean replies, ${ms / cases.size.coerceAtLeast(1)} ms a case, throttled $throttled times. Failed: ${failed.joinToString(", ").ifEmpty { "none" }}"
    }

    /** Plays [case]'s turns; returns the last turn's first-round calls and its final reply. */
    private suspend fun play(backend: LlmBackend, case: Case): Pair<List<ToolCall>, String> {
        val session = backend.openChat(
            ChatSpec(systemPrefix = system, history = emptyList(), toolDeclarations = tools, sampling = sampling, maxOutputTokens = 512, prefillOnOpen = true),
        )
        try {
            var firstCalls: List<ToolCall> = emptyList()
            var reply = ""
            for ((i, said) in case.turns.withIndex()) {
                val words = if (case.voice) PromptAssembler.heard(said) else said
                var events = session.send("$now\n$words")
                val text = StringBuilder()
                var round = 0
                firstCalls = emptyList()
                while (true) {
                    var asked: List<ToolCall> = emptyList()
                    events.collect { event ->
                        when (event) {
                            is GenEvent.TextDelta -> text.append(event.text)
                            is GenEvent.ToolCalls -> asked = event.calls
                            is GenEvent.Error -> text.append("<ERROR ${event.cause.message}>")
                            is GenEvent.Done -> Unit
                        }
                    }
                    if (round == 0) firstCalls = asked
                    if (asked.isEmpty() || round++ >= 2) break
                    events = session.sendToolResults(asked.map { ToolResult(it.name, fakeResult(it)) })
                }
                if (i == case.turns.lastIndex) reply = text.toString().trim()
            }
            return firstCalls to reply
        } finally {
            session.close()
        }
    }

    private fun matches(expect: Expect, calls: List<ToolCall>): Boolean {
        if (expect.tool == null) return calls.isEmpty()
        val call = calls.firstOrNull { it.name == expect.tool } ?: return false
        val args = call.arguments.mapValues { it.value?.toString().orEmpty() }
        return expect.args.all { (key, pattern) -> Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(args[key].orEmpty()) } &&
            expect.absent.none { (key, pattern) -> Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(args[key].orEmpty()) }
    }

    /** Enough for the model to finish its reply, as the real tool would say it. */
    private fun fakeResult(call: ToolCall): String = when (call.name) {
        ToolCatalog.FIND_PHOTOS -> if (call.arguments["question"] != null) """{"ok":true,"found":1,"answer":"PAN: ABCDE1234F"}""" else """{"ok":true,"found":12}"""
        // As DeviceTools.sendMessage says it.
        ToolCatalog.SEND_MESSAGE -> """{"ok":true,"ready":"${call.arguments["app"] ?: "SMS"} open to ${call.arguments["to"]} with the message; not sent yet, the user taps send"}"""
        ToolCatalog.CALL -> """{"ok":true,"opened":"dialer","who":"${call.arguments["who"]}"}"""
        ToolCatalog.ADD_TASK -> """{"ok":true,"id":41,"due":"Wed 25 Nov 18:00"}"""
        ToolCatalog.SET_ALARM -> """{"ok":true,"time":"05:30","day":"Wed 25 Nov"}"""
        ToolCatalog.SET_TIMER -> """{"ok":true,"ends":"10:25"}"""
        ToolCatalog.HEALTH -> """{"ok":true,"steps":6240,"goal":8000,"period":"today"}"""
        ToolCatalog.CHECK_INBOX -> """{"ok":true,"found":1,"messages":[{"from":"Rahul","app":"WhatsApp","at":"Mon 23 Nov 19:02","text":"Trip is on for Saturday, leaving 6am"}]}"""
        ToolCatalog.EDIT_NOTE -> """{"ok":true,"note":"Shopping list","items":4}"""
        ToolCatalog.READ_FILE -> """{"ok":true,"found":"Resume_2026.pdf","opened":true}"""
        ToolCatalog.CALCULATE -> """{"ok":true,"result":"441"}"""
        ToolCatalog.GET_UPCOMING -> """{"ok":true,"items":[{"title":"Team call","at":"Wed 25 Nov 11:00"}]}"""
        // In the app the answer is the reply itself; here, enough to end the turn.
        ToolCatalog.ASK_GEMINI -> """{"ok":true,"answered":true,"by":"Gemini · asked by Luna"}"""
        else -> """{"ok":true}"""
    }

    companion object {
        fun tuning(name: String): NanoPrompt.Tuning = NanoPrompt.Tuning(
            paramDocs = "docs" in name,
            examples = "examples" in name,
            focus = "focus" in name,
            thinking = "thinking" in name,
        )

        val VARIANTS = listOf("plain", "docs", "docs+examples", "docs+examples+focus", "docs+examples+focus+thinking").map(::tuning)

        /** Between cases, to stay inside AICore's per-app quota. */
        const val PACE_MS = 4_000L

        /** A reply that says a message went, or repeats the app's notes. */
        private val BAD_REPLY = listOf(
            Regex("(has been|was|were|I've|I have|I) sent|sent (it|successfully|the message)|message sent", RegexOption.IGNORE_CASE),
            Regex("\\[(Now|Memory|Saved image|The phone)"),
            Regex("<ERROR"),
            // A refusal of what needs no tool ("I can't tell jokes", 24 Nov 2026).
            Regex("\\bI (can't|cannot|am unable to|am not able to) (tell|fulfill|write|help with)", RegexOption.IGNORE_CASE),
        )

        private val ME = "^(me|myself|user|i)\\b"
        private val LAST_MONTH = "last month|october"

        val CASES = listOf(
            Case("photos_me_month", listOf("Could you show me the photos of me from last month"), listOf(Expect(ToolCatalog.FIND_PHOTOS, mapOf("person" to ME, "when" to LAST_MONTH)))),
            Case("photos_me_month_voice", listOf("Could you show me the photos of me from last month"), listOf(Expect(ToolCatalog.FIND_PHOTOS, mapOf("person" to ME, "when" to LAST_MONTH))), voice = true),
            Case("photos_after_pan", listOf("what's my PAN card number", "show photos of me from last month"), listOf(Expect(ToolCatalog.FIND_PHOTOS, mapOf("person" to ME, "when" to LAST_MONTH), absent = mapOf("query" to "pan|card", "question" to ".")))),
            Case("photos_beach_video", listOf("show my beach videos"), listOf(Expect(ToolCatalog.FIND_PHOTOS, mapOf("query" to "video")))),
            Case("photos_with_friend", listOf("photos of me with Shiyas"), listOf(Expect(ToolCatalog.FIND_PHOTOS, mapOf("person" to "shiyas")))),
            Case("photos_place", listOf("pictures I took in Munnar"), listOf(Expect(ToolCatalog.FIND_PHOTOS, mapOf("place" to "munnar")), Expect(ToolCatalog.FIND_PHOTOS, mapOf("query" to "munnar")))),
            Case("doc_number", listOf("what's my driving licence number"), listOf(Expect(ToolCatalog.FIND_PHOTOS, mapOf("query" to "licen", "question" to "."))))
            ,
            Case("doc_show", listOf("show me my Aadhaar card"), listOf(Expect(ToolCatalog.FIND_PHOTOS, mapOf("query" to "aadha")))),
            Case("photos_all", listOf("show all of my photos"), listOf(Expect(null), Expect(ToolCatalog.FIND_PHOTOS, absent = mapOf("person" to ME, "query" to "^(?!\\s*(all|my|photos?|pictures?|images?|\\s)*$).+")))),
            Case("msg_umma", listOf("send hi to Umma on WhatsApp"), listOf(Expect(ToolCatalog.SEND_MESSAGE, mapOf("to" to "umma", "app" to "whatsapp")))),
            Case("msg_voice_misheard", listOf("Sun high to Uma on WhatsApp"), listOf(Expect(ToolCatalog.SEND_MESSAGE, mapOf("to" to "uma", "app" to "whatsapp"))), voice = true),
            Case("msg_name_only", listOf("send a message to Umma", "umma"), listOf(Expect(ToolCatalog.SEND_MESSAGE, mapOf("to" to "umma")))),
            Case("remind_arrive", listOf("remind me when we reach near Whitefield"), listOf(Expect(ToolCatalog.ADD_TASK, mapOf("place" to "whitefield"), absent = mapOf("place" to "near|reach", "title" to "when|reach")))),
            Case("code_long", listOf("could you write the code for an ice-cream shop website in html"), listOf(Expect(null))),
            Case("call", listOf("call Rahul"), listOf(Expect(ToolCatalog.CALL, mapOf("who" to "rahul")))),
            Case("remind", listOf("remind me to call mom tomorrow at 6pm"), listOf(Expect(ToolCatalog.ADD_TASK, mapOf("title" to "mom", "when" to "6")))),
            Case("remind_place", listOf("remind me to buy milk when I'm near DMart"), listOf(Expect(ToolCatalog.ADD_TASK, mapOf("title" to "milk", "place" to "dmart")))),
            Case("alarm", listOf("wake me up at 5:30 tomorrow"), listOf(Expect(ToolCatalog.SET_ALARM, mapOf("when" to "5:30")))),
            Case("alarm_ml", listOf("നാളെ രാവിലെ 7 മണിക്ക് എന്നെ ഉണർത്തണം"), listOf(Expect(ToolCatalog.SET_ALARM, mapOf("when" to "7")))),
            Case("timer", listOf("set a timer for 10 minutes"), listOf(Expect(ToolCatalog.SET_TIMER, mapOf("duration" to "10")))),
            Case("event", listOf("I have a dentist appointment on Friday at 4pm"), listOf(Expect(ToolCatalog.ADD_EVENT, mapOf("title" to "dentist")), Expect(ToolCatalog.ADD_TASK, mapOf("title" to "dentist")))),
            Case("steps", listOf("how many steps have I walked today?"), listOf(Expect(ToolCatalog.HEALTH, mapOf("metric" to "step")))),
            Case("inbox", listOf("did Rahul message me about the trip?"), listOf(Expect(ToolCatalog.CHECK_INBOX, mapOf("from" to "rahul")))),
            Case("note", listOf("add eggs and bread to my shopping list"), listOf(Expect(ToolCatalog.EDIT_NOTE, mapOf("add" to "egg")))),
            Case("file", listOf("open my resume pdf"), listOf(Expect(ToolCatalog.READ_FILE, mapOf("name" to "resume")))),
            Case("fact", listOf("remember that I'm vegetarian"), listOf(Expect(ToolCatalog.SAVE_FACT, mapOf("value" to "vegetarian")))),
            Case("calc", listOf("what's 18% of 2450?"), listOf(Expect(ToolCatalog.CALCULATE, mapOf("expression" to "2450")))),
            Case("upcoming", listOf("what's on my schedule tomorrow?"), listOf(Expect(ToolCatalog.GET_UPCOMING))),
            Case("setting", listOf("turn on the flashlight"), listOf(Expect(ToolCatalog.PHONE_SETTING, mapOf("setting" to "flash")))),
            Case("open_app", listOf("play Arijit Singh songs on YouTube"), listOf(Expect(ToolCatalog.OPEN_APP, mapOf("app" to "youtube")))),
            Case("no_tool_sky", listOf("why is the sky blue? one sentence"), listOf(Expect(null))),
            Case("no_tool_joke", listOf("tell me a short joke"), listOf(Expect(null))),
        )

        /**
         * An Auto chat's: what [com.local.assistant.hybrid.AutoRouter] leaves to the phone's model
         * (it names someone, or is about the user), where it should ask Gemini, and what it
         * shouldn't. The ones the router sends straight on (long writing, code, translation) never
         * reach the model, so aren't here.
         */
        val AUTO_CASES = listOf(
            Case("ask_plan_dinner", listOf("plan Priya's birthday dinner, she's vegetarian and we have about 1500 rupees"), listOf(Expect(ToolCatalog.ASK_GEMINI, mapOf("question" to "dinner|birthday|party")))),
            Case("ask_role_not_name", listOf("my friend Shiyas is moving to Berlin, give him tips for finding a flat there"), listOf(Expect(ToolCatalog.ASK_GEMINI, mapOf("question" to "berlin|flat|apartment"), absent = mapOf("question" to "shiyas")))),
            Case("ask_about_user", listOf("I'm vegetarian and going to Tokyo next month, what should I eat there?"), listOf(Expect(ToolCatalog.ASK_GEMINI, mapOf("question" to "tokyo")))),
            Case("ask_explain", listOf("explain in detail how a SIP in mutual funds works, with an example"), listOf(Expect(ToolCatalog.ASK_GEMINI, mapOf("question" to "sip|mutual")), Expect(null))),
            Case("ask_after_inbox", listOf("did my landlord message me about the rent?"), listOf(Expect(ToolCatalog.CHECK_INBOX))),
            Case("no_ask_remind", listOf("remind me to call the plumber at 5pm"), listOf(Expect(ToolCatalog.ADD_TASK, mapOf("title" to "plumber")))),
            Case("no_ask_photos", listOf("show me photos from my Goa trip"), listOf(Expect(ToolCatalog.FIND_PHOTOS, mapOf("place" to "goa")), Expect(ToolCatalog.FIND_PHOTOS, mapOf("query" to "goa")))),
            Case("no_ask_hello", listOf("hi! how's it going?"), listOf(Expect(null))),
        )
    }
}
