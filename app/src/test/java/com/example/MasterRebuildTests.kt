package com.example

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.ClearHistoryScope
import com.example.data.database.ScreenEarnerDatabase
import com.example.data.database.ScreenEarnerRepository
import com.example.data.engine.AutomationState
import com.example.data.engine.ConfirmedCrashEmission
import com.example.data.engine.GameAdapterRegistry
import com.example.data.engine.HourlyAnalysisEngine
import com.example.data.engine.LiveCrashPipeline
import com.example.data.engine.PatternAlertEvent
import com.example.data.engine.PatternDetectionEngine
import com.example.data.engine.RibbonHistoryCollector
import com.example.data.model.AlarmProfileCategory
import com.example.data.model.AlarmProfileEntity
import com.example.data.model.CalendarHourUtils
import com.example.data.model.CollectionSource
import com.example.data.model.ColorMatchDirection
import com.example.data.model.CrashColorCategory
import com.example.data.model.CrashRecord
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.GameState
import com.example.data.model.MonitoringPipelineStatus
import com.example.data.model.MonitoringSessionEntity
import com.example.data.model.MonitoringStatus
import com.example.data.model.MultiplierRecord
import com.example.data.model.MultiplierRuleSubtype
import com.example.data.model.NotificationDeliveryStatus
import com.example.data.model.NotificationPriorityLevel
import com.example.data.model.NotificationSettings
import com.example.data.model.PatternComparator
import com.example.data.model.PatternCondition
import com.example.data.model.RoundLifecycleState
import com.example.data.model.SessionStats
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyGroup
import com.example.data.model.StrategyMode
import com.example.data.model.StrategyNotificationType
import com.example.data.model.StrategyRangeValidator
import com.example.service.AlarmAudioController
import com.example.service.AutomationController
import com.example.service.DetectedUiTargets
import com.example.service.FloatingSessionStatsHudComposable
import com.example.service.GameDetectionState
import com.example.service.PersistedRibbonBaselineItem
import com.example.service.ScreenEarnerOverlayService
import com.example.service.SessionStateManager
import com.example.service.StrategyNotificationManager
import com.example.service.SystemNotificationDispatcher
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LogsAnalyticsScreen
import com.example.ui.screens.SettingsAppearanceScreen
import com.example.ui.screens.StrategyScreen
import com.example.ui.theme.AutoPredictorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MasterRebuildTests {

    @get:Rule
    val composeRule = createComposeRule()

    private var db: ScreenEarnerDatabase? = null

    @After
    fun tearDown() {
        AlarmAudioController.stopAlarm()
        AlarmAudioController.clearSnooze()
        AutomationController.cancelTestBackgroundLoops()
        AutomationController.resetTestState()
        AutomationController.engine = null
        AutomationController.repository = null
        db?.close()
        db = null
    }

    private fun setupDbAndRepo(): Pair<Context, ScreenEarnerRepository> {
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
        return context to repo
    }

    private fun crashRow(
        gameId: String = "aviator",
        gameName: String = "Aviator",
        sessionId: String = "sess_1",
        mult: Double,
        ts: Long,
        roundId: String? = null,
        hex: String? = null,
        category: CrashColorCategory? = null,
        dedupSuffix: String = "${ts}_$mult"
    ): CrashRecord {
        val cat = category ?: when {
            mult < 2.0 -> CrashColorCategory.Blue
            mult < 10.0 -> CrashColorCategory.Purple
            else -> CrashColorCategory.Pink
        }
        return CrashRecord(
            sessionId = sessionId,
            gameId = gameId,
            gameName = gameName,
            multiplier = mult,
            detectedColorHex = hex ?: cat.defaultHex,
            colorCategory = cat,
            isPixelColorVerified = hex != null,
            crashTimestamp = ts,
            hourStartTimestamp = CalendarHourUtils.hourStartFor(ts),
            sourcePackage = "com.android.chrome",
            roundIdentifier = roundId,
            dedupKey = "${sessionId}_${gameId}_$dedupSuffix"
        )
    }

    private fun detectionFrame(
        phase: String,
        liveMult: Double? = null,
        ribbon: List<Double> = emptyList(),
        roundId: String? = null,
        gameId: String = "aviator",
        gameName: String = "Aviator",
        platformName: String = "Chrome Web",
        expanded: Boolean = false,
        windowAccessStatus: MonitoringStatus = MonitoringStatus.MONITORING,
        liveLost: Boolean = false,
        nowMs: Long = 1_700_000_000_000L
    ): GameDetectionState {
        val items = ribbon.mapIndexed { idx, v ->
            com.example.data.engine.ExtractedHistoryItem(
                multiplier = v,
                orderIndex = idx,
                source = CollectionSource.VISIBLE_RIBBON,
                displayText = "${RibbonHistoryCollector.formatMultKey(v)}x"
            )
        }
        return GameDetectionState(
            isGameDetected = !liveLost && windowAccessStatus == MonitoringStatus.MONITORING,
            gameId = gameId,
            displayedGameName = gameName,
            platformName = platformName,
            isReady = !liveLost,
            gamePhase = phase,
            detectedMultiplier = liveMult,
            liveDetectionLost = liveLost,
            detectionConfidence = if (liveLost) 0f else 0.9f,
            detectedRoundId = roundId,
            roundId = roundId,
            foregroundPackage = "com.android.chrome",
            uiTargets = DetectedUiTargets(
                detectedMultiplier = liveMult,
                ribbonMultipliers = ribbon,
                ribbonItems = items,
                historyPanelExpanded = expanded,
                detectedCurrentRoundId = roundId,
                gamePhase = phase,
                isFlewAwayDetected = phase == "CRASHED"
            ),
            updatedAtMs = nowMs,
            windowAccessStatus = windowAccessStatus,
            gameWindowAccessible = windowAccessStatus == MonitoringStatus.MONITORING && !liveLost,
            gameWindowSuspended = liveLost || windowAccessStatus == MonitoringStatus.GAME_SUSPENDED
        )
    }

    @Test
    fun test01_confirmedCrashOnly_ignoresInFlightMultiplier() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val emissions = mutableListOf<ConfirmedCrashEmission>()
        try {
            val pipeline = LiveCrashPipeline(scope, debounceWindowMs = 40L) {
                emissions.add(it)
            }
            pipeline.configureSession("sess_t1")
            pipeline.onDetectionFrame(detectionFrame("BETTING_OPEN", null, roundId = "R-1"), MonitoringStatus.MONITORING)
            pipeline.onDetectionFrame(detectionFrame("IN_FLIGHT", 1.15, roundId = "R-1"), MonitoringStatus.MONITORING)
            pipeline.onDetectionFrame(detectionFrame("IN_FLIGHT", 3.42, roundId = "R-1"), MonitoringStatus.MONITORING)
            pipeline.onDetectionFrame(detectionFrame("IN_FLIGHT", 5.88, roundId = "R-1"), MonitoringStatus.MONITORING)
            delay(80L)
            assertEquals("Live rising multipliers must NEVER be recorded", 0, emissions.size)
            assertEquals(RoundLifecycleState.MULTIPLIER_RISING, pipeline.lifecycleState.value)

            pipeline.onDetectionFrame(detectionFrame("CRASHED", 6.12, roundId = "R-1"), MonitoringStatus.MONITORING)
            delay(100L)
            assertEquals(1, emissions.size)
            assertEquals(6.12, emissions.first().multiplier, 0.001)
            assertTrue(emissions.first().crashRecord.isConfirmed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun test02_baselineFirst_zeroHistoricalImportOnStartup() = runBlocking {
        val (_, repo) = setupDbAndRepo()
        val start = AutomationController.startMonitoringSession(isAccessibilityConnected = true, nowMs = 1_700_000_000_000L)
        assertTrue(start.started)

        AutomationController.processDetectionEvent(
            detection = detectionFrame("BETTING_OPEN", ribbon = listOf(4.20, 1.35, 11.10, 2.05), roundId = "R-10", nowMs = 1_700_000_001_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_001_000L
        )
        assertEquals(0, AutomationController.activeSessionCrashRecords.value.size)
        assertEquals(0, repo.getCrashRecordsForSessionOnce(AutomationController.activeSessionId.value).size)
        assertEquals(
            MonitoringPipelineStatus.BASELINE_CAPTURED_WAITING_FOR_NEW_CRASH,
            AutomationController.engine!!.gameDetection.gameState.value.pipelineStatus
        )
    }

    @Test
    fun test03_consecutiveEqualMultipliersAcrossDistinctRoundsRecorded() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val emissions = mutableListOf<ConfirmedCrashEmission>()
        try {
            val pipeline = LiveCrashPipeline(scope, debounceWindowMs = 20L) {
                emissions.add(it)
            }
            pipeline.configureSession("sess_t3")

            // Round 1 -> 2.10x
            pipeline.onDetectionFrame(detectionFrame("IN_FLIGHT", 2.00, roundId = "R-101"), MonitoringStatus.MONITORING)
            pipeline.onDetectionFrame(detectionFrame("CRASHED", 2.10, roundId = "R-101"), MonitoringStatus.MONITORING)
            pipeline.flushPendingNow()

            // Round 2 -> same 2.10x multiplier, distinct round
            pipeline.onDetectionFrame(detectionFrame("BETTING_OPEN", null, roundId = "R-102"), MonitoringStatus.MONITORING)
            pipeline.onDetectionFrame(detectionFrame("IN_FLIGHT", 1.80, roundId = "R-102"), MonitoringStatus.MONITORING)
            pipeline.onDetectionFrame(detectionFrame("CRASHED", 2.10, roundId = "R-102"), MonitoringStatus.MONITORING)
            pipeline.flushPendingNow()

            assertEquals(2, emissions.size)
            assertEquals(2.10, emissions[0].multiplier, 0.001)
            assertEquals(2.10, emissions[1].multiplier, 0.001)
            assertNotEquals(emissions[0].crashRecord.dedupKey, emissions[1].crashRecord.dedupKey)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun test04_ribbonOpenRefreshReposition_zeroDuplicatesOrHistoricalImport() {
        val baseline = listOf(
            PersistedRibbonBaselineItem(2.45, "2.45x", "R-5", null, 0, 0, 0, 10, 10),
            PersistedRibbonBaselineItem(1.30, "1.30x", "R-4", null, 1, 10, 0, 20, 10),
            PersistedRibbonBaselineItem(8.90, "8.90x", "R-3", null, 2, 20, 0, 30, 10)
        )

        // Manual open / expand adding older tail items
        val expanded = listOf(2.45, 1.30, 8.90, 1.15, 4.50).mapIndexed { i, v ->
            com.example.data.engine.ExtractedHistoryItem(multiplier = v, roundId = "R-${5 - i}", orderIndex = i)
        }
        val expandResult = RibbonHistoryCollector.alignAuthoritativeLiveSnapshot(
            previousBaseline = baseline,
            currentItems = expanded,
            previousExpanded = false,
            currentExpanded = true,
            sawRoundCompletionTransition = false
        )
        assertTrue(expandResult.newCompletedItems.isEmpty())
        assertTrue(expandResult.isScrollOrExpandRejected)

        // Reverse scroll revealing older rounds at head
        val reverseScroll = listOf(1.30, 8.90, 1.15).mapIndexed { i, v ->
            com.example.data.engine.ExtractedHistoryItem(multiplier = v, roundId = "R-${4 - i}", orderIndex = i)
        }
        val reverseResult = RibbonHistoryCollector.alignAuthoritativeLiveSnapshot(
            previousBaseline = baseline,
            currentItems = reverseScroll,
            previousExpanded = false,
            currentExpanded = false,
            sawRoundCompletionTransition = false
        )
        assertTrue(reverseResult.newCompletedItems.isEmpty())
        assertTrue(reverseResult.isScrollOrExpandRejected)

        // Repeated identical frame (repositioned UI bounds)
        val sameItems = listOf(2.45, 1.30, 8.90).mapIndexed { i, v ->
            com.example.data.engine.ExtractedHistoryItem(multiplier = v, roundId = "R-${5 - i}", orderIndex = i, bounds = Rect(50 + i * 20, 100, 70 + i * 20, 120))
        }
        val unchangedResult = RibbonHistoryCollector.alignAuthoritativeLiveSnapshot(
            previousBaseline = baseline,
            currentItems = sameItems
        )
        assertTrue(unchangedResult.newCompletedItems.isEmpty())
        assertTrue(unchangedResult.isUnchanged)
    }

    @Test
    fun test05_unknownGameTitleRecordedContinually_neverHardcodesAppOrGameTitle() = runBlocking {
        val adapter = GameAdapterRegistry.resolveAdapter(
            packageName = "com.android.chrome",
            url = "https://unlisted-crash-host.example/play",
            pageHints = emptyList(),
            rawTexts = listOf("Predict Manager", "Bet", "Cash Out", "1.45x"),
            hasMultiplierSignals = true
        )
        assertNotNull(adapter)
        assertEquals("unknown", adapter!!.gameId)
        assertEquals("Unknown", adapter.displayedGameName)

        val (_, repo) = setupDbAndRepo()
        AutomationController.startMonitoringSession(isAccessibilityConnected = true, nowMs = 1_700_000_000_000L)
        AutomationController.processDetectionEvent(
            detection = detectionFrame("BETTING_OPEN", ribbon = listOf(2.00, 1.50), gameId = "unknown", gameName = "Unknown", platformName = "Chrome Web", nowMs = 1_700_000_001_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_001_000L
        )
        AutomationController.processDetectionEvent(
            detection = detectionFrame("CRASHED", liveMult = 3.75, ribbon = listOf(3.75, 2.00, 1.50), roundId = "unk-1", gameId = "unknown", gameName = "Unknown", platformName = "Chrome Web", nowMs = 1_700_000_006_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_006_000L
        )
        val rows = repo.getCrashRecordsForSessionOnce(AutomationController.activeSessionId.value)
        assertEquals(1, rows.size)
        assertEquals("unknown", rows.first().gameId)
        assertEquals("Unknown", rows.first().gameName)
    }

    @Test
    fun test06_gameSwitchingIsolatesPerGameHistoryPartitions() = runBlocking {
        val (_, repo) = setupDbAndRepo()
        val now = 1_700_000_000_000L
        repo.insertCrashRecord(crashRow(gameId = "aviator", gameName = "Aviator", sessionId = "s1", mult = 1.50, ts = now))
        repo.insertCrashRecord(crashRow(gameId = "jetx", gameName = "JetX", sessionId = "s1", mult = 15.00, ts = now + 10_000L))
        repo.insertCrashRecord(crashRow(gameId = "unknown", gameName = "Unknown", sessionId = "s1", mult = 2.40, ts = now + 20_000L))

        val aviatorOnly = repo.getCrashRecordsSinceAsc(0L, gameId = "aviator")
        val jetxOnly = repo.getCrashRecordsSinceAsc(0L, gameId = "jetx")
        val unknownOnly = repo.getCrashRecordsSinceAsc(0L, gameId = "unknown")

        assertEquals(1, aviatorOnly.size)
        assertEquals(1.50, aviatorOnly.first().multiplier, 0.001)
        assertEquals(1, jetxOnly.size)
        assertEquals(15.00, jetxOnly.first().multiplier, 0.001)
        assertEquals(1, unknownOnly.size)
        assertEquals(2.40, unknownOnly.first().multiplier, 0.001)
    }

    @Test
    fun test07_backgroundServiceStatusAndCoveredWindowReporting() = runBlocking {
        setupDbAndRepo()
        AutomationController.startMonitoringSession(isAccessibilityConnected = true, nowMs = 1_700_000_000_000L)

        // Covered / inaccessible window must report unavailable & invent 0 crashes
        AutomationController.processDetectionEvent(
            detection = detectionFrame(
                phase = "LIVE_DETECTION_LOST",
                liveMult = 9.99,
                ribbon = listOf(9.99, 1.20),
                windowAccessStatus = MonitoringStatus.GAME_NOT_ACCESSIBLE,
                liveLost = true,
                nowMs = 1_700_000_005_000L
            ),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_005_000L
        )
        assertEquals(0, AutomationController.activeSessionCrashRecords.value.size)

        // Revoked accessibility must surface PERMISSION_REQUIRED truthfully
        AutomationController.processDetectionEvent(
            detection = detectionFrame("BETTING_OPEN", nowMs = 1_700_000_006_000L),
            isAccessibilityConnected = false,
            nowMs = 1_700_000_006_000L
        )
        assertEquals(MonitoringStatus.PERMISSION_REQUIRED, AutomationController.monitoringStatus.value)
        assertEquals(
            MonitoringPipelineStatus.ACCESSIBILITY_DISCONNECTED,
            AutomationController.engine!!.gameDetection.gameState.value.pipelineStatus
        )
    }

    @Test
    fun test08_overlayClampAndFloatingPillShowsOnlyLastCrashAndRoundUnknown() {
        val safeBounds = Rect(0, 50, 1080, 2200)
        val clamped = ScreenEarnerOverlayService.clampOverlayPosition(x = 1500, y = -20, viewWidthPx = 200, viewHeightPx = 100, safeBounds = safeBounds)
        assertEquals(880, clamped.first)
        assertEquals(50, clamped.second)

        composeRule.setContent {
            AutoPredictorTheme(darkTheme = false) {
                FloatingSessionStatsHudComposable(
                    stats = SessionStats(),
                    observedRounds = 4,
                    automationState = AutomationState.RUNNING,
                    liveMultiplier = 8.77,
                    latestCrashMultiplier = 2.45,
                    latestRoundId = null
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("top_pill_last_crash").assertIsDisplayed()
        composeRule.onNodeWithText("LAST CRASH: 2.45x").assertIsDisplayed()
        composeRule.onNodeWithText("ROUND: Unknown").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("8.77", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun test09_screenAwakeTruthfulState_reflectsRealLockAndStandby() {
        composeRule.setContent {
            AutoPredictorTheme(darkTheme = false) {
                HomeScreen(
                    gameState = GameState(),
                    automationState = AutomationState.IDLE,
                    isOverlayServiceRunning = false,
                    strategyConfig = StrategyConfig(keepScreenAwake = true),
                    onToggleOverlayService = {},
                    onStartMonitoring = {},
                    onPauseMonitoring = {},
                    onNavigateToTab = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Keep Awake: STANDBY").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Keep Awake: ON").fetchSemanticsNodes().size)
    }

    @Test
    fun test10_strategyA_aboveXThreeTimesThenBelowYTwoTimes_triggersAlarm() {
        val engine = PatternDetectionEngine()
        val strategy = CustomPatternStrategy(
            id = 10L,
            name = "3 Above 2x Then 2 Below 2x",
            strategyGroup = StrategyGroup.MULTIPLIER_INTERVAL,
            strategyMode = StrategyMode.STRATEGY_A_CONSECUTIVE_LOWS,
            multiplierRuleSubtype = MultiplierRuleSubtype.ABOVE_THEN_BELOW,
            aboveThresholdX = 2.00,
            aboveConsecutiveCount = 3,
            belowThresholdY = 2.00,
            belowConsecutiveCount = 2,
            inclusiveBounds = false
        )
        val t0 = 1_700_000_000_000L
        val partialRecords = listOf(2.50, 3.10, 4.00, 1.40).mapIndexed { idx, v ->
            crashRow(mult = v, ts = t0 + idx * 5_000L, roundId = "R-$idx", dedupSuffix = "r$idx")
        }
        val batch1 = engine.evaluateStrategiesOnCrashRecords(listOf(strategy), partialRecords, nowMs = t0 + 15_000L)
        assertTrue(batch1.events.any { it.notificationType == StrategyNotificationType.STRATEGY_APPROACHING })
        assertTrue(batch1.events.none { it.notificationType == StrategyNotificationType.STRATEGY_REACHED })

        val fullRecords = partialRecords + crashRow(mult = 1.85, ts = t0 + 20_000L, roundId = "R-4", dedupSuffix = "r4")
        val batch2 = engine.evaluateStrategiesOnCrashRecords(batch1.updatedStrategies, fullRecords, nowMs = t0 + 20_000L)
        assertEquals(1, batch2.events.count { it.notificationType == StrategyNotificationType.STRATEGY_REACHED })
        assertEquals("ABOVE_THEN_BELOW", batch2.newOccurrences.first().patternType)
    }

    @Test
    fun test11_inclusiveVsExclusiveBounds_andRangeConflictValidator() {
        val engine = PatternDetectionEngine()
        val exclusiveRule = CustomPatternStrategy(
            id = 11L,
            name = "Exclusive Low <2.00",
            strategyGroup = StrategyGroup.MULTIPLIER_INTERVAL,
            strategyMode = StrategyMode.STRATEGY_A_CONSECUTIVE_LOWS,
            multiplierRuleSubtype = MultiplierRuleSubtype.LOW_MULTIPLIER,
            inclusiveBounds = false
        ).withConditions(listOf(PatternCondition(PatternComparator.LESS_THAN, 2.00, consecutiveRounds = 2)))

        val inclusiveRule = CustomPatternStrategy(
            id = 12L,
            name = "Inclusive Low <=2.00",
            strategyGroup = StrategyGroup.MULTIPLIER_INTERVAL,
            strategyMode = StrategyMode.STRATEGY_A_CONSECUTIVE_LOWS,
            multiplierRuleSubtype = MultiplierRuleSubtype.LOW_MULTIPLIER,
            inclusiveBounds = true
        ).withConditions(listOf(PatternCondition(PatternComparator.LESS_OR_EQUAL, 2.00, consecutiveRounds = 2)))

        val recs = listOf(1.50, 2.00).mapIndexed { idx, v -> crashRow(mult = v, ts = 1_700_000_000_000L + idx * 5_000L, dedupSuffix = "b$idx") }
        val res = engine.evaluateStrategiesOnCrashRecords(listOf(exclusiveRule, inclusiveRule), recs, nowMs = 1_700_000_010_000L)
        assertEquals(0, res.events.count { it.strategy.id == 11L && it.notificationType == StrategyNotificationType.STRATEGY_REACHED })
        assertEquals(1, res.events.count { it.strategy.id == 12L && it.notificationType == StrategyNotificationType.STRATEGY_REACHED })

        // Range validation checks overlap & blocking error
        val conflicting = CustomPatternStrategy(
            id = 13L,
            name = "Overlap",
            strategyGroup = StrategyGroup.MULTIPLIER_INTERVAL,
            strategyMode = StrategyMode.CUSTOM_CONDITION
        ).withConditions(
            listOf(
                PatternCondition(PatternComparator.LESS_THAN, 5.0, consecutiveRounds = 1),
                PatternCondition(PatternComparator.GREATER_THAN, 3.0, consecutiveRounds = 1)
            )
        )
        val validation = StrategyRangeValidator.validate(conflicting, emptyList(), configuredLowThreshold = 10.0, configuredBigThreshold = 5.0)
        assertTrue(validation.warnings.isNotEmpty())
        val invalid = StrategyRangeValidator.validate(conflicting.copy(name = ""), emptyList())
        assertFalse(invalid.isValid)
    }

    @Test
    fun test12_strategyB_timeIntervalPostBigWaitAnd1h3h6hWindow() {
        val engine = PatternDetectionEngine()
        val t0 = 1_700_000_000_000L
        val strategyB = CustomPatternStrategy(
            id = 14L,
            name = "Wait 5m after 10x",
            strategyGroup = StrategyGroup.TIME_INTERVAL,
            strategyMode = StrategyMode.STRATEGY_B_BIG_INTERVALS,
            bigMultiplierThreshold = 10.00,
            postBigWaitMinutes = 5,
            analysisWindowMinutes = 180,
            requiredOccurrencesPerHour = 1
        ).withConditions(listOf(PatternCondition(PatternComparator.GREATER_OR_EQUAL, 10.0, consecutiveRounds = 1, timeWindowMinutes = 180)))

        val bigCrash = crashRow(mult = 15.20, ts = t0, dedupSuffix = "big1")
        val smallCrashAt4Min = crashRow(mult = 1.40, ts = t0 + 4 * 60_000L, dedupSuffix = "sm4")

        val tooEarly = engine.evaluateStrategiesOnCrashRecords(listOf(strategyB), listOf(bigCrash, smallCrashAt4Min), nowMs = t0 + 4 * 60_000L)
        assertEquals(0, tooEarly.events.size)

        val tickAt5Min = engine.evaluateTimeSchedulesTick(tooEarly.updatedStrategies, listOf(bigCrash, smallCrashAt4Min), nowMs = t0 + 5 * 60_000L)
        assertEquals(1, tickAt5Min.events.size)
        assertEquals("TIME_INTERVAL_WAIT", tickAt5Min.newOccurrences.first().patternType)
    }

    @Test
    fun test13_strategyC_horizontalColorAndHexSequenceMatching() {
        val engine = PatternDetectionEngine()
        val strategyC = CustomPatternStrategy(
            id = 15L,
            name = "Blue-Purple-Pink",
            strategyGroup = StrategyGroup.COLOR_SEQUENCE,
            strategyMode = StrategyMode.STRATEGY_C_COLOR_PATTERNS,
            colorMatchDirection = ColorMatchDirection.HORIZONTAL,
            colorSequenceCsv = "Blue,Purple,Pink",
            customColorHexFilterCsv = "#3B82F6,#8B5CF6,#EC4899"
        )
        val t0 = 1_700_000_000_000L
        val recs = listOf(
            crashRow(mult = 1.30, ts = t0, hex = "#3B82F6", category = CrashColorCategory.Blue, dedupSuffix = "c0"),
            crashRow(mult = 3.50, ts = t0 + 5_000L, hex = "#8B5CF6", category = CrashColorCategory.Purple, dedupSuffix = "c1"),
            crashRow(mult = 14.00, ts = t0 + 10_000L, hex = "#EC4899", category = CrashColorCategory.Pink, dedupSuffix = "c2")
        )
        val batch = engine.evaluateStrategiesOnCrashRecords(listOf(strategyC), recs, nowMs = t0 + 10_000L)
        assertEquals(1, batch.events.size)
        assertTrue(batch.newOccurrences.first().detailsSummary.contains("#EC4899"))
    }

    @Test
    fun test14_strategyC_verticalGridColumnColorSequenceMatching() {
        val engine = PatternDetectionEngine()
        val strategyC = CustomPatternStrategy(
            id = 16L,
            name = "Vertical Stacked Pinks",
            strategyGroup = StrategyGroup.COLOR_SEQUENCE,
            strategyMode = StrategyMode.STRATEGY_C_COLOR_PATTERNS,
            colorMatchDirection = ColorMatchDirection.VERTICAL,
            gridColumnsForVertical = 4,
            colorSequenceCsv = "Pink,Pink"
        )
        val t0 = 1_700_000_000_000L
        // Indices: 0=Pink, 1=Blue, 2=Blue, 3=Blue, 4=Pink -> index 0 & index 4 share column 0 in 4-col grid
        val recs = listOf(
            crashRow(mult = 12.0, ts = t0, category = CrashColorCategory.Pink, dedupSuffix = "v0"),
            crashRow(mult = 1.2, ts = t0 + 1_000L, category = CrashColorCategory.Blue, dedupSuffix = "v1"),
            crashRow(mult = 1.3, ts = t0 + 2_000L, category = CrashColorCategory.Blue, dedupSuffix = "v2"),
            crashRow(mult = 1.4, ts = t0 + 3_000L, category = CrashColorCategory.Blue, dedupSuffix = "v3"),
            crashRow(mult = 18.0, ts = t0 + 4_000L, category = CrashColorCategory.Pink, dedupSuffix = "v4")
        )
        val matched = engine.evaluateStrategiesOnCrashRecords(listOf(strategyC), recs, nowMs = t0 + 4_000L)
        assertEquals(1, matched.events.size)
        assertTrue(matched.newOccurrences.first().patternType.contains("Vertical"))
    }

    @Test
    fun test15_strategyReordering_swapsSortOrderInRepository() = runBlocking {
        val (_, repo) = setupDbAndRepo()
        val id1 = repo.insertCustomStrategy(CustomPatternStrategy(name = "Rule Alpha", sortOrder = 0))
        val id2 = repo.insertCustomStrategy(CustomPatternStrategy(name = "Rule Beta", sortOrder = 1))
        val initial = repo.getCustomStrategiesOnce()
        assertEquals(listOf("Rule Alpha", "Rule Beta"), initial.map { it.name })

        repo.swapStrategyOrder(initial[0], initial[1])
        val swapped = repo.getCustomStrategiesOnce()
        assertEquals(listOf("Rule Beta", "Rule Alpha"), swapped.map { it.name })
        assertEquals(id2, swapped[0].id)
        assertEquals(id1, swapped[1].id)
    }

    @Test
    fun test16_alarmSoundNeverAutoAssignedWhenBlank() {
        assertNull(AlarmAudioController.resolveSoundUri("", isAlarm = true))
        assertNull(AlarmAudioController.resolveSoundUri("", isAlarm = false))
        val explicit = AlarmAudioController.resolveSoundUri("content://settings/system/alarm_alert", isAlarm = true)
        assertEquals(Uri.parse("content://settings/system/alarm_alert"), explicit)

        val defaults = AlarmProfileEntity.defaultProfiles()
        assertEquals(5, defaults.size)
        assertTrue(defaults.all { it.soundUri.isBlank() && it.soundLabel == "No sound selected" })
    }

    @Test
    fun test17_settingsScreenDisplaysNoSoundSelected_neverDefaultTone() {
        composeRule.setContent {
            AutoPredictorTheme(darkTheme = false) {
                SettingsAppearanceScreen(
                    strategyConfig = StrategyConfig(),
                    notificationSettings = NotificationSettings(standardSoundUri = "", alarmSoundUri = ""),
                    alarmProfiles = AlarmProfileEntity.defaultProfiles(),
                    onStrategyConfigChanged = {},
                    onNotificationSettingsChanged = {},
                    onSaveAlarmProfile = {},
                    onResetSessionStats = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_alarm_profiles_card").performScrollTo()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("No sound selected", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertEquals(0, composeRule.onAllNodesWithText("Default Notification Tone", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Default System Alarm Tone", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun test18_fiveIndependentAlarmProfileCategoriesPersistedInRoom() = runBlocking {
        val (_, repo) = setupDbAndRepo()
        val seeded = repo.getAlarmProfilesOnce()
        assertEquals(5, seeded.size)

        val multProfile = repo.getAlarmProfile(AlarmProfileCategory.MULTIPLIER_CONDITIONS).copy(
            soundUri = "content://media/external/audio/mult_tone",
            soundLabel = "Custom Mult Tone",
            vibrationPattern = "LONG_CONTINUOUS",
            repeatCooldownSeconds = 30
        )
        repo.upsertAlarmProfile(multProfile)

        val fetchedMult = repo.getAlarmProfile(AlarmProfileCategory.MULTIPLIER_CONDITIONS)
        val fetchedTime = repo.getAlarmProfile(AlarmProfileCategory.TIME_BASED_ALERTS)
        assertEquals("content://media/external/audio/mult_tone", fetchedMult.soundUri)
        assertEquals("LONG_CONTINUOUS", fetchedMult.vibrationPattern)
        assertEquals("", fetchedTime.soundUri)
        assertEquals("No sound selected", fetchedTime.soundLabel)
    }

    @Test
    fun test19_repeatingAlarmEngine_alertsEveryGenuineOccurrence_andRespectsRepeatLimit() = runBlocking {
        val (context, repo) = setupDbAndRepo()
        val fakeDispatcher = object : SystemNotificationDispatcher {
            override fun hasNotificationPermission(context: Context): Boolean = true
            override fun postSystemNotification(
                context: Context,
                notificationId: Int,
                title: String,
                message: String,
                priorityLevel: NotificationPriorityLevel,
                soundEnabled: Boolean,
                vibrationEnabled: Boolean,
                historyRecordId: Long
            ): Boolean = true
        }
        val mgr = StrategyNotificationManager(context, repo, fakeDispatcher)
        val strat = CustomPatternStrategy(id = 41L, name = "RepeatRule", repeatCooldownMinutes = 0, repeatLimit = 2)
        val settings = NotificationSettings(masterEnabled = true, alarmRepeatIntervalMinutes = 0)
        val t0 = 1_700_000_000_000L

        val d1 = mgr.dispatchPatternAlert(PatternAlertEvent(strat, StrategyNotificationType.STRATEGY_REACHED, "Hit 1", "Msg 1", "k1", 1.2, "occ_1", t0), settings, t0)
        val dup = mgr.dispatchPatternAlert(PatternAlertEvent(strat, StrategyNotificationType.STRATEGY_REACHED, "Hit 1", "Msg 1", "k1", 1.2, "occ_1", t0 + 1_000L), settings, t0 + 1_000L)
        val d2 = mgr.dispatchPatternAlert(PatternAlertEvent(strat, StrategyNotificationType.STRATEGY_REACHED, "Hit 2", "Msg 2", "k2", 1.3, "occ_2", t0 + 5_000L), settings, t0 + 5_000L)
        val capped = mgr.dispatchPatternAlert(PatternAlertEvent(strat, StrategyNotificationType.STRATEGY_REACHED, "Hit 3", "Msg 3", "k3", 1.1, "occ_3", t0 + 10_000L), settings, t0 + 10_000L)

        assertEquals(NotificationDeliveryStatus.DELIVERED, d1.deliveryStatus)
        assertEquals(NotificationDeliveryStatus.SUPPRESSED_DUPLICATE, dup.deliveryStatus)
        assertEquals(NotificationDeliveryStatus.DELIVERED, d2.deliveryStatus)
        assertEquals(NotificationDeliveryStatus.SUPPRESSED_COOLDOWN, capped.deliveryStatus)
    }

    @Test
    fun test20_oneStrategyDoesNotBlockAnotherStrategyAlert() = runBlocking {
        val (context, repo) = setupDbAndRepo()
        val fakeDispatcher = object : SystemNotificationDispatcher {
            override fun hasNotificationPermission(context: Context): Boolean = true
            override fun postSystemNotification(
                context: Context,
                notificationId: Int,
                title: String,
                message: String,
                priorityLevel: NotificationPriorityLevel,
                soundEnabled: Boolean,
                vibrationEnabled: Boolean,
                historyRecordId: Long
            ): Boolean = true
        }
        val mgr = StrategyNotificationManager(context, repo, fakeDispatcher)
        val strat1 = CustomPatternStrategy(id = 51L, name = "Strat One", repeatCooldownMinutes = 10)
        val strat2 = CustomPatternStrategy(id = 52L, name = "Strat Two", repeatCooldownMinutes = 0)
        val settings = NotificationSettings(masterEnabled = true, alarmRepeatIntervalMinutes = 0)
        val t0 = 1_700_000_000_000L

        val s1Hit1 = mgr.dispatchPatternAlert(PatternAlertEvent(strat1, StrategyNotificationType.STRATEGY_REACHED, "S1", "M1", "k1", 1.2, "s1_occ1", t0), settings, t0)
        val s1Blocked = mgr.dispatchPatternAlert(PatternAlertEvent(strat1, StrategyNotificationType.STRATEGY_REACHED, "S1", "M2", "k2", 1.3, "s1_occ2", t0 + 1_000L), settings, t0 + 1_000L)
        val s2Deliver = mgr.dispatchPatternAlert(PatternAlertEvent(strat2, StrategyNotificationType.STRATEGY_REACHED, "S2", "M1", "k3", 12.0, "s2_occ1", t0 + 1_000L), settings, t0 + 1_000L)

        assertEquals(NotificationDeliveryStatus.DELIVERED, s1Hit1.deliveryStatus)
        assertEquals(NotificationDeliveryStatus.SUPPRESSED_COOLDOWN, s1Blocked.deliveryStatus)
        assertEquals(NotificationDeliveryStatus.DELIVERED, s2Deliver.deliveryStatus)
    }

    @Test
    fun test21_roomMigration10To11_and_11To12_preserveExistingRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_v10_v12.db"
        context.deleteDatabase(dbName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE crash_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, gameId TEXT NOT NULL, gameName TEXT NOT NULL, multiplier REAL NOT NULL, detectedColorHex TEXT NOT NULL, colorCategory TEXT NOT NULL, isPixelColorVerified INTEGER NOT NULL, crashTimestamp INTEGER NOT NULL, hourStartTimestamp INTEGER NOT NULL, sourcePackage TEXT NOT NULL, roundIdentifier TEXT, confidence REAL NOT NULL, manuallyCorrected INTEGER NOT NULL, collectionSource TEXT NOT NULL, dedupKey TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE multiplier_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, gameId TEXT NOT NULL, roundId TEXT, multiplier REAL NOT NULL, gameTimestampMs INTEGER, observationTimestampMs INTEGER NOT NULL, crashTimestamp INTEGER NOT NULL, platform TEXT NOT NULL, timestampSource TEXT NOT NULL, collectionSource TEXT NOT NULL, collectionStatus TEXT NOT NULL, dedupKey TEXT NOT NULL, sequenceIndex INTEGER NOT NULL, hasGapBefore INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE notification_settings (id INTEGER NOT NULL PRIMARY KEY, masterEnabled INTEGER NOT NULL, approachingEnabled INTEGER NOT NULL, reachedEnabled INTEGER NOT NULL, invalidatedEnabled INTEGER NOT NULL, historyUnavailableEnabled INTEGER NOT NULL, pausedEnabled INTEGER NOT NULL, hourlySummaryEnabled INTEGER NOT NULL, gameDetectionEnabled INTEGER NOT NULL, monitoringErrorsEnabled INTEGER NOT NULL, soundEnabled INTEGER NOT NULL, vibrationEnabled INTEGER NOT NULL, repeatIntervalMinutes INTEGER NOT NULL, quietHoursEnabled INTEGER NOT NULL, quietStartHour INTEGER NOT NULL, quietStartMinute INTEGER NOT NULL, quietEndHour INTEGER NOT NULL, quietEndMinute INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE notification_history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, strategyId INTEGER NOT NULL, strategyName TEXT NOT NULL, notificationType TEXT NOT NULL, title TEXT NOT NULL, message TEXT NOT NULL, triggeringRecordKey TEXT, triggeringMultiplier REAL, timestampMs INTEGER NOT NULL, deliveryStatus TEXT NOT NULL, interactionStatus TEXT NOT NULL, channelId TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE custom_pattern_strategies (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, isEnabled INTEGER NOT NULL, strategyMode TEXT NOT NULL, logicOperator TEXT NOT NULL, conditionsJson TEXT NOT NULL, waitingPeriodSeconds INTEGER NOT NULL, notificationLevel TEXT NOT NULL, approachingEnabled INTEGER NOT NULL, customMessage TEXT NOT NULL, repeatPolicy TEXT NOT NULL, repeatCooldownMinutes INTEGER NOT NULL, notificationEnabled INTEGER NOT NULL, requiredOccurrencesPerHour INTEGER NOT NULL, analysisWindowMinutes INTEGER NOT NULL, minIntervalMinutes INTEGER NOT NULL, maxIntervalMinutes INTEGER NOT NULL, colorSequenceCsv TEXT NOT NULL, targetGameId TEXT NOT NULL, lastEvaluatedRecordKey TEXT, lastTriggeredOccurrenceKey TEXT, lastApproachedOccurrenceKey TEXT, lastNotifiedAtMs INTEGER NOT NULL, currentlyApproaching INTEGER NOT NULL, currentlyReached INTEGER NOT NULL, createdAtMs INTEGER NOT NULL)")

                        db.execSQL("INSERT INTO crash_records VALUES (1, 'aviator', 'Aviator', 4.55, '#8B5CF6', 'Purple', 0, 1700000000000, 1700000000000, 'com.android.chrome', 'R-10', 1.0, 0, 'LIVE_CRASH', 'legacy_key_1')")
                        db.execSQL("INSERT INTO custom_pattern_strategies (id, name, isEnabled, strategyMode, logicOperator, conditionsJson, waitingPeriodSeconds, notificationLevel, approachingEnabled, customMessage, repeatPolicy, repeatCooldownMinutes, notificationEnabled, requiredOccurrencesPerHour, analysisWindowMinutes, minIntervalMinutes, maxIntervalMinutes, colorSequenceCsv, targetGameId, lastEvaluatedRecordKey, lastTriggeredOccurrenceKey, lastApproachedOccurrenceKey, lastNotifiedAtMs, currentlyApproaching, currentlyReached, createdAtMs) VALUES (7, 'Legacy Rule', 1, 'CUSTOM_CONDITION', 'AND', '', 0, 'HIGH', 1, '', 'ONCE_PER_OCCURRENCE', 0, 1, 2, 60, 5, 15, '', 'ALL', NULL, NULL, NULL, 0, 0, 0, 1700000000000)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val sqlite = helper.writableDatabase
        ScreenEarnerDatabase.MIGRATION_10_11.migrate(sqlite)
        ScreenEarnerDatabase.MIGRATION_11_12.migrate(sqlite)

        sqlite.query("SELECT multiplier, isConfirmed, sessionId FROM crash_records WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(4.55, c.getDouble(0), 0.001)
            assertEquals(1, c.getInt(1))
            assertEquals("", c.getString(2))
        }
        sqlite.query("SELECT name, strategyGroup, repeatLimit, sortOrder FROM custom_pattern_strategies WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Legacy Rule", c.getString(0))
            assertEquals("MULTIPLIER_INTERVAL", c.getString(1))
            assertEquals(0, c.getInt(2))
            assertEquals(0, c.getInt(3))
        }
        sqlite.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('alarm_profiles', 'monitoring_sessions')").use { c ->
            assertEquals(2, c.count)
        }
        helper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun test22_logsAnalyticsScreen_noEditButtons_andTapToExpandShowsUnknownRoundId() {
        val recUnknown = crashRow(mult = 1.75, ts = 1_700_000_000_000L, roundId = null, dedupSuffix = "exp1").copy(id = 101L)
        composeRule.setContent {
            AutoPredictorTheme(darkTheme = false) {
                LogsAnalyticsScreen(
                    crashRecords = listOf(recUnknown),
                    allCrashRecords = listOf(recUnknown),
                    selectedGameId = "ALL"
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("Edit", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Fix", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithContentDescription("Edit", substring = true).fetchSemanticsNodes().size)

        composeRule.onNodeWithTag("crash_history_row_101").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("crash_history_detail_101", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Round ID: Unknown", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun test23_hourlyAnalysisEngine_supportsAllTimeRangePresets() {
        val now = 1_700_000_000_000L
        val records = listOf(
            crashRow(mult = 1.20, ts = now - 30 * 60_000L, dedupSuffix = "t1"),       // 30m ago
            crashRow(mult = 3.40, ts = now - 2 * 3_600_000L, dedupSuffix = "t2"),    // 2h ago
            crashRow(mult = 12.00, ts = now - 5 * 3_600_000L, dedupSuffix = "t3"),   // 5h ago
            crashRow(mult = 1.80, ts = now - 10 * 3_600_000L, dedupSuffix = "t4"),   // 10h ago
            crashRow(mult = 5.00, ts = now - 20 * 3_600_000L, dedupSuffix = "t5")    // 20h ago
        )
        assertEquals(1, HourlyAnalysisEngine.analyzeTimeRange(HourlyAnalysisEngine.TimeRangePreset.ONE_HOUR, records, nowMs = now).totalRounds)
        assertEquals(2, HourlyAnalysisEngine.analyzeTimeRange(HourlyAnalysisEngine.TimeRangePreset.THREE_HOURS, records, nowMs = now).totalRounds)
        assertEquals(3, HourlyAnalysisEngine.analyzeTimeRange(HourlyAnalysisEngine.TimeRangePreset.SIX_HOURS, records, nowMs = now).totalRounds)
        assertEquals(4, HourlyAnalysisEngine.analyzeTimeRange(HourlyAnalysisEngine.TimeRangePreset.TWELVE_HOURS, records, nowMs = now).totalRounds)
        assertEquals(5, HourlyAnalysisEngine.analyzeTimeRange(HourlyAnalysisEngine.TimeRangePreset.TWENTY_FOUR_HOURS, records, nowMs = now).totalRounds)

        val custom = HourlyAnalysisEngine.analyzeTimeRange(
            preset = HourlyAnalysisEngine.TimeRangePreset.CUSTOM,
            records = records,
            customStartMs = now - 6 * 3_600_000L,
            customEndMs = now - 3_600_000L,
            nowMs = now
        )
        assertEquals(2, custom.totalRounds)
    }

    @Test
    fun test24_clearHistoryByScope_currentArchivedAndAll() = runBlocking {
        val (_, repo) = setupDbAndRepo()
        val start = AutomationController.startMonitoringSession(isAccessibilityConnected = true, nowMs = 1_700_000_000_000L)
        assertTrue(start.started)
        val activeSid = AutomationController.activeSessionId.value

        repo.insertCrashRecord(crashRow(gameId = "aviator", sessionId = activeSid, mult = 1.80, ts = 1_700_000_001_000L, dedupSuffix = "act1"))
        repo.insertCrashRecord(crashRow(gameId = "aviator", sessionId = "archived_s", mult = 4.50, ts = 1_699_000_000_000L, dedupSuffix = "arcAv"))
        repo.insertCrashRecord(crashRow(gameId = "jetx", sessionId = "archived_s", mult = 9.20, ts = 1_699_000_005_000L, dedupSuffix = "arcJx"))

        assertEquals(1, AutomationController.countClearScopeRecords(ClearHistoryScope.CURRENT_SESSION, "aviator"))
        assertEquals(1, AutomationController.countClearScopeRecords(ClearHistoryScope.SELECTED_GAME_ARCHIVED, "aviator"))
        assertEquals(3, AutomationController.countClearScopeRecords(ClearHistoryScope.ALL_HISTORY, "ALL"))

        // Clear archived records ONLY for aviator
        AutomationController.clearHistoryByScope(ClearHistoryScope.SELECTED_GAME_ARCHIVED, "aviator")
        assertEquals(1, repo.getCrashRecordsForSessionOnce(activeSid).size)
        assertEquals(1, repo.getCrashRecordsSinceAsc(0L, "jetx").size)
        assertEquals(0, AutomationController.countClearScopeRecords(ClearHistoryScope.SELECTED_GAME_ARCHIVED, "aviator"))

        // Clear current session only -> jetx archived stays
        AutomationController.clearHistoryByScope(ClearHistoryScope.CURRENT_SESSION, "aviator")
        assertEquals(0, repo.getCrashRecordsForSessionOnce(activeSid).size)
        assertEquals(1, repo.getCrashRecordsSinceAsc(0L, "jetx").size)

        // Clear ALL
        AutomationController.clearHistoryByScope(ClearHistoryScope.ALL_HISTORY, "ALL")
        assertEquals(0, AutomationController.countClearScopeRecords(ClearHistoryScope.ALL_HISTORY, "ALL"))
    }

    @Test
    fun test25_customStrategyBuilderDialog_twoStepReviewBeforeSave() {
        var saved: CustomPatternStrategy? = null
        composeRule.setContent {
            AutoPredictorTheme(darkTheme = false) {
                StrategyScreen(
                    currentConfig = StrategyConfig(),
                    customStrategies = emptyList(),
                    onSaveCustomStrategy = { saved = it },
                    onSaveConfig = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("new_above_below_btn").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("builder_strategy_name_input").performScrollTo().performTextReplacement("My Above Below Rule")
        composeRule.onNodeWithTag("builder_review_strategy_btn").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("strategy_review_panel").assertIsDisplayed()
        assertNull("Save callback must NOT fire before Review confirmation", saved)

        composeRule.onNodeWithTag("builder_save_strategy_btn").performClick()
        composeRule.waitForIdle()
        assertNotNull(saved)
        assertEquals("My Above Below Rule", saved!!.name)
        assertEquals(MultiplierRuleSubtype.ABOVE_THEN_BELOW, saved!!.multiplierRuleSubtype)
    }
}
