package app.verbatim.network

import app.verbatim.asr.TranscriptSegment
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.POST

// ---------------------------------------------------------------------------
// API interface — declares the HTTP endpoints the app calls on the server
// ---------------------------------------------------------------------------

interface VerbatimApi {

    /** Sends the transcript text (never the audio) to the server. */
    @POST("transcript")
    suspend fun submitTranscript(@Body body: SubmitTranscriptRequest): Response<SubmitTranscriptResponse>

    /** Forget a recording the user deleted (its transcript and search index on the server). */
    @DELETE("recordings/{id}")
    suspend fun deleteRecording(@Path("id") serverId: String): Response<Unit>

    /** Connection test for the Settings screen. */
    @GET("health")
    suspend fun health(): Response<Map<String, String>>

    /** Natural-language query against the indexed recordings. */
    @POST("query")
    suspend fun queryRag(@Body body: RagRequest): Response<RagResponse>
}

data class SubmitTranscriptRequest(
    val filename: String,
    val timestamp: Long,
    val durationMs: Long,
    val transcript: String,
    val segments: List<TranscriptSegment> = emptyList()
)

data class SubmitTranscriptResponse(
    val recording_id: String = "",
    val status: String = "done",
    val summary: String = "",
    val action_items: List<String> = emptyList()
)

data class RagRequest(val question: String)

// ---------------------------------------------------------------------------
// Response data classes — match the JSON keys the server sends back
// ---------------------------------------------------------------------------

data class RagResponse(
    val answer: String,
    val sources: List<String>
)

// ---------------------------------------------------------------------------
// Retrofit client — singleton that builds the API instance
// ---------------------------------------------------------------------------

object NetworkClient {

    /** Retrofit needs some valid address before the user has set theirs in
     *  Settings; [isConfigured] keeps the app from ever calling this one. */
    private const val NO_SERVER_URL = "http://localhost/"

    @Volatile
    private var token = ""

    /** False until the user has entered their own server's address. */
    @Volatile
    var isConfigured = false
        private set

    /** The server summarizes (or answers) *during* the request with a local LLM:
     *  a long conversation can take minutes, far beyond OkHttp's 10 s default.
     *  The interceptor (code that runs on every request) adds the access token. */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(1, TimeUnit.MINUTES)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder().header("Authorization", "Bearer $token").build()
            )
        }
        .build()

    @Volatile
    private var retrofit: Retrofit = buildRetrofit(NO_SERVER_URL)

    /** Public handle — the API the app calls on the server. */
    val api: VerbatimApi get() = retrofit.create(VerbatimApi::class.java)

    /** Point the client at the user's server (at startup and from Settings). */
    fun configure(url: String, accessToken: String) {
        retrofit = buildRetrofit(url.ifBlank { NO_SERVER_URL })
        token = accessToken
        isConfigured = url.isNotBlank()
    }

    /** What an error status from the server means, in words for the user. */
    fun describeHttpError(code: Int): String = when (code) {
        401 -> "The server rejected the access token. Check it in Settings."
        503 -> "The server is running, but its AI model is not available."
        else -> "The server replied HTTP $code"
    }

    // Retrofit requires the base URL to end with "/".
    private fun buildRetrofit(url: String): Retrofit = Retrofit.Builder()
        .baseUrl(if (url.endsWith("/")) url else "$url/")
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
}
