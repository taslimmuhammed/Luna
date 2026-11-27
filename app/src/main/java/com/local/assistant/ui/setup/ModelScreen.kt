package com.local.assistant.ui.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.assistant.llm.EngineState
import com.local.assistant.llm.NanoBackend
import com.local.assistant.model.OldChatModel
import com.local.assistant.model.Transfer
import com.local.assistant.model.TransferKind
import com.local.assistant.model.formatBytes
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.glass
import com.local.assistant.ui.theme.GlassIconButton
import kotlinx.coroutines.launch

/**
 * The chat model's page in Settings: Gemini Nano, kept by Android (AICore), with memory search's
 * model below it, and the old Gemma file while it's still on the phone, to delete.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(
    nano: NanoBackend,
    oldModel: OldChatModel,
    onBack: (() -> Unit)?,
    memorySearch: MemorySearchControls? = null,
) {
    val engineState by nano.state.collectAsStateWithLifecycle()
    val oldBytes by oldModel.bytes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        nano.answersOnPhone()
        oldModel.refresh()
    }

    Scaffold(
        containerColor = AppColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("Model", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    if (onBack != null) {
                        GlassIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack, Modifier.padding(start = 10.dp, end = 6.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppColors.Background,
                    titleContentColor = AppColors.TextPrimary,
                    navigationIconContentColor = AppColors.TextPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                // One card of glass, as a settings page.
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp)
                .glass()
                .padding(horizontal = 18.dp),
        ) {
            Text(
                text = "Gemini Nano",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.TextPrimary,
                modifier = Modifier.padding(top = 18.dp),
            )
            Text(
                text = "Google's model on the phone, kept up to date by Android. Local chats and the assistant " +
                    "run on it with no internet, and nothing you say leaves the phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            NanoStatus(engineState, onRetry = { scope.launch { nano.recheck() } })

            if (memorySearch != null) MemorySearchSection(memorySearch)

            if (oldBytes > 0) OldModelSection(oldBytes, onDelete = { scope.launch { oldModel.delete() } })
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NanoStatus(engineState: EngineState, onRetry: () -> Unit) {
    Column(Modifier.padding(top = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (engineState is EngineState.Loading || engineState == EngineState.Idle) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp).padding(end = 0.dp),
                    strokeWidth = 2.dp,
                    color = AppColors.TextSecondary,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = when (engineState) {
                    is EngineState.Ready -> "On this phone · ${engineState.contextTokens / 1024}K window"
                    is EngineState.Loading -> "Android is downloading it…"
                    EngineState.Idle -> "Checking…"
                    EngineState.NoModel -> "Not on this phone"
                    is EngineState.Failed -> "Couldn't check"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextPrimary,
            )
        }
        when (engineState) {
            EngineState.NoModel -> Text(
                text = "This phone doesn't have Gemini Nano, so local chats and the assistant can't run here. " +
                    "Chats in Cloud mode still work.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            is EngineState.Failed -> {
                Text(
                    text = engineState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.Danger,
                    modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(onClick = onRetry) { Text("Try again") }
            }
            else -> Unit
        }
    }
}

/** Gemma 4 E4B's file, from before Nano: what it takes, and a Delete that asks first. */
@Composable
private fun OldModelSection(bytes: Long, onDelete: () -> Unit) {
    Column(Modifier.padding(top = 28.dp)) {
        HorizontalDivider(color = AppColors.Border)
        Text(
            text = "Old chat model · ${OldChatModel.DISPLAY_NAME}",
            style = MaterialTheme.typography.titleSmall,
            color = AppColors.TextPrimary,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = "${formatBytes(bytes)} on this phone. Luna runs on Gemini Nano now and doesn't use it.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        var confirming by remember { mutableStateOf(false) }
        if (confirming) {
            Text(
                text = "Delete the file? It can't be undone.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextPrimary,
                modifier = Modifier.padding(top = 16.dp),
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { confirming = false; onDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Danger),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Delete") }
                OutlinedButton(
                    onClick = { confirming = false },
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Keep") }
            }
        } else {
            OutlinedButton(
                onClick = { confirming = true },
                modifier = Modifier.padding(top = 16.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Delete ${formatBytes(bytes)}", color = AppColors.Danger)
            }
        }
    }
}

@Composable
internal fun TransferSection(transfer: Transfer, onCancel: () -> Unit) {
    Column(Modifier.padding(top = 28.dp)) {
        Text(
            text = when (transfer.kind) {
                TransferKind.DOWNLOAD -> "Downloading"
                TransferKind.IMPORT -> "Importing"
            },
            style = MaterialTheme.typography.labelLarge,
            color = AppColors.TextPrimary,
        )
        LinearProgressIndicator(
            progress = { transfer.fraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            color = AppColors.Accent,
            trackColor = AppColors.SurfaceMuted,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${formatBytes(transfer.bytesDone)} / ${formatBytes(transfer.bytesTotal)}",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
            )
            transfer.etaSeconds?.let {
                Text(
                    text = "${formatBytes(transfer.bytesPerSecond)}/s · ${formatDuration(it)} left",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                )
            }
        }
        if (transfer.kind == TransferKind.DOWNLOAD) {
            Text(
                text = "You can leave the app: it keeps downloading, with its progress in a notification.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        // All of it is here while it's checked. A download keeps what arrived: Pause, and the
        // next Download carries on from there.
        if (!transfer.checking) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.padding(top = 20.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(if (transfer.kind == TransferKind.DOWNLOAD) "Pause" else "Cancel")
            }
        }
    }
}

private fun formatDuration(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds}s"
}
