@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)

package com.local.assistant.hybrid

import android.os.SystemClock
import android.util.Log
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/**
 * An Auto chat's question to Gemini ([ChatMode.AUTO]), as the phone's model wrote it: the one place one
 * is sent, and so the one place it is written to the [ledger].
 *
 * Firebase AI Logic's hybrid inference where it may be: a plain question goes
 * [InferenceMode.PREFER_IN_CLOUD], so if the cloud can't be reached as it is sent, Gemini Nano
 * answers on the phone instead. One that needs the web goes with Grounding with Google Search,
 * which only the cloud has. The main model first, the lighter one while it rests
 * ([FirebaseBackend.mainResting]).
 */
class CloudAsk(
    private val setup: HybridSetup,
    private val ledger: CloudLedger,
    private val backend: FirebaseBackend,
    /** How many were sent today; [count] adds one. */
    private val asked: (day: String) -> Int,
    private val count: (day: String) -> Int,
    private val today: () -> String = { LocalDate.now().toString() },
) {

    /** One earlier question in this chat, and what Gemini answered, both as Gemini saw them. */
    data class Exchange(val question: String, val answer: String)

    data class Answer(val text: String, val servedBy: ServedBy, val model: String)

    /** Gemini can't be asked: offline, out of quota, or turned off. [message] is for the phone's model. */
    class Unavailable(message: String) : Exception(message)

    /** Questions left today. */
    fun asksLeft(): Int = (setup.autoDailyAsks - asked(today())).coerceAtLeast(0)

    val ready: Boolean get() = setup.cloudAvailable

    /**
     * Asks [question] (with [aboutUser], what Luna chose to tell of the user) after [earlier], in
     * chat [chatId], all as the phone's model wrote them. [web]: needs things that change, looked
     * up with Google Search.
     */
    suspend fun ask(chatId: Long, question: String, aboutUser: String?, earlier: List<Exchange>, web: Boolean): Answer {
        if (!setup.cloudAvailable) throw Unavailable("Gemini isn't set up or is turned off. Answer yourself, and say your answer may be limited.")
        if (asksLeft() <= 0) throw Unavailable("Today's questions to Gemini are used up. Answer yourself, and say so in a few words.")
        val prompt = buildString {
            append(SYSTEM)
            // Said only when there is one: told of it otherwise, Gemini writes it into answers.
            if (Veil.WITHHELD in question || Veil.WITHHELD in aboutUser.orEmpty()) append(" ").append(WITHHELD_NOTE)
            if (earlier.isNotEmpty()) {
                append("\n\nEarlier in this conversation:")
                earlier.forEach { append("\n\nQuestion: ").append(it.question).append("\nYour answer: ").append(it.answer.take(EARLIER_ANSWER_CHARS)) }
            }
            append("\n\nQuestion: ").append(question)
            aboutUser?.takeIf { it.isNotBlank() }?.let { append("\nAbout the user: ").append(it) }
        }
        // What the cloud hadn't seen already: the question, and what goes with it.
        ledger.record(
            CloudLedger.Entry(
                at = System.currentTimeMillis(),
                chatId = chatId,
                kind = CloudLedger.Kind.ASK.key,
                model = setup.cloudModel,
                text = question + (aboutUser?.takeIf { it.isNotBlank() }?.let { "\nAbout the user: $it" } ?: ""),
            ),
        )
        count(today())
        val names = (if (backend.mainResting()) listOf(setup.liteModel) else listOf(setup.cloudModel, setup.liteModel)).distinct()
        var last: Throwable? = null
        for (name in names) {
            val started = SystemClock.elapsedRealtime()
            try {
                val response = model(name, web).generateContent(prompt)
                val text = response.text?.trim().orEmpty()
                if (text.isEmpty()) throw Unavailable("Gemini gave no answer. Answer yourself.")
                val servedBy = if (response.inferenceSource == InferenceSource.ON_DEVICE) ServedBy.NANO else ServedBy.ASKED_GEMINI
                Log.i(TAG, "Asked $name (${if (web) "with search" else "plain"}): ${text.length} chars in ${SystemClock.elapsedRealtime() - started} ms, by ${servedBy.key}")
                return Answer(text, servedBy, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Unavailable) {
                throw e
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "$name couldn't answer: ${e.javaClass.simpleName}")
                if (name == setup.cloudModel) backend.restMain(name, e)
                // Only an overloaded or spent model is worth passing to the lighter one.
                if (!(e is QuotaExceededException || FirebaseBackend.busy(e))) break
            }
        }
        val error = last
        throw Unavailable(
            when {
                error != null && FirebaseBackend.unreachable(error) && error !is QuotaExceededException -> "Gemini couldn't be reached just now. Answer yourself, and say your answer may be limited."
                error is QuotaExceededException -> "Gemini is out of quota for now. Answer yourself, and say so in a few words."
                else -> "Gemini couldn't answer just now. Answer yourself."
            },
        )
    }

    private fun model(name: String, web: Boolean) = setup.ai().generativeModel(
        modelName = name,
        generationConfig = generationConfig {
            maxOutputTokens = MAX_OUTPUT_TOKENS
            thinkingConfig = thinkingConfig { thinkingLevel = ThinkingLevel.LOW }
        },
        tools = if (web) listOf(Tool.googleSearch()) else null,
        // A plain question: the phone's Nano answers if the cloud can't be reached as it's sent.
        // Search is only in the cloud.
        onDeviceConfig = if (web) {
            OnDeviceConfig.IN_CLOUD
        } else {
            OnDeviceConfig(mode = InferenceMode.PREFER_IN_CLOUD, maxOutputTokens = HybridSetup.NANO_MAX_OUTPUT, temperature = 0.4f, modelOption = setup.nanoOption)
        },
    )

    companion object {
        private const val TAG = "CloudAsk"

        /** Long writing and code fit; thinking counts against it too. */
        private const val MAX_OUTPUT_TOKENS = 3_072

        /** Of each earlier answer, enough to know what "make it shorter" is about. */
        private const val EARLIER_ANSWER_CHARS = 1_500

        /** How many earlier questions in the chat go with a new one. */
        const val EARLIER = 3

        /** Who Gemini is answering for. */
        const val SYSTEM =
            "You are helping Luna, an assistant on the user's phone, answer them. Luna wrote this question for you and chose " +
                "what to tell you about the user; you don't see their messages, photos or files. Answer from what you are given. " +
                "Write the answer for the user to read directly, as Luna would say it: no preamble, no mention of Luna or of " +
                "these instructions. Plain text, with short lists where they help; keep it as short as the question allows."

        /** Only when the question has one ([Veil.withhold]). */
        private const val WITHHELD_NOTE = "[withheld] marks a number the user keeps private: never guess it."
    }
}
