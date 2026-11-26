package com.local.assistant.ui.privacy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.assistant.data.repo.ChatRepository
import com.local.assistant.hybrid.CloudLedger
import com.local.assistant.memory.db.AgendaDao
import com.local.assistant.memory.db.FactDao
import com.local.assistant.memory.db.UserNoteDao
import com.local.assistant.ui.chat.CloudColors
import com.local.assistant.ui.chat.highlightPlaceholders
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.GlassCard
import com.local.assistant.ui.theme.GlassIconButton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What the "What the cloud has seen" screen shows and changes: the [ledger] of every request, how
 * many messages stayed on the phone, and what Cloud chats have learned, each of which can be made
 * the phone's alone again ("Keep on phone").
 */
class CloudControls(
    val ledger: CloudLedger,
    private val chats: ChatRepository,
    private val facts: FactDao,
    private val agenda: AgendaDao,
    private val notes: UserNoteDao,
    /** Something Cloud chats could see is now Local only: their prefix changes. */
    private val onChanged: () -> Unit,
    /** Questions Auto chats sent Gemini today, and how many they may a day. */
    val asksToday: Flow<Int> = flowOf(0),
    val asksPerDay: () -> Int = { 0 },
) {
    /** One thing a Cloud chat can recall, with how to take it back. */
    data class Learned(val kind: String, val text: String, val keepLocal: suspend () -> Unit)

    val localMessages: Flow<Int> = chats.observeLocalMessageCount()

    val cloudMessages: Flow<Int> = chats.observeCloudMessageCount()

    val learned: Flow<List<Learned>> = combine(
        facts.observeCloud(),
        agenda.observeCloudTasks(),
        agenda.observeCloudEvents(),
        notes.observeCloud(),
    ) { facts, tasks, events, notes ->
        facts.map { fact ->
            Learned("Fact", "${fact.subject.replace('_', ' ')} · ${fact.attribute.replace('_', ' ')}: ${fact.value}") {
                this.facts.keepLocal(fact.id)
            }
        } + tasks.map { task ->
            Learned("Reminder", task.title) { agenda.keepTaskLocal(task.id) }
        } + events.map { event ->
            Learned("Event", event.title) { agenda.keepEventLocal(event.id) }
        } + notes.map { note ->
            Learned("Note", note.title) { this.notes.keepLocal(note.id) }
        }
    }

    suspend fun keepLocal(item: Learned) {
        item.keepLocal()
        onChanged()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudLedgerScreen(controls: CloudControls, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(controls) { controls.ledger.load() }
    val entries by controls.ledger.entries.collectAsStateWithLifecycle()
    val local by controls.localMessages.collectAsStateWithLifecycle(initialValue = 0)
    val cloud by controls.cloudMessages.collectAsStateWithLifecycle(initialValue = 0)
    val learned by controls.learned.collectAsStateWithLifecycle(initialValue = emptyList())
    val asksToday by controls.asksToday.collectAsStateWithLifecycle(initialValue = 0)
    var clearing by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = AppColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("What the cloud has seen", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    GlassIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack, Modifier.padding(start = 10.dp, end = 6.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppColors.Background,
                    titleContentColor = AppColors.TextPrimary,
                    navigationIconContentColor = AppColors.TextPrimary,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Count(Modifier.weight(1f), local, "stayed on this phone", Icons.Outlined.Lock, CloudColors.Local)
                    Count(Modifier.weight(1f), cloud, "sent to Gemini", Icons.Outlined.Cloud, CloudColors.Cloud)
                }
                val perDay = controls.asksPerDay()
                if (perDay > 0) {
                    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = CloudColors.Auto, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Gemini today: $asksToday of $perDay", style = MaterialTheme.typography.titleSmall, color = CloudColors.Auto, modifier = Modifier.weight(1f))
                        }
                        LinearProgressIndicator(
                            progress = { (asksToday.toFloat() / perDay).coerceIn(0f, 1f) },
                            color = CloudColors.Auto,
                            trackColor = CloudColors.AutoSoft,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        )
                        Text(
                            "Questions Auto chats asked Gemini. Past the day's allowance, Auto answers on this phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextSecondary,
                        )
                    }
                }
                Text(
                    "Local chats, and everything learned from them, never leave this phone: Cloud chats are built without them. " +
                        "Auto chats send only the questions Luna writes. " +
                        "Below is everything Cloud chats can recall, and every request that went out, word for word.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            item { Header("What Cloud chats can recall") }
            if (learned.isEmpty()) {
                item { Quiet("Nothing yet. Facts, reminders and notes you make in a Cloud chat show here.") }
            }
            items(learned, key = { "${it.kind}:${it.text}" }) { item ->
                GlassCard(Modifier.padding(horizontal = 16.dp), padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.kind, style = MaterialTheme.typography.labelSmall, color = CloudColors.Cloud)
                            Text(item.text, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextPrimary)
                        }
                        TextButton(onClick = { scope.launch { controls.keepLocal(item) } }) { Text("Keep on phone") }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Every request, as sent", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (entries.isNotEmpty()) TextButton(onClick = { clearing = true }) { Text("Clear list") }
                }
            }
            if (entries.isEmpty()) {
                item { Quiet("Nothing has been sent. Everything so far stayed on this phone.") }
            }
            items(entries) { entry -> EntryCard(entry) }
        }
    }

    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text("Clear this list?") },
            text = { Text("It clears the record on this phone. It doesn't change your chats, or what Google processed when it was sent.") },
            confirmButton = {
                TextButton(onClick = {
                    clearing = false
                    scope.launch { controls.ledger.clear() }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { clearing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Count(modifier: Modifier, n: Int, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    GlassCard(modifier, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("$n", style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.SemiBold)
        }
        Text("message${if (n == 1) "" else "s"} $label", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
}

@Composable
private fun Quiet(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun EntryCard(entry: CloudLedger.Entry) {
    var open by remember { mutableStateOf(false) }
    val ask = entry.kind == CloudLedger.Kind.ASK.key
    GlassCard(Modifier.padding(horizontal = 16.dp).clickable { open = !open }, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                CloudLedger.Kind.of(entry.kind)?.label ?: entry.kind,
                style = MaterialTheme.typography.labelLarge,
                color = if (ask) CloudColors.Auto else CloudColors.Cloud,
                modifier = Modifier.weight(1f),
            )
            Text(TIME.format(Instant.ofEpochMilli(entry.at).atZone(ZoneId.systemDefault())), style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
        }
        Text(
            listOfNotNull(entry.model, entry.attachments?.let { "with $it" }).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextSecondary,
        )
        Spacer(Modifier.size(4.dp))
        SelectionContainer {
            val text = if (open) entry.text else entry.text.take(PREVIEW_CHARS) + if (entry.text.length > PREVIEW_CHARS) "…" else ""
            Text(
                if (ask) highlightPlaceholders(text) else androidx.compose.ui.text.AnnotatedString(text),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = AppColors.TextPrimary,
            )
        }
    }
}

private const val PREVIEW_CHARS = 220
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")
