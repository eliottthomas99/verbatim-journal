package app.verbatim.asr

import android.content.Context
import android.util.Log
import app.verbatim.JournalApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * The Parakeet model is too big for the APK (670 MB), so the user downloads it
 * once from Settings. Revision and checksums are pinned: what runs on the phone
 * is exactly the published model. An interrupted download resumes where it stopped.
 */
object ParakeetModel {
    private const val TAG = "ParakeetModel"
    private const val BASE_URL =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/2bda32ec70b097a55adaa07d9a7173915b43cc78/"

    private class ModelFile(val name: String, val size: Long, val sha256: String)

    private val FILES = listOf(
        ModelFile("encoder.int8.onnx", 652_184_281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
        ModelFile("decoder.int8.onnx", 11_845_275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
        ModelFile("joiner.int8.onnx", 6_355_277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
        ModelFile("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d")
    )
    private val TOTAL_SIZE = FILES.sumOf { it.size }

    sealed interface State {
        data object Absent : State
        data class Downloading(val bytes: Long, val total: Long) : State
        data object Verifying : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Absent)
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun directory(context: Context) = File(context.filesDir, "parakeet")

    private fun marker(context: Context) = File(directory(context), "verified")

    /** Verified once after the download: hashing 670 MB at every start would be slow. */
    fun isReady(context: Context): Boolean = marker(context).isFile &&
        FILES.all { File(directory(context), it.name).length() == it.size }

    /** Sets the state from what is on disk; call before showing it. */
    fun refresh(context: Context) {
        if (job?.isActive == true) return
        _state.value = if (isReady(context)) State.Ready else State.Absent
    }

    fun download(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = JournalApplication.instance.applicationScope.launch {
            try {
                downloadAll(app)
                marker(app).createNewFile()
                _state.value = State.Ready
            } catch (e: CancellationException) {
                _state.value = State.Absent
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download failed", e)
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Stops a download; what was received is kept so it can resume. */
    fun cancelDownload() {
        job?.cancel()
    }

    fun delete(context: Context) {
        job?.cancel()
        ParakeetEngine.release()
        directory(context).deleteRecursively()
        _state.value = State.Absent
    }

    private suspend fun downloadAll(context: Context) {
        val dir = directory(context)
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create the model directory" }
        val client = OkHttpClient()
        val needed = FILES.filterNot { File(dir, it.name).length() == it.size }
        val missing = needed.sumOf { it.size - File(dir, it.name + ".part").length() }
        check(dir.usableSpace > missing) { "Not enough free space (${missing / 1_000_000} MB needed)" }
        var done = FILES.filter { it !in needed }.sumOf { it.size }
        for (file in needed) {
            fetch(client, dir, file, done)
            done += file.size
        }
        _state.value = State.Verifying
        for (file in FILES) {
            coroutineContext.ensureActive()
            val target = File(dir, file.name)
            if (sha256(target) != file.sha256) {
                target.delete()
                error("${file.name} failed its integrity check; try again")
            }
        }
    }

    private suspend fun fetch(client: OkHttpClient, dir: File, file: ModelFile, doneBefore: Long) {
        val part = File(dir, file.name + ".part")
        val resumeAt = part.length()
        val request = Request.Builder().url(BASE_URL + file.name)
            .apply { if (resumeAt > 0) header("Range", "bytes=$resumeAt-") }
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
            // 206 = the server continues where we stopped; 200 = it restarts from zero.
            val append = response.code == 206
            var received = if (append) resumeAt else 0L
            val body = checkNotNull(response.body) { "Empty response" }
            FileOutputStream(part, append).use { out ->
                val input = body.byteStream()
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    received += count
                    _state.value = State.Downloading(doneBefore + received, TOTAL_SIZE)
                }
            }
        }
        check(part.length() == file.size) { "Download of ${file.name} was incomplete; try again" }
        check(part.renameTo(File(dir, file.name))) { "Cannot save ${file.name}" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
