package app.verbatim

import android.app.Application
import android.util.Log
import app.verbatim.asr.WhistleNative
import app.verbatim.data.AppPrefs
import app.verbatim.network.NetworkClient
import app.verbatim.data.local.RecordingDatabase
import app.verbatim.repository.SyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Custom Application class — gives us a single, app-wide place to hold the
 * Room database and the SyncRepository.  Accessible from Services and other
 * components that don't have access to an Activity's lifecycle.
 */
class JournalApplication : Application() {

    /** App-wide scope: survives the service being stopped, so the transcription
     *  of a finished recording still completes. */
    val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val database by lazy { RecordingDatabase.getInstance(this) }
    val repository by lazy { SyncRepository(database.recordingDao(), this, applicationScope) }
    private val _asrStatus = MutableStateFlow("Preparing offline transcription model…")
    val asrStatus = _asrStatus.asStateFlow()

    companion object {
        /** App-wide reference, set in [onCreate]. */
        lateinit var instance: JournalApplication
            private set

        private const val TAG = "JournalApplication"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Use the server address saved in Settings (none on a fresh install).
        runCatching { NetworkClient.configure(AppPrefs.serverUrl(this), AppPrefs.serverToken(this)) }
            .onFailure { Log.e(TAG, "Invalid saved server URL", it) }
        // Send the transcripts that could not reach the server last time.
        repository.syncPending()
        applicationScope.launch {
            try {
                WhistleNative.loadModelFromAssets(this@JournalApplication).getOrThrow()
                _asrStatus.value = "Offline model ready"
            } catch (error: Exception) {
                Log.e(TAG, "Whistle initialization failed", error)
                _asrStatus.value = "Offline model unavailable: ${error.message}"
            } catch (error: LinkageError) {
                Log.e(TAG, "Whistle native library unavailable", error)
                _asrStatus.value = "Offline model unavailable: native library could not load"
            }
        }
    }
}
