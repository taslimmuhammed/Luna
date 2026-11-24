package com.local.assistant.memory.prompt

import com.local.assistant.memory.tools.ToolCatalog
import com.local.assistant.wake.WakeWord

/**
 * Section A: the constant part of the system instruction.
 *
 * Versioned because it is part of a byte-stable prefix: changing a word here changes every
 * conversation's prefix, which forces a rebuild, and the version is how that is noticed.
 */
object Instructions {

    const val VERSION = 10

    /**
     * What the model is told it is when the persona doesn't say: an assistant, named after the wake
     * word (the user, 2026-10-30: "since the model is now named as luna, it should be aware of it
     * ... also tell it to behave as an assistant if it's not present").
     */
    const val ROLE = "You are a helpful assistant running entirely on the user's device."
    const val NAMED = "Your name is ${WakeWord.NAME}."

    /**
     * What a Cloud chat's model is told it is: in the cloud by the user's choice, seeing only what
     * was said in Cloud mode ([com.local.assistant.hybrid.CloudScope]).
     */
    const val CLOUD_ROLE = "You are a helpful assistant. The user switched this chat to Cloud mode, so you run on Gemini in the cloud and see only what they said in Cloud mode. " +
        "Their Local chats, contacts, messages, photos, files and health data stay on their phone: you can't see them, and never guess at them."

    private const val ON_DEVICE = " running entirely on the user's device"

    /** [withTools] adds the routing rules for the declared tools. */
    fun render(
        persona: String,
        withTools: Boolean = false,
        webSearch: Boolean = false,
        photoSearch: Boolean = false,
        healthData: Boolean = false,
        fileSearch: Boolean = false,
        inbox: Boolean = false,
        /** A Cloud chat's: [CLOUD_ROLE], and its tools' rules ([ToolCatalog.cloudRules]). */
        cloud: Boolean = false,
    ): String = buildString {
        if (cloud) {
            // The default persona says it runs on the device, which here it doesn't.
            val own = persona.replace(ON_DEVICE, "").trim()
            append(listOfNotNull(CLOUD_ROLE, own.takeIf { it.isNotEmpty() }, NAMED.takeUnless { own.contains(WakeWord.NAME, ignoreCase = true) }).joinToString(" "))
        } else {
            append(identity(persona))
        }
        append("\n\n")
        append(MEMORY_RULES)
        if (withTools) {
            append("\n\n")
            append(if (cloud) ToolCatalog.cloudRules(webSearch) else ToolCatalog.rules(webSearch, photoSearch, healthData, fileSearch, inbox))
        }
    }

    /** The persona, with [ROLE] before it if it never says "assistant" and [NAMED] after if it never names it. */
    fun identity(persona: String): String {
        val trimmed = persona.trim()
        return listOfNotNull(
            ROLE.takeUnless { trimmed.contains("assistant", ignoreCase = true) },
            trimmed.takeIf { it.isNotEmpty() },
            NAMED.takeUnless { trimmed.contains(WakeWord.NAME, ignoreCase = true) },
        ).joinToString(" ")
    }

    private val MEMORY_RULES = """
        The app gives you a memory. How to use it:
        - Text in [square brackets] at the start of a user message is added by the app, not typed by the user.
        - [Now: …] is the current date and time. Trust it over anything else, and use it for words like "today" or "tomorrow".
        - "About the user" is what the user asked you to keep in mind. Follow their preferences in every reply.
        - [Memory: …] lines are facts recalled for this message. [Memory (date): …] lines are parts of earlier conversations, with the day they happened; they can be out of date. When they disagree, "About the user" wins, then [Memory: …], then the newest dated line.
        - [Saved image (date) "…": …] is an image the user asked you to remember, with what you noted when saving it. The image itself is attached to that message, or was earlier in this chat: look at it to answer, and use the note for exact text.
        - "Notes and lists" names the user's own notes. [List "…" (list 3): …] and [Note "…" (note 7): …] show one as it is now: answer from it over anything older.
        - Use all of this naturally. Do not list or quote these notes unless the user asks what you remember.
    """.trimIndent()
}
