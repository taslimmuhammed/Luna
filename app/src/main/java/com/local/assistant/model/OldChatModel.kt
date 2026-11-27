package com.local.assistant.model

import android.content.Context
import com.local.assistant.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gemma 4 E4B, the chat model before Gemini Nano (27 Nov 2026). Its 3.66 GB file is still on
 * phones that downloaded it, along with any part of a download that stopped; nothing reads them
 * now. Settings › Model offers to delete them, and only the user's tap does.
 */
class OldChatModel(private val context: Context, private val settings: SettingsStore) {

    private val _bytes = MutableStateFlow(0L)

    /** What the old files take, 0 once there are none. */
    val bytes: StateFlow<Long> = _bytes.asStateFlow()

    private fun files(): List<File> {
        val dir = File(context.filesDir, DIRECTORY)
        return listOfNotNull(settings.modelPath?.let(::File), File(dir, FILE_NAME), File(dir, FILE_NAME + PART_SUFFIX))
            .distinctBy { it.absolutePath }
            .filter { it.isFile }
    }

    /** Looks again at what's on disk. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        _bytes.value = files().sumOf { it.length() }
    }

    suspend fun delete() = withContext(Dispatchers.IO) {
        // The first setup counted as done by this file stays done ([SettingsStore.setupDone]).
        settings.setupDone
        files().forEach { it.delete() }
        settings.modelPath = null
        _bytes.value = files().sumOf { it.length() }
    }

    companion object {
        const val DISPLAY_NAME = "Gemma 4 E4B"
        private const val FILE_NAME = "gemma-4-E4B-it.litertlm"
        private const val DIRECTORY = "models"
        private const val PART_SUFFIX = ".part"
    }
}
