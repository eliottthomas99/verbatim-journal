package app.verbatim.voice

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads answers aloud, on the phone.
 *
 * First choice: the app's own Piper voices ([PiperVoices], French + English,
 * bundled). Fallback, only if they fail: the installed system engines, each
 * answer going to one that has a voice for its language (some engines only
 * speak English). Without one, [problem] says so
 * instead of reading French with English pronunciation.
 */
class AnswerSpeaker(context: Context) {

    /** Compose state: lets the UI show "stop" while speaking. */
    var isSpeaking by mutableStateOf(false)
        private set

    /** Why the last answer could not be read, for the UI ("" if fine). */
    var problem by mutableStateOf("")
        private set

    private inner class Engine(val pkg: String, context: Context) {
        @Volatile
        var ready = false

        // The init callback must not touch `tts`: Android may call it from
        // inside the constructor (it does when the engine cannot be bound).
        val tts = TextToSpeech(context, { status -> ready = status == TextToSpeech.SUCCESS }, pkg).apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                // this@AnswerSpeaker: inside apply{}, plain `isSpeaking` would be TextToSpeech's own.
                override fun onStart(utteranceId: String?) { this@AnswerSpeaker.isSpeaking = true }
                override fun onDone(utteranceId: String?) { this@AnswerSpeaker.isSpeaking = false }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { this@AnswerSpeaker.isSpeaking = false }
            })
        }

        fun speaks(locale: Locale) = ready && tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
    }

    // The default engine first (if it is really installed: the system setting can
    // name "com.google.android.tts" on a phone without it), then the others.
    private val engines: List<Engine> = run {
        val installed = context.packageManager
            .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .map { it.serviceInfo.packageName }
            .distinct()
        val default = Settings.Secure.getString(context.contentResolver, "tts_default_synth")
        installed.sortedByDescending { it == default }.map { Engine(it, context.applicationContext) }
    }

    /** The app's own bundled voices — the first choice. */
    private val piper = PiperVoices(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var piperJob: Job? = null

    init {
        // The speaker is created when the Ask screen opens: load the French
        // voice (most answers) while the question is being typed or answered.
        scope.launch { runCatching { piper.preload("fr") } }
    }

    /** Reads [text] aloud with the bundled voices; falls back to the system engines if they fail. */
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
                Log.e(TAG, "Bundled voice failed, trying the system engines", e)
                isSpeaking = false
                withContext(Dispatchers.Main) { speakWithSystem(text, french) }
            }
        }
    }

    /** Returns false (and sets [problem]) if no installed engine speaks the answer's language. */
    private fun speakWithSystem(text: String, french: Boolean): Boolean {
        val locale = if (french) Locale.FRENCH else Locale.ENGLISH
        val engine = engines.firstOrNull { it.speaks(locale) }
        if (engine == null) {
            problem = when {
                engines.none { it.ready } -> "No text-to-speech engine is available on this phone."
                french -> "No French voice installed. Install an offline engine with a French voice (e.g. SherpaTTS) in Settings › Speech."
                else -> "No English voice installed."
            }
            return false
        }
        problem = ""
        engine.tts.language = locale
        engine.tts.speak(plainText(text), TextToSpeech.QUEUE_FLUSH, null, "answer")
        return true
    }

    fun stop() {
        piper.stop()
        piperJob?.cancel()
        engines.forEach { it.tts.stop() }
        isSpeaking = false
    }

    fun shutdown() {
        stop()
        scope.cancel()
        engines.forEach { it.tts.shutdown() }
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
