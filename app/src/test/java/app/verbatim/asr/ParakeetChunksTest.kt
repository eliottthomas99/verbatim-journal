package app.verbatim.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParakeetChunksTest {
    private val rate = 16_000

    private fun loud(seconds: Int) = FloatArray(seconds * rate) { 0.5f }

    @Test
    fun emptyAudioHasNoChunks() {
        assertTrue(planChunks(FloatArray(0)).isEmpty())
    }

    @Test
    fun shortAudioIsOneChunk() {
        assertEquals(listOf(0 to 20 * rate), planChunks(loud(20)))
    }

    @Test
    fun audioJustOverTheLimitIsNotSplitIntoATinyPiece() {
        assertEquals(listOf(0 to 34 * rate), planChunks(loud(34)))
    }

    @Test
    fun chunksAreContiguousAndCoverEverything() {
        val pcm = loud(600)
        val chunks = planChunks(pcm)
        assertEquals(0, chunks.first().first)
        assertEquals(pcm.size, chunks.last().second)
        chunks.zipWithNext { a, b -> assertEquals(a.second, b.first) }
        assertTrue(chunks.dropLast(1).all { (s, e) -> e - s <= 30 * rate })
        assertTrue(chunks.all { (s, e) -> e - s >= 5 * rate })
    }

    @Test
    fun cutFallsInTheSilence() {
        val pcm = loud(60)
        // One second of silence between 24 s and 25 s, inside the 20-30 s search window.
        pcm.fill(0f, 24 * rate, 25 * rate)
        val cut = planChunks(pcm).first().second
        assertTrue("cut at $cut", cut in 24 * rate until 25 * rate)
    }

    @Test
    fun fragmentsBecomeWordsWithPunctuationAttached() {
        val words = wordsFromTokens(
            arrayOf(" B", "on", "jo", "ur", ",", " ce", "ci"),
            floatArrayOf(0.0f, 0.2f, 0.4f, 0.6f, 0.8f, 1.0f, 1.2f),
            offsetSeconds = 30.0,
            chunkEndSeconds = 60.0
        )
        assertEquals(listOf("Bonjour,", "ceci"), words.map { it.word })
        assertEquals(30.0, words[0].start, 0.001)
        assertEquals(31.0, words[0].end, 0.001)
        assertEquals(31.0, words[1].start, 0.001)
        assertEquals(31.7, words[1].end, 0.001)
    }

    @Test
    fun lastWordStaysInsideItsChunk() {
        val words = wordsFromTokens(arrayOf(" fin"), floatArrayOf(9.9f), 0.0, 10.0)
        assertEquals(10.0, words.single().end, 0.001)
    }

    @Test
    fun noTokensNoWords() {
        assertTrue(wordsFromTokens(emptyArray(), FloatArray(0), 0.0, 5.0).isEmpty())
    }
}
