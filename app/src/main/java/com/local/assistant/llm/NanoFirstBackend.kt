package com.local.assistant.llm

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach

/**
 * Local chats on Gemini Nano through AICore while the app is in front and the phone has Nano, and
 * on [gemma] otherwise. The assistant overlay is a screen of the app's own, so it counts as in
 * front and is answered by Nano too.
 *
 * AICore refuses any request from an app that isn't in the foreground (ErrorCode 30; a foreground
 * service doesn't count, as Google's docs say and the phone showed on 26 Nov 2026), so a chat sent
 * then goes to Gemma. One-shot jobs ([complete]) follow the same rule: Nano while the app is in
 * front, Gemma otherwise. [nanoOnly] is Nano or nothing.
 *
 * The prompt is planned for the smaller of the two windows, so a chat can go to either.
 *
 * Nano can't hear: a voice message reaches it as words, written down by ML Kit's speech
 * recognition or the phone's recogniser ([com.local.assistant.voice.Transcriber]).
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

    /** Until when Nano is left alone after AICore refused it (quota, background), in elapsed-realtime ms. */
    @Volatile private var restingUntil = 0L

    private fun resting(): Boolean = android.os.SystemClock.elapsedRealtime() < restingUntil

    /**
     * Nano will take the next message once the app is in front: the phone has it, and AICore
     * isn't resting it. A screen being opened counts before the app does (the overlay's chat is
     * made ready in its first moment), so this leaves the app out. The lock screen doesn't
     * matter: with the app's own screen shown over it, as the overlay is, Nano answered in 172 ms
     * (`LockProbeActivity`, 27 Nov 2026). AICore refuses only an app with no screen in front.
     */
    private fun nanoWillAnswer(): Boolean = nanoReady() && !resting()

    private fun useNano(): Boolean = foreground() && nanoWillAnswer()

    /**
     * Whether Nano answers chats here now, asking AICore the first time: the screens then show Nano
     * as ready and leave Gemma unloaded. False while Nano rests.
     */
    suspend fun answersOnPhone(): Boolean {
        if (!nanoReady()) runCatching { nano.inspect() }.onFailure { Log.w(TAG, "AICore check failed", it) }
        return nanoWillAnswer()
    }

    /**
     * The chat's model as a screen shows it: ready while Nano answers ([onPhone], from
     * [answersOnPhone]), whatever Gemma is doing; Gemma's [gemmaState] otherwise.
     */
    fun shownState(gemmaState: LlmService.State, onPhone: Boolean): LlmService.State =
        if (onPhone) LlmService.State.Ready(backend = "Gemini Nano", contextTokens = nano.capabilities?.maxContextTokens ?: 0) else gemmaState

    /**
     * AICore refusing to run the request rather than the model failing: over the app's quota
     * (ErrorCode 9, seen after ~100 requests in two minutes), over its battery quota, or the app
     * counted as in the background. Gemma can answer instead.
     */
    private fun refused(error: Throwable): Boolean {
        if (error is AiCoreBackend.NanoTimeout) return true
        val code = (error as? com.google.mlkit.genai.common.GenAiException)?.errorCode ?: return false
        return code in REFUSALS
    }

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
        // Only when Nano will answer: while it rests after AICore refused it, or over the lock
        // screen, the chat is on Gemma, which has to be loaded first. Returning true for any app in
        // front opened that chat on an unloaded Gemma, and the turn failed with no reply (the
        // user's chat, 24 Nov 2026). The app being in front isn't asked: the overlay makes its chat
        // ready before it counts as in front, and loaded Gemma for nothing.
        if (phone && nanoWillAnswer()) return true
        return gemma.ensureReady()
    }

    override suspend fun openChat(spec: ChatSpec): ChatSession {
        if (!nanoReady()) runCatching { nano.inspect() }
        val onNano = nanoWillAnswer()
        Log.i(TAG, "Chat on ${if (onNano) "Gemini Nano" else "Gemma"}")
        val session = if (onNano) nano.openChat(spec) else gemma.openChat(spec)
        return Routed(session, onNano, spec)
    }

    /**
     * A one-shot job (a chat's summary, a document read, the brief's sorting) on Nano while the
     * app is in front, else on Gemma, as for chats. Refused by AICore, it goes to Gemma as well.
     */
    override suspend fun complete(system: String, input: String, jsonSchema: String?, maxTokens: Int, sampling: Sampling): String {
        if (useNano()) {
            try {
                return nano.complete(system, input, jsonSchema, maxTokens, sampling)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!refused(e)) throw e
                rest(e)
            }
        }
        return gemma.complete(system, input, jsonSchema, maxTokens, sampling)
    }

    private fun rest(error: Throwable) {
        restingUntil = android.os.SystemClock.elapsedRealtime() + REST_MS
        Log.w(TAG, "AICore refused (${error.message}); Gemma answers for the next ${REST_MS / 1000} s")
    }

    /** Nano isn't answering now: the app isn't in front, or AICore refused. */
    class NanoUnavailable(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

    /**
     * Nano alone, for work done while the app is in front that isn't worth loading Gemma for
     * (catching up on session summaries and what the chat didn't save): ready only while Nano
     * answers, and a request made once it doesn't fails with [NanoUnavailable] instead of going to
     * Gemma, so the work is left for later as it was.
     */
    val nanoOnly: LlmBackend = object : LlmBackend {
        override val capabilities: BackendCapabilities? get() = nano.capabilities?.takeIf { nanoReady() }

        override suspend fun ensureReady(): Boolean = answersOnPhone() && foreground()

        override suspend fun openChat(spec: ChatSpec): ChatSession {
            if (!useNano()) throw NanoUnavailable("Gemini Nano isn't answering now")
            return nano.openChat(spec)
        }

        override suspend fun complete(system: String, input: String, jsonSchema: String?, maxTokens: Int, sampling: Sampling): String {
            if (!useNano()) throw NanoUnavailable("Gemini Nano isn't answering now")
            try {
                return nano.complete(system, input, jsonSchema, maxTokens, sampling)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!refused(e)) throw e
                rest(e)
                throw NanoUnavailable("AICore refused", e)
            }
        }

        override suspend fun release() = Unit

        override fun isContextOverflow(error: Throwable): Boolean = nano.isContextOverflow(error)
    }

    override suspend fun release() = gemma.release()

    override fun isContextOverflow(error: Throwable): Boolean = nano.isContextOverflow(error) || gemma.isContextOverflow(error)

    /**
     * A refused Nano request counts as unreadable, the one failure the turn runner retries in a
     * conversation rebuilt from what is stored ([com.local.assistant.memory.tools.ToolLoop]). Nano
     * rests meanwhile, so the rebuilt one is on Gemma.
     */
    override fun isUnreadableToolCall(error: Throwable): Boolean = gemma.isUnreadableToolCall(error) || refused(error)

    /**
     * A session that says, as it replies, which model it is on. One opened on Gemma moves to Nano
     * at its first message if Nano can answer by then: a chat is made ready as it is shown, a
     * moment before the app counts as in front, and stayed on Gemma for good (the user's chat,
     * 24 Nov 2026: Gemma answered from "continue the Website" on, and saved code to a note). One
     * opened on Nano moves to Gemma if the app isn't in front when its first message goes, which
     * AICore would refuse.
     */
    private inner class Routed(
        @Volatile private var inner: ChatSession,
        @Volatile private var onNano: Boolean,
        private val spec: ChatSpec,
    ) : ChatSession {

        @Volatile private var used = false

        override val isAlive: Boolean get() = inner.isAlive

        override fun send(envelope: String, attachment: PromptAttachment?): Flow<GenEvent> = send(envelope, listOfNotNull(attachment))

        override fun send(envelope: String, attachments: List<PromptAttachment>): Flow<GenEvent> = flow {
            if (!used) {
                used = true
                val nanoNow = useNano()
                if (nanoNow != onNano) {
                    Log.i(TAG, "Chat moved to ${if (nanoNow) "Gemini Nano" else "Gemma"} before its first message")
                    runCatching { inner.close() }
                    if (!nanoNow) gemma.ensureReady()
                    inner = if (nanoNow) nano.openChat(spec) else gemma.openChat(spec)
                    onNano = nanoNow
                }
            }
            emitAll(mark(inner.send(envelope, attachments)))
        }

        override fun sendToolResults(results: List<ToolResult>): Flow<GenEvent> = mark(inner.sendToolResults(results))

        override fun tokenCount(): Int? = inner.tokenCount()

        override fun cancel() = inner.cancel()

        override fun close() = inner.close()

        private fun mark(events: Flow<GenEvent>): Flow<GenEvent> {
            _lastWasNano.value = onNano
            if (onNano) _lastStats.value = null
            return events.onEach { event ->
                if (onNano && event is GenEvent.Done) _lastStats.value = event.stats
                if (onNano && event is GenEvent.Error && refused(event.cause)) rest(event.cause)
            }
        }
    }

    private companion object {
        const val TAG = "NanoFirstBackend"

        /** The window to plan for while Gemma isn't loaded: what it runs with by default. */
        const val PLANNED_WITHOUT_GEMMA = 8_192

        const val REST_MS = 60_000L

        private val REFUSALS = setOf(
            com.google.mlkit.genai.common.GenAiException.ErrorCode.BUSY,
            com.google.mlkit.genai.common.GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
            com.google.mlkit.genai.common.GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED,
        )
    }
}
