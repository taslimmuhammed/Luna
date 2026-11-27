package com.local.assistant.gallery

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.local.assistant.memory.embed.Int8Vectors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The words on screenshots, so photo search finds one by what it says ("my train PNR", "the wifi
 * password") as well as by how it looks: to the photo model a screenshot is mostly a page of
 * text, and it reads little of it.
 *
 * ML Kit's text recognition (Latin script, in the app) reads each screenshot on the phone, in the
 * background with the rest of indexing ([GalleryIndex]). The words are embedded by the photo
 * model's text side ([GalleryEmbedder.embedDocument]), into the space the search words go to, and
 * searched beside the picture ([PhotoRanking.rankWithWords]). Screenshots of this app aren't
 * read: they're its own chats, already in memory search, and their words would answer anything.
 */
class ScreenText {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** The words on [bitmap], or null if it couldn't be read. */
    suspend fun read(bitmap: Bitmap): String? = suspendCancellableCoroutine { done ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { done.resume(it.text) }
            .addOnFailureListener { done.resume(null) }
    }

    fun close() = recognizer.close()

    companion object {
        /**
         * What read the words and made their vector: a different reader or model reads them all
         * again. The text vectors don't depend on the image budget, as memory search's don't.
         */
        const val READ_BY = "mlkit-latin-16.0.1/${GalleryModel.KEY}@${Int8Vectors.DIMENSIONS}"

        /** A screenshot is read at full size up to this (a 1272×2772 one is 3.5 M); longer ones are halved. */
        const val MAX_PIXELS = 12_000_000

        /**
         * The most of its words that go to the model: as memory search sends a memory. The first
         * ones, which are the screen's title and what it's about.
         */
        const val MAX_CHARS = 1_200

        /** Halved on the model's refusal down to this ([GalleryEmbedder.embedDocument]). */
        const val MIN_SENT_CHARS = 150

        /** Fewer letters than this is nothing to search by: a status bar's clock and battery. */
        const val MIN_LETTERS = 12

        /**
         * The words as stored: one line per line read, spaces tidied, and empty when there's
         * nothing worth searching ([MIN_LETTERS]).
         */
        fun clean(read: String?): String {
            if (read == null) return ""
            val lines = read.lineSequence().map { it.trim().replace(SPACES, " ") }.filter { it.isNotEmpty() }.toList()
            val text = lines.joinToString("\n")
            return if (text.count { it.isLetter() } < MIN_LETTERS) "" else text
        }

        private val SPACES = Regex("\\s+")
    }
}
