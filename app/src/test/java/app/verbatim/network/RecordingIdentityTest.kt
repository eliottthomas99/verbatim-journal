package app.verbatim.network

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingIdentityTest {
    @Test fun matchesPythonUuid5IncludingUtf8() {
        assertEquals("f8846dcd-d0da-5a9b-b00f-9f7bd6e72962", serverRecordingId("a.m4a", 1000))
        assertEquals("f8d6e79a-df3f-519d-8947-fbe9e1511d61", serverRecordingId("note_123.txt", 123))
        assertEquals("15d04adf-c689-5a79-8adf-a64d66ef9914", serverRecordingId("café.m4a", 456))
    }
}
