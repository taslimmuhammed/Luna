package com.local.assistant.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.local.assistant.hybrid.ServedBy
import com.local.assistant.ui.theme.AppColors

/** Cloud mode's own colour, so a Cloud message is never mistaken for a Local one. */
object CloudColors {
    val Cloud = Color(0xFF2563EB)
    val CloudSoft = Color(0x1A2563EB)
    val Local = Color(0xFF15803D)
    val LocalSoft = Color(0x1A15803D)
}

/**
 * Where the next message goes, above the composer: Local (on this phone) or Cloud (Gemini). Every
 * message keeps the mode it was sent in, so switching changes only what comes next. [available]
 * false: this build has no cloud, and the switch says so instead of offering it.
 */
@Composable
fun ModeSwitch(
    cloud: Boolean,
    available: Boolean,
    onChange: (Boolean) -> Unit,
    onOpenLedger: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(AppColors.SurfaceMuted)
                .padding(3.dp),
        ) {
            Segment("Local", Icons.Outlined.Lock, selected = !cloud, tint = CloudColors.Local) { if (cloud) onChange(false) }
            Segment("Cloud", Icons.Outlined.Cloud, selected = cloud, tint = CloudColors.Cloud, enabled = available) { if (!cloud) onChange(true) }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = when {
                cloud -> "Gemini sees only your Cloud messages"
                available -> "Stays on this phone"
                else -> "On this phone · Cloud isn't set up in this build"
            },
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (available) {
            Icon(
                Icons.Outlined.Visibility,
                contentDescription = "What the cloud has seen",
                tint = AppColors.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onOpenLedger)
                    .padding(6.dp)
                    .size(18.dp),
            )
        }
    }
}

@Composable
private fun Segment(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(if (selected) Color.White else Color.Transparent, label = "segment")
    val color = when {
        !enabled -> AppColors.Outline
        selected -> tint
        else -> AppColors.TextSecondary
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = "$label mode${if (selected) ", on" else ""}" }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Under a reply: which model wrote it, and where. */
@Composable
fun ServedByBadge(servedBy: ServedBy, modifier: Modifier = Modifier) {
    val color = if (servedBy.onDevice) CloudColors.Local else CloudColors.Cloud
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            when (servedBy) {
                ServedBy.CLOUD -> Icons.Outlined.Cloud
                ServedBy.APP -> Icons.Outlined.PhoneAndroid
                else -> Icons.Outlined.Lock
            },
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(servedBy.label, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** Under a message of the user's: where it went. */
@Composable
fun SentMark(cloud: Boolean, modifier: Modifier = Modifier) {
    val color = if (cloud) CloudColors.Cloud else CloudColors.Local
    Row(modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (cloud) Icons.Outlined.Cloud else Icons.Outlined.Lock, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text(if (cloud) "Sent to Gemini" else "Stayed on this phone", style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/**
 * Where a chat switched mode. To Cloud: what came before stays on the phone, said with how much.
 * To Local: from here it's the phone's alone again.
 */
@Composable
fun ModeDivider(toCloud: Boolean, before: Int) {
    val color = if (toCloud) CloudColors.Cloud else CloudColors.Local
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = color.copy(alpha = 0.3f))
            Icon(if (toCloud) Icons.Outlined.Cloud else Icons.Outlined.Lock, contentDescription = null, tint = color, modifier = Modifier.padding(horizontal = 8.dp).size(14.dp))
            Text(if (toCloud) (if (before == 0) "Cloud chat" else "Switched to Cloud") else "Back to Local", style = MaterialTheme.typography.labelMedium, color = color)
            Spacer(Modifier.width(8.dp))
            HorizontalDivider(Modifier.weight(1f), color = color.copy(alpha = 0.3f))
        }
        Text(
            if (toCloud && before == 0) {
                "Gemini sees only what's said in this chat in Cloud mode, never your Local chats."
            } else if (toCloud) {
                "The $before message${if (before == 1) "" else "s"} above stay${if (before == 1) "s" else ""} on this phone. Gemini sees only what's said from here in Cloud mode."
            } else {
                "From here, nothing leaves this phone."
            },
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * Shown the first time a chat is switched to Cloud, before anything is sent: what goes, what
 * never does, and how to check.
 */
@Composable
fun CloudConsentDialog(onAgree: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Cloud, contentDescription = null, tint = CloudColors.Cloud) },
        title = { Text("Switch this chat to Cloud?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Point(Icons.Outlined.Cloud, CloudColors.Cloud, "What goes to Gemini", "Only the messages you send while Cloud is on, and what you saved in Cloud chats (reminders, notes, facts). Google processes them under the Gemini API terms.")
                Point(Icons.Outlined.Lock, CloudColors.Local, "What never does", "Your Local chats and everything learned from them, contacts, messages, photos, files and health data. A Cloud chat can't search them: it's built without them, not just told to ignore them.")
                Point(Icons.Outlined.Visibility, AppColors.TextSecondary, "See for yourself", "\"What the cloud has seen\" lists every word that was sent, as it was sent.")
                Text("You can switch back to Local at any time. Messages keep the mode they were sent in.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            }
        },
        confirmButton = { TextButton(onClick = onAgree) { Text("Use Cloud") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Stay Local") } },
    )
}

@Composable
private fun Point(icon: ImageVector, tint: Color, title: String, body: String) {
    Row {
        Box(Modifier.padding(top = 2.dp)) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
    }
}
