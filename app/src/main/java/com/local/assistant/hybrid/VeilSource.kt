package com.local.assistant.hybrid

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.local.assistant.memory.db.FactEntity
import com.local.assistant.memory.tools.Contact
import java.util.Locale

/**
 * The people Luna knows by name, for [AutoRouter]: a message naming one goes to the phone's model
 * first, which decides what of it Gemini needs. Gathered from what the phone already knows, and
 * never asked for: the contacts only if the app may already read them (no permission dialog for
 * this), the names in the user's memory (their own, their people's), and the people named in
 * their photos. Kept for a minute, as one Auto turn may ask twice.
 */
class VeilSource(
    private val context: Context,
    private val contacts: suspend () -> List<Contact>?,
    private val facts: suspend () -> List<FactEntity>,
    private val faces: suspend () -> List<String>,
) {

    @Volatile private var cached: Pair<Long, Veil.Known>? = null

    suspend fun known(): Veil.Known {
        cached?.let { (at, known) -> if (SystemClock.elapsedRealtime() - at < CACHE_MS) return known }
        val facts = runCatching { facts() }.getOrDefault(emptyList())
        val people = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                runCatching { contacts() }.getOrNull()?.forEach { add(it.name) }
            }
            facts.forEach { fact -> namesIn(fact)?.let(::add) }
            runCatching { faces() }.getOrDefault(emptyList()).forEach(::add)
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.ROOT) }
        val known = Veil.Known(people = people)
        cached = SystemClock.elapsedRealtime() to known
        return known
    }

    private fun namesIn(fact: FactEntity): String? {
        val attribute = fact.attribute.lowercase(Locale.ROOT)
        return fact.value.takeIf { attribute == "name" || attribute.endsWith("_name") || attribute == "full_name" || attribute == "nickname" }
    }

    private companion object {
        const val CACHE_MS = 60_000L
    }
}
