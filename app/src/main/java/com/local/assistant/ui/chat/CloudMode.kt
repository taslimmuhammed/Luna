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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.local.assistant.hybrid.ChatMode
import com.local.assistant.hybrid.RouteReason
import com.local.assistant.hybrid.ServedBy
import com.local.assistant.ui.theme.AppColors

/** Cloud mode's own colour, so a Cloud message is never mistaken for a Local one. */
object CloudColors {
    val Cloud = Color(0xFF2563EB)
    val CloudSoft = Color(0x1A2563EB)
    val Local = Color(0xFF15803D)
    val LocalSoft = Color(0x1A15803D)

    /** Auto: the phone's model, asking Gemini questions it writes. */
    val Auto = Color(0xFF7C3AED)
    val AutoSoft = Color(0x1A7C3AED)

    fun of(mode: ChatMode): Color = when (mode) {
        ChatMode.LOCAL -> Local
        ChatMode.AUTO -> Auto
        ChatMode.CLOUD -> Cloud
    }
}

fun ChatMode.icon(): ImageVector = when (this) {
    ChatMode.LOCAL -> Icons.Outlined.Lock
    ChatMode.AUTO -> Icons.Outlined.AutoAwesome
    ChatMode.CLOUD -> Icons.Outlined.Cloud
}

/**
 * Where the next message goes, above the composer: Local (on this phone), Auto (Luna decides
 * what goes to Gemini) or Cloud (Gemini). Every message keeps the mode it was sent in, so
 * switching changes only what comes next. [available] false: this build has no cloud, and the
 * switch says so instead of offering it. [asksToday] of [asksPerDay]: Auto's questions today.
 */
@Composable
fun ModeSwitch(
    mode: ChatMode,
    available: Boolean,
    onChange: (ChatMode) -> Unit,
    onOpenLedger: () -> Unit,
    modifier: Modifier = Modifier,
    asksToday: Int = 0,
    asksPerDay: Int = 0,
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
            Segment("Local", ChatMode.LOCAL.icon(), selected = mode == ChatMode.LOCAL, tint = CloudColors.Local) { if (mode != ChatMode.LOCAL) onChange(ChatMode.LOCAL) }
            Segment("Auto", ChatMode.AUTO.icon(), selected = mode == ChatMode.AUTO, tint = CloudColors.Auto, enabled = available) { if (mode != ChatMode.AUTO) onChange(ChatMode.AUTO) }
            Segment("Cloud", ChatMode.CLOUD.icon(), selected = mode == ChatMode.CLOUD, tint = CloudColors.Cloud, enabled = available) { if (mode != ChatMode.CLOUD) onChange(ChatMode.CLOUD) }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = when {
                !available -> "On this phone · Cloud isn't set up in this build"
                mode == ChatMode.CLOUD -> "Gemini sees only your Cloud messages"
                mode == ChatMode.AUTO && asksPerDay > 0 && asksToday >= asksPerDay -> "Today's Gemini questions are used up · on this phone"
                mode == ChatMode.AUTO -> "Luna decides what Gemini sees"
                else -> "Stays on this phone"
            },
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
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
            .padding(horizontal = 10.dp, vertical = 6.dp),
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
                ServedBy.CLOUD, ServedBy.ASKED_GEMINI -> Icons.Outlined.Cloud
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

/**
 * Under an Auto chat's reply: where it ran and why, in a few words ("On this phone · used your
 * messages", "Asked Gemini · long writing"). [servedBy] says when Gemini Nano stood in for the
 * cloud. Tapping it, when the reply asked Gemini, opens what Gemini saw ([onOpen]).
 */
@Composable
fun RouteBadge(reason: RouteReason, servedBy: ServedBy?, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    val cloud = reason.cloud && servedBy != ServedBy.NANO
    val color = if (cloud) CloudColors.Auto else CloudColors.Local
    val label = when {
        reason.cloud && servedBy == ServedBy.NANO -> "On this phone · Gemini Nano stood in for the cloud"
        else -> reason.label
    }
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (cloud) Icons.Outlined.Cloud else Icons.Outlined.Lock, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = color)
        if (onOpen != null) {
            Spacer(Modifier.width(4.dp))
            Text("· See what was sent", style = MaterialTheme.typography.bodySmall, color = color, fontWeight = FontWeight.SemiBold)
        }
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
 * To Auto: Luna decides from here. To Local: from here it's the phone's alone again.
 */
@Composable
fun ModeDivider(toCloud: Boolean, before: Int) = ModeDivider(if (toCloud) ChatMode.CLOUD else ChatMode.LOCAL, before)

@Composable
fun ModeDivider(to: ChatMode, before: Int) {
    val color = CloudColors.of(to)
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = color.copy(alpha = 0.3f))
            Icon(to.icon(), contentDescription = null, tint = color, modifier = Modifier.padding(horizontal = 8.dp).size(14.dp))
            Text(
                when (to) {
                    ChatMode.CLOUD -> if (before == 0) "Cloud chat" else "Switched to Cloud"
                    ChatMode.AUTO -> if (before == 0) "Auto chat" else "Switched to Auto"
                    ChatMode.LOCAL -> "Back to Local"
                },
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
            Spacer(Modifier.width(8.dp))
            HorizontalDivider(Modifier.weight(1f), color = color.copy(alpha = 0.3f))
        }
        Text(
            if (to == ChatMode.CLOUD && before == 0) {
                "Gemini sees only what's said in this chat in Cloud mode, never your Local chats."
            } else if (to == ChatMode.CLOUD) {
                "The $before message${if (before == 1) "" else "s"} above stay${if (before == 1) "s" else ""} on this phone. Gemini sees only what's said from here in Cloud mode."
            } else if (to == ChatMode.AUTO) {
                "Luna answers on this phone, and asks Gemini only questions it writes."
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

/**
 * Shown the first time a chat is switched to Auto, before anything is sent: what may go, what
 * never does, and how to check.
 */
@Composable
fun AutoConsentDialog(onAgree: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = CloudColors.Auto) },
        title = { Text("Let Luna decide?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Point(Icons.Outlined.Lock, CloudColors.Local, "Most things stay here", "Luna answers on this phone, with your messages, photos, files and health data. Offline, or with an ID or card number in a message, nothing leaves.")
                Point(Icons.Outlined.AutoAwesome, CloudColors.Auto, "What may go to Gemini", "Only a question Luna writes for it, when the phone's model would do worse: long writing, translation, planning, live facts. Luna puts in only what the answer needs, and never ID or card numbers. Google processes it under the Gemini API terms.")
                Point(Icons.Outlined.Visibility, AppColors.TextSecondary, "See for yourself", "Each reply says where it ran. Tap \"See what was sent\" for the question word for word, and \"What the cloud has seen\" lists them all. If a question would copy a message or file out, Luna asks you first.")
                Text("You can switch to Local or Cloud at any time.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            }
        },
        confirmButton = { TextButton(onClick = onAgree) { Text("Use Auto") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/**
 * An Auto question that copies a private message, file or reading out: held until the user has
 * seen exactly what would be sent ([sent], already hidden) and said so.
 */
@Composable
fun AskConfirmDialog(sent: String, hidden: String?, onSend: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        icon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = CloudColors.Auto) },
        title = { Text("Send this to Gemini?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This question copies part of something private Luna read. It would go exactly like this:", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CloudColors.AutoSoft)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(highlightPlaceholders(sent), style = MaterialTheme.typography.bodyMedium)
                }
                hidden?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = CloudColors.Auto) }
            }
        },
        confirmButton = { TextButton(onClick = onSend) { Text("Send") } },
        dismissButton = { TextButton(onClick = onKeep) { Text("Keep on phone") } },
    )
}

/** [text] with the numbers held back from it ([withheld]) picked out. */
fun highlightPlaceholders(text: String): AnnotatedString = buildAnnotatedString {
    var at = 0
    PLACEHOLDER.findAll(text).forEach { match ->
        append(text.substring(at, match.range.first))
        withStyle(SpanStyle(color = CloudColors.Auto, fontWeight = FontWeight.SemiBold, background = CloudColors.AutoSoft)) { append(match.value) }
        at = match.range.last + 1
    }
    append(text.substring(at))
}

private val PLACEHOLDER = Regex("\\[withheld]")

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
