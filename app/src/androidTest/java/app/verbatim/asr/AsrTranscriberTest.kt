package app.verbatim.asr

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * On-device tests for [AsrTranscriber]: real M4A files decoded to 16 kHz mono
 * PCM and transcribed by the bundled Whistle model.
 *
 * The M4A fixtures are generated on the PC by
 * tools/generate_whistle_m4a_test_audio.ps1 (synthetic TTS speech only).
 */
class AsrTranscriberTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val transcriber = AsrTranscriber(context)

    @Before
    fun loadModel() {
        // The model is loaded once per process; the app loads it the same way.
        WhistleNative.loadModelFromAssets(context).getOrThrow()
    }

    @Test
    fun englishM4aIsTranscribedWithRealTimings() = runBlocking {
        val result = transcriber.transcribe(assetFile("english.m4a")).getOrThrow()

        assertEquals("en", result.language)
        assertTrue("unexpected text: '${result.text}'", result.text.contains("speech", ignoreCase = true))
        assertTrue("no segments", result.segments.isNotEmpty())

        var previousStart = -1L
        for (segment in result.segments) {
            assertTrue("negative start: $segment", segment.startMs >= 0)
            assertTrue("end before start: $segment", segment.endMs > segment.startMs)
            assertTrue("times not ascending: $segment", segment.startMs >= previousStart)
            previousStart = segment.startMs
        }

        val duration = durationMs("english.m4a")
        val lastEnd = result.segments.last().endMs
        assertTrue("last end $lastEnd ms is past the clip duration $duration ms", lastEnd <= duration + 200)
    }

    @Test
    fun frenchM4aKeepsItsAccents() = runBlocking {
        val result = transcriber.transcribe(assetFile("french.m4a")).getOrThrow()

        assertEquals("fr", result.language)
        assertTrue("unexpected text: '${result.text}'", result.text.contains("téléphone", ignoreCase = true))
        assertTrue("no segments", result.segments.isNotEmpty())
        assertTrue(result.segments.all { it.startMs >= 0 && it.endMs > it.startMs })
    }

    @Test
    fun longFrenchKeepsItsTailPastTheThirtySecondWindow() = runBlocking {
        val duration = durationMs("long_french.m4a")
        assertTrue("fixture must be longer than 30 s, was $duration ms", duration > 30_000)

        val result = transcriber.transcribe(assetFile("long_french.m4a")).getOrThrow()

        // The final sentence only exists at the end of the recording.
        assertTrue(
            "the tail was lost: '${result.text}'",
            result.text.contains("survivre", ignoreCase = true)
        )
        val last = result.segments.lastOrNull()
        assertNotNull("no segments", last)
        assertTrue(
            "last word ended at ${last!!.endMs} ms, expected at least 90% of $duration ms",
            last.endMs >= (duration * 0.9).toLong()
        )
    }

    @Test
    fun silenceIsASuccessAndNotAnError() = runBlocking {
        val result = transcriber.transcribe(assetFile("silence.m4a"))

        assertTrue("silence must not be a failure: ${result.exceptionOrNull()}", result.isSuccess)
        val value = result.getOrThrow()
        assertEquals("", value.text)
        assertTrue("silence must not produce segments", value.segments.isEmpty())
        assertEquals("", value.language)
    }

    @Test
    fun corruptInputFailsWithoutCrashing() = runBlocking {
        val result = transcriber.transcribe(assetFile("corrupt.m4a"))

        assertTrue("a corrupt file must fail", result.isFailure)
    }

    @Test
    fun cancellationStopsTheTranscription() = runBlocking {
        val file = assetFile("long_french.m4a")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val job = scope.launch { transcriber.transcribe(file) }
            delay(300)
            job.cancel()
            job.join()

            // A cancelled transcription must not look like a finished one.
            assertTrue("job should be cancelled but was completed=${job.isCompleted}", job.isCancelled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun overlappingJobsBothSucceed() = runBlocking {
        val file = assetFile("french.m4a")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val first = scope.async { transcriber.transcribe(file) }
            val second = scope.async { transcriber.transcribe(file) }
            val results = listOf(first.await(), second.await())

            results.forEach { assertTrue("overlapping job failed: ${it.exceptionOrNull()}", it.isSuccess) }
            assertEquals(results[0].getOrThrow().text, results[1].getOrThrow().text)
        } finally {
            scope.cancel()
        }
    }

    /** Copies a fixture from the test assets into the app cache. */
    private fun assetFile(name: String): File {
        val file = File(context.cacheDir, name)
        instrumentation.context.assets.open(name).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    /** Real duration of a fixture in milliseconds, written by the fixture script. */
    private fun durationMs(name: String): Long {
        val text = instrumentation.context.assets.open("$name.duration.txt").use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        return text.trim().toLong()
    }
}
