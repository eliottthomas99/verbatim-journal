package app.verbatim.data

import android.content.Context
import app.verbatim.network.NetworkClient

/** The app's settings, in plain SharedPreferences (no androidx.preference). */
object AppPrefs {
    private const val FILE = "verbatim_prefs"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_SERVER_TOKEN = "server_token"
    private const val KEY_CONSENT = "consent_acknowledged"
    private const val KEY_SPEECH_ENGINE = "speech_engine"
    const val ENGINE_WHISTLE = "whistle"
    const val ENGINE_PARAKEET = "parakeet"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun serverUrl(context: Context): String = prefs(context).getString(KEY_SERVER_URL, null).orEmpty()

    fun serverToken(context: Context): String = prefs(context).getString(KEY_SERVER_TOKEN, null).orEmpty()

    /** Saves the address and token and points the network client at them immediately. */
    fun setServer(context: Context, url: String, token: String) {
        prefs(context).edit().putString(KEY_SERVER_URL, url).putString(KEY_SERVER_TOKEN, token).apply()
        NetworkClient.configure(url, token)
    }

    fun speechEngine(context: Context): String =
        prefs(context).getString(KEY_SPEECH_ENGINE, null) ?: ENGINE_WHISTLE

    fun setSpeechEngine(context: Context, engine: String) {
        prefs(context).edit().putString(KEY_SPEECH_ENGINE, engine).apply()
    }

    fun consentAcknowledged(context: Context): Boolean = prefs(context).getBoolean(KEY_CONSENT, false)

    fun setConsentAcknowledged(context: Context) {
        prefs(context).edit().putBoolean(KEY_CONSENT, true).apply()
    }
}
