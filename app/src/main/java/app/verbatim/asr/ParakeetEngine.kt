package app.verbatim.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import app.verbatim.JournalApplication
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * NVIDIA Parakeet TDT 0.6B v3, run on the phone by sherpa-onnx. The audio stays
 * on the device. The model takes ~1 GB of memory and ~3.5 s to load, so it is
 * loaded on demand and released after a couple of idle minutes.
 */
object ParakeetEngine {
    private const val TAG = "ParakeetEngine"
    private const val SAMPLE_RATE = 16_000
    private const val THREADS = 4
    private const val IDLE_RELEASE_MS = 120_000L

    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var releaseJob: Job? = null

    /** Loads the model ahead of a transcription that is about to be needed. */
    fun preload(context: Context) = synchronized(lock) {
        loaded(context)
        scheduleRelease()
    }

    /** Blocking — call it off the main thread. [checkCancelled] runs between chunks. */
    fun transcribe(context: Context, pcm: FloatArray, checkCancelled: () -> Unit = {}): WhistleResult =
        synchronized(lock) {
            releaseJob?.cancel()
            try {
                val engine = loaded(context)
                val texts = mutableListOf<String>()
                val words = mutableListOf<WordTimestamp>()
                for ((start, end) in planChunks(pcm, SAMPLE_RATE)) {
                    checkCancelled()
                    val stream = engine.createStream()
                    try {
                        stream.acceptWaveform(pcm.copyOfRange(start, end), SAMPLE_RATE)
                        engine.decode(stream)
                        val result = engine.getResult(stream)
                        result.text.trim().takeIf { it.isNotEmpty() }?.let(texts::add)
                        words += wordsFromTokens(
                            result.tokens,
                            result.timestamps,
                            start.toDouble() / SAMPLE_RATE,
                            end.toDouble() / SAMPLE_RATE
                        )
                    } finally {
                        stream.release()
                    }
                }
                WhistleResult(text = texts.joinToString(" "), words = words)
            } finally {
                scheduleRelease()
            }
        }

    /** Free the memory now (the model files are being deleted). */
    fun release() = synchronized(lock) {
        releaseJob?.cancel()
        recognizer?.release()
        recognizer = null
    }

    private fun loaded(context: Context): OfflineRecognizer {
        recognizer?.let { return it }
        val started = System.currentTimeMillis()
        val dir = ParakeetModel.directory(context)
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$dir/encoder.int8.onnx",
                    decoder = "$dir/decoder.int8.onnx",
                    joiner = "$dir/joiner.int8.onnx"
                ),
                tokens = "$dir/tokens.txt",
                numThreads = THREADS,
                modelType = "nemo_transducer"
            )
        )
        return OfflineRecognizer(config = config).also {
            recognizer = it
            Log.d(TAG, "Parakeet loaded in ${System.currentTimeMillis() - started} ms")
        }
    }

    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = JournalApplication.instance.applicationScope.launch {
            delay(IDLE_RELEASE_MS)
            release()
            Log.d(TAG, "Parakeet released after being idle")
        }
    }
}
