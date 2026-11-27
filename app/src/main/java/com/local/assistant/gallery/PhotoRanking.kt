package com.local.assistant.gallery

import com.local.assistant.memory.embed.Int8Vectors

/** A match; for a video, [atMs] is the moment that matched. */
data class PhotoHit(val mediaId: Long, val similarity: Float, val atMs: Long? = null)

/**
 * Which photos answer a query, best first.
 *
 * Set from the phone's own 2,428 photos (2026-10-25, `GalleryCalibrationTest`): for "beach",
 * "sunset", "food", "mountains" and "car", the top 100 were looked at and the right ones marked.
 * What separated them was distance from the query's own best photo. Where the matches ended sat
 * about 0.035 below the top score for every one of these queries, while the similarity at that
 * point ranged from 0.70 to 0.75 between them, so a fixed similarity would be too strict for one
 * query and too loose for the next. Kept within [MATCH_MARGIN] of the best: beach 11 photos (91%
 * right), sunset 30 (93%), food 30 (97%), mountains 27 (100%). Car was 38% right, mostly street
 * scenes with a car somewhere in them. A cutoff in standard deviations above the query's mean (the
 * first version) let in hundreds of near misses, and cut common subjects like "selfie" to a few.
 *
 * Anything has a best photo, so a query must also reach [MIN_BEST] to match at all. That floor is
 * set low on purpose: wording moves every score together, by as much as 0.035 ("beach" topped
 * out at 0.762, "beaches" at 0.727, on the same photos first), and a search that finds nothing
 * when the photos are there is worse than one that offers the nearest thing. "skiing", on a
 * phone with snowy mountains but no skiing, topped out at 0.732 and shows those. The tool asks
 * the model for a short singular query, the wording that scored highest.
 */
object PhotoRanking {

    /** How far below the best photo a photo may score and still be listed. */
    const val MATCH_MARGIN = 0.03f

    /** The best photo must reach this, or nothing matches. */
    const val MIN_BEST = 0.72f

    const val MAX_RESULTS = 2_000

    /**
     * A screenshot's words ([ScreenText]) are scored against a query on a scale of their own,
     * words against words rather than words against a picture: they count from [WORDS_MIN], and
     * within [WORDS_MARGIN] of the best words, whatever the pictures score. They are listed
     * [WORDS_OFFSET] lower ([listed]): as far above the pictures' bar ([MIN_BEST]) as above their
     * own, so the two kinds of match rank on one scale and [PhotoRecall]'s bars, set on pictures,
     * hold for words too.
     *
     * Set on the phone's 129 screenshots with words (27 Nov 2026, debug `screentext` probe). For
     * a search Gemini Nano wrote from each of 40 screenshots, as a person would type it, the
     * screenshot's words scored 0.70–0.98, median 0.84, and were the closest words for 29 of 40.
     * Searches its screenshots shouldn't answer ("beach", "dog", "tell me a joke", "how are you")
     * topped out at 0.71–0.77 on words. From 0.77, within 0.03: 37 of the 40 found, 27 listed
     * first, against 29 and 20 by the pictures alone; of the unrelated searches only "birthday
     * cake" (0.86) reached it, likely a screenshot about one. The offset barely moved which came
     * first (27 at 0.05, 28 at none), and without it "good morning" (0.77) brought a screenshot
     * under the reply.
     */
    const val WORDS_MIN = 0.77f
    const val WORDS_MARGIN = 0.03f
    const val WORDS_OFFSET = 0.05f

    /** A vector's score as listed: a picture's as it is, a screenshot's words' [WORDS_OFFSET] lower. */
    fun listed(score: Float, words: Boolean): Float = if (words) score - WORDS_OFFSET else score

    internal fun scores(query: ByteArray, vectors: ByteArray, norms: IntArray, size: Int): FloatArray? {
        if (size == 0) return null
        val queryNorm = Int8Vectors.squaredNorm(query)
        if (queryNorm == 0) return null
        val dims = Int8Vectors.DIMENSIONS
        return FloatArray(size) { i ->
            Int8Vectors.similarity(Int8Vectors.cosineDistance(Int8Vectors.dot(vectors, i * dims, query), norms[i], queryNorm))
        }
    }

    /**
     * The matches, best first. [ids] and [vectors] side by side: vector i is bytes
     * `i * DIMENSIONS until (i + 1) * DIMENSIONS` of [vectors], with its squared norm at [norms] i.
     * Where [words] i is true, the vector is a screenshot's words, held to their own bar
     * ([WORDS_MIN]); the pictures' bar ([cutoff]) is set by the pictures alone.
     */
    fun rank(query: ByteArray, ids: LongArray, vectors: ByteArray, norms: IntArray, size: Int = ids.size, words: BooleanArray? = null): List<PhotoHit> {
        val scores = scores(query, vectors, norms, size) ?: return emptyList()
        var bestPicture = Float.NEGATIVE_INFINITY
        var bestWords = Float.NEGATIVE_INFINITY
        for (i in 0 until size) {
            if (words?.get(i) == true) bestWords = maxOf(bestWords, scores[i]) else bestPicture = maxOf(bestPicture, scores[i])
        }
        val pictureCut = if (bestPicture >= MIN_BEST) bestPicture - MATCH_MARGIN else Float.POSITIVE_INFINITY
        val wordsCut = if (bestWords >= WORDS_MIN) bestWords - WORDS_MARGIN else Float.POSITIVE_INFINITY
        val hits = ArrayList<PhotoHit>()
        for (i in 0 until size) {
            val isWords = words?.get(i) == true
            if (scores[i] >= if (isWords) wordsCut else pictureCut) hits += PhotoHit(ids[i], listed(scores[i], isWords))
        }
        hits.sortWith(compareByDescending<PhotoHit> { it.similarity }.thenBy { it.mediaId })
        return if (hits.size > MAX_RESULTS) hits.subList(0, MAX_RESULTS) else hits
    }

    /** The lowest similarity that still counts for this query, or null when nothing matches. */
    fun cutoff(scores: FloatArray): Float? {
        val best = scores.maxOrNull() ?: return null
        return if (best < MIN_BEST) null else best - MATCH_MARGIN
    }
}
