package com.local.assistant.hybrid

import android.util.Log
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.memory.tools.ActionResult
import com.local.assistant.memory.tools.AskView
import com.local.assistant.memory.tools.ChatToolLog
import com.local.assistant.memory.tools.MemoryChip
import com.local.assistant.memory.tools.ToolCatalog
import com.local.assistant.memory.tools.ToolContext

/**
 * ask_gemini, in an Auto chat ([ChatMode.AUTO]): the question the phone's model wrote, with what
 * it chose to tell Gemini about the user (or, for a message [AutoRouter] sent straight on, the
 * user's own words), asked with the chat's earlier questions. Gemini answers from exactly that,
 * and the answer is the reply: the phone's model doesn't copy it out again.
 *
 * Never an ID or card number ([Veil.withhold]), nor a question that copies a private message,
 * file or health reading out word for word without the user's say-so ([AskConfirm]).
 */
class AskTools(
    private val cloud: CloudAsk,
    private val chats: ChatRepository,
    private val confirm: AskConfirm,
) {

    suspend fun ask(question: String, aboutUser: String?, web: Boolean, context: ToolContext): ActionResult {
        val route = context.auto
        if (route == null || route.lane == AutoRouter.Lane.LOCAL_ONLY) {
            val why = route?.reason?.label?.substringAfter("· ")?.let { " ($it)" }.orEmpty()
            return failed("Nothing can go to Gemini from this message$why. Answer it yourself.", question, route)
        }
        val tools = chats.toolRowsBetween(context.chatId, 0L, Long.MAX_VALUE)
            .mapNotNull { row -> ChatToolLog.parse(row.text)?.let { row to it } }
        // The chat's earlier questions and Gemini's answers, as they went.
        val earlier = tools.mapNotNull { (_, record) -> record.chip?.ask?.takeIf { record.tool == ToolCatalog.ASK_GEMINI && it.outcome == AskView.OUTCOME_ANSWERED } }
        val sent = Veil.withhold(question.trim())
        val about = aboutUser?.trim()?.takeIf { it.isNotEmpty() }?.let(Veil::withhold)
        val note = Veil.withheldNote(sent.withheld + (about?.withheld ?: 0))

        // What this turn read of the user's own: a question copying it out waits on the user.
        val read = tools.filter { (row, record) -> row.id > context.userMessageId && record.tool in PRIVATE }.map { (_, record) -> record.result } +
            listOf(context.documentNotes).filter { it.isNotBlank() }
        if (Veil.pastes(question, read) || (aboutUser != null && Veil.pastes(aboutUser, read))) {
            Log.i(TAG, "The question copies what the turn read: asking the user first")
            if (!confirm.ask(context.chatId, sent.text + (about?.let { "\n\nAbout you: ${it.text}" } ?: ""), note)) {
                return ActionResult(
                    result = linkedMapOf("ok" to false, "error" to "The user kept this on the phone: Gemini wasn't asked. Answer yourself from what you have."),
                    chip = chip(question, sent.text, about?.text, note, route, web, AskView.OUTCOME_KEPT),
                )
            }
        }

        val answer = try {
            cloud.ask(context.chatId, sent.text, about?.text, earlier.takeLast(CloudAsk.EARLIER).map { CloudAsk.Exchange(it.sent, it.answerSent.orEmpty()) }, web)
        } catch (e: CloudAsk.Unavailable) {
            return failed(e.message.orEmpty(), question, route, sent.text, about?.text, note, web)
        }
        val view = chip(question, sent.text, about?.text, note, route, web, AskView.OUTCOME_ANSWERED).let { chip ->
            chip.copy(ask = chip.ask!!.copy(model = answer.model, servedBy = answer.servedBy.key, answerSent = answer.text))
        }
        return ActionResult(
            result = linkedMapOf("ok" to true, "answered" to true, "by" to answer.servedBy.label),
            chip = view,
            reply = answer.text,
        )
    }

    /** [text] with its ID and card numbers withheld: a web lookup's query from an Auto chat. */
    fun withhold(text: String): String = Veil.withhold(text).text

    private fun failed(
        message: String,
        question: String,
        route: AutoRouter.Route?,
        sent: String? = null,
        about: String? = null,
        note: String? = null,
        web: Boolean = false,
    ) = ActionResult(
        result = linkedMapOf("ok" to false, "error" to message),
        chip = sent?.let { chip(question, it, about, note, route, web, AskView.OUTCOME_FAILED) },
    )

    private fun chip(
        question: String,
        sent: String,
        about: String?,
        note: String?,
        route: AutoRouter.Route?,
        web: Boolean,
        outcome: String,
    ): MemoryChip {
        val reason = route?.reason?.takeIf { it.cloud } ?: RouteReason.ASKED
        return MemoryChip(
            kind = MemoryChip.Kind.ASK,
            label = when (outcome) {
                AskView.OUTCOME_KEPT -> "Kept on this phone"
                AskView.OUTCOME_FAILED -> "Gemini couldn't answer"
                else -> "Asked Gemini"
            },
            detail = note.orEmpty(),
            ask = AskView(
                asked = question,
                sent = sent,
                aboutUser = about,
                hidden = note,
                reason = reason.key,
                web = web,
                outcome = outcome,
                at = System.currentTimeMillis(),
            ),
        )
    }

    companion object {
        private const val TAG = "AskTools"

        /** Tools whose results are the user's own: a question mustn't copy them out unasked. */
        val PRIVATE = setOf(ToolCatalog.CHECK_INBOX, ToolCatalog.READ_FILE, ToolCatalog.HEALTH, ToolCatalog.FIND_PHOTOS, ToolCatalog.SEARCH_MEMORY)
    }
}
