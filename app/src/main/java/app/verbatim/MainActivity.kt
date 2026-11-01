package app.verbatim

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.verbatim.data.AppPrefs
import app.verbatim.data.local.RecordingDatabase
import app.verbatim.service.RecordingService
import app.verbatim.service.RecordingState
import app.verbatim.ui.screens.ConsentDialog
import app.verbatim.ui.screens.NoteEditorScreen
import app.verbatim.ui.screens.QueryScreen
import app.verbatim.ui.screens.RecordingDetailScreen
import app.verbatim.ui.screens.RecordingsListScreen
import app.verbatim.ui.screens.SettingsScreen
import app.verbatim.ui.theme.VerbatimTheme
import app.verbatim.voice.AnswerSpeaker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The app's screens. A NavHost would be overkill for five of them. */
private sealed interface Screen {
    data object List : Screen
    data object Ask : Screen
    data object Settings : Screen
    data object Write : Screen
    data class Detail(val id: Long) : Screen
    data class Edit(val id: Long) : Screen
}

class MainActivity : ComponentActivity() {

    /** Tracks whether all required permissions have been granted.
     *  The tile service reads this via SharedPreferences. */
    var isPermissionsGranted = false
        private set

    // A permanently denied permission returns immediately without showing Android's
    // prompt. Always explain that result instead of leaving Record looking broken.
    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "Microphone enabled. Tap Record or the microphone to start.", Toast.LENGTH_LONG).show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Microphone access required")
                .setMessage("Recording and spoken questions need microphone access. Open app settings, choose Permissions, then allow Microphone. No recording has started.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ActivityResultContracts.RequestMultiplePermissions is the modern,
    // non-deprecated way to request dangerous permissions at runtime.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] == true
        } else {
            true // POST_NOTIFICATIONS not required before Android 13
        }
        isPermissionsGranted = audioGranted && notificationGranted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()

        val database = RecordingDatabase.getInstance(applicationContext)
        val app = application as JournalApplication

        setContent {
            VerbatimTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: MainViewModel = viewModel(factory = MainViewModelFactory(database.recordingDao()))
                    val recordings by viewModel.recordings.collectAsState()
                    val asrStatus by app.asrStatus.collectAsState()
                    val retranscribing by app.repository.retranscribing.collectAsState()
                    var screen by remember { mutableStateOf<Screen>(Screen.List) }
                    // Where "back" goes from a recording: the list, or the answer that cited it.
                    var detailReturnsTo by remember { mutableStateOf<Screen>(Screen.List) }
                    var showConsent by remember { mutableStateOf(!AppPrefs.consentAcknowledged(this)) }

                    // An answer to a spoken question is read aloud automatically.
                    LaunchedEffect(viewModel.speakRequest) {
                        viewModel.speakRequest?.let {
                            speaker.speak(it)
                            viewModel.speakRequest = null
                        }
                    }

                    BackHandler(enabled = screen != Screen.List) {
                        screen = if (screen is Screen.Detail) detailReturnsTo else Screen.List
                    }

                    when (val current = screen) {
                        Screen.List -> RecordingsListScreen(
                            recordings = recordings,
                            asrStatus = asrStatus,
                            serverConfigured = AppPrefs.serverUrl(this).isNotBlank(),
                            onRecordingClick = { id ->
                                detailReturnsTo = Screen.List
                                screen = Screen.Detail(id)
                            },
                            onRecordPressed = { toggleRecording() },
                            onSettingsClick = { screen = Screen.Settings },
                            onAskClick = { screen = Screen.Ask },
                            onWriteClick = { screen = Screen.Write },
                            isRecording = isRecording,
                            recordingStartedAt = recordingStartedAt,
                            recordButtonEnabled = recordButtonEnabled
                        )
                        Screen.Ask -> QueryScreen(
                            exchanges = viewModel.exchanges,
                            question = viewModel.question,
                            onQuestionChange = { viewModel.question = it },
                            onAsk = { viewModel.ask() },
                            isAsking = viewModel.isAsking,
                            voiceState = viewModel.voiceState,
                            // Voice-input errors first, else why an answer could not be read aloud.
                            voiceError = viewModel.voiceError.ifEmpty { speaker.problem },
                            onVoice = {
                                speaker.stop()
                                if (hasMicPermission()) viewModel.toggleVoice() else microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            // The microphone is busy while a recording runs.
                            voiceAvailable = !isRecording,
                            isSpeaking = speaker.isSpeaking,
                            onSpeak = { speaker.speak(it) },
                            onStopSpeaking = { speaker.stop() },
                            recordings = recordings,
                            onRecordingClick = { id ->
                                detailReturnsTo = Screen.Ask
                                screen = Screen.Detail(id)
                            },
                            onNewConversation = { viewModel.clearConversation() },
                            onBack = { screen = Screen.List }
                        )
                        Screen.Write -> NoteEditorScreen(
                            onSave = { text ->
                                app.repository.addTextNote(text)
                                screen = Screen.List
                            },
                            onClose = { screen = Screen.List }
                        )
                        is Screen.Edit -> {
                            val recording = recordings.find { it.id == current.id }
                            if (recording == null) {
                                LaunchedEffect(current.id) { if (recordings.isNotEmpty()) screen = Screen.List }
                            } else {
                                NoteEditorScreen(
                                    initialText = recording.transcript,
                                    title = "Edit text",
                                    onSave = { text ->
                                        app.repository.editTranscript(recording.id, text)
                                        screen = Screen.Detail(recording.id)
                                    },
                                    onClose = { screen = Screen.Detail(recording.id) }
                                )
                            }
                        }
                        Screen.Settings -> SettingsScreen(
                            onShowConsent = { showConsent = true },
                            onBack = { screen = Screen.List }
                        )
                        is Screen.Detail -> {
                            val recording = recordings.find { it.id == current.id }
                            if (recording == null) {
                                // Not loaded yet (or gone): show the list instead.
                                LaunchedEffect(current.id) { if (recordings.isNotEmpty()) screen = Screen.List }
                            } else {
                                RecordingDetailScreen(
                                    recording = recording,
                                    onRetrySync = { app.repository.submitTranscript(recording.id) },
                                    onEdit = { screen = Screen.Edit(recording.id) },
                                    isRetranscribing = recording.id in retranscribing,
                                    onRetranscribe = { app.repository.retranscribe(recording.id) },
                                    onDelete = {
                                        app.repository.deleteRecording(recording.id)
                                        screen = detailReturnsTo
                                    },
                                    onBack = { screen = detailReturnsTo }
                                )
                            }
                        }
                    }

                    if (showConsent) {
                        ConsentDialog(
                            onDismiss = { showConsent = false },
                            onConfirm = {
                                AppPrefs.setConsentAcknowledged(this)
                                showConsent = false
                            }
                        )
                    }
                }
            }
        }
    }

    // --- Record button: same service and intents as the tile ---

    /** Mirrors RecordingState, so a recording started from the tile shows here too. */
    private var isRecording by mutableStateOf(false)
    private var recordingStartedAt by mutableLongStateOf(0L)

    /** Reads answers aloud (offline text-to-speech); released in onDestroy. */
    private val speaker by lazy { AnswerSpeaker(this) }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }
    /** Briefly false after a tap, so a double tap cannot start twice. */
    private var recordButtonEnabled by mutableStateOf(true)
    private var recordingListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onStart() {
        super.onStart()
        isRecording = RecordingState.isRecording(this)
        recordingStartedAt = RecordingState.startedAt(this)
        recordingListener = RecordingState.observe(this) {
            isRecording = it
            recordingStartedAt = RecordingState.startedAt(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // Retry the transcripts that could not reach the server (cheap if none).
        (application as JournalApplication).repository.syncPending()
    }

    override fun onStop() {
        recordingListener?.let { RecordingState.stopObserving(this, it) }
        recordingListener = null
        super.onStop()
    }

    private fun toggleRecording() {
        if (!isRecording && !hasMicPermission()) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        recordButtonEnabled = false
        lifecycleScope.launch {
            delay(1500)
            recordButtonEnabled = true
        }
        if (isRecording) {
            // The service is already in the foreground: a plain startService is enough.
            startService(Intent(this, RecordingService::class.java).apply { action = "STOP_RECORDING" })
        } else {
            val intent = Intent(this, RecordingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }
    }

    /** Check which permissions are missing and request them. */
    private fun requestPermissionsIfNeeded() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            isPermissionsGranted = true
        }
    }
}
