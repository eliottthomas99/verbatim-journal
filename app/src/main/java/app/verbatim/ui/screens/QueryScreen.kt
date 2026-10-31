package app.verbatim.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.verbatim.MainViewModel.Exchange
import app.verbatim.MainViewModel.VoiceState
import app.verbatim.data.model.Recording
import app.verbatim.ui.theme.Recording as RecordingRed
import java.text.SimpleDateFormat
import java.util.Locale

private val SUGGESTIONS = listOf(
    "What did I do yesterday?",
    "Qu'est-ce que je dois faire cette semaine ?",
    "Who did I promise something to?",
)

/**
 * "Ask your journal": a conversation with the server, which searches the
 * transcripts and answers with the local LLM. Questions can be typed or
 * spoken (recognized on the phone); answers can be read aloud.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueryScreen(
    exchanges: List<Exchange>,
    question: String,
    onQuestionChange: (String) -> Unit,
    onAsk: () -> Unit,
    isAsking: Boolean,
    voiceState: VoiceState,
    voiceError: String,
    onVoice: () -> Unit,
    voiceAvailable: Boolean,
    isSpeaking: Boolean,
    onSpeak: (String) -> Unit,
    onStopSpeaking: () -> Unit,
    recordings: List<Recording>,
    onRecordingClick: (Long) -> Unit,
    onNewConversation: () -> Unit,
    onBack: () -> Unit
) {
    val listState = rememberLazyListState()
    // Follow the conversation: scroll to the newest answer.
    LaunchedEffect(exchanges.size, exchanges.lastOrNull()?.pending) {
        if (exchanges.isNotEmpty()) listState.animateScrollToItem(exchanges.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ask your journal") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isSpeaking) {
                        IconButton(onClick = onStopSpeaking) { Icon(Icons.Default.Stop, contentDescription = "Stop reading") }
                    }
                    if (exchanges.isNotEmpty()) {
                        IconButton(onClick = { onStopSpeaking(); onNewConversation() }, enabled = !isAsking) {
                            Icon(Icons.Outlined.AddComment, contentDescription = "New conversation")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            InputBar(question, onQuestionChange, onAsk, isAsking, voiceState, voiceError, onVoice, voiceAvailable)
        }
    ) { padding ->
        if (exchanges.isEmpty()) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Ask anything about your recordings", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Type or tap the microphone. Your server searches the transcripts and answers; the voice stays on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SUGGESTIONS.forEach { suggestion ->
                    SuggestionChip(
                        onClick = { onQuestionChange(suggestion); onAsk() },
                        label = { Text(suggestion) }
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(exchanges) { _, exchange ->
                    ExchangeView(exchange, recordings, onRecordingClick, onSpeak)
                }
            }
        }
    }
}

@Composable
private fun ExchangeView(
    exchange: Exchange,
    recordings: List<Recording>,
    onRecordingClick: (Long) -> Unit,
    onSpeak: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The question, as a bubble on the right.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (exchange.byVoice) {
                        Icon(Icons.Default.Mic, contentDescription = "Spoken", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(exchange.question, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        // The answer, as a card on the left.
        Surface(
            shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    exchange.pending -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Searching your journal…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    exchange.error.isNotEmpty() -> Text(exchange.error, color = MaterialTheme.colorScheme.error)
                    else -> {
                        Text(exchange.answer, style = MaterialTheme.typography.bodyLarge)
                        Sources(exchange.sources, recordings, onRecordingClick)
                        TextButton(onClick = { onSpeak(exchange.answer) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Read aloud")
                        }
                    }
                }
            }
        }
    }
}

/** The recordings an answer is based on — tap to open one. */
@Composable
private fun Sources(sources: List<String>, recordings: List<Recording>, onRecordingClick: (Long) -> Unit) {
    val matched = sources.mapNotNull { id -> recordings.find { it.serverRecordingId == id } }
    if (matched.isEmpty()) return
    val format = SimpleDateFormat("EEE d MMM, HH:mm", Locale.ENGLISH)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Sources", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        matched.forEach { recording ->
            AssistChip(
                onClick = { onRecordingClick(recording.id) },
                label = { Text(format.format(recording.timestamp)) },
                leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }
    }
}

@Composable
private fun InputBar(
    question: String,
    onQuestionChange: (String) -> Unit,
    onAsk: () -> Unit,
    isAsking: Boolean,
    voiceState: VoiceState,
    voiceError: String,
    onVoice: () -> Unit,
    voiceAvailable: Boolean
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column(
            Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            val status = when (voiceState) {
                VoiceState.Listening -> "Listening… tap ■ when you're done"
                VoiceState.Transcribing -> "Recognizing on the phone…"
                VoiceState.Idle -> voiceError
            }
            if (status.isNotEmpty()) {
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (voiceState == VoiceState.Idle) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = question,
                    onValueChange = onQuestionChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask a question…") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onAsk() }),
                    enabled = voiceState == VoiceState.Idle
                )
                val listening = voiceState == VoiceState.Listening
                FilledIconButton(
                    onClick = onVoice,
                    enabled = voiceAvailable && !isAsking && voiceState != VoiceState.Transcribing,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (listening) RecordingRed else MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = if (listening) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    if (voiceState == VoiceState.Transcribing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(if (listening) Icons.Default.Stop else Icons.Default.Mic, contentDescription = if (listening) "Stop listening" else "Ask by voice")
                    }
                }
                FilledIconButton(onClick = onAsk, enabled = question.isNotBlank() && !isAsking && voiceState == VoiceState.Idle) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask")
                }
            }
        }
    }
}
