package app.verbatim.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Legal reminder, shown once on first launch and re-readable from
 * Settings. A Quick Settings tile cannot show a dialog, so while recording
 * the reminder lives in the notification instead.
 */
@Composable
fun ConsentDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recording notice") },
        text = {
            Text(
                "In France, recording a private conversation without the consent of " +
                    "everyone involved is a criminal offence (Article 226-1 of the Code " +
                    "pénal). Before recording a conversation, make sure everyone being " +
                    "recorded has agreed.\n\nThe audio stays on this phone; only the " +
                    "transcript text is sent to your server."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("I understand") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Later") }
        }
    )
}
