package app.verbatim.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.verbatim.JournalApplication
import app.verbatim.asr.SpeechEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Spoken questions, recognized **on the phone** by the same engine as the
 * recordings. System speech recognizers may use the cloud, so this app doesn't
 * use them: the voice never leaves the device.
 *
 * AudioRecord gives raw microphone samples, already at the 16 kHz mono float
 * format the engines want — no file, no decoding.
 */
class VoiceQuestion {
    @Volatile
    private var listening = false

    /** Records until [stop] is called (or [maxMs]); returns the recognized text. */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the caller
    suspend fun listenAndTranscribe(maxMs: Long = 30_000, onListeningDone: () -> Unit = {}): String {
        val app = JournalApplication.instance
        // Load the engine while the user speaks, so the answer doesn't wait for it.
        app.applicationScope.launch { SpeechEngine.preload(app) }
        val pcm = record(maxMs)
        onListeningDone()
        if (pcm.size < SAMPLE_RATE / 2) return "" // under half a second: nothing said
        return withContext(Dispatchers.Default) {
            SpeechEngine.transcribe(app, pcm).getOrThrow().text.trim()
        }
    }

    fun stop() {
        listening = false
    }

    @SuppressLint("MissingPermission")
    private suspend fun record(maxMs: Long): FloatArray = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            maxOf(minBuffer, SAMPLE_RATE / 5 * 4) // >= 200 ms of floats
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
        val parts = ArrayList<FloatArray>()
        var total = 0
        val maxSamples = (SAMPLE_RATE * maxMs / 1000).toInt()
        val chunk = FloatArray(SAMPLE_RATE / 10) // 100 ms
        listening = true
        try {
            recorder.startRecording()
            while (listening && total < maxSamples) {
                coroutineContext.ensureActive()
                val n = recorder.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                check(n >= 0) { "Microphone read failed ($n)" }
                parts.add(chunk.copyOf(n))
                total += n
            }
        } finally {
            listening = false
            runCatching { recorder.stop() }
            recorder.release()
        }
        val pcm = FloatArray(total)
        var offset = 0
        for (part in parts) {
            part.copyInto(pcm, offset)
            offset += part.size
        }
        // The engines require samples in [-1, 1]; float capture can slightly overshoot.
        for (i in pcm.indices) pcm[i] = pcm[i].coerceIn(-1f, 1f)
        pcm
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
