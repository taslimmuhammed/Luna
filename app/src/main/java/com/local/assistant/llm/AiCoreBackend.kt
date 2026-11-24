package com.local.assistant.llm

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.FunctionArgValue
import com.google.mlkit.genai.common.FunctionCall
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.StreamingCallback
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Content
import com.google.mlkit.genai.prompt.Feature
import com.google.mlkit.genai.prompt.FunctionCallPart
import com.google.mlkit.genai.prompt.FunctionResponsePart
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.Part
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.schema.tools.FunctionDeclaration
import com.google.mlkit.genai.schema.tools.FunctionSchema
import com.google.mlkit.genai.schema.tools.FunctionType
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * [LlmBackend] on Gemini Nano through Android's AICore (ML Kit's Prompt API), the model the
 * phone itself keeps: nothing for the app to download or load.
 *
 * AICore keeps no conversation for the app, so a [ChatSession] here holds the turns itself and
 * sends them all, with the instructions and tools, on every request. Tool calling is native
 * where the phone's Nano has it ([Feature.FUNCTION_CALLING]); the app still runs the calls.
 *
 * [preview]: Nano 4, on phones enrolled in AICore's developer preview, rather than the stable one.
 */
class AiCoreBackend(
    private val preview: () -> Boolean = { true },
    private val fast: () -> Boolean = { false },
) : LlmBackend {

    /** What the phone's AICore offers, once [ensureReady] has asked. */
    data class Report(
        val status: Int,
        val baseModel: String?,
        val tokenLimit: Int?,
        val features: List<Int>,
    ) {
        val available: Boolean get() = status == FeatureStatus.AVAILABLE
        fun has(feature: Int) = feature in features

        override fun toString(): String = "status=${statusName(status)} model=$baseModel tokenLimit=$tokenLimit features=${features.map(::featureName)}"
    }

    @Volatile private var model: GenerativeModel? = null
    @Volatile private var modelKey: Pair<Boolean, Boolean>? = null
    @Volatile var report: Report? = null
        private set

    @Volatile private var _capabilities: BackendCapabilities? = null
    override val capabilities: BackendCapabilities? get() = _capabilities

    private val lock = Mutex()

    /** The model for the current choice of preview and speed, made again when either changes. */
    fun client(): GenerativeModel {
        val key = preview() to fast()
        model?.takeIf { modelKey == key }?.let { return it }
        model?.close()
        val config = GenerationConfig.Builder().apply {
            modelConfig = ModelConfig.Builder().apply {
                releaseStage = if (key.first) ModelReleaseStage.PREVIEW else ModelReleaseStage.STABLE
                preference = if (key.second) ModelPreference.FAST else ModelPreference.FULL
            }.build()
        }.build()
        return Generation.getClient(config).also {
            model = it
            modelKey = key
            report = null
        }
    }

    /** Asks AICore what it has, without downloading or running anything. */
    suspend fun inspect(): Report = lock.withLock {
        report?.let { return it }
        val client = client()
        val status = client.checkStatus()
        val available = status == FeatureStatus.AVAILABLE
        Report(
            status = status,
            baseModel = if (available) runCatching { client.getBaseModelName() }.getOrNull() else null,
            tokenLimit = if (available) runCatching { client.getTokenLimit() }.getOrNull() else null,
            features = if (available) runCatching { client.getAvailableFeatures() }.getOrDefault(emptyList()) else emptyList(),
        ).also { found ->
            report = found
            Log.i(TAG, "AICore: $found")
            if (found.available) {
                _capabilities = BackendCapabilities(
                    tools = found.has(Feature.FUNCTION_CALLING),
                    // Structured output here takes annotated Kotlin types, not a JSON Schema string.
                    constrainedJson = false,
                    thinking = found.has(Feature.THINKING_MODE),
                    speculativeDecoding = false,
                    maxContextTokens = found.tokenLimit ?: DEFAULT_TOKEN_LIMIT,
                    visionTokensPerImage = IMAGE_TOKENS,
                )
            }
        }
    }

    override suspend fun ensureReady(): Boolean = inspect().available

    /** Downloads Nano when AICore says it can, reporting each status as it goes. */
    suspend fun download(onStatus: (String) -> Unit) {
        client().download().collect { onStatus(it.toString()) }
        lock.withLock { report = null }
    }

    override suspend fun openChat(spec: ChatSpec): ChatSession {
        val found = inspect()
        if (!found.available) error("Gemini Nano isn't available on this phone (${statusName(found.status)})")
        val tools = if (found.has(Feature.FUNCTION_CALLING)) spec.toolDeclarations.mapNotNull(::declaration) else emptyList()
        return AiCoreChatSession(
            backend = this,
            system = spec.systemPrefix,
            systemSupported = found.has(Feature.SYSTEM_PROMPT),
            tools = tools,
            turns = historyContents(spec.history).toMutableList(),
            maxOutputTokens = spec.maxOutputTokens,
            sampling = spec.sampling,
        )
    }

    override suspend fun complete(system: String, input: String, jsonSchema: String?, maxTokens: Int, sampling: Sampling): String {
        val found = inspect()
        if (!found.available) error("Gemini Nano isn't available on this phone (${statusName(found.status)})")
        // No schema-string constraint on this API: the schema goes in the instructions instead.
        val instructions = if (jsonSchema == null) system else "$system\n\nAnswer with JSON only, no prose and no code fence, matching this JSON Schema:\n$jsonSchema"
        val request = request(
            system = instructions,
            systemSupported = found.has(Feature.SYSTEM_PROMPT),
            contents = listOf(Content.Builder().role(Content.Role.USER).text(input).build()),
            tools = emptyList(),
            maxOutputTokens = maxTokens,
            sampling = sampling,
        )
        val text = client().generateContent(request).candidates.firstOrNull()?.text.orEmpty()
        return if (jsonSchema == null) text else text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    internal fun request(
        system: String,
        systemSupported: Boolean,
        contents: List<Content>,
        tools: List<FunctionDeclaration>,
        maxOutputTokens: Int,
        sampling: Sampling,
    ): GenerateContentRequest {
        // Without a system prompt on this phone, the instructions open the first user turn.
        val sent = if (systemSupported || system.isBlank() || contents.isEmpty()) contents else {
            val first = contents.first()
            listOf(Content.Builder().role(first.role).apply {
                addPart(TextPart(system + "\n\n"))
                first.parts.forEach { addPart(it) }
            }.build()) + contents.drop(1)
        }
        return GenerateContentRequest.Builder(sent).apply {
            if (systemSupported && system.isNotBlank()) systemInstruction = SystemInstruction(system)
            if (tools.isNotEmpty()) setTools(tools)
            this.maxOutputTokens = maxOutputTokens.coerceIn(1, MAX_OUTPUT_TOKENS)
            // Gemma's 1.0 left stray words after tool results on Nano 4; 0.3 didn't (probe, 24 Nov 2026).
            temperature = minOf(sampling.temperature, MAX_TEMPERATURE).toFloat()
            topK = sampling.topK
            seed = sampling.seed
        }.build()
    }

    override suspend fun release() {
        lock.withLock {
            model?.close()
            model = null
            modelKey = null
            report = null
        }
    }

    override fun isContextOverflow(error: Throwable): Boolean =
        (error as? GenAiException)?.errorCode == GenAiException.ErrorCode.REQUEST_TOO_LARGE

    /** Stored turns as AICore contents; a run of tool rows becomes the model's calls and their results. */
    private fun historyContents(history: List<MessageEntity>): List<Content> {
        val out = mutableListOf<Content>()
        var i = 0
        while (i < history.size) {
            val message = history[i]
            if (message.role != Role.TOOL) {
                i++
                when (message.role) {
                    Role.USER -> out += Content.Builder().role(Content.Role.USER).text(ContextWindow.historyText(message.attachmentKind, message.attachmentPath, message.text)).build()
                    Role.ASSISTANT -> if (message.text.isNotBlank()) out += Content.Builder().role(Content.Role.MODEL).text(message.text).build()
                    Role.TOOL -> Unit
                }
                continue
            }
            val calls = mutableListOf<Part>()
            val results = mutableListOf<Part>()
            while (i < history.size && history[i].role == Role.TOOL) {
                val row = history[i++].text
                val (name, arguments) = ToolReplay.call(row) ?: continue
                val id = "h${out.size}_${calls.size}"
                calls += FunctionCallPart(FunctionCall(name, arguments.mapValues { argValue(it.value) }, id))
                ToolReplay.replayed(row)?.let { results += FunctionResponsePart(id, name, responseMap(it.json)) }
            }
            if (calls.isNotEmpty()) {
                out += Content.Builder().role(Content.Role.MODEL).apply { calls.forEach { addPart(it) } }.build()
                if (results.isNotEmpty()) out += Content.Builder().role(Content.Role.USER).apply { results.forEach { addPart(it) } }.build()
            }
        }
        return out
    }

    companion object {
        private const val TAG = "AiCoreBackend"
        const val DEFAULT_TOKEN_LIMIT = 4_000

        /** What AICore allows in one reply. */
        const val MAX_OUTPUT_TOKENS = 1_024
        const val IMAGE_TOKENS = 256
        const val MAX_TEMPERATURE = 0.3

        private val gson = Gson()

        private val CALL_BLOCK = Regex("<ctrl42>.*?(<ctrl43>|$)", RegexOption.DOT_MATCHES_ALL)
        private val CONTROL = Regex("<ctrl\\d+>")
        private val PARTIAL_CONTROL = Regex("<(c(t(r(l\\d*)?)?)?)?$")

        /**
         * [raw] without the model's tool calls, which it streams as text between control tokens,
         * or any other control token. While streaming ([final] false), a token cut in half at the
         * end is held back until the rest arrives.
         */
        fun visibleText(raw: String, final: Boolean = false): String {
            var text = CALL_BLOCK.replace(raw, "")
            text = CONTROL.replace(text, "")
            if (!final) text = PARTIAL_CONTROL.replace(text, "")
            return text
        }

        fun statusName(status: Int) = when (status) {
            FeatureStatus.AVAILABLE -> "AVAILABLE"
            FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
            FeatureStatus.DOWNLOADING -> "DOWNLOADING"
            FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
            else -> "UNKNOWN($status)"
        }

        fun featureName(feature: Int) = when (feature) {
            Feature.SYSTEM_PROMPT -> "system_prompt"
            Feature.THINKING_MODE -> "thinking"
            Feature.PREFIX_CACHING -> "prefix_caching"
            Feature.STRUCTURED_OUTPUT -> "structured_output"
            Feature.FUNCTION_CALLING -> "function_calling"
            else -> "feature_$feature"
        }

        /** One of the app's OpenAPI declarations ([com.local.assistant.memory.tools.ToolCatalog]) for AICore. */
        fun declaration(json: String): FunctionDeclaration? = runCatching {
            val root = JsonParser.parseString(json).asJsonObject
            FunctionDeclaration(
                root.get("name").asString,
                root.get("description")?.asString.orEmpty(),
                root.getAsJsonObject("parameters")?.let(::schema) ?: FunctionSchema(FunctionType.OBJECT, null, emptyMap(), emptyList(), null),
            )
        }.onFailure { Log.w(TAG, "Couldn't convert a tool declaration", it) }.getOrNull()

        private fun schema(node: JsonObject): FunctionSchema {
            val description = node.get("description")?.takeIf { it.isJsonPrimitive }?.asString
            val types = node.get("type")?.let { type ->
                if (type.isJsonArray) type.asJsonArray.map { it.asString } else listOf(type.asString)
            }.orEmpty()
            val nullable = "null" in types
            val type = when (types.firstOrNull { it != "null" } ?: if (node.has("properties")) "object" else "string") {
                "integer" -> FunctionType.INTEGER
                "number" -> FunctionType.NUMBER
                "boolean" -> FunctionType.BOOLEAN
                "array" -> FunctionType.ARRAY
                "object" -> FunctionType.OBJECT
                else -> FunctionType.STRING
            }
            val properties = node.getAsJsonObject("properties")?.entrySet()?.associate { (key, value) -> key to schema(value.asJsonObject) }
            val required = node.getAsJsonArray("required")?.map { it.asString }
            val enum = node.getAsJsonArray("enum")?.map { it.asString }
            val items = node.getAsJsonObject("items")?.let(::schema)
            return FunctionSchema(type, description, properties, required, enum, null, nullable.takeIf { it }, items)
        }

        /** A call's arguments as the app's tools take them: Long for whole numbers, as LiteRT-LM gives them. */
        fun argument(value: FunctionArgValue?): Any? = when (value) {
            null -> null
            is FunctionArgValue.StringValue -> value.value
            is FunctionArgValue.LongValue -> value.value
            is FunctionArgValue.IntValue -> value.value.toLong()
            is FunctionArgValue.DoubleValue -> value.value.let { if (it % 1.0 == 0.0 && kotlin.math.abs(it) < 1e15) it.toLong() else it }
            is FunctionArgValue.BoolValue -> value.value
            is FunctionArgValue.ListValue -> value.values.map(::argument)
            is FunctionArgValue.MapValue -> value.map.mapValues { argument(it.value) }
            else -> value.toString()
        }

        fun argValue(value: Any?): FunctionArgValue = when (value) {
            is String -> FunctionArgValue.StringValue(value)
            is Boolean -> FunctionArgValue.BoolValue(value)
            is Int -> FunctionArgValue.LongValue(value.toLong())
            is Long -> FunctionArgValue.LongValue(value)
            is Number -> FunctionArgValue.DoubleValue(value.toDouble())
            is List<*> -> FunctionArgValue.ListValue(value.map(::argValue))
            is Map<*, *> -> FunctionArgValue.MapValue(value.entries.associate { (k, v) -> k.toString() to argValue(v) })
            null -> FunctionArgValue.StringValue("")
            else -> FunctionArgValue.StringValue(value.toString())
        }

        /** A tool's JSON result as the map AICore sends back; anything not an object goes under "result". */
        @Suppress("UNCHECKED_CAST")
        fun responseMap(json: String): Map<String, Any?> = runCatching {
            val parsed = gson.fromJson(json, Any::class.java)
            if (parsed is Map<*, *>) parsed as Map<String, Any?> else mapOf("result" to parsed)
        }.getOrDefault(mapOf("result" to json))
    }
}

private class AiCoreChatSession(
    private val backend: AiCoreBackend,
    private val system: String,
    private val systemSupported: Boolean,
    private val tools: List<FunctionDeclaration>,
    private val turns: MutableList<Content>,
    private val maxOutputTokens: Int,
    private val sampling: Sampling,
) : ChatSession {

    @Volatile private var closed = false
    @Volatile private var job: Job? = null
    @Volatile private var lastTokens: Int? = null

    /** The calls the model made last, by name, so their results go back with the same ids. */
    private var pendingCalls: List<FunctionCall> = emptyList()

    override val isAlive: Boolean get() = !closed

    override fun send(envelope: String, attachment: PromptAttachment?): Flow<GenEvent> = send(envelope, listOfNotNull(attachment))

    override fun send(envelope: String, attachments: List<PromptAttachment>): Flow<GenEvent> {
        val content = Content.Builder().role(Content.Role.USER).apply {
            // One image per request is all Nano takes.
            attachments.firstOrNull { it.kind == AttachmentKind.IMAGE && File(it.path).isFile }?.let { image ->
                BitmapFactory.decodeFile(image.path)?.let { addPart(ImagePart(it)) }
            }
            addPart(TextPart(envelope))
        }.build()
        return generate(content)
    }

    override fun sendToolResults(results: List<ToolResult>): Flow<GenEvent> {
        val unused = pendingCalls.toMutableList()
        val parts = results.map { result ->
            val call = unused.firstOrNull { it.name == result.name }?.also { unused.remove(it) }
            FunctionResponsePart(call?.id ?: result.name, result.name, AiCoreBackend.responseMap(result.json))
        }
        val content = Content.Builder().role(Content.Role.USER).apply { parts.forEach { addPart(it) } }.build()
        return generate(content)
    }

    private fun generate(content: Content): Flow<GenEvent> = channelFlow {
        turns += content
        val request = backend.request(system, systemSupported, turns.toList(), tools, maxOutputTokens, sampling)
        val started = SystemClock.elapsedRealtime()
        var firstAt = 0L
        var chars = 0
        var failure: Throwable? = null
        var calls: List<FunctionCall> = emptyList()
        var text = ""
        // Everything streamed so far, and how much of its visible part the chat has been sent.
        val streamed = StringBuilder()
        var shown = 0
        val running = launch {
            try {
                val response = backend.client().generateContent(request, object : StreamingCallback {
                    override fun onNewText(text: String) {
                        if (text.isEmpty()) return
                        streamed.append(text)
                        // A tool call streams as text too (<ctrl42>call:…<ctrl43>): never shown.
                        val visible = AiCoreBackend.visibleText(streamed.toString())
                        if (visible.length <= shown) return
                        if (firstAt == 0L) firstAt = SystemClock.elapsedRealtime()
                        chars += visible.length - shown
                        trySend(GenEvent.TextDelta(visible.substring(shown)))
                        shown = visible.length
                    }
                })
                val candidate: Candidate? = response.candidates.firstOrNull()
                text = AiCoreBackend.visibleText(candidate?.text.orEmpty(), final = true).trim()
                calls = candidate?.functionCalls.orEmpty()
                if (candidate?.finishReason == Candidate.FinishReason.MAX_TOKENS) Log.i(TAG, "Reply cut at the output limit")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        job = running
        running.join()
        val done = SystemClock.elapsedRealtime()
        if (failure != null) {
            turns.removeAt(turns.lastIndex)
            send(GenEvent.Error(failure!!))
            return@channelFlow
        }
        // Replies that arrive whole (no streamed text) still reach the chat.
        if (chars == 0 && text.isNotEmpty()) {
            firstAt = done
            chars = text.length
            send(GenEvent.TextDelta(text))
        }
        turns += Content.Builder().role(Content.Role.MODEL).apply {
            if (text.isNotEmpty()) addPart(TextPart(text))
            calls.forEach { addPart(FunctionCallPart(it)) }
        }.build()
        pendingCalls = calls
        if (calls.isNotEmpty()) send(GenEvent.ToolCalls(calls.map { call -> ToolCall(call.name, call.arguments.mapValues { AiCoreBackend.argument(it.value) }) }))
        // AICore reports no token counts per reply; text is counted at about 4 characters a token.
        val decodeTokens = (chars + 3) / 4 + calls.size * CALL_TOKENS
        val decodeMs = (done - (if (firstAt > 0) firstAt else done)).coerceAtLeast(1)
        lastTokens = null
        send(
            GenEvent.Done(
                GenStats(
                    decodeTokens = decodeTokens,
                    tokensPerSecond = if (firstAt > 0 && decodeMs > 50) decodeTokens * 1000.0 / decodeMs else 0.0,
                    timeToFirstTokenMs = (if (firstAt > 0) firstAt else done) - started,
                    prefillTokens = 0,
                ),
            ),
        )
    }.flowOn(Dispatchers.IO)

    override fun tokenCount(): Int? = lastTokens

    override fun cancel() {
        job?.cancel()
    }

    override fun close() {
        closed = true
        job?.cancel()
    }

    private companion object {
        const val TAG = "AiCoreChatSession"

        /** About what a tool call costs in output, for the stats. */
        const val CALL_TOKENS = 20
    }
}
