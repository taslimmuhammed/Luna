package com.local.assistant.hybrid

import com.local.assistant.memory.retrieval.QueryText
import com.local.assistant.privacy.SensitiveText
import java.util.Locale

/**
 * Where an Auto chat's message goes ([ChatMode.AUTO]), decided before the turn by rules alone, so
 * it costs nothing: no model is asked.
 *
 * - [Lane.LOCAL_ONLY]: nothing may go. Offline, Cloud not set up, today's questions used, the phone
 *   locked, or an ID or card number in the message.
 * - [Lane.LOCAL_CAN_ASK]: the phone's model answers, and may ask Gemini (`ask_gemini`) with a
 *   question it writes itself. The default, and always so for anything about the user's own
 *   things: their photos, messages, files, health, reminders, people they named.
 * - [Lane.ASK_FIRST]: the app asks Gemini itself with the user's own words (no one Luna knows
 *   named in them, no ID or card number), with no turn of the phone's model first: long writing, code, translation, things
 *   that change (news, prices, scores), planning and comparing, a follow-up to Gemini's last
 *   answer. Only for a message that stands on its own: one that leans on what came before ("write
 *   a poem about it") is the phone's model's to put into words.
 *
 * The phone being hot or low on battery widens [Lane.ASK_FIRST] to explanations ("how does UPI
 * work"), which the phone's model would answer at length.
 */
object AutoRouter {

    enum class Lane { LOCAL_ONLY, LOCAL_CAN_ASK, ASK_FIRST }

    /** The message and its chat. [knownNames]: people Luna knows by name, any case ([Veil.Known.people]). */
    data class Input(
        val text: String,
        val hasAttachment: Boolean = false,
        /** Asked over a screen, about it: its screenshot goes with it, and it never leaves the phone. */
        val aboutScreen: Boolean = false,
        val locked: Boolean = false,
        /** The chat's last reply came from Gemini: a short "make it shorter" goes back to it. */
        val lastWasAsk: Boolean = false,
        val knownNames: Collection<String> = emptyList(),
    )

    /** What the phone and the cloud allow right now ([HybridSignals]). */
    data class Signals(
        val online: Boolean = true,
        val cloudReady: Boolean = true,
        val asksLeft: Int = Int.MAX_VALUE,
        /** Thermal status moderate or worse. */
        val hot: Boolean = false,
        /** Under 15% and not charging. */
        val lowBattery: Boolean = false,
    )

    /** [reason]: said under the reply; null for [Lane.LOCAL_CAN_ASK], whose reason the turn settles. */
    data class Route(val lane: Lane, val reason: RouteReason?)

    fun route(input: Input, signals: Signals): Route {
        val text = input.text.trim().replace(WAKE, "")
        val lower = text.lowercase(Locale.ROOT).replace('’', '\'')
        // Nothing may leave the phone.
        when {
            input.locked -> return Route(Lane.LOCAL_ONLY, RouteReason.LOCKED)
            !signals.cloudReady -> return Route(Lane.LOCAL_ONLY, RouteReason.NOT_SET_UP)
            !signals.online -> return Route(Lane.LOCAL_ONLY, RouteReason.OFFLINE)
            signals.asksLeft <= 0 -> return Route(Lane.LOCAL_ONLY, RouteReason.ALLOWANCE)
            SensitiveText.contains(text) -> return Route(Lane.LOCAL_ONLY, RouteReason.SENSITIVE)
        }
        val canAsk = Route(Lane.LOCAL_CAN_ASK, null)
        // What only the phone can see or do: its model answers, asking if it needs to.
        if (input.hasAttachment || input.aboutScreen || text.isEmpty()) return canAsk
        if (QueryText.isCommand(text) || personal(lower) || namesSomeone(text, input.knownNames)) return canAsk
        // Back to Gemini for more of what it just wrote.
        if (input.lastWasAsk && FOLLOW_UP.containsMatchIn(lower)) return Route(Lane.ASK_FIRST, RouteReason.FOLLOW_UP)
        if (QueryText.isSmallTalk(text) || QueryText.isTrivial(text)) return canAsk
        // Leans on what came before: the phone's model puts it into a question of its own.
        if (REFERS_BACK.containsMatchIn(lower)) return canAsk
        val reason = when {
            TRANSLATE.containsMatchIn(lower) -> RouteReason.TRANSLATE
            CODE.containsMatchIn(lower) -> RouteReason.WRITING
            WRITING.containsMatchIn(lower) && !TO_SOMEONE.containsMatchIn(lower) -> RouteReason.WRITING
            LIVE.containsMatchIn(lower) -> RouteReason.LIVE
            PLANNING.containsMatchIn(lower) -> RouteReason.PLANNING
            (signals.hot || signals.lowBattery) && EXPLAIN.containsMatchIn(lower) -> RouteReason.SAVE_BATTERY
            else -> null
        }
        return if (reason != null) Route(Lane.ASK_FIRST, reason) else canAsk
    }

    /** "Hey Luna, …" down to what is asked. */
    private val WAKE = Regex("^(hey |hi |ok |okay )?luna\\b[,.!]?\\s*", RegexOption.IGNORE_CASE)

    /** About the user's own things: what the phone keeps, or what they told it. */
    internal fun personal(lower: String): Boolean =
        QueryText.asksForPhotos(lower) || QueryText.asksAboutMessages(lower) || MY_THINGS.containsMatchIn(lower) ||
            ABOUT_ME.containsMatchIn(lower) || NEAR_ME.containsMatchIn(lower)

    /** A person Luna knows, named in [text]: what's said about them is the phone's to word. */
    internal fun namesSomeone(text: String, names: Collection<String>): Boolean = Veil.mentions(text, names)

    private val MY_THINGS = Regex(
        "\\bmy\\s+(\\w+\\s+)?(photos?|pics?|pictures?|videos?|gallery|selfies?|screenshots?|messages?|texts?|inbox|sms|emails?|mails?|" +
            "whatsapps?|chats?|files?|documents?|docs?|pdfs?|folders?|downloads|calendar|schedule|agenda|reminders?|tasks?|to-?dos?|notes?|" +
            "lists?|events?|meetings?|appointments?|alarms?|timers?|steps|sleep|heart|health|workouts?|weight|contacts?|phone|battery|" +
            "location|notifications?|expenses|spending|bank|account|balance|salary|plans?|day|week|morning|evening|tonight)\\b",
    )

    /** Questions about the user, or about what they told Luna. */
    private val ABOUT_ME = Regex(
        "\\b(did|have|had|was|am) i\\b|\\bdo i (have|need|owe)\\b|\\bi (told|said|mentioned|asked) you\\b|\\b(do you )?remember\\b|" +
            "\\b(what|when|where|who|which|how)('s| is| are| was| were) my\\b|\\bmy \\w+'s\\b|\\babout me\\b|\\bwho am i\\b",
    )

    /** Where the user is: only the phone knows. */
    private val NEAR_ME = Regex("\\b(near|around|close to) (me|here|my)\\b|\\bnearby\\b|\\bnearest\\b|\\bhere\\b.*\\b(now|today)\\b")

    /** Leans on what came before, so it can't be sent as it is. */
    private val REFERS_BACK = Regex(
        "\\b(it|them|there|him|her|the above|the same|as before|like before)\\b|\\b(this|that|these|those) (one|ones|thing|place|topic|idea|guy|person)\\b|" +
            "^(and|also|what about|how about|then|so)\\b",
    )

    /** More of what Gemini just wrote. */
    private val FOLLOW_UP = Regex(
        "\\b(shorter|longer|simpler|more|another|again|rewrite|rephrase|reword|redo|formal|casual|friendlier|funnier|expand|elaborate|" +
            "continue|go on|summari[sz]e|bullet|points|example|examples|translate|in (hindi|tamil|malayalam|telugu|kannada|english)|" +
            "why|explain|instead|change|tweak|improve|fix|edit|add|remove|cut)\\b|^(and|also|what about|how about|ok(ay)?|then|but)\\b",
    )

    private val WRITING = Regex(
        "^(please |can you |could you |pls )?(write|draft|compose|create|generate|make|prepare)\\b.{0,60}\\b(essays?|e-?mails?|mails?|letters?|stor(y|ies)|" +
            "poems?|posts?|captions?|speech(es)?|articles?|blogs?|bios?|cover letter|resume|cv|summar(y|ies)|reports?|proposals?|scripts?|" +
            "songs?|lyrics|tweets?|threads?|essay|paragraphs?|toasts?|wish(es)?|invitations?|announcements?|outlines?|slogans?|names|jokes?|quiz|questions)\\b",
    )

    /** "Write a message to Priya": the phone's, which sends it. */
    private val TO_SOMEONE = Regex("\\b(message|text|sms|whatsapp|dm)\\b.{0,20}\\b(to|for)\\b")

    private val CODE = Regex(
        "\\b(write|fix|debug|explain|convert|optimi[sz]e|refactor|review)\\b.{0,60}\\b(code|python|kotlin|java|javascript|typescript|sql|regex|" +
            "function|script|program|algorithm|query|bug|html|css|bash|excel formula)\\b",
    )

    private val TRANSLATE = Regex(
        "\\btranslat(e|ion)\\b|\\bhow (do|would) (you|i) say\\b|\\b(say|write)\\b.{0,40}\\bin (hindi|tamil|malayalam|telugu|kannada|marathi|bengali|" +
            "gujarati|punjabi|urdu|french|german|spanish|japanese|chinese|arabic|korean|italian|russian)\\b",
    )

    /** Things that change, which the phone's model can't know. */
    private val LIVE = Regex(
        "\\b(latest|news|headlines|today's|tonight's|this week's|right now|currently|live|score|scores|who won|results? of|stock|share price|" +
            "price of|prices of|how much (is|does|are)|weather|forecast|exchange rate|gold rate|petrol price|traffic|released|launch date|" +
            "trending|election|ipl|world cup)\\b",
    )

    private val PLANNING = Regex(
        "\\b(plan|itinerary|trip to|travel to|visit(ing)? \\w+ for|compare|comparison|vs\\.?|versus|pros and cons|which is better|" +
            "should i (buy|choose|pick|get|go)|recommend|suggest|ideas? for|step[- ]by[- ]step|roadmap|checklist for)\\b",
    )

    private val EXPLAIN = Regex(
        "^(please |can you )?(explain|what is|what are|what's|who is|who was|how does|how do|how to|why does|why do|why is|tell me about|describe)\\b",
    )
}

/** Why an Auto chat's reply came from where it did, stored with it ([com.local.assistant.data.db.MessageEntity.route]). */
enum class RouteReason(val key: String, val label: String, val cloud: Boolean) {
    LOCKED("locked", "On this phone · the phone is locked", cloud = false),
    NOT_SET_UP("not_set_up", "On this phone · Cloud isn't set up", cloud = false),
    OFFLINE("offline", "On this phone · offline", cloud = false),
    ALLOWANCE("allowance", "On this phone · today's Gemini questions are used up", cloud = false),
    SENSITIVE("sensitive", "On this phone · it has an ID or card number", cloud = false),
    KEPT("kept", "On this phone · you kept it here", cloud = false),
    USED_PHOTOS("photos", "On this phone · used your photos", cloud = false),
    USED_MESSAGES("messages", "On this phone · used your messages", cloud = false),
    USED_FILES("files", "On this phone · used your files", cloud = false),
    USED_HEALTH("health", "On this phone · used your health data", cloud = false),
    USED_MEMORY("memory", "On this phone · used what it knows about you", cloud = false),
    ACTED("acted", "On this phone · done on the phone", cloud = false),
    KNEW("knew", "On this phone · Luna knew this", cloud = false),
    CLOUD_FAILED("cloud_failed", "On this phone · Gemini couldn't be reached", cloud = false),
    ASKED("asked", "Asked Gemini", cloud = true),
    WRITING("writing", "Asked Gemini · long writing", cloud = true),
    LIVE("live", "Asked Gemini · needed the web", cloud = true),
    TRANSLATE("translate", "Asked Gemini · translation", cloud = true),
    PLANNING("planning", "Asked Gemini · planning", cloud = true),
    FOLLOW_UP("follow_up", "Asked Gemini · follow-up", cloud = true),
    SAVE_BATTERY("battery", "Asked Gemini · to spare the battery", cloud = true),
    ;

    companion object {
        fun of(key: String?): RouteReason? = entries.firstOrNull { it.key == key }
    }
}
