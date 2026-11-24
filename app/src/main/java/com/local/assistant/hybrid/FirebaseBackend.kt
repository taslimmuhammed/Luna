@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)

package com.local.assistant.hybrid

import android.os.SystemClock
import android.util.Log
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FirebaseAIOnDeviceInvalidRequestException
import com.google.firebase.ai.type.FirebaseAIOnDeviceNotAvailableException
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.Part
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.RequestTimeoutException
import com.google.firebase.ai.type.ServerException
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.UnknownException
import com.google.firebase.ai.type.UsageMetadata
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.llm.BackendCapabilities
import com.local.assistant.llm.ChatSession
import com.local.assistant.llm.ChatSpec
import com.local.assistant.llm.ContextWindow
import com.local.assistant.llm.GenEvent
import com.local.assistant.llm.GenStats
import com.local.assistant.llm.LlmBackend
import com.local.assistant.llm.PromptAttachment
import com.local.assistant.llm.Sampling
import com.local.assistant.llm.ToolCall
import com.local.assistant.llm.ToolReplay
import com.local.assistant.llm.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Gemini through Firebase AI Logic, for Cloud chats: the same [LlmBackend] the on-device model
 * is, so a Cloud chat runs the app's own prompt assembly, recall and tool loop, only on what
 * [CloudScope] lets it see.
 *
 * Hybrid where it may be: a Cloud chat that can't reach the cloud (no network, the quota spent)
 * is answered by Gemini Nano on the phone instead, if the phone has it, and says so under the
 * reply ([ServedBy.NANO]). The other way round never happens: nothing of a Local chat comes here.
 *
 * Every request is written to [ledger] as it is sent.
 */
class FirebaseBackend(
    private val setup: HybridSetup,
    private val ledger: CloudLedger,
) : LlmBackend {

    override val capabilities: BackendCapabilities?
        get() = if (setup.cloudAvailable) CAPABILITIES else null

    private val _lastServedBy = MutableStateFlow<ServedBy?>(null)

    /** Who answered the last request: Gemini in the cloud, or Nano when the cloud couldn't be reached. */
    val lastServedBy: StateFlow<ServedBy?> = _lastServedBy.asStateFlow()

    private val _lastStats = MutableStateFlow<GenStats?>(null)
    val lastStats: StateFlow<GenStats?> = _lastStats.asStateFlow()

    /** Before a turn: nothing answered it yet. A turn the app answered itself leaves it null. */
    fun beginTurn() {
        _lastServedBy.value = null
        _lastStats.value = null
    }

    override suspend fun ensureReady(): Boolean {
        check(setup.configured) { NOT_SET_UP }
        check(setup.cloudAvailable) { "Cloud mode is turned off for now. Switch this chat to Local to carry on." }
        return true
    }

    override suspend fun openChat(spec: ChatSpec): ChatSession {
        ensureReady()
        // Remote Config's model first, then the lighter one for when it's overloaded or missing.
        val names = listOf(setup.cloudModel, setup.liteModel).distinct()
        val history = withContext(Dispatchers.IO) { historyContents(spec.history) }
        return CloudChatSession(this, names.map { it to chatModel(it, spec) }, spec, history)
    }

    private fun chatModel(name: String, spec: ChatSpec): GenerativeModel =
        setup.ai().generativeModel(
            modelName = name,
            // Gemini 3's own sampling: Google advises against lowering its temperature, and the
            // app's settings were tuned for Gemma.
            generationConfig = generationConfig {
                // Thinking tokens count against the limit; a little of it helps with tools.
                maxOutputTokens = spec.maxOutputTokens + THINKING_TOKENS
                thinkingConfig = thinkingConfig { thinkingLevel = ThinkingLevel.LOW }
            },
            tools = spec.toolDeclarations.takeIf { it.isNotEmpty() }?.let { declared ->
                listOf(Tool.functionDeclarations(declared.map(SchemaJson::declaration)))
            },
            systemInstruction = spec.systemPrefix.takeIf { it.isNotBlank() }?.let { content { text(it) } },
            onDeviceConfig = OnDeviceConfig.IN_CLOUD,
        )

    /**
     * One-shot work on what a Cloud chat sent (a document attached to a Cloud message): Gemini
     * Nano on the phone when it has it and the input is short enough for it, else Gemini in the
     * cloud. Firebase's hybrid inference ([InferenceMode.PREFER_ON_DEVICE]), with the cloud as the
     * fallback it is, since all of this was the cloud's to see anyway.
     */
    override suspend fun complete(
        system: String,
        input: String,
        jsonSchema: String?,
        maxTokens: Int,
        sampling: Sampling,
    ): String {
        ensureReady()
        val short = (system.length + input.length) <= NANO_MAX_CHARS && jsonSchema == null
        val config = generationConfig {
            maxOutputTokens = maxTokens + THINKING_TOKENS
            if (jsonSchema != null) {
                responseMimeType = "application/json"
                runCatching { SchemaJson.schema(jsonSchema) }.getOrNull()?.let { responseSchema = it }
            }
        }
        fun model(mode: InferenceMode) = setup.ai().generativeModel(
            modelName = setup.liteModel,
            generationConfig = config,
            // Nano takes no system instruction of its own: it goes in the prompt, for both.
            onDeviceConfig = OnDeviceConfig(mode = mode, maxOutputTokens = maxTokens, temperature = sampling.temperature.toFloat(), modelOption = setup.nanoOption),
        )
        val prompt = "$system\n\n$input"
        val started = SystemClock.elapsedRealtime()
        val response = try {
            ledger.record(CloudLedger.Entry(System.currentTimeMillis(), null, CloudLedger.Kind.TASK.key, setup.liteModel, prompt))
            model(if (short) InferenceMode.PREFER_ON_DEVICE else InferenceMode.ONLY_IN_CLOUD).generateContent(prompt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseAIOnDeviceInvalidRequestException) {
            // Too much for Nano after all: the cloud reads it.
            Log.i(TAG, "Nano refused the task; asking the cloud")
            model(InferenceMode.ONLY_IN_CLOUD).generateContent(prompt)
        } catch (e: FirebaseAIOnDeviceNotAvailableException) {
            model(InferenceMode.ONLY_IN_CLOUD).generateContent(prompt)
        }
        served(response.inferenceSource)
        recordStats(response.usageMetadata, SystemClock.elapsedRealtime() - started, null)
        return response.text.orEmpty()
    }

    override suspend fun release() = Unit

    /** Gemini's window is far larger than the budget planned for it; a refusal for length is a request too large to send. */
    override fun isContextOverflow(error: Throwable): Boolean =
        error is ServerException && error.message?.contains("exceeds the maximum number of tokens", ignoreCase = true) == true

    internal fun served(source: InferenceSource?) {
        _lastServedBy.value = if (source == InferenceSource.ON_DEVICE) ServedBy.NANO else ServedBy.CLOUD
    }

    internal fun recordStats(usage: UsageMetadata?, totalMs: Long, firstTokenMs: Long?) {
        val decode = usage?.candidatesTokenCount ?: return
        val decodeMs = (totalMs - (firstTokenMs ?: 0L)).coerceAtLeast(1L)
        _lastStats.value = GenStats(
            decodeTokens = decode,
            tokensPerSecond = decode * 1000.0 / decodeMs,
            timeToFirstTokenMs = firstTokenMs ?: totalMs,
            prefillTokens = usage.promptTokenCount,
        )
    }

    internal suspend fun record(chatId: Long?, kind: CloudLedger.Kind, text: String, attachments: List<PromptAttachment> = emptyList()) {
        val files = attachments.groupingBy { it.kind }.eachCount().entries.joinToString(", ") { (kind, n) ->
            "$n ${kind.name.lowercase()}${if (n > 1) "s" else ""}"
        }.ifEmpty { null }
        ledger.record(CloudLedger.Entry(System.currentTimeMillis(), chatId, kind.key, setup.cloudModel, text, files))
    }

    internal val nanoSetup: HybridSetup get() = setup

    /**
     * The stored turns as Gemini takes them. A run of tool rows (one turn's calls) becomes one
     * model turn making the calls and one with their results, as they went live; the calls are
     * marked as replayed (their thought signatures weren't kept: see [REPLAYED_SIGNATURE]).
     */
    private fun historyContents(history: List<MessageEntity>): List<Content> {
        val contents = mutableListOf<Content>()
        var i = 0
        while (i < history.size) {
            val message = history[i]
            if (message.role != Role.TOOL) {
                i++
                when (message.role) {
                    Role.USER -> contents += userContent(
                        ContextWindow.historyText(message.attachmentKind, message.attachmentPath, message.text),
                        listOfNotNull(
                            message.attachmentPath?.let { path ->
                                ContextWindow.sentAttachment(message.attachmentKind, message.text)?.let { PromptAttachment(path, it) }
                            },
                        ),
                    )
                    Role.ASSISTANT -> if (message.text.isNotBlank()) contents += Content("model", listOf(TextPart(message.text)))
                    Role.TOOL -> Unit
                }
                continue
            }
            val calls = mutableListOf<Part>()
            val results = mutableListOf<Part>()
            while (i < history.size && history[i].role == Role.TOOL) {
                val row = history[i++].text
                val (name, arguments) = ToolReplay.call(row) ?: continue
                calls += FunctionCallPart.createWithThinking(name, arguments.mapValues { (_, v) -> SchemaJson.element(v) }, null, false, REPLAYED_SIGNATURE)
                results += FunctionResponsePart(name, SchemaJson.element(ToolReplay.result(row)) as? JsonObject ?: JsonObject(emptyMap()))
            }
            if (calls.isNotEmpty()) {
                contents += Content("model", calls)
                contents += Content("function", results)
            }
        }
        return normalized(contents)
    }

    companion object {
        private const val TAG = "FirebaseBackend"

        const val NOT_SET_UP = "Cloud mode isn't set up in this build of the app. Everything still works on the phone."

        /** Room for Gemini's thinking on top of the reply itself. */
        private const val THINKING_TOKENS = 1024

        /** About 2,500 tokens: what Nano is asked to read in one go at most. */
        internal const val NANO_MAX_CHARS = 10_000

        /**
         * Gemini 3 checks the thought signature of each function call it is shown again. Calls
         * replayed from storage have none; this value is the one Google documents for calls
         * that didn't come from the live conversation.
         */
        internal const val REPLAYED_SIGNATURE = "skip_thought_signature_validator"

        private val CAPABILITIES = BackendCapabilities(
            tools = true,
            constrainedJson = true,
            thinking = true,
            speculativeDecoding = false,
            // Gemini reads far more; the budget is the 16K one, which keeps each request small.
            maxContextTokens = 16_384,
            // An image at Gemini 3's default resolution.
            visionTokensPerImage = 1_120,
        )

        /** A user turn: media first, then text, as the on-device model has it. */
        internal fun userContent(text: String, attachments: List<PromptAttachment>): Content {
            val parts = mutableListOf<Part>()
            attachments.forEach { attachment ->
                val file = File(attachment.path).takeIf { it.isFile } ?: return@forEach
                val mime = when (attachment.kind) {
                    AttachmentKind.IMAGE -> when (file.extension.lowercase()) {
                        "png" -> "image/png"
                        "webp" -> "image/webp"
                        else -> "image/jpeg"
                    }
                    AttachmentKind.AUDIO -> "audio/wav"
                    // Read by the app, never sent as itself (ContextWindow.sentAttachment).
                    AttachmentKind.DOCUMENT -> return@forEach
                }
                parts += InlineDataPart(file.readBytes(), mime)
            }
            if (text.isNotBlank() || parts.isEmpty()) parts += TextPart(text.ifBlank { " " })
            return Content("user", parts)
        }

        /**
         * Turns as Gemini wants them: two of the same role in a row become one, and the history
         * starts with the user's.
         */
        internal fun normalized(contents: List<Content>): List<Content> {
            val merged = mutableListOf<Content>()
            contents.forEach { next ->
                val last = merged.lastOrNull()
                if (last != null && last.role == next.role) {
                    merged[merged.lastIndex] = Content(last.role, last.parts + next.parts)
                } else {
                    merged += next
                }
            }
            while (merged.isNotEmpty() && merged.first().role != "user") merged.removeAt(0)
            return merged
        }

        /** The cloud couldn't be reached, or won't answer for now: worth asking the phone instead. */
        internal fun unreachable(error: Throwable): Boolean {
            var cause: Throwable? = error
            while (cause != null) {
                if (cause is java.io.IOException || cause is QuotaExceededException || cause is RequestTimeoutException) return true
                cause = cause.cause
            }
            return error is UnknownException || busy(error)
        }

        /**
         * Gemini is overloaded for now ("experiencing high demand", 503 or 500): worth trying
         * again in a moment, then another model.
         */
        internal fun busy(error: Throwable): Boolean =
            error is RequestTimeoutException ||
                (error is ServerException && BUSY.containsMatchIn(error.message.orEmpty()))

        private val BUSY = Regex("high demand|overloaded|try again later|unavailable|\\b50[03]\\b|internal error", RegexOption.IGNORE_CASE)
    }
}

/**
 * A Cloud chat's conversation. Gemini keeps nothing between requests, so the turns are held here
 * and each request sends them all; the ledger records only what is new in each.
 */
private class CloudChatSession(
    private val backend: FirebaseBackend,
    private val models: List<Pair<String, GenerativeModel>>,
    private val spec: ChatSpec,
    initial: List<Content>,
) : ChatSession {

    private val history = initial.toMutableList()

    /** The function calls of the last reply, waiting for their results. */
    private var pending: List<FunctionCallPart> = emptyList()

    private var tokens: Int? = null
    private var opened = false

    @Volatile
    private var closed = false

    override val isAlive: Boolean get() = !closed

    override fun send(envelope: String, attachment: PromptAttachment?): Flow<GenEvent> = send(envelope, listOfNotNull(attachment))

    override fun send(envelope: String, attachments: List<PromptAttachment>): Flow<GenEvent> = flow {
        // Results that went back as a message of their own: the calls still need an answer first.
        val owed = answerPending(emptyList())
        val user = withContext(Dispatchers.IO) { FirebaseBackend.userContent(envelope, attachments) }
        backend.record(spec.chatId, CloudLedger.Kind.MESSAGE, envelope, attachments)
        emitAll(stream(listOfNotNull(owed, user), fallbackPrompt = envelope))
    }.flowOn(Dispatchers.IO)

    override fun sendToolResults(results: List<ToolResult>): Flow<GenEvent> = flow {
        val answered = answerPending(results) ?: Content("function", results.map { FunctionResponsePart(it.name, SchemaJson.result(it.json)) })
        backend.record(spec.chatId, CloudLedger.Kind.TOOL_RESULTS, ToolReplay.asMessage(results))
        emitAll(stream(listOf(answered), fallbackPrompt = null))
    }.flowOn(Dispatchers.IO)

    /** The waiting calls answered: by [results] where they have one, as not run where they don't. */
    private fun answerPending(results: List<ToolResult>): Content? {
        if (pending.isEmpty()) return null
        val left = results.toMutableList()
        val parts = pending.map { call ->
            val result = left.firstOrNull { it.name == call.name }?.also { left.remove(it) }
            FunctionResponsePart(
                call.name,
                result?.let { SchemaJson.result(it.json) } ?: JsonObject(mapOf("ok" to JsonPrimitive(true), "note" to JsonPrimitive("The results follow in the next message."))),
                call.id,
            )
        } + left.map { FunctionResponsePart(it.name, SchemaJson.result(it.json)) }
        pending = emptyList()
        return Content("function", parts)
    }

    private fun stream(added: List<Content>, fallbackPrompt: String?): Flow<GenEvent> = flow {
        if (!opened) {
            opened = true
            backend.record(
                spec.chatId,
                CloudLedger.Kind.OPENED,
                "Instructions and memory (Cloud only):\n${spec.systemPrefix}\n\n" +
                    "Earlier in this chat: ${spec.history.count { it.role != Role.TOOL }} Cloud messages, as sent before.",
            )
        }
        val request = FirebaseBackend.normalized(history + added)
        val text = StringBuilder()
        var signature: String? = null
        val calls = mutableListOf<FunctionCallPart>()
        val other = mutableListOf<Part>()
        var usage: UsageMetadata? = null
        var source: InferenceSource? = null
        val started = SystemClock.elapsedRealtime()
        var firstAt: Long? = null
        var failure: Throwable? = null
        // Overloaded: the same model again after a moment, then the lighter one. Only while nothing
        // has been shown or asked for, since that can't be taken back and asked again.
        tries@ for ((index, entry) in models.withIndex()) {
            val (name, model) = entry
            for (attempt in 0 until if (index == 0) MAIN_TRIES else 1) {
                if (attempt > 0) delay(RETRY_DELAY_MS * attempt)
                failure = null
                signature = null
                other.clear()
                streamFrom(model, request, text, calls, other, started, onSignature = { signature = it }, onUsage = { usage = it ?: usage }, onSource = { source = it ?: source }, onFirst = { if (firstAt == null) firstAt = it })
                    .also { failure = it }
                val error = failure ?: run {
                    if (index > 0) Log.i(TAG, "Answered by $name, the fallback")
                    break@tries
                }
                if (text.isNotEmpty() || calls.isNotEmpty()) break@tries
                Log.i(TAG, "$name failed (try ${attempt + 1}): ${error.javaClass.simpleName}: ${error.message}")
                if (!FirebaseBackend.busy(error)) {
                    // The same model would fail the same way; another may not (one the project lacks).
                    if (error is ServerException && !backend.isContextOverflow(error)) continue@tries else break@tries
                }
            }
        }
        val error = failure
        if (error != null && text.isEmpty() && calls.isEmpty() && fallbackPrompt != null && FirebaseBackend.unreachable(error)) {
            Log.i(TAG, "Cloud unreachable (${error.javaClass.simpleName}); asking Gemini Nano on the phone")
            val answered = answerOnPhone(fallbackPrompt)
            if (answered != null) {
                history += added
                history += Content("model", listOf(TextPart(answered)))
                emit(GenEvent.TextDelta(answered))
                emit(GenEvent.Done(null))
                return@flow
            }
        }
        if (error != null) {
            Log.w(TAG, "Cloud request failed", error)
            emit(GenEvent.Error(CloudError.of(error)))
            return@flow
        }
        val parts = buildList {
            if (text.isNotEmpty()) add(if (signature != null) TextPart.createWithThinking(text.toString(), false, signature) else TextPart(text.toString()))
            addAll(other)
            addAll(calls)
        }
        history += added
        if (parts.isNotEmpty()) history += Content("model", parts)
        usage?.let { tokens = it.totalTokenCount }
        backend.served(source)
        backend.recordStats(usage, SystemClock.elapsedRealtime() - started, firstAt)
        if (calls.isNotEmpty()) {
            pending = calls
            emit(GenEvent.ToolCalls(calls.map { ToolCall(it.name, SchemaJson.arguments(it.args)) }))
        }
        emit(GenEvent.Done(backend.lastStats.value))
    }

    /** One request to [model], its words emitted as they come. The failure, or null. */
    private suspend fun FlowCollector<GenEvent>.streamFrom(
        model: GenerativeModel,
        request: List<Content>,
        text: StringBuilder,
        calls: MutableList<FunctionCallPart>,
        other: MutableList<Part>,
        started: Long,
        onSignature: (String) -> Unit,
        onUsage: (UsageMetadata?) -> Unit,
        onSource: (InferenceSource?) -> Unit,
        onFirst: (Long) -> Unit,
    ): Throwable? {
        try {
            model.generateContentStream(request).collect { chunk: GenerateContentResponse ->
                onUsage(chunk.usageMetadata)
                onSource(chunk.inferenceSource)
                chunk.candidates.firstOrNull()?.content?.parts.orEmpty().forEach { part ->
                    when (part) {
                        is TextPart -> {
                            part.thoughtSignature?.let(onSignature)
                            if (!part.isThought && part.text.isNotEmpty()) {
                                onFirst(SystemClock.elapsedRealtime() - started)
                                text.append(part.text)
                                emit(GenEvent.TextDelta(part.text))
                            }
                        }
                        is FunctionCallPart -> calls += part
                        else -> if (!part.isThought) other += part
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e
        }
        return null
    }

    /**
     * No cloud: Gemini Nano on the phone answers instead, from the instructions and the last few
     * turns in one prompt (it takes no history of its own), with no tools. Null when the phone has
     * no Nano, or it fails too.
     */
    private suspend fun answerOnPhone(envelope: String): String? {
        val setup = backend.nanoSetup
        if (setup.nano.value != HybridSetup.Nano.AVAILABLE && setup.refreshNano() != HybridSetup.Nano.AVAILABLE) return null
        val recent = history.takeLast(NANO_TURNS).joinToString("\n") { turn ->
            val words = turn.parts.filterIsInstance<TextPart>().joinToString(" ") { it.text }.take(NANO_TURN_CHARS)
            "${if (turn.role == "model") "Assistant" else "User"}: $words"
        }
        val prompt = buildString {
            append(spec.systemPrefix.take(NANO_SYSTEM_CHARS))
            append("\n\nYou are answering on the phone, offline, without tools: if something needs a tool, say it can be done once the phone is back online.\n\n")
            if (recent.isNotBlank()) append(recent).append('\n')
            append("User: ").append(envelope.take(NANO_TURN_CHARS * 2)).append("\nAssistant:")
        }
        return try {
            val response = setup.nanoModel().generateContent(prompt)
            backend.served(InferenceSource.ON_DEVICE)
            response.text?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Nano couldn't answer either", e)
            null
        }
    }

    override fun tokenCount(): Int? = tokens

    /** The request in flight stops with its collector, which [com.local.assistant.chat.ChatSender] cancels. */
    override fun cancel() = Unit

    override fun close() {
        closed = true
    }

    private companion object {
        const val TAG = "CloudChatSession"
        const val NANO_TURNS = 6

        /** Tries of the main model when Gemini is overloaded, before the lighter one. */
        const val MAIN_TRIES = 2

        /** The wait before a retry, times the try's number. */
        const val RETRY_DELAY_MS = 1_500L
        const val NANO_TURN_CHARS = 1_200
        const val NANO_SYSTEM_CHARS = 3_000
    }
}

/** A cloud failure in words the chat can show. */
class CloudError(message: String, cause: Throwable) : Exception(message, cause) {
    companion object {
        fun of(error: Throwable): CloudError = CloudError(
            when {
                FirebaseBackend.busy(error) -> "Gemini is overloaded right now, and the lighter model too. Try again in a minute, or switch this chat to Local."
                FirebaseBackend.unreachable(error) && error is QuotaExceededException -> "The cloud model is busy right now. Try again in a minute, or switch this chat to Local."
                FirebaseBackend.unreachable(error) -> "Couldn't reach the cloud. Check the connection, or switch this chat to Local: it works offline."
                else -> "The cloud model couldn't answer (${error.javaClass.simpleName}). Try again, or switch this chat to Local."
            },
            error,
        )
    }
}
