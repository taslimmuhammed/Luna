package com.local.assistant.debug

import com.local.assistant.AppContainer
import com.local.assistant.gallery.PhotoRanking
import com.local.assistant.llm.Sampling
import kotlin.random.Random

/**
 * Debug builds only. How the words read off screenshots (`gallery/ScreenText`) score against
 * searches, for setting [PhotoRanking.WORDS_MIN], [PhotoRanking.WORDS_MARGIN] and
 * [PhotoRanking.WORDS_OFFSET]:
 *
 * - each sampled screenshot searched for by three of its own words, and by a search Gemini Nano
 *   writes from its words, as a person would type it (with the app in front);
 * - searches its screenshots shouldn't answer ("beach", "tell me a joke"), and a few they may.
 *
 * Only scores and ranks are written: never a screenshot's words, nor the searches made from them.
 *
 *   adb shell am broadcast -a com.local.assistant.debug.MODEL_PROBE -p com.local.assistant.dev --es mode screentext [--ei max 40] [--ez nano false]
 */
internal class ScreenTextProbe(private val container: AppContainer, private val log: (String) -> Unit) {

    /** [listedAt]: where the real search lists the screenshot, from 1; 0 when it doesn't. */
    private class Row(val target: Float, val rank: Int, val of: Int, val bestOther: Float, val bestPicture: Float, val ownPicture: Float, val listedAt: Int, val listed: Int)

    suspend fun run(max: Int, nano: Boolean) {
        val index = container.galleryIndex
        val texts = index.screenWords()
        log("# Screenshot words (${texts.size} screenshots with words to search)")
        log("Bar now: words from ${PhotoRanking.WORDS_MIN}, within ${PhotoRanking.WORDS_MARGIN} of the best, listed ${PhotoRanking.WORDS_OFFSET} lower; pictures from ${PhotoRanking.MIN_BEST}.")
        if (texts.isEmpty()) return
        val sample = texts.shuffled(Random(7)).take(max)
        val own = ArrayList<Row>()
        val written = ArrayList<Row>()
        for ((n, text) in sample.withIndex()) {
            ownWords(text.text)?.let { query -> measure(query, text.mediaId)?.let { own += it; line("own $n", it) } }
            if (nano) nanoSearch(text.text)?.let { query -> measure(query, text.mediaId)?.let { written += it; line("nano $n", it) } }
        }
        summary("By three of its own words", own)
        summary("By Nano's search", written)

        log("\n## Searches its screenshots shouldn't answer")
        for (query in UNRELATED) general(query)
        log("\n## Searches they may answer")
        for (query in MAYBE) general(query)
    }

    private suspend fun measure(query: String, mediaId: Long): Row? {
        val raw = container.galleryIndex.rawScores(query) ?: return null
        val target = raw.words[mediaId] ?: return null
        val others = raw.words.filterKeys { it != mediaId }.values
        val hits = container.galleryIndex.search(query).orEmpty()
        return Row(
            listedAt = 1 + hits.indexOfFirst { it.mediaId == mediaId },
            listed = hits.size,
            target = target,
            rank = 1 + others.count { it > target },
            of = raw.words.size,
            bestOther = others.maxOrNull() ?: 0f,
            bestPicture = raw.bestPicture,
            ownPicture = raw.pictures[mediaId] ?: 0f,
        )
    }

    private fun line(label: String, row: Row) {
        log("- $label: words %.3f, rank %d of %d (best other %.3f); best picture %.3f, its picture %.3f; search lists it %d of %d".format(row.target, row.rank, row.of, row.bestOther, row.bestPicture, row.ownPicture, row.listedAt, row.listed))
    }

    private fun summary(title: String, rows: List<Row>) {
        if (rows.isEmpty()) return
        val targets = rows.map { it.target }.sorted()
        fun pct(n: Int) = "%d%%".format(100 * n / rows.size)
        log("\n## $title: ${rows.size}")
        log("First: ${pct(rows.count { it.rank == 1 })}; top 3: ${pct(rows.count { it.rank <= 3 })}; top 10: ${pct(rows.count { it.rank <= 10 })}")
        log("The search: lists it ${pct(rows.count { it.listedAt > 0 })}; first ${pct(rows.count { it.listedAt == 1 })}; top 3 ${pct(rows.count { it.listedAt in 1..3 })}; median list length ${rows.map { it.listed }.sorted()[rows.size / 2]}")
        log("Its words: min %.3f, 10th pct %.3f, median %.3f, max %.3f".format(targets.first(), targets[targets.size / 10], targets[targets.size / 2], targets.last()))
        for (bar in listOf(0.60f, 0.62f, 0.64f, 0.66f, 0.68f, 0.70f, 0.72f, 0.74f)) {
            log("- from " + "%.2f".format(bar) + ": " + pct(rows.count { it.target >= bar }) + " reach it")
        }
        log("Its picture already above its words: " + pct(rows.count { it.ownPicture >= it.target }) + "; best picture median " + "%.3f".format(rows.map { it.bestPicture }.sorted()[rows.size / 2]))
    }

    private suspend fun general(query: String) {
        val raw = container.galleryIndex.rawScores(query) ?: return
        val scores = raw.words.values.sortedDescending()
        log("- \"$query\": best words %.3f (2nd %.3f, 10th %.3f), best picture %.3f; words from %.2f: %d".format(
            scores.getOrElse(0) { 0f }, scores.getOrElse(1) { 0f }, scores.getOrElse(9) { 0f }, raw.bestPicture, PhotoRanking.WORDS_MIN,
            scores.count { it >= PhotoRanking.WORDS_MIN },
        ))
    }

    /** Three words in a row from the middle of [text], of four letters or more; null with fewer. */
    private fun ownWords(text: String): String? {
        val words = WORD.findAll(text).map { it.value }.toList()
        if (words.size < 3) return null
        val start = (words.size / 2 - 1).coerceIn(0, words.size - 3)
        return words.subList(start, start + 3).joinToString(" ")
    }

    private suspend fun nanoSearch(text: String): String? = try {
        container.chatBackend.complete(
            system = "You write the searches people type into their phone's photo search.",
            input = "Text read from a screenshot on my phone:\n\"\"\"\n${text.take(1_200)}\n\"\"\"\n" +
                "Write what I would type to find this screenshot again: 2 to 5 words, the way people search " +
                "(\"flight ticket to Delhi\", \"electricity bill\", \"chat with Rahul about rent\"), not copied word for word. " +
                "Reply with the search only.",
            jsonSchema = null,
            maxTokens = 24,
            sampling = Sampling.BACKGROUND,
        ).trim().trim('"').lineSequence().firstOrNull()?.takeIf { it.isNotBlank() && it.length <= 80 }
    } catch (e: Exception) {
        null
    }

    private companion object {
        val WORD = Regex("\\p{L}[\\p{L}'-]{3,}")

        val UNRELATED = listOf(
            "beach", "sunset", "food", "mountains", "car", "selfie", "birthday cake", "dog", "wedding", "flowers",
            "what is the capital of France", "tell me a joke", "set an alarm for 6am", "how are you", "remind me to call mom",
            "good morning", "photos of my family", "the moon",
        )

        val MAYBE = listOf(
            "train ticket", "payment receipt", "wifi password", "boarding pass", "bank statement", "OTP", "error message",
            "order confirmation", "chat screenshot", "map directions",
        )
    }
}
