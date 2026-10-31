package app.verbatim.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.verbatim.asr.TranscriptSegment
import app.verbatim.data.model.Recording
import app.verbatim.data.model.isTextNote
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** One recording: player, summary, action items, timed transcript. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingDetailScreen(
    recording: Recording,
    onRetrySync: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    isRetranscribing: Boolean,
    onRetranscribe: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRetranscribe by remember { mutableStateOf(false) }
    val audioFile = remember(recording.filename) { File(context.filesDir, recording.filename) }
    val player = remember(recording.filename) { AudioPlayer(audioFile) }
    DisposableEffect(player) { onDispose { player.release() } }
    // Refresh the position ~5 times a second while playing (slider + highlighted line).
    LaunchedEffect(player.isPlaying) {
        while (player.isPlaying) {
            player.refresh()
            delay(200)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!recording.isTextNote && audioFile.exists()) {
                        if (isRetranscribing) {
                            CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { confirmRetranscribe = true }) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "Transcribe again")
                            }
                        }
                    }
                    // Correct recognition mistakes by hand (the server re-summarizes and re-indexes).
                    if (recording.transcript.isNotEmpty()) {
                        IconButton(onClick = { player.release(); onEdit() }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit text")
                        }
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        if (confirmRetranscribe) {
            AlertDialog(
                onDismissRequest = { confirmRetranscribe = false },
                title = { Text("Transcribe again?") },
                text = { Text("The current text, including any corrections you made by hand, will be replaced using the speech model selected in Settings. The summary is then redone.") },
                confirmButton = {
                    TextButton(onClick = { confirmRetranscribe = false; onRetranscribe() }) { Text("Transcribe") }
                },
                dismissButton = { TextButton(onClick = { confirmRetranscribe = false }) { Text("Cancel") } }
            )
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(if (recording.isTextNote) "Delete this note?" else "Delete this recording?") },
                text = {
                    Text(
                        (if (recording.isTextNote) "The note" else "The audio, the transcript") +
                            " and its summary will be removed from this phone and from your server. This can't be undone."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmDelete = false; player.release(); onDelete() }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
            )
        }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Header(recording, onRetrySync) }
            if (audioFile.exists()) {
                item { PlayerCard(player, recording.durationMs) }
            }
            if (recording.summary.isNotEmpty()) {
                item {
                    Section("Summary") {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(
                                recording.summary,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }
            val todos = recording.actionItems.lines().filter { it.isNotBlank() }
            if (todos.isNotEmpty()) {
                item {
                    Section("Action items") {
                        todos.forEach { todo ->
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Outlined.CheckBoxOutlineBlank, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                                Text(todo, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            item {
                Section(if (recording.isTextNote) "Note" else "Transcript") {
                    when {
                        recording.transcriptionError.isNotEmpty() -> Text(
                            "Transcription failed: ${recording.transcriptionError}",
                            color = MaterialTheme.colorScheme.error
                        )
                        recording.segments.isEmpty() && recording.transcript.isNotEmpty() ->
                            Text(recording.transcript, style = MaterialTheme.typography.bodyMedium)
                        recording.segments.isEmpty() ->
                            Text("No speech detected.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // Real timing from the on-device engine: tap a line to listen from there.
            items(recording.segments) { segment ->
                SegmentRow(segment, enabled = audioFile.exists(), active = player.positionMs in segment.startMs until segment.endMs && player.isPlaying) {
                    player.playFrom(segment.startMs)
                }
            }
        }
    }
}

@Composable
private fun Header(recording: Recording, onRetrySync: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            SimpleDateFormat("EEEE d MMMM yyyy", Locale.ENGLISH).format(recording.timestamp)
                .replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.headlineSmall
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                SimpleDateFormat("HH:mm", Locale.ENGLISH).format(recording.timestamp) + " · " +
                    if (recording.isTextNote) "Written note" else formatDuration(recording.durationMs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            val synced = recording.isSynced
            Icon(
                if (synced) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = if (synced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Text(
                if (synced) "Synced" else if (recording.syncError.isNotEmpty()) "Not synced" else "Waiting to sync",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!recording.isSynced && recording.syncError.isNotEmpty()) {
            Text(recording.syncError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (!recording.isSynced && recording.transcript.isNotEmpty()) {
            TextButton(onClick = onRetrySync, contentPadding = PaddingValues(0.dp)) { Text("Retry sync now") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun PlayerCard(player: AudioPlayer, durationMs: Long) {
    val total = player.durationMs.takeIf { it > 0 } ?: durationMs
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = { player.toggle() }) {
                Icon(if (player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (player.isPlaying) "Pause" else "Play")
            }
            Slider(
                value = if (total > 0) (player.positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f,
                onValueChange = { player.seekTo((it * total).toLong()) },
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            Text(
                "${formatDuration(player.positionMs)} / ${formatDuration(total)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}

@Composable
private fun SegmentRow(segment: TranscriptSegment, enabled: Boolean, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .background(if (active) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            formatDuration(segment.startMs),
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(40.dp)
        )
        Text(
            segment.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Small wrapper around Android's MediaPlayer, exposing Compose state
 * (isPlaying, positionMs) so the UI redraws when they change.
 */
private class AudioPlayer(private val file: File) {
    private var mp: MediaPlayer? = null
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set

    /** Prepares the player on first use; null if the file is unreadable. */
    private fun ensure(): MediaPlayer? {
        mp?.let { return it }
        return runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    this@AudioPlayer.isPlaying = false
                    positionMs = 0
                }
            }
        }.getOrNull()?.also {
            mp = it
            durationMs = it.duration.toLong()
        }
    }

    fun toggle() {
        val p = ensure() ?: return
        if (p.isPlaying) p.pause() else p.start()
        isPlaying = p.isPlaying
    }

    fun playFrom(ms: Long) {
        val p = ensure() ?: return
        p.seekTo(ms.toInt())
        positionMs = ms
        p.start()
        isPlaying = true
    }

    fun seekTo(ms: Long) {
        val p = ensure() ?: return
        p.seekTo(ms.toInt())
        positionMs = ms
    }

    fun refresh() {
        mp?.let { positionMs = it.currentPosition.toLong() }
    }

    fun release() {
        mp?.release()
        mp = null
        isPlaying = false
    }
}
