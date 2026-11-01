package app.verbatim.asr

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes an AAC/M4A recording into 16 kHz mono float PCM in [-1, 1], the
 * exact input format the Parakeet engine expects.
 *
 * Per decoded output buffer the pipeline is:
 *   copy the valid bytes (respecting `BufferInfo.offset` and `BufferInfo.size`)
 *   → downmix interleaved channels to mono
 *   → stream-resample to 16 kHz
 *   → keep only the resampled chunk.
 *
 * The decoded 44.1/48 kHz stereo PCM is never accumulated: only the 16 kHz
 * mono chunks (the engine's required input) are kept, so memory stays bounded
 * even for long recordings.
 *
 * Internal — the public entry point is [AsrTranscriber.transcribe].
 */
internal class M4aDecoder {

    /** The output format the decoder actually produced, read from the codec. */
    private data class OutputSpec(
        val sampleRate: Int,
        val channelCount: Int,
        val pcmEncoding: Int
    )

    /**
     * Decodes [file] completely.
     *
     * @param cancelCheck invoked before every codec interaction; throw
     *        (typically a CancellationException) to abort the decode.
     * @return 16 kHz mono float PCM in [-1, 1]. May be empty for a stream
     *         without audio frames.
     * @throws Exception for a missing file, a corrupt container, a missing
     *         audio track, an unsupported output format, or a codec failure.
     */
    fun decode(file: File, cancelCheck: () -> Unit): FloatArray {
        if (!file.isFile) {
            throw IllegalArgumentException("Audio file not found: ${file.absolutePath}")
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            // Throws for a corrupt container (e.g. random bytes).
            extractor.setDataSource(file.absolutePath)

            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i
                    break
                }
            }
            if (trackIndex < 0) {
                throw IllegalStateException("No audio track in ${file.name}")
            }

            extractor.selectTrack(trackIndex)
            val trackFormat = extractor.getTrackFormat(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Audio track in ${file.name} has no MIME type")

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            return drain(decoder, extractor, cancelCheck)
        } finally {
            // Release the codec and the extractor on success, cancellation and
            // failure alike.
            try {
                if (codec != null) {
                    try {
                        codec.stop()
                    } catch (_: Exception) {
                        // A codec that never started cannot be stopped.
                    }
                    codec.release()
                }
            } finally {
                extractor.release()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun drain(
        codec: MediaCodec,
        extractor: MediaExtractor,
        cancelCheck: () -> Unit
    ): FloatArray {
        val chunks = ArrayList<FloatArray>()
        val info = MediaCodec.BufferInfo()

        var resampler: StreamingResampler? = null
        var spec: OutputSpec? = null
        var inputDone = false
        // Index of the dequeued-but-not-yet-released output buffer; released in
        // the finally block if we leave early (a real leak otherwise).
        var pendingOutput = -1
        var lastProgressAt = SystemClock.elapsedRealtime()

        try {
            while (true) {
                cancelCheck()

                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("Decoder input buffer $inputIndex is null")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        lastProgressAt = SystemClock.elapsedRealtime()
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outputIndex >= 0 -> {
                        pendingOutput = outputIndex
                        try {
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                // Codec-specific data, not PCM.
                                codec.releaseOutputBuffer(outputIndex, false)
                                pendingOutput = -1
                                continue
                            }
                            if (info.size > 0) {
                                // Read the format the decoder really produced;
                                // never assume 44.1 kHz stereo 16-bit.
                                val current = spec ?: readSpec(codec.outputFormat)
                                    ?: throw IllegalStateException(
                                        "Decoder output format is missing the sample rate or channel count"
                                    )
                                spec = current
                                val activeResampler = resampler
                                    ?: StreamingResampler(current.sampleRate).also { resampler = it }
                                val buffer = codec.getOutputBuffer(outputIndex)
                                    ?: throw IllegalStateException("Decoder output buffer $outputIndex is null")
                                val mono = decodeToMono(
                                    buffer, info.offset, info.size, current.channelCount, current.pcmEncoding
                                )
                                chunks.add(activeResampler.process(mono))
                            }
                        } finally {
                            if (pendingOutput >= 0) {
                                codec.releaseOutputBuffer(pendingOutput, false)
                                pendingOutput = -1
                            }
                        }
                        lastProgressAt = SystemClock.elapsedRealtime()
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // The new format is read when the first audio buffer arrives.
                        spec = null
                    }

                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output yet; keep feeding input.
                    }

                    @Suppress("DEPRECATION")
                    outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        // Buffers were reallocated; nothing to do.
                    }

                    else -> throw IllegalStateException("Unexpected decoder state: $outputIndex")
                }

                if (SystemClock.elapsedRealtime() - lastProgressAt > STALL_TIMEOUT_MS) {
                    throw IllegalStateException("Audio decoder stalled")
                }
            }

            resampler?.let { chunks.add(it.finish()) }
        } finally {
            if (pendingOutput >= 0) {
                try {
                    codec.releaseOutputBuffer(pendingOutput, false)
                } catch (_: Exception) {
                    // The codec is being torn down anyway.
                }
            }
        }

        val total = chunks.sumOf { it.size }
        val pcm = FloatArray(total)
        var offset = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, pcm, offset, chunk.size)
            offset += chunk.size
        }
        return pcm
    }

    /** Reads the decoder output format, or null when a value is missing. */
    private fun readSpec(format: MediaFormat): OutputSpec? {
        val sampleRate = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: run {
            Log.w(TAG, "Decoder output format has no sample rate")
            return null
        }
        val channelCount = format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: run {
            Log.w(TAG, "Decoder output format has no channel count")
            return null
        }
        // The AAC decoder on Android does not publish KEY_PCM_ENCODING; the
        // platform default for raw PCM output is 16-bit signed. Logged so the
        // fallback is never a silent assumption.
        val pcmEncoding = format.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING)
            ?: AudioFormat.ENCODING_PCM_16BIT.also {
                Log.w(TAG, "Decoder output format has no PCM encoding, using the 16-bit default")
            }
        return OutputSpec(sampleRate, channelCount, pcmEncoding)
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    /**
     * Converts one decoded output buffer to mono float in [-1, 1].
     *
     * 16-bit PCM is little-endian on every device we ship (arm64 only).
     */
    private fun decodeToMono(
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        channels: Int,
        encoding: Int
    ): FloatArray = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> {
            val data = ByteArray(size)
            // Buffer.position/limit return Buffer on Android, so no chaining.
            val copy = buffer.duplicate()
            copy.position(offset)
            copy.limit(offset + size)
            copy.get(data)
            StreamingResampler.downmixInterleavedPcm16(data, 0, data.size, channels)
        }

        AudioFormat.ENCODING_PCM_FLOAT -> {
            val frames = size / 4 / channels
            val out = FloatArray(frames)
            val source = buffer.duplicate().order(ByteOrder.nativeOrder())
            for (frame in 0 until frames) {
                var sum = 0f
                for (channel in 0 until channels) {
                    source.position(offset + (frame * channels + channel) * 4)
                    sum += source.getFloat()
                }
                out[frame] = sum / channels
            }
            out
        }

        else -> throw IllegalStateException("Unsupported decoder PCM encoding: $encoding")
    }

    private companion object {
        const val TAG = "M4aDecoder"

        /** 10 ms: short enough to stay responsive to cancellation. */
        const val TIMEOUT_US = 10_000L

        /** The decoder must produce something within this window. */
        const val STALL_TIMEOUT_MS = 10_000L
    }
}
