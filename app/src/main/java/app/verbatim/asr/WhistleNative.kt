/*
 * WhistleNative.kt — Kotlin wrapper for the Needle (Cactus Whistle) C API.
 *
 * Exposes a thread-safe, Kotlin-friendly interface:
 *   loadModel(path)    — map a verified .cact (called once at startup)
 *   transcribe(float[])— transcribe 16 kHz mono float PCM, returns JSON text
 *   loadModelFromAssets() — convenience: reads bundled .cact from APK
 *
 * IMPORTANT: Needle is process-global and NOT thread-safe. All calls
 * are serialized by a synchronized block / single worker.
 */

package app.verbatim.asr

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val TAG = "WhistleNative"

/**
 * Result of a transcription run.
 *
 * @param text The transcribed text (empty for silence).
 * @param language The detected language code (e.g. "en", "fr").
 * @param ttftMs Milliseconds to first token (performance metric).
 * @param decodeTps Tokens per second after first token.
 * @param words Word-level timestamps (when word_timestamps=1).
 */
data class WhistleResult(
    val text: String = "",
    val language: String = "",
    val ttftMs: Double = 0.0,
    val decodeTps: Double = 0.0,
    val words: List<WordTimestamp> = emptyList()
)

/** A single word with timing (seconds). */
data class WordTimestamp(
    val word: String,
    val start: Double,
    val end: Double,
    val probability: Double
)

/**
 * Manages the Needle (Whistle) native engine.
 *
 * Usage (in JournalApplication.onCreate):
 *   WhistleNative.loadModelFromAssets(context).getOrThrow() // background thread
 *
 * Usage (per recording):
 *   val result = WhistleNative.transcribe(pcmFloatArray)
 */
object WhistleNative {
    private const val MODEL_SIZE = 16_919_407L
    private const val MODEL_HASH = "b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb"
    private val lock = Any()
    private var modelLoaded = false

    // ---------- JNI declarations ----------
    // These match the C functions in needle_jni.c.

    /** Maps a verified model file and retains it for the process lifetime. */
    private external fun loadModel(path: String): Int

    /**
     * Transcribe 16 kHz mono float PCM in [-1, 1].
     * Returns UTF-8 JSON bytes; native failures throw immediately.
     */
    private external fun transcribePcm(
        pcm: FloatArray,
        outCapacity: Int
    ): ByteArray

    /** Returns a bitmask of loaded models (NEEDLE_TEXT = 1, NEEDLE_SPEECH = 2). */
    private external fun modelsAvailable(): Int

    /** Loads the shared JNI library. */
    init {
        try {
            System.loadLibrary("verbatim-jni")
            Log.d(TAG, "Native library 'verbatim-jni' loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}")
            throw e
        }
    }

    // ---------- Public API ----------

    /**
     * Load the bundled Whistle model from APK assets.
     * Must be called once, before any transcribe call.
     * Returns true on success, false on failure.
     */
    fun loadModelFromAssets(context: Context): Result<Unit> = synchronized(lock) {
        runCatching {
            if (!modelLoaded) {
                val model = prepareModel(context)
                check(loadModel(model.absolutePath) >= 0) { "Whistle model loading failed" }
                check(modelsAvailable().and(NEEDLE_SPEECH) != 0) { "Speech model not available" }
                modelLoaded = true
                Log.d(TAG, "Verified Whistle speech model loaded (automatic language detection)")
            }
        }
    }

    /**
     * Transcribe 16 kHz mono float PCM (values in [-1, 1]).
     * Language is auto-detected (NULL passed to native).
     * Word timestamps are enabled.
     *
     * Returns a parsed WhistleResult, or an error. Call off the UI thread.
     * All model operations are serialized, including loading and error retrieval.
     */
    fun transcribe(pcm: FloatArray): Result<WhistleResult> = synchronized(lock) {
        runCatching {
            check(modelLoaded) { "Whistle model is not ready" }
            require(pcm.all { it.isFinite() && it in -1f..1f }) { "Expected finite normalized PCM samples" }
            if (pcm.isEmpty()) return@runCatching WhistleResult()
            // Duration-scaled output, with headroom for word timestamps. Oversize input
            // fails explicitly; callers need bounded audio decoding/chunks.
            val capacity = maxOf(262_144L, ((pcm.size.toLong() + 15999) / 16000) * 8192)
            require(capacity <= 64 * 1024 * 1024) { "Audio must be processed in bounded chunks" }
            val json = transcribePcm(pcm, capacity.toInt()).toString(Charsets.UTF_8)
            parseJson(json)
        }
    }

    /**
     * Returns true if the speech model is available.
     */
    fun isModelLoaded(): Boolean = synchronized(lock) { modelLoaded }

    // ---------- JSON parsing ----------

    private fun parseJson(json: String): WhistleResult {
        val obj = JSONObject(json)

        val text = obj.getString("text")
        val language = obj.getString("language")
        val ttftMs = obj.optDouble("ttft_ms", 0.0)
        val decodeTps = obj.optDouble("decode_tps", 0.0)

        val words = mutableListOf<WordTimestamp>()
        if (obj.has("words")) {
                val arr = obj.getJSONArray("words")
                for (i in 0 until arr.length()) {
                    val w = arr.getJSONObject(i)
                    words.add(
                        WordTimestamp(
                            word = w.getString("word"),
                            start = w.getDouble("start"),
                            end = w.getDouble("end"),
                            probability = w.getDouble("probability")
                        )
                    )
                }
        }

        return WhistleResult(
            text = text,
            language = language,
            ttftMs = ttftMs,
            decodeTps = decodeTps,
            words = words
        )
    }

    // Needle constants (from needle.h)
    private const val NEEDLE_SPEECH = 2

    // ---------- Asset verification (defensive) ----------

    private fun validModel(file: File): Boolean {
        if (!file.isFile || file.length() != MODEL_SIZE) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == MODEL_HASH
    }

    // Called under the same lock as inference, on a background dispatcher.
    internal fun prepareModel(context: Context): File = synchronized(lock) {
        val directory = File(context.filesDir, "whistle")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create model directory" }
        val model = File(directory, "whistle.cact")
        if (validModel(model)) return model
        val temporary = File(directory, "whistle.cact.part")
        try {
            context.assets.open("asr/whistle.cact").use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(validModel(temporary)) { "Bundled Whistle model failed integrity verification" }
            Files.move(temporary.toPath(), model.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
        model
    }
}
