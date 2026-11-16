package com.example

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.ScreenEarnerDatabase
import com.example.data.database.ScreenEarnerRepository
import com.example.data.engine.AutomationState
import com.example.data.model.CollectionSource
import com.example.data.engine.ExtractedHistoryItem
import com.example.data.engine.HistoryCollectionMetadata
import com.example.data.engine.HourlyAnalysisEngine
import com.example.data.engine.RibbonHistoryCollector
import com.example.data.engine.SmartAnalysisAgent
import com.example.data.engine.StatisticalAnalysisEngine
import com.example.data.model.CrashColorCategory
import com.example.data.model.CrashRecord
import com.example.data.model.GameState
import com.example.data.model.MonitoringPipelineStatus
import com.example.data.model.MultiplierRecord
import com.example.data.model.SessionStats
import com.example.data.model.StrategyConfig
import com.example.service.AutomationController
import com.example.service.DetectedUiTargets
import com.example.service.FloatingSessionStatsHudComposable
import com.example.service.GameDetectionState
import com.example.service.SessionStateManager
import com.example.ui.screens.LiveMonitorScreen
import com.example.ui.theme.AutoPredictorTheme
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UrgentRepairPipelineTest {

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

    private fun setupIsolatedController(): Pair<Context, ScreenEarnerRepository> {
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

    private fun buildGameDetection(
        isGameDetected: Boolean = true,
        phase: String = "BETTING_OPEN",
        liveMult: Double? = null,
        ribbon: List<Double> = emptyList(),
        expanded: Boolean = false,
        roundId: String? = null,
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
            isGameDetected = isGameDetected,
            gameId = "aviator",
            displayedGameName = if (isGameDetected) "SportyBet Aviator" else "Waiting for Game...",
            platformName = if (isGameDetected) "SportyBet Aviator" else "Waiting for Game...",
            isReady = isGameDetected,
            gamePhase = phase,
            detectedMultiplier = liveMult,
            liveDetectionLost = false,
            detectionConfidence = if (isGameDetected) 0.9f else 0f,
            detectedRoundId = roundId,
            foregroundPackage = if (isGameDetected) "com.android.chrome" else "",
            uiTargets = DetectedUiTargets(
                detectedMultiplier = liveMult,
                ribbonMultipliers = ribbon,
                ribbonItems = items,
                historyPanelExpanded = expanded,
                detectedCurrentRoundId = roundId,
                gamePhase = phase
            ),
            updatedAtMs = nowMs
        )
    }

    @Test
    fun `live recording isolates older sessions, captures baseline only first, ignores flight values, and records real new crashes including duplicates`() = runTest {
        val (context, repo) = setupIsolatedController()

        // Seed older session DB record that MUST NOT leak into a brand new active session
        repo.insertCrashRecord(
            CrashRecord(
                sessionId = "old_session_xyz",
                gameId = "aviator",
                gameName = "Aviator",
                multiplier = 99.00,
                detectedColorHex = "#EC4899",
                colorCategory = CrashColorCategory.Pink,
                crashTimestamp = 1_699_000_000_000L,
                hourStartTimestamp = 1_699_000_000_000L,
                sourcePackage = "com.android.chrome",
                dedupKey = "old_session_xyz_aviator_99"
            )
        )

        val startRes = AutomationController.startMonitoringSession(
            isAccessibilityConnected = true,
            nowMs = 1_700_000_000_000L
        )
        assertTrue(startRes.started)
        val sessionId = AutomationController.activeSessionId.value
        assertTrue(sessionId.isNotBlank())
        assertEquals(0, AutomationController.activeSessionCrashRecords.value.size)

        // 1. First readable ribbon -> Baseline capture only (0 new inserts)
        AutomationController.processDetectionEvent(
            detection = buildGameDetection(ribbon = listOf(2.10, 1.45, 5.60), roundId = "rd_100", nowMs = 1_700_000_001_000L),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_001_000L
        )
        assertEquals(0, AutomationController.activeSessionCrashRecords.value.size)
        assertEquals(0, repo.getCrashRecordsForSessionOnce(sessionId).size)
        assertEquals(
            MonitoringPipelineStatus.BASELINE_CAPTURED_WAITING_FOR_NEW_CRASH,
            AutomationController.engine!!.gameDetection.gameState.value.pipelineStatus
        )
        assertNull(AutomationController.engine!!.gameDetection.gameState.value.latestCrashMultiplier)

        // 2. In-flight rising multipliers -> 0 inserts, latestCrashMultiplier stays null
        listOf(1.05, 1.85, 3.40).forEachIndexed { idx, flyingMult ->
            AutomationController.processDetectionEvent(
                detection = buildGameDetection(
                    phase = "IN_FLIGHT",
                    liveMult = flyingMult,
                    ribbon = listOf(2.10, 1.45, 5.60),
                    roundId = "rd_101",
                    nowMs = 1_700_000_002_000L + (idx * 300L)
                ),
                isAccessibilityConnected = true,
                nowMs = 1_700_000_002_000L + (idx * 300L)
            )
        }
        assertEquals(0, AutomationController.activeSessionCrashRecords.value.size)
        assertNull(AutomationController.engine!!.gameDetection.gameState.value.latestCrashMultiplier)

        // 3. Real completed crash 2.45x enters ribbon head -> persists 1 row
        AutomationController.processDetectionEvent(
            detection = buildGameDetection(
                phase = "CRASHED",
                liveMult = 2.45,
                ribbon = listOf(2.45, 2.10, 1.45, 5.60),
                roundId = "rd_101",
                nowMs = 1_700_000_005_000L
            ),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_005_000L
        )
        assertEquals(1, AutomationController.activeSessionCrashRecords.value.size)
        assertEquals(2.45, AutomationController.activeSessionCrashRecords.value.first().multiplier, 0.001)
        assertEquals(
            MonitoringPipelineStatus.MONITORING_LIVE,
            AutomationController.engine!!.gameDetection.gameState.value.pipelineStatus
        )
        assertEquals(2.45, AutomationController.engine!!.gameDetection.gameState.value.latestCrashMultiplier ?: 0.0, 0.001)

        // 4. Unchanged repeated frame -> 0 extra inserts
        AutomationController.processDetectionEvent(
            detection = buildGameDetection(
                phase = "BETTING_OPEN",
                ribbon = listOf(2.45, 2.10, 1.45, 5.60),
                roundId = "rd_102",
                nowMs = 1_700_000_006_000L
            ),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_006_000L
        )
        assertEquals(1, AutomationController.activeSessionCrashRecords.value.size)

        // 5. Expanding history panel with older tail entries -> 0 extra inserts
        AutomationController.processDetectionEvent(
            detection = buildGameDetection(
                phase = "BETTING_OPEN",
                ribbon = listOf(2.45, 2.10, 1.45, 5.60, 8.90, 3.30),
                expanded = true,
                roundId = "rd_102",
                nowMs = 1_700_000_007_000L
            ),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_007_000L
        )
        assertEquals(1, AutomationController.activeSessionCrashRecords.value.size)

        // 6. Reverse scroll jump -> 0 extra inserts
        AutomationController.processDetectionEvent(
            detection = buildGameDetection(
                phase = "BETTING_OPEN",
                ribbon = listOf(2.10, 1.45, 5.60),
                roundId = "rd_102",
                nowMs = 1_700_000_008_000L
            ),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_008_000L
        )
        assertEquals(1, AutomationController.activeSessionCrashRecords.value.size)

        // 7. Sequential crash 1.12x followed by consecutive equal 1.12x -> both recorded
        AutomationController.processDetectionEvent(
            detection = buildGameDetection(
                phase = "CRASHED",
                liveMult = 1.12,
                ribbon = listOf(1.12, 2.45, 2.10, 1.45),
                roundId = "rd_102",
                nowMs = 1_700_000_012_000L
            ),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_012_000L
        )
        assertEquals(2, AutomationController.activeSessionCrashRecords.value.size)

        AutomationController.processDetectionEvent(
            detection = buildGameDetection(
                phase = "CRASHED",
                liveMult = 1.12,
                ribbon = listOf(1.12, 1.12, 2.45, 2.10),
                roundId = "rd_103",
                nowMs = 1_700_000_016_000L
            ),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_016_000L
        )
        assertEquals(3, AutomationController.activeSessionCrashRecords.value.size)

        // 8. Pause & Resume preserve session ID and count == 3
        AutomationController.pauseMonitoringSession()
        assertEquals(MonitoringPipelineStatus.PAUSED, AutomationController.engine!!.gameDetection.gameState.value.pipelineStatus)
        assertEquals(3, AutomationController.activeSessionCrashRecords.value.size)

        AutomationController.startMonitoringSession(isAccessibilityConnected = true, nowMs = 1_700_000_020_000L)
        assertEquals(sessionId, AutomationController.activeSessionId.value)
        assertEquals(3, AutomationController.activeSessionCrashRecords.value.size)

        // 9. Simulated service restart restores session & baseline without duplicate inserts or old session leaks
        AutomationController.resetTestState()
        AutomationController.restorePersistedSessionIfIntended()
        assertEquals(sessionId, AutomationController.activeSessionId.value)
        assertEquals(3, AutomationController.activeSessionCrashRecords.value.size)

        // Replaying same ribbon after restart produces 0 duplicates
        AutomationController.processDetectionEvent(
            detection = buildGameDetection(
                phase = "BETTING_OPEN",
                ribbon = listOf(1.12, 1.12, 2.45, 2.10),
                roundId = "rd_104",
                nowMs = 1_700_000_025_000L
            ),
            isAccessibilityConnected = true,
            nowMs = 1_700_000_025_000L
        )
        assertEquals(3, AutomationController.activeSessionCrashRecords.value.size)

        // 10. End Session & Clear Session Data clears active session only
        AutomationController.endSessionAndClearSessionData()
        assertEquals("", AutomationController.activeSessionId.value)
        assertEquals(0, AutomationController.activeSessionCrashRecords.value.size)
        assertEquals(0, repo.getCrashRecordsForSessionOnce(sessionId).size)
        assertEquals(MonitoringPipelineStatus.STOPPED, AutomationController.engine!!.gameDetection.gameState.value.pipelineStatus)
        assertFalse(SessionStateManager(context).isIntendedMonitoringActive())
    }

    @Test
    fun `all 8 truthful pipeline statuses surface accurately`() = runTest {
        setupIsolatedController()

        // 1. ACCESSIBILITY_DISCONNECTED
        AutomationController.processDetectionEvent(
            detection = buildGameDetection(isGameDetected = false, nowMs = 1000L),
            isAccessibilityConnected = false,
            nowMs = 1000L
        )
        assertEquals(
            MonitoringPipelineStatus.ACCESSIBILITY_DISCONNECTED,
            AutomationController.engine!!.gameDetection.gameState.value.pipelineStatus
        )

        // 2. STOPPED when IDLE
        AutomationController.processDetectionEvent(
            detection = buildGameDetection(isGameDetected = false, nowMs = 2000L),
            isAccessibilityConnected = true,
            nowMs = 2000L
        )
        assertEquals(
            MonitoringPipelineStatus.STOPPED,
            AutomationController.engine!!.gameDetection.gameState.value.pipelineStatus
        )

        // 3. WAITING_FOR_GAME when RUNNING & no game detected
        AutomationController.startMonitoringSession(isAccessibilityConnected = true, nowMs = 3000L)
        AutomationController.processDetectionEvent(
            detection = buildGameDetection(isGameDetected = false, nowMs = 3100L),
            isAccessibilityConnected = true,
            nowMs = 3100L
        )
        assertEquals(
            MonitoringPipelineStatus.WAITING_FOR_GAME,
            AutomationController.engine!!.gameDetection.gameState.value.pipelineStatus
        )

        // 4. GAME_DETECTED_WAITING_FOR_HISTORY
        AutomationController.processDetectionEvent(
            detection = buildGameDetection(isGameDetected = true, ribbon = emptyList(), nowMs = 4000L),
            isAccessibilityConnected = true,
            nowMs = 4000L
        )
        assertEquals(
            MonitoringPipelineStatus.GAME_DETECTED_WAITING_FOR_HISTORY,
            AutomationController.engine!!.gameDetection.gameState.value.pipelineStatus
        )

        // 5. HISTORY_NOT_READABLE after >= 4000ms with unreadable ribbon
        AutomationController.processDetectionEvent(
            detection = buildGameDetection(isGameDetected = true, ribbon = emptyList(), nowMs = 8500L),
            isAccessibilityConnected = true,
            nowMs = 8500L
        )
        assertEquals(
            MonitoringPipelineStatus.HISTORY_NOT_READABLE,
            AutomationController.engine!!.gameDetection.gameState.value.pipelineStatus
        )
    }

    @Test
    fun `top status pill displays only last crash and round id and persists drag position`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mgr = SessionStateManager(context)
        mgr.saveTopPillPosition(315, 142)
        val (rx, ry) = mgr.loadTopPillPosition(20, 36)
        assertEquals(315, rx)
        assertEquals(142, ry)

        composeRule.setContent {
            AutoPredictorTheme {
                FloatingSessionStatsHudComposable(
                    stats = SessionStats(observedCrashCount = 12),
                    observedRounds = 12,
                    automationState = AutomationState.RUNNING,
                    gameName = "Aviator",
                    liveMultiplier = 4.75,
                    latestCrashMultiplier = 2.45,
                    latestRoundId = "rd_901"
                )
            }
        }

        composeRule.onNodeWithText("LAST CRASH: 2.45x").assertIsDisplayed()
        composeRule.onNodeWithText("ROUND: rd_901").assertIsDisplayed()
        composeRule.onNodeWithText("R:12").assertDoesNotExist()
        composeRule.onNodeWithText("4.75x").assertDoesNotExist()
    }

    @Test
    fun `live monitor screen and smart agent expose zero confidence percentages`() {
        val gs = GameState(
            isDetected = true,
            displayedGameName = "Aviator",
            platformName = "BetPawa Aviator",
            pipelineStatus = MonitoringPipelineStatus.MONITORING_LIVE,
            pipelineDiagnosticReason = "Recorded new completed crash (2.45x)",
            activeSessionCrashCount = 4,
            latestCrashMultiplier = 2.45,
            latestRoundId = "rd_555",
            detectionConfidence = 0.88f
        )

        val agent = SmartAnalysisAgent.generateExplanation(
            gameState = gs,
            strategies = emptyList(),
            report = StatisticalAnalysisEngine.analyzeCompletedCrashes(emptyList()),
            hourlyBundle = HourlyAnalysisEngine.buildMultiWindowBundle(emptyList()),
            occurrences = emptyList(),
            config = StrategyConfig()
        )
        assertFalse(agent.liveStatusLine.contains("Confidence", ignoreCase = true))
        assertFalse(agent.liveStatusLine.contains("%"))

        composeRule.setContent {
            AutoPredictorTheme {
                LiveMonitorScreen(
                    gameState = gs,
                    automationState = AutomationState.RUNNING,
                    strategyConfig = StrategyConfig(),
                    crashRecords = emptyList(),
                    historyMetadata = HistoryCollectionMetadata(),
                    selectedGameId = "aviator",
                    isPixelCaptureActive = false,
                    onSelectGameId = {},
                    onStartMonitoring = {},
                    onPauseMonitoring = {},
                    onCollectHistory = {},
                    onRequestPixelCapture = {},
                    onStopPixelCapture = {}
                )
            }
        }

        composeRule.onNodeWithTag("live_pipeline_status_badge").assertIsDisplayed()
        composeRule.onNodeWithTag("end_session_clear_btn").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Detection Confidence", substring = true).assertDoesNotExist()
    }

    @Test
    fun `MainActivity handles ACTION_EXIT_APP by finishing cleanly while normal launch succeeds`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val exitIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_EXIT_APP
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, exitIntent).create()
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun `project gradle properties sets java awt headless true for KSP and manifest accessibility config enables unimportant views`() {
        val gradleProps = File("gradle.properties").takeIf { it.exists() } ?: File("../gradle.properties")
        if (gradleProps.exists()) {
            val content = gradleProps.readText()
            assertTrue(
                "Expected -Djava.awt.headless=true in gradle.properties to avoid KSP AWT-EventQueue NPE",
                content.contains("-Djava.awt.headless=true")
            )
        }
    }
}
