package app.verbatim.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import app.verbatim.asr.TranscriptSegment

/**
 * TypeConverters tell Room how to store types it doesn't understand natively.
 * Here we convert List<String> and List<TranscriptSegment> → JSON strings and back.
 */
class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromStringList(value: List<String>): String {
        return gson.toJson(value)
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, type)
    }

    /**
     * Stores the ASR segment list as a JSON string, like:
     * `[{"text":"Bonjour,","startMs":0,"endMs":450}]`
     */
    @TypeConverter
    fun fromSegmentList(value: List<TranscriptSegment>): String {
        return gson.toJson(value)
    }

    @TypeConverter
    fun toSegmentList(value: String): List<TranscriptSegment> {
        // Rows copied by the v2 → v3 migration predate segments, so the column
        // holds '' for them. Gson cannot parse "", so treat it (and a null
        // result) as "no segments" instead of crashing.
        if (value.isEmpty()) return emptyList()
        val type = object : TypeToken<List<TranscriptSegment>>() {}.type
        return gson.fromJson(value, type) ?: emptyList()
    }
}
