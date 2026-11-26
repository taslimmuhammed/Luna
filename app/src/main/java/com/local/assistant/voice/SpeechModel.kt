package com.local.assistant.voice

import android.content.Context
import android.media.AudioFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.local.assistant.llm.AiCoreBackend
import com.local.assistant.media.MediaLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Collections
import java.util.Locale
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer as GenAiRecognizer

/**
 * Voice messages written down by ML Kit's GenAI speech recognition in its advanced mode: a Gemini
 * model that AICore keeps on the phone, which Google describes as covering more languages, with
 * better quality overall, than the phone's own recogniser. Nothing for the app to ship or load:
 * AICore downloads the model and keeps it, beside Gemini Nano. Phones with Gemini Nano on the
 * chips ML Kit lists (Pixel 10 and 11, Snapdragon SM8750 to SM8975 — the test phone's SM8850 is
 * one — and a few MediaTek and Exynos ones); elsewhere, and until the model is here, the phone's
 * recogniser writes voice messages down alone ([Transcriber]). On the user's own voice it did worse
 * than the phone's recogniser (VoiceBench, 26 Nov 2026), so it is only the fallback.
 *
 * The language is the phone's when the model takes it, else US English on an English phone:
 * ML Kit lists en-US and no en-IN (Nov 2026). ML Kit's speech library is an alpha.
 */
class SpeechModel(private val context: Context, private val scope: CoroutineScope) {

    private enum class State { UNKNOWN, UNAVAILABLE, WAITING, DOWNLOADING, READY }

    @Volatile private var state = State.UNKNOWN

    /** The recogniser for the language found, once its model is here. */
    @Volatile private var client: GenAiRecognizer? = null

    private val lock = Mutex()

    /** The model is here: voice messages are written down with it. */
    val ready: Boolean get() = state == State.READY

    /**
     * Finds the model for the phone's language, and has AICore download it, on an unmetered
     * network, when it can be. Called as the app comes to the front: AICore serves the app in
     * front. Does nothing once the model is here or on its way, or the phone has none.
     */
    fun refresh() {
        if (state == State.READY || state == State.DOWNLOADING || state == State.UNAVAILABLE) return
        scope.launch {
            lock.withLock {
                try {
                    find()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // AICore not bound yet, or the download stopped: asked again next time.
                    Log.w(TAG, "Advanced speech recognition not set up: ${e.javaClass.simpleName}: ${e.message}")
                    state = State.UNKNOWN
                }
            }
        }
    }

    private suspend fun find() {
        if (state == State.READY || state == State.DOWNLOADING) return
        var later: GenAiRecognizer? = null
        var unknown = false
        for (locale in languages()) {
            val candidate = SpeechRecognition.getClient(
                SpeechRecognizerOptions.Builder().apply {
                    this.locale = locale
                    preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
                }.build(),
            )
            val status = runCatching { candidate.checkStatus() }
                .onFailure { Log.i(TAG, "Advanced speech status for ${locale.toLanguageTag()} unknown: ${it.message}") }
                .getOrNull()
            if (status != null) Log.i(TAG, "Advanced speech for ${locale.toLanguageTag()}: ${AiCoreBackend.statusName(status)}")
            when (status) {
                FeatureStatus.AVAILABLE -> {
                    later?.close()
                    use(candidate)
                    return
                }
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> if (later == null) later = candidate else candidate.close()
                null -> {
                    unknown = true
                    candidate.close()
                }
                else -> candidate.close()
            }
        }
        val toFetch = later ?: run {
            state = if (unknown) State.UNKNOWN else State.UNAVAILABLE
            return
        }
        if (!unmetered()) {
            Log.i(TAG, "Advanced speech can be downloaded; waiting for Wi-Fi")
            toFetch.close()
            state = State.WAITING
            return
        }
        state = State.DOWNLOADING
        try {
            val started = SystemClock.elapsedRealtime()
            withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                toFetch.download()
                    .takeWhile { it !is DownloadStatus.DownloadCompleted }
                    .collect { if (it is DownloadStatus.DownloadFailed) throw it.e }
            }
            if (toFetch.checkStatus() == FeatureStatus.AVAILABLE) {
                Log.i(TAG, "Advanced speech downloaded in ${(SystemClock.elapsedRealtime() - started) / 1000} s")
                use(toFetch)
            } else {
                toFetch.close()
                state = State.WAITING
            }
        } catch (e: Exception) {
            toFetch.close()
            throw e
        }
    }

    private fun use(recogniser: GenAiRecognizer) {
        client?.close()
        client = recogniser
        state = State.READY
    }

    /** The phone's language, then US English for an English phone that isn't set to it. */
    private fun languages(): List<Locale> {
        val phone = Locale.getDefault()
        return listOfNotNull(phone, Locale.US.takeIf { phone.language == Locale.ENGLISH.language && phone != Locale.US })
    }

    private fun unmetered(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Starts writing down a recording about to begin; null until the model is here. */
    fun start(): Session? {
        val recogniser = client?.takeIf { ready } ?: return null
        return runCatching { Session(recogniser) }
            .onFailure { Log.w(TAG, "Advanced speech recognition wouldn't start", it) }
            .getOrNull()
    }

    /**
     * One recording's words. [feed] it the audio as it is recorded, then [finish] for what was
     * said. The model reads the recorder's own audio through a pipe, as the phone's recogniser
     * does, so the words are ready about when the user stops.
     */
    inner class Session internal constructor(private val recogniser: GenAiRecognizer) {

        private val pipe = ParcelFileDescriptor.createPipe()
        private val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        private val source = AudioSource.fromPfd(pipe[0], AudioSource.Mode.STREAMING, PCM)

        /** The recorder never waits on the model: its chunks queue here, and go down the pipe from this. */
        private val chunks = Channel<ByteArray>(Channel.UNLIMITED)

        /** Each finished stretch of speech, in order. */
        private val said: MutableList<String> = Collections.synchronizedList(ArrayList())

        @Volatile private var failed = false

        private val writer = scope.launch(Dispatchers.IO) {
            try {
                for (chunk in chunks) out.write(chunk)
            } catch (e: IOException) {
                // The model stopped reading (an error): the phone's recogniser's words go instead.
            } finally {
                runCatching { out.close() }
            }
        }

        private val reader = scope.launch(Dispatchers.IO) {
            try {
                recogniser.startRecognition(SpeechRecognizerRequest.Builder().apply { audioSource = source }.build())
                    .takeWhile { it !is SpeechRecognizerResponse.CompletedResponse }
                    .collect { response ->
                        when (response) {
                            is SpeechRecognizerResponse.FinalTextResponse -> response.text.trim().takeIf { it.isNotEmpty() }?.let(said::add)
                            is SpeechRecognizerResponse.ErrorResponse -> throw response.e
                            else -> Unit
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                Log.w(TAG, "Advanced speech recognition failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                // Closing the read end also stops the writer, if the model stopped early.
                runCatching { source.close() }
            }
        }

        /** A chunk of 16 kHz mono 16-bit PCM, from any thread; never blocks. */
        fun feed(pcm: ByteArray, length: Int) {
            chunks.trySend(pcm.copyOf(length))
        }

        /** What was said, or null when the model failed, took too long or heard nothing. The recording is over. */
        suspend fun finish(): String? {
            // The same moment of quiet the phone's recogniser gets, so a last word isn't cut off.
            chunks.trySend(ByteArray(TRAILING_SILENCE_BYTES))
            chunks.close()
            val started = SystemClock.elapsedRealtime()
            val done = withTimeoutOrNull(FINISH_TIMEOUT_MS) {
                writer.join()
                reader.join()
            } != null
            if (!done) stop()
            val words = synchronized(said) { said.joinToString(" ") }.replace(WHITESPACE, " ").trim()
            Log.i(TAG, "${if (!done) "Timed out" else if (failed) "Failed" else "Transcribed"} in ${SystemClock.elapsedRealtime() - started} ms after the recording: ${words.length} characters")
            return words.takeIf { done && !failed && it.isNotEmpty() }
        }

        /** The recording was thrown away. */
        fun cancel() {
            chunks.close()
            writer.cancel()
            stop()
        }

        private fun stop() {
            reader.cancel()
            runCatching { out.close() }
            scope.launch { runCatching { recogniser.stopRecognition() } }
        }
    }

    private companion object {
        const val TAG = "SpeechModel"

        /** What the recorder writes: 16 kHz mono 16-bit PCM, the format ML Kit asks for. */
        val PCM: AudioFormat = AudioFormat.Builder()
            .setSampleRate(MediaLimits.AUDIO_SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        /** How long the words may take after the recording ends, as for the phone's recogniser. */
        const val FINISH_TIMEOUT_MS = 4_000L

        /** 0.7 s of silence, 16 kHz mono 16-bit. */
        const val TRAILING_SILENCE_BYTES = MediaLimits.AUDIO_SAMPLE_RATE_HZ * 2 * 7 / 10

        /** A download still going after this is left to AICore and looked for again next time. */
        const val DOWNLOAD_TIMEOUT_MS = 30 * 60 * 1000L

        val WHITESPACE = Regex("\\s+")
    }
}
