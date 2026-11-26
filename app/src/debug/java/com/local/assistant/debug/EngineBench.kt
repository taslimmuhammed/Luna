package com.local.assistant.debug

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import com.local.assistant.AppContainer
import com.local.assistant.llm.Sampling
import com.local.assistant.memory.prompt.Instructions
import com.local.assistant.memory.tools.ToolCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Debug builds only: how fast a model file runs on one backend, with the app's own instructions
 * and tools, outside the app's engine (which is unloaded first). Each message opens a new
 * conversation, as a new chat does: "open" is reading the instructions and tools, "first word"
 * is from sending the message.
 *
 *   adb shell "am broadcast -a com.local.assistant.debug.MODEL_PROBE -p com.local.assistant.dev --es mode engine [--es model /path/x.litertlm] [--es backend gpu|npu|cpu] [--ez mtp true] [--ei ctx 4096] [--ei rounds 2]"
 *
 * The NPU needs the Qualcomm libraries in the APK, extracted (a debug build made with -Pnpu).
 */
class EngineBench(private val context: Context, private val container: AppContainer, private val log: (String) -> Unit) {

    /** [tools] false: the instructions alone, so the reply is text and its speed is steady writing. */
    private data class Prompt(val id: String, val text: String, val tools: Boolean = true)

    private val prompts = listOf(
        Prompt("tool", "Remind me to call mom tomorrow at 6pm"),
        Prompt("short", "In two sentences, why is the sky blue?"),
        Prompt("long", "In about 250 words, explain how a refrigerator keeps food cold.", tools = false),
    )

    @OptIn(ExperimentalApi::class)
    suspend fun run(modelPath: String, backendName: String, mtp: Boolean, contextTokens: Int, rounds: Int) = withContext(Dispatchers.IO) {
        val file = File(modelPath)
        log("# Engine bench, ${java.util.Date()}")
        log("- model: ${file.name} (${file.length() / 1_000_000} MB), backend: $backendName, MTP: $mtp, context: $contextTokens, rounds: $rounds")
        val libs = File(context.applicationInfo.nativeLibraryDir)
        if (backendName == "npu") log("- native libraries: ${libs.list()?.sorted()?.joinToString().orEmpty()}")
        container.llmService.unload()

        ExperimentalFlags.enableSpeculativeDecoding = mtp
        ExperimentalFlags.enableBenchmark = true
        val backend = when (backendName) {
            "npu" -> Backend.NPU(nativeLibraryDir = libs.absolutePath)
            "cpu" -> Backend.CPU()
            else -> Backend.GPU()
        }
        val loading = SystemClock.elapsedRealtime()
        val engine = try {
            Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    maxNumTokens = contextTokens,
                    cacheDir = context.cacheDir.absolutePath,
                ),
            ).apply { initialize() }
        } catch (e: Throwable) {
            log("- LOAD FAILED after ${SystemClock.elapsedRealtime() - loading} ms: ${causes(e)}")
            return@withContext
        }
        log("- loaded in ${SystemClock.elapsedRealtime() - loading} ms")

        val system = Instructions.render(container.settings.systemPrompt, withTools = true)
        val tools = ToolCatalog.declarations(web = false)
        val sampling = Sampling.CHAT
        log("- instructions: ${system.length} chars; ${tools.size} tools, ${tools.sumOf { it.length }} chars")
        log("")
        log("| round | prompt | open ms | open tokens | open tok/s | first word ms | prefill tok/s | decode tokens | decode tok/s | total ms | reply |")
        log("|---|---|---|---|---|---|---|---|---|---|---|")
        try {
            repeat(rounds) { round ->
                for (prompt in prompts) {
                    val opening = SystemClock.elapsedRealtime()
                    val conversation = engine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(if (prompt.tools) system else Instructions.render(container.settings.systemPrompt)),
                            tools = if (prompt.tools) tools.map { tool(Declared(it)) } else emptyList(),
                            samplerConfig = SamplerConfig(topK = sampling.topK, topP = sampling.topP, temperature = sampling.temperature, seed = sampling.seed),
                            automaticToolCalling = false,
                            prefillPrefaceOnInit = true,
                            maxOutputToken = 512,
                        ),
                    )
                    try {
                        val openMs = SystemClock.elapsedRealtime() - opening
                        val preface = runCatching { conversation.getBenchmarkInfo() }.getOrNull()
                        val sent = SystemClock.elapsedRealtime()
                        var firstAt = 0L
                        val reply = StringBuilder()
                        conversation.sendMessageAsync("[Now: Tuesday 24 November 2026, 10:15]\n${prompt.text}").collect { chunk ->
                            if (firstAt == 0L) firstAt = SystemClock.elapsedRealtime()
                            if (chunk.toolCalls.isNotEmpty()) {
                                chunk.toolCalls.forEach { reply.append("CALL ${it.name}${it.arguments} ") }
                            } else {
                                reply.append(chunk.toString())
                            }
                        }
                        val done = SystemClock.elapsedRealtime()
                        val info = conversation.getBenchmarkInfo()
                        log(
                            "| ${round + 1} | ${prompt.id} | $openMs | ${preface?.lastPrefillTokenCount ?: "?"} | ${preface?.lastPrefillTokensPerSecond?.let { "%.0f".format(it) } ?: "?"} " +
                                "| ${if (firstAt > 0) firstAt - sent else -1} | ${"%.0f".format(info.lastPrefillTokensPerSecond)} " +
                                "| ${info.lastDecodeTokenCount} | ${"%.1f".format(info.lastDecodeTokensPerSecond)} | ${done - sent} " +
                                "| ${reply.toString().replace("\n", " ").replace("|", "/").take(90)} |",
                        )
                    } catch (e: Throwable) {
                        log("| ${round + 1} | ${prompt.id} | FAILED: ${causes(e)} |")
                    } finally {
                        runCatching { conversation.close() }
                    }
                }
            }
        } finally {
            runCatching { engine.close() }
        }
        log("")
    }

    companion object {
        /** Downloads [url] to [into] (resuming a partial file), then checks it against [sha256]. */
        suspend fun fetch(url: String, into: File, sha256: String?, log: (String) -> Unit) = withContext(Dispatchers.IO) {
            into.parentFile?.mkdirs()
            val started = SystemClock.elapsedRealtime()
            val have = into.length()
            val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val resumed = connection.responseCode == 206
            val total = connection.contentLengthLong.let { if (resumed) it + have else it }
            log("- fetching ${into.name}: HTTP ${connection.responseCode}, ${total / 1_000_000} MB${if (resumed) ", resuming at ${have / 1_000_000} MB" else ""}")
            var done = if (resumed) have else 0L
            var nextReport = done + 250_000_000
            connection.inputStream.use { input ->
                java.io.FileOutputStream(into, resumed).use { out ->
                    val buffer = ByteArray(1 shl 20)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done >= nextReport) {
                            log("  ${done / 1_000_000} MB, ${done / 1000 / maxOf(1, SystemClock.elapsedRealtime() - started)} MB/s")
                            nextReport += 250_000_000
                        }
                    }
                }
            }
            log("- fetched ${into.length()} bytes in ${(SystemClock.elapsedRealtime() - started) / 1000} s")
            if (sha256 != null) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                into.inputStream().use { input ->
                    val buffer = ByteArray(1 shl 20)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                log("- sha256 ${if (actual == sha256) "OK" else "MISMATCH: $actual"}")
            }
        }
    }

    private fun causes(e: Throwable): String =
        generateSequence(e) { it.cause }.take(4).joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }

    /** A declaration the model sees; never run here. */
    private class Declared(private val declaration: String) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = declaration
        override fun execute(paramsJsonString: String): String = error("not run in the bench")
    }
}
