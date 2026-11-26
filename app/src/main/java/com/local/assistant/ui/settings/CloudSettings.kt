package com.local.assistant.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.hybrid.ChatMode
import com.local.assistant.hybrid.HybridSetup
import com.local.assistant.ui.theme.AppColors

/** How the main page sums Cloud mode up. */
internal fun cloudSummary(hybrid: HybridSetup?, settings: SettingsStore): String = when {
    hybrid == null || !hybrid.configured -> "Not set up in this build · everything stays on this phone"
    !hybrid.cloudAvailable -> "Turned off for now"
    settings.newChatsMode == ChatMode.CLOUD && settings.cloudConsented -> "New chats start in Cloud"
    settings.newChatsMode == ChatMode.AUTO && settings.autoConsented -> "New chats start in Auto · Luna decides what Gemini sees"
    else -> "New chats start Local · switch any chat to Auto or Cloud"
}

/**
 * Cloud mode's page: where new chats start, Gemini Nano on the phone, and the record of what was
 * sent. Switching a chat itself is done in the chat.
 */
@Composable
internal fun CloudSettings(settings: SettingsStore, hybrid: HybridSetup?, onOpenLedger: () -> Unit) {
    if (hybrid == null || !hybrid.configured) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Cloud mode isn't set up in this build", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
            Text(
                "This build has no Firebase project, so every chat runs on this phone, as it always has.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
            )
        }
        return
    }
    var newChatsMode by remember { mutableStateOf(settings.newChatsMode) }
    val nano by hybrid.nano.collectAsStateWithLifecycle()
    LaunchedEffect(hybrid) { hybrid.refreshNano() }

    Text(
        "New chats start in",
        style = MaterialTheme.typography.bodyLarge,
        color = AppColors.TextPrimary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
    // A mode not yet agreed to can't be the default: a chat switched to it once shows what it sends.
    val agreed = mapOf(ChatMode.LOCAL to true, ChatMode.AUTO to settings.autoConsented, ChatMode.CLOUD to settings.cloudConsented)
    val shown = newChatsMode.takeIf { agreed[it] == true } ?: ChatMode.LOCAL
    listOf(
        ChatMode.LOCAL to ("Local" to "Everything stays on this phone."),
        ChatMode.AUTO to ("Auto" to "Luna answers on the phone and asks Gemini only questions it writes, with only what they need."),
        ChatMode.CLOUD to ("Cloud" to "Gemini answers, seeing only what's said in Cloud chats."),
    ).forEach { (mode, words) ->
        val (title, body) = words
        val can = agreed[mode] == true
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = shown == mode, enabled = can, role = Role.RadioButton) {
                    settings.newChatsMode = mode
                    newChatsMode = mode
                }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = shown == mode, onClick = null, enabled = can)
            Column(Modifier.padding(start = 8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = if (can) AppColors.TextPrimary else AppColors.TextSecondary)
                Text(
                    if (can) body else "Switch a chat to $title once first, to see what it sends.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }
        }
    }
    val asksToday by settings.observeCloudAsks { java.time.LocalDate.now().toString() }.collectAsStateWithLifecycle(0)
    NavRow(
        "What the cloud has seen",
        "Every request Cloud chats sent and every question Auto asked, word for word · Gemini today: $asksToday of ${hybrid.autoDailyAsks}",
        onOpenLedger,
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Gemini Nano on this phone", style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
        Text(
            when (nano) {
                HybridSetup.Nano.AVAILABLE -> "Ready. A Cloud chat with no connection is answered here instead, and short documents sent in Cloud chats are read here first."
                HybridSetup.Nano.DOWNLOADABLE -> "This phone can run it. Download it so Cloud chats still answer offline."
                HybridSetup.Nano.DOWNLOADING -> "Downloading through Android's AICore…"
                HybridSetup.Nano.UNAVAILABLE -> "This phone doesn't have it. Cloud chats need a connection; Local chats never do."
                HybridSetup.Nano.UNKNOWN -> "Checking…"
            },
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
        )
        if (nano == HybridSetup.Nano.DOWNLOADABLE) {
            TextButton(onClick = hybrid::downloadNano) { Text("Download Gemini Nano") }
        }
    }
}
