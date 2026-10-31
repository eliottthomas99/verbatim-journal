package app.verbatim.repository

import android.content.Context
import android.util.Log
import java.io.File
import app.verbatim.asr.AsrTranscriber
import app.verbatim.data.local.RecordingDao
import app.verbatim.data.model.Recording
import app.verbatim.network.NetworkClient
import app.verbatim.network.SubmitTranscriptRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Inserts recordings into the local database and syncs their transcript
 * *text* to the server.
 *
 * The audio itself never leaves the phone: it is transcribed on device (see
 * `AsrTranscriber`) and only the text is sent, by [submitTranscript].
 */
class SyncRepository(
    private val dao: RecordingDao,
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    /** One sync at a time: a Mutex is a lock for coroutines, so a retry
     *  cannot send the same recording twice in parallel. */
    private val syncLock = Mutex()

    /** Server ids whose deletion has not reached the server yet (survives restarts). */
    private val prefs = context.getSharedPreferences("sync_state", Context.MODE_PRIVATE)

    /**
     * Insert a new recording row into Room (suspend — call from a coroutine).
     * Returns the generated local ID.
     */
    suspend fun insertRecording(recording: Recording): Long {
        return dao.insertRecording(recording)
    }

    /** A note typed by hand: stored like a transcribed recording (no audio), then synced. */
    fun addTextNote(text: String) {
        scope.launch {
            val now = System.currentTimeMillis()
            val id = dao.insertRecording(
                Recording(filename = "${Recording.TEXT_NOTE_PREFIX}$now.txt", timestamp = now, transcript = text.trim())
            )
            syncLock.withLock { submitNow(id) }
        }
    }

    /** Save a hand-corrected text and send it again (new summary + search index on the server). */
    fun editTranscript(recordingId: Long, text: String) {
        scope.launch {
            syncLock.withLock {
                dao.updateTranscript(recordingId, text.trim())
                submitNow(recordingId)
            }
        }
    }

    /** The recordings being transcribed again, for the detail screen. */
    private val _retranscribing = MutableStateFlow<Set<Long>>(emptySet())
    val retranscribing = _retranscribing.asStateFlow()

    /**
     * Run the audio through the current speech engine again and send the new
     * text. The old transcript stays if this fails or finds nothing.
     */
    fun retranscribe(recordingId: Long) {
        if (recordingId in _retranscribing.value) return
        _retranscribing.value += recordingId
        scope.launch {
            try {
                val recording = dao.getRecordingById(recordingId) ?: return@launch
                val result = AsrTranscriber(context).transcribe(File(context.filesDir, recording.filename))
                val transcript = result.getOrNull()
                if (transcript != null && transcript.text.isNotBlank()) {
                    syncLock.withLock {
                        dao.replaceTranscript(recordingId, transcript.text, transcript.segments)
                        submitNow(recordingId)
                    }
                }
            } finally {
                _retranscribing.value -= recordingId
            }
        }
    }

    /**
     * Delete everywhere: the row and the audio on the phone at once, the
     * transcript and search index on the server now — or at the next sync if the
     * server is unreachable (a deleted note must not stay searchable).
     */
    fun deleteRecording(recordingId: Long) {
        scope.launch {
            syncLock.withLock {
                // Re-read under the lock: a sync that just finished may have set the server id.
                val recording = dao.getRecordingById(recordingId) ?: return@withLock
                dao.deleteRecording(recordingId)
                File(context.filesDir, recording.filename).delete()
                recording.serverRecordingId?.let { serverId ->
                    prefs.edit().putStringSet(KEY_PENDING_DELETES, pendingDeletes() + serverId).apply()
                }
                flushDeletes()
            }
        }
    }

    private fun pendingDeletes(): Set<String> = prefs.getStringSet(KEY_PENDING_DELETES, emptySet()).orEmpty().toSet()

    /** Tell the server about every deletion it has not acknowledged yet. */
    private suspend fun flushDeletes() {
        if (!NetworkClient.isConfigured) return
        for (serverId in pendingDeletes()) {
            try {
                val response = NetworkClient.api.deleteRecording(serverId)
                if (response.isSuccessful) {
                    prefs.edit().putStringSet(KEY_PENDING_DELETES, pendingDeletes() - serverId).apply()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Deletion of $serverId on the server postponed", e)
                return // Server unreachable: retry everything at the next sync
            }
        }
    }

    /** Send one recording's transcript to the server (fire and forget). */
    fun submitTranscript(recordingId: Long) {
        scope.launch { syncLock.withLock { submitNow(recordingId) } }
    }

    /**
     * Retry every recording whose text has not reached the server yet (network
     * down, server asleep...). Called when the app starts and comes to the front.
     */
    fun syncPending() {
        scope.launch {
            syncLock.withLock {
                flushDeletes()
                dao.getPendingSync().forEach { submitNow(it.id) }
            }
        }
    }

    private suspend fun submitNow(recordingId: Long) {
        // No server yet: the text waits on the phone until one is set in Settings.
        if (!NetworkClient.isConfigured) return
        try {
            val recording = dao.getRecordingById(recordingId) ?: return
            // Nothing to send: silence, or a failed local transcription.
            if (recording.isSynced || recording.transcript.isBlank()) return
            val response = NetworkClient.api.submitTranscript(
                SubmitTranscriptRequest(
                    filename = recording.filename,
                    timestamp = recording.timestamp,
                    durationMs = recording.durationMs,
                    transcript = recording.transcript,
                    segments = recording.segments
                )
            )
            val body = response.body()
            if (response.isSuccessful && body != null) {
                dao.markUploaded(recordingId, body.recording_id)
                // The server summarizes while handling the request, so the
                // summary comes back in the same response.
                if (body.summary.isNotEmpty() || body.action_items.isNotEmpty()) {
                    dao.markSummary(
                        recordingId,
                        body.summary,
                        body.action_items.joinToString("\n")
                    )
                }
            } else {
                dao.markUploadFailed(recordingId, NetworkClient.describeHttpError(response.code()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync of recording $recordingId failed", e)
            dao.markUploadFailed(recordingId, e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        private const val TAG = "SyncRepository"
        private const val KEY_PENDING_DELETES = "pending_server_deletes"
    }
}
