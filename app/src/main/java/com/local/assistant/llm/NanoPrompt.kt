package com.local.assistant.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.local.assistant.memory.tools.ToolCatalog

/**
 * What Gemini Nano is given beyond the app's own prompt, to call the app's tools as well as
 * Gemma does. The app's prompt and tool texts were written for Gemma; Nano 4 read them less well
 * (24 Nov 2026, on the phone): it said it couldn't see photos instead of calling find_photos,
 * carried an earlier search's words into a new one, asked for a number instead of trying a name,
 * said messages were sent, and copied the app's [bracketed] notes into replies.
 */
object NanoPrompt {

    /** Which of these to use; each was measured on its own (ModelProbe's tool benchmark). */
    data class Tuning(
        /** Each parameter's part of a tool's description moved onto the parameter itself. */
        val paramDocs: Boolean = false,
        /** Worked examples of calls and the rules Nano broke, after the app's instructions. */
        val examples: Boolean = false,
        /** Only the tools a message could be asking for, rather than all of them. */
        val focus: Boolean = false,
        /** Nano 4's thinking before it answers. */
        val thinking: Boolean = false,
    ) {
        companion object {
            /**
             * What chats use: 28 of 30 cases called right, against 23 of 30 with none of it, and
             * in ~2 s a case, as fast as none of it since fewer tools are offered (ToolBench on
             * the phone, 24 Nov 2026). Thinking wasn't measured; it can only slow replies.
             */
            val BEST = Tuning(paramDocs = true, examples = true, focus = true)
        }

        override fun toString() = listOfNotNull("docs".takeIf { paramDocs }, "examples".takeIf { examples }, "focus".takeIf { focus }, "thinking".takeIf { thinking })
            .joinToString("+").ifEmpty { "plain" }
    }

    /**
     * A declaration whose description explains its parameters inline ("query: what is in the
     * photos …. person: only for …"), with each explanation moved onto its parameter, where a
     * small model looks for it. The part before the first parameter stays the tool's description.
     */
    fun withParamDocs(json: String): String = runCatching {
        val root = JsonParser.parseString(json).asJsonObject
        val description = root.get("description")?.asString ?: return json
        val properties = root.getAsJsonObject("parameters")?.getAsJsonObject("properties") ?: return json
        val names = properties.keySet()
        // Where each "name:" begins, in the order they come.
        val marks = names.mapNotNull { name ->
            Regex("(?<![A-Za-z_])${Regex.escape(name)}:\\s").find(description)?.let { it.range.first to name }
        }.sortedBy { it.first }
        if (marks.isEmpty()) return json
        val lead = description.substring(0, marks.first().first).trim()
        marks.forEachIndexed { i, (start, name) ->
            val end = marks.getOrNull(i + 1)?.first ?: description.length
            val text = description.substring(start + name.length + 1, end).trim().trimEnd(';', ',').trim()
            val property = properties.getAsJsonObject(name) ?: JsonObject().also { properties.add(name, it) }
            if (text.isNotEmpty()) property.addProperty("description", text.replaceFirstChar { it.uppercase() })
        }
        root.addProperty("description", lead.ifEmpty { description })
        root.toString()
    }.getOrDefault(json)

    /**
     * The user's message as Nano gets it. A voice message comes to Gemma as its recording with
     * the phone's words in a note ([com.local.assistant.memory.prompt.PromptAssembler.heard]);
     * Nano can't hear, so the words are the message itself, with what may be wrong in them said.
     */
    fun forNano(envelope: String, heard: String? = null): String = HEARD.replace(envelope) { match ->
        if (heard != null) {
            "(Voice message, written down from its recording; a name or number may still be misheard:) $heard"
        } else {
            "(Voice message, written down by the phone's speech recogniser, which may have misheard a name or number:) " + match.groupValues[1]
        }
    }

    /** The phone's words for a voice message in [envelope], if it has one. */
    fun heardWords(envelope: String): String? = HEARD.find(envelope)?.groupValues?.get(1)

    private val HEARD = Regex("\\[The phone's speech recogniser wrote this voice message down as: \"(.*?)\"\\. It may have misheard a name or a number\\.]", RegexOption.DOT_MATCHES_ALL)

    /**
     * The app's instructions with what Nano misread put plainly. "You act only through these
     * tools" was read as all it could do: asked for a website's HTML it said "I cannot write
     * complex code like a complete website", and a rule arguing back made it refuse a joke as well
     * (ToolBench, 24 Nov 2026). Gemma's instructions are unchanged.
     */
    fun reworded(system: String): String = system.replace(ACT_ONLY, ACT_ONLY_PLAIN)

    private const val ACT_ONLY = "You act only through these tools."
    private const val ACT_ONLY_PLAIN = "You do things on the phone and in the world (send, call, set, save, open) only through these tools; answering and writing (code, letters, jokes) you do yourself, in your reply."

    /** The examples and rules for the tools [declared], to follow the app's instructions. */
    fun guide(declared: Set<String>): String = buildString {
        append("How to use the tools: call them, then answer in a short sentence.\n")
        append("Examples (what the user says → the call):\n")
        EXAMPLES.filter { (tool, _) -> tool in declared }.forEach { (_, line) -> append("- ").append(line).append('\n') }
        append(
            """
            Rules:
            - When a tool can do it, call it. Never say you can't see their photos, files, messages, steps or schedule: the tools can.
            - Asked again for something done earlier, or told it didn't work, call the tool again. Never answer from an earlier result.
            - Fill a call only from the user's latest message. Never carry words from an earlier search or message into a new call.
            - Names go into calls exactly as the user said them (Umma, Shiyas, Achan); the app finds the contact. Never ask for a number before trying the name.
            - send_message and phone_call only open the app: say it's ready to send or call, never that it was sent or called.
            - Lines in [square brackets] are notes from the app. Use them, but never write them in a reply.
            - After a tool runs, answer from its result in one or two short sentences that say what was done: the reminder's title and its time or place, who the message is to. If it failed, say what it said.
            - Jokes, explanations and writing (code, a letter, a poem) need no tool: write them in your reply, in full. Save to a note only when asked to.
            - "Continue" means go on from exactly where your last reply stopped.
            """.trimIndent(),
        )
        if (ToolCatalog.ASK_GEMINI in declared) {
            append(
                "\n- ask_gemini is for long writing, planning, comparing and detailed explanations. Its question must stand on its own " +
                    "and carry only what the answer needs: a name only if the answer uses it, never phone numbers, addresses or ID " +
                    "numbers, and never a message, file or health reading copied in. " +
                    "Never use it for reminders, alarms, calls, messages, photos, files, notes or small talk.",
            )
        }
    }

    /** One example per tool, phrased unlike the benchmark's cases. */
    private val EXAMPLES = listOf(
        ToolCatalog.FIND_PHOTOS to "\"pictures of me and Shiyas from 2023\" → find_photos(person=\"me, Shiyas\", when=\"2023\")",
        ToolCatalog.FIND_PHOTOS to "\"my sunset photos in Goa\" → find_photos(query=\"sunset\", place=\"Goa\")",
        ToolCatalog.FIND_PHOTOS to "\"what's my passport expiry date\" → find_photos(query=\"passport\", question=\"passport expiry date\")",
        ToolCatalog.ADD_TASK to "\"don't let me forget to pay the EB bill on Friday\" → add_task(title=\"Pay the EB bill\", when=\"on Friday\")",
        ToolCatalog.ADD_TASK to "\"remind me to buy bread when I reach Lulu\" → add_task(title=\"Buy bread\", place=\"Lulu\")",
        ToolCatalog.SET_ALARM to "\"get me up at 6 tomorrow\" → set_alarm(when=\"6 tomorrow\")",
        ToolCatalog.SET_TIMER to "\"timer for 15 minutes for the rice\" → set_timer(duration=\"15 minutes\", label=\"rice\")",
        ToolCatalog.SEND_MESSAGE to "\"text Achan that I'll be late\" → send_message(to=\"Achan\", text=\"I'll be late\", app=\"sms\")",
        ToolCatalog.CALL to "\"ring Shiyas\" → phone_call(who=\"Shiyas\")",
        ToolCatalog.CHECK_INBOX to "\"did the bank send me anything today\" → check_inbox(from=\"bank\", when=\"today\")",
        ToolCatalog.HEALTH to "\"how did I sleep last night\" → health_data(metric=\"sleep\", when=\"last night\")",
        ToolCatalog.READ_FILE to "\"summarise my rental agreement\" → read_file(name=\"rental agreement\", question=\"summary\")",
        ToolCatalog.EDIT_NOTE to "\"put milk on my grocery list\" → edit_note(note=\"grocery list\", add=\"milk\")",
        ToolCatalog.SAVE_FACT to "\"remember that my dentist is Dr. Rao\" → save_fact(subject=\"user\", attribute=\"dentist\", value=\"Dr. Rao\")",
        ToolCatalog.GET_UPCOMING to "\"what have I got this week\" → get_upcoming(days=7)",
        ToolCatalog.PHONE_SETTING to "\"switch off bluetooth\" → phone_setting(setting=\"bluetooth\", value=\"off\")",
        ToolCatalog.OPEN_APP to "\"directions to Lulu mall\" → open_app(app=\"maps\", query=\"Lulu mall\")",
        ToolCatalog.CALCULATE to "\"15% tip on 860\" → calculate(expression=\"860*15/100\")",
        ToolCatalog.WEB_SEARCH to "\"will it rain in Kochi today\" → web_lookup(query=\"Kochi weather today\")",
        ToolCatalog.ASK_GEMINI to "\"write a toast for Anu's farewell, she's moving to Canada\" → ask_gemini(question=\"Write a short farewell toast for Anu, a colleague who is moving to Canada\")",
        ToolCatalog.ASK_GEMINI to "\"plan a 3-day Kochi trip for me and my parents, we're vegetarian\" → ask_gemini(question=\"Plan a 3-day trip to Kochi for a family of three\", about_user=\"vegetarian; travelling with parents\")",
    )

    /**
     * The tools [userText] could be asking for, by its words, plus any [alwaysKeep] (those the
     * chat has already called). Nothing recognised: every tool, so a request is never left without one.
     */
    fun focus(declared: List<String>, userText: String, alwaysKeep: Set<String> = emptySet()): Set<String> {
        // The app's notes ([Now: …], [Memory: …]) are not what the user asked.
        val said = userText.replace(BRACKETED, " ").lowercase()
        val wanted = TOPICS.filter { (pattern, _) -> pattern.containsMatchIn(said) }.flatMap { it.second }.toSet()
        if (wanted.isEmpty()) return declared.toSet()
        return declared.filter { it in wanted || it in alwaysKeep || it in ALWAYS }.toSet()
    }

    private val BRACKETED = Regex("\\[[^\\]]*]")

    /**
     * Cheap to offer, and wanted in all sorts of messages. ask_gemini too, where declared (Auto
     * chats): whatever a message is about, a part of it may be better asked in the cloud.
     */
    private val ALWAYS = setOf(ToolCatalog.SAVE_FACT, ToolCatalog.SEARCH_MEMORY, ToolCatalog.ASK_GEMINI)

    private fun words(vararg w: String) = Regex("\\b(" + w.joinToString("|") + ")")

    private val TOPICS: List<Pair<Regex, Set<String>>> = listOf(
        words("photo", "pic", "picture", "image", "video", "selfie", "gallery", "licen[cs]e", "passport", "aadha?a?r", "pan\\b", "card", "ticket", "bill", "certificate", "insurance", "document", "id\\b", "marks", "score")
            to setOf(ToolCatalog.FIND_PHOTOS, ToolCatalog.READ_FILE),
        words("send", "text", "message", "whatsapp", "sms", "e-?mail", "mail", "tell", "wish", "reply", "say hi", "hi to", "high to")
            to setOf(ToolCatalog.SEND_MESSAGE, ToolCatalog.CHECK_INBOX, ToolCatalog.CALL),
        words("did .* (say|send|message|text|reply)", "sent me", "message(d)? me", "inbox", "otp", "any message")
            to setOf(ToolCatalog.CHECK_INBOX),
        words("call", "dial", "ring", "phone")
            to setOf(ToolCatalog.CALL, ToolCatalog.ADD_TASK),
        words("remind", "reminder", "forget to", "don'?t let me", "to-?do", "task", "later", "tomorrow", "tonight", "near", "when i (reach|get|am)")
            to setOf(ToolCatalog.ADD_TASK, ToolCatalog.UPDATE_TASK, ToolCatalog.ADD_EVENT),
        words("alarm", "wake", "get me up")
            to setOf(ToolCatalog.SET_ALARM),
        words("timer", "countdown", "minutes? for", "seconds")
            to setOf(ToolCatalog.SET_TIMER),
        words("schedule", "calendar", "meeting", "appointment", "event", "upcoming", "plans?", "agenda", "birthday", "busy", "free")
            to setOf(ToolCatalog.GET_UPCOMING, ToolCatalog.ADD_EVENT, ToolCatalog.ADD_TASK),
        words("list", "note", "shopping", "grocer", "groceries", "packing", "jot", "write (it )?down")
            to setOf(ToolCatalog.EDIT_NOTE),
        words("steps?", "walk", "walked", "sleep", "slept", "heart", "calorie", "workout", "exercise", "distance", "fitness", "health")
            to setOf(ToolCatalog.HEALTH),
        words("file", "pdf", "doc", "word", "resume", "cv", "folder", "spreadsheet", "excel", "slides", "agreement", "download")
            to setOf(ToolCatalog.READ_FILE),
        words("flash", "torch", "wi-?fi", "bluetooth", "volume", "silent", "vibrate", "dnd", "do not disturb", "airplane", "aeroplane", "flight mode", "mobile data", "ringer", "brightness")
            to setOf(ToolCatalog.PHONE_SETTING),
        words("open", "play", "youtube", "maps?", "directions", "navigate", "spotify", "song", "music", "launch", "search for")
            to setOf(ToolCatalog.OPEN_APP),
        Regex("\\d\\s*[%+*/x×÷-]\\s*\\d|\\b(percent|%|convert|how much is|calculate|sum|total|divide|multiply|km|miles?|kg|pounds?|celsius|fahrenheit)\\b")
            to setOf(ToolCatalog.CALCULATE),
        words("remember", "forget", "my \\w+ is", "i am", "i'm", "i have", "do you know", "did i (tell|say)", "what('s| is) my", "who is my")
            to setOf(ToolCatalog.SAVE_FACT, ToolCatalog.FORGET, ToolCatalog.SEARCH_MEMORY),
        words("screenshot", "screen")
            to setOf(ToolCatalog.TAKE_SCREENSHOT, ToolCatalog.SEND_MESSAGE),
        words("news", "weather", "rain", "price", "score", "latest", "today'?s", "look up", "google", "search online")
            to setOf(ToolCatalog.WEB_SEARCH),
    )
}
