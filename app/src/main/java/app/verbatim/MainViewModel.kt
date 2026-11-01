package app.verbatim

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.verbatim.data.local.RecordingDao
import app.verbatim.data.model.Recording
import app.verbatim.network.NetworkClient
import app.verbatim.network.RagRequest
import app.verbatim.voice.VoiceQuestion
import app.verbatim.voice.plainText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Factory that creates MainViewModel instances with a given DAO. */
class MainViewModelFactory(
    private val dao: RecordingDao
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(dao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

/**
 * ViewModel for the main screen.
 * It exposes recordings as a StateFlow (stable, always emits the latest value)
 * so Compose can observe changes reactively.
 *
 * `viewModelScope` is a coroutine scope tied to the ViewModel's lifecycle —
 * when the ViewModel is destroyed (e.g. user navigates away), all its coroutines
 * are cancelled automatically.
 *
 * There is **no polling**: the phone transcribes the recording itself (see
 * `AsrTranscriber`), so the list only has to read Room.
 */
class MainViewModel(private val dao: RecordingDao) : ViewModel() {

    /** A stable StateFlow that always holds the latest list of recordings. */
    val recordings = dao.getAllRecordings()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000), // stop after 5s of no observers
            initialValue = emptyList()
        )

    /** Insert a new recording row into the database. Called by RecordingService when a recording ends. */
    fun insertRecording(recording: Recording) {
        viewModelScope.launch {
            dao.insertRecording(recording)
        }
    }

    /** Update an existing recording (e.g. after transcription or sync). */
    fun updateRecording(recording: Recording) {
        viewModelScope.launch {
            dao.updateRecording(recording)
        }
    }

    // --- "Ask your journal": a conversation kept here, not in the screen, so
    // it survives a rotation or a visit to a recording's detail screen.

    /** One question and its answer. [sources] are server recording ids. */
    data class Exchange(
        val question: String,
        val byVoice: Boolean,
        val answer: String = "",
        val sources: List<String> = emptyList(),
        val error: String = "",
        val pending: Boolean = true
    )

    val exchanges = mutableStateListOf<Exchange>()
    var question by mutableStateOf("")
    val isAsking get() = exchanges.lastOrNull()?.pending == true

    /** Set when an answer to a spoken question arrives; the screen reads it aloud. */
    var speakRequest by mutableStateOf<String?>(null)

    fun ask(byVoice: Boolean = false) {
        val q = question.trim()
        if (q.isEmpty() || isAsking) return
        question = ""
        exchanges.add(Exchange(q, byVoice))
        val index = exchanges.lastIndex
        if (!NetworkClient.isConfigured) {
            exchanges[index] = exchanges[index].copy(error = "Connect your server in Settings first.", pending = false)
            return
        }
        viewModelScope.launch {
            val done = try {
                val response = NetworkClient.api.queryRag(RagRequest(q))
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    exchanges[index].copy(answer = plainText(body.answer), sources = body.sources, pending = false)
                } else {
                    exchanges[index].copy(error = NetworkClient.describeHttpError(response.code()), pending = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                exchanges[index].copy(error = "Could not reach the server: ${e.message ?: e.javaClass.simpleName}", pending = false)
            }
            exchanges[index] = done
            if (byVoice && done.answer.isNotEmpty()) speakRequest = done.answer
        }
    }

    /** Start a new conversation (the old one is only in memory anyway). */
    fun clearConversation() {
        if (!isAsking) exchanges.clear()
    }

    // --- Spoken questions (recognized on the phone by Parakeet) ---

    enum class VoiceState { Idle, Listening, Transcribing }

    var voiceState by mutableStateOf(VoiceState.Idle)
        private set
    var voiceError by mutableStateOf("")
        private set
    private val voice = VoiceQuestion()

    /** First tap: start listening. Second tap: stop, transcribe, ask. */
    fun toggleVoice() {
        when (voiceState) {
            VoiceState.Listening -> voice.stop()
            VoiceState.Transcribing -> Unit
            VoiceState.Idle -> {
                voiceError = ""
                voiceState = VoiceState.Listening
                viewModelScope.launch {
                    try {
                        val text = voice.listenAndTranscribe(onListeningDone = { voiceState = VoiceState.Transcribing })
                        if (text.isBlank()) {
                            voiceError = "I didn't catch that — try again."
                        } else {
                            question = text
                            ask(byVoice = true)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        voiceError = "Voice input failed: ${e.message ?: e.javaClass.simpleName}"
                    } finally {
                        voiceState = VoiceState.Idle
                    }
                }
            }
        }
    }

    override fun onCleared() {
        voice.stop()
    }
}
