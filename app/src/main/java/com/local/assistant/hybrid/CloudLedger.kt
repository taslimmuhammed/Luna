package com.local.assistant.hybrid

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything that left the phone for the cloud model, word for word, kept on the phone for the
 * user to read ("What the cloud has seen"). Written by the one place a request is made
 * ([FirebaseBackend]) as it is sent, so it can't miss one: if it isn't here, it wasn't sent.
 *
 * The newest [MAX_ENTRIES], one JSON object a line in `files/cloud-ledger.jsonl`.
 */
class CloudLedger(private val file: File) {

    /**
     * One request. [kind]: what it was ([Kind]); [text]: what was sent that the cloud hadn't seen
     * already in this conversation; [attachments]: files sent with it, by kind ("1 image").
     */
    data class Entry(
        val at: Long,
        val chatId: Long?,
        val kind: String,
        val model: String,
        val text: String,
        val attachments: String? = null,
        /** Who answered: [ServedBy.key]; Gemini Nano when the phone answered instead. */
        val servedBy: String? = null,
    )

    enum class Kind(val key: String, val label: String) {
        OPENED("opened", "Conversation opened"),
        MESSAGE("message", "Message"),
        TOOL_RESULTS("tool_results", "Tool results"),
        TASK("task", "Background task"),
        SEARCH("search", "Web search"),
        /** An Auto chat's question, written by Luna, private details hidden ([Veil]). */
        ASK("ask", "Question from Luna"),
        ;

        companion object {
            fun of(key: String) = entries.firstOrNull { it.key == key }
        }
    }

    private val gson = Gson()
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Newest first. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    @Volatile
    private var loaded = false

    suspend fun load() = mutex.withLock { loadLocked() }

    private suspend fun loadLocked() {
        if (loaded) return
        _entries.value = withContext(Dispatchers.IO) {
            runCatching {
                if (!file.isFile) emptyList() else file.readLines().mapNotNull { line ->
                    runCatching { gson.fromJson(line, Entry::class.java) }.getOrNull()
                }.asReversed()
            }.getOrElse {
                Log.w(TAG, "Could not read the ledger", it)
                emptyList()
            }
        }
        loaded = true
    }

    suspend fun record(entry: Entry) = mutex.withLock {
        loadLocked()
        val kept = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        _entries.value = kept
        withContext(Dispatchers.IO) {
            runCatching {
                if (kept.size < MAX_ENTRIES) {
                    file.appendText(gson.toJson(entry) + "\n")
                } else {
                    file.writeText(kept.asReversed().joinToString("") { gson.toJson(it) + "\n" })
                }
            }.onFailure { Log.w(TAG, "Could not write the ledger", it) }
        }
    }

    /** "Forget what the cloud has seen" on the phone; what the cloud provider kept is theirs (Gemini API terms). */
    suspend fun clear() = mutex.withLock {
        _entries.value = emptyList()
        loaded = true
        withContext(Dispatchers.IO) { runCatching { file.delete() } }
    }

    private companion object {
        const val TAG = "CloudLedger"
        const val MAX_ENTRIES = 300
    }
}
