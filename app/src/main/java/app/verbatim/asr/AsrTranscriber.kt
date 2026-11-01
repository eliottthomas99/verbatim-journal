package app.verbatim.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToLong

/**
 * One transcript segment with real timing taken from the engine's word
 * timestamps. Times are milliseconds from the start of the recording.
 */
data class TranscriptSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

/**
 * Result of one transcription run.
 *
 * An empty [text] with empty [segments] means "silence", which is a valid
 * success — not an error.
 */
data class TranscriptionResult(
    val text: String,
    val segments: List<TranscriptSegment>,
    val language: String = ""
)

/**
 * Transcribes a recording on the phone with Parakeet.
 *
 * The audio never leaves the device: the M4A is decoded to 16 kHz mono float
 * PCM locally and passed to the on-device engine through [SpeechEngine].
 * Language is auto-detected by the engine.
 */
class AsrTranscriber(private val context: Context) {

    private val decoder = M4aDecoder()

    /**
     * Transcribes [audioFile] off the UI thread.
     *
     * Returns a failure [Result] for errors (corrupt file, decoder problem,
     * engine error). Silence is a *success* with empty text and no segments.
     *
     * If the calling coroutine is cancelled, a [CancellationException] is
     * thrown instead of returning a result — a cancelled transcription must
     * never look like a finished one.
     */
    suspend fun transcribe(audioFile: File): Result<TranscriptionResult> =
        withContext(Dispatchers.IO) {
            val job = coroutineContext[Job]
            val cancelCheck: () -> Unit = { job?.ensureActive() }
            try {
                SpeechEngine.requireModel(context)
                val pcm = decoder.decode(audioFile, cancelCheck)
                val engine = SpeechEngine.transcribe(context, pcm, cancelCheck).getOrThrow()
                Log.d(
                    TAG,
                    "Transcribed ${audioFile.name}: ${engine.text.length} chars, " +
                        "${engine.words.size} words, language=${engine.language}"
                )
                Result.success(toResult(engine))
            } catch (e: CancellationException) {
                // Cancellation is not a failure: let it cancel the caller.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed for ${audioFile.name}", e)
                Result.failure(e)
            }
        }

    private fun toResult(engine: SpeechResult): TranscriptionResult =
        TranscriptionResult(
            text = engine.text,
            segments = buildSegments(engine.words),
            language = engine.language
        )

    /**
     * Groups the engine's word timestamps into sentence segments. A segment
     * ends after a word ending in `.`, `!` or `?`. Times come from the real
     * word timestamps — they are never estimated.
     */
    private fun buildSegments(words: List<WordTimestamp>): List<TranscriptSegment> {
        if (words.isEmpty()) return emptyList()
        val segments = mutableListOf<TranscriptSegment>()
        var current = mutableListOf<WordTimestamp>()
        for (word in words) {
            current.add(word)
            val cleaned = word.word.trimEnd()
            if (cleaned.endsWith(".") || cleaned.endsWith("!") || cleaned.endsWith("?")) {
                segments.add(segmentOf(current))
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) {
            segments.add(segmentOf(current))
        }
        return segments
    }

    private fun segmentOf(words: List<WordTimestamp>): TranscriptSegment {
        val text = words.joinToString(" ") { it.word.trim() }.trim()
        val first = words.first()
        val last = words.last()
        return TranscriptSegment(
            text = text,
            startMs = (first.start * 1000.0).roundToLong(),
            endMs = (last.end * 1000.0).roundToLong()
        )
    }

    private companion object {
        const val TAG = "AsrTranscriber"
    }
}
