package com.local.assistant.debug

import android.content.Context
import android.os.SystemClock
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.llm.AiCoreBackend
import com.local.assistant.llm.ChatSpec
import com.local.assistant.llm.GenEvent
import com.local.assistant.llm.LlmBackend
import com.local.assistant.llm.PromptAttachment
import com.local.assistant.llm.Sampling
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale

/**
 * Debug builds only: the user's own voice messages, written down again by AICore's speech
 * recognition (basic and advanced) and by Gemma hearing the recording, beside what the phone's
 * recogniser wrote. The recordings and the phone's words come from files/voice-cases.tsv
 * (path, tab, words), one a line.
 *
 *   adb shell am broadcast -a com.local.assistant.debug.MODEL_PROBE -p com.local.assistant.dev --es mode voice [--ez download true]
 */
class VoiceBench(
    private val context: Context,
    private val gemma: LlmBackend,
    private val log: (String) -> Unit,
) {
    suspend fun run(download: Boolean) {
        val cases = File(context.filesDir, "voice-cases.tsv").readLines().mapNotNull { line ->
            line.split('\t').takeIf { it.size >= 2 }?.let { File(it[0]) to it[1] }
        }.filter { it.first.isFile }
        log("# Voice benchmark, ${java.util.Date()}: ${cases.size} recordings")
        log("")

        val recognisers = listOf(
            "advanced en-IN" to (SpeechRecognizerOptions.Mode.MODE_ADVANCED to Locale.forLanguageTag("en-IN")),
            "advanced en-US" to (SpeechRecognizerOptions.Mode.MODE_ADVANCED to Locale.US),
            "basic en-IN" to (SpeechRecognizerOptions.Mode.MODE_BASIC to Locale.forLanguageTag("en-IN")),
            "basic en-US" to (SpeechRecognizerOptions.Mode.MODE_BASIC to Locale.US),
        ).mapNotNull { (name, choice) ->
            val recogniser = SpeechRecognition.getClient(
                SpeechRecognizerOptions.Builder().apply {
                    preferredMode = choice.first
                    locale = choice.second
                }.build(),
            )
            var status = runCatching { recogniser.checkStatus() }.getOrElse { log("- $name: status failed: ${it.message}"); return@mapNotNull null }
            log("- $name: ${AiCoreBackend.statusName(status)}")
            if (status == FeatureStatus.DOWNLOADABLE && download) {
                runCatching { withTimeout(600_000) { recogniser.download().collect { log("    download: $it") } } }
                    .onFailure { log("    download failed: ${it.message}") }
                status = runCatching { recogniser.checkStatus() }.getOrDefault(status)
                log("    now ${AiCoreBackend.statusName(status)}")
            }
            if (status == FeatureStatus.AVAILABLE) name to recogniser else null
        }
        log("")
        gemma.ensureReady()

        for ((file, phone) in cases) {
            log("## ${file.name}")
            log("- phone: $phone")
            for ((name, recogniser) in recognisers) {
                val start = SystemClock.elapsedRealtime()
                val heard = runCatching {
                    withTimeout(30_000) {
                        val text = StringBuilder()
                        AudioSource.fromFile(file).use { source ->
                            val request = SpeechRecognizerRequest.Builder().apply { audioSource = source }.build()
                            recogniser.startRecognition(request)
                                .takeWhile { it !is SpeechRecognizerResponse.CompletedResponse }
                                .collect { response ->
                                    when (response) {
                                        is SpeechRecognizerResponse.FinalTextResponse -> text.append(response.text).append(' ')
                                        is SpeechRecognizerResponse.ErrorResponse -> throw response.e
                                        else -> Unit
                                    }
                                }
                        }
                        text.toString().trim()
                    }
                }
                log("- $name (${SystemClock.elapsedRealtime() - start} ms): ${heard.getOrElse { "ERROR ${it.javaClass.simpleName}: ${it.message}" }}")
            }
            val start = SystemClock.elapsedRealtime()
            val byGemma = runCatching { withTimeout(60_000) { gemmaHears(file, phone) } }
            log("- gemma (${SystemClock.elapsedRealtime() - start} ms): ${byGemma.getOrElse { "ERROR ${it.javaClass.simpleName}: ${it.message}" }}")
            log("")
        }
    }

    /** Gemma hears the recording with the phone's words beside it, and writes what was said. */
    private suspend fun gemmaHears(file: File, phone: String): String {
        val session = gemma.openChat(
            ChatSpec(
                systemPrefix = "You write down voice messages exactly as spoken. The speaker is in Kerala, India, and uses Malayalam names (Umma, Achan, Shiyas).",
                history = emptyList(),
                sampling = Sampling.BACKGROUND,
                maxOutputTokens = 96,
            ),
        )
        try {
            val out = StringBuilder()
            session.send(
                "The phone's speech recogniser wrote this recording as: \"$phone\". Listen to the recording and write what was said, word for word, fixing any name or number it misheard. Write only the words.",
                PromptAttachment(file.path, AttachmentKind.AUDIO),
            ).collect { if (it is GenEvent.TextDelta) out.append(it.text) }
            return out.toString().trim()
        } finally {
            session.close()
        }
    }
}
