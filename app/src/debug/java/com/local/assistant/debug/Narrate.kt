package com.local.assistant.debug

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.local.assistant.voice.NaturalVoices
import com.local.assistant.voice.VoicePack
import java.io.File

/**
 * Debug builds only: speech for a demo video's narration, made on the phone by the app's own
 * Kokoro voice pack (the one Luna speaks with), and written as WAV files to pull with adb.
 *
 *   adb shell "am broadcast -a com.local.assistant.debug.MODEL_PROBE -p com.local.assistant.dev --es mode narrate [--es script narration.txt] [--ei speaker 3] [--ef speed 1.0]"
 *
 * The script (in files/) has one line per clip, `id|text`; an optional third field `id|text|speaker`
 * picks another of Kokoro's voices for that line. Clips go to files/narration/<id>.wav.
 */
class Narrate(private val context: Context, private val log: (String) -> Unit) {

    fun run(scriptName: String, speaker: Int, speed: Float) {
        val dir = File(context.filesDir, "voice/" + VoicePack.KOKORO.name)
            .takeIf { it.isDirectory }
            ?: File(context.filesDir, "voice").listFiles()?.firstOrNull { it.isDirectory }
            ?: error("No voice pack in files/voice")
        val script = File(context.filesDir, scriptName)
        val out = File(context.filesDir, "narration").apply { mkdirs() }
        val started = SystemClock.elapsedRealtime()
        val tts = OfflineTts(null, NaturalVoices.HEART.config(dir, 4))
        try {
            log("# Narration, ${tts.numSpeakers()} speakers, ${tts.sampleRate()} Hz, from ${dir.name}")
            script.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                val parts = line.split('|')
                val id = parts[0].trim()
                val text = parts.getOrNull(1)?.trim().orEmpty()
                val sid = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: speaker
                val t0 = SystemClock.elapsedRealtime()
                val audio = tts.generate(text, sid, speed)
                val file = File(out, "$id.wav")
                audio.save(file.path)
                log("- $id: ${"%.2f".format(audio.samples.size / audio.sampleRate.toFloat())} s, speaker $sid, made in ${SystemClock.elapsedRealtime() - t0} ms")
            }
        } finally {
            tts.release()
        }
        log("Done in ${SystemClock.elapsedRealtime() - started} ms")
    }
}
