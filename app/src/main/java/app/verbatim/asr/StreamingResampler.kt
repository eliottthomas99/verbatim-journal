package app.verbatim.asr

/**
 * Stateful sample-rate converter for mono float PCM.
 *
 * The speech engine requires 16 kHz mono float PCM in [-1, 1], but the codec
 * hands us decoded audio at its original rate (44.1/48 kHz) in bursts of
 * ~1024-sample buffers. This class converts each burst as it arrives and
 * carries the converter state across burst boundaries, so callers never have
 * to hold the whole recording at the original rate in memory.
 *
 * State carried between [process] calls:
 *  - [position] — the fractional source position (in source samples, absolute
 *    coordinates) where the next output sample will be interpolated.
 *  - [lastSample] — the last source sample seen, so the first output samples
 *    of the next chunk interpolate across the boundary with real data instead
 *    of clamping.
 *
 * Resampling is linear interpolation: `out = s0 + f * (s1 - s0)`. No
 * pre-emphasis, mel filtering or windowing — the speech engine owns its own
 * frontend.
 *
 * Pure Kotlin, no Android imports, so it is unit-testable on the JVM.
 */
class StreamingResampler(
    sourceRate: Int,
    targetRate: Int = TARGET_SAMPLE_RATE
) {
    init {
        require(sourceRate > 0) { "Source sample rate must be positive" }
        require(targetRate > 0) { "Target sample rate must be positive" }
    }

    /** source / target, e.g. 44100 / 16000 = 2.75625 source samples per output sample. */
    private val ratio = sourceRate.toDouble() / targetRate

    /** Fractional source position (absolute sample index) of the next output sample. */
    private var position = 0.0

    /** Last source sample seen, carried so the next chunk interpolates across the boundary. */
    private var lastSample: Float? = null

    /** Total source samples seen so far (end of the stream in absolute coordinates). */
    private var totalSamples = 0

    /**
     * Converts one chunk of mono float PCM (values in [-1, 1]) at the source
     * rate. Returns the corresponding output samples at the target rate.
     *
     * Safe to call with zero-size chunks (no state change). The final
     * fractional tail is emitted by [finish] instead, because its second
     * interpolation point lies in a chunk that does not exist yet.
     */
    fun process(monoChunk: FloatArray): FloatArray {
        val size = monoChunk.size
        if (size == 0) return FloatArray(0)
        val base = totalSamples
        totalSamples += size

        val out = ArrayList<Float>()
        var pos = position
        // Emit while both interpolation points are known in this chunk
        // (s0 may be the carried sample from the previous chunk).
        while (pos - base < size - 1) {
            val i0 = pos.toInt() // floor (pos is always >= 0)
            val frac = pos - i0
            val local = i0 - base
            val s0 = if (local >= 0) monoChunk[local] else lastSample!!
            // The loop condition guarantees local + 1 < size, except for the
            // carried case; clamp defensively either way.
            val s1 = if (local + 1 < size) monoChunk[local + 1] else s0
            out.add((s0 + frac * (s1 - s0)).toFloat())
            pos += ratio
        }
        lastSample = monoChunk[size - 1]
        position = pos
        return out.toFloatArray()
    }

    /**
     * Emits the remaining tail samples after the final chunk, clamped to the
     * last source sample (a few samples at most; the audio is effectively
     * finished so flat values are inaudible). Calling it twice returns empty.
     */
    fun finish(): FloatArray {
        val end = totalSamples
        val last = lastSample
        if (last == null || position >= end) return FloatArray(0)
        val out = ArrayList<Float>()
        var pos = position
        while (pos < end) {
            // s0 is the final source sample and s1 clamps to it, so the
            // interpolated value is exactly the last sample.
            out.add(last)
            pos += ratio
        }
        position = pos
        return out.toFloatArray()
    }

    companion object {
        /** Parakeet requires 16 kHz input. */
        const val TARGET_SAMPLE_RATE = 16_000

        /**
         * Converts interleaved 16-bit signed little-endian PCM to mono float in
         * [-1, 1] by averaging the channels. Pure function over raw bytes so it
         * is testable on the JVM.
         */
        fun downmixInterleavedPcm16(bytes: ByteArray, offset: Int, size: Int, channels: Int): FloatArray {
            require(channels >= 1) { "Channel count must be positive" }
            require(offset >= 0 && size >= 0 && offset + size <= bytes.size) {
                "Offset/size out of bounds for ${bytes.size} bytes"
            }
            require(size % 2 == 0) { "16-bit PCM size must be an even number of bytes" }
            val frames = size / 2 / channels
            val out = FloatArray(frames)
            for (frame in 0 until frames) {
                var sum = 0
                var index = offset + frame * channels * 2
                for (channel in 0 until channels) {
                    // Little-endian int16: mask the low byte, sign-extend the high byte.
                    sum += (bytes[index].toInt() and 0xFF) or (bytes[index + 1].toInt() shl 8)
                    index += 2
                }
                out[frame] = sum / channels / 32768f
            }
            return out
        }
    }
}
