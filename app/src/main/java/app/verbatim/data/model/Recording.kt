package app.verbatim.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.verbatim.asr.TranscriptSegment

/**
 * Represents a single voice recording in the app.
 * Stored in Room (the Android database abstraction layer).
 */
@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val filename: String,
    val timestamp: Long,          // epoch millis when recording was created
    val durationMs: Long = 0,     // actual recording duration in ms
    val transcript: String = "",  // filled on the phone by Parakeet (local transcription)
    val summary: String = "",     // filled later by the server LLM
    val actionItems: String = "", // extracted by the server LLM
    val speakerLabels: List<String> = emptyList(), // e.g. ["Speaker A", "Speaker B"]
    val isSynced: Boolean = false,          // whether the transcript text has been sent to the server
    val syncError: String = "",             // upload failure reason
    val transcriptionError: String = "",    // LOCAL (on-phone) transcription failure reason
    val segments: List<TranscriptSegment> = emptyList(), // sentence-level timing from the ASR engine
    val serverRecordingId: String? = null   // ID returned by the server for the summary round-trip
) {
    companion object {
        /** Typed notes have no audio; their "filename" starts with this (no schema change needed). */
        const val TEXT_NOTE_PREFIX = "note_"
    }
}

/** True for a note typed by hand (no audio file). Top-level, so Room ignores it. */
val Recording.isTextNote: Boolean get() = filename.startsWith(Recording.TEXT_NOTE_PREFIX)
