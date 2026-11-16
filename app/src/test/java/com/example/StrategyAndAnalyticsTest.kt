package com.example

import com.example.data.database.ScreenEarnerDao
import com.example.data.database.ScreenEarnerRepository
import com.example.data.engine.AutomationEngine
import com.example.data.engine.GameDetection
import com.example.data.engine.MultiplierHistoryAnalyzer
import com.example.data.engine.RiskManager
import com.example.data.engine.StrategyEngine
import com.example.data.engine.StrategyTrigger
import com.example.data.engine.TimeWindowFilter
import com.example.data.model.FloatingShortcut
import com.example.data.model.MultiplierRecord
import com.example.data.model.PlatformProfile
import com.example.data.model.RiskLimits
import com.example.data.model.SessionLog
import com.example.data.model.ShortcutType
import com.example.data.model.StakeProgressionMode
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyRuleConfig
import com.example.data.model.StrategyRuleId
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.NotificationHistoryRecord
import com.example.data.model.NotificationInteractionStatus
import com.example.data.model.NotificationSettings
import com.example.service.GameDetectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyAndAnalyticsTest {

    private fun configWithSingleRule(
        ruleId: StrategyRuleId,
        mode: StakeProgressionMode = StakeProgressionMode.DOUBLE_AMOUNT,
        customAdd: Double = 10.0,
        baseStake: Double = 10.0,
        targetMultiplier: Double = 2.00
    ): StrategyConfig {
        val disabledAll = StrategyRuleId.entries.map {
            StrategyRuleConfig(
                ruleId = it,
                isEnabled = (it == ruleId),
                progressionMode = if (it == ruleId) mode else StakeProgressionMode.DOUBLE_AMOUNT,
                customAddAmount = if (it == ruleId) customAdd else 10.0,
                targetWinsToFetch = 2
            )
        }
        return StrategyConfig(
            isStrategy1Enabled = false,
            isStrategy2Enabled = false,
            isHighMultiplierLossRuleEnabled = false,
            baseStake = baseStake,
            targetMultiplier = targetMultiplier,
            isAutoBetEnabled = true,
            isAutoCashOutEnabled = true
        ).withUpdatedRuleConfigs(disabledAll)
    }

    @Test
    fun `loss recovery rules trigger at exact thresholds 2, 3, 5, 7, 9, 14 and reset after 2 verified wins`() {
        val lossRules = listOf(
            StrategyRuleId.AFTER_2_LOSSES to 2,
            StrategyRuleId.AFTER_3_LOSSES to 3,
            StrategyRuleId.AFTER_5_LOSSES to 5,
            StrategyRuleId.AFTER_7_LOSSES to 7,
            StrategyRuleId.AFTER_9_LOSSES to 9,
            StrategyRuleId.AFTER_14_LOSSES to 14
        )

        for ((ruleId, threshold) in lossRules) {
            val engine = StrategyEngine()
            val config = configWithSingleRule(ruleId, baseStake = 20.0)
            engine.reset(config.baseStake)

            for (i in 1 until threshold) {
                val trigger = engine.processCompletedRound(1.35, config, currentTimeMs = i * 10_000L)
                assertTrue("Rule $ruleId triggered too early at $i losses", trigger is StrategyTrigger.None)
                assertFalse(engine.getState().isBettingSessionActive)
            }

            val fired = engine.processCompletedRound(1.50, config, currentTimeMs = threshold * 10_000L)
            assertTrue("Expected LossRecoveryFired for $ruleId", fired is StrategyTrigger.LossRecoveryFired)
            assertTrue(engine.getState().isBettingSessionActive)
            assertEquals(20.0, engine.getState().currentCalculatedStake, 0.001)

            val afterWin1 = engine.processBetOutcome(isWin = true, multiplier = 2.10, config = config)
            assertTrue(afterWin1.isBettingSessionActive)
            assertEquals(1, afterWin1.consecutiveWinsInSession)

            val afterWin2 = engine.processBetOutcome(isWin = true, multiplier = 2.40, config = config)
            assertFalse(afterWin2.isBettingSessionActive)
            assertEquals(0, afterWin2.consecutiveWinsInSession)
            assertEquals(20.0, afterWin2.currentCalculatedStake, 0.001)
        }
    }

    @Test
    fun `progression Option A adds custom amount and Option B doubles stake on loss`() {
        val addEngine = StrategyEngine()
        val addConfig = configWithSingleRule(
            ruleId = StrategyRuleId.AFTER_2_LOSSES,
            mode = StakeProgressionMode.ADD_AMOUNT,
            customAdd = 15.0,
            baseStake = 10.0
        )
        addEngine.reset(10.0)
        addEngine.processCompletedRound(1.10, addConfig)
        addEngine.processCompletedRound(1.20, addConfig)
        assertEquals(10.0, addEngine.getState().currentCalculatedStake, 0.001)

        addEngine.processBetOutcome(isWin = false, multiplier = 1.40, config = addConfig)
        assertEquals(25.0, addEngine.getState().currentCalculatedStake, 0.001)
        addEngine.processBetOutcome(isWin = false, multiplier = 1.50, config = addConfig)
        assertEquals(40.0, addEngine.getState().currentCalculatedStake, 0.001)

        val doubleEngine = StrategyEngine()
        val doubleConfig = configWithSingleRule(
            ruleId = StrategyRuleId.AFTER_2_LOSSES,
            mode = StakeProgressionMode.DOUBLE_AMOUNT,
            baseStake = 10.0
        )
        doubleEngine.reset(10.0)
        doubleEngine.processCompletedRound(1.10, doubleConfig)
        doubleEngine.processCompletedRound(1.20, doubleConfig)
        doubleEngine.processBetOutcome(isWin = false, multiplier = 1.40, config = doubleConfig)
        assertEquals(20.0, doubleEngine.getState().currentCalculatedStake, 0.001)
        doubleEngine.processBetOutcome(isWin = false, multiplier = 1.50, config = doubleConfig)
        assertEquals(40.0, doubleEngine.getState().currentCalculatedStake, 0.001)
    }

    @Test
    fun `multiplier range waiting rules enforce 10m and 5m delays before fetching 2 wins`() {
        val baseTime = 1_700_000_000_000L

        val engine10 = StrategyEngine()
        val config10 = configWithSingleRule(StrategyRuleId.RANGE_10_TO_100_WAIT_10M, baseStake = 25.0)
        engine10.reset(25.0)

        val trigger10 = engine10.processCompletedRound(45.50, config10, currentTimeMs = baseTime)
        assertTrue(trigger10 is StrategyTrigger.RangeWaitStarted)
        assertFalse(engine10.getState().isBettingSessionActive)
        assertEquals(600_000L, engine10.getState().activeWaitRemainingMs)

        val beforeElapsed = engine10.checkPendingTimers(config10, currentTimeMs = baseTime + 599_000L)
        assertTrue(beforeElapsed is StrategyTrigger.None)
        assertFalse(engine10.getState().isBettingSessionActive)

        val afterElapsed = engine10.checkPendingTimers(config10, currentTimeMs = baseTime + 600_000L)
        assertTrue(afterElapsed is StrategyTrigger.WaitTimerCompleted)
        assertTrue(engine10.getState().isBettingSessionActive)
        assertEquals(2, engine10.getState().targetWinsToFetch)

        val engine100 = StrategyEngine()
        val config100 = configWithSingleRule(StrategyRuleId.RANGE_100_TO_1000_WAIT_5M, baseStake = 30.0)
        engine100.reset(30.0)

        val trigger100 = engine100.processCompletedRound(240.00, config100, currentTimeMs = baseTime)
        assertTrue(trigger100 is StrategyTrigger.RangeWaitStarted)
        assertEquals(300_000L, engine100.getState().activeWaitRemainingMs)

        val completed100 = engine100.checkPendingTimers(config100, currentTimeMs = baseTime + 300_000L)
        assertTrue(completed100 is StrategyTrigger.WaitTimerCompleted)
        assertTrue(engine100.getState().isBettingSessionActive)
    }

    @Test
    fun `exclusive strategy lock prevents overlap and 3 minute timeout guard resets cycle cleanly`() {
        val t0 = 1_700_000_000_000L
        val engine = StrategyEngine()
        val config = StrategyConfig(
            isStrategy1Enabled = false,
            isStrategy2Enabled = false,
            isHighMultiplierLossRuleEnabled = false,
            baseStake = 15.0,
            sessionTimeoutMinutes = 3
        ).withUpdatedRuleConfigs(
            listOf(
                StrategyRuleConfig(
                    ruleId = StrategyRuleId.AFTER_2_LOSSES,
                    isEnabled = true
                ),
                StrategyRuleConfig(
                    ruleId = StrategyRuleId.RANGE_10_TO_100_WAIT_10M,
                    isEnabled = true
                )
            )
        )
        engine.reset(15.0)

        // Trigger AFTER_2_LOSSES at t0 + 10s
        engine.processCompletedRound(1.20, config, currentTimeMs = t0)
        val fired = engine.processCompletedRound(1.30, config, currentTimeMs = t0 + 10_000L)
        assertTrue(fired is StrategyTrigger.LossRecoveryFired)
        assertTrue(engine.getState().isBettingSessionActive)
        assertEquals(StrategyRuleId.AFTER_2_LOSSES, engine.getState().activeRuleId)

        // While active, a 45.00x round must NOT start RANGE_10_TO_100_WAIT_10M or override lock
        val blockedTrigger = engine.processCompletedRound(45.00, config, currentTimeMs = t0 + 30_000L)
        assertTrue(blockedTrigger is StrategyTrigger.None)
        assertTrue(engine.getState().isBettingSessionActive)
        assertNull(engine.getState().pendingWaitRuleId)
        assertEquals(StrategyRuleId.AFTER_2_LOSSES, engine.getState().activeRuleId)

        // Simulate 1 loss inside active session so stake progressed to 30.0
        engine.processBetOutcome(isWin = false, multiplier = 1.50, config = config, currentTimeMs = t0 + 40_000L)
        assertEquals(30.0, engine.getState().currentCalculatedStake, 0.001)

        // At 2m59s (179s after session started at t0 + 10s), still active
        val stillActive = engine.checkPendingTimers(config, currentTimeMs = t0 + 10_000L + 179_000L)
        assertTrue(stillActive is StrategyTrigger.None)
        assertTrue(engine.getState().isBettingSessionActive)

        // At 3m00s (180s), 3-minute timeout guard cancels active cycle and resets state cleanly
        val timedOut = engine.checkPendingTimers(config, currentTimeMs = t0 + 10_000L + 180_000L)
        assertTrue(timedOut is StrategyTrigger.SessionTimedOut)
        assertFalse(engine.getState().isBettingSessionActive)
        assertNull(engine.getState().activeRuleId)
        assertEquals(15.0, engine.getState().currentCalculatedStake, 0.001)
        assertEquals(0, engine.getState().consecutiveObservedLosses)
    }

    @Test
    fun `editable rule thresholds and wait durations persist and control triggering`() {
        val baseTime = 1_700_000_000_000L
        val customConfig = StrategyConfig(
            isStrategy1Enabled = false,
            isStrategy2Enabled = false,
            isHighMultiplierLossRuleEnabled = false,
            baseStake = 10.0,
            sessionTimeoutMinutes = 4
        ).withUpdatedRule(
            StrategyRuleConfig(
                ruleId = StrategyRuleId.AFTER_2_LOSSES,
                isEnabled = true,
                progressionMode = StakeProgressionMode.ADD_AMOUNT,
                customAddAmount = 25.0,
                targetWinsToFetch = 3,
                requiredLosses = 4
            )
        ).withUpdatedRule(
            StrategyRuleConfig(
                ruleId = StrategyRuleId.RANGE_10_TO_100_WAIT_10M,
                isEnabled = true,
                waitMinutes = 7,
                minMultiplier = 15.0,
                maxMultiplier = 80.0,
                targetWinsToFetch = 2
            )
        )

        val reparsedLoss = customConfig.getRuleConfig(StrategyRuleId.AFTER_2_LOSSES)
        assertEquals(4, reparsedLoss.requiredLosses)
        assertEquals(3, reparsedLoss.targetWinsToFetch)
        assertEquals(25.0, reparsedLoss.customAddAmount, 0.001)

        val reparsedRange = customConfig.getRuleConfig(StrategyRuleId.RANGE_10_TO_100_WAIT_10M)
        assertEquals(7, reparsedRange.waitMinutes)
        assertEquals(15.0, reparsedRange.minMultiplier, 0.001)
        assertEquals(80.0, reparsedRange.maxMultiplier, 0.001)

        // Verify engine respects edited waitMinutes = 7m and minMultiplier = 15x
        val engine = StrategyEngine()
        engine.reset(10.0)
        // 12x is below edited 15x minMultiplier, so range rule must NOT trigger!
        val belowEditedRange = engine.processCompletedRound(12.00, customConfig, baseTime)
        assertTrue(belowEditedRange is StrategyTrigger.None)

        // 25x is inside edited 15x-80x range, triggers 7m wait (420,000 ms)
        val inEditedRange = engine.processCompletedRound(25.00, customConfig, baseTime + 5_000L)
        assertTrue(inEditedRange is StrategyTrigger.RangeWaitStarted)
        assertEquals(420_000L, engine.getState().activeWaitRemainingMs)
    }

    @Test
    fun `game detection phase machine emits live flight multipliers and settles round once on crashed`() {
        val detection = GameDetection()
        val settledRounds = mutableListOf<Double>()

        detection.updateFromAccessibilityDetection(
            GameDetectionState(
                isGameDetected = true,
                platformName = "BetPawa Aviator",
                gamePhase = "IN_FLIGHT",
                detectedMultiplier = 1.45
            ),
            onRealRoundCompleted = { settledRounds.add(it) }
        )
        assertEquals(1.45, detection.gameState.value.currentMultiplier, 0.001)
        assertEquals("IN_FLIGHT", detection.gameState.value.status)
        assertEquals(0, settledRounds.size)

        detection.updateFromAccessibilityDetection(
            GameDetectionState(
                isGameDetected = true,
                platformName = "BetPawa Aviator",
                gamePhase = "IN_FLIGHT",
                detectedMultiplier = 3.20
            ),
            onRealRoundCompleted = { settledRounds.add(it) }
        )
        assertEquals(3.20, detection.gameState.value.currentMultiplier, 0.001)
        assertEquals(0, settledRounds.size)

        detection.updateFromAccessibilityDetection(
            GameDetectionState(
                isGameDetected = true,
                platformName = "BetPawa Aviator",
                gamePhase = "CRASHED",
                detectedMultiplier = 3.45
            ),
            onRealRoundCompleted = { settledRounds.add(it) }
        )
        assertEquals("CRASHED", detection.gameState.value.status)
        assertEquals(listOf(3.45), settledRounds)
    }

    @Test
    fun `automation start validation requires calibrated bet hand and accessibility connection`() {
        val fakeDao = object : ScreenEarnerDao {
            override fun getAllShortcuts(): Flow<List<FloatingShortcut>> = emptyFlow()
            override suspend fun insertShortcut(shortcut: FloatingShortcut): Long = 1L
            override suspend fun updateShortcut(shortcut: FloatingShortcut) {}
            override suspend fun deleteShortcutById(id: Long) {}
            override suspend fun deleteAllShortcuts() {}
            override fun getAllProfiles(): Flow<List<PlatformProfile>> = emptyFlow()
            override suspend fun insertProfile(profile: PlatformProfile) {}
            override suspend fun setActiveProfile(activeId: String) {}
            override fun getStrategyConfig(): Flow<StrategyConfig?> = emptyFlow()
            override suspend fun saveStrategyConfig(config: StrategyConfig) {}
            override fun getRiskLimits(): Flow<RiskLimits?> = emptyFlow()
            override suspend fun saveRiskLimits(limits: RiskLimits) {}
            override fun getRecentSessionLogs(): Flow<List<SessionLog>> = emptyFlow()
            override suspend fun insertSessionLog(log: SessionLog) {}
            override suspend fun clearSessionLogs() {}
            override fun getAllMultiplierRecords(): Flow<List<MultiplierRecord>> = emptyFlow()
            override fun getMultiplierRecordsSince(sinceTimestamp: Long): Flow<List<MultiplierRecord>> = emptyFlow()
            override suspend fun getMultiplierRecordsSinceChronological(sinceTimestamp: Long): List<MultiplierRecord> = emptyList()
            override suspend fun insertMultiplierRecord(record: MultiplierRecord) {}
            override suspend fun insertMultiplierRecords(records: List<MultiplierRecord>) {}
            override suspend fun pruneMultiplierRecordsOlderThan(cutoffTimestamp: Long) {}
            override suspend fun clearMultiplierRecords() {}
            override suspend fun clearMultiplierRecordsForSession(sessionId: String) {}
            override fun getAllCustomStrategies(): Flow<List<CustomPatternStrategy>> = emptyFlow()
            override suspend fun getCustomStrategiesOnce(): List<CustomPatternStrategy> = emptyList()
            override suspend fun insertCustomStrategy(strategy: CustomPatternStrategy): Long = 1L
            override suspend fun updateCustomStrategy(strategy: CustomPatternStrategy) {}
            override suspend fun deleteCustomStrategyById(id: Long) {}
            override fun getNotificationSettings(): Flow<NotificationSettings?> = emptyFlow()
            override suspend fun getNotificationSettingsOnce(): NotificationSettings? = NotificationSettings()
            override suspend fun saveNotificationSettings(settings: NotificationSettings) {}
            override fun getAllNotificationHistory(): Flow<List<NotificationHistoryRecord>> = emptyFlow()
            override suspend fun insertNotificationHistory(record: NotificationHistoryRecord): Long = 1L
            override suspend fun updateNotificationInteraction(id: Long, status: NotificationInteractionStatus) {}
            override suspend fun clearNotificationHistory() {}
            override fun getAllCrashRecords(): Flow<List<com.example.data.model.CrashRecord>> = emptyFlow()
            override fun getCrashRecordsForSession(sessionId: String): Flow<List<com.example.data.model.CrashRecord>> = emptyFlow()
            override suspend fun getCrashRecordsForSessionOnce(sessionId: String): List<com.example.data.model.CrashRecord> = emptyList()
            override fun getCrashRecordsForGame(gameId: String): Flow<List<com.example.data.model.CrashRecord>> = emptyFlow()
            override suspend fun getCrashRecordsForGameSinceAsc(gameId: String, sinceMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordsForGameBetweenAsc(gameId: String, startMs: Long, endMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordsSinceAsc(sinceMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordsBetweenAsc(startMs: Long, endMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordById(id: Long): com.example.data.model.CrashRecord? = null
            override suspend fun existsCrashByDedupKey(dedupKey: String): Boolean = false
            override suspend fun insertCrashRecord(record: com.example.data.model.CrashRecord): Long = 1L
            override suspend fun insertCrashRecords(records: List<com.example.data.model.CrashRecord>): List<Long> = emptyList()
            override suspend fun updateCrashRecord(record: com.example.data.model.CrashRecord) {}
            override suspend fun clearCrashRecordsForSession(sessionId: String) {}
            override suspend fun clearCrashRecordsForGame(gameId: String) {}
            override suspend fun clearAllCrashRecords() {}
            override fun getAllCrashCorrectionAudits(): Flow<List<com.example.data.model.CrashCorrectionAudit>> = emptyFlow()
            override suspend fun insertCrashCorrectionAudit(audit: com.example.data.model.CrashCorrectionAudit): Long = 1L
            override fun getAllHourlySummaries(): Flow<List<com.example.data.model.HourlySessionSummary>> = emptyFlow()
            override fun getHourlySummariesForGame(gameId: String): Flow<List<com.example.data.model.HourlySessionSummary>> = emptyFlow()
            override suspend fun getHourlySummary(gameId: String, hourStartMs: Long): com.example.data.model.HourlySessionSummary? = null
            override suspend fun upsertHourlySummary(summary: com.example.data.model.HourlySessionSummary): Long = 1L
            override fun getAllPatternOccurrences(): Flow<List<com.example.data.model.PatternOccurrenceRecord>> = emptyFlow()
            override suspend fun getPatternOccurrencesForStrategyHour(strategyId: Long, hourStartMs: Long): List<com.example.data.model.PatternOccurrenceRecord> = emptyList()
            override suspend fun getLatestOccurrenceForStrategy(strategyId: Long): com.example.data.model.PatternOccurrenceRecord? = null
            override suspend fun insertPatternOccurrence(record: com.example.data.model.PatternOccurrenceRecord): Long = 1L
            override fun getAllMonitoringSessions(): Flow<List<com.example.data.model.MonitoringSessionEntity>> = emptyFlow()
            override suspend fun getActiveMonitoringSession(): com.example.data.model.MonitoringSessionEntity? = null
            override suspend fun upsertMonitoringSession(session: com.example.data.model.MonitoringSessionEntity) {}
            override suspend fun closeActiveMonitoringSessions(endedAtMs: Long) {}
            override suspend fun deleteArchivedSessionsForGame(gameId: String) {}
            override suspend fun clearAllMonitoringSessions() {}
            override suspend fun clearArchivedMultiplierRecordsForGame(gameId: String, activeSessionId: String) {}
            override suspend fun clearArchivedCrashRecordsForGame(gameId: String, activeSessionId: String) {}
            override suspend fun countCrashRecordsForSession(sessionId: String): Int = 0
            override suspend fun countArchivedCrashRecordsForGame(gameId: String, activeSessionId: String): Int = 0
            override suspend fun countAllCrashRecords(): Int = 0
            override suspend fun clearHourlySummariesForGame(gameId: String) {}
            override suspend fun clearAllHourlySummaries() {}
            override suspend fun clearPatternOccurrencesForGame(gameId: String) {}
            override suspend fun clearAllPatternOccurrences() {}
            override fun getAllAlarmProfiles(): Flow<List<com.example.data.model.AlarmProfileEntity>> = emptyFlow()
            override suspend fun getAlarmProfilesOnce(): List<com.example.data.model.AlarmProfileEntity> = emptyList()
            override suspend fun getAlarmProfile(categoryName: String): com.example.data.model.AlarmProfileEntity? = null
            override suspend fun upsertAlarmProfile(profile: com.example.data.model.AlarmProfileEntity) {}
            override suspend fun insertAlarmProfilesIfAbsent(profiles: List<com.example.data.model.AlarmProfileEntity>) {}
            override suspend fun clearNotificationHistoryForGame(gameId: String) {}
        }

        val engine = AutomationEngine(
            repository = ScreenEarnerRepository(fakeDao),
            strategyEngine = StrategyEngine(),
            riskManager = RiskManager(),
            gameDetection = GameDetection()
        )

        // Fails with uncalibrated BET hand
        val uncalibrated = engine.startAutomationWithValidation(StrategyConfig(), RiskLimits(), isAccessibilityConnected = true)
        assertFalse(uncalibrated.started)

        // Calibrate BET hand, still fails if Accessibility off
        engine.updateCalibratedShortcuts(
            listOf(
                FloatingShortcut(id = 1, label = "BET 1", type = ShortcutType.BET, targetX = 350f, targetY = 1200f, isCalibrated = true)
            )
        )
        val noAccessibility = engine.startAutomationWithValidation(StrategyConfig(), RiskLimits(), isAccessibilityConnected = false)
        assertFalse(noAccessibility.started)

        // Both calibrated BET hand + Accessibility connected -> starts cleanly
        val ok = engine.startAutomationWithValidation(StrategyConfig(), RiskLimits(), isAccessibilityConnected = true)
        assertTrue(ok.started)
    }

    @Test
    fun `multiplier history analyzer computes 30m 1h 3h 5h windows and 1h spotlight accurately`() {
        val now = 1_700_000_000_000L
        val min = 60_000L

        val records = listOf(
            MultiplierRecord(id = 1, multiplier = 1.45, crashTimestamp = now - 10 * min),
            MultiplierRecord(id = 2, multiplier = 62.50, crashTimestamp = now - 25 * min),
            MultiplierRecord(id = 3, multiplier = 148.20, crashTimestamp = now - 45 * min),
            MultiplierRecord(id = 4, multiplier = 1.12, crashTimestamp = now - 120 * min),
            MultiplierRecord(id = 5, multiplier = 520.00, crashTimestamp = now - 240 * min),
            MultiplierRecord(id = 6, multiplier = 999.00, crashTimestamp = now - 360 * min)
        )

        val stats30m = MultiplierHistoryAnalyzer.analyzeWindow(records, TimeWindowFilter.THIRTY_MINUTES, now)
        assertEquals(2, stats30m.totalRounds)
        assertEquals(1, stats30m.below2xCount)
        assertEquals(1, stats30m.above50xCount)
        assertEquals(0, stats30m.above100xCount)

        val stats1h = MultiplierHistoryAnalyzer.analyzeWindow(records, TimeWindowFilter.ONE_HOUR, now)
        assertEquals(3, stats1h.totalRounds)
        assertEquals(1, stats1h.below2xCount)
        assertEquals(2, stats1h.above50xCount)
        assertEquals(1, stats1h.above100xCount)
        assertEquals(now - 45 * min, stats1h.above100xRecords.first().crashTimestamp)

        val stats3h = MultiplierHistoryAnalyzer.analyzeWindow(records, TimeWindowFilter.THREE_HOURS, now)
        assertEquals(4, stats3h.totalRounds)
        assertEquals(2, stats3h.below2xCount)

        val stats5h = MultiplierHistoryAnalyzer.analyzeWindow(records, TimeWindowFilter.FIVE_HOURS, now)
        assertEquals(5, stats5h.totalRounds)
        assertEquals(3, stats5h.above50xCount)
        assertEquals(2, stats5h.above100xCount)

        val spotlight = MultiplierHistoryAnalyzer.getLastHourSpotlight(records, now)
        assertNotNull(spotlight)
        assertEquals(148.20, spotlight!!.highestMultiplier, 0.001)
        assertEquals(now - 45 * min, spotlight.crashTimestamp)

        assertNull(MultiplierHistoryAnalyzer.getLastHourSpotlight(emptyList(), now))
    }

    @Test
    fun `calculateNextStakeAfterLoss and StakeInstruction support Add Amount, Double Amount, decimals, and 13x override`() {
        val engine = StrategyEngine()

        // Option A: Add Amount (whole & decimal)
        assertEquals(
            35.0,
            engine.calculateNextStakeAfterLoss(
                currentStake = 20.0,
                progressionMode = StakeProgressionMode.ADD_AMOUNT,
                customAddAmount = 15.0
            ),
            0.001
        )
        assertEquals(
            15.75,
            engine.calculateNextStakeAfterLoss(
                currentStake = 10.50,
                progressionMode = StakeProgressionMode.ADD_AMOUNT,
                customAddAmount = 5.25
            ),
            0.001
        )

        // Option B: Double Amount
        assertEquals(
            40.0,
            engine.calculateNextStakeAfterLoss(
                currentStake = 20.0,
                progressionMode = StakeProgressionMode.DOUBLE_AMOUNT,
                customAddAmount = 15.0
            ),
            0.001
        )

        // >13.00x High Multiplier Loss overrides Add Amount with Double Amount
        assertEquals(
            21.0,
            engine.calculateNextStakeAfterLoss(
                currentStake = 10.50,
                progressionMode = StakeProgressionMode.ADD_AMOUNT,
                customAddAmount = 5.25,
                lossMultiplier = 14.20,
                isHighMultiplierLossRuleEnabled = true
            ),
            0.001
        )

        assertEquals("25", StrategyEngine.formatStakeAmount(25.0))
        assertEquals("15.75", StrategyEngine.formatStakeAmount(15.75))

        // Verify StakeInstruction updates across lifecycle ready for AccessibilityController
        val config = configWithSingleRule(
            ruleId = StrategyRuleId.AFTER_2_LOSSES,
            mode = StakeProgressionMode.ADD_AMOUNT,
            customAdd = 5.25,
            baseStake = 10.50
        ).copy(isHighMultiplierLossRuleEnabled = true)

        engine.reset(10.50)
        assertEquals("10.50", engine.getPendingStakeInstruction().formattedStake)
        assertFalse(engine.getPendingStakeInstruction().isSessionActive)

        // Trigger AFTER_2_LOSSES
        engine.processCompletedRound(1.20, config, 1_000L)
        engine.processCompletedRound(1.30, config, 2_000L)
        val triggeredInstruction = engine.getPendingStakeInstruction()
        assertTrue(triggeredInstruction.isSessionActive)
        assertEquals(10.50, triggeredInstruction.stakeAmount, 0.001)
        assertEquals("10.50", triggeredInstruction.formattedStake)
        assertEquals(StakeProgressionMode.ADD_AMOUNT, triggeredInstruction.progressionMode)
        assertEquals(1, triggeredInstruction.stakeLevel)

        // Loss #1 -> Add 5.25 => 15.75
        engine.processBetOutcome(isWin = false, multiplier = 1.40, config = config, currentTimeMs = 3_000L)
        val afterAddLoss = engine.getPendingStakeInstruction()
        assertEquals(15.75, afterAddLoss.stakeAmount, 0.001)
        assertEquals("15.75", afterAddLoss.formattedStake)
        assertEquals(StakeProgressionMode.ADD_AMOUNT, afterAddLoss.progressionMode)
        assertEquals(2, afterAddLoss.stakeLevel)

        // Loss #2 at >13.00x -> Double 15.75 => 31.50
        engine.processBetOutcome(isWin = false, multiplier = 18.50, config = config, currentTimeMs = 4_000L)
        val afterHighLoss = engine.getPendingStakeInstruction()
        assertEquals(31.50, afterHighLoss.stakeAmount, 0.001)
        assertEquals("31.50", afterHighLoss.formattedStake)
        assertEquals(StakeProgressionMode.DOUBLE_AMOUNT, afterHighLoss.progressionMode)
        assertEquals(3, afterHighLoss.stakeLevel)

        // 2 Verified Wins -> Reset back to base stake 10.50
        engine.processBetOutcome(isWin = true, multiplier = 2.10, config = config, currentTimeMs = 5_000L)
        engine.processBetOutcome(isWin = true, multiplier = 2.20, config = config, currentTimeMs = 6_000L)
        val afterReset = engine.getPendingStakeInstruction()
        assertFalse(afterReset.isSessionActive)
        assertEquals(10.50, afterReset.stakeAmount, 0.001)
        assertEquals("10.50", afterReset.formattedStake)
        assertEquals(1, afterReset.stakeLevel)
    }

    @Test
    fun `automation engine passes calculated Add Amount and Double Amount stakes to TargetUiInteractor`() {
        val fakeDao = object : ScreenEarnerDao {
            override fun getAllShortcuts(): Flow<List<FloatingShortcut>> = emptyFlow()
            override suspend fun insertShortcut(shortcut: FloatingShortcut): Long = 1L
            override suspend fun updateShortcut(shortcut: FloatingShortcut) {}
            override suspend fun deleteShortcutById(id: Long) {}
            override suspend fun deleteAllShortcuts() {}
            override fun getAllProfiles(): Flow<List<PlatformProfile>> = emptyFlow()
            override suspend fun insertProfile(profile: PlatformProfile) {}
            override suspend fun setActiveProfile(activeId: String) {}
            override fun getStrategyConfig(): Flow<StrategyConfig?> = emptyFlow()
            override suspend fun saveStrategyConfig(config: StrategyConfig) {}
            override fun getRiskLimits(): Flow<RiskLimits?> = emptyFlow()
            override suspend fun saveRiskLimits(limits: RiskLimits) {}
            override fun getRecentSessionLogs(): Flow<List<SessionLog>> = emptyFlow()
            override suspend fun insertSessionLog(log: SessionLog) {}
            override suspend fun clearSessionLogs() {}
            override fun getAllMultiplierRecords(): Flow<List<MultiplierRecord>> = emptyFlow()
            override fun getMultiplierRecordsSince(sinceTimestamp: Long): Flow<List<MultiplierRecord>> = emptyFlow()
            override suspend fun getMultiplierRecordsSinceChronological(sinceTimestamp: Long): List<MultiplierRecord> = emptyList()
            override suspend fun insertMultiplierRecord(record: MultiplierRecord) {}
            override suspend fun insertMultiplierRecords(records: List<MultiplierRecord>) {}
            override suspend fun pruneMultiplierRecordsOlderThan(cutoffTimestamp: Long) {}
            override suspend fun clearMultiplierRecords() {}
            override suspend fun clearMultiplierRecordsForSession(sessionId: String) {}
            override fun getAllCustomStrategies(): Flow<List<CustomPatternStrategy>> = emptyFlow()
            override suspend fun getCustomStrategiesOnce(): List<CustomPatternStrategy> = emptyList()
            override suspend fun insertCustomStrategy(strategy: CustomPatternStrategy): Long = 1L
            override suspend fun updateCustomStrategy(strategy: CustomPatternStrategy) {}
            override suspend fun deleteCustomStrategyById(id: Long) {}
            override fun getNotificationSettings(): Flow<NotificationSettings?> = emptyFlow()
            override suspend fun getNotificationSettingsOnce(): NotificationSettings? = NotificationSettings()
            override suspend fun saveNotificationSettings(settings: NotificationSettings) {}
            override fun getAllNotificationHistory(): Flow<List<NotificationHistoryRecord>> = emptyFlow()
            override suspend fun insertNotificationHistory(record: NotificationHistoryRecord): Long = 1L
            override suspend fun updateNotificationInteraction(id: Long, status: NotificationInteractionStatus) {}
            override suspend fun clearNotificationHistory() {}
            override fun getAllCrashRecords(): Flow<List<com.example.data.model.CrashRecord>> = emptyFlow()
            override fun getCrashRecordsForSession(sessionId: String): Flow<List<com.example.data.model.CrashRecord>> = emptyFlow()
            override suspend fun getCrashRecordsForSessionOnce(sessionId: String): List<com.example.data.model.CrashRecord> = emptyList()
            override fun getCrashRecordsForGame(gameId: String): Flow<List<com.example.data.model.CrashRecord>> = emptyFlow()
            override suspend fun getCrashRecordsForGameSinceAsc(gameId: String, sinceMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordsForGameBetweenAsc(gameId: String, startMs: Long, endMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordsSinceAsc(sinceMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordsBetweenAsc(startMs: Long, endMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordById(id: Long): com.example.data.model.CrashRecord? = null
            override suspend fun existsCrashByDedupKey(dedupKey: String): Boolean = false
            override suspend fun insertCrashRecord(record: com.example.data.model.CrashRecord): Long = 1L
            override suspend fun insertCrashRecords(records: List<com.example.data.model.CrashRecord>): List<Long> = emptyList()
            override suspend fun updateCrashRecord(record: com.example.data.model.CrashRecord) {}
            override suspend fun clearCrashRecordsForSession(sessionId: String) {}
            override suspend fun clearCrashRecordsForGame(gameId: String) {}
            override suspend fun clearAllCrashRecords() {}
            override fun getAllCrashCorrectionAudits(): Flow<List<com.example.data.model.CrashCorrectionAudit>> = emptyFlow()
            override suspend fun insertCrashCorrectionAudit(audit: com.example.data.model.CrashCorrectionAudit): Long = 1L
            override fun getAllHourlySummaries(): Flow<List<com.example.data.model.HourlySessionSummary>> = emptyFlow()
            override fun getHourlySummariesForGame(gameId: String): Flow<List<com.example.data.model.HourlySessionSummary>> = emptyFlow()
            override suspend fun getHourlySummary(gameId: String, hourStartMs: Long): com.example.data.model.HourlySessionSummary? = null
            override suspend fun upsertHourlySummary(summary: com.example.data.model.HourlySessionSummary): Long = 1L
            override fun getAllPatternOccurrences(): Flow<List<com.example.data.model.PatternOccurrenceRecord>> = emptyFlow()
            override suspend fun getPatternOccurrencesForStrategyHour(strategyId: Long, hourStartMs: Long): List<com.example.data.model.PatternOccurrenceRecord> = emptyList()
            override suspend fun getLatestOccurrenceForStrategy(strategyId: Long): com.example.data.model.PatternOccurrenceRecord? = null
            override suspend fun insertPatternOccurrence(record: com.example.data.model.PatternOccurrenceRecord): Long = 1L
            override fun getAllMonitoringSessions(): Flow<List<com.example.data.model.MonitoringSessionEntity>> = emptyFlow()
            override suspend fun getActiveMonitoringSession(): com.example.data.model.MonitoringSessionEntity? = null
            override suspend fun upsertMonitoringSession(session: com.example.data.model.MonitoringSessionEntity) {}
            override suspend fun closeActiveMonitoringSessions(endedAtMs: Long) {}
            override suspend fun deleteArchivedSessionsForGame(gameId: String) {}
            override suspend fun clearAllMonitoringSessions() {}
            override suspend fun clearArchivedMultiplierRecordsForGame(gameId: String, activeSessionId: String) {}
            override suspend fun clearArchivedCrashRecordsForGame(gameId: String, activeSessionId: String) {}
            override suspend fun countCrashRecordsForSession(sessionId: String): Int = 0
            override suspend fun countArchivedCrashRecordsForGame(gameId: String, activeSessionId: String): Int = 0
            override suspend fun countAllCrashRecords(): Int = 0
            override suspend fun clearHourlySummariesForGame(gameId: String) {}
            override suspend fun clearAllHourlySummaries() {}
            override suspend fun clearPatternOccurrencesForGame(gameId: String) {}
            override suspend fun clearAllPatternOccurrences() {}
            override fun getAllAlarmProfiles(): Flow<List<com.example.data.model.AlarmProfileEntity>> = emptyFlow()
            override suspend fun getAlarmProfilesOnce(): List<com.example.data.model.AlarmProfileEntity> = emptyList()
            override suspend fun getAlarmProfile(categoryName: String): com.example.data.model.AlarmProfileEntity? = null
            override suspend fun upsertAlarmProfile(profile: com.example.data.model.AlarmProfileEntity) {}
            override suspend fun insertAlarmProfilesIfAbsent(profiles: List<com.example.data.model.AlarmProfileEntity>) {}
            override suspend fun clearNotificationHistoryForGame(gameId: String) {}
        }

        val recordedApplies = mutableListOf<Pair<Double, StakeProgressionMode>>()
        var betClickCount = 0
        val fakeInteractor = object : com.example.data.engine.TargetUiInteractor {
            override fun applyStake(
                newStake: Double,
                progressionMode: StakeProgressionMode,
                fallbackX: Float?,
                fallbackY: Float?,
                onComplete: (Boolean) -> Unit
            ) {
                recordedApplies.add(newStake to progressionMode)
                onComplete(true)
            }

            override fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
                betClickCount++
                onComplete(true)
            }

            override fun clickCashOut(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
                onComplete(true)
            }
        }

        val strategyEngine = StrategyEngine()
        val engine = AutomationEngine(
            repository = ScreenEarnerRepository(fakeDao),
            strategyEngine = strategyEngine,
            riskManager = RiskManager(),
            gameDetection = GameDetection(),
            targetUiInteractor = fakeInteractor
        )
        engine.updateCalibratedShortcuts(
            listOf(
                FloatingShortcut(id = 1, label = "BET", type = ShortcutType.BET, targetX = 300f, targetY = 1200f, isCalibrated = true)
            )
        )

        val config = configWithSingleRule(
            ruleId = StrategyRuleId.AFTER_2_LOSSES,
            mode = StakeProgressionMode.ADD_AMOUNT,
            customAdd = 15.0,
            baseStake = 10.0
        )
        val limits = RiskLimits()
        engine.startAutomation(config, limits)

        // Two observed losses unlock AFTER_2_LOSSES at base stake 10.0
        engine.handleRoundCompleted(1.20, config, limits, currentTimeMs = 1_000L)
        engine.handleRoundCompleted(1.30, config, limits, currentTimeMs = 2_000L)

        // Phase BETTING_OPEN places bet with base stake 10.0 (ADD_AMOUNT)
        engine.handlePhaseUpdate("BETTING_OPEN", 1.00, config, limits, currentTimeMs = 3_000L)
        // Round crashes at 1.40x -> loss increases stake to 25.0
        engine.handleRoundCompleted(1.40, config, limits, currentTimeMs = 4_000L)
        // Next BETTING_OPEN passes 25.0 to TargetUiInteractor
        engine.handlePhaseUpdate("BETTING_OPEN", 1.00, config, limits, currentTimeMs = 5_000L)

        assertEquals(2, recordedApplies.size)
        assertEquals(10.0, recordedApplies[0].first, 0.001)
        assertEquals(StakeProgressionMode.ADD_AMOUNT, recordedApplies[0].second)
        assertEquals(25.0, recordedApplies[1].first, 0.001)
        assertEquals(StakeProgressionMode.ADD_AMOUNT, recordedApplies[1].second)
        assertEquals(2, betClickCount)
    }

    @Test
    fun `strategy engine binds single, multiple, or all selected strategies directly`() {
        val engine = StrategyEngine()

        // 1. Bind a SINGLE strategy (After 9 Losses only)
        engine.updateStrategySelection(
            selectedRuleIds = setOf(StrategyRuleId.AFTER_9_LOSSES),
            isStrategy1Enabled = false,
            isStrategy2Enabled = false,
            isHighMultiplierLossRuleEnabled = false
        )
        assertEquals(setOf(StrategyRuleId.AFTER_9_LOSSES), engine.getState().enabledRuleIds)

        // 2 losses and 45x range multiplier should NOT fire because only AFTER_9_LOSSES is bound
        assertTrue(engine.processCompletedRound(1.20, currentTimeMs = 1_000L) is StrategyTrigger.None)
        assertTrue(engine.processCompletedRound(1.20, currentTimeMs = 2_000L) is StrategyTrigger.None)
        assertTrue(engine.processCompletedRound(45.00, currentTimeMs = 3_000L) is StrategyTrigger.None)

        // Reset observed losses then hit 9 consecutive losses using bound config (no explicit config arg)
        engine.reset()
        for (i in 1..8) {
            assertTrue(engine.processCompletedRound(1.30, currentTimeMs = 10_000L + i * 1_000L) is StrategyTrigger.None)
        }
        val fired9 = engine.processCompletedRound(1.40, currentTimeMs = 20_000L)
        assertTrue(fired9 is StrategyTrigger.LossRecoveryFired)
        assertEquals(StrategyRuleId.AFTER_9_LOSSES, (fired9 as StrategyTrigger.LossRecoveryFired).ruleConfig.ruleId)

        // 2. Bind MULTIPLE strategies (After 3 Losses + High-Multiplier Wait 10x-100x)
        engine.reset()
        val multiSet = setOf(StrategyRuleId.AFTER_3_LOSSES, StrategyRuleId.RANGE_10_TO_100_WAIT_10M)
        engine.updateStrategySelection(selectedRuleIds = multiSet)
        assertEquals(multiSet, engine.getState().enabledRuleIds)

        val rangeWait = engine.processCompletedRound(28.00, currentTimeMs = 30_000L)
        assertTrue(rangeWait is StrategyTrigger.RangeWaitStarted)

        // 3. Bind ALL strategies
        engine.reset()
        val allSet = StrategyRuleId.entries.toSet()
        engine.updateStrategySelection(
            selectedRuleIds = allSet,
            isStrategy1Enabled = true,
            isStrategy2Enabled = true,
            isHighMultiplierLossRuleEnabled = true
        )
        assertEquals(allSet, engine.getState().enabledRuleIds)
        assertTrue(engine.boundConfig.value.isStrategy1Enabled)
        assertTrue(engine.boundConfig.value.isStrategy2Enabled)
        assertTrue(engine.boundConfig.value.isHighMultiplierLossRuleEnabled)
    }

    @Test
    fun `game detection syncs site ribbon shifts into history and resolves BetPawa vs SportyBet URLs`() {
        val detection = GameDetection()
        val recorded = mutableListOf<Double>()

        // Initial ribbon snapshot (only baseline, does not invent a fake round)
        detection.updateFromAccessibilityDetection(
            GameDetectionState(
                isGameDetected = true,
                foregroundPackage = "com.android.chrome",
                platformName = "Chrome Aviator Web",
                gamePhase = "BETTING_OPEN",
                detectedMultiplier = null,
                uiTargets = com.example.service.DetectedUiTargets(
                    detectedUrl = "https://www.sportybet.com/ng/m/aviator",
                    ribbonMultipliers = listOf(15.74, 2.00, 8.14),
                    siteButtonState = com.example.data.model.SiteButtonState.IDLE_READY_TO_BET
                )
            ),
            onRealRoundCompleted = { recorded.add(it) },
            currentTimeMs = 10_000L
        )
        assertEquals("SportyBet Aviator", detection.gameState.value.platformName)
        assertEquals(listOf(15.74, 2.00, 8.14), detection.gameState.value.recentRibbonHistory)
        assertTrue(recorded.isEmpty())

        // Ribbon head shifts with new completed round 4.65x (without requiring Flew Away frame)
        detection.updateFromAccessibilityDetection(
            GameDetectionState(
                isGameDetected = true,
                foregroundPackage = "com.android.chrome",
                platformName = "Chrome Aviator Web",
                gamePhase = "BETTING_OPEN",
                detectedMultiplier = null,
                uiTargets = com.example.service.DetectedUiTargets(
                    detectedUrl = "https://www.sportybet.com/ng/m/aviator",
                    ribbonMultipliers = listOf(4.65, 15.74, 2.00, 8.14),
                    siteButtonState = com.example.data.model.SiteButtonState.IDLE_READY_TO_BET
                )
            ),
            onRealRoundCompleted = { recorded.add(it) },
            currentTimeMs = 20_000L
        )
        assertEquals(listOf(4.65), recorded)
        assertEquals(listOf(4.65), detection.gameState.value.multiplierHistory)

        // Flew Away crash at 2.30x followed by ribbon showing 2.30x must record 2.30x only once
        detection.updateFromAccessibilityDetection(
            GameDetectionState(
                isGameDetected = true,
                foregroundPackage = "com.android.chrome",
                platformName = "SportyBet Aviator",
                gamePhase = "CRASHED",
                detectedMultiplier = 2.30,
                uiTargets = com.example.service.DetectedUiTargets(
                    detectedUrl = "https://www.sportybet.com/ng/m/aviator",
                    ribbonMultipliers = listOf(4.65, 15.74, 2.00),
                    siteButtonState = com.example.data.model.SiteButtonState.CRASHED
                )
            ),
            onRealRoundCompleted = { recorded.add(it) },
            currentTimeMs = 30_000L
        )
        detection.updateFromAccessibilityDetection(
            GameDetectionState(
                isGameDetected = true,
                foregroundPackage = "com.android.chrome",
                platformName = "SportyBet Aviator",
                gamePhase = "BETTING_OPEN",
                detectedMultiplier = null,
                uiTargets = com.example.service.DetectedUiTargets(
                    detectedUrl = "https://www.sportybet.com/ng/m/aviator",
                    ribbonMultipliers = listOf(2.30, 4.65, 15.74),
                    siteButtonState = com.example.data.model.SiteButtonState.IDLE_READY_TO_BET
                )
            ),
            onRealRoundCompleted = { recorded.add(it) },
            currentTimeMs = 31_000L
        )
        assertEquals(listOf(4.65, 2.30), recorded)
    }

    @Test
    fun `automation engine blocks double-bet on BET_QUEUED and picks matching site shortcut`() {
        val fakeDao = object : ScreenEarnerDao {
            override fun getAllShortcuts(): Flow<List<FloatingShortcut>> = emptyFlow()
            override suspend fun insertShortcut(shortcut: FloatingShortcut): Long = 1L
            override suspend fun updateShortcut(shortcut: FloatingShortcut) {}
            override suspend fun deleteShortcutById(id: Long) {}
            override suspend fun deleteAllShortcuts() {}
            override fun getAllProfiles(): Flow<List<PlatformProfile>> = emptyFlow()
            override suspend fun insertProfile(profile: PlatformProfile) {}
            override suspend fun setActiveProfile(activeId: String) {}
            override fun getStrategyConfig(): Flow<StrategyConfig?> = emptyFlow()
            override suspend fun saveStrategyConfig(config: StrategyConfig) {}
            override fun getRiskLimits(): Flow<RiskLimits?> = emptyFlow()
            override suspend fun saveRiskLimits(limits: RiskLimits) {}
            override fun getRecentSessionLogs(): Flow<List<SessionLog>> = emptyFlow()
            override suspend fun insertSessionLog(log: SessionLog) {}
            override suspend fun clearSessionLogs() {}
            override fun getAllMultiplierRecords(): Flow<List<MultiplierRecord>> = emptyFlow()
            override fun getMultiplierRecordsSince(sinceTimestamp: Long): Flow<List<MultiplierRecord>> = emptyFlow()
            override suspend fun getMultiplierRecordsSinceChronological(sinceTimestamp: Long): List<MultiplierRecord> = emptyList()
            override suspend fun insertMultiplierRecord(record: MultiplierRecord) {}
            override suspend fun insertMultiplierRecords(records: List<MultiplierRecord>) {}
            override suspend fun pruneMultiplierRecordsOlderThan(cutoffTimestamp: Long) {}
            override suspend fun clearMultiplierRecords() {}
            override suspend fun clearMultiplierRecordsForSession(sessionId: String) {}
            override fun getAllCustomStrategies(): Flow<List<CustomPatternStrategy>> = emptyFlow()
            override suspend fun getCustomStrategiesOnce(): List<CustomPatternStrategy> = emptyList()
            override suspend fun insertCustomStrategy(strategy: CustomPatternStrategy): Long = 1L
            override suspend fun updateCustomStrategy(strategy: CustomPatternStrategy) {}
            override suspend fun deleteCustomStrategyById(id: Long) {}
            override fun getNotificationSettings(): Flow<NotificationSettings?> = emptyFlow()
            override suspend fun getNotificationSettingsOnce(): NotificationSettings? = NotificationSettings()
            override suspend fun saveNotificationSettings(settings: NotificationSettings) {}
            override fun getAllNotificationHistory(): Flow<List<NotificationHistoryRecord>> = emptyFlow()
            override suspend fun insertNotificationHistory(record: NotificationHistoryRecord): Long = 1L
            override suspend fun updateNotificationInteraction(id: Long, status: NotificationInteractionStatus) {}
            override suspend fun clearNotificationHistory() {}
            override fun getAllCrashRecords(): Flow<List<com.example.data.model.CrashRecord>> = emptyFlow()
            override fun getCrashRecordsForSession(sessionId: String): Flow<List<com.example.data.model.CrashRecord>> = emptyFlow()
            override suspend fun getCrashRecordsForSessionOnce(sessionId: String): List<com.example.data.model.CrashRecord> = emptyList()
            override fun getCrashRecordsForGame(gameId: String): Flow<List<com.example.data.model.CrashRecord>> = emptyFlow()
            override suspend fun getCrashRecordsForGameSinceAsc(gameId: String, sinceMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordsForGameBetweenAsc(gameId: String, startMs: Long, endMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordsSinceAsc(sinceMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordsBetweenAsc(startMs: Long, endMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordById(id: Long): com.example.data.model.CrashRecord? = null
            override suspend fun existsCrashByDedupKey(dedupKey: String): Boolean = false
            override suspend fun insertCrashRecord(record: com.example.data.model.CrashRecord): Long = 1L
            override suspend fun insertCrashRecords(records: List<com.example.data.model.CrashRecord>): List<Long> = emptyList()
            override suspend fun updateCrashRecord(record: com.example.data.model.CrashRecord) {}
            override suspend fun clearCrashRecordsForSession(sessionId: String) {}
            override suspend fun clearCrashRecordsForGame(gameId: String) {}
            override suspend fun clearAllCrashRecords() {}
            override fun getAllCrashCorrectionAudits(): Flow<List<com.example.data.model.CrashCorrectionAudit>> = emptyFlow()
            override suspend fun insertCrashCorrectionAudit(audit: com.example.data.model.CrashCorrectionAudit): Long = 1L
            override fun getAllHourlySummaries(): Flow<List<com.example.data.model.HourlySessionSummary>> = emptyFlow()
            override fun getHourlySummariesForGame(gameId: String): Flow<List<com.example.data.model.HourlySessionSummary>> = emptyFlow()
            override suspend fun getHourlySummary(gameId: String, hourStartMs: Long): com.example.data.model.HourlySessionSummary? = null
            override suspend fun upsertHourlySummary(summary: com.example.data.model.HourlySessionSummary): Long = 1L
            override fun getAllPatternOccurrences(): Flow<List<com.example.data.model.PatternOccurrenceRecord>> = emptyFlow()
            override suspend fun getPatternOccurrencesForStrategyHour(strategyId: Long, hourStartMs: Long): List<com.example.data.model.PatternOccurrenceRecord> = emptyList()
            override suspend fun getLatestOccurrenceForStrategy(strategyId: Long): com.example.data.model.PatternOccurrenceRecord? = null
            override suspend fun insertPatternOccurrence(record: com.example.data.model.PatternOccurrenceRecord): Long = 1L
            override fun getAllMonitoringSessions(): Flow<List<com.example.data.model.MonitoringSessionEntity>> = emptyFlow()
            override suspend fun getActiveMonitoringSession(): com.example.data.model.MonitoringSessionEntity? = null
            override suspend fun upsertMonitoringSession(session: com.example.data.model.MonitoringSessionEntity) {}
            override suspend fun closeActiveMonitoringSessions(endedAtMs: Long) {}
            override suspend fun deleteArchivedSessionsForGame(gameId: String) {}
            override suspend fun clearAllMonitoringSessions() {}
            override suspend fun clearArchivedMultiplierRecordsForGame(gameId: String, activeSessionId: String) {}
            override suspend fun clearArchivedCrashRecordsForGame(gameId: String, activeSessionId: String) {}
            override suspend fun countCrashRecordsForSession(sessionId: String): Int = 0
            override suspend fun countArchivedCrashRecordsForGame(gameId: String, activeSessionId: String): Int = 0
            override suspend fun countAllCrashRecords(): Int = 0
            override suspend fun clearHourlySummariesForGame(gameId: String) {}
            override suspend fun clearAllHourlySummaries() {}
            override suspend fun clearPatternOccurrencesForGame(gameId: String) {}
            override suspend fun clearAllPatternOccurrences() {}
            override fun getAllAlarmProfiles(): Flow<List<com.example.data.model.AlarmProfileEntity>> = emptyFlow()
            override suspend fun getAlarmProfilesOnce(): List<com.example.data.model.AlarmProfileEntity> = emptyList()
            override suspend fun getAlarmProfile(categoryName: String): com.example.data.model.AlarmProfileEntity? = null
            override suspend fun upsertAlarmProfile(profile: com.example.data.model.AlarmProfileEntity) {}
            override suspend fun insertAlarmProfilesIfAbsent(profiles: List<com.example.data.model.AlarmProfileEntity>) {}
            override suspend fun clearNotificationHistoryForGame(gameId: String) {}
        }

        val betClickCoords = mutableListOf<Pair<Float?, Float?>>()
        val fakeInteractor = object : com.example.data.engine.TargetUiInteractor {
            override fun applyStake(
                newStake: Double,
                progressionMode: StakeProgressionMode,
                fallbackX: Float?,
                fallbackY: Float?,
                onComplete: (Boolean) -> Unit
            ) { onComplete(true) }

            override fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
                betClickCoords.add(fallbackX to fallbackY)
                onComplete(true)
            }

            override fun clickCashOut(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
                onComplete(true)
            }
        }

        val engine = AutomationEngine(
            repository = ScreenEarnerRepository(fakeDao),
            strategyEngine = StrategyEngine(),
            riskManager = RiskManager(),
            gameDetection = GameDetection(),
            targetUiInteractor = fakeInteractor
        )
        engine.updateCalibratedShortcuts(
            listOf(
                FloatingShortcut(id = 1, label = "BP BET", type = ShortcutType.BET, targetPlatform = "BetPawa", targetX = 111f, targetY = 222f, isCalibrated = true),
                FloatingShortcut(id = 2, label = "SB BET", type = ShortcutType.BET, targetPlatform = "SportyBet", targetX = 777f, targetY = 888f, isCalibrated = true)
            )
        )

        val config = configWithSingleRule(StrategyRuleId.AFTER_2_LOSSES)
        val limits = RiskLimits()
        engine.updateActivePlatform("SportyBet Aviator")
        engine.startAutomation(config, limits)
        engine.handleRoundCompleted(1.20, config, limits, currentTimeMs = 1_000L)
        engine.handleRoundCompleted(1.30, config, limits, currentTimeMs = 2_000L)

        // When site button shows Cancel (BET_QUEUED), no bet click occurs!
        engine.handlePhaseUpdate(
            phase = "BETTING_OPEN",
            liveMultiplier = 1.00,
            config = config,
            limits = limits,
            currentTimeMs = 3_000L,
            siteButtonState = com.example.data.model.SiteButtonState.BET_QUEUED
        )
        assertTrue(betClickCoords.isEmpty())

        // Once site button is IDLE_READY_TO_BET ("Bet"), SportyBet coordinates (777, 888) are tapped
        engine.handlePhaseUpdate(
            phase = "BETTING_OPEN",
            liveMultiplier = 1.00,
            config = config,
            limits = limits,
            currentTimeMs = 4_000L,
            siteButtonState = com.example.data.model.SiteButtonState.IDLE_READY_TO_BET
        )
        assertEquals(listOf(777f to 888f), betClickCoords)
    }

    @Test
    fun `session stats track wins losses total profit and halt all gestures when Stop if Profit Reaches triggers`() {
        val fakeDao = object : ScreenEarnerDao {
            override fun getAllShortcuts(): Flow<List<FloatingShortcut>> = emptyFlow()
            override suspend fun insertShortcut(shortcut: FloatingShortcut): Long = 1L
            override suspend fun updateShortcut(shortcut: FloatingShortcut) {}
            override suspend fun deleteShortcutById(id: Long) {}
            override suspend fun deleteAllShortcuts() {}
            override fun getAllProfiles(): Flow<List<PlatformProfile>> = emptyFlow()
            override suspend fun insertProfile(profile: PlatformProfile) {}
            override suspend fun setActiveProfile(activeId: String) {}
            override fun getStrategyConfig(): Flow<StrategyConfig?> = emptyFlow()
            override suspend fun saveStrategyConfig(config: StrategyConfig) {}
            override fun getRiskLimits(): Flow<RiskLimits?> = emptyFlow()
            override suspend fun saveRiskLimits(limits: RiskLimits) {}
            override fun getRecentSessionLogs(): Flow<List<SessionLog>> = emptyFlow()
            override suspend fun insertSessionLog(log: SessionLog) {}
            override suspend fun clearSessionLogs() {}
            override fun getAllMultiplierRecords(): Flow<List<MultiplierRecord>> = emptyFlow()
            override fun getMultiplierRecordsSince(sinceTimestamp: Long): Flow<List<MultiplierRecord>> = emptyFlow()
            override suspend fun getMultiplierRecordsSinceChronological(sinceTimestamp: Long): List<MultiplierRecord> = emptyList()
            override suspend fun insertMultiplierRecord(record: MultiplierRecord) {}
            override suspend fun insertMultiplierRecords(records: List<MultiplierRecord>) {}
            override suspend fun pruneMultiplierRecordsOlderThan(cutoffTimestamp: Long) {}
            override suspend fun clearMultiplierRecords() {}
            override suspend fun clearMultiplierRecordsForSession(sessionId: String) {}
            override fun getAllCustomStrategies(): Flow<List<CustomPatternStrategy>> = emptyFlow()
            override suspend fun getCustomStrategiesOnce(): List<CustomPatternStrategy> = emptyList()
            override suspend fun insertCustomStrategy(strategy: CustomPatternStrategy): Long = 1L
            override suspend fun updateCustomStrategy(strategy: CustomPatternStrategy) {}
            override suspend fun deleteCustomStrategyById(id: Long) {}
            override fun getNotificationSettings(): Flow<NotificationSettings?> = emptyFlow()
            override suspend fun getNotificationSettingsOnce(): NotificationSettings? = NotificationSettings()
            override suspend fun saveNotificationSettings(settings: NotificationSettings) {}
            override fun getAllNotificationHistory(): Flow<List<NotificationHistoryRecord>> = emptyFlow()
            override suspend fun insertNotificationHistory(record: NotificationHistoryRecord): Long = 1L
            override suspend fun updateNotificationInteraction(id: Long, status: NotificationInteractionStatus) {}
            override suspend fun clearNotificationHistory() {}
            override fun getAllCrashRecords(): Flow<List<com.example.data.model.CrashRecord>> = emptyFlow()
            override fun getCrashRecordsForSession(sessionId: String): Flow<List<com.example.data.model.CrashRecord>> = emptyFlow()
            override suspend fun getCrashRecordsForSessionOnce(sessionId: String): List<com.example.data.model.CrashRecord> = emptyList()
            override fun getCrashRecordsForGame(gameId: String): Flow<List<com.example.data.model.CrashRecord>> = emptyFlow()
            override suspend fun getCrashRecordsForGameSinceAsc(gameId: String, sinceMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordsForGameBetweenAsc(gameId: String, startMs: Long, endMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordsSinceAsc(sinceMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordsBetweenAsc(startMs: Long, endMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordById(id: Long): com.example.data.model.CrashRecord? = null
            override suspend fun existsCrashByDedupKey(dedupKey: String): Boolean = false
            override suspend fun insertCrashRecord(record: com.example.data.model.CrashRecord): Long = 1L
            override suspend fun insertCrashRecords(records: List<com.example.data.model.CrashRecord>): List<Long> = emptyList()
            override suspend fun updateCrashRecord(record: com.example.data.model.CrashRecord) {}
            override suspend fun clearCrashRecordsForSession(sessionId: String) {}
            override suspend fun clearCrashRecordsForGame(gameId: String) {}
            override suspend fun clearAllCrashRecords() {}
            override fun getAllCrashCorrectionAudits(): Flow<List<com.example.data.model.CrashCorrectionAudit>> = emptyFlow()
            override suspend fun insertCrashCorrectionAudit(audit: com.example.data.model.CrashCorrectionAudit): Long = 1L
            override fun getAllHourlySummaries(): Flow<List<com.example.data.model.HourlySessionSummary>> = emptyFlow()
            override fun getHourlySummariesForGame(gameId: String): Flow<List<com.example.data.model.HourlySessionSummary>> = emptyFlow()
            override suspend fun getHourlySummary(gameId: String, hourStartMs: Long): com.example.data.model.HourlySessionSummary? = null
            override suspend fun upsertHourlySummary(summary: com.example.data.model.HourlySessionSummary): Long = 1L
            override fun getAllPatternOccurrences(): Flow<List<com.example.data.model.PatternOccurrenceRecord>> = emptyFlow()
            override suspend fun getPatternOccurrencesForStrategyHour(strategyId: Long, hourStartMs: Long): List<com.example.data.model.PatternOccurrenceRecord> = emptyList()
            override suspend fun getLatestOccurrenceForStrategy(strategyId: Long): com.example.data.model.PatternOccurrenceRecord? = null
            override suspend fun insertPatternOccurrence(record: com.example.data.model.PatternOccurrenceRecord): Long = 1L
            override fun getAllMonitoringSessions(): Flow<List<com.example.data.model.MonitoringSessionEntity>> = emptyFlow()
            override suspend fun getActiveMonitoringSession(): com.example.data.model.MonitoringSessionEntity? = null
            override suspend fun upsertMonitoringSession(session: com.example.data.model.MonitoringSessionEntity) {}
            override suspend fun closeActiveMonitoringSessions(endedAtMs: Long) {}
            override suspend fun deleteArchivedSessionsForGame(gameId: String) {}
            override suspend fun clearAllMonitoringSessions() {}
            override suspend fun clearArchivedMultiplierRecordsForGame(gameId: String, activeSessionId: String) {}
            override suspend fun clearArchivedCrashRecordsForGame(gameId: String, activeSessionId: String) {}
            override suspend fun countCrashRecordsForSession(sessionId: String): Int = 0
            override suspend fun countArchivedCrashRecordsForGame(gameId: String, activeSessionId: String): Int = 0
            override suspend fun countAllCrashRecords(): Int = 0
            override suspend fun clearHourlySummariesForGame(gameId: String) {}
            override suspend fun clearAllHourlySummaries() {}
            override suspend fun clearPatternOccurrencesForGame(gameId: String) {}
            override suspend fun clearAllPatternOccurrences() {}
            override fun getAllAlarmProfiles(): Flow<List<com.example.data.model.AlarmProfileEntity>> = emptyFlow()
            override suspend fun getAlarmProfilesOnce(): List<com.example.data.model.AlarmProfileEntity> = emptyList()
            override suspend fun getAlarmProfile(categoryName: String): com.example.data.model.AlarmProfileEntity? = null
            override suspend fun upsertAlarmProfile(profile: com.example.data.model.AlarmProfileEntity) {}
            override suspend fun insertAlarmProfilesIfAbsent(profiles: List<com.example.data.model.AlarmProfileEntity>) {}
            override suspend fun clearNotificationHistoryForGame(gameId: String) {}
        }

        var betClicks = 0
        var cashOutClicks = 0
        val cancelReasons = mutableListOf<String>()
        val fakeInteractor = object : com.example.data.engine.TargetUiInteractor {
            override fun applyStake(
                newStake: Double,
                progressionMode: StakeProgressionMode,
                fallbackX: Float?,
                fallbackY: Float?,
                onComplete: (Boolean) -> Unit
            ) { onComplete(true) }

            override fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
                betClicks++
                onComplete(true)
            }

            override fun clickCashOut(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
                cashOutClicks++
                onComplete(true)
            }

            override fun cancelPendingGestures(reason: String) {
                cancelReasons.add(reason)
            }
        }

        val engine = AutomationEngine(
            repository = ScreenEarnerRepository(fakeDao),
            strategyEngine = StrategyEngine(),
            riskManager = RiskManager(),
            gameDetection = GameDetection(),
            targetUiInteractor = fakeInteractor
        )
        engine.updateCalibratedShortcuts(
            listOf(
                FloatingShortcut(id = 1, label = "BET", type = ShortcutType.BET, targetX = 300f, targetY = 1200f, isCalibrated = true),
                FloatingShortcut(id = 2, label = "CASH", type = ShortcutType.CASH_OUT, targetX = 600f, targetY = 1200f, isCalibrated = true)
            )
        )

        val config = configWithSingleRule(
            ruleId = StrategyRuleId.AFTER_2_LOSSES,
            mode = StakeProgressionMode.DOUBLE_AMOUNT,
            baseStake = 10.0,
            targetMultiplier = 2.00
        )
        val limits = RiskLimits(
            profitTarget = 15.0,
            stopOnProfitEnabled = true,
            maxSessionLoss = 500.0,
            stopOnLossEnabled = true
        )

        engine.startAutomation(config, limits)
        // Trigger AFTER_2_LOSSES
        engine.handleRoundCompleted(1.20, config, limits, currentTimeMs = 1_000L)
        engine.handleRoundCompleted(1.30, config, limits, currentTimeMs = 2_000L)

        // Round 1: Bet 10 -> Loss at 1.40x -> 0W / 1L, profit = -10.0, stake doubles to 20.0
        engine.handleRoundCompleted(1.40, config, limits, currentTimeMs = 3_000L)
        assertEquals(0, engine.sessionStats.value.winCount)
        assertEquals(1, engine.sessionStats.value.lossCount)
        assertEquals(-10.0, engine.sessionStats.value.totalProfit, 0.001)
        assertEquals(com.example.data.engine.AutomationState.RUNNING, engine.automationState.value)

        // Round 2: Bet 20 -> Win at 2.50x (net +20) -> 1W / 1L, total profit = +10.0 (< 15.0 target)
        engine.handleRoundCompleted(2.50, config, limits, currentTimeMs = 4_000L)
        assertEquals(1, engine.sessionStats.value.winCount)
        assertEquals(1, engine.sessionStats.value.lossCount)
        assertEquals(10.0, engine.sessionStats.value.totalProfit, 0.001)
        assertEquals(com.example.data.engine.AutomationState.RUNNING, engine.automationState.value)

        // Round 3: Bet 20 -> Win at 2.10x (net +20) -> 2W / 1L, total profit = +30.0 (>= 15.0 target!)
        engine.handleRoundCompleted(2.10, config, limits, currentTimeMs = 5_000L)
        assertEquals(2, engine.sessionStats.value.winCount)
        assertEquals(1, engine.sessionStats.value.lossCount)
        assertEquals(30.0, engine.sessionStats.value.totalProfit, 0.001)
        assertEquals(com.example.data.engine.AutomationState.PAUSED, engine.automationState.value)
        assertNotNull(engine.sessionStats.value.limitHaltReason)
        assertTrue(cancelReasons.any { it.contains("Stop if Profit Reaches", ignoreCase = true) })

        val clicksAtHalt = betClicks
        // Subsequent phases must NOT dispatch any more automation gestures
        engine.handlePhaseUpdate("BETTING_OPEN", 1.0, config, limits, currentTimeMs = 6_000L)
        engine.evaluateInFlightMultiplier(3.0, config, limits = limits)
        assertEquals(clicksAtHalt, betClicks)
        assertEquals(0, cashOutClicks)
    }

    @Test
    fun `Stop if Loss Reaches automatically halts all gestures and preserves loss telemetry`() {
        val fakeDao = object : ScreenEarnerDao {
            override fun getAllShortcuts(): Flow<List<FloatingShortcut>> = emptyFlow()
            override suspend fun insertShortcut(shortcut: FloatingShortcut): Long = 1L
            override suspend fun updateShortcut(shortcut: FloatingShortcut) {}
            override suspend fun deleteShortcutById(id: Long) {}
            override suspend fun deleteAllShortcuts() {}
            override fun getAllProfiles(): Flow<List<PlatformProfile>> = emptyFlow()
            override suspend fun insertProfile(profile: PlatformProfile) {}
            override suspend fun setActiveProfile(activeId: String) {}
            override fun getStrategyConfig(): Flow<StrategyConfig?> = emptyFlow()
            override suspend fun saveStrategyConfig(config: StrategyConfig) {}
            override fun getRiskLimits(): Flow<RiskLimits?> = emptyFlow()
            override suspend fun saveRiskLimits(limits: RiskLimits) {}
            override fun getRecentSessionLogs(): Flow<List<SessionLog>> = emptyFlow()
            override suspend fun insertSessionLog(log: SessionLog) {}
            override suspend fun clearSessionLogs() {}
            override fun getAllMultiplierRecords(): Flow<List<MultiplierRecord>> = emptyFlow()
            override fun getMultiplierRecordsSince(sinceTimestamp: Long): Flow<List<MultiplierRecord>> = emptyFlow()
            override suspend fun getMultiplierRecordsSinceChronological(sinceTimestamp: Long): List<MultiplierRecord> = emptyList()
            override suspend fun insertMultiplierRecord(record: MultiplierRecord) {}
            override suspend fun insertMultiplierRecords(records: List<MultiplierRecord>) {}
            override suspend fun pruneMultiplierRecordsOlderThan(cutoffTimestamp: Long) {}
            override suspend fun clearMultiplierRecords() {}
            override suspend fun clearMultiplierRecordsForSession(sessionId: String) {}
            override fun getAllCustomStrategies(): Flow<List<CustomPatternStrategy>> = emptyFlow()
            override suspend fun getCustomStrategiesOnce(): List<CustomPatternStrategy> = emptyList()
            override suspend fun insertCustomStrategy(strategy: CustomPatternStrategy): Long = 1L
            override suspend fun updateCustomStrategy(strategy: CustomPatternStrategy) {}
            override suspend fun deleteCustomStrategyById(id: Long) {}
            override fun getNotificationSettings(): Flow<NotificationSettings?> = emptyFlow()
            override suspend fun getNotificationSettingsOnce(): NotificationSettings? = NotificationSettings()
            override suspend fun saveNotificationSettings(settings: NotificationSettings) {}
            override fun getAllNotificationHistory(): Flow<List<NotificationHistoryRecord>> = emptyFlow()
            override suspend fun insertNotificationHistory(record: NotificationHistoryRecord): Long = 1L
            override suspend fun updateNotificationInteraction(id: Long, status: NotificationInteractionStatus) {}
            override suspend fun clearNotificationHistory() {}
            override fun getAllCrashRecords(): Flow<List<com.example.data.model.CrashRecord>> = emptyFlow()
            override fun getCrashRecordsForSession(sessionId: String): Flow<List<com.example.data.model.CrashRecord>> = emptyFlow()
            override suspend fun getCrashRecordsForSessionOnce(sessionId: String): List<com.example.data.model.CrashRecord> = emptyList()
            override fun getCrashRecordsForGame(gameId: String): Flow<List<com.example.data.model.CrashRecord>> = emptyFlow()
            override suspend fun getCrashRecordsForGameSinceAsc(gameId: String, sinceMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordsForGameBetweenAsc(gameId: String, startMs: Long, endMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordsSinceAsc(sinceMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordsBetweenAsc(startMs: Long, endMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordById(id: Long): com.example.data.model.CrashRecord? = null
            override suspend fun existsCrashByDedupKey(dedupKey: String): Boolean = false
            override suspend fun insertCrashRecord(record: com.example.data.model.CrashRecord): Long = 1L
            override suspend fun insertCrashRecords(records: List<com.example.data.model.CrashRecord>): List<Long> = emptyList()
            override suspend fun updateCrashRecord(record: com.example.data.model.CrashRecord) {}
            override suspend fun clearCrashRecordsForSession(sessionId: String) {}
            override suspend fun clearCrashRecordsForGame(gameId: String) {}
            override suspend fun clearAllCrashRecords() {}
            override fun getAllCrashCorrectionAudits(): Flow<List<com.example.data.model.CrashCorrectionAudit>> = emptyFlow()
            override suspend fun insertCrashCorrectionAudit(audit: com.example.data.model.CrashCorrectionAudit): Long = 1L
            override fun getAllHourlySummaries(): Flow<List<com.example.data.model.HourlySessionSummary>> = emptyFlow()
            override fun getHourlySummariesForGame(gameId: String): Flow<List<com.example.data.model.HourlySessionSummary>> = emptyFlow()
            override suspend fun getHourlySummary(gameId: String, hourStartMs: Long): com.example.data.model.HourlySessionSummary? = null
            override suspend fun upsertHourlySummary(summary: com.example.data.model.HourlySessionSummary): Long = 1L
            override fun getAllPatternOccurrences(): Flow<List<com.example.data.model.PatternOccurrenceRecord>> = emptyFlow()
            override suspend fun getPatternOccurrencesForStrategyHour(strategyId: Long, hourStartMs: Long): List<com.example.data.model.PatternOccurrenceRecord> = emptyList()
            override suspend fun getLatestOccurrenceForStrategy(strategyId: Long): com.example.data.model.PatternOccurrenceRecord? = null
            override suspend fun insertPatternOccurrence(record: com.example.data.model.PatternOccurrenceRecord): Long = 1L
            override fun getAllMonitoringSessions(): Flow<List<com.example.data.model.MonitoringSessionEntity>> = emptyFlow()
            override suspend fun getActiveMonitoringSession(): com.example.data.model.MonitoringSessionEntity? = null
            override suspend fun upsertMonitoringSession(session: com.example.data.model.MonitoringSessionEntity) {}
            override suspend fun closeActiveMonitoringSessions(endedAtMs: Long) {}
            override suspend fun deleteArchivedSessionsForGame(gameId: String) {}
            override suspend fun clearAllMonitoringSessions() {}
            override suspend fun clearArchivedMultiplierRecordsForGame(gameId: String, activeSessionId: String) {}
            override suspend fun clearArchivedCrashRecordsForGame(gameId: String, activeSessionId: String) {}
            override suspend fun countCrashRecordsForSession(sessionId: String): Int = 0
            override suspend fun countArchivedCrashRecordsForGame(gameId: String, activeSessionId: String): Int = 0
            override suspend fun countAllCrashRecords(): Int = 0
            override suspend fun clearHourlySummariesForGame(gameId: String) {}
            override suspend fun clearAllHourlySummaries() {}
            override suspend fun clearPatternOccurrencesForGame(gameId: String) {}
            override suspend fun clearAllPatternOccurrences() {}
            override fun getAllAlarmProfiles(): Flow<List<com.example.data.model.AlarmProfileEntity>> = emptyFlow()
            override suspend fun getAlarmProfilesOnce(): List<com.example.data.model.AlarmProfileEntity> = emptyList()
            override suspend fun getAlarmProfile(categoryName: String): com.example.data.model.AlarmProfileEntity? = null
            override suspend fun upsertAlarmProfile(profile: com.example.data.model.AlarmProfileEntity) {}
            override suspend fun insertAlarmProfilesIfAbsent(profiles: List<com.example.data.model.AlarmProfileEntity>) {}
            override suspend fun clearNotificationHistoryForGame(gameId: String) {}
        }

        var betClicks = 0
        val cancelReasons = mutableListOf<String>()
        val fakeInteractor = object : com.example.data.engine.TargetUiInteractor {
            override fun applyStake(
                newStake: Double,
                progressionMode: StakeProgressionMode,
                fallbackX: Float?,
                fallbackY: Float?,
                onComplete: (Boolean) -> Unit
            ) { onComplete(true) }

            override fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
                betClicks++
                onComplete(true)
            }

            override fun clickCashOut(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
                onComplete(true)
            }

            override fun cancelPendingGestures(reason: String) {
                cancelReasons.add(reason)
            }
        }

        val engine = AutomationEngine(
            repository = ScreenEarnerRepository(fakeDao),
            strategyEngine = StrategyEngine(),
            riskManager = RiskManager(),
            gameDetection = GameDetection(),
            targetUiInteractor = fakeInteractor
        )
        engine.updateCalibratedShortcuts(
            listOf(FloatingShortcut(id = 1, label = "BET", type = ShortcutType.BET, targetX = 300f, targetY = 1200f, isCalibrated = true))
        )

        val config = configWithSingleRule(
            ruleId = StrategyRuleId.AFTER_2_LOSSES,
            mode = StakeProgressionMode.DOUBLE_AMOUNT,
            baseStake = 15.0,
            targetMultiplier = 2.00
        )
        val limits = RiskLimits(
            profitTarget = 500.0,
            stopOnProfitEnabled = true,
            maxSessionLoss = 25.0,
            stopOnLossEnabled = true
        )

        engine.startAutomation(config, limits)
        engine.handleRoundCompleted(1.10, config, limits, currentTimeMs = 1_000L)
        engine.handleRoundCompleted(1.20, config, limits, currentTimeMs = 2_000L)

        // Round 1 loss of $15 -> totalProfit = -15.0 (< $25 limit), next proposed stake = $30 -> potential loss $45 > $25
        engine.handleRoundCompleted(1.30, config, limits, currentTimeMs = 3_000L)
        assertEquals(1, engine.sessionStats.value.lossCount)
        assertEquals(-15.0, engine.sessionStats.value.totalProfit, 0.001)

        // Next round attempt triggers Stop if Loss Reaches and cancels pending gestures immediately
        engine.handleRoundCompleted(1.40, config, limits, currentTimeMs = 4_000L)
        assertEquals(com.example.data.engine.AutomationState.PAUSED, engine.automationState.value)
        assertTrue(cancelReasons.any { it.contains("Stop if Loss Reaches", ignoreCase = true) })
        assertEquals(1, betClicks)
    }
}
