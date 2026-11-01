package app.verbatim.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.verbatim.BuildConfig
import app.verbatim.JournalApplication
import app.verbatim.asr.ParakeetModel
import app.verbatim.data.AppPrefs
import app.verbatim.network.NetworkClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The speech model is downloaded explicitly and always runs on this phone. */
@Composable
private fun SpeechModelSection() {
    val context = LocalContext.current
    val model by ParakeetModel.state.collectAsState()
    LaunchedEffect(Unit) { ParakeetModel.refresh(context) }
    var confirmDelete by remember { mutableStateOf(false) }

    Text("Speech recognition", style = MaterialTheme.typography.titleMedium)
    Text(
        "Parakeet runs on this phone: your audio never leaves it. Download it once before transcribing or asking by voice. Recordings made without it are kept for later transcription.",
        style = MaterialTheme.typography.bodySmall
    )
    Text("Parakeet v3", style = MaterialTheme.typography.titleSmall)
    Text(
        "670 MB download. Uses about 1 GB of memory while transcribing. NVIDIA Parakeet TDT 0.6B v3, CC BY 4.0.",
        style = MaterialTheme.typography.bodySmall
    )
    when (val state = model) {
        ParakeetModel.State.Absent -> OutlinedButton(onClick = { ParakeetModel.download(context) }) {
            Text("Download Parakeet (670 MB, use Wi-Fi)")
        }
        is ParakeetModel.State.Downloading -> {
            LinearProgressIndicator(
                progress = { state.bytes.toFloat() / state.total },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "${state.bytes / 1_000_000} / ${state.total / 1_000_000} MB. Keep the app open; a stopped download resumes.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = { ParakeetModel.cancelDownload() }) { Text("Stop") }
        }
        ParakeetModel.State.Verifying -> Text("Checking the download…", style = MaterialTheme.typography.bodySmall)
        ParakeetModel.State.Ready -> OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete Parakeet") }
        is ParakeetModel.State.Failed -> {
            Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { ParakeetModel.download(context) }) { Text("Try again") }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete Parakeet?") },
            text = { Text("Transcription and spoken questions will be unavailable until you download it again. Existing recordings and transcripts are kept.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; ParakeetModel.delete(context) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

/** What the app is built from; the licences require showing this. */
private const val LICENCES = """Verbatim is free software under the GNU General Public License v3.0. Source code: ${BuildConfig.SOURCE_URL}

Speech recognition
NVIDIA Parakeet TDT 0.6B v3 (required download for transcription), CC BY 4.0. Converted and quantized by k2-fsa/sherpa-onnx.

Reading aloud
sherpa-onnx 1.13.8, Apache-2.0
espeak-ng, GPL-3.0
Piper fr_FR-siwis-medium voice, SIWIS dataset, CC BY 4.0 (University of Edinburgh)
Piper en_US-ljspeech-medium voice, LJ Speech dataset, public domain

Libraries
AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines, Room, Retrofit, OkHttp, Gson: Apache-2.0"""

/** Server address + token + speech model + the consent notice + licences. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onShowConsent: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(AppPrefs.serverUrl(context)) }
    var serverToken by remember { mutableStateOf(AppPrefs.serverToken(context)) }
    var status by remember { mutableStateOf("") }
    var showLicences by remember { mutableStateOf(false) }
    var licenceFile by remember { mutableStateOf<String?>(null) }
    val licenceFiles = remember { context.assets.list("licenses").orEmpty().sorted() }
    val licenceText = remember(licenceFile) {
        licenceFile?.let { name -> context.assets.open("licenses/$name").bufferedReader().use { it.readText() } }
    }
    val urlValid = serverUrl.trim().toHttpUrlOrNull() != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Your server", style = MaterialTheme.typography.titleMedium)
            Text(
                "The computer you set up to run Verbatim's server. Only transcript text is sent there, never audio.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it; status = "" },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Address") },
                singleLine = true,
                isError = serverUrl.isNotBlank() && !urlValid,
                placeholder = { Text("http://my-computer:8000") }
            )
            OutlinedTextField(
                value = serverToken,
                onValueChange = { serverToken = it; status = "" },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Access token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Button(
                enabled = urlValid,
                onClick = {
                    AppPrefs.setServer(context, serverUrl.trim(), serverToken.trim())
                    status = "Saved. Testing…"
                    scope.launch {
                        status = try {
                            val response = NetworkClient.api.health()
                            if (response.isSuccessful) {
                                // Send what was waiting for a server.
                                JournalApplication.instance.repository.syncPending()
                                "Connected."
                            } else {
                                NetworkClient.describeHttpError(response.code())
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "Saved, but the server is unreachable: ${e.message ?: e.javaClass.simpleName}"
                        }
                    }
                }
            ) { Text("Save & test") }
            if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(16.dp))
            SpeechModelSection()

            Spacer(Modifier.height(16.dp))
            Text("Consent", style = MaterialTheme.typography.titleMedium)
            Text(
                "Recording a private conversation without everyone's consent is illegal in France (Art. 226-1 Code pénal).",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = onShowConsent) { Text("Read the recording notice") }

            Spacer(Modifier.height(16.dp))
            Text("About", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { showLicences = true }) { Text("Open-source licences") }
        }
    }
    if (showLicences) {
        AlertDialog(
            onDismissRequest = { showLicences = false },
            title = { Text("Open-source licences") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(LICENCES, style = MaterialTheme.typography.bodySmall)
                    Text("Verbatim comes without warranty. You may copy, modify and redistribute it under GPL-3.0.", style = MaterialTheme.typography.bodySmall)
                    licenceFiles.forEach { name ->
                        TextButton(onClick = { showLicences = false; licenceFile = name }) { Text(name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLicences = false }) { Text("Close") } }
        )
    }
    if (licenceFile != null) {
        AlertDialog(
            onDismissRequest = { licenceFile = null; showLicences = true },
            title = { Text(licenceFile.orEmpty()) },
            text = { Text(licenceText.orEmpty(), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { licenceFile = null; showLicences = true }) { Text("Back") } }
        )
    }
}
