package com.local.assistant.memory.work

import android.util.Log
import com.local.assistant.data.db.AppDatabase
import com.local.assistant.memory.db.AppStateEntity
import com.local.assistant.memory.db.ArchiveRepository
import com.local.assistant.memory.db.SummaryStatus

/**
 * Once, the first time the model runs with its text decoder in fp32
 * ([com.local.assistant.llm.ModelPrecision]): puts right what it wrote before, in fp16, when it
 * garbled digits past token 2,048.
 *
 * Left alone, those numbers outlive the fix. The fp32 model copies what it is given exactly,
 * including its own old mistakes: asked for a roll number, it read "43610104" out of an archived
 * fp16 reply rather than 4361044 off the certificate, and a saved image's details said 436104.
 *
 * - Archived exchanges keep the user's words but lose a reply with digits in it.
 * - Session and rolling summaries with digits in them go; the chats themselves keep everything.
 *
 * What the user typed was never garbled, so facts and the chats' own messages are not touched.
 */
class PrecisionUpgrade(
    private val database: AppDatabase,
    private val archive: ArchiveRepository,
    /** The prompt's summary and the archive's vectors changed. */
    private val onChanged: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Report(val exchanges: Int, val summaries: Int)

    /** Null when there was nothing left to do. */
    suspend fun run(): Report? {
        val state = database.appStateDao()
        if (state.get(KEY_DONE) == VERSION) return null
        // When fp32 began, kept across restarts: what was written after it needs nothing.
        val since = state.get(KEY_SINCE)?.toLongOrNull()
            ?: clock().also { state.put(AppStateEntity(KEY_SINCE, it.toString())) }

        val exchanges = archive.dropRepliesWithNumbers(since)
        var summaries = 0
        val sessions = database.sessionDao()
        for (session in sessions.summarised()) {
            val ended = session.endedAt ?: continue
            if (ended < since && session.summary.orEmpty().any(Char::isDigit)) {
                sessions.setSummary(session.id, null, SummaryStatus.DONE)
                summaries++
            }
        }
        val chats = database.chatDao()
        for (chat in chats.summarisedChats()) {
            if (chat.rollingSummary.orEmpty().any(Char::isDigit)) {
                // The turns it covered are replayed again, or summarised afresh when they don't fit.
                chats.setRollingSummary(chat.id, null, null)
                summaries++
            }
        }
        onChanged()
        state.put(AppStateEntity(KEY_DONE, VERSION))

        return Report(exchanges, summaries).also { Log.i(TAG, "Put right after the move to fp32: $it") }
    }

    private companion object {
        const val TAG = "PrecisionUpgrade"
        const val KEY_DONE = "precision.fp32_cleanup"
        const val KEY_SINCE = "precision.fp32_since"
        const val VERSION = "1"
    }
}
