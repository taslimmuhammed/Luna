package com.local.assistant.memory.tools

import com.google.gson.Gson
import com.local.assistant.data.db.Role
import com.local.assistant.data.repo.ChatRepository

/**
 * Tool calls stored as TOOL rows in the chat they happened in, as JSON. Their place among the
 * messages is what ties a chip to the reply it belongs under.
 *
 * [cloud]: the log of a Cloud chat's tool calls, whose rows are marked as the cloud's, since the
 * model that asked for them saw their results.
 */
class ChatToolLog(private val chats: ChatRepository, private val cloud: Boolean = false) : ToolLog {

    override suspend fun record(chatId: Long, record: ToolRecord): Long =
        chats.addMessage(chatId = chatId, role = Role.TOOL, text = gson.toJson(record), cloud = cloud)

    override suspend fun read(id: Long): ToolRecord? =
        chats.message(id)?.takeIf { it.role == Role.TOOL }?.let { parse(it.text) }

    override suspend fun update(id: Long, record: ToolRecord) =
        chats.updateMessage(id, gson.toJson(record), incomplete = false)

    companion object {
        private val gson = Gson()

        /** Null for a row that is not a readable tool record. */
        fun parse(json: String): ToolRecord? = runCatching { gson.fromJson(json, ToolRecord::class.java) }.getOrNull()
    }
}
