package app.verbatim.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * Runs Parakeet on this phone. Missing models and inference failures are
 * reported to the caller; there is no alternate or cloud speech engine.
 */
object SpeechEngine {
    private const val TAG = "SpeechEngine"

    fun requireModel(context: Context) {
        requireSpeechModel(ParakeetModel.isReady(context))
    }

    /** Blocking — call it off the main thread. */
    fun transcribe(context: Context, pcm: FloatArray, checkCancelled: () -> Unit = {}): Result<SpeechResult> {
        try {
            requireModel(context)
            return Result.success(ParakeetEngine.transcribe(context, pcm, checkCancelled))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        } catch (e: LinkageError) {
            return Result.failure(IllegalStateException("Parakeet could not load. Your recording is saved; try transcribing again.", e))
        }
    }

    /** Avoids the model's load time once a transcription is about to be needed. */
    fun preload(context: Context) {
        if (!ParakeetModel.isReady(context)) return
        runCatching { ParakeetEngine.preload(context) }
            .onFailure { Log.w(TAG, "Parakeet preload failed", it) }
    }
}
