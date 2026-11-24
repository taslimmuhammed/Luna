package com.local.assistant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    /** The chat list: every chat but the assistant overlay's. */
    @Query("SELECT * FROM chats WHERE hidden = 0 ORDER BY updatedAt DESC")
    fun observeChats(): Flow<List<ChatEntity>>

    @Query("UPDATE chats SET hidden = 0 WHERE id = :chatId")
    suspend fun showChat(chatId: Long)

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id ASC")
    fun observeMessages(chatId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id ASC")
    suspend fun messagesFor(chatId: Long): List<MessageEntity>

    /** The conversation as the model sees it: user and assistant turns before [beforeId]. */
    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND id < :beforeId " +
            "AND role IN ('USER', 'ASSISTANT') ORDER BY id ASC",
    )
    suspend fun turnsBefore(chatId: Long, beforeId: Long): List<MessageEntity>

    /** The tool calls made between [afterId] and [beforeId], oldest first. */
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND role = 'TOOL' AND id > :afterId AND id < :beforeId ORDER BY id ASC")
    suspend fun toolRowsBetween(chatId: Long, afterId: Long, beforeId: Long): List<MessageEntity>

    @Query("SELECT attachmentPath FROM messages WHERE attachmentPath IS NOT NULL")
    suspend fun attachmentPaths(): List<String>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun message(messageId: Long): MessageEntity?

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun chat(chatId: Long): ChatEntity?

    @Insert
    suspend fun insertChat(chat: ChatEntity): Long

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("UPDATE messages SET text = :text, incomplete = :incomplete WHERE id = :messageId")
    suspend fun updateMessage(messageId: Long, text: String, incomplete: Boolean)

    @Query("UPDATE chats SET rollingSummary = :summary, rollingUptoMessageId = :uptoMessageId WHERE id = :chatId")
    suspend fun setRollingSummary(chatId: Long, summary: String?, uptoMessageId: Long?)

    @Query("UPDATE chats SET rollingSummary = NULL")
    suspend fun clearRollingSummaries()

    @Query("SELECT * FROM chats WHERE rollingSummary IS NOT NULL")
    suspend fun summarisedChats(): List<ChatEntity>

    /** Where [chatId]'s next message goes: the cloud model, or the phone's own. */
    @Query("UPDATE chats SET cloud = :cloud WHERE id = :chatId")
    suspend fun setCloud(chatId: Long, cloud: Boolean)

    /** Every message the cloud model was sent, newest first, for the screen that shows what it has seen. */
    @Query("SELECT * FROM messages WHERE cloud = 1 AND role != 'TOOL' ORDER BY id DESC LIMIT :limit")
    fun observeCloudMessages(limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE cloud = 0 AND role != 'TOOL'")
    fun observeLocalMessageCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE cloud = 1 AND role != 'TOOL'")
    fun observeCloudMessageCount(): Flow<Int>

    @Query("UPDATE chats SET title = :title WHERE id = :chatId")
    suspend fun renameChat(chatId: Long, title: String)

    @Query("UPDATE chats SET updatedAt = :timestamp WHERE id = :chatId")
    suspend fun touchChat(chatId: Long, timestamp: Long)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteChat(chatId: Long)

    @Query("DELETE FROM chats")
    suspend fun deleteAllChats()

    @Transaction
    suspend fun appendMessage(message: MessageEntity): Long {
        val id = insertMessage(message)
        touchChat(message.chatId, message.createdAt)
        return id
    }
}
