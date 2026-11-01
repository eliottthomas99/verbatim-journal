package app.verbatim

import app.verbatim.asr.TranscriptSegment
import app.verbatim.data.local.Converters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvertersTest {
    private val converters = Converters()

    @Test fun stringListsKeepTheirExistingJsonFormat() {
        val values = listOf("Speaker A", "Speaker B")
        val json = converters.fromStringList(values)
        assertEquals("[\"Speaker A\",\"Speaker B\"]", json)
        assertEquals(values, converters.toStringList(json))
        assertTrue(converters.toStringList("[]").isEmpty())
    }

    @Test fun segmentListsPreserveTextAndLongTimestamps() {
        val values = listOf(TranscriptSegment("Invented sentence.", 3_000_000_000L, 3_000_000_500L))
        assertEquals(values, converters.toSegmentList(converters.fromSegmentList(values)))
        assertTrue(converters.toSegmentList("").isEmpty())
        assertTrue(converters.toSegmentList("[]").isEmpty())
    }
}
