package com.local.assistant.hybrid

import com.local.assistant.privacy.SensitiveText
import java.util.Locale

/**
 * What an Auto chat's question to Gemini may carry. The phone's model writes each question and
 * decides what goes in it, and Gemini answers from exactly that: nothing is swapped for
 * placeholders on the way. (Placeholders were tried first, 26 Nov 2026: Gemini wrote
 * "[Person 1]" into a story that had no one in it to hide.) What stays fixed:
 *
 * - ID, card and account numbers and secrets ([SensitiveText]) go as `[withheld]` ([withhold]),
 *   whoever wrote the question.
 * - A question that copies a private message, file or reading out word for word waits for the
 *   user's say-so ([pastes], [AskConfirm]).
 * - A message that names someone Luna knows goes to the phone's model first, never straight on
 *   ([mentions], [AutoRouter]), so it is the phone's model that decides what of it to say.
 */
object Veil {

    /** The people Luna knows by name (contacts, the user, their memory and photos), any case. */
    data class Known(val people: List<String> = emptyList())

    /** [text] as it may be sent, and how many numbers were [withheld] from it. */
    data class Withheld(val text: String, val withheld: Int)

    const val WITHHELD = "[withheld]"

    /** [text] with its ID, card and account numbers as [WITHHELD]: never sent, and not kept to put back. */
    fun withhold(text: String): Withheld {
        var out = text
        var n = 0
        SensitiveText.find(text).sortedByDescending { it.start }.forEach { span ->
            out = out.replaceRange(span.start, span.end, WITHHELD)
            n++
        }
        return Withheld(out, n)
    }

    /** "1 ID or card number withheld"; null when none was. */
    fun withheldNote(n: Int): String? = when {
        n <= 0 -> null
        n == 1 -> "1 ID or card number withheld"
        else -> "$n ID or card numbers withheld"
    }

    /**
     * Whether [question] copies a run of [run] words or more out of any of [privateTexts] (a
     * message, a file, a health reading the turn read): then it isn't sent until the user has seen
     * it and said so.
     */
    fun pastes(question: String, privateTexts: List<String>, run: Int = PASTE_RUN): Boolean {
        val asked = words(question)
        if (asked.size < run) return false
        val grams = HashSet<String>()
        for (i in 0..asked.size - run) grams += asked.subList(i, i + run).joinToString(" ")
        return privateTexts.any { text ->
            val theirs = words(text)
            (0..theirs.size - run).any { i -> theirs.subList(i, i + run).joinToString(" ") in grams }
        }
    }

    /** Whether [name] can be looked for in text at all: long enough, and not an everyday word. */
    fun nameable(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.length < 3) return false
        if (trimmed.lowercase(Locale.ROOT) in EVERYDAY) return false
        return trimmed.any(Char::isLetter)
    }

    /** [name] as a whole word or words, any case, spaces as any run of spaces. */
    fun wordPattern(name: String): Regex {
        val parts = name.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
        return Regex("(?<![\\p{L}\\p{N}])$parts(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    }

    /** Whether [text] names any of [people] (a whole name, or a first name that is a name). */
    fun mentions(text: String, people: Collection<String>): Boolean =
        peopleNames(people.toList()).any { wordPattern(it).containsMatchIn(text) }

    /** Full names, then their first names (when they're a name and not a word), longest first. */
    private fun peopleNames(people: List<String>): List<String> {
        val whole = people.map { it.trim() }.filter(::nameable)
        val firsts = whole.mapNotNull { name -> name.split(Regex("\\s+")).firstOrNull()?.takeIf { it != name && nameable(it) } }
        return (whole + firsts).distinctBy { it.lowercase(Locale.ROOT) }.sortedByDescending { it.length }
    }

    private fun words(text: String): List<String> =
        WORD.findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()

    private const val PASTE_RUN = 12

    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")

    /** Words that are names too often to count as one ("Will it rain?", "the May bill"). */
    private val EVERYDAY = setOf(
        "the", "and", "you", "she", "her", "his", "him", "they", "them", "who", "what", "when", "where", "why", "how", "will", "may", "june",
        "april", "august", "march", "rose", "lily", "joy", "hope", "faith", "grace", "dawn", "summer", "autumn", "winter", "spring", "sky",
        "sunny", "honey", "baby", "babe", "dear", "love", "king", "queen", "prince", "rich", "bill", "mark", "pat", "art", "ray", "max",
        "home", "office", "work", "gym", "school", "college", "hospital", "mom", "dad", "mum", "amma", "appa", "achan", "papa", "mama",
        "bro", "sis", "wife", "husband", "boss", "doctor", "dr", "mr", "mrs", "ms", "sir", "madam", "uncle", "aunty", "auntie", "person",
        "place", "phone", "email", "withheld", "luna", "gemini", "google", "india", "bank", "shop", "store", "market", "mall", "station",
        "airport", "temple", "church", "mosque", "park", "beach", "hotel", "new", "old", "big", "little", "good", "best", "happy", "nice",
        "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "today", "tomorrow", "tonight", "morning", "evening",
    )
}
