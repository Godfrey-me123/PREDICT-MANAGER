package com.example

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.ScreenEarnerDatabase
import com.example.data.database.ScreenEarnerRepository
import com.example.data.engine.AutomationState
import com.example.data.engine.GameAdapterRegistry
import com.example.data.engine.GameDetection
import com.example.data.engine.HourlyAnalysisEngine
import com.example.data.engine.OperatingScheduleEvaluator
import com.example.data.engine.PatternDetectionEngine
import com.example.data.engine.SmartAnalysisAgent
import com.example.data.engine.StatisticalAnalysisEngine
import com.example.data.model.CalendarHourUtils
import com.example.data.model.ColorClassifier
import com.example.data.model.CrashColorCategory
import com.example.data.model.CrashRecord
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.NotificationDeliveryStatus
import com.example.data.model.NotificationPriorityLevel
import com.example.data.model.NotificationSettings
import com.example.data.model.PatternComparator
import com.example.data.model.PatternCondition
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyMode
import com.example.data.model.StrategyNotificationType
import com.example.service.AndroidSystemNotificationDispatcher
import com.example.service.AccessibilityController
import com.example.service.DetectedUiTargets
import com.example.service.GameDetectionState
import com.example.service.StrategyNotificationManager
import com.example.service.SystemNotificationDispatcher
import com.example.ui.components.BoundedCrashHistoryGrid
import com.example.ui.theme.AutoPredictorTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutoPredictorTest {

    @get:Rule
    val composeRule = createComposeRule()

    // =========================================================================
    // PROMPT 2: Universal GameAdapter Registry + LIVE_DETECTION_LOST
    // =========================================================================

    @Test
    fun `game adapter registry detects Aviator, JetX, Spaceman, and emits LIVE_DETECTION_LOST`() {
        val aviator = GameAdapterRegistry.resolveAdapter(
            packageName = "com.android.chrome",
            url = "https://www.sportybet.com/tz/m/games/spribe/aviator",
            pageHints = emptyList(),
            rawTexts = listOf("Round History", "1.53x", "2.04x")
        )
        assertNotNull(aviator)
        assertEquals("aviator", aviator!!.gameId)
        assertEquals("Aviator", aviator.displayedGameName)

        val jetX = GameAdapterRegistry.resolveAdapter(
            packageName = "com.android.chrome",
            url = "https://example.com/smartsoft/jetx",
            pageHints = listOf("JetX"),
            rawTexts = listOf("JetX")
        )
        assertNotNull(jetX)
        assertEquals("jetx", jetX!!.gameId)
        assertEquals("JetX", jetX.displayedGameName)

        val spaceman = GameAdapterRegistry.resolveAdapter(
            packageName = "com.android.chrome",
            url = "https://example.com/pragmatic/spaceman",
            pageHints = listOf("Spaceman"),
            rawTexts = listOf("Spaceman")
        )
        assertNotNull(spaceman)
        assertEquals("spaceman", spaceman!!.gameId)
        assertEquals("Spaceman", spaceman.displayedGameName)

        val lostPhase = com.example.data.engine.AviatorGameAdapter().detectRoundState(
            targets = DetectedUiTargets(detectedMultiplier = null, ribbonItems = emptyList(), gamePhase = "WAITING"),
            hasActiveGameBefore = true,
            nowMs = 22_000L,
            lastValidDetectionMs = 5_000L
        )
        assertEquals("LIVE_DETECTION_LOST", lostPhase)

        val detection = GameDetection()
        detection.updateFromAccessibilityDetection(
            detection = GameDetectionState(
                isGameDetected = true,
                gameId = "aviator",
                displayedGameName = "Aviator",
                platformName = "SportyBet Aviator",
                gamePhase = "IN_FLIGHT",
                detectedMultiplier = 2.15
            ),
            onRealRoundCompleted = {},
            currentTimeMs = 10_000L
        )
        assertFalse(detection.gameState.value.liveDetectionLost)

        detection.updateFromAccessibilityDetection(
            detection = GameDetectionState(
                isGameDetected = true,
                gameId = "aviator",
                displayedGameName = "Aviator",
                platformName = "SportyBet Aviator",
                gamePhase = lostPhase,
                liveDetectionLost = true,
                detectedMultiplier = null
            ),
            onRealRoundCompleted = {},
            currentTimeMs = 22_000L
        )
        assertTrue(detection.gameState.value.liveDetectionLost)
        assertEquals("LIVE_DETECTION_LOST", detection.gameState.value.status)
    }

    // =========================================================================
    // SCREENSHOT BUG FIX: 8-column Round History clusters row-by-row capturing 1.53x at index 0
    // =========================================================================

    @Test
    fun `ribbon clustering captures first top-left multiplier 1_53x before 2_04x on expanded Round History grid`() {
        val candidates = listOf(
            // Intentionally out-of-order accessibility tree scan (e.g. slight Y jitter on 1.53x vs 2.04x)
            com.example.service.MultiplierCandidate(2.04, Rect(160, 298, 250, 340), orderIndex = 0),
            com.example.service.MultiplierCandidate(1.53, Rect(40, 303, 130, 345), orderIndex = 1),
            com.example.service.MultiplierCandidate(1.94, Rect(280, 300, 370, 342), orderIndex = 2),
            com.example.service.MultiplierCandidate(3.39, Rect(400, 299, 490, 341), orderIndex = 3),
            // Row 2
            com.example.service.MultiplierCandidate(2.00, Rect(40, 390, 130, 432), orderIndex = 4),
            com.example.service.MultiplierCandidate(2.34, Rect(160, 392, 250, 434), orderIndex = 5)
        )

        val (ribbonList, _) = AccessibilityController.extractRibbonAndCenterMultipliers(
            candidates = candidates,
            viewportHeightPx = 2400
        )
        assertEquals(
            listOf(1.53, 2.04, 1.94, 3.39, 2.00, 2.34),
            ribbonList
        )
    }

    // =========================================================================
    // PROMPT 3: HEX Color Classification & Manual Correction Audit in Room v10
    // =========================================================================

    @Test
    fun `color classifier and Room manual crash correction preserve audit record`() = runTest {
        assertEquals(CrashColorCategory.Blue, ColorClassifier.classifyHex("#2563EB"))
        assertEquals(CrashColorCategory.Purple, ColorClassifier.classifyHex("#7C3AED"))
        assertEquals(CrashColorCategory.Pink, ColorClassifier.classifyHex("#EC4899"))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ScreenEarnerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = ScreenEarnerRepository(db.dao())
            val t0 = 1_700_000_000_000L
            repo.insertCrashRecords(
                listOf(
                    CrashRecord(
                        gameId = "aviator",
                        gameName = "Aviator",
                        multiplier = 1.94,
                        detectedColorHex = "#2563EB",
                        colorCategory = CrashColorCategory.Blue,
                        isPixelColorVerified = false,
                        crashTimestamp = t0,
                        hourStartTimestamp = CalendarHourUtils.hourStartFor(t0),
                        dedupKey = "aviator_fix_1"
                    )
                )
            )
            val inserted = repo.crashRecords.first().first()
            val ok = repo.applyManualCrashCorrection(
                recordId = inserted.id,
                newMultiplier = 2.04,
                newColorHex = CrashColorCategory.Purple.defaultHex,
                newCategory = CrashColorCategory.Purple,
                reason = "OCR digit fix"
            )
            assertTrue(ok)

            val updated = repo.crashRecords.first().first()
            assertEquals(2.04, updated.multiplier, 0.001)
            assertEquals(CrashColorCategory.Purple, updated.colorCategory)
            assertTrue(updated.manuallyCorrected)

            val audits = repo.crashCorrectionAudits.first()
            assertEquals(1, audits.size)
            assertEquals(1.94, audits.first().originalMultiplier, 0.001)
            assertEquals(2.04, audits.first().correctedMultiplier, 0.001)
            assertEquals("#2563EB", audits.first().originalColorHex)
        } finally {
            db.close()
        }
    }

    // =========================================================================
    // PROMPT 4: BoundedCrashHistoryGrid Dimensions (4x7, 5x8)
    // =========================================================================

    @Test
    fun `bounded crash history grid renders strictly up to rows x columns cells`() {
        val now = 1_700_000_000_000L
        val records = (1..50).map { i ->
            CrashRecord(
                id = i.toLong(),
                gameId = "aviator",
                gameName = "Aviator",
                multiplier = 1.00 + i * 0.1,
                detectedColorHex = if (i * 0.1 + 1.0 < 2.0) "#2563EB" else "#7C3AED",
                colorCategory = if (i * 0.1 + 1.0 < 2.0) CrashColorCategory.Blue else CrashColorCategory.Purple,
                crashTimestamp = now + i * 1_000L,
                hourStartTimestamp = CalendarHourUtils.hourStartFor(now),
                dedupKey = "grid_$i"
            )
        }

        val rowsState = androidx.compose.runtime.mutableStateOf(4)
        val colsState = androidx.compose.runtime.mutableStateOf(7)

        composeRule.setContent {
            AutoPredictorTheme {
                BoundedCrashHistoryGrid(
                    crashRecords = records,
                    gridRows = rowsState.value,
                    gridColumns = colsState.value
                )
            }
        }

        // 4 x 7 = 28 newest cells (records 50 down to 23); record 50 (6.00x) present, record 10 (2.00x) excluded
        composeRule.onNodeWithTag("bounded_crash_history_grid").assertExists()
        composeRule.onNodeWithText("6.00x").assertExists()
        composeRule.onAllNodesWithText("2.00x").assertCountEquals(0)

        rowsState.value = 5
        colsState.value = 8
        composeRule.waitForIdle()
        // 5 x 8 = 40 newest cells (50 down to 11); record 11 (2.10x) now included
        composeRule.onNodeWithText("2.10x").assertExists()
    }

    // =========================================================================
    // PROMPT 5: StatisticalAnalysisEngine (strict consecutive lows, gaps, motifs)
    // =========================================================================

    @Test
    fun `statistical engine enforces strict consecutive chains and detects big intervals and 3-color motifs`() {
        val base = 1_700_000_000_000L
        val hourStart = CalendarHourUtils.hourStartFor(base)

        // 14 consecutive lows followed by 2.50x break, then Big1 (12x) -> 7 mins -> Big2 (15x), then alternating & Purple->Purple->Blue twice
        val mults = mutableListOf<Pair<Double, CrashColorCategory>>()
        repeat(14) { mults.add(1.30 to CrashColorCategory.Blue) }
        mults.add(2.50 to CrashColorCategory.Purple)
        mults.add(1.20 to CrashColorCategory.Blue)
        mults.add(12.00 to CrashColorCategory.Pink) // big #1
        mults.add(2.50 to CrashColorCategory.Purple)
        mults.add(1.40 to CrashColorCategory.Blue)
        mults.add(15.00 to CrashColorCategory.Pink) // big #2 (7 mins later)
        mults.add(2.30 to CrashColorCategory.Purple)
        mults.add(2.40 to CrashColorCategory.Purple)
        mults.add(1.10 to CrashColorCategory.Blue)
        mults.add(2.60 to CrashColorCategory.Purple)
        mults.add(2.70 to CrashColorCategory.Purple)
        mults.add(1.15 to CrashColorCategory.Blue)

        val records = mults.mapIndexed { idx, (m, cat) ->
            // place Big #1 at base + 2m and Big #2 at base + 9m -> 7.0 min gap
            val ts = when (idx) {
                16 -> base + 120_000L
                19 -> base + 540_000L
                in 17..18 -> base + 150_000L + idx * 1_000L
                in 20..mults.size -> base + 550_000L + idx * 1_000L
                else -> base + idx * 5_000L
            }
            CrashRecord(
                id = idx.toLong() + 1L,
                gameId = "aviator",
                gameName = "Aviator",
                multiplier = m,
                detectedColorHex = cat.defaultHex,
                colorCategory = cat,
                crashTimestamp = ts,
                hourStartTimestamp = hourStart,
                dedupKey = "stat_$idx"
            )
        }

        val report = StatisticalAnalysisEngine.analyzeCompletedCrashes(
            records = records,
            config = StrategyConfig(lowThreshold = 2.00, boundaryValue = 2.00, bigThreshold = 10.00),
            gameId = "aviator",
            gameName = "Aviator"
        )
        assertEquals(records.size, report.totalCompletedRounds)
        assertEquals(14, report.longestConsecutiveLows)
        assertEquals(2, report.bigMultiplierCount)
        assertEquals(1, report.bigMultiplierGaps.size)
        assertEquals(7.0, report.bigMultiplierGaps.first().gapMinutes, 0.1)
        assertTrue(report.bigMultiplierGaps.first().isWithinQualifyingRange)
        assertTrue(report.topRepeatedColorSequences.any { (motif, count) -> motif == "Purple->Purple->Blue" && count >= 2 })
    }

    // =========================================================================
    // PROMPT 6: HourlyAnalysisEngine + Hour Boundary Archive + INSUFFICIENT DATA
    // =========================================================================

    @Test
    fun `hourly analysis engine separates windows, flags INSUFFICIENT DATA, and finalizes closing hour`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ScreenEarnerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = ScreenEarnerRepository(db.dao())
            val cal = Calendar.getInstance().apply {
                set(2026, Calendar.MAY, 10, 14, 45, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val now14 = cal.timeInMillis
            val hour14Start = CalendarHourUtils.hourStartFor(now14)
            val hour13Start = hour14Start - 3_600_000L

            // Only 3 rounds in Hour 13 -> INSUFFICIENT DATA; 6 rounds in Hour 14 -> SUFFICIENT
            val items = listOf(
                CrashRecord(gameId = "aviator", gameName = "Aviator", multiplier = 1.20, detectedColorHex = "#2563EB", colorCategory = CrashColorCategory.Blue, crashTimestamp = hour13Start + 600_000L, hourStartTimestamp = hour13Start, dedupKey = "h13_1"),
                CrashRecord(gameId = "aviator", gameName = "Aviator", multiplier = 1.50, detectedColorHex = "#2563EB", colorCategory = CrashColorCategory.Blue, crashTimestamp = hour13Start + 1_200_000L, hourStartTimestamp = hour13Start, dedupKey = "h13_2"),
                CrashRecord(gameId = "aviator", gameName = "Aviator", multiplier = 12.0, detectedColorHex = "#EC4899", colorCategory = CrashColorCategory.Pink, crashTimestamp = hour13Start + 1_800_000L, hourStartTimestamp = hour13Start, dedupKey = "h13_3"),
                CrashRecord(gameId = "aviator", gameName = "Aviator", multiplier = 1.10, detectedColorHex = "#2563EB", colorCategory = CrashColorCategory.Blue, crashTimestamp = now14 - 20 * 60_000L, hourStartTimestamp = hour14Start, dedupKey = "h14_1"),
                CrashRecord(gameId = "aviator", gameName = "Aviator", multiplier = 1.30, detectedColorHex = "#2563EB", colorCategory = CrashColorCategory.Blue, crashTimestamp = now14 - 18 * 60_000L, hourStartTimestamp = hour14Start, dedupKey = "h14_2"),
                CrashRecord(gameId = "aviator", gameName = "Aviator", multiplier = 2.00, detectedColorHex = "#7C3AED", colorCategory = CrashColorCategory.Purple, crashTimestamp = now14 - 10 * 60_000L, hourStartTimestamp = hour14Start, dedupKey = "h14_3"),
                CrashRecord(gameId = "aviator", gameName = "Aviator", multiplier = 3.50, detectedColorHex = "#7C3AED", colorCategory = CrashColorCategory.Purple, crashTimestamp = now14 - 8 * 60_000L, hourStartTimestamp = hour14Start, dedupKey = "h14_4"),
                CrashRecord(gameId = "aviator", gameName = "Aviator", multiplier = 15.0, detectedColorHex = "#EC4899", colorCategory = CrashColorCategory.Pink, crashTimestamp = now14 - 4 * 60_000L, hourStartTimestamp = hour14Start, dedupKey = "h14_5"),
                CrashRecord(gameId = "aviator", gameName = "Aviator", multiplier = 1.90, detectedColorHex = "#2563EB", colorCategory = CrashColorCategory.Blue, crashTimestamp = now14 - 1 * 60_000L, hourStartTimestamp = hour14Start, dedupKey = "h14_6")
            )
            repo.insertCrashRecords(items)

            val bundle = HourlyAnalysisEngine.buildMultiWindowBundle(
                allRecords = items,
                gameId = "aviator",
                gameName = "Aviator",
                nowMs = now14
            )
            assertTrue(bundle.previousHour.isInsufficientData)
            assertFalse(bundle.currentHour.isInsufficientData)
            assertEquals(6, bundle.currentHour.totalRounds)
            assertEquals(6, bundle.rolling30m.totalRounds)
            assertEquals(1, bundle.currentHour.boundaryCount)

            val transition = HourlyAnalysisEngine.finalizeClosingHourSummaries(
                allRecords = items,
                closingHourStartMs = hour14Start,
                newHourStartMs = hour14Start + 3_600_000L,
                finalizedAtMs = hour14Start + 3_600_000L
            )
            transition.savedSummaries.forEach { repo.upsertHourlySummary(it) }
            assertEquals(2, transition.savedSummaries.size) // overall + aviator
            assertEquals(6, transition.savedSummaries.first { it.gameId == "aviator" }.totalRounds)

            val persistedSummaries = repo.hourlySummaries.first()
            assertEquals(2, persistedSummaries.size)
        } finally {
            db.close()
        }
    }

    // =========================================================================
    // PROMPT 7 & 9: Strategy A/B/C Evaluation & Smart Analysis Agent
    // =========================================================================

    @Test
    fun `strategy A B C detect patterns and SmartAnalysisAgent uses non-predictive factual copy`() {
        val base = 1_700_000_000_000L
        val hourStart = CalendarHourUtils.hourStartFor(base)
        val stratA = CustomPatternStrategy(
            id = 101L,
            name = "Strategy A 3-Low",
            strategyMode = StrategyMode.STRATEGY_A_CONSECUTIVE_LOWS,
            requiredOccurrencesPerHour = 2
        ).withConditions(listOf(PatternCondition(PatternComparator.LESS_THAN, 2.00, consecutiveRounds = 3)))

        val stratC = CustomPatternStrategy(
            id = 102L,
            name = "Strategy C P-P-B",
            strategyMode = StrategyMode.STRATEGY_C_COLOR_PATTERNS,
            colorSequenceCsv = "Purple,Purple,Blue"
        )

        val records = listOf(
            CrashRecord(id = 1, gameId = "aviator", gameName = "Aviator", multiplier = 2.40, detectedColorHex = "#7C3AED", colorCategory = CrashColorCategory.Purple, crashTimestamp = base + 1_000L, hourStartTimestamp = hourStart, dedupKey = "sc_1"),
            CrashRecord(id = 2, gameId = "aviator", gameName = "Aviator", multiplier = 3.10, detectedColorHex = "#7C3AED", colorCategory = CrashColorCategory.Purple, crashTimestamp = base + 2_000L, hourStartTimestamp = hourStart, dedupKey = "sc_2"),
            CrashRecord(id = 3, gameId = "aviator", gameName = "Aviator", multiplier = 1.20, detectedColorHex = "#2563EB", colorCategory = CrashColorCategory.Blue, crashTimestamp = base + 3_000L, hourStartTimestamp = hourStart, dedupKey = "sc_3"),
            CrashRecord(id = 4, gameId = "aviator", gameName = "Aviator", multiplier = 1.30, detectedColorHex = "#2563EB", colorCategory = CrashColorCategory.Blue, crashTimestamp = base + 4_000L, hourStartTimestamp = hourStart, dedupKey = "sc_4"),
            CrashRecord(id = 5, gameId = "aviator", gameName = "Aviator", multiplier = 1.40, detectedColorHex = "#2563EB", colorCategory = CrashColorCategory.Blue, crashTimestamp = base + 5_000L, hourStartTimestamp = hourStart, dedupKey = "sc_5")
        )

        val engine = PatternDetectionEngine()
        // Step at record 3 triggers Strategy C (Purple -> Purple -> Blue)
        val stepC = engine.evaluateStrategiesOnCrashRecords(listOf(stratC), records.take(3), nowMs = base + 3_000L)
        assertTrue(stepC.newOccurrences.isNotEmpty())

        // Step through 5 records triggers Strategy A (3 consecutive lows)
        val stepA = engine.evaluateStrategiesOnCrashRecords(listOf(stratA), records, nowMs = base + 5_000L)
        assertEquals(1, stepA.newOccurrences.size)

        val bundle = HourlyAnalysisEngine.buildMultiWindowBundle(
            allRecords = records,
            gameId = "aviator",
            gameName = "Aviator",
            nowMs = base + 6_000L
        )
        val report = StatisticalAnalysisEngine.analyzeCompletedCrashes(
            records = records,
            gameId = "aviator",
            gameName = "Aviator"
        )
        val explanation = SmartAnalysisAgent.generateExplanation(
            gameState = com.example.data.model.GameState(isDetected = true, gameId = "aviator", displayedGameName = "Aviator"),
            strategies = listOf(stratA, stratC),
            report = report,
            hourlyBundle = bundle,
            occurrences = stepA.newOccurrences + stepC.newOccurrences,
            config = StrategyConfig(),
            nowMs = base + 6_000L
        )
        assertFalse(explanation.liveStatusLine.contains("guaranteed", ignoreCase = true))
        assertTrue(explanation.integrityDisclaimer.contains("never guarantee", ignoreCase = true))
        assertTrue(explanation.strategyBreakdowns.size == 2)
        assertTrue(explanation.strategyBreakdowns.first().hourlyProgressText.contains("1 of 2"))
    }

    // =========================================================================
    // PROMPT 8 & 10: Notification Channels, DND Blocking & Overnight Schedule
    // =========================================================================

    @Test
    fun `overnight operating schedule evaluates standby correctly and notification channels enforce DND`() = runTest {
        val cal23 = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 30)
        }.timeInMillis
        val cal10 = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
        }.timeInMillis

        // 22:00 -> 04:00 overnight window
        assertTrue(OperatingScheduleEvaluator.isWithinOperatingHours(true, 22, 0, 4, 0, cal23))
        assertFalse(OperatingScheduleEvaluator.isWithinOperatingHours(true, 22, 0, 4, 0, cal10))

        assertEquals(
            AndroidSystemNotificationDispatcher.CHANNEL_HOURLY_ANALYSIS,
            AndroidSystemNotificationDispatcher.resolveChannelId(StrategyNotificationType.NEW_HOUR_STARTED)
        )
        assertEquals(
            AndroidSystemNotificationDispatcher.CHANNEL_GAME_DETECTION,
            AndroidSystemNotificationDispatcher.resolveChannelId(StrategyNotificationType.GAME_DETECTED)
        )
        assertEquals(
            AndroidSystemNotificationDispatcher.CHANNEL_MONITORING_ERRORS,
            AndroidSystemNotificationDispatcher.resolveChannelId(StrategyNotificationType.MONITORING_ERROR)
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ScreenEarnerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = ScreenEarnerRepository(db.dao())
            val dndBlockingDispatcher = object : SystemNotificationDispatcher {
                override fun hasNotificationPermission(context: Context): Boolean = true
                override fun isDoNotDisturbBlocking(context: Context, priorityLevel: NotificationPriorityLevel): Boolean = true
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
            val mgr = StrategyNotificationManager(context, repo, dndBlockingDispatcher)
            val result = mgr.dispatchHourlyBoundaryNotice(
                gameId = "aviator",
                gameName = "Aviator",
                closedHourLabel = "13:00–13:59",
                newHourLabel = "14:00–14:59",
                summaryLine = "Finalized",
                settings = NotificationSettings(),
                nowMs = cal23
            )
            assertEquals(NotificationDeliveryStatus.SUPPRESSED_QUIET_HOURS, result.deliveryStatus)
            assertEquals(AndroidSystemNotificationDispatcher.CHANNEL_HOURLY_ANALYSIS, result.channelId)
        } finally {
            db.close()
        }
    }
}
