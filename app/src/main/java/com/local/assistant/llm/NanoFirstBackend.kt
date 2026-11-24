package com.local.assistant.llm

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach

/**
 * Trial (branch aicore-trial): Local chats on Gemini Nano through AICore while the app is in
 * front and the phone has Nano, and on [gemma] otherwise.
 *
 * AICore refuses any request from an app that isn't in the foreground (ErrorCode 30), so a chat
 * opened with the app in the background (the wake-word panel over another app) stays on Gemma.
 * One-shot jobs ([complete]) are background work here (summaries, extraction), so Gemma does them.
 *
 * The prompt is planned for the smaller of the two windows, so a chat can go to either.
 */
class NanoFirstBackend(
    private val nano: AiCoreBackend,
    private val gemma: LlmBackend,
    private val foreground: () -> Boolean,
) : LlmBackend {

    /** Whether the last reply streamed came from Nano. */
    private val _lastWasNano = MutableStateFlow(false)
    val lastWasNano: StateFlow<Boolean> = _lastWasNano

    /** What Nano's last reply measured, for the line under it. */
    private val _lastStats = MutableStateFlow<GenStats?>(null)
    val lastStats: StateFlow<GenStats?> = _lastStats

    private fun nanoReady(): Boolean = nano.report?.available == true

    private fun useNano(): Boolean = foreground() && nanoReady()

    override val capabilities: BackendCapabilities?
        get() {
            val local = gemma.capabilities
            val phone = nano.capabilities?.takeIf { nanoReady() } ?: return local
            if (local == null) return phone.copy(maxContextTokens = minOf(phone.maxContextTokens, PLANNED_WITHOUT_GEMMA))
            return local.copy(
                tools = local.tools && phone.tools,
                maxContextTokens = minOf(local.maxContextTokens, phone.maxContextTokens),
                visionTokensPerImage = maxOf(local.visionTokensPerImage, phone.visionTokensPerImage),
            )
        }

    override suspend fun ensureReady(): Boolean {
        val phone = runCatching { nano.ensureReady() }.onFailure { Log.w(TAG, "AICore check failed", it) }.getOrDefault(false)
        if (phone && foreground()) return true
        return gemma.ensureReady()
    }

    override suspend fun openChat(spec: ChatSpec): ChatSession {
        if (!nanoReady()) runCatching { nano.inspect() }
        val onNano = useNano()
        Log.i(TAG, "Chat on ${if (onNano) "Gemini Nano" else "Gemma"}")
        val session = if (onNano) nano.openChat(spec) else gemma.openChat(spec)
        return Routed(session, onNano)
    }

    override suspend fun complete(system: String, input: String, jsonSchema: String?, maxTokens: Int, sampling: Sampling): String =
        gemma.complete(system, input, jsonSchema, maxTokens, sampling)

    override suspend fun release() = gemma.release()

    override fun isContextOverflow(error: Throwable): Boolean = nano.isContextOverflow(error) || gemma.isContextOverflow(error)

    override fun isUnreadableToolCall(error: Throwable): Boolean = gemma.isUnreadableToolCall(error)

    /** A session that says, as it replies, which model it is on. */
    private inner class Routed(private val inner: ChatSession, private val onNano: Boolean) : ChatSession by inner {

        override fun send(envelope: String, attachment: PromptAttachment?): Flow<GenEvent> = mark(inner.send(envelope, attachment))

        override fun send(envelope: String, attachments: List<PromptAttachment>): Flow<GenEvent> = mark(inner.send(envelope, attachments))

        override fun sendToolResults(results: List<ToolResult>): Flow<GenEvent> = mark(inner.sendToolResults(results))

        private fun mark(events: Flow<GenEvent>): Flow<GenEvent> {
            _lastWasNano.value = onNano
            if (onNano) _lastStats.value = null
            return events.onEach { event -> if (onNano && event is GenEvent.Done) _lastStats.value = event.stats }
        }
    }

    private companion object {
        const val TAG = "NanoFirstBackend"

        /** The window to plan for while Gemma isn't loaded: what it runs with by default. */
        const val PLANNED_WITHOUT_GEMMA = 8_192
    }
}
