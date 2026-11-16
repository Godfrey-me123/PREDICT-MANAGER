package com.example.data.database

import com.example.data.model.AlarmProfileCategory
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
import com.example.data.model.ShortcutType
import com.example.data.model.StrategyConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

enum class ClearHistoryScope {
    CURRENT_SESSION,
    SELECTED_GAME_ARCHIVED,
    ALL_HISTORY
}

class ScreenEarnerRepository(private val dao: ScreenEarnerDao) {

    val shortcuts: Flow<List<FloatingShortcut>> = dao.getAllShortcuts()
    val profiles: Flow<List<PlatformProfile>> = dao.getAllProfiles()
    val strategyConfig: Flow<StrategyConfig?> = dao.getStrategyConfig()
    val riskLimits: Flow<RiskLimits?> = dao.getRiskLimits()
    val sessionLogs: Flow<List<SessionLog>> = dao.getRecentSessionLogs()
    val multiplierRecords: Flow<List<MultiplierRecord>> = dao.getAllMultiplierRecords()
    val customStrategies: Flow<List<CustomPatternStrategy>> = dao.getAllCustomStrategies()
    val notificationSettings: Flow<NotificationSettings?> = dao.getNotificationSettings()
    val notificationHistory: Flow<List<NotificationHistoryRecord>> = dao.getAllNotificationHistory()
    val alarmProfiles: Flow<List<AlarmProfileEntity>> = dao.getAllAlarmProfiles()
    val monitoringSessions: Flow<List<MonitoringSessionEntity>> = dao.getAllMonitoringSessions()
    val crashRecords: Flow<List<CrashRecord>> = dao.getAllCrashRecords()
    val crashCorrectionAudits: Flow<List<CrashCorrectionAudit>> = dao.getAllCrashCorrectionAudits()
    val hourlySummaries: Flow<List<HourlySessionSummary>> = dao.getAllHourlySummaries()
    val patternOccurrences: Flow<List<PatternOccurrenceRecord>> = dao.getAllPatternOccurrences()

    suspend fun seedDefaultsIfEmpty() {
        val currentShortcuts = dao.getAllShortcuts().first()
        if (currentShortcuts.isEmpty()) {
            dao.insertShortcut(
                FloatingShortcut(
                    label = "LIVE HUD",
                    type = ShortcutType.BET,
                    panelId = 1,
                    visualX = 80f,
                    visualY = 320f,
                    backgroundColorHex = "#1D4ED8",
                    targetX = 0f,
                    targetY = 0f,
                    isCalibrated = false
                )
            )
        }

        val currentProfiles = dao.getAllProfiles().first()
        if (currentProfiles.isEmpty()) {
            dao.insertProfile(
                PlatformProfile(
                    id = "sportybet_aviator",
                    platformName = "SportyBet Aviator",
                    orientation = "PORTRAIT",
                    isActive = true,
                    description = "Browser & mobile observation profile for SportyBet Aviator."
                )
            )
            dao.insertProfile(
                PlatformProfile(
                    id = "betpawa_aviator",
                    platformName = "BetPawa Aviator",
                    orientation = "PORTRAIT",
                    isActive = false,
                    description = "Browser & app observation profile for BetPawa Aviator."
                )
            )
            dao.insertProfile(
                PlatformProfile(
                    id = "smartsoft_jetx",
                    platformName = "SmartSoft JetX",
                    orientation = "PORTRAIT",
                    isActive = false,
                    description = "Observation profile for JetX crash game."
                )
            )
            dao.insertProfile(
                PlatformProfile(
                    id = "pragmatic_spaceman",
                    platformName = "Pragmatic Spaceman",
                    orientation = "PORTRAIT",
                    isActive = false,
                    description = "Observation profile for Spaceman crash game."
                )
            )
        }

        if (dao.getStrategyConfig().first() == null) {
            dao.saveStrategyConfig(StrategyConfig())
        }

        if (dao.getRiskLimits().first() == null) {
            dao.saveRiskLimits(RiskLimits())
        }

        if (dao.getNotificationSettingsOnce() == null) {
            dao.saveNotificationSettings(NotificationSettings())
        }

        // Seed 5 alarm profile categories with NO pre-selected sound (manual selection only)
        dao.insertAlarmProfilesIfAbsent(AlarmProfileEntity.defaultProfiles())
        // CustomPatternStrategy table intentionally starts empty with zero hardcoded example strategies.
    }

    suspend fun insertShortcut(shortcut: FloatingShortcut) = dao.insertShortcut(shortcut)
    suspend fun updateShortcut(shortcut: FloatingShortcut) = dao.updateShortcut(shortcut)
    suspend fun deleteShortcut(id: Long) = dao.deleteShortcutById(id)
    suspend fun resetShortcutsToDefault() {
        dao.deleteAllShortcuts()
        seedDefaultsIfEmpty()
    }

    suspend fun setActiveProfile(profileId: String) = dao.setActiveProfile(profileId)
    suspend fun insertProfile(profile: PlatformProfile) = dao.insertProfile(profile)

    suspend fun saveStrategyConfig(config: StrategyConfig) = dao.saveStrategyConfig(config)
    suspend fun saveRiskLimits(limits: RiskLimits) = dao.saveRiskLimits(limits)

    suspend fun addSessionLog(log: SessionLog) = dao.insertSessionLog(log)
    suspend fun clearSessionLogs() = dao.clearSessionLogs()

    // Monitoring Sessions
    suspend fun getActiveMonitoringSession(): MonitoringSessionEntity? = dao.getActiveMonitoringSession()
    suspend fun upsertMonitoringSession(session: MonitoringSessionEntity) = dao.upsertMonitoringSession(session)
    suspend fun closeActiveMonitoringSessions(endedAtMs: Long) = dao.closeActiveMonitoringSessions(endedAtMs)

    suspend fun addMultiplierRecord(record: MultiplierRecord) = dao.insertMultiplierRecord(record)
    suspend fun addMultiplierRecords(records: List<MultiplierRecord>) {
        if (records.isNotEmpty()) {
            dao.insertMultiplierRecords(records)
        }
    }

    suspend fun getRecentChronologicalRecords(windowMinutes: Int = 30, nowMs: Long = System.currentTimeMillis()): List<MultiplierRecord> {
        val cutoff = nowMs - (windowMinutes.coerceAtLeast(1) * 60_000L)
        return dao.getMultiplierRecordsSinceChronological(cutoff)
    }

    suspend fun pruneMultiplierRecordsOlderThan(cutoffTimestamp: Long) =
        dao.pruneMultiplierRecordsOlderThan(cutoffTimestamp)

    suspend fun clearMultiplierRecords() = dao.clearMultiplierRecords()
    suspend fun clearMultiplierRecordsForSession(sessionId: String) =
        dao.clearMultiplierRecordsForSession(sessionId)

    // Crash Records
    fun getCrashRecordsForGame(gameId: String): Flow<List<CrashRecord>> = dao.getCrashRecordsForGame(gameId)
    fun getCrashRecordsForSession(sessionId: String): Flow<List<CrashRecord>> = dao.getCrashRecordsForSession(sessionId)
    suspend fun getCrashRecordsForSessionOnce(sessionId: String): List<CrashRecord> =
        dao.getCrashRecordsForSessionOnce(sessionId)

    suspend fun getCrashRecordsSinceAsc(sinceMs: Long, gameId: String? = null): List<CrashRecord> {
        return if (gameId.isNullOrBlank() || gameId == "ALL") {
            dao.getCrashRecordsSinceAsc(sinceMs)
        } else {
            dao.getCrashRecordsForGameSinceAsc(gameId, sinceMs)
        }
    }

    suspend fun getCrashRecordsBetweenAsc(startMs: Long, endMs: Long, gameId: String? = null): List<CrashRecord> {
        return if (gameId.isNullOrBlank() || gameId == "ALL") {
            dao.getCrashRecordsBetweenAsc(startMs, endMs)
        } else {
            dao.getCrashRecordsForGameBetweenAsc(gameId, startMs, endMs)
        }
    }

    suspend fun insertCrashRecord(record: CrashRecord): Long = dao.insertCrashRecord(record)
    suspend fun insertCrashRecords(records: List<CrashRecord>): List<Long> =
        if (records.isEmpty()) emptyList() else dao.insertCrashRecords(records)

    suspend fun insertConfirmedCrashIfAbsent(
        crashRecord: CrashRecord,
        multiplierRecord: MultiplierRecord
    ): Boolean = dao.insertConfirmedCrashIfAbsent(crashRecord, multiplierRecord)

    suspend fun applyManualCrashCorrection(
        recordId: Long,
        newMultiplier: Double,
        newColorHex: String,
        newCategory: CrashColorCategory,
        reason: String
    ): Boolean = dao.applyManualCrashCorrection(recordId, newMultiplier, newColorHex, newCategory, reason)

    suspend fun countClearScopeRecords(
        scope: ClearHistoryScope,
        activeSessionId: String,
        selectedGameId: String
    ): Int {
        return when (scope) {
            ClearHistoryScope.CURRENT_SESSION ->
                if (activeSessionId.isBlank()) 0 else dao.countCrashRecordsForSession(activeSessionId)
            ClearHistoryScope.SELECTED_GAME_ARCHIVED -> {
                val targetGame = if (selectedGameId == "ALL" || selectedGameId.isBlank()) "unknown" else selectedGameId
                dao.countArchivedCrashRecordsForGame(targetGame, activeSessionId)
            }
            ClearHistoryScope.ALL_HISTORY -> dao.countAllCrashRecords()
        }
    }

    suspend fun clearHistoryByScope(
        scope: ClearHistoryScope,
        activeSessionId: String,
        selectedGameId: String
    ) {
        when (scope) {
            ClearHistoryScope.CURRENT_SESSION -> {
                if (activeSessionId.isNotBlank()) {
                    dao.clearCrashRecordsForSession(activeSessionId)
                    dao.clearMultiplierRecordsForSession(activeSessionId)
                }
            }
            ClearHistoryScope.SELECTED_GAME_ARCHIVED -> {
                val targetGame = if (selectedGameId == "ALL" || selectedGameId.isBlank()) "unknown" else selectedGameId
                dao.clearArchivedCrashRecordsForGame(targetGame, activeSessionId)
                dao.clearArchivedMultiplierRecordsForGame(targetGame, activeSessionId)
                dao.deleteArchivedSessionsForGame(targetGame)
                dao.clearHourlySummariesForGame(targetGame)
                dao.clearPatternOccurrencesForGame(targetGame)
            }
            ClearHistoryScope.ALL_HISTORY -> {
                dao.clearAllCrashRecords()
                dao.clearMultiplierRecords()
                dao.clearAllMonitoringSessions()
                dao.clearAllHourlySummaries()
                dao.clearAllPatternOccurrences()
                dao.clearSessionLogs()
            }
        }
    }

    suspend fun clearCrashRecordsForSession(sessionId: String) = dao.clearCrashRecordsForSession(sessionId)
    suspend fun clearCrashRecordsForGame(gameId: String) = dao.clearCrashRecordsForGame(gameId)
    suspend fun clearAllCrashRecords() = dao.clearAllCrashRecords()

    // Hourly Session Summaries
    suspend fun getHourlySummary(gameId: String, hourStartMs: Long): HourlySessionSummary? =
        dao.getHourlySummary(gameId, hourStartMs)

    suspend fun upsertHourlySummary(summary: HourlySessionSummary): Long =
        dao.upsertHourlySummary(summary)

    // Pattern Occurrences
    suspend fun getPatternOccurrencesForStrategyHour(strategyId: Long, hourStartMs: Long): List<PatternOccurrenceRecord> =
        dao.getPatternOccurrencesForStrategyHour(strategyId, hourStartMs)

    suspend fun getLatestOccurrenceForStrategy(strategyId: Long): PatternOccurrenceRecord? =
        dao.getLatestOccurrenceForStrategy(strategyId)

    suspend fun insertPatternOccurrence(record: PatternOccurrenceRecord): Long =
        dao.insertPatternOccurrence(record)

    // User-defined custom pattern strategies
    suspend fun getCustomStrategiesOnce(): List<CustomPatternStrategy> = dao.getCustomStrategiesOnce()
    suspend fun insertCustomStrategy(strategy: CustomPatternStrategy): Long = dao.insertCustomStrategy(strategy)
    suspend fun updateCustomStrategy(strategy: CustomPatternStrategy) = dao.updateCustomStrategy(strategy)
    suspend fun deleteCustomStrategy(id: Long) = dao.deleteCustomStrategyById(id)

    suspend fun swapStrategyOrder(first: CustomPatternStrategy, second: CustomPatternStrategy) {
        val orderA = first.sortOrder
        val orderB = second.sortOrder
        val newOrderA = if (orderA == orderB) orderB + 1 else orderB
        val newOrderB = if (orderA == orderB) orderA else orderA
        dao.updateCustomStrategy(first.copy(sortOrder = newOrderA))
        dao.updateCustomStrategy(second.copy(sortOrder = newOrderB))
    }

    // Alarm Profiles (5 configurable categories)
    suspend fun getAlarmProfilesOnce(): List<AlarmProfileEntity> {
        val existing = dao.getAlarmProfilesOnce()
        if (existing.size < AlarmProfileCategory.entries.size) {
            dao.insertAlarmProfilesIfAbsent(AlarmProfileEntity.defaultProfiles())
            return dao.getAlarmProfilesOnce()
        }
        return existing
    }

    suspend fun getAlarmProfile(category: AlarmProfileCategory): AlarmProfileEntity {
        return dao.getAlarmProfile(category.name) ?: AlarmProfileEntity.defaultProfiles()
            .first { it.categoryName == category.name }
    }

    suspend fun upsertAlarmProfile(profile: AlarmProfileEntity) = dao.upsertAlarmProfile(profile)

    // Notification settings & history
    suspend fun getNotificationSettingsOnce(): NotificationSettings =
        dao.getNotificationSettingsOnce() ?: NotificationSettings()

    suspend fun saveNotificationSettings(settings: NotificationSettings) =
        dao.saveNotificationSettings(settings)

    suspend fun addNotificationHistory(record: NotificationHistoryRecord): Long =
        dao.insertNotificationHistory(record)

    suspend fun updateNotificationInteraction(id: Long, status: NotificationInteractionStatus) =
        dao.updateNotificationInteraction(id, status)

    suspend fun clearNotificationHistory() = dao.clearNotificationHistory()
}
