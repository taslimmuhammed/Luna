package com.local.assistant.memory.db

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.local.assistant.data.db.AppDatabase
import com.local.assistant.memory.core.FactDecision
import com.local.assistant.memory.core.FactKeys
import com.local.assistant.memory.core.FactUpsertPolicy
import com.local.assistant.memory.core.FactWrite
import com.local.assistant.memory.prompt.AgendaItem
import com.local.assistant.memory.retrieval.FactSource
import com.local.assistant.memory.tools.EventTimes
import com.local.assistant.memory.tools.PeopleMemory
import com.local.assistant.memory.tools.PersonHints
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId

/** The outcome of a fact write, with the row as it was before — which is all undo needs. */
data class FactWriteResult(val decision: FactDecision, val previous: FactEntity?) {
    /** The row as it now stands, or null if nothing was written. */
    val fact: FactEntity?
        get() = when (decision) {
            is FactDecision.Insert -> decision.fact
            is FactDecision.Update -> decision.fact
            is FactDecision.Confirm -> decision.fact
            is FactDecision.Skip -> null
        }
}

/**
 * The structured memory store on Room.
 *
 * Every write is one transaction: the read that decides it and the write itself cannot be split
 * by a concurrent writer, so two saves of the same key never both insert. Calls made inside
 * [transaction] join it, so a tool's whole effect commits together.
 *
 * [cloudOnly]: this is a Cloud chat's view of memory (`hybrid/CloudScope`). Every read sees only
 * what was said in Cloud chats ([FactEntity.cloud] and the rest); every write is marked as said
 * there. Nothing a Local chat told it can come back out of this one, whatever the cloud model asks.
 */
class MemoryRepository(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    val cloudOnly: Boolean = false,
) : MemoryStore, FactSource, PeopleMemory {
    private val facts = database.factDao()
    private val agendaDao = database.agendaDao()
    private val sessions = database.sessionDao()

    override suspend fun <T> transaction(block: suspend () -> T): T =
        database.useWriterConnection { connection -> connection.immediateTransaction { block() } }

    /**
     * Records `subject.attribute = value` under the upsert rules in [FactUpsertPolicy]. Keys are
     * normalised here, so callers pass them as the model or the user wrote them.
     */
    override suspend fun saveFact(
        subject: String,
        attribute: String,
        value: String,
        origin: FactOrigin,
        pin: Boolean,
        statedAt: Long?,
        sourceMessageId: Long?,
    ): FactWriteResult {
        val write = FactWrite(
            subject = resolveSubject(subject),
            attribute = FactKeys.attribute(attribute),
            value = FactKeys.value(value),
            pin = pin,
            origin = origin,
            statedAt = statedAt ?: clock(),
            sourceMessageId = sourceMessageId,
        )
        return transaction {
            val existing = facts.find(write.subject, write.attribute)
            val forgottenAt = facts.forgottenAt(write.subject, write.attribute)
            val decided = FactUpsertPolicy.decide(existing, write, forgottenAt, clock())
            // Said in a Cloud chat, the value is one the cloud has seen; said in a Local one, a new
            // value is the phone's alone, while restating the same value leaves it as it was.
            val tagged = when (decided) {
                is FactDecision.Insert -> FactDecision.Insert(decided.fact.copy(cloud = cloudOnly))
                is FactDecision.Update -> FactDecision.Update(decided.fact.copy(cloud = cloudOnly))
                is FactDecision.Confirm -> FactDecision.Confirm(decided.fact.copy(cloud = cloudOnly || decided.fact.cloud))
                is FactDecision.Skip -> decided
            }
            val decision = when (val d = tagged) {
                is FactDecision.Insert -> FactDecision.Insert(d.fact.copy(id = facts.insert(d.fact)))
                is FactDecision.Update -> d.also { facts.update(it.fact) }
                is FactDecision.Confirm -> d.also { if (it.fact != existing) facts.update(it.fact) }
                is FactDecision.Skip -> d
            }
            FactWriteResult(decision, existing)
        }
    }

    override suspend fun restoreFact(previous: FactEntity?, written: FactEntity): Boolean = transaction {
        val current = facts.byId(written.id)
        if (current != written) return@transaction false
        if (previous == null) facts.delete(written.id) else facts.update(previous)
        true
    }

    /**
     * Deletes a fact, or every fact about a subject when [attribute] is null, and leaves a
     * tombstone so extraction from older messages cannot bring it back.
     */
    override suspend fun forgetFacts(subject: String, attribute: String?): ForgetResult {
        val subjectKey = resolveSubject(subject)
        val attributeKey = attribute?.takeIf { it.isNotBlank() }?.let(FactKeys::attribute).orEmpty()
        return transaction {
            val removed = if (cloudOnly) {
                // A Cloud chat can only forget what Cloud chats were told: a Local fact isn't
                // there for it to see, so it isn't there for it to remove either.
                val found = if (attributeKey.isEmpty()) facts.bySubject(subjectKey) else listOfNotNull(facts.find(subjectKey, attributeKey))
                found.filter { it.cloud }.onEach { facts.delete(it.id) }
            } else if (attributeKey.isEmpty()) {
                facts.bySubject(subjectKey).also { facts.deleteSubject(subjectKey) }
            } else {
                listOfNotNull(facts.find(subjectKey, attributeKey)).also { facts.deleteKey(subjectKey, attributeKey) }
            }
            val previous = facts.tombstone(subjectKey, attributeKey)
            val now = clock()
            facts.insertTombstone(ForgottenEntity(subjectKey, attributeKey, now))
            ForgetResult(subjectKey, attributeKey, removed, now, previous)
        }
    }

    override suspend fun unforget(result: ForgetResult) = transaction {
        facts.insertAll(result.removed)
        val previous = result.previousTombstone
        if (previous == null) {
            facts.deleteTombstone(result.subject, result.attribute)
        } else {
            facts.insertTombstone(ForgottenEntity(result.subject, result.attribute, previous))
        }
    }

    /** The subject key a name files under: normalised, then through any alias the user merged. */
    private suspend fun resolveSubject(raw: String): String {
        val key = FactKeys.subject(raw)
        return facts.subjectForAlias(key) ?: key
    }

    /**
     * Moves every fact about [from] under [into] and remembers [from] as a name for it. Where
     * both have the same attribute, the user's own edit wins, then the newer statement. Tombstones
     * move too, so nothing forgotten under the old name comes back under the new one.
     */
    suspend fun mergeSubjects(from: String, into: String): Int = transaction {
        if (from == into || from == FactKeys.USER) return@transaction 0
        var moved = 0
        for (fact in facts.bySubject(from)) {
            val existing = facts.find(into, fact.attribute)
            if (existing == null) {
                facts.update(fact.copy(subject = into))
            } else {
                val keepIncoming = when {
                    existing.origin == FactOrigin.USER_EDIT && fact.origin != FactOrigin.USER_EDIT -> false
                    fact.origin == FactOrigin.USER_EDIT && existing.origin != FactOrigin.USER_EDIT -> true
                    else -> fact.statedAt > existing.statedAt
                }
                facts.delete(fact.id)
                if (keepIncoming) facts.update(fact.copy(id = existing.id, subject = into, core = existing.core || fact.core))
            }
            moved++
        }
        for (tombstone in facts.tombstonesFor(from)) {
            val current = facts.tombstone(into, tombstone.attribute)
            if (current == null || current < tombstone.forgottenAt) facts.insertTombstone(tombstone.copy(subject = into))
        }
        facts.deleteTombstonesFor(from)
        facts.repointAliases(from, into)
        facts.insertAlias(SubjectAliasEntity(alias = from, subject = into))
        moved
    }

    /**
     * Files every fact under its attribute's current key — for rows written before a synonym was
     * added ("occupation" is now `job`). Where both exist, the user's own edit wins, then the
     * newer statement. Returns how many rows changed.
     */
    suspend fun normalizeAttributes(): Int = transaction {
        var changed = 0
        for (fact in facts.all()) {
            val key = FactKeys.attribute(fact.attribute)
            if (key.isEmpty() || key == fact.attribute) continue
            val existing = facts.find(fact.subject, key)
            val moved = fact.copy(attribute = key, category = com.local.assistant.memory.core.FactCategorizer.categorize(fact.subject, key))
            if (existing == null) {
                facts.update(moved)
            } else {
                val keepMoved = when {
                    existing.origin == FactOrigin.USER_EDIT && fact.origin != FactOrigin.USER_EDIT -> false
                    fact.origin == FactOrigin.USER_EDIT && existing.origin != FactOrigin.USER_EDIT -> true
                    else -> fact.statedAt > existing.statedAt
                }
                facts.delete(fact.id)
                if (keepMoved) facts.update(moved.copy(id = existing.id, core = existing.core || fact.core))
            }
            changed++
        }
        changed
    }

    /** Applies every certain merge (see `AliasPlanner.merges`). Returns how many facts moved. */
    suspend fun mergeCertainAliases(): Int {
        val aliases = facts.aliases().associate { it.alias to it.subject }
        val plan = com.local.assistant.memory.core.AliasPlanner.merges(facts.subjects(), aliases)
        return plan.entries.sumOf { (from, into) -> mergeSubjects(from, into) }
    }

    override suspend fun searchFacts(words: List<String>, limit: Int): List<FactEntity> {
        val match = ftsMatch(words) ?: return emptyList()
        return if (cloudOnly) facts.searchAllCloud(match, limit) else facts.searchAll(match, limit)
    }

    override suspend fun coreFacts(): List<FactEntity> = if (cloudOnly) facts.coreFactsCloud() else facts.coreFacts()

    override suspend fun subjectsForAliases(aliases: List<String>): List<String> =
        if (aliases.isEmpty()) emptyList() else facts.subjectsForAliases(aliases)

    override suspend fun nonCoreAbout(subjects: List<String>, limit: Int): List<FactEntity> = when {
        subjects.isEmpty() -> emptyList()
        cloudOnly -> facts.nonCoreAboutCloud(subjects, limit)
        else -> facts.nonCoreAbout(subjects, limit)
    }

    override suspend fun searchNonCore(match: String, limit: Int): List<FactEntity> =
        try {
            if (cloudOnly) facts.searchCloud(match, limit) else facts.search(match, limit)
        } catch (e: Exception) {
            // Recall is best-effort; a query the FTS parser rejects just finds nothing.
            emptyList()
        }

    /**
     * What memory knows about [who] that helps find them in the phone: a number or email saved
     * for them, a name ("my mother's name is Lakshmi"), the other words the user calls them by,
     * and — for "my dentist" or "the CA" — the name filed on the user's own fact.
     */
    override suspend fun hints(who: String): PersonHints {
        // Finding someone in the phone's contacts is a Local chat's to do.
        if (cloudOnly) return PersonHints.NONE
        val subject = resolveSubject(who)
        if (subject.isEmpty() || subject == FactKeys.USER) return PersonHints.NONE
        val about = facts.bySubject(subject)
        val role = facts.find(FactKeys.USER, FactKeys.attribute(who))
        val names = buildList {
            about.filter { it.attribute in NAME_ATTRIBUTES }.forEach { add(it.value) }
            role?.value?.let(::add)
            facts.aliases().filter { it.subject == subject }.forEach { add(it.alias.replace('_', ' ')) }
            FactKeys.relationWords(subject).forEach(::add)
        }.distinctBy { it.lowercase() }
        return PersonHints(
            names = names,
            phone = about.firstOrNull { it.attribute == "phone" }?.value,
            email = about.firstOrNull { it.attribute == "email" }?.value,
        )
    }

    fun observeCoreFacts(): Flow<List<FactEntity>> = facts.observeCoreFacts()

    override suspend fun insertTask(task: TaskEntity): Long = agendaDao.insertTask(if (cloudOnly) task.copy(cloud = true) else task)

    override suspend fun task(id: Long): TaskEntity? = if (cloudOnly) agendaDao.taskCloud(id) else agendaDao.task(id)

    override suspend fun updateTask(task: TaskEntity) {
        // Only one of its own: a Local reminder isn't the Cloud chat's to change.
        if (cloudOnly) {
            if (agendaDao.taskCloud(task.id) == null) return
            agendaDao.updateTask(task.copy(cloud = true))
            return
        }
        // New words from a Local chat are the phone's alone: the reminder leaves Cloud chats' sight.
        // Done, snoozed or moved, it keeps its mark.
        val stored = agendaDao.task(task.id)
        val sameWords = stored != null && stored.title == task.title && stored.notes == task.notes && stored.place?.name == task.place?.name
        agendaDao.updateTask(task.copy(cloud = stored?.cloud == true && sameWords))
    }

    override suspend fun deleteTask(id: Long) {
        if (cloudOnly && agendaDao.taskCloud(id) == null) return
        agendaDao.deleteTask(id)
    }

    override suspend fun openTasks(): List<TaskEntity> = if (cloudOnly) agendaDao.allOpenTasksCloud() else agendaDao.allOpenTasks()

    override suspend fun tasksDueBetween(from: Long, to: Long): List<TaskEntity> =
        if (cloudOnly) agendaDao.tasksDueBetweenCloud(from, to) else agendaDao.tasksDueBetween(from, to)

    override suspend fun tasksMentioning(word: String, limit: Int): List<TaskEntity> =
        if (cloudOnly) agendaDao.tasksMentioningCloud(word, limit) else agendaDao.tasksMentioning(word, limit)

    override suspend fun insertEvent(event: EventEntity): Long = agendaDao.insertEvent(if (cloudOnly) event.copy(cloud = true) else event)

    override suspend fun event(id: Long): EventEntity? = if (cloudOnly) agendaDao.eventCloud(id) else agendaDao.event(id)

    override suspend fun updateEvent(event: EventEntity) {
        if (cloudOnly) {
            if (agendaDao.eventCloud(event.id) == null) return
            agendaDao.updateEvent(event.copy(cloud = true))
            return
        }
        val stored = agendaDao.event(event.id)
        val sameWords = stored != null && stored.title == event.title && stored.notes == event.notes
        agendaDao.updateEvent(event.copy(cloud = stored?.cloud == true && sameWords))
    }

    override suspend fun deleteEvent(id: Long) {
        if (cloudOnly && agendaDao.eventCloud(id) == null) return
        agendaDao.deleteEvent(id)
    }

    override suspend fun eventsBetween(from: Long, to: Long): List<EventEntity> =
        if (cloudOnly) agendaDao.eventsBetweenCloud(from, to) else agendaDao.eventsBetween(from, to)

    override suspend fun eventsMentioning(word: String, limit: Int): List<EventEntity> =
        if (cloudOnly) agendaDao.eventsMentioningCloud(word, limit) else agendaDao.eventsMentioning(word, limit)

    /**
     * The next [limit] open tasks and upcoming events, soonest first; undated tasks last. A
     * repeating event appears at its next occurrence.
     */
    suspend fun agenda(now: Long, limit: Int): List<AgendaItem> {
        val open = if (cloudOnly) agendaDao.openTasksCloud(limit) else agendaDao.openTasks(limit)
        val tasks = open.map { AgendaItem(it.title, it.dueAt, ref = "task ${it.id}", place = it.place?.name) }
        val upcoming = if (cloudOnly) agendaDao.eventsBetweenCloud(now, Long.MAX_VALUE) else agendaDao.eventsBetween(now, Long.MAX_VALUE)
        val events = upcoming.mapNotNull { event ->
            EventTimes.nextStart(event, now, zone())?.let { AgendaItem(event.title, it, event.allDay, ref = "event ${event.id}") }
        }
        return (tasks + events)
            .sortedWith(compareBy<AgendaItem> { it.at == null }.thenBy { it.at })
            .take(limit)
    }

    /** The newest finished session summary from any chat, for a conversation's section C. */
    /**
     * The newest finished session summary from any chat, for a conversation's section C. None for
     * a Cloud chat: summaries are written from every chat's messages, Local ones too.
     */
    suspend fun latestSessionSummary(): String? = if (cloudOnly) null else sessions.latestSummarised()?.summary

    companion object {
        private val NAME_ATTRIBUTES = setOf("name", "full_name", "nickname", "first_name")

        /** Words as an FTS4 query: any of them, each as a prefix. Null if nothing is left. */
        fun ftsMatch(words: List<String>): String? =
            words.map { word -> word.filter { it.isLetterOrDigit() } }
                .filter { it.length >= 2 }
                .distinct()
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" OR ") { "$it*" }
    }
}
