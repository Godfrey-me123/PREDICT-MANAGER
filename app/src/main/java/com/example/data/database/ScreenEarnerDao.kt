package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.model.AlarmProfileEntity
import com.example.data.model.CrashColorCategory
import com.example.data.model.CrashCorrectionAudit
import com.example.data.model.CrashRecord
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.FloatingShortcut
import com.example.data.model.HourlySessionSummary
import com.example.data.model.MonitoringSessionEntity
import com.example.data.model.MultiplierRecord
import com.example.data.model.NotificationHistoryRecord
import com.example.data.model.NotificationInteractionStatus
import com.example.data.model.NotificationSettings
import com.example.data.model.PatternOccurrenceRecord
import com.example.data.model.PlatformProfile
import com.example.data.model.RiskLimits
import com.example.data.model.SessionLog
import com.example.data.model.StrategyConfig
import kotlinx.coroutines.flow.Flow

@Dao
interface ScreenEarnerDao {
    // Shortcuts (legacy overlay layout records preserved cleanly)
    @Query("SELECT * FROM floating_shortcuts ORDER BY id ASC")
    fun getAllShortcuts(): Flow<List<FloatingShortcut>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShortcut(shortcut: FloatingShortcut): Long

    @Update
    suspend fun updateShortcut(shortcut: FloatingShortcut)

    @Query("DELETE FROM floating_shortcuts WHERE id = :id")
    suspend fun deleteShortcutById(id: Long)

    @Query("DELETE FROM floating_shortcuts")
    suspend fun deleteAllShortcuts()

    // Profiles
    @Query("SELECT * FROM platform_profiles")
    fun getAllProfiles(): Flow<List<PlatformProfile>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: PlatformProfile)

    @Query("UPDATE platform_profiles SET isActive = (id = :activeId)")
    suspend fun setActiveProfile(activeId: String)

    // Strategy Config
    @Query("SELECT * FROM strategy_configs WHERE id = 1")
    fun getStrategyConfig(): Flow<StrategyConfig?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveStrategyConfig(config: StrategyConfig)

    // Risk Limits (retained for schema compatibility)
    @Query("SELECT * FROM risk_limits WHERE id = 1")
    fun getRiskLimits(): Flow<RiskLimits?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRiskLimits(limits: RiskLimits)

    // Session Logs
    @Query("SELECT * FROM session_logs ORDER BY timestamp DESC LIMIT 100")
    fun getRecentSessionLogs(): Flow<List<SessionLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessionLog(log: SessionLog)

    @Query("DELETE FROM session_logs")
    suspend fun clearSessionLogs()

    // Monitoring Sessions (Room v12)
    @Query("SELECT * FROM monitoring_sessions ORDER BY startedAtMs DESC LIMIT 100")
    fun getAllMonitoringSessions(): Flow<List<MonitoringSessionEntity>>

    @Query("SELECT * FROM monitoring_sessions WHERE isActive = 1 ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun getActiveMonitoringSession(): MonitoringSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMonitoringSession(session: MonitoringSessionEntity)

    @Query("UPDATE monitoring_sessions SET isActive = 0, endedAtMs = :endedAtMs, status = 'ENDED' WHERE isActive = 1")
    suspend fun closeActiveMonitoringSessions(endedAtMs: Long)

    @Query("DELETE FROM monitoring_sessions WHERE gameId = :gameId AND isActive = 0")
    suspend fun deleteArchivedSessionsForGame(gameId: String)

    @Query("DELETE FROM monitoring_sessions")
    suspend fun clearAllMonitoringSessions()

    // Multiplier Records (observed round & ribbon history)
    @Query("SELECT * FROM multiplier_records ORDER BY crashTimestamp DESC, sequenceIndex ASC, id DESC LIMIT 2000")
    fun getAllMultiplierRecords(): Flow<List<MultiplierRecord>>

    @Query("SELECT * FROM multiplier_records WHERE crashTimestamp >= :sinceTimestamp ORDER BY crashTimestamp DESC, sequenceIndex ASC, id DESC")
    fun getMultiplierRecordsSince(sinceTimestamp: Long): Flow<List<MultiplierRecord>>

    @Query("SELECT * FROM multiplier_records WHERE crashTimestamp >= :sinceTimestamp ORDER BY crashTimestamp ASC, sequenceIndex DESC, id ASC")
    suspend fun getMultiplierRecordsSinceChronological(sinceTimestamp: Long): List<MultiplierRecord>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMultiplierRecord(record: MultiplierRecord)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMultiplierRecords(records: List<MultiplierRecord>)

    @Query("DELETE FROM multiplier_records WHERE crashTimestamp < :cutoffTimestamp")
    suspend fun pruneMultiplierRecordsOlderThan(cutoffTimestamp: Long)

    @Query("DELETE FROM multiplier_records")
    suspend fun clearMultiplierRecords()

    @Query("DELETE FROM multiplier_records WHERE sessionId = :sessionId")
    suspend fun clearMultiplierRecordsForSession(sessionId: String)

    @Query("DELETE FROM multiplier_records WHERE gameId = :gameId AND sessionId != :activeSessionId")
    suspend fun clearArchivedMultiplierRecordsForGame(gameId: String, activeSessionId: String)

    // Crash Records (Predict Manager verified per-game history + actual color HEX)
    @Query("SELECT * FROM crash_records ORDER BY crashTimestamp DESC, id DESC LIMIT 2000")
    fun getAllCrashRecords(): Flow<List<CrashRecord>>

    @Query("SELECT * FROM crash_records WHERE sessionId = :sessionId ORDER BY crashTimestamp DESC, id DESC LIMIT 2000")
    fun getCrashRecordsForSession(sessionId: String): Flow<List<CrashRecord>>

    @Query("SELECT * FROM crash_records WHERE sessionId = :sessionId ORDER BY crashTimestamp ASC, id ASC")
    suspend fun getCrashRecordsForSessionOnce(sessionId: String): List<CrashRecord>

    @Query("SELECT * FROM crash_records WHERE gameId = :gameId ORDER BY crashTimestamp DESC, id DESC LIMIT 1000")
    fun getCrashRecordsForGame(gameId: String): Flow<List<CrashRecord>>

    @Query("SELECT * FROM crash_records WHERE gameId = :gameId AND crashTimestamp >= :sinceMs ORDER BY crashTimestamp ASC, id ASC")
    suspend fun getCrashRecordsForGameSinceAsc(gameId: String, sinceMs: Long): List<CrashRecord>

    @Query("SELECT * FROM crash_records WHERE gameId = :gameId AND crashTimestamp BETWEEN :startMs AND :endMs ORDER BY crashTimestamp ASC, id ASC")
    suspend fun getCrashRecordsForGameBetweenAsc(gameId: String, startMs: Long, endMs: Long): List<CrashRecord>

    @Query("SELECT * FROM crash_records WHERE crashTimestamp >= :sinceMs ORDER BY crashTimestamp ASC, id ASC")
    suspend fun getCrashRecordsSinceAsc(sinceMs: Long): List<CrashRecord>

    @Query("SELECT * FROM crash_records WHERE crashTimestamp BETWEEN :startMs AND :endMs ORDER BY crashTimestamp ASC, id ASC")
    suspend fun getCrashRecordsBetweenAsc(startMs: Long, endMs: Long): List<CrashRecord>

    @Query("SELECT * FROM crash_records WHERE id = :id")
    suspend fun getCrashRecordById(id: Long): CrashRecord?

    @Query("SELECT EXISTS(SELECT 1 FROM crash_records WHERE dedupKey = :dedupKey)")
    suspend fun existsCrashByDedupKey(dedupKey: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrashRecord(record: CrashRecord): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrashRecords(records: List<CrashRecord>): List<Long>

    @Transaction
    suspend fun insertConfirmedCrashIfAbsent(
        crashRecord: CrashRecord,
        multiplierRecord: MultiplierRecord
    ): Boolean {
        if (existsCrashByDedupKey(crashRecord.dedupKey)) return false
        val rowId = insertCrashRecord(crashRecord)
        if (rowId <= 0L) return false
        insertMultiplierRecord(multiplierRecord)
        return true
    }

    @Update
    suspend fun updateCrashRecord(record: CrashRecord)

    @Query("DELETE FROM crash_records WHERE sessionId = :sessionId")
    suspend fun clearCrashRecordsForSession(sessionId: String)

    @Query("DELETE FROM crash_records WHERE gameId = :gameId")
    suspend fun clearCrashRecordsForGame(gameId: String)

    @Query("DELETE FROM crash_records WHERE gameId = :gameId AND sessionId != :activeSessionId")
    suspend fun clearArchivedCrashRecordsForGame(gameId: String, activeSessionId: String)

    @Query("DELETE FROM crash_records")
    suspend fun clearAllCrashRecords()

    @Query("SELECT COUNT(*) FROM crash_records WHERE sessionId = :sessionId")
    suspend fun countCrashRecordsForSession(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM crash_records WHERE gameId = :gameId AND sessionId != :activeSessionId")
    suspend fun countArchivedCrashRecordsForGame(gameId: String, activeSessionId: String): Int

    @Query("SELECT COUNT(*) FROM crash_records")
    suspend fun countAllCrashRecords(): Int

    // Crash Correction Audits (retained for Room compatibility)
    @Query("SELECT * FROM crash_correction_audits ORDER BY correctedAtMs DESC LIMIT 200")
    fun getAllCrashCorrectionAudits(): Flow<List<CrashCorrectionAudit>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrashCorrectionAudit(audit: CrashCorrectionAudit): Long

    @Transaction
    suspend fun applyManualCrashCorrection(
        recordId: Long,
        newMultiplier: Double,
        newColorHex: String,
        newCategory: CrashColorCategory,
        reason: String
    ): Boolean {
        val existing = getCrashRecordById(recordId) ?: return false
        insertCrashCorrectionAudit(
            CrashCorrectionAudit(
                crashRecordId = existing.id,
                gameId = existing.gameId,
                originalMultiplier = existing.multiplier,
                originalColorHex = existing.detectedColorHex,
                originalColorCategory = existing.colorCategory,
                correctedMultiplier = newMultiplier,
                correctedColorHex = newColorHex,
                correctedColorCategory = newCategory,
                reason = reason
            )
        )
        updateCrashRecord(
            existing.copy(
                multiplier = newMultiplier,
                detectedColorHex = newColorHex,
                colorCategory = newCategory,
                manuallyCorrected = true
            )
        )
        return true
    }

    // Hourly Session Summaries
    @Query("SELECT * FROM hourly_session_summaries ORDER BY hourStartTimestamp DESC LIMIT 240")
    fun getAllHourlySummaries(): Flow<List<HourlySessionSummary>>

    @Query("SELECT * FROM hourly_session_summaries WHERE gameId = :gameId ORDER BY hourStartTimestamp DESC LIMIT 120")
    fun getHourlySummariesForGame(gameId: String): Flow<List<HourlySessionSummary>>

    @Query("SELECT * FROM hourly_session_summaries WHERE gameId = :gameId AND hourStartTimestamp = :hourStartMs LIMIT 1")
    suspend fun getHourlySummary(gameId: String, hourStartMs: Long): HourlySessionSummary?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHourlySummary(summary: HourlySessionSummary): Long

    @Query("DELETE FROM hourly_session_summaries WHERE gameId = :gameId")
    suspend fun clearHourlySummariesForGame(gameId: String)

    @Query("DELETE FROM hourly_session_summaries")
    suspend fun clearAllHourlySummaries()

    // Pattern Occurrence Records (per-strategy, per-game history)
    @Query("SELECT * FROM pattern_occurrence_records ORDER BY triggerTimestampMs DESC LIMIT 500")
    fun getAllPatternOccurrences(): Flow<List<PatternOccurrenceRecord>>

    @Query("SELECT * FROM pattern_occurrence_records WHERE strategyId = :strategyId AND hourStartTimestamp = :hourStartMs ORDER BY triggerTimestampMs ASC")
    suspend fun getPatternOccurrencesForStrategyHour(strategyId: Long, hourStartMs: Long): List<PatternOccurrenceRecord>

    @Query("SELECT * FROM pattern_occurrence_records WHERE strategyId = :strategyId ORDER BY triggerTimestampMs DESC LIMIT 1")
    suspend fun getLatestOccurrenceForStrategy(strategyId: Long): PatternOccurrenceRecord?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPatternOccurrence(record: PatternOccurrenceRecord): Long

    @Query("DELETE FROM pattern_occurrence_records WHERE gameId = :gameId")
    suspend fun clearPatternOccurrencesForGame(gameId: String)

    @Query("DELETE FROM pattern_occurrence_records")
    suspend fun clearAllPatternOccurrences()

    // Custom Pattern Strategies (User-defined, ordered by sortOrder)
    @Query("SELECT * FROM custom_pattern_strategies ORDER BY sortOrder ASC, createdAtMs ASC, id ASC")
    fun getAllCustomStrategies(): Flow<List<CustomPatternStrategy>>

    @Query("SELECT * FROM custom_pattern_strategies ORDER BY sortOrder ASC, createdAtMs ASC, id ASC")
    suspend fun getCustomStrategiesOnce(): List<CustomPatternStrategy>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomStrategy(strategy: CustomPatternStrategy): Long

    @Update
    suspend fun updateCustomStrategy(strategy: CustomPatternStrategy)

    @Query("DELETE FROM custom_pattern_strategies WHERE id = :id")
    suspend fun deleteCustomStrategyById(id: Long)

    // Alarm Profiles (Room v12 — 5 configurable categories)
    @Query("SELECT * FROM alarm_profiles ORDER BY categoryName ASC")
    fun getAllAlarmProfiles(): Flow<List<AlarmProfileEntity>>

    @Query("SELECT * FROM alarm_profiles ORDER BY categoryName ASC")
    suspend fun getAlarmProfilesOnce(): List<AlarmProfileEntity>

    @Query("SELECT * FROM alarm_profiles WHERE categoryName = :categoryName LIMIT 1")
    suspend fun getAlarmProfile(categoryName: String): AlarmProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlarmProfile(profile: AlarmProfileEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlarmProfilesIfAbsent(profiles: List<AlarmProfileEntity>)

    // Notification Settings
    @Query("SELECT * FROM notification_settings WHERE id = 1")
    fun getNotificationSettings(): Flow<NotificationSettings?>

    @Query("SELECT * FROM notification_settings WHERE id = 1")
    suspend fun getNotificationSettingsOnce(): NotificationSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveNotificationSettings(settings: NotificationSettings)

    // Notification History
    @Query("SELECT * FROM notification_history ORDER BY timestampMs DESC, id DESC LIMIT 500")
    fun getAllNotificationHistory(): Flow<List<NotificationHistoryRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotificationHistory(record: NotificationHistoryRecord): Long

    @Query("UPDATE notification_history SET interactionStatus = :status WHERE id = :id")
    suspend fun updateNotificationInteraction(id: Long, status: NotificationInteractionStatus)

    @Query("DELETE FROM notification_history WHERE gameId = :gameId")
    suspend fun clearNotificationHistoryForGame(gameId: String)

    @Query("DELETE FROM notification_history")
    suspend fun clearNotificationHistory()
}
