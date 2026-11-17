package com.example

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.ScreenEarnerDatabase
import com.example.data.database.ScreenEarnerRepository
import com.example.data.engine.AutomationEngine
import com.example.data.engine.AutomationState
import com.example.data.engine.ExtractedHistoryItem
import com.example.data.engine.GameDetection
import com.example.data.engine.RibbonHistoryCollector
import com.example.data.engine.RiskManager
import com.example.data.engine.StrategyEngine
import com.example.data.engine.TargetUiInteractor
import com.example.data.model.BalanceDetectionStatus
import com.example.data.model.CollectionSource
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.FloatingShortcut
import com.example.data.model.NotificationPriorityLevel
import com.example.data.model.NotificationSettings
import com.example.data.model.PatternComparator
import com.example.data.model.PatternCondition
import com.example.data.model.RiskLimits
import com.example.data.model.ShortcutType
import com.example.data.model.SiteButtonState
import com.example.data.model.StakeProgressionMode
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyMode
import com.example.data.model.StrategyNotificationType
import com.example.data.model.StrategyRuleConfig
import com.example.data.model.StrategyRuleId
import com.example.service.AccessibilityController
import com.example.service.AutomationController
import com.example.service.DetectedUiTargets
import com.example.service.GameDetectionState
import com.example.service.SessionStateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EndToEndExecutionPipelineTest {

    private var db: ScreenEarnerDatabase? = null

    class TestableAccessibilityController : AccessibilityController() {
        fun testWriteAndVerifyStake(
            stakeNode: AccessibilityNodeInfo,
            newStake: Double,
            formatted: String,
            rootNode: AccessibilityNodeInfo
        ): Boolean = writeAndVerifyStakeNode(stakeNode, newStake, formatted, rootNode)
    }

    @After
    fun tearDown() {
        AutomationController.cancelTestBackgroundLoops()
        AutomationController.resetTestState()
        AutomationController.engine = null
        AutomationController.repository = null
        db?.close()
        db = null
    }

    private fun setupIsolatedControllerWithFakeInteractor(
        onApplyStake: (Double, StakeProgressionMode) -> Boolean = { _, _ -> true },
        onClickBet: (Float?, Float?) -> Boolean = { _, _ -> true },
        onClickCashOut: (Float?, Float?) -> Boolean = { _, _ -> true }
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

        val fakeInteractor = object : TargetUiInteractor {
            override fun applyStake(
                newStake: Double,
                progressionMode: StakeProgressionMode,
                fallbackX: Float?,
                fallbackY: Float?,
                onComplete: (Boolean) -> Unit
            ) {
                onComplete(onApplyStake(newStake, progressionMode))
            }

            override fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
                onComplete(onClickBet(fallbackX, fallbackY))
            }

            override fun clickCashOut(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
                onComplete(onClickCashOut(fallbackX, fallbackY))
            }
        }

        AutomationController.initialize(repo, context)
        val wiredEngine = AutomationEngine(
            repository = repo,
            strategyEngine = StrategyEngine(),
            riskManager = RiskManager(),
            gameDetection = GameDetection(),
            targetUiInteractor = fakeInteractor
        ).also { eng ->
            eng.testBalanceOverride = null
            eng.onExecutionNotification = AutomationController.engine?.onExecutionNotification
        }
        AutomationController.engine = wiredEngine
        return Triple(context, repo, wiredEngine)
    }

    private fun buildDetection(
        phase: String,
        buttonState: SiteButtonState,
        liveMult: Double? = null,
        ribbon: List<Double> = listOf(1.25, 1.35, 3.10),
        balance: Double? = 500.0,
        currency: String = "TZS",
        roundId: String = "R-100",
        nowMs: Long = 1_700_000_000_000L
    ): GameDetectionState {
        val items = ribbon.mapIndexed { idx, value ->
            ExtractedHistoryItem(
                multiplier = value,
                orderIndex = idx,
                source = CollectionSource.VISIBLE_RIBBON,
                displayText = "${RibbonHistoryCollector.formatMultKey(value)}x"
            )
        }
        return GameDetectionState(
            isGameDetected = true,
            gameId = "aviator",
            displayedGameName = "BetPawa Aviator",
            platformName = "BetPawa Aviator",
            isReady = true,
            gamePhase = phase,
            detectedMultiplier = liveMult,
            liveDetectionLost = false,
            detectionConfidence = 0.95f,
            detectedRoundId = roundId,
            roundId = roundId,
            foregroundPackage = "com.android.chrome",
            uiTargets = DetectedUiTargets(
                detectedMultiplier = liveMult,
                ribbonMultipliers = ribbon,
                ribbonItems = items,
                gamePhase = phase,
                siteButtonState = buttonState,
                isFlewAwayDetected = phase == "CRASHED",
                isCancelBtnDetected = buttonState == SiteButtonState.BET_QUEUED,
                detectedUrl = "https://www.betpawa.co.tz/casino/game/aviator",
                detectedCurrentRoundId = roundId,
                betButtonBounds = Rect(400, 1100, 800, 1280),
                cashOutButtonBounds = Rect(400, 1100, 800, 1280),
                detectedBalance = balance,
                detectedCurrency = currency
            ),
            updatedAtMs = nowMs
        )
    }

    @Test
    fun `manifest declares ScreenCaptureProjectionService and FOREGROUND_SERVICE_MEDIA_PROJECTION`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS
        )

        val permissions = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertTrue(
            "FOREGROUND_SERVICE_MEDIA_PROJECTION permission must be declared",
            permissions.contains(android.Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION)
        )

        val services = pkgInfo.services?.toList().orEmpty()
        val projectionService = services.firstOrNull {
            it.name == "com.example.service.ScreenCaptureProjectionService"
        }
        assertNotNull("ScreenCaptureProjectionService must be declared in AndroidManifest.xml", projectionService)
        assertTrue(
            (projectionService!!.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) != 0
        )

        val a11yService = services.firstOrNull {
            it.name == "com.example.service.ScreenEarnerAccessibilityService"
        }
        assertNotNull("ScreenEarnerAccessibilityService must be declared in AndroidManifest.xml", a11yService)
    }

    @Test
    fun `writeAndVerifyStakeNode strictly rejects silent failures and passes only when readback matches`() {
        val controller = Robolectric.buildService(TestableAccessibilityController::class.java).create().get()
        val rootNode = AccessibilityNodeInfo.obtain()
        val stakeNode = AccessibilityNodeInfo.obtain()
        stakeNode.isEditable = true
        stakeNode.text = "1.00"

        // Case 1: Site rejects text replacement -> read-back remains "1.00" instead of "20.00"
        Shadows.shadowOf(stakeNode).setOnPerformActionListener { _, _ -> false }
        assertFalse(
            "Must return false when ACTION_SET_TEXT & ACTION_PASTE do not update readback",
            controller.testWriteAndVerifyStake(stakeNode, 20.0, "20.00", rootNode)
        )

        // Case 2: Android reports true for ACTION_SET_TEXT, but React/WebView ignores it and keeps "1.00"
        Shadows.shadowOf(stakeNode).setOnPerformActionListener { action, _ ->
            action == AccessibilityNodeInfo.ACTION_SET_TEXT
        }
        assertFalse(
            "Must NOT report success when ACTION_SET_TEXT returns true but text stays unchanged",
            controller.testWriteAndVerifyStake(stakeNode, 20.0, "20.00", rootNode)
        )

        // Case 3: Site actually updates editable text to "20.00"
        Shadows.shadowOf(stakeNode).setOnPerformActionListener { action, args ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                val updated = args?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                stakeNode.text = updated
                true
            } else {
                false
            }
        }
        assertTrue(
            "Must return true once read-back verifies exact new stake",
            controller.testWriteAndVerifyStake(stakeNode, 20.0, "20.00", rootNode)
        )
    }

    @Test
    fun `balance detected as zero reports Insufficient balance and emits non-spammy notification`() = runBlocking {
        val appliedStakes = mutableListOf<Double>()
        var betClicks = 0
        val (_, repo, engine) = setupIsolatedControllerWithFakeInteractor(
            onApplyStake = { stake, _ -> appliedStakes.add(stake); true },
            onClickBet = { _, _ -> betClicks++; true }
        )
        val emittedNotifications = mutableListOf<Pair<StrategyNotificationType, String>>()
        engine.onExecutionNotification = { type, _, msg, _, _ ->
            emittedNotifications.add(type to msg)
        }

        val config = StrategyConfig(
            isStrategy1Enabled = false,
            isStrategy2Enabled = false,
            isHighMultiplierLossRuleEnabled = false,
            baseStake = 10.0,
            targetMultiplier = 2.00,
            isAutoBetEnabled = true,
            isAutoCashOutEnabled = true
        ).withUpdatedRule(
            StrategyRuleConfig(ruleId = StrategyRuleId.AFTER_2_LOSSES, isEnabled = true, requiredLosses = 2)
        )
        repo.saveStrategyConfig(config)
        repo.saveRiskLimits(RiskLimits())
        repo.saveNotificationSettings(NotificationSettings(monitoringErrorsEnabled = true, standardAlertEnabled = true))
        delay(50L)

        AutomationController.startMonitoringSession(isAccessibilityConnected = true, nowMs = 1_700_000_000_000L)
        engine.updateCalibratedShortcuts(
            listOf(FloatingShortcut(id = 1, label = "BET", type = ShortcutType.BET, targetPlatform = "BetPawa", targetX = 600f, targetY = 1190f, isCalibrated = true))
        )

        // Baseline capture + two crashes < 2.0x to activate AFTER_2_LOSSES
        AutomationController.processDetectionEvent(
            buildDetection("BETTING_OPEN", SiteButtonState.IDLE_READY_TO_BET, ribbon = listOf(3.00, 4.00, 5.00), balance = 0.0, roundId = "R-0", nowMs = 1_700_000_001_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_001_000L
        )
        AutomationController.processDetectionEvent(
            buildDetection("CRASHED", SiteButtonState.CRASHED, liveMult = 1.15, ribbon = listOf(1.15, 3.00, 4.00, 5.00), balance = 0.0, roundId = "R-1", nowMs = 1_700_000_005_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_005_000L
        )
        AutomationController.processDetectionEvent(
            buildDetection("CRASHED", SiteButtonState.CRASHED, liveMult = 1.40, ribbon = listOf(1.40, 1.15, 3.00, 4.00), balance = 0.0, roundId = "R-2", nowMs = 1_700_000_010_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_010_000L
        )

        assertEquals(BalanceDetectionStatus.VERIFIED, engine.gameDetection.balanceState.value.status)
        assertEquals(0.0, engine.resolveEffectiveBalance(), 0.001)
        assertTrue(engine.strategyEngine.getState().isBettingSessionActive)

        // Direct RiskManager + resolveEffectiveBalance verification for DETECTED status as well
        val detectedCheck = engine.riskManager.validateBetExecution(
            proposedStake = 10.0,
            currentSessionLoss = 0.0,
            currentRounds = 0,
            currentProgressionLevel = 1,
            currentBalance = 0.0,
            limits = RiskLimits(),
            currentSessionProfit = 0.0,
            balanceStatus = BalanceDetectionStatus.DETECTED
        )
        assertFalse(detectedCheck.isAllowed)
        assertTrue(
            "DETECTED balance 0.0 must report Insufficient balance, got: ${detectedCheck.violationReason}",
            detectedCheck.violationReason.orEmpty().contains("Insufficient balance", ignoreCase = true)
        )

        // Now BETTING_OPEN arrives with balance == 0.0 -> must NOT say "balance is unavailable", must say "Insufficient balance"
        AutomationController.processDetectionEvent(
            buildDetection("BETTING_OPEN", SiteButtonState.IDLE_READY_TO_BET, ribbon = listOf(1.40, 1.15, 3.00, 4.00), balance = 0.0, roundId = "R-3", nowMs = 1_700_000_012_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_012_000L
        )

        assertEquals(0, betClicks)
        assertTrue(appliedStakes.isEmpty())
        assertEquals(AutomationState.PAUSED, engine.automationState.value)
        val violation = engine.lastRiskViolation.value.orEmpty()
        assertTrue("Expected Insufficient balance message, got: $violation", violation.contains("Insufficient balance", ignoreCase = true))
        assertEquals(1, emittedNotifications.count { it.first == StrategyNotificationType.MONITORING_ERROR })
    }

    @Test
    fun `end to end detection triggers bet, cashout even with stale autoCashOut flag, and settles loss double and win reset`() = runBlocking {
        val appliedStakes = mutableListOf<Pair<Double, StakeProgressionMode>>()
        var betClicks = 0
        var cashOutClicks = 0
        val (_, repo, engine) = setupIsolatedControllerWithFakeInteractor(
            onApplyStake = { stake, mode ->
                appliedStakes.add(stake to mode)
                true
            },
            onClickBet = { _, _ -> betClicks++; true },
            onClickCashOut = { _, _ -> cashOutClicks++; true }
        )
        val executionNotices = mutableListOf<Pair<StrategyNotificationType, String>>()
        engine.onExecutionNotification = { type, title, _, _, _ ->
            executionNotices.add(type to title)
        }

        // Even if persisted DB had isAutoCashOutEnabled = false while isAutoBetEnabled = true, auto cash-out runs at targetMultiplier
        val config = StrategyConfig(
            isStrategy1Enabled = false,
            isStrategy2Enabled = false,
            isHighMultiplierLossRuleEnabled = false,
            baseStake = 5.0,
            targetMultiplier = 2.00,
            isAutoBetEnabled = true,
            isAutoCashOutEnabled = false
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
        repo.saveNotificationSettings(NotificationSettings(standardAlertEnabled = true, monitoringErrorsEnabled = true))
        delay(60L)

        AutomationController.startMonitoringSession(isAccessibilityConnected = true, nowMs = 1_700_000_000_000L)
        engine.updateCalibratedShortcuts(
            listOf(
                FloatingShortcut(id = 1, label = "BP BET", type = ShortcutType.BET, targetPlatform = "BetPawa", targetX = 600f, targetY = 1190f, isCalibrated = true),
                FloatingShortcut(id = 2, label = "BP CASHOUT", type = ShortcutType.CASH_OUT, targetPlatform = "BetPawa", targetX = 600f, targetY = 1190f, isCalibrated = true)
            )
        )

        // 1. Baseline capture -> 2 consecutive low crashes -> Strategy activates
        AutomationController.processDetectionEvent(
            buildDetection("BETTING_OPEN", SiteButtonState.IDLE_READY_TO_BET, ribbon = listOf(2.50, 3.10, 4.20), balance = 1000.0, roundId = "R-10", nowMs = 1_700_000_001_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_001_000L
        )
        AutomationController.processDetectionEvent(
            buildDetection("CRASHED", SiteButtonState.CRASHED, liveMult = 1.20, ribbon = listOf(1.20, 2.50, 3.10), balance = 1000.0, roundId = "R-11", nowMs = 1_700_000_004_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_004_000L
        )
        AutomationController.processDetectionEvent(
            buildDetection("CRASHED", SiteButtonState.CRASHED, liveMult = 1.45, ribbon = listOf(1.45, 1.20, 2.50), balance = 1000.0, roundId = "R-12", nowMs = 1_700_000_008_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_008_000L
        )
        assertTrue(engine.strategyEngine.getState().isBettingSessionActive)
        assertEquals(5.0, engine.strategyEngine.getState().currentCalculatedStake, 0.001)

        // 2. Round R-13 BETTING_OPEN -> applies stake 5.0 and clicks BET; repeats on BET_QUEUED do not double-bet
        AutomationController.processDetectionEvent(
            buildDetection("BETTING_OPEN", SiteButtonState.IDLE_READY_TO_BET, ribbon = listOf(1.45, 1.20, 2.50), balance = 1000.0, roundId = "R-13", nowMs = 1_700_000_010_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_010_000L
        )
        AutomationController.processDetectionEvent(
            buildDetection("BETTING_OPEN", SiteButtonState.BET_QUEUED, ribbon = listOf(1.45, 1.20, 2.50), balance = 995.0, roundId = "R-13", nowMs = 1_700_000_011_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_011_000L
        )
        assertEquals(1, betClicks)
        assertEquals(5.0, appliedStakes.last().first, 0.001)

        // 3. Round R-13 crashes early at 1.35x -> settles LOSS, doubles next stake to 10.0
        AutomationController.processDetectionEvent(
            buildDetection("IN_FLIGHT", SiteButtonState.CASH_OUT_AVAILABLE, liveMult = 1.20, ribbon = listOf(1.45, 1.20, 2.50), balance = 995.0, roundId = "R-13", nowMs = 1_700_000_012_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_012_000L
        )
        assertEquals(0, cashOutClicks)
        AutomationController.processDetectionEvent(
            buildDetection("CRASHED", SiteButtonState.CRASHED, liveMult = 1.35, ribbon = listOf(1.35, 1.45, 1.20), balance = 995.0, roundId = "R-13", nowMs = 1_700_000_015_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_015_000L
        )
        assertEquals(1, engine.sessionStats.value.lossCount)
        assertEquals(-5.0, engine.sessionStats.value.totalProfit, 0.001)
        assertEquals(10.0, engine.strategyEngine.getState().currentCalculatedStake, 0.001)

        // 4. Round R-14 BETTING_OPEN -> applies doubled stake 10.0 & clicks BET
        AutomationController.processDetectionEvent(
            buildDetection("BETTING_OPEN", SiteButtonState.IDLE_READY_TO_BET, ribbon = listOf(1.35, 1.45, 1.20), balance = 995.0, roundId = "R-14", nowMs = 1_700_000_017_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_017_000L
        )
        assertEquals(2, betClicks)
        assertEquals(10.0, appliedStakes.last().first, 0.001)

        // 5. Round R-14 IN_FLIGHT hits 2.15x -> clicks CASH OUT once
        AutomationController.processDetectionEvent(
            buildDetection("IN_FLIGHT", SiteButtonState.CASH_OUT_AVAILABLE, liveMult = 2.15, ribbon = listOf(1.35, 1.45, 1.20), balance = 985.0, roundId = "R-14", nowMs = 1_700_000_020_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_020_000L
        )
        assertEquals(1, cashOutClicks)

        // 6. Round R-14 crashes at 2.80x -> settles WIN, profit = -5 + 10 = +5.0, stake resets to base 5.0
        AutomationController.processDetectionEvent(
            buildDetection("CRASHED", SiteButtonState.CRASHED, liveMult = 2.80, ribbon = listOf(2.80, 1.35, 1.45), balance = 1005.0, roundId = "R-14", nowMs = 1_700_000_023_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_023_000L
        )
        delay(60L)
        assertEquals(1, engine.sessionStats.value.winCount)
        assertEquals(1, engine.sessionStats.value.lossCount)
        assertEquals(5.0, engine.sessionStats.value.totalProfit, 0.001)
        assertEquals(5.0, engine.strategyEngine.getState().currentCalculatedStake, 0.001)
        assertFalse(engine.strategyEngine.getState().isBettingSessionActive)

        val logs = repo.sessionLogs.first()
        assertEquals(2, logs.size)
        assertTrue(executionNotices.any { it.first == StrategyNotificationType.AUTOMATION_ACTION && it.second.contains("Bet Placed") })
        assertTrue(executionNotices.any { it.first == StrategyNotificationType.AUTOMATION_ACTION && it.second.contains("Cash Out Confirmed") })
        // Routine balance/amount reads must NOT generate notification noise
        assertTrue(executionNotices.none { it.second.contains("Balance", ignoreCase = true) })
    }

    @Test
    fun `custom pattern strategy match unlocks active betting session when Auto Bet is enabled`() = runBlocking {
        var betClicks = 0
        val (_, repo, engine) = setupIsolatedControllerWithFakeInteractor(
            onApplyStake = { _, _ -> true },
            onClickBet = { _, _ -> betClicks++; true }
        )

        val customStrategy = CustomPatternStrategy(
            id = 1L,
            name = "2 Low Custom Alert",
            strategyMode = StrategyMode.STRATEGY_A_CONSECUTIVE_LOWS,
            isEnabled = true,
            targetGameId = "aviator",
            requiredOccurrencesPerHour = 1,
            analysisWindowMinutes = 60,
            conditionsJson = CustomPatternStrategy.serializeConditions(
                listOf(PatternCondition(comparator = PatternComparator.LESS_THAN, threshold = 2.0, consecutiveRounds = 2))
            )
        )
        repo.insertCustomStrategy(customStrategy)

        val config = StrategyConfig(
            isStrategy1Enabled = false,
            isStrategy2Enabled = false,
            isHighMultiplierLossRuleEnabled = false,
            baseStake = 15.0,
            targetMultiplier = 2.00,
            isAutoBetEnabled = true,
            isAutoCashOutEnabled = true
        ).withUpdatedRuleConfigs(
            StrategyRuleId.entries.map { StrategyRuleConfig(ruleId = it, isEnabled = false) }
        )
        repo.saveStrategyConfig(config)
        repo.saveRiskLimits(RiskLimits())
        delay(60L)

        val baseNow = System.currentTimeMillis()
        AutomationController.startMonitoringSession(isAccessibilityConnected = true, nowMs = baseNow)
        engine.updateCalibratedShortcuts(
            listOf(FloatingShortcut(id = 1, label = "BET", type = ShortcutType.BET, targetPlatform = "BetPawa", targetX = 600f, targetY = 1190f, isCalibrated = true))
        )

        // Baseline + 2 consecutive low crashes -> CustomPatternStrategy matches
        AutomationController.processDetectionEvent(
            buildDetection("BETTING_OPEN", SiteButtonState.IDLE_READY_TO_BET, ribbon = listOf(4.20, 5.10), balance = 500.0, roundId = "R-20", nowMs = baseNow + 1_000L),
            isAccessibilityConnected = true,
            nowMs = baseNow + 1_000L
        )
        AutomationController.processDetectionEvent(
            buildDetection("CRASHED", SiteButtonState.CRASHED, liveMult = 1.25, ribbon = listOf(1.25, 4.20, 5.10), balance = 500.0, roundId = "R-21", nowMs = baseNow + 4_000L),
            isAccessibilityConnected = true,
            nowMs = baseNow + 4_000L
        )
        AutomationController.processDetectionEvent(
            buildDetection("CRASHED", SiteButtonState.CRASHED, liveMult = 1.55, ribbon = listOf(1.55, 1.25, 4.20), balance = 500.0, roundId = "R-22", nowMs = baseNow + 8_000L),
            isAccessibilityConnected = true,
            nowMs = baseNow + 8_000L
        )
        // Wait briefly for evaluatePatternStrategies coroutine launched inside processDetectionEvent
        delay(200L)

        assertTrue(
            "CustomPatternStrategy match should activate StrategyEngine betting session",
            engine.strategyEngine.getState().isBettingSessionActive
        )

        AutomationController.processDetectionEvent(
            buildDetection("BETTING_OPEN", SiteButtonState.IDLE_READY_TO_BET, ribbon = listOf(1.55, 1.25, 4.20), balance = 500.0, roundId = "R-23", nowMs = baseNow + 10_000L),
            isAccessibilityConnected = true,
            nowMs = baseNow + 10_000L
        )
        assertEquals(1, betClicks)
    }

    @Test
    fun `manual bet with auto cashout enabled does not fire below 1_20x on transient frames and cashes out at 2_00x`() = runBlocking {
        var cashOutClicks = 0
        val requestedGestures = mutableListOf<String>()
        val (_, repo, engine) = setupIsolatedControllerWithFakeInteractor(
            onClickCashOut = { _, _ -> cashOutClicks++; true }
        )
        engine.onGestureDispatchRequested = { _, _, label ->
            requestedGestures.add(label)
        }
        val notices = mutableListOf<Pair<StrategyNotificationType, String>>()
        engine.onExecutionNotification = { type, title, _, _, _ ->
            notices.add(type to title)
        }

        val config = StrategyConfig(
            baseStake = 10.0,
            targetMultiplier = 2.00,
            isAutoBetEnabled = false,
            isAutoCashOutEnabled = true
        )
        repo.saveStrategyConfig(config)
        repo.saveRiskLimits(RiskLimits())
        delay(60L)

        AutomationController.startMonitoringSession(isAccessibilityConnected = true, nowMs = 1_700_000_000_000L)
        engine.updateCalibratedShortcuts(
            listOf(FloatingShortcut(id = 2, label = "CASH OUT HAND", type = ShortcutType.CASH_OUT, targetPlatform = "BetPawa", targetX = 720f, targetY = 1200f, targetMultiplier = 2.00, isCalibrated = true))
        )

        // Prior round crashed at 3.50x so currentMultiplier in state would have been 3.50x if stale fallback existed;
        // IN_FLIGHT with null liveMult + 1.15x must NEVER trigger cash-out at < 1.20x when target is 2.00x.
        AutomationController.processDetectionEvent(
            buildDetection("CRASHED", SiteButtonState.CRASHED, liveMult = 3.50, ribbon = listOf(3.50, 1.80), balance = 1000.0, roundId = "R-40", nowMs = 1_700_000_001_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_001_000L
        )
        AutomationController.processDetectionEvent(
            buildDetection("IN_FLIGHT", SiteButtonState.IDLE_READY_TO_BET, liveMult = null, ribbon = listOf(3.50, 1.80), balance = 990.0, roundId = "R-41", nowMs = 1_700_000_003_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_003_000L
        )
        AutomationController.processDetectionEvent(
            buildDetection("IN_FLIGHT", SiteButtonState.CASH_OUT_AVAILABLE, liveMult = 1.15, ribbon = listOf(3.50, 1.80), balance = 990.0, roundId = "R-41", nowMs = 1_700_000_004_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_004_000L
        )
        assertEquals(0, cashOutClicks)

        // Reaches 2.02x with manual bet's CASH_OUT_AVAILABLE visible -> cashes out and notifies
        AutomationController.processDetectionEvent(
            buildDetection("IN_FLIGHT", SiteButtonState.CASH_OUT_AVAILABLE, liveMult = 2.02, ribbon = listOf(3.50, 1.80), balance = 990.0, roundId = "R-41", nowMs = 1_700_000_007_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_007_000L
        )
        assertEquals(1, cashOutClicks)
        assertTrue(requestedGestures.contains("CASH OUT"))
        assertTrue(notices.any { it.second.contains("Waiting for Cash Out", ignoreCase = true) })
        assertTrue(notices.any { it.second.contains("Cashing Out", ignoreCase = true) })
        assertTrue(notices.any { it.second.contains("Cash Out Confirmed", ignoreCase = true) })
    }

    @Test
    fun `emits full action lifecycle notifications from waiting to bet through round settlement`() = runBlocking {
        val (_, repo, engine) = setupIsolatedControllerWithFakeInteractor(
            onApplyStake = { _, _ -> true },
            onClickBet = { _, _ -> true },
            onClickCashOut = { _, _ -> true }
        )
        val actionTitles = mutableListOf<String>()
        engine.onExecutionNotification = { type, title, _, _, _ ->
            if (type == StrategyNotificationType.AUTOMATION_ACTION) {
                actionTitles.add(title)
            }
        }

        val config = StrategyConfig(
            isStrategy1Enabled = false,
            isStrategy2Enabled = false,
            isHighMultiplierLossRuleEnabled = false,
            baseStake = 5.0,
            targetMultiplier = 2.00,
            isAutoBetEnabled = true,
            isAutoCashOutEnabled = true
        ).withUpdatedRule(
            StrategyRuleConfig(
                ruleId = StrategyRuleId.AFTER_3_LOSSES,
                isEnabled = true,
                progressionMode = StakeProgressionMode.DOUBLE_AMOUNT,
                requiredLosses = 3,
                targetWinsToFetch = 1
            )
        )
        repo.saveStrategyConfig(config)
        repo.saveRiskLimits(RiskLimits())
        delay(60L)

        AutomationController.startMonitoringSession(isAccessibilityConnected = true, nowMs = 1_700_000_000_000L)
        engine.updateCalibratedShortcuts(
            listOf(
                FloatingShortcut(id = 1, label = "BET HAND", type = ShortcutType.BET, targetPlatform = "BetPawa", targetX = 500f, targetY = 1200f, isCalibrated = true),
                FloatingShortcut(id = 2, label = "CASH OUT HAND", type = ShortcutType.CASH_OUT, targetPlatform = "BetPawa", targetX = 720f, targetY = 1200f, targetMultiplier = 2.00, isCalibrated = true)
            )
        )

        // Baseline + waiting to bet before 3 consecutive lows
        AutomationController.processDetectionEvent(
            buildDetection("BETTING_OPEN", SiteButtonState.IDLE_READY_TO_BET, ribbon = listOf(3.10, 4.20), balance = 1000.0, roundId = "R-50", nowMs = 1_700_000_001_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_001_000L
        )
        val ribbons = listOf(
            listOf(1.10, 3.10, 4.20),
            listOf(1.25, 1.10, 3.10),
            listOf(1.40, 1.25, 1.10)
        )
        listOf(1.10 to "R-51", 1.25 to "R-52", 1.40 to "R-53").forEachIndexed { idx, (mult, rid) ->
            AutomationController.processDetectionEvent(
                buildDetection("CRASHED", SiteButtonState.CRASHED, liveMult = mult, ribbon = ribbons[idx], balance = 1000.0, roundId = rid, nowMs = 1_700_000_003_000L + idx * 3_000L),
                isAccessibilityConnected = true,
                nowMs = 1_700_000_003_000L + idx * 3_000L
            )
        }
        assertTrue(engine.strategyEngine.getState().isBettingSessionActive)

        // Next round: BETTING_OPEN -> IN_FLIGHT -> 2.10x -> CRASHED
        AutomationController.processDetectionEvent(
            buildDetection("BETTING_OPEN", SiteButtonState.IDLE_READY_TO_BET, ribbon = listOf(1.40, 1.25, 1.10), balance = 1000.0, roundId = "R-54", nowMs = 1_700_000_015_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_015_000L
        )
        // Transient IN_FLIGHT frame with IDLE_READY_TO_BET must NOT prematurely settle before CASH_OUT_AVAILABLE appears
        AutomationController.processDetectionEvent(
            buildDetection("IN_FLIGHT", SiteButtonState.IDLE_READY_TO_BET, liveMult = 1.05, ribbon = listOf(1.40, 1.25, 1.10), balance = 995.0, roundId = "R-54", nowMs = 1_700_000_016_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_016_000L
        )
        AutomationController.processDetectionEvent(
            buildDetection("IN_FLIGHT", SiteButtonState.CASH_OUT_AVAILABLE, liveMult = 2.10, ribbon = listOf(1.40, 1.25, 1.10), balance = 995.0, roundId = "R-54", nowMs = 1_700_000_019_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_019_000L
        )
        AutomationController.processDetectionEvent(
            buildDetection("CRASHED", SiteButtonState.CRASHED, liveMult = 2.40, ribbon = listOf(2.40, 1.40, 1.25), balance = 1005.0, roundId = "R-54", nowMs = 1_700_000_021_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_021_000L
        )

        assertTrue(actionTitles.any { it.contains("Waiting to Bet", ignoreCase = true) })
        assertTrue(actionTitles.any { it.contains("Placing Bet", ignoreCase = true) })
        assertTrue(actionTitles.any { it.contains("Bet Placed", ignoreCase = true) })
        assertTrue(actionTitles.any { it.contains("Waiting for Cash Out", ignoreCase = true) })
        assertTrue(actionTitles.any { it.contains("Cashing Out", ignoreCase = true) })
        assertTrue(actionTitles.any { it.contains("Cash Out Confirmed", ignoreCase = true) })
        assertTrue(actionTitles.any { it.contains("Round Won", ignoreCase = true) })
        assertTrue(actionTitles.none { it.contains("Balance", ignoreCase = true) })
    }
}
