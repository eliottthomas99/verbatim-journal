package app.verbatim.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.verbatim.data.model.Recording

/**
 * The Room database singleton.
 * Think of it as the master key to all your tables — you get DAOs from it.
 * Uses the "double-checked locking" pattern so only one instance ever exists.
 */
@Database(
    entities = [Recording::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class RecordingDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile
        private var INSTANCE: RecordingDatabase? = null

        /**
         * v2 → v3: drops the `isTranscribing` polling flag (transcription now
         * happens on the phone) and adds `syncError` (upload failures) and
         * `segments` (JSON ASR timing).
         *
         * Room cannot add or drop a column in place, so the table is recreated:
         * build `recordings_new` with the v3 column set, copy every row over
         * (the two new NOT NULL columns start as '' = "none yet"), then swap
         * the names. Every existing row survives the upgrade.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recordings_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `filename` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `transcript` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `actionItems` TEXT NOT NULL,
                        `speakerLabels` TEXT NOT NULL,
                        `isSynced` INTEGER NOT NULL,
                        `syncError` TEXT NOT NULL,
                        `transcriptionError` TEXT NOT NULL,
                        `segments` TEXT NOT NULL,
                        `serverRecordingId` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `recordings_new` (
                        `id`, `filename`, `timestamp`, `durationMs`, `transcript`,
                        `summary`, `actionItems`, `speakerLabels`, `isSynced`,
                        `syncError`, `transcriptionError`, `segments`, `serverRecordingId`
                    )
                    SELECT
                        `id`, `filename`, `timestamp`, `durationMs`, `transcript`,
                        `summary`, `actionItems`, `speakerLabels`, `isSynced`,
                        '', `transcriptionError`, '', `serverRecordingId`
                    FROM `recordings`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `recordings`")
                db.execSQL("ALTER TABLE `recordings_new` RENAME TO `recordings`")
            }
        }

        fun getInstance(context: Context): RecordingDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RecordingDatabase::class.java,
                    "recordings_database"
                )
                    // The 2 → 3 step is a real, row-preserving migration (above).
                    // In Room 2.7.0 the class lives in package androidx.room.migration
                    // (it moved out of androidx.room). No fallbackToDestructiveMigration:
                    // in this version it would silently wipe the tables for ANY version
                    // gap that has no migration, which must never happen to the user's
                    // journal — a real gap should fail loudly instead.
                    .addMigrations(MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
