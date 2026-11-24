package com.local.assistant.llm

import android.util.Log
import com.local.assistant.data.db.AttachmentKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Trial (branch aicore-trial): Local chats on Gemini Nano through AICore while the app is in
 * front and the phone has Nano, and on [gemma] otherwise.
 *
 * AICore refuses any request from an app that isn't in the foreground (ErrorCode 30), so a chat
 * opened with the app in the background (the wake-word panel over another app) stays on Gemma.
 * One-shot jobs ([complete]) are background work here (summaries, extraction), so Gemma does them.
 *
 * The prompt is planned for the smaller of the two windows, so a chat can go to either.
 *
 * Nano can't hear, so a voice message reaches it as the phone's recogniser wrote it down. Gemma
 * hears the recording and puts the words right first when it is loaded ([hear]); [warmEars]
 * loads it while the user is still speaking.
 */
class NanoFirstBackend(
    private val nano: AiCoreBackend,
    private val gemma: LlmBackend,
    private val foreground: () -> Boolean,
    private val scope: CoroutineScope,
) : LlmBackend {

    init {
        nano.ears = ::hear
    }

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

    private fun useNano(): Boolean = foreground() && nanoReady() && !resting()

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
        // Only when Nano will answer: while it rests after AICore refused it, the chat is rebuilt on
        // Gemma, which has to be loaded first. Returning true for any app in front opened that
        // chat on an unloaded Gemma, and the turn failed with no reply (the user's chat, 24 Nov 2026).
        if (phone && useNano()) return true
        return gemma.ensureReady()
    }

    /** A voice message is being recorded: Gemma loads meanwhile, to hear it ([hear]). */
    fun warmEars() {
        if (!useNano() || gemma.capabilities != null) return
        scope.launch { runCatching { gemma.ensureReady() }.onFailure { Log.w(TAG, "Gemma didn't load to hear a voice message", it) } }
    }

    /**
     * The words of the recording at [path], as Gemma hears them with the phone's [words] beside
     * it; null when Gemma isn't loaded or takes too long, and the phone's words go as they are.
     * On the user's 14 voice messages (VoiceBench, 24 Nov 2026) it put right 2 of the phone's 3
     * misheard names ("send hi to mount" → "Umma", "co QR code" → "Shiyas QR code") and changed no
     * right one, in ~1 s; AICore's own recogniser (basic, the only one on the phone) fixed 1 and
     * got 2 others wrong.
     */
    private suspend fun hear(path: String, words: String): String? {
        if (gemma.capabilities == null) return null
        val started = android.os.SystemClock.elapsedRealtime()
        val heard = withTimeoutOrNull(EARS_TIMEOUT_MS) {
            val session = gemma.openChat(
                ChatSpec(systemPrefix = EARS_SYSTEM, history = emptyList(), sampling = Sampling.BACKGROUND, maxOutputTokens = EARS_MAX_TOKENS),
            )
            try {
                val out = StringBuilder()
                session.send(
                    "The phone's speech recogniser wrote this recording as: \"$words\". Listen to the recording and write what was said, " +
                        "word for word, fixing any name or number it misheard. Write only the words.",
                    PromptAttachment(path, AttachmentKind.AUDIO),
                ).collect { if (it is GenEvent.TextDelta) out.append(it.text) }
                out.toString()
            } finally {
                session.close()
            }
        }?.trim()?.trim('"')
        Log.i(TAG, "Gemma heard the voice message in ${android.os.SystemClock.elapsedRealtime() - started} ms; ${if (heard == null) "kept the phone's words" else if (heard.equals(words, ignoreCase = true)) "same words" else "words changed"}")
        // Something other than the words (an answer, a refusal) is no transcript.
        return heard?.takeIf { it.isNotBlank() && it.length <= words.length * 2 + 20 }
    }

    override suspend fun openChat(spec: ChatSpec): ChatSession {
        if (!nanoReady()) runCatching { nano.inspect() }
        val onNano = useNano()
        Log.i(TAG, "Chat on ${if (onNano) "Gemini Nano" else "Gemma"}")
        val session = if (onNano) nano.openChat(spec) else gemma.openChat(spec)
        return Routed(session, onNano, spec)
    }

    override suspend fun complete(system: String, input: String, jsonSchema: String?, maxTokens: Int, sampling: Sampling): String =
        gemma.complete(system, input, jsonSchema, maxTokens, sampling)

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
     * 24 Nov 2026: Gemma answered from "continue the Website" on, and saved code to a note).
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
                if (!onNano && useNano()) {
                    Log.i(TAG, "Chat moved to Gemini Nano before its first message")
                    runCatching { inner.close() }
                    inner = nano.openChat(spec)
                    onNano = true
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
                if (onNano && event is GenEvent.Error && refused(event.cause)) {
                    restingUntil = android.os.SystemClock.elapsedRealtime() + REST_MS
                    Log.w(TAG, "AICore refused (${event.cause.message}); Gemma answers for the next ${REST_MS / 1000} s")
                }
            }
        }
    }

    private companion object {
        const val TAG = "NanoFirstBackend"

        /** The window to plan for while Gemma isn't loaded: what it runs with by default. */
        const val PLANNED_WITHOUT_GEMMA = 8_192

        const val REST_MS = 60_000L

        /** How long a voice message waits for Gemma to hear it; ~1 s when loaded. */
        const val EARS_TIMEOUT_MS = 5_000L
        const val EARS_MAX_TOKENS = 96
        const val EARS_SYSTEM = "You write down voice messages exactly as spoken. The speaker is in India and may use Malayalam names and words."

        private val REFUSALS = setOf(
            com.google.mlkit.genai.common.GenAiException.ErrorCode.BUSY,
            com.google.mlkit.genai.common.GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
            com.google.mlkit.genai.common.GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED,
        )
    }
}
