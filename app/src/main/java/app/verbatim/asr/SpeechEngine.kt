package app.verbatim.asr

import android.content.Context
import android.util.Log
import app.verbatim.data.AppPrefs
import kotlinx.coroutines.CancellationException

/**
 * Picks the on-device speech engine the user chose in Settings: Whistle (light,
 * bundled) or Parakeet (accurate, downloaded). If Parakeet fails the recording
 * still gets a transcript from Whistle.
 */
object SpeechEngine {
    private const val TAG = "SpeechEngine"

    private fun usesParakeet(context: Context) =
        AppPrefs.speechEngine(context) == AppPrefs.ENGINE_PARAKEET && ParakeetModel.isReady(context)

    /** Blocking — call it off the main thread. */
    fun transcribe(context: Context, pcm: FloatArray, checkCancelled: () -> Unit = {}): Result<WhistleResult> {
        if (usesParakeet(context)) {
            try {
                return Result.success(ParakeetEngine.transcribe(context, pcm, checkCancelled))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Parakeet failed, falling back to Whistle", e)
            } catch (e: LinkageError) {
                Log.e(TAG, "Parakeet unavailable, falling back to Whistle", e)
            }
        }
        return WhistleNative.transcribe(pcm)
    }

    /** Avoids the model's load time once a transcription is about to be needed. */
    fun preload(context: Context) {
        if (!usesParakeet(context)) return
        runCatching { ParakeetEngine.preload(context) }
            .onFailure { Log.w(TAG, "Parakeet preload failed", it) }
    }
}
