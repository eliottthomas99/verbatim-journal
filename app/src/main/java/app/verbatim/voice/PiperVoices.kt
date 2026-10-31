package app.verbatim.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * The app's own voices: Piper models (French + English) bundled in the APK
 * and run on the phone by sherpa-onnx — no system engine, no network.
 *
 * A voice is loaded on first use (~1 s). Audio is generated sentence by
 * sentence and each sentence is played as soon as it is ready, so speech
 * starts quickly even for a long answer.
 */
class PiperVoices(private val context: Context) {
    private val voices = HashMap<String, OfflineTts>()

    @Volatile
    private var stopRequested = false

    /** Synthesizes and plays [text]; returns when finished or [stop]ped.
     *  Blocking — call it off the main thread. [lang] is "fr" or "en". */
    @Synchronized // one sentence stream at a time; the native engine is not shared across threads
    fun speakBlocking(text: String, lang: String) {
        val tts = voice(lang)
        stopRequested = false
        val rate = tts.sampleRate()
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT) * 4
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // Generation and playback overlap: the engine drops each finished
        // sentence into a queue and goes on with the next one, while this
        // thread plays the queue. (Writing to the speaker from the engine's
        // callback made them take turns: a silence before every sentence.)
        val queue = LinkedBlockingQueue<FloatArray>()
        val player = thread(name = "piper-playback") {
            var written = 0L
            try {
                while (true) {
                    val chunk = queue.take()
                    if (chunk === END || stopRequested) break
                    track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                    written += chunk.size
                }
                // Let the last sentence finish playing before releasing the track.
                while (!stopRequested && track.playbackHeadPosition < written) Thread.sleep(50)
            } catch (_: InterruptedException) {
            }
        }
        try {
            track.play()
            tts.generateWithCallback(text, 0, 1.0f, ChunkSink(queue))
            queue.put(END)
            player.join()
        } finally {
            player.interrupt()
            runCatching { track.stop() }
            track.release()
        }
    }

    /**
     * Receives each generated chunk; returning 0 stops generation.
     *
     * A real class, not a lambda: the native code looks up the exact JVM
     * method `invoke(float[]): Integer`, and Kotlin 2 compiles lambdas to a
     * generic `invoke(Object): Object` only (→ NoSuchMethodError + abort).
     */
    private inner class ChunkSink(private val queue: LinkedBlockingQueue<FloatArray>) : (FloatArray) -> Int {
        override fun invoke(samples: FloatArray): Int {
            if (stopRequested) return 0
            queue.put(samples.copyOf()) // the native side may reuse its buffer
            return 1
        }
    }

    fun stop() {
        stopRequested = true
    }

    /** Load a voice ahead of time (~1 s), so the first answer starts faster. Blocking. */
    @Synchronized
    fun preload(lang: String) {
        voice(lang)
    }

    fun release() = synchronized(this) {
        voices.values.forEach { it.release() }
        voices.clear()
    }

    private fun voice(lang: String): OfflineTts = voices.getOrPut(lang) {
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "tts/$lang/model.onnx", // read straight from the APK assets
                    tokens = "tts/$lang/tokens.txt",
                    dataDir = espeakDir().absolutePath, // must be a real folder
                ),
                numThreads = 4, // 2 gave audible gaps between sentences on a test phone
                debug = false,
                provider = "cpu",
            )
        )
        Log.d(TAG, "Loading Piper voice '$lang'")
        OfflineTts(assetManager = context.assets, config = config)
    }

    /** espeak-ng (text → phonemes) needs its data as files: copy it out of the APK once. */
    private fun espeakDir(): File {
        val dir = File(context.filesDir, "tts-espeak-ng-data-v1")
        val done = File(dir, ".complete")
        if (!done.exists()) {
            dir.deleteRecursively()
            copyAssetDir("tts/espeak-ng-data", dir)
            done.createNewFile() // only once everything is copied
        }
        return dir
    }

    private fun copyAssetDir(assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) { // a file
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            target.mkdirs()
            children.forEach { copyAssetDir("$assetPath/$it", File(target, it)) }
        }
    }

    private companion object {
        const val TAG = "PiperVoices"
        /** Marks the end of the sentences in the playback queue. */
        val END = FloatArray(0)
    }
}
