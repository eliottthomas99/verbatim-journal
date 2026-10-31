package app.verbatim.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.verbatim.data.model.Recording
import app.verbatim.data.model.isTextNote
import app.verbatim.ui.theme.Recording as RecordingRed
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Home screen: an "ask" bar, the recordings grouped by day, and a big
 * record button (the Quick Settings tile does the same thing).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsListScreen(
    recordings: List<Recording>,
    onRecordingClick: (Long) -> Unit,
    onRecordPressed: () -> Unit,
    onSettingsClick: () -> Unit,
    onAskClick: () -> Unit,
    onWriteClick: () -> Unit,
    isRecording: Boolean,
    recordingStartedAt: Long,
    recordButtonEnabled: Boolean,
    asrStatus: String = "",
    serverConfigured: Boolean = true
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Verbatim", style = MaterialTheme.typography.headlineSmall)
                        // Only worth showing while the offline model is not ready.
                        if (asrStatus.isNotEmpty() && asrStatus != "Offline model ready") {
                            Text(asrStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            // Recording is the main action; writing a note by hand is the small one.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(
                    onClick = onWriteClick,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Icon(Icons.Outlined.EditNote, contentDescription = "Write a note")
                }
                RecordButton(isRecording, recordingStartedAt, recordButtonEnabled, onRecordPressed)
            }
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!serverConfigured) {
                item { ConnectServerCard(onSettingsClick) }
            }
            item { AskBar(onAskClick) }
            if (recordings.isEmpty()) {
                item { EmptyState() }
            }
            groupByDay(recordings).forEach { (day, dayRecordings) ->
                item(key = "day-$day") {
                    Text(
                        day,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, start = 4.dp)
                    )
                }
                items(dayRecordings, key = { it.id }) { recording ->
                    RecordingCard(recording, onClick = { onRecordingClick(recording.id) })
                }
            }
        }
    }
}

/** First run: recording works at once, but summaries and questions need a server. */
@Composable
private fun ConnectServerCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Connect your server", style = MaterialTheme.typography.titleMedium)
            Text(
                "Recordings are transcribed on this phone and wait here. Summaries and questions need your own server: tap to enter its address.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** Looks like a search field; opens the "Ask your journal" screen. */
@Composable
private fun AskBar(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Ask your journal…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun RecordButton(isRecording: Boolean, startedAt: Long, enabled: Boolean, onClick: () -> Unit) {
    // Ticks once a second while recording, for the timer.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isRecording) {
        while (isRecording) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    ExtendedFloatingActionButton(
        onClick = { if (enabled) onClick() },
        containerColor = if (isRecording) RecordingRed else MaterialTheme.colorScheme.primary,
        contentColor = if (isRecording) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onPrimary,
        icon = { Icon(if (isRecording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null) },
        text = {
            Text(
                if (isRecording && startedAt > 0) "Stop · ${formatDuration(now - startedAt)}"
                else if (isRecording) "Stop"
                else "Record"
            )
        }
    )
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
        }
        Text("Nothing recorded yet", style = MaterialTheme.typography.titleLarge)
        Text(
            "Tap Record below, or add the \"Record\" tile to your Quick Settings to start from anywhere. Everything is transcribed on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One recording: time and length on the left, summary (or transcript) on the right. */
@Composable
fun RecordingCard(recording: Recording, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(52.dp)) {
                Text(
                    SimpleDateFormat("HH:mm", Locale.ENGLISH).format(recording.timestamp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (recording.isTextNote) {
                    Icon(Icons.Outlined.EditNote, contentDescription = "Written note", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                } else {
                    Text(formatDuration(recording.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    recording.transcriptionError.isNotEmpty() -> Text(
                        "Transcription failed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    recording.summary.isNotEmpty() -> Text(
                        recording.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    recording.transcript.isNotEmpty() -> Text(
                        "“${recording.transcript}”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    else -> Text("No speech detected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusRow(recording)
            }
        }
    }
}

/** Small icons: synced / waiting / failed, and the number of action items. */
@Composable
private fun StatusRow(recording: Recording) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            recording.isSynced -> StatusIcon(Icons.Outlined.CloudDone, "Synced", MaterialTheme.colorScheme.primary)
            recording.syncError.isNotEmpty() -> StatusIcon(Icons.Outlined.ErrorOutline, "Not synced", MaterialTheme.colorScheme.error)
            recording.transcript.isNotEmpty() -> StatusIcon(Icons.Outlined.CloudOff, "Waiting to sync", muted)
        }
        val todos = recording.actionItems.lines().count { it.isNotBlank() }
        if (todos > 0) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Outlined.TaskAlt, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
            Text(
                if (todos == 1) "1 action item" else "$todos action items",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun StatusIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: androidx.compose.ui.graphics.Color) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
}

/** "Today", "Yesterday", "Monday 27 October" (with the year if not this year). */
private fun groupByDay(recordings: List<Recording>): List<Pair<String, List<Recording>>> {
    val today = Calendar.getInstance()
    val dayKey = { ms: Long -> Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) } }
    val todayKey = dayKey(today.timeInMillis)
    val yesterdayKey = dayKey(today.timeInMillis - 24 * 3600 * 1000L)
    return recordings.groupBy { dayKey(it.timestamp) }.map { (key, list) ->
        val ts = list.first().timestamp
        val label = when (key) {
            todayKey -> "Today"
            yesterdayKey -> "Yesterday"
            else -> {
                val sameYear = key / 1000 == today.get(Calendar.YEAR)
                SimpleDateFormat(if (sameYear) "EEEE d MMMM" else "EEEE d MMMM yyyy", Locale.ENGLISH).format(ts)
            }
        }
        label.replaceFirstChar { it.uppercase() } to list
    }
}

/** 125000 ms -> "2:05". */
fun formatDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
