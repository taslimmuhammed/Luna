package com.local.assistant.hybrid

/**
 * Which model wrote a reply, stored with it (`MessageEntity.servedBy`) and shown under it, so the
 * user can always see where an answer came from. [onDevice] is what the badge leads with.
 */
enum class ServedBy(val key: String, val label: String, val onDevice: Boolean) {
    /** Gemma 4 on the phone, through LiteRT-LM: replies from before Gemini Nano (27 Nov 2026). */
    GEMMA("gemma", "On this phone · Gemma 4", onDevice = true),

    /** Gemini Nano on the phone, through Firebase AI Logic's on-device inference (AICore). */
    NANO("nano", "On this phone · Gemini Nano", onDevice = true),

    /** Gemini in the cloud, through Firebase AI Logic. */
    CLOUD("cloud", "Cloud · Gemini", onDevice = false),

    /**
     * Gemini in the cloud, asked by the phone's model in an Auto chat ([ChatMode.AUTO]) with a
     * question it wrote, private details hidden ([Veil]).
     */
    ASKED_GEMINI("asked_gemini", "Gemini · asked by Luna", onDevice = false),

    /** Answered by the app itself, no model: an alarm, a sum. */
    APP("app", "On this phone", onDevice = true),
    ;

    companion object {
        fun of(key: String?): ServedBy? = entries.firstOrNull { it.key == key }
    }
}
