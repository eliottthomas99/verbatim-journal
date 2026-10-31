package app.verbatim.service

import android.content.Context
import android.content.SharedPreferences

/**
 * "Is a recording running?" — one flag shared by the service (which writes
 * it), the Quick Settings tile and the app's record button (which read it).
 */
object RecordingState {
    const val PREFS = "recording_state"
    const val KEY = "is_recording"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isRecording(context: Context): Boolean = prefs(context).getBoolean(KEY, false)

    fun setRecording(context: Context, value: Boolean) {
        prefs(context).edit()
            .putLong(KEY_STARTED_AT, if (value) System.currentTimeMillis() else 0L)
            .putBoolean(KEY, value)
            .apply()
    }

    /** When the running recording started (epoch ms), for the timer; 0 if none. */
    fun startedAt(context: Context): Long = prefs(context).getLong(KEY_STARTED_AT, 0L)

    private const val KEY_STARTED_AT = "started_at"

    /** Calls [onChange] whenever the flag changes; returns the listener so it
     *  can be unregistered (SharedPreferences only keeps a weak reference). */
    fun observe(context: Context, onChange: (Boolean) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == KEY) onChange(p.getBoolean(KEY, false))
        }
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun stopObserving(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}
