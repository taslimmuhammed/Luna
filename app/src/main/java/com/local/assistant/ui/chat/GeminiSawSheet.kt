package com.local.assistant.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.local.assistant.hybrid.RouteReason
import com.local.assistant.hybrid.ServedBy
import com.local.assistant.memory.tools.AskView
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.Glass
import java.text.DateFormat
import java.util.Date

/**
 * An Auto chat's question to Gemini, opened from its chip or the reply's route line: exactly what
 * went, as the phone's model wrote it, and what it chose to tell Gemini about the user.
 * [onKeepLocal] switches the chat to Local; [onOpenLedger] opens every question sent, in "What
 * the cloud has seen".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeminiSawSheet(ask: AskView, onKeepLocal: () -> Unit, onOpenLedger: () -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val kept = ask.outcome == AskView.OUTCOME_KEPT
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Glass.Sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = CloudColors.Auto, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(if (kept) "Kept on this phone" else "What Gemini saw", style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
                    Text(subtitle(ask), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                }
            }

            Section(if (kept) "Would have gone" else "The question Luna wrote", Icons.Outlined.AutoAwesome) {
                SelectionContainer { Text(highlightPlaceholders(ask.sent), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextPrimary) }
            }
            ask.aboutUser?.takeIf { it.isNotBlank() }?.let { about ->
                Section("What Luna told it about you", Icons.Outlined.Person) {
                    SelectionContainer { Text(highlightPlaceholders(about), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextPrimary) }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ask.hidden?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = CloudColors.Auto) }
                Text(
                    "That was all of it. Luna writes each question itself and puts in only what the answer needs. Your messages, " +
                        "photos, files and health data are never sent as they are, and ID and card numbers never at all.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onKeepLocal) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = CloudColors.Local, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Keep this chat on the phone", color = CloudColors.Local)
                }
                TextButton(onClick = onOpenLedger) {
                    Icon(Icons.Outlined.Visibility, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("All sent", color = AppColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, icon: ImageVector, body: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = CloudColors.Auto, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, color = CloudColors.Auto)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CloudColors.AutoSoft)
                .padding(12.dp),
        ) { body() }
    }
}

/** "Planning · gemini-3.5-flash-lite · with Google Search · 4:12 PM". */
private fun subtitle(ask: AskView): String = listOfNotNull(
    RouteReason.of(ask.reason)?.label?.substringAfter("· ", missingDelimiterValue = "")?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() },
    when {
        ServedBy.of(ask.servedBy) == ServedBy.NANO -> "Gemini Nano on this phone stood in"
        else -> ask.model
    },
    "with Google Search".takeIf { ask.web },
    "Gemini couldn't answer".takeIf { ask.outcome == AskView.OUTCOME_FAILED },
    ask.at.takeIf { it > 0 }?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) },
).joinToString(" · ")
