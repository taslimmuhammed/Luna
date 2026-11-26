package com.local.assistant.hybrid

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A question to Gemini held until the user says it may go: one that copies a private message,
 * file or health reading out word for word ([Veil.pastes]). The chat screen shows [pending] with
 * what would be sent, and [answer]s it; nobody answering in time keeps it on the phone.
 */
class AskConfirm {

    data class Pending(val chatId: Long, val sent: String, val hidden: String?, internal val answer: CompletableDeferred<Boolean>)

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    /** Whether [sent] may go to Gemini: the user's answer, false if none comes within [WAIT_MS]. */
    suspend fun ask(chatId: Long, sent: String, hidden: String?): Boolean {
        val asked = Pending(chatId, sent, hidden, CompletableDeferred())
        _pending.value = asked
        return try {
            withTimeoutOrNull(WAIT_MS) { asked.answer.await() } ?: false
        } finally {
            _pending.compareAndSet(asked, null)
        }
    }

    fun answer(send: Boolean) {
        _pending.value?.answer?.complete(send)
    }

    private companion object {
        const val WAIT_MS = 60_000L
    }
}
