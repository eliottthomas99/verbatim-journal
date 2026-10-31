package app.verbatim.voice

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reads answers aloud, on the phone.
 *
 * Only the bundled Piper voices ([PiperVoices], French + English) are used.
 * A system engine may send text to the cloud, so failure never falls back to one.
 */
class AnswerSpeaker(context: Context) {

    /** Compose state: lets the UI show "stop" while speaking. */
    var isSpeaking by mutableStateOf(false)
        private set

    /** Why the last answer could not be read, for the UI ("" if fine). */
    var problem by mutableStateOf("")
        private set

    /** The app's own bundled voices — the first choice. */
    private val piper = PiperVoices(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var piperJob: Job? = null

    init {
        // The speaker is created when the Ask screen opens: load the French
        // voice (most answers) while the question is being typed or answered.
        scope.launch { runCatching { piper.preload("fr") } }
    }

    /** Reads [text] using bundled voices only; reports an error if they fail. */
    fun speak(text: String) {
        stop()
        problem = ""
        isSpeaking = true
        val french = looksFrench(text)
        piperJob = scope.launch {
            try {
                piper.speakBlocking(plainText(text), if (french) "fr" else "en")
                // A newer answer may have replaced this one (stop() cancels this job).
                if (isActive) isSpeaking = false
            } catch (e: Throwable) { // includes a missing native library (LinkageError)
                if (!isActive) return@launch
                Log.e(TAG, "Bundled voice failed", e)
                isSpeaking = false
                problem = "The offline voice is unavailable. Your answer was not sent to a system speech engine."
            }
        }
    }

    fun stop() {
        piper.stop()
        piperJob?.cancel()
        isSpeaking = false
    }

    fun shutdown() {
        stop()
        scope.cancel()
        // Free the voices once the last sentence has stopped (speakBlocking holds the same lock).
        Thread { piper.release() }.start()
    }

    private companion object {
        const val TAG = "AnswerSpeaker"

        val FRENCH_WORDS = Regex("""\b(le|la|les|des|je|vous|tu|est|et|pas|une|avec|pour|qui|que|hier|demain)\b""", RegexOption.IGNORE_CASE)
        val ENGLISH_WORDS = Regex("""\b(the|you|and|is|was|with|for|who|what|yesterday|tomorrow)\b""", RegexOption.IGNORE_CASE)

        fun looksFrench(text: String) =
            FRENCH_WORDS.findAll(text).count() + text.count { it in "éèàçêù" } > ENGLISH_WORDS.findAll(text).count()
    }
}

/** Removes Markdown leftovers (**bold**, # titles, list bullets) for display and speech. */
fun plainText(text: String): String = text
    .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
    .replace(Regex("""(?m)^#+\s*"""), "")
    .replace(Regex("""(?m)^\s*[*-]\s+"""), "• ")
