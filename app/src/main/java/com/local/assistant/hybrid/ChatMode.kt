package com.local.assistant.hybrid

/**
 * Where a chat's next message goes.
 *
 * - [LOCAL]: the phone's model, and nothing leaves the phone.
 * - [AUTO]: the phone's model, which sees everything, and may ask Gemini for help with a question
 *   it writes itself. What it knows to be private is hidden first ([Veil]), and every question is
 *   kept in the ledger ([CloudLedger]). Where each message went, and why, is [AutoRouter]'s.
 * - [CLOUD]: Gemini, which sees only what was said in Cloud mode ([CloudScope]).
 */
enum class ChatMode(val key: String) {
    LOCAL("local"),
    AUTO("auto"),
    CLOUD("cloud"),
    ;

    /** Whether anything can go to the cloud from a chat in this mode. */
    val reachesCloud: Boolean get() = this != LOCAL

    companion object {
        fun of(cloud: Boolean, auto: Boolean): ChatMode = when {
            cloud -> CLOUD
            auto -> AUTO
            else -> LOCAL
        }

        fun of(key: String?): ChatMode? = entries.firstOrNull { it.key == key }
    }
}
