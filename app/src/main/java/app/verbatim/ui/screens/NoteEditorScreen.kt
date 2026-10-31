package app.verbatim.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

/**
 * A note typed by hand, for when speaking is not possible. It is saved like
 * a recording's transcript, so it is summarized and searchable too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    onSave: (String) -> Unit,
    onClose: () -> Unit,
    initialText: String = "",
    title: String = "New note"
) {
    // rememberSaveable: the text survives a screen rotation.
    var text by rememberSaveable { mutableStateOf(initialText) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() } // open the keyboard right away

    val changed = text.trim() != initialText.trim()
    val close = { if (changed) confirmDiscard = true else onClose() }
    BackHandler(onBack = close)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = close) { Icon(Icons.Default.Close, contentDescription = "Close") }
                },
                actions = {
                    TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank() && changed) { Text("Save") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 8.dp)
                .fillMaxSize()
                .imePadding()
                .focusRequester(focus),
            placeholder = { Text("Write what you want to remember…") },
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.background,
                unfocusedContainerColor = MaterialTheme.colorScheme.background,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
            )
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(if (initialText.isEmpty()) "Discard this note?" else "Discard your changes?") },
            confirmButton = { TextButton(onClick = onClose) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep writing") } }
        )
    }
}
