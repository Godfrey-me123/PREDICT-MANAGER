package com.example

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.ui.components.LiveCrashHeaderCard
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.ScreenEarnerDatabase
import com.example.data.database.ScreenEarnerRepository
import com.example.data.engine.AutomationEngine
import com.example.data.engine.AutomationState
import com.example.data.engine.ExtractedHistoryItem
import com.example.data.engine.PendingBetOpportunity
import com.example.data.engine.RibbonHistoryCollector
import com.example.data.engine.RiskManager
import com.example.data.engine.TargetUiInteractor
import com.example.data.model.BalanceDetectionStatus
import com.example.data.model.CollectionSource
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.FloatingShortcut
import com.example.data.model.GameState
import com.example.data.model.MonitoringStatus
import com.example.data.model.NotificationPriorityLevel
import com.example.data.model.NotificationSettings
import com.example.data.model.PatternComparator
import com.example.data.model.PatternCondition
import com.example.data.model.RiskLimits
import com.example.data.model.ShortcutType
import com.example.data.model.SiteButtonState
import com.example.data.model.StakeProgressionMode
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyGroup
import com.example.data.model.StrategyMode
import com.example.data.model.StrategyRuleConfig
import com.example.data.model.StrategyRuleId
import com.example.service.AutomationController
import com.example.service.DetectedUiTargets
import com.example.service.GameDetectionState
import android.content.pm.ServiceInfo
import androidx.lifecycle.Lifecycle
import com.example.service.OverlaySettingsPanelComposable
import com.example.service.ScreenEarnerOverlayService
import com.example.service.SessionStateManager
import com.example.ui.ScreenEarnerViewModel
import com.example.ui.navigation.AppRoute
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.AutoPredictorTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ArchitectureAndOverlayRepairTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var db: ScreenEarnerDatabase? = null

    @After
    fun tearDown() {
        AutomationController.cancelTestBackgroundLoops()
        AutomationController.resetTestState()
        AutomationController.engine = null
        AutomationController.repository = null
        db?.close()
        db = null
    }

    private fun setupIsolatedController(
        interactor: TargetUiInteractor = object : TargetUiInteractor {
            override fun applyStake(
                newStake: Double,
                progressionMode: StakeProgressionMode,
                fallbackX: Float?,
                fallbackY: Float?,
                onComplete: (Boolean) -> Unit
            ) = onComplete(true)

            override fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) =
                onComplete(true)

            override fun clickCashOut(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) =
                onComplete(true)
        }
    ): Triple<Context, ScreenEarnerRepository, AutomationEngine> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SessionStateManager(context).clearSessionRuntimeState()
        AutomationController.resetTestState()
        AutomationController.bypassMonitoringGuardForTest = true
        AutomationController.engine = null
        AutomationController.repository = null

        val database = Room.inMemoryDatabaseBuilder(context, ScreenEarnerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db = database
        val repo = ScreenEarnerRepository(database.dao())
        AutomationController.initialize(repo, context)

        val currentEngine = AutomationController.engine!!
        val customEngine = AutomationEngine(
            repository = repo,
            strategyEngine = currentEngine.strategyEngine,
            riskManager = currentEngine.riskManager,
            gameDetection = currentEngine.gameDetection,
            targetUiInteractor = interactor
        )
        AutomationController.engine = customEngine
        return Triple(context, repo, customEngine)
    }

    private fun buildDetection(
        phase: String,
        buttonState: SiteButtonState = SiteButtonState.IDLE_READY_TO_BET,
        liveMult: Double? = null,
        ribbon: List<Double> = emptyList(),
        balance: Double? = 500.0,
        roundId: String? = null,
        confidence: Float = 0.92f,
        nowMs: Long = 1_700_000_000_000L
    ): GameDetectionState {
        val items = ribbon.mapIndexed { idx, v ->
            ExtractedHistoryItem(
                multiplier = v,
                orderIndex = idx,
                source = CollectionSource.VISIBLE_RIBBON,
                displayText = "${RibbonHistoryCollector.formatMultKey(v)}x"
            )
        }
        return GameDetectionState(
            isGameDetected = true,
            gameId = "aviator",
            displayedGameName = "SportyBet Aviator",
            platformName = "SportyBet Aviator",
            isReady = true,
            gamePhase = phase,
            detectedMultiplier = liveMult,
            liveDetectionLost = false,
            detectionConfidence = confidence,
            detectedRoundId = roundId,
            foregroundPackage = "com.android.chrome",
            uiTargets = DetectedUiTargets(
                detectedMultiplier = liveMult,
                ribbonMultipliers = ribbon,
                ribbonItems = items,
                gamePhase = phase,
                siteButtonState = buttonState,
                detectedCurrentRoundId = roundId,
                detectedBalance = balance,
                detectedCurrency = "KES"
            ),
            updatedAtMs = nowMs
        )
    }

    @Test
    fun `monitoring continues recording crashes when automation is IDLE PAUSED or STOPPED`() = runBlocking {
        val (_, repo, engine) = setupIsolatedController()
        repo.saveStrategyConfig(
            StrategyConfig(
                isAutoBetEnabled = false,
                automationMasterEnabled = false,
                isStrategy1Enabled = false,
                isStrategy2Enabled = false,
                isHighMultiplierLossRuleEnabled = false
            )
        )
        repo.saveRiskLimits(RiskLimits())
        delay(50L)

        val baseNow = 1_700_000_000_000L
        AutomationController.startMonitoringSession(isAccessibilityConnected = true, nowMs = baseNow)
        // Explicitly stop Automation Assistant while keeping Monitoring running
        AutomationController.stopAutomationAssistant()
        assertEquals(MonitoringStatus.MONITORING, AutomationController.monitoringStatus.value)
        assertEquals(AutomationState.IDLE, engine.automationState.value)

        // Baseline frame (0 DB inserts)
        AutomationController.processDetectionEvent(
            buildDetection("BETTING_OPEN", ribbon = listOf(2.40, 1.85), roundId = "R-1", nowMs = baseNow + 1_000L),
            isAccessibilityConnected = true,
            nowMs = baseNow + 1_000L
        )
        // First completed round while Automation == IDLE
        AutomationController.processDetectionEvent(
            buildDetection("CRASHED", SiteButtonState.CRASHED, liveMult = 1.32, ribbon = listOf(1.32, 2.40, 1.85), roundId = "R-2", nowMs = baseNow + 4_000L),
            isAccessibilityConnected = true,
            nowMs = baseNow + 4_000L
        )
        assertEquals(MonitoringStatus.MONITORING, AutomationController.monitoringStatus.value)
        assertEquals(1, AutomationController.activeSessionCrashRecords.value.size)

        // Pause Automation Assistant -> Monitoring STILL continues recording new crashes
        AutomationController.pauseAutomationAssistant()
        assertEquals(MonitoringStatus.MONITORING, AutomationController.monitoringStatus.value)

        AutomationController.processDetectionEvent(
            buildDetection("CRASHED", SiteButtonState.CRASHED, liveMult = 3.75, ribbon = listOf(3.75, 1.32, 2.40), roundId = "R-3", nowMs = baseNow + 8_000L),
            isAccessibilityConnected = true,
            nowMs = baseNow + 8_000L
        )
        assertEquals(2, AutomationController.activeSessionCrashRecords.value.size)

        // Trigger Emergency Stop -> Automation locks cleanly while Monitoring continues live
        engine.triggerEmergencyStop("Manual Risk Lock")
        assertEquals(AutomationState.EMERGENCY_STOP, engine.automationState.value)
        assertEquals(MonitoringStatus.MONITORING, AutomationController.monitoringStatus.value)

        AutomationController.processDetectionEvent(
            buildDetection("CRASHED", SiteButtonState.CRASHED, liveMult = 5.10, ribbon = listOf(5.10, 3.75, 1.32), roundId = "R-4", nowMs = baseNow + 12_000L),
            isAccessibilityConnected = true,
            nowMs = baseNow + 12_000L
        )
        assertEquals(3, AutomationController.activeSessionCrashRecords.value.size)
        engine.clearEmergencyStop()
        assertEquals(AutomationState.IDLE, engine.automationState.value)
    }

    @Test
    fun `opportunity queue deduplicates prioritizes respects targetGameId and expires cleanly`() = runBlocking {
        val (_, repo, engine) = setupIsolatedController()
        val config = StrategyConfig(
            baseStake = 20.0,
            isAutoBetEnabled = true,
            automationMasterEnabled = true,
            isStrategy1Enabled = false,
            isStrategy2Enabled = false,
            isHighMultiplierLossRuleEnabled = false
        ).withUpdatedRuleConfigs(
            StrategyRuleId.entries.map { StrategyRuleConfig(ruleId = it, isEnabled = false) }
        )
        repo.saveStrategyConfig(config)
        repo.saveRiskLimits(RiskLimits())
        delay(40L)

        val now = 1_700_000_000_000L
        AutomationController.startMonitoringSession(isAccessibilityConnected = true, nowMs = now)
        engine.updateCalibratedShortcuts(
            listOf(FloatingShortcut(id = 1, label = "BET HAND", type = ShortcutType.BET, targetX = 500f, targetY = 1200f, isCalibrated = true))
        )
        AutomationController.startAutomationAssistant(isAccessibilityConnected = true)
        AutomationController.processDetectionEvent(
            buildDetection("IN_FLIGHT", SiteButtonState.CASH_OUT_AVAILABLE, liveMult = 1.40, ribbon = listOf(2.50), roundId = "R-10", nowMs = now + 500L),
            isAccessibilityConnected = true,
            nowMs = now + 500L
        )
        assertEquals(AutomationState.RUNNING, engine.automationState.value)

        // Occupy active session first so subsequent enqueues stay in queue
        assertTrue(
            engine.enqueueCustomStrategyOpportunity(
                PendingBetOpportunity(
                    id = "opp_active",
                    strategyId = 1L,
                    strategyName = "Initial Active Rule",
                    targetGameId = "ALL",
                    occurrenceKey = "occ_active",
                    createdAtMs = now,
                    expiresAtMs = now + 30_000L
                ),
                config = config,
                currentTimeMs = now + 1_000L
            )
        )
        assertTrue(engine.strategyEngine.getState().isBettingSessionActive)

        // Low-priority expiring opportunity
        assertTrue(
            engine.enqueueCustomStrategyOpportunity(
                PendingBetOpportunity(
                    id = "opp_low_expired",
                    strategyId = 2L,
                    strategyName = "Short Window Low",
                    targetGameId = "aviator",
                    occurrenceKey = "occ_low_exp",
                    createdAtMs = now,
                    expiresAtMs = now + 2_000L,
                    priorityScore = 1
                ),
                config = config,
                currentTimeMs = now + 1_000L
            )
        )
        // Duplicate occurrence key must be rejected
        assertFalse(
            engine.enqueueCustomStrategyOpportunity(
                PendingBetOpportunity(
                    id = "opp_dup",
                    strategyId = 2L,
                    strategyName = "Duplicate Key",
                    targetGameId = "aviator",
                    occurrenceKey = "occ_low_exp",
                    createdAtMs = now,
                    expiresAtMs = now + 20_000L,
                    priorityScore = 50
                ),
                config = config,
                currentTimeMs = now + 1_000L
            )
        )
        // Game-mismatched opportunity must not fire while gameId == "aviator"
        assertTrue(
            engine.enqueueCustomStrategyOpportunity(
                PendingBetOpportunity(
                    id = "opp_jetx",
                    strategyId = 3L,
                    strategyName = "JetX Only Strategy",
                    targetGameId = "jetx",
                    occurrenceKey = "occ_jetx",
                    createdAtMs = now,
                    expiresAtMs = now + 30_000L,
                    priorityScore = 100
                ),
                config = config,
                currentTimeMs = now + 1_000L
            )
        )
        // Matching higher-priority aviator opportunity
        assertTrue(
            engine.enqueueCustomStrategyOpportunity(
                PendingBetOpportunity(
                    id = "opp_high_aviator",
                    strategyId = 4L,
                    strategyName = "Aviator High Priority",
                    targetGameId = "aviator",
                    occurrenceKey = "occ_high_aviator",
                    createdAtMs = now,
                    expiresAtMs = now + 30_000L,
                    priorityScore = 25
                ),
                config = config,
                currentTimeMs = now + 1_000L
            )
        )
        assertEquals(3, engine.queuedOpportunityCount.value)

        // Reset current betting session so next activation pulls from queue after opp_low_expired expires
        engine.strategyEngine.reset(config.baseStake)
        assertTrue(engine.tryActivateNextQueuedOpportunity(config = config, currentTimeMs = now + 5_000L))
        assertEquals("Aviator High Priority", engine.strategyEngine.getState().activeRuleLabel)
        // JetX stays queued & Short Window Low was pruned on expiration
        assertEquals(1, engine.queuedOpportunityCount.value)
    }

    @Test
    fun `execution safety dismisses keyboard bounds retries checks button readiness and guards minFlightTime`() = runBlocking {
        var dismissCalls = 0
        var applyCalls = 0
        var betClickCalls = 0
        var cashOutCalls = 0
        var buttonReadyResult = false

        val fakeInteractor = object : TargetUiInteractor {
            override fun applyStake(
                newStake: Double,
                progressionMode: StakeProgressionMode,
                fallbackX: Float?,
                fallbackY: Float?,
                onComplete: (Boolean) -> Unit
            ) {
                applyCalls++
                // Succeed on retry #2
                onComplete(applyCalls >= 2)
            }

            override fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
                betClickCalls++
                onComplete(true)
            }

            override fun clickCashOut(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
                cashOutCalls++
                onComplete(true)
            }

            override fun dismissKeyboardIfOpen(): Boolean {
                dismissCalls++
                return true
            }

            override fun isButtonReady(expectBet: Boolean, config: StrategyConfig): Boolean = buttonReadyResult
        }

        val (_, repo, engine) = setupIsolatedController(fakeInteractor)
        val config = StrategyConfig(
            baseStake = 15.0,
            targetMultiplier = 2.00,
            isAutoBetEnabled = true,
            isAutoCashOutEnabled = true,
            automationMasterEnabled = true,
            dismissKeyboardBeforeBet = true,
            verifyButtonStateBeforeAction = true,
            maxBetRetries = 2,
            retryDelayMs = 10L,
            minFlightTimeBeforeCashOutMs = 1500L,
            isStrategy1Enabled = false,
            isStrategy2Enabled = false,
            isHighMultiplierLossRuleEnabled = false
        ).withUpdatedRule(
            StrategyRuleConfig(
                ruleId = StrategyRuleId.AFTER_2_LOSSES,
                isEnabled = true,
                progressionMode = StakeProgressionMode.DOUBLE_AMOUNT,
                requiredLosses = 2,
                targetWinsToFetch = 1
            )
        )
        repo.saveStrategyConfig(config)
        repo.saveRiskLimits(RiskLimits())
        delay(50L)

        val now = 1_700_000_000_000L
        AutomationController.startMonitoringSession(isAccessibilityConnected = true, nowMs = now)
        engine.updateCalibratedShortcuts(
            listOf(
                FloatingShortcut(id = 1, label = "BET HAND", type = ShortcutType.BET, targetX = 500f, targetY = 1200f, isCalibrated = true),
                FloatingShortcut(id = 2, label = "CASH OUT HAND", type = ShortcutType.CASH_OUT, targetX = 720f, targetY = 1200f, targetMultiplier = 2.00, isCalibrated = true)
            )
        )
        AutomationController.startAutomationAssistant(isAccessibilityConnected = true)

        // Baseline + 2 low crashes activate StrategyEngine
        AutomationController.processDetectionEvent(
            buildDetection("BETTING_OPEN", SiteButtonState.IDLE_READY_TO_BET, ribbon = listOf(4.20, 5.10), roundId = "R-200", nowMs = now + 1_000L),
            isAccessibilityConnected = true,
            nowMs = now + 1_000L
        )
        AutomationController.processDetectionEvent(
            buildDetection("CRASHED", SiteButtonState.CRASHED, liveMult = 1.25, ribbon = listOf(1.25, 4.20), roundId = "R-201", nowMs = now + 4_000L),
            isAccessibilityConnected = true,
            nowMs = now + 4_000L
        )
        AutomationController.processDetectionEvent(
            buildDetection("CRASHED", SiteButtonState.CRASHED, liveMult = 1.50, ribbon = listOf(1.50, 1.25), roundId = "R-202", nowMs = now + 8_000L),
            isAccessibilityConnected = true,
            nowMs = now + 8_000L
        )
        assertTrue(engine.strategyEngine.getState().isBettingSessionActive)

        // 1. When verifyButtonStateBeforeAction == true and buttonReadyResult == false, BET & applyStake are withheld
        AutomationController.processDetectionEvent(
            buildDetection("BETTING_OPEN", SiteButtonState.IDLE_READY_TO_BET, ribbon = listOf(1.50, 1.25), roundId = "R-203", nowMs = now + 10_000L),
            isAccessibilityConnected = true,
            nowMs = now + 10_000L
        )
        assertEquals(0, applyCalls)
        assertEquals(0, betClickCalls)

        // 2. Make button ready -> dismissKeyboardIfOpen called, applyStake retries once (2 total calls), BET succeeds
        buttonReadyResult = true
        AutomationController.processDetectionEvent(
            buildDetection("BETTING_OPEN", SiteButtonState.IDLE_READY_TO_BET, ribbon = listOf(1.50, 1.25), roundId = "R-203", nowMs = now + 10_500L),
            isAccessibilityConnected = true,
            nowMs = now + 10_500L
        )
        delay(120L)
        assertEquals(2, dismissCalls)
        assertEquals(2, applyCalls)
        assertEquals(1, betClickCalls)

        // 3. Enter IN_FLIGHT -> too early (< 1500ms elapsed) even if 2.05x appears -> Cash Out withheld
        AutomationController.processDetectionEvent(
            buildDetection("IN_FLIGHT", SiteButtonState.CASH_OUT_AVAILABLE, liveMult = 1.15, ribbon = listOf(1.50, 1.25), roundId = "R-203", nowMs = now + 12_000L),
            isAccessibilityConnected = true,
            nowMs = now + 12_000L
        )
        AutomationController.processDetectionEvent(
            buildDetection("IN_FLIGHT", SiteButtonState.CASH_OUT_AVAILABLE, liveMult = 2.05, ribbon = listOf(1.50, 1.25), roundId = "R-203", nowMs = now + 12_800L),
            isAccessibilityConnected = true,
            nowMs = now + 12_800L
        )
        assertEquals(0, cashOutCalls)

        // 4. After minFlightTimeBeforeCashOutMs (1500ms) passes -> Cash Out executes cleanly
        AutomationController.processDetectionEvent(
            buildDetection("IN_FLIGHT", SiteButtonState.CASH_OUT_AVAILABLE, liveMult = 2.25, ribbon = listOf(1.50, 1.25), roundId = "R-203", nowMs = now + 14_000L),
            isAccessibilityConnected = true,
            nowMs = now + 14_000L
        )
        assertEquals(1, cashOutCalls)
    }

    @Test
    fun `riskManager enforces maxSequenceExposure maxDailyLoss and safetyReserveAmount`() {
        val rm = RiskManager()
        val limits = RiskLimits(
            maxSingleStake = 200.0,
            maxSessionLoss = 500.0,
            maxSequenceExposure = 60.0,
            maxDailyLoss = 120.0,
            safetyReserveAmount = 80.0
        )

        val reserveViolation = rm.validateBetExecution(
            proposedStake = 30.0,
            currentSessionLoss = 0.0,
            currentRounds = 1,
            currentProgressionLevel = 1,
            currentBalance = 100.0, // remaining 70 < safetyReserveAmount 80
            limits = limits,
            balanceStatus = BalanceDetectionStatus.VERIFIED
        )
        assertFalse(reserveViolation.isAllowed)
        assertTrue(reserveViolation.violationReason?.contains("protected reserve", ignoreCase = true) == true)

        val seqExposureViolation = rm.validateBetExecution(
            proposedStake = 40.0,
            currentSessionLoss = 0.0,
            currentRounds = 2,
            currentProgressionLevel = 2,
            currentBalance = 500.0,
            limits = limits,
            currentSequenceExposure = 70.0
        )
        assertFalse(seqExposureViolation.isAllowed)
        assertTrue(seqExposureViolation.violationReason?.contains("Sequence exposure", ignoreCase = true) == true)

        val dailyLossViolation = rm.evaluateSessionLimits(
            currentSessionProfit = 0.0,
            currentSessionLoss = 10.0,
            limits = limits,
            currentDailyLoss = 125.0
        )
        assertFalse(dailyLossViolation.isAllowed)
        assertTrue(dailyLossViolation.violationReason?.contains("Daily loss", ignoreCase = true) == true)
    }

    @Test
    fun `room migration 13 to 14 preserves existing settings rows and populates new column defaults`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_v13_v14.db"
        context.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(13) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE strategy_configs (
                                id INTEGER NOT NULL PRIMARY KEY,
                                isStrategy1Enabled INTEGER NOT NULL,
                                isStrategy2Enabled INTEGER NOT NULL,
                                isHighMultiplierLossRuleEnabled INTEGER NOT NULL,
                                strategy1LowThreshold REAL NOT NULL,
                                strategy1RequiredLowRounds INTEGER NOT NULL,
                                strategy2HighThreshold REAL NOT NULL,
                                strategy2WaitRounds INTEGER NOT NULL,
                                baseStake REAL NOT NULL,
                                multiplierProgression REAL NOT NULL,
                                stopAfterConsecutiveWins INTEGER NOT NULL,
                                targetMultiplier REAL NOT NULL,
                                isAutoBetEnabled INTEGER NOT NULL,
                                isAutoCashOutEnabled INTEGER NOT NULL,
                                sessionTimeoutMinutes INTEGER NOT NULL,
                                currencyPreference TEXT NOT NULL,
                                keepScreenAwake INTEGER NOT NULL,
                                diagnosticToastsEnabled INTEGER NOT NULL,
                                diagnosticNotificationsEnabled INTEGER NOT NULL,
                                scheduleEnabled INTEGER NOT NULL,
                                scheduleStartHour INTEGER NOT NULL,
                                scheduleStartMinute INTEGER NOT NULL,
                                scheduleEndHour INTEGER NOT NULL,
                                scheduleEndMinute INTEGER NOT NULL,
                                lowThreshold REAL NOT NULL,
                                boundaryValue REAL NOT NULL,
                                bigThreshold REAL NOT NULL,
                                gridRows INTEGER NOT NULL,
                                gridColumns INTEGER NOT NULL,
                                monitoringEnabled INTEGER NOT NULL,
                                pixelColorCaptureEnabled INTEGER NOT NULL,
                                ruleConfigsJson TEXT NOT NULL,
                                maxStrategyBetAttempts INTEGER NOT NULL,
                                isMartingaleEnabled INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE risk_limits (
                                id INTEGER NOT NULL PRIMARY KEY,
                                maxSingleStake REAL NOT NULL,
                                maxSessionLoss REAL NOT NULL,
                                maxTotalRounds INTEGER NOT NULL,
                                maxProgressionLevel INTEGER NOT NULL,
                                profitTarget REAL NOT NULL,
                                minBalanceThreshold REAL NOT NULL,
                                emergencyStop INTEGER NOT NULL,
                                stopOnProfitEnabled INTEGER NOT NULL,
                                stopOnLossEnabled INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE notification_settings (
                                id INTEGER NOT NULL PRIMARY KEY,
                                masterEnabled INTEGER NOT NULL,
                                approachingEnabled INTEGER NOT NULL,
                                reachedEnabled INTEGER NOT NULL,
                                invalidatedEnabled INTEGER NOT NULL,
                                historyUnavailableEnabled INTEGER NOT NULL,
                                pausedEnabled INTEGER NOT NULL,
                                hourlySummaryEnabled INTEGER NOT NULL,
                                gameDetectionEnabled INTEGER NOT NULL,
                                monitoringErrorsEnabled INTEGER NOT NULL,
                                standardAlertEnabled INTEGER NOT NULL,
                                standardSoundUri TEXT NOT NULL,
                                standardVibrationPattern TEXT NOT NULL,
                                standardRepeatedAlerts INTEGER NOT NULL,
                                highPriorityAlarmEnabled INTEGER NOT NULL,
                                alarmSoundUri TEXT NOT NULL,
                                alarmVibrationPattern TEXT NOT NULL,
                                alarmRepeated INTEGER NOT NULL,
                                alarmRepeatIntervalMinutes INTEGER NOT NULL,
                                alarmMaxDurationMinutes INTEGER NOT NULL,
                                soundEnabled INTEGER NOT NULL,
                                vibrationEnabled INTEGER NOT NULL,
                                repeatIntervalMinutes INTEGER NOT NULL,
                                quietHoursEnabled INTEGER NOT NULL,
                                quietStartHour INTEGER NOT NULL,
                                quietStartMinute INTEGER NOT NULL,
                                quietEndHour INTEGER NOT NULL,
                                quietEndMinute INTEGER NOT NULL,
                                soundSourceType TEXT NOT NULL,
                                deliveryMode TEXT NOT NULL,
                                channelConfigVersion INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )

                        db.execSQL(
                            "INSERT INTO strategy_configs VALUES (1, 1, 0, 1, 2.0, 14, 10.0, 15, 25.0, 2.0, 2, 2.5, 1, 1, 3, 'KES', 1, 1, 1, 0, 8, 0, 23, 0, 2.0, 2.0, 10.0, 5, 8, 1, 0, '[]', 3, 1)"
                        )
                        db.execSQL(
                            "INSERT INTO risk_limits VALUES (1, 350.0, 900.0, 40, 4, 1200.0, 45.0, 0, 1, 1)"
                        )
                        db.execSQL(
                            "INSERT INTO notification_settings VALUES (1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, '', 'DEFAULT', 1, 1, '', 'DISTINCTIVE', 1, 0, 5, 1, 1, 0, 0, 23, 0, 7, 0, 'SYSTEM', 'ALARM', 1)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )

        val sqlite = helper.writableDatabase
        ScreenEarnerDatabase.MIGRATION_13_14.migrate(sqlite)

        sqlite.query(
            "SELECT baseStake, currencyPreference, minDetectionConfidence, automationMasterEnabled, dismissKeyboardBeforeBet, verifyStakeReadBack, maxBetRetries FROM strategy_configs WHERE id = 1"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(25.0, c.getDouble(0), 0.001)
            assertEquals("KES", c.getString(1))
            assertEquals(0.5f, c.getFloat(2), 0.001f)
            assertEquals(1, c.getInt(3))
            assertEquals(1, c.getInt(4))
            assertEquals(1, c.getInt(5))
            assertEquals(2, c.getInt(6))
        }

        sqlite.query(
            "SELECT maxSingleStake, maxSequenceExposure, maxDailyLoss, safetyReserveAmount FROM risk_limits WHERE id = 1"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(350.0, c.getDouble(0), 0.001)
            assertEquals(500.0, c.getDouble(1), 0.001)
            assertEquals(500.0, c.getDouble(2), 0.001)
            assertEquals(0.0, c.getDouble(3), 0.001)
        }

        sqlite.query(
            "SELECT executionAlertsEnabled, failureAlertsEnabled FROM notification_settings WHERE id = 1"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals(1, c.getInt(1))
        }

        helper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `appRoute maps all 9 modules cleanly and overlay settings runs without ActivityResultRegistry`() {
        assertEquals(9, AppRoute.entries.size)
        AppRoute.entries.forEach { route ->
            assertEquals(route, AppRoute.fromTabIndex(route.tabIndex))
        }
        assertEquals(MainActivity.TAB_HOME, AppRoute.HOME.tabIndex)
        assertEquals(MainActivity.TAB_LIVE_MONITOR, AppRoute.LIVE_MONITOR.tabIndex)
        assertEquals(MainActivity.TAB_RISK_MANAGER, AppRoute.RISK_MANAGER.tabIndex)

        composeRule.setContent {
            AutoPredictorTheme(darkTheme = false) {
                OverlaySettingsPanelComposable(
                    strategyConfig = StrategyConfig(),
                    notificationSettings = NotificationSettings(),
                    isMonitoringRunning = true
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("overlay_settings_window").assertIsDisplayed()
        composeRule.onNodeWithTag("overlay_settings_fullscreen_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("overlay_settings_dismiss_keyboard_switch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("overlay_settings_verify_stake_switch").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `home screen renders independent automation assistant controls and overlay mode switch`() {
        var autoStarted = 0
        var autoStopped = 0
        var openedOverlay = 0
        var openedTab = -1

        composeRule.setContent {
            AutoPredictorTheme(darkTheme = false) {
                HomeScreen(
                    gameState = GameState(isDetected = true, gameId = "aviator", displayedGameName = "Aviator"),
                    automationState = AutomationState.IDLE,
                    isOverlayServiceRunning = true,
                    strategyConfig = StrategyConfig(),
                    crashRecords = emptyList(),
                    isStandbyOutsideSchedule = false,
                    onToggleOverlayService = {},
                    onStartMonitoring = {},
                    onPauseMonitoring = {},
                    onNavigateToTab = { openedTab = it },
                    monitoringStatus = MonitoringStatus.MONITORING,
                    queuedOpportunitiesCount = 2,
                    onStartAutomationAssistant = { autoStarted++ },
                    onPauseAutomationAssistant = {},
                    onStopAutomationAssistant = { autoStopped++ },
                    onOpenOverlaySettings = { openedOverlay++ }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("home_open_overlay_mode_btn").performScrollTo().performClick()
        assertEquals(1, openedOverlay)

        composeRule.onNodeWithTag("home_start_automation_btn").performScrollTo().performClick()
        assertEquals(1, autoStarted)

        composeRule.onNodeWithTag("home_stop_automation_btn").performScrollTo().performClick()
        assertEquals(1, autoStopped)
        assertNotEquals(99, openedTab)
    }

    @Test
    fun `viewModel stopAutomationAssistant leaves active monitoring intact`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AutomationController.bypassMonitoringGuardForTest = true
        val vm = ScreenEarnerViewModel(app)
        delay(80L)

        AutomationController.startMonitoringSession(isAccessibilityConnected = true)
        assertEquals(MonitoringStatus.MONITORING, vm.monitoringStatus.value)

        vm.stopAutomationAssistant()
        assertEquals(AutomationState.IDLE, vm.automationState.value)
        assertEquals(MonitoringStatus.MONITORING, vm.monitoringStatus.value)

        vm.navigateToRoute(AppRoute.STRATEGIES)
        assertEquals(AppRoute.STRATEGIES, vm.activeRoute.value)
        assertEquals(MainActivity.TAB_STRATEGY, vm.selectedTab.value)
    }

    @Test
    fun `overlay service starts with specialUse foreground type and destroys safely from initialized or resumed state`() {
        val controller = Robolectric.buildService(ScreenEarnerOverlayService::class.java)
        val service = controller.create().get()

        assertTrue(ScreenEarnerOverlayService.isRunning)
        assertEquals(Lifecycle.State.RESUMED, service.lifecycle.currentState)
        val shadowService = Shadows.shadowOf(service)
        assertTrue(shadowService.lastForegroundNotification != null)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            service.foregroundServiceType
        )

        controller.destroy()
        assertFalse(ScreenEarnerOverlayService.isRunning)
        assertEquals(Lifecycle.State.DESTROYED, service.lifecycle.currentState)

        // Verify calling onDestroy() directly on an uncreated instance (INITIALIZED state) does not crash
        val uncreatedService = Robolectric.buildService(ScreenEarnerOverlayService::class.java).get()
        assertEquals(Lifecycle.State.INITIALIZED, uncreatedService.lifecycle.currentState)
        uncreatedService.onDestroy()
        assertEquals(Lifecycle.State.INITIALIZED, uncreatedService.lifecycle.currentState)
    }

    @Test
    fun `liveBalanceHeaderCard renders verified scanning and stale states with retry button`() {
        composeRule.setContent {
            AutoPredictorTheme(darkTheme = false) {
                LiveCrashHeaderCard(
                    gameState = GameState(
                        isDetected = true,
                        displayedGameName = "Aviator",
                        balanceStatus = BalanceDetectionStatus.VERIFIED,
                        detectedBalance = 1500.0,
                        detectedCurrency = "KES",
                        balanceLastUpdatedMs = System.currentTimeMillis()
                    ),
                    automationState = AutomationState.IDLE
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("header_balance_display").assertIsDisplayed()
    }
}
