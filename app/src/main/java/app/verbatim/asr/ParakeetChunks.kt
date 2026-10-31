package app.verbatim.asr

import kotlin.math.max
import kotlin.math.min

/**
 * Parakeet's memory grows with the length of its input (measured on a test phone:
 * 1 GB for 42 s, 2.8 GB for 5 min), so long recordings are cut into short pieces.
 * Each cut falls on the quietest moment near the limit, to avoid splitting a word.
 *
 * Returns contiguous `[start, end)` sample ranges covering the whole audio.
 */
internal fun planChunks(
    pcm: FloatArray,
    sampleRate: Int = 16_000,
    maxSeconds: Int = 30,
    searchSeconds: Int = 10,
    // A slightly long last piece beats a tiny one the model can't make sense of.
    toleranceSeconds: Int = 5
): List<Pair<Int, Int>> {
    if (pcm.isEmpty()) return emptyList()
    val maxLength = maxSeconds * sampleRate
    val chunks = mutableListOf<Pair<Int, Int>>()
    var start = 0
    while (pcm.size - start > maxLength + toleranceSeconds * sampleRate) {
        val searchEnd = start + maxLength
        val cut = quietestPoint(pcm, searchEnd - searchSeconds * sampleRate, searchEnd, sampleRate / 10)
        chunks.add(start to cut)
        start = cut
    }
    chunks.add(start to pcm.size)
    return chunks
}

/** The middle of the lowest-energy frame in `[from, to)`. */
private fun quietestPoint(pcm: FloatArray, from: Int, to: Int, frame: Int): Int {
    var best = (from + to) / 2
    var bestEnergy = Double.MAX_VALUE
    var position = from
    while (position + frame <= to) {
        var energy = 0.0
        for (i in position until position + frame) energy += pcm[i] * pcm[i]
        if (energy < bestEnergy) {
            bestEnergy = energy
            best = position + frame / 2
        }
        position += frame
    }
    return best
}

/**
 * Parakeet returns timed word *fragments*: `" B", "on", "jo", "ur", ","`. A
 * leading space starts a new word. [offsetSeconds] is where the chunk starts in
 * the recording, [chunkEndSeconds] where it ends.
 */
internal fun wordsFromTokens(
    tokens: Array<String>,
    timestamps: FloatArray,
    offsetSeconds: Double,
    chunkEndSeconds: Double
): List<WordTimestamp> {
    val count = min(tokens.size, timestamps.size)
    val words = mutableListOf<WordTimestamp>()
    var text = StringBuilder()
    var wordStart = 0.0
    fun flush(end: Double) {
        val word = text.toString().trim()
        if (word.isNotEmpty()) words.add(WordTimestamp(word, wordStart, max(wordStart, end), 1.0))
        text = StringBuilder()
    }
    for (i in 0 until count) {
        val time = offsetSeconds + timestamps[i]
        if (tokens[i].startsWith(" ") || i == 0) {
            flush(time)
            wordStart = time
        }
        text.append(tokens[i])
    }
    // No token follows the last word: assume half a second, inside the chunk.
    if (count > 0) flush(min(offsetSeconds + timestamps[count - 1] + 0.5, chunkEndSeconds))
    return words
}
