package app.verbatim.data.local

import androidx.lifecycle.LiveData
import androidx.room.*
import app.verbatim.asr.TranscriptSegment
import app.verbatim.data.model.Recording
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for Recording operations.
 * Think of a DAO as a bridge: it lets your code ask the database questions
 * without writing raw SQL strings everywhere.
 */
@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY timestamp DESC")
    fun getAllRecordings(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecordingById(id: Long): Recording?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecording(recording: Recording): Long

    @Update
    suspend fun updateRecording(recording: Recording)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteRecording(id: Long)

    // Kept for backward compatibility (e.g. tests). New code should use the Flow version above.
    @Query("SELECT * FROM recordings ORDER BY timestamp DESC")
    fun getAllRecordingsLegacy(): LiveData<List<Recording>>

    /** Recordings whose transcript text has not reached the server yet (one-shot read, for the retry). */
    @Query("SELECT * FROM recordings WHERE isSynced = 0 AND transcript != '' ORDER BY timestamp ASC")
    suspend fun getPendingSync(): List<Recording>

    /** Mark a recording as uploaded. Touches only the sync columns, so duration/timestamp survive. */
    @Query("UPDATE recordings SET isSynced = 1, serverRecordingId = :serverId, syncError = '' WHERE id = :id")
    suspend fun markUploaded(id: Long, serverId: String)

    /** Record a failed upload (the row stays unsynced so it can be retried later). */
    @Query("UPDATE recordings SET isSynced = 0, syncError = :error WHERE id = :id")
    suspend fun markUploadFailed(id: Long, error: String)

    /**
     * The user corrected the text by hand: keep it, drop what was derived from
     * the old text (timed lines, summary) and mark it to be sent again — the
     * server then re-summarizes and re-indexes it.
     */
    @Query("""UPDATE recordings SET transcript = :transcript, segments = '[]', transcriptionError = '',
              summary = '', actionItems = '', isSynced = 0, syncError = '' WHERE id = :id""")
    suspend fun updateTranscript(id: Long, transcript: String)

    /** A new transcription of the audio: new text and timed lines, then sent again like a correction. */
    @Query("""UPDATE recordings SET transcript = :transcript, segments = :segments, transcriptionError = '',
              summary = '', actionItems = '', isSynced = 0, syncError = '' WHERE id = :id""")
    suspend fun replaceTranscript(id: Long, transcript: String, segments: List<TranscriptSegment>)

    /** A failed retry must remain visible without erasing any existing text. */
    @Query("UPDATE recordings SET transcriptionError = :error WHERE id = :id")
    suspend fun markTranscriptionError(id: Long, error: String)

    /** Store the server-generated summary and action items. Targeted UPDATE so the other columns survive. */
    @Query("UPDATE recordings SET summary = :summary, actionItems = :actionItems WHERE id = :id")
    suspend fun markSummary(id: Long, summary: String, actionItems: String)
}
