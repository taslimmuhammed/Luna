package com.local.assistant.ui.settings

import androidx.compose.foundation.layout.Column
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
import com.local.assistant.hybrid.HybridSetup
import com.local.assistant.ui.theme.AppColors

/** How the main page sums Cloud mode up. */
internal fun cloudSummary(hybrid: HybridSetup?, settings: SettingsStore): String = when {
    hybrid == null || !hybrid.configured -> "Not set up in this build · everything stays on this phone"
    !hybrid.cloudAvailable -> "Turned off for now"
    settings.newChatsCloud -> "New chats start in Cloud"
    else -> "New chats start Local · switch any chat to Cloud"
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
    var newChatsCloud by remember { mutableStateOf(settings.newChatsCloud) }
    val nano by hybrid.nano.collectAsStateWithLifecycle()
    LaunchedEffect(hybrid) { hybrid.refreshNano() }

    SwitchRow(
        "New chats start in Cloud",
        if (settings.cloudConsented) {
            "Off: every new chat starts on this phone, and you switch it to Cloud when you want Gemini."
        } else {
            "Switch a chat to Cloud once first, to see what it sends."
        },
        checked = newChatsCloud && settings.cloudConsented,
    ) { on ->
        if (settings.cloudConsented) {
            settings.newChatsCloud = on
            newChatsCloud = on
        }
    }
    NavRow("What the cloud has seen", "Every request Cloud chats sent, word for word, and what they can recall", onOpenLedger)
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
