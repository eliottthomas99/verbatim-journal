package app.verbatim.asr

import org.junit.Assert.*
import org.junit.Test

class SpeechReadinessTest {
    @Test fun absentModelExplainsHowToRecover() {
        try {
            requireSpeechModel(false)
            fail("A missing model must not silently transcribe with another engine")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("Settings"))
            assertTrue(error.message.orEmpty().contains("recording is saved"))
            assertTrue(error.message.orEmpty().contains("Transcribe again"))
        }
    }

    @Test fun readyModelAllowsRecognition() {
        requireSpeechModel(true)
    }

    @Test fun speechResultKeepsWordTimesAndDoesNotInventLanguage() {
        val word = WordTimestamp("notebook", 1.0, 1.5, 1.0)
        val result = SpeechResult(text = "notebook", words = listOf(word))
        assertEquals("", result.language)
        assertEquals(word, result.words.single())
    }
}
