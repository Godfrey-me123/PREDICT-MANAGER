package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.AlarmProfileEntity
import com.example.data.model.CrashCorrectionAudit
import com.example.data.model.CrashRecord
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.FloatingShortcut
import com.example.data.model.HourlySessionSummary
import com.example.data.model.MonitoringSessionEntity
import com.example.data.model.MultiplierRecord
import com.example.data.model.NotificationHistoryRecord
import com.example.data.model.NotificationSettings
import com.example.data.model.PatternOccurrenceRecord
import com.example.data.model.PlatformProfile
import com.example.data.model.RiskLimits
import com.example.data.model.SessionLog
import com.example.data.model.StrategyConfig

@Database(
    entities = [
        FloatingShortcut::class,
        PlatformProfile::class,
        StrategyConfig::class,
        RiskLimits::class,
        SessionLog::class,
        MultiplierRecord::class,
        CustomPatternStrategy::class,
        NotificationSettings::class,
        NotificationHistoryRecord::class,
        CrashRecord::class,
        CrashCorrectionAudit::class,
        HourlySessionSummary::class,
        PatternOccurrenceRecord::class,
        AlarmProfileEntity::class,
        MonitoringSessionEntity::class
    ],
    version = 12,
    exportSchema = false
)
@TypeConverters(ScreenEarnerTypeConverters::class)
abstract class ScreenEarnerDatabase : RoomDatabase() {
    abstract fun dao(): ScreenEarnerDao

    companion object {
        @Volatile
        private var INSTANCE: ScreenEarnerDatabase? = null

        private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIdx >= 0 && cursor.getString(nameIdx) == column) {
                        return true
                    }
                }
            }
            return false
        }

        private fun addColumnIfMissing(
            db: SupportSQLiteDatabase,
            table: String,
            column: String,
            definition: String
        ) {
            if (!hasColumn(db, table, column)) {
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "crash_records", "sessionId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "crash_records", "isConfirmed", "INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_crash_records_sessionId` ON `crash_records` (`sessionId`)")

                addColumnIfMissing(db, "multiplier_records", "sessionId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "multiplier_records", "isConfirmed", "INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_multiplier_records_sessionId` ON `multiplier_records` (`sessionId`)")

                addColumnIfMissing(db, "notification_settings", "standardAlertEnabled", "INTEGER NOT NULL DEFAULT 1")
                addColumnIfMissing(db, "notification_settings", "standardSoundUri", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "notification_settings", "standardVibrationPattern", "TEXT NOT NULL DEFAULT 'DEFAULT'")
                addColumnIfMissing(db, "notification_settings", "standardRepeatedAlerts", "INTEGER NOT NULL DEFAULT 1")
                addColumnIfMissing(db, "notification_settings", "highPriorityAlarmEnabled", "INTEGER NOT NULL DEFAULT 1")
                addColumnIfMissing(db, "notification_settings", "alarmSoundUri", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "notification_settings", "alarmVibrationPattern", "TEXT NOT NULL DEFAULT 'DISTINCTIVE'")
                addColumnIfMissing(db, "notification_settings", "alarmRepeated", "INTEGER NOT NULL DEFAULT 1")
                addColumnIfMissing(db, "notification_settings", "alarmRepeatIntervalMinutes", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "notification_settings", "alarmMaxDurationMinutes", "INTEGER NOT NULL DEFAULT 5")
                addColumnIfMissing(db, "notification_settings", "channelConfigVersion", "INTEGER NOT NULL DEFAULT 1")

                addColumnIfMissing(db, "notification_history", "gameId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "notification_history", "gameName", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "notification_history", "occurrenceRecordId", "INTEGER")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "crash_records", "originalDetectedTitle", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "crash_records", "isConfirmed", "INTEGER NOT NULL DEFAULT 1")
                addColumnIfMissing(db, "crash_records", "observationOrder", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "multiplier_records", "isConfirmed", "INTEGER NOT NULL DEFAULT 1")

                addColumnIfMissing(db, "custom_pattern_strategies", "strategyGroup", "TEXT NOT NULL DEFAULT 'MULTIPLIER_INTERVAL'")
                addColumnIfMissing(db, "custom_pattern_strategies", "multiplierRuleSubtype", "TEXT NOT NULL DEFAULT 'STANDARD_THRESHOLD'")
                addColumnIfMissing(db, "custom_pattern_strategies", "aboveThresholdX", "REAL NOT NULL DEFAULT 2.0")
                addColumnIfMissing(db, "custom_pattern_strategies", "aboveConsecutiveCount", "INTEGER NOT NULL DEFAULT 3")
                addColumnIfMissing(db, "custom_pattern_strategies", "belowThresholdY", "REAL NOT NULL DEFAULT 2.0")
                addColumnIfMissing(db, "custom_pattern_strategies", "belowConsecutiveCount", "INTEGER NOT NULL DEFAULT 2")
                addColumnIfMissing(db, "custom_pattern_strategies", "inclusiveBounds", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "custom_pattern_strategies", "postBigWaitMinutes", "INTEGER NOT NULL DEFAULT 5")
                addColumnIfMissing(db, "custom_pattern_strategies", "bigMultiplierThreshold", "REAL NOT NULL DEFAULT 10.0")
                addColumnIfMissing(db, "custom_pattern_strategies", "specificAlertTimesCsv", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "custom_pattern_strategies", "colorMatchDirection", "TEXT NOT NULL DEFAULT 'HORIZONTAL'")
                addColumnIfMissing(db, "custom_pattern_strategies", "gridColumnsForVertical", "INTEGER NOT NULL DEFAULT 8")
                addColumnIfMissing(db, "custom_pattern_strategies", "customColorHexFilterCsv", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "custom_pattern_strategies", "repeatLimit", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "custom_pattern_strategies", "useAlarmStyle", "INTEGER NOT NULL DEFAULT 1")
                addColumnIfMissing(db, "custom_pattern_strategies", "vibrationEnabled", "INTEGER NOT NULL DEFAULT 1")
                addColumnIfMissing(db, "custom_pattern_strategies", "sortOrder", "INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `alarm_profiles` (
                        `categoryName` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `soundUri` TEXT NOT NULL DEFAULT '',
                        `soundLabel` TEXT NOT NULL DEFAULT 'No sound selected',
                        `vibrationEnabled` INTEGER NOT NULL DEFAULT 1,
                        `vibrationPattern` TEXT NOT NULL DEFAULT 'DISTINCTIVE',
                        `repeatEnabled` INTEGER NOT NULL DEFAULT 1,
                        `repeatCooldownSeconds` INTEGER NOT NULL DEFAULT 0,
                        `maxRepeatsPerEvent` INTEGER NOT NULL DEFAULT 1,
                        `useAlarmStyle` INTEGER NOT NULL DEFAULT 1,
                        PRIMARY KEY(`categoryName`)
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `monitoring_sessions` (
                        `sessionId` TEXT NOT NULL,
                        `gameId` TEXT NOT NULL,
                        `gameName` TEXT NOT NULL,
                        `originalDetectedTitle` TEXT NOT NULL DEFAULT '',
                        `startedAtMs` INTEGER NOT NULL,
                        `endedAtMs` INTEGER,
                        `isActive` INTEGER NOT NULL DEFAULT 1,
                        `confirmedCrashCount` INTEGER NOT NULL DEFAULT 0,
                        `status` TEXT NOT NULL DEFAULT 'ACTIVE',
                        PRIMARY KEY(`sessionId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_monitoring_sessions_gameId` ON `monitoring_sessions` (`gameId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_monitoring_sessions_startedAtMs` ON `monitoring_sessions` (`startedAtMs`)")
            }
        }

        fun getDatabase(context: Context): ScreenEarnerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ScreenEarnerDatabase::class.java,
                    "screen_earner_db"
                )
                    .addMigrations(MIGRATION_10_11, MIGRATION_11_12)
                    .fallbackToDestructiveMigrationOnDowngrade(true)
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}
