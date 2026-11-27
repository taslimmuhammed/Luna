package com.local.assistant.gallery

import com.local.assistant.memory.embed.Int8Vectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** Pictures and screenshots' words ranked together ([PhotoRanking.rank]), each against its own bar. */
class PhotoRankingTest {

    private val dims = Int8Vectors.DIMENSIONS
    private val query = vectorAt(1f)

    /** A vector whose cosine with [query] is [cos]. */
    private fun vectorAt(cos: Float): ByteArray {
        val v = FloatArray(dims)
        v[0] = cos
        v[1] = sqrt(1f - cos * cos)
        return Int8Vectors.quantize(v)!!
    }

    private fun rank(vararg items: Pair<Float, Boolean>): List<PhotoHit> {
        val vectors = ByteArray(items.size * dims)
        items.forEachIndexed { i, (cos, _) -> vectorAt(cos).copyInto(vectors, i * dims) }
        val norms = IntArray(items.size) { Int8Vectors.squaredNorm(vectorAt(items[it].first)) }
        return PhotoRanking.rank(query, LongArray(items.size) { it.toLong() }, vectors, norms, words = BooleanArray(items.size) { items[it].second })
    }

    @Test
    fun picturesAloneAreRankedAsBefore() {
        val best = PhotoRanking.MIN_BEST + 0.05f
        val hits = rank(best to false, best - PhotoRanking.MATCH_MARGIN / 2 to false, best - PhotoRanking.MATCH_MARGIN * 2 to false)
        assertEquals(listOf(0L, 1L), hits.map { it.mediaId })
    }

    @Test
    fun wordsBelowTheirBarDontCount() {
        val hits = rank(PhotoRanking.MIN_BEST + 0.05f to false, PhotoRanking.WORDS_MIN - 0.05f to true)
        assertEquals(listOf(0L), hits.map { it.mediaId })
    }

    @Test
    fun wordsMatchWhenNoPictureDoes() {
        val hits = rank(PhotoRanking.MIN_BEST - 0.1f to false, PhotoRanking.WORDS_MIN + 0.05f to true)
        assertEquals(listOf(1L), hits.map { it.mediaId })
        assertEquals(PhotoRanking.listed(PhotoRanking.WORDS_MIN + 0.05f, true), hits.single().similarity, 0.01f)
    }

    @Test
    fun eachKindSetsItsOwnBar() {
        // A close picture doesn't cut a screenshot whose words match, nor the other way round.
        val picture = maxOf(PhotoRanking.MIN_BEST, PhotoRanking.WORDS_MIN) + 0.2f
        val words = PhotoRanking.WORDS_MIN + 0.02f
        val hits = rank(picture to false, words to true, words - PhotoRanking.WORDS_MARGIN * 2 to true)
        assertEquals(setOf(0L, 1L), hits.map { it.mediaId }.toSet())
        assertTrue(hits.first().mediaId == 0L)
    }
}
