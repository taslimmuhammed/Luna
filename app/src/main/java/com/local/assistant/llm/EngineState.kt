package com.local.assistant.llm

/** Where the phone's chat model, Gemini Nano, stands, as the screens show it ([NanoBackend.state]). */
sealed interface EngineState {
    /** The phone has no Gemini Nano: local chats can't run here, Cloud mode can. */
    data object NoModel : EngineState

    /** AICore hasn't been asked yet. */
    data object Idle : EngineState

    /**
     * AICore is fetching Nano, since [startedAt] (elapsedRealtime); [expectedMs] is how long it
     * should take, when known.
     */
    data class Loading(val startedAt: Long, val expectedMs: Long?) : EngineState

    data class Ready(val backend: String, val contextTokens: Int) : EngineState

    data class Failed(val message: String) : EngineState
}

/**
 * How much of the context window the active chat is using, as the model counts it, so the meter
 * tells whether a chat is really running out of room.
 */
data class ContextUsage(
    val used: Int,
    val max: Int,
    /** The chat whose conversation this is; the overlay's chat can be live behind the chat on screen. */
    val chatId: Long? = null,
) {
    val fraction: Float get() = if (max > 0) (used.toFloat() / max).coerceIn(0f, 1f) else 0f
    val isNearlyFull: Boolean get() = fraction >= NEARLY_FULL_FRACTION

    private companion object {
        const val NEARLY_FULL_FRACTION = 0.85f
    }
}
