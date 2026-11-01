package app.verbatim.asr

internal const val MODEL_REQUIRED = "Download Parakeet in Settings to transcribe. Your recording is saved; use Transcribe again after downloading."

internal fun requireSpeechModel(available: Boolean) {
    check(available) { MODEL_REQUIRED }
}

/** On-device recognition output. Parakeet does not report a language code. */
data class SpeechResult(
    val text: String = "",
    val language: String = "",
    val words: List<WordTimestamp> = emptyList()
)

/** A word with timing in seconds from the start of the recording. */
data class WordTimestamp(
    val word: String,
    val start: Double,
    val end: Double,
    val probability: Double
)
