package app.verbatim.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * JVM unit tests for [StreamingResampler]. Fixtures are synthetic signals,
 * no device needed.
 */
class StreamingResamplerTest {

    private fun singleShot(source: FloatArray, sourceRate: Int, targetRate: Int = 16_000): FloatArray {
        val resampler = StreamingResampler(sourceRate, targetRate)
        return resampler.process(source) + resampler.finish()
    }

    private fun concat(parts: List<FloatArray>): FloatArray {
        val total = parts.sumOf { it.size }
        val out = FloatArray(total)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }

    @Test
    fun downsamples44100To16000Sine() {
        val input = FloatArray(44_100) { sin(2.0 * PI * 440.0 * it / 44_100).toFloat() }
        val out = singleShot(input, 44_100)
        val expected = (input.size * 16_000.0 / 44_100.0).toInt() // 16000
        assertTrue("size ${out.size} not close to $expected", out.size in (expected - 4)..(expected + 4))
        assertTrue("output contains non-finite or out-of-range values", out.all { it.isFinite() && it in -1f..1f })
    }

    @Test
    fun downsamples48000To16000Sine() {
        val input = FloatArray(48_000) { sin(2.0 * PI * 440.0 * it / 48_000).toFloat() }
        val out = singleShot(input, 48_000)
        val expected = (input.size * 16_000.0 / 48_000.0).toInt() // 16000
        assertTrue("size ${out.size} not close to $expected", out.size in (expected - 4)..(expected + 4))
        assertTrue("output contains non-finite or out-of-range values", out.all { it.isFinite() && it in -1f..1f })
    }

    @Test
    fun sameRateIsIdentity() {
        val rng = Random(42)
        val input = FloatArray(30_000) { rng.nextFloat() * 2f - 1f }
        val out = singleShot(input, 16_000, 16_000)
        assertEquals(input.size, out.size)
        input.forEachIndexed { i, expected ->
            assertEquals(expected, out[i], 1e-6f)
        }
    }

    @Test
    fun constantSignalStaysConstantAcrossChunkBoundaries() {
        // A constant input must stay constant after any interpolation; this
        // catches a corrupted carried sample at chunk boundaries.
        val rng = Random(7)
        val source = FloatArray(20_000) { 0.5f }
        val resampler = StreamingResampler(44_100)
        var i = 0
        val parts = mutableListOf<FloatArray>()
        while (i < source.size) {
            val length = (1 + rng.nextInt(4_000)).coerceAtMost(source.size - i)
            parts.add(resampler.process(source.copyOfRange(i, i + length)))
            i += length
        }
        parts.add(resampler.finish())
        val out = concat(parts)
        assertTrue(out.isNotEmpty())
        out.forEach { assertEquals(0.5f, it, 1e-6f) }
    }

    @Test
    fun stereoDownmixIsChannelAverage() {
        // +0.5 and 0.0 -> 0.25; -0.5 and 0.0 -> -0.25
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(16_384).putShort(0)
            .putShort(-16_384).putShort(0)
            .array()
        val mono = StreamingResampler.downmixInterleavedPcm16(bytes, 0, bytes.size, channels = 2)
        assertEquals(2, mono.size)
        assertEquals(0.25f, mono[0], 1e-7f)
        assertEquals(-0.25f, mono[1], 1e-7f)
    }

    @Test
    fun monoDownmixScalesToUnitRange() {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(32_767).putShort(-32_768)
            .array()
        val mono = StreamingResampler.downmixInterleavedPcm16(bytes, 0, bytes.size, channels = 1)
        assertEquals(2, mono.size)
        assertTrue(mono[0] > 0.999f && mono[0] <= 1f)
        assertEquals(-1f, mono[1], 0f)
    }

    @Test
    fun chunkedFeedingMatchesSingleShot() {
        val rng = Random(1234)
        val n = 100_000
        val source = FloatArray(n) { rng.nextFloat() * 2f - 1f }

        val oneShot = singleShot(source, 44_100)

        val resampler = StreamingResampler(44_100)
        val parts = mutableListOf<FloatArray>()
        var i = 0
        while (i < n) {
            val length = (1 + rng.nextInt(5_000)).coerceAtMost(n - i)
            parts.add(resampler.process(source.copyOfRange(i, i + length)))
            i += length
        }
        parts.add(resampler.finish())
        val chunked = concat(parts)

        // Total length matches (at worst a couple of tail-clamp samples apart).
        assertTrue(
            "chunked size ${chunked.size} vs single-shot ${oneShot.size}",
            Math.abs(chunked.size - oneShot.size) <= 2
        )
        // The overlapping prefix must be bit-stable: chunked interpolation
        // uses the same samples as single-shot, just fed in bursts.
        val prefix = minOf(chunked.size, oneShot.size)
        for (i in 0 until prefix) {
            assertEquals("mismatch at $i", oneShot[i], chunked[i], 1e-6f)
        }
    }

    @Test
    fun emptyChunksLeaveStateUnchanged() {
        val resampler = StreamingResampler(44_100)
        assertTrue(resampler.process(FloatArray(0)).isEmpty())
        assertTrue(resampler.finish().isEmpty())
        // After a real chunk the stream can end through empty buffers.
        val out = resampler.process(FloatArray(4_410) { 0.1f }) + resampler.finish()
        assertTrue(out.isNotEmpty())
        out.forEach { assertEquals(0.1f, it, 1e-6f) }
    }
}
