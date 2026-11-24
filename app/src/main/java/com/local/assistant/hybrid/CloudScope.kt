package com.local.assistant.hybrid

import android.util.Log
import com.local.assistant.data.db.ChatDao
import com.local.assistant.memory.db.ArchiveRepository
import com.local.assistant.memory.db.ChunkDao
import com.local.assistant.memory.db.ChunkEntity
import com.local.assistant.memory.retrieval.ArchiveSource
import com.local.assistant.memory.retrieval.Bm25
import com.local.assistant.memory.retrieval.KeywordHit
import com.local.assistant.memory.retrieval.VectorHit
import com.local.assistant.memory.tools.ToolCatalog
import com.local.assistant.memory.tools.ToolExecutor

/**
 * What a Cloud chat may see, in one place.
 *
 * The rule: **the cloud model is only ever shown what was already said to it.** A message typed in
 * Cloud mode goes to the cloud; so may anything learned only from such messages (a fact, a
 * reminder, a note, an archived exchange), since the cloud saw where it came from. Everything else
 * stays on the phone: every Local chat and whatever was learned from one, the user's messages,
 * photos, files, health readings and contacts.
 *
 * It isn't a filter laid over the prompt at the end, where one missed line would leak. A Cloud
 * chat is built from its own sources, and each of them can only read Cloud data:
 *
 * - its history: the chat's Cloud messages only (`ConversationManager.cloud`), so switching a
 *   chat from Local to Cloud sends none of what came before;
 * - its memory: [com.local.assistant.memory.db.MemoryRepository] with `cloudOnly`, and the
 *   same for saved images and notes, whose every query has `cloud = 1` in its SQL;
 * - its archive: [CloudArchive] below, exchanges whose message was said in a Cloud chat;
 * - its tools: [TOOLS], with no tool that reads the phone. [ToolExecutor] refuses any other call
 *   (`allowed`), whatever the model asks for.
 *
 * Writes go the other way: a Local chat sees everything, Cloud data included, and a Local change
 * to something the cloud has seen makes it the phone's alone again.
 */
object CloudScope {

    /**
     * The tools a Cloud chat has: ones that add to or act on its own memory, set the clock, or
     * reach the web. Not calls and messages (they read the contacts), photos, files, health data or
     * the inbox (the user's own data), nor screenshots (the screen).
     */
    val TOOLS: Set<String> = setOf(
        ToolCatalog.ADD_TASK,
        ToolCatalog.UPDATE_TASK,
        ToolCatalog.ADD_EVENT,
        ToolCatalog.SAVE_FACT,
        ToolCatalog.GET_UPCOMING,
        ToolCatalog.SEARCH_MEMORY,
        ToolCatalog.FORGET,
        ToolCatalog.SET_ALARM,
        ToolCatalog.REMEMBER_IMAGE,
        ToolCatalog.EDIT_NOTE,
        ToolCatalog.SET_TIMER,
        ToolCatalog.PHONE_SETTING,
        ToolCatalog.CALCULATE,
        ToolCatalog.WEB_SEARCH,
    )

    /**
     * Whether a Cloud chat may run [name] with [arguments]. read_file only for the document the
     * user attached to their own Cloud message ([ToolExecutor.ATTACHED]): never to find files.
     */
    fun allows(name: String, arguments: Map<String, Any?>): Boolean =
        name in TOOLS || (name == ToolCatalog.READ_FILE && arguments[ToolExecutor.ATTACHED] == true)

    /** What the cloud model hears when it asks for something only a Local chat can do. */
    const val LOCAL_ONLY =
        "That needs the user's own data on the phone (contacts, messages, photos, files, health or Local chats), " +
            "which never goes to the cloud. Tell them they can switch this chat to Local to do it."
}

/**
 * The archive as a Cloud chat's recall reads it: only exchanges whose message was said in a Cloud
 * chat. Keyword search filters in SQL; the vector index holds every exchange, so its neighbours are
 * over-fetched and the Local ones dropped before anything else sees them.
 */
class CloudArchive(
    private val archive: ArchiveRepository,
    private val chunks: ChunkDao,
) : ArchiveSource {

    override suspend fun keywordHits(match: String, limit: Int): List<KeywordHit> =
        try {
            chunks.keywordMatchesCloud(match, limit).map { row ->
                val scored = Bm25.score(Bm25.parse(row.matchinfo))
                KeywordHit(row.id, row.messageId, row.chatId, scored.score, scored.termHits)
            }
        } catch (e: Exception) {
            // A query the FTS parser rejects must not cost the user their turn.
            Log.w(TAG, "Keyword search failed: ${e.javaClass.simpleName}")
            emptyList()
        }

    override suspend fun chunks(ids: Collection<Long>): List<ChunkEntity> {
        if (ids.isEmpty()) return emptyList()
        val cloud = chunks.cloudChunkIds(ids.toList())
        return if (cloud.isEmpty()) emptyList() else archive.chunks(cloud)
    }

    override suspend fun nearest(query: ByteArray, k: Int): List<VectorHit> {
        val hits = archive.nearest(query, k * OVERFETCH)
        if (hits.isEmpty()) return hits
        val cloud = chunks.cloudChunkIds(hits.map { it.chunkId }).toSet()
        return hits.filter { it.chunkId in cloud }.take(k)
    }

    private companion object {
        const val TAG = "CloudArchive"

        /** Most of the archive is Local; neighbours enough that some Cloud ones are among them. */
        const val OVERFETCH = 5
    }
}

/** The Cloud messages of a chat, for the history and tool replay a Cloud conversation is built from. */
suspend fun ChatDao.cloudTurnsBefore(chatId: Long, beforeId: Long) = turnsBefore(chatId, beforeId).filter { it.cloud }
