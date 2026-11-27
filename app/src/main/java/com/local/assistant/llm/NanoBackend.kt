package com.local.assistant.llm

import android.os.SystemClock
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Local chats, on Gemini Nano through AICore. The phone's own model is the only one: a phone
 * without Nano has no local chats ([EngineState.NoModel]), and Cloud mode answers there.
 *
 * AICore refuses any request from an app that isn't in front (ErrorCode 30; a foreground service
 * doesn't count, as Google's docs say and the phone showed on 26 Nov 2026). The lock screen is
 * fine with one of the app's own screens over it, as the overlay is: Nano answered in 172 ms
 * (`LockProbeActivity`, 27 Nov 2026). So a one-shot job ([complete]) is asked only while the app
 * is in front, and fails with [NanoUnavailable] otherwise; work that can wait is left for the
 * next time the app comes to the front (`AppContainer.catchUpOnPhone`).
 *
 * Nano can't hear: a voice message reaches it as words, written down by ML Kit's speech
 * recognition or the phone's recogniser ([com.local.assistant.voice.Transcriber]).
 */
class NanoBackend(
    private val nano: AiCoreBackend,
    private val scope: CoroutineScope,
    private val foreground: () -> Boolean,
) : LlmBackend {

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)

    /** Nano as the screens show it: ready, being fetched by AICore, or missing from this phone. */
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** What Nano's last reply measured, for the line under it. */
    private val _lastStats = MutableStateFlow<GenStats?>(null)
    val lastStats: StateFlow<GenStats?> = _lastStats

    private var fetching: Job? = null

    private fun nanoReady(): Boolean = nano.report?.available == true

    /** Whether Nano answers chats on this phone, asking AICore the first time. */
    suspend fun answersOnPhone(): Boolean {
        if (!nanoReady()) check(fresh = false)
        return nanoReady()
    }

    /** Asks AICore again: after a failure, or to see whether Nano has finished downloading. */
    suspend fun recheck(): Boolean {
        check(fresh = true)
        return nanoReady()
    }

    private suspend fun check(fresh: Boolean) {
        val found = runCatching { if (fresh) nano.refresh() else nano.inspect() }
            .onFailure { Log.w(TAG, "AICore check failed", it) }
        _state.value = found.fold(
            onSuccess = { report ->
                when (report.status) {
                    FeatureStatus.AVAILABLE -> EngineState.Ready(backend = NAME, contextTokens = window())
                    FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> fetch()
                    else -> EngineState.NoModel
                }
            },
            onFailure = { EngineState.Failed("Couldn't reach Gemini Nano (${it.message ?: it.javaClass.simpleName}).") },
        )
    }

    /** AICore has Nano to download: fetches it once, and asks again when it's done. */
    private fun fetch(): EngineState {
        (_state.value as? EngineState.Loading)?.let { if (fetching?.isActive == true) return it }
        val loading = EngineState.Loading(startedAt = SystemClock.elapsedRealtime(), expectedMs = null)
        fetching = scope.launch {
            runCatching { nano.download { Log.i(TAG, "Nano download: $it") } }
                .onFailure { Log.w(TAG, "Nano download failed", it) }
            check(fresh = true)
        }
        return loading
    }

    private fun window(): Int = minOf(nano.capabilities?.maxContextTokens ?: PLANNED_WINDOW, PLANNED_WINDOW)

    override val capabilities: BackendCapabilities?
        get() = nano.capabilities?.takeIf { nanoReady() }?.let { it.copy(maxContextTokens = window()) }

    override suspend fun ensureReady(): Boolean = answersOnPhone()

    override suspend fun openChat(spec: ChatSpec): ChatSession {
        if (!answersOnPhone()) throw NanoUnavailable(NO_NANO)
        Log.i(TAG, "Chat on $NAME")
        return Measured(nano.openChat(spec))
    }

    /** A one-shot job (a chat's summary, a document read, the brief's sorting), while the app is in front. */
    override suspend fun complete(system: String, input: String, jsonSchema: String?, maxTokens: Int, sampling: Sampling): String {
        if (!foreground()) throw NanoUnavailable(NOT_IN_FRONT)
        if (!answersOnPhone()) throw NanoUnavailable(NO_NANO)
        return nano.complete(system, input, jsonSchema, maxTokens, sampling)
    }

    /** Nano isn't answering now: the app isn't in front, or the phone has no Nano. */
    class NanoUnavailable(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

    /**
     * Nano for work that can wait until the app is in front (session summaries, what the chat
     * didn't save): ready only while the app is in front, so the work is left for later instead of
     * failing.
     */
    val nanoOnly: LlmBackend = object : LlmBackend {
        override val capabilities: BackendCapabilities? get() = this@NanoBackend.capabilities

        override suspend fun ensureReady(): Boolean = foreground() && answersOnPhone()

        override suspend fun openChat(spec: ChatSpec): ChatSession {
            if (!foreground()) throw NanoUnavailable(NOT_IN_FRONT)
            return this@NanoBackend.openChat(spec)
        }

        override suspend fun complete(system: String, input: String, jsonSchema: String?, maxTokens: Int, sampling: Sampling): String =
            this@NanoBackend.complete(system, input, jsonSchema, maxTokens, sampling)

        override suspend fun release() = Unit

        override fun isContextOverflow(error: Throwable): Boolean = nano.isContextOverflow(error)
    }

    override suspend fun release() = nano.release()

    override fun isContextOverflow(error: Throwable): Boolean = nano.isContextOverflow(error)

    /** A chat session that keeps what its replies measured, for the line under them. */
    private inner class Measured(private val inner: ChatSession) : ChatSession by inner {

        override fun send(envelope: String, attachment: PromptAttachment?): Flow<GenEvent> = send(envelope, listOfNotNull(attachment))

        override fun send(envelope: String, attachments: List<PromptAttachment>): Flow<GenEvent> = measured(inner.send(envelope, attachments))

        override fun sendToolResults(results: List<ToolResult>): Flow<GenEvent> = measured(inner.sendToolResults(results))

        private fun measured(events: Flow<GenEvent>): Flow<GenEvent> {
            _lastStats.value = null
            return events.onEach { if (it is GenEvent.Done) _lastStats.value = it.stats }
        }
    }

    private companion object {
        const val TAG = "NanoBackend"
        const val NAME = "Gemini Nano"

        /**
         * The window prompts are planned for. Nano takes 16K, but a prompt that long is slow to
         * read on the phone; every eval ran at this size.
         */
        const val PLANNED_WINDOW = 8_192

        const val NO_NANO = "This phone doesn't have Gemini Nano, so local chats can't run here. Cloud mode works."
        const val NOT_IN_FRONT = "Gemini Nano answers only while Luna is on screen."
    }
}
