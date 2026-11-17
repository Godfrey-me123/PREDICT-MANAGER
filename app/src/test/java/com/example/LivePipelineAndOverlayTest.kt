package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.ScreenEarnerDatabase
import com.example.data.database.ScreenEarnerRepository
import com.example.data.engine.AutomationState
import com.example.data.engine.ConfirmedCrashEmission
import com.example.data.engine.LiveCrashPipeline
import com.example.data.model.CalendarHourUtils
import com.example.data.model.CrashColorCategory
import com.example.data.model.CrashRecord
import com.example.data.model.GameState
import com.example.data.model.MonitoringStatus
import com.example.data.model.NotificationSettings
import com.example.data.model.RoundLifecycleState
import com.example.data.model.StrategyConfig
import com.example.service.AlarmAudioController
import com.example.service.AlarmStopReceiver
import com.example.service.AndroidSystemNotificationDispatcher
import com.example.service.AutomationController
import com.example.service.DetectedUiTargets
import com.example.service.GameDetectionState
import com.example.service.OverlaySettingsPanelComposable
import com.example.service.ScreenEarnerOverlayService
import com.example.ui.components.LiveCrashHeaderCard
import com.example.ui.screens.SettingsAppearanceScreen
import com.example.ui.theme.AutoPredictorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LivePipelineAndOverlayTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AlarmAudioController.stopAlarm()
        AlarmAudioController.clearSnooze()
    }

    @After
    fun tearDown() {
        AlarmAudioController.stopAlarm()
        AlarmAudioController.clearSnooze()
        AutomationController.endSessionAndClearData(app)
    }

    private fun fakeDetection(
        phase: String,
        multiplier: Double?,
        roundId: String? = null,
        historyExpanded: Boolean = false,
        windowAccessStatus: MonitoringStatus = MonitoringStatus.MONITORING,
        liveDetectionLost: Boolean = false,
        gameWindowSuspended: Boolean = false,
        updatedAtMs: Long = System.currentTimeMillis()
    ): GameDetectionState {
        return GameDetectionState(
            isGameDetected = !liveDetectionLost && !gameWindowSuspended,
            gameId = "aviator",
            displayedGameName = "Aviator",
            platformName = "BetPawa Aviator",
            isReady = true,
            gamePhase = phase,
            detectedMultiplier = multiplier,
            liveColorHex = null,
            isLiveColorPixelVerified = false,
            liveDetectionLost = liveDetectionLost,
            detectionConfidence = 0f,
            foregroundPackage = "com.android.chrome",
            uiTargets = DetectedUiTargets(
                detectedMultiplier = multiplier,
                historyPanelExpanded = historyExpanded,
                gamePhase = phase,
                isFlewAwayDetected = phase == "CRASHED",
                roundId = roundId
            ),
            roundId = roundId,
            updatedAtMs = updatedAtMs,
            windowAccessStatus = windowAccessStatus,
            gameWindowAccessible = windowAccessStatus == MonitoringStatus.MONITORING,
            gameWindowSuspended = gameWindowSuspended
        )
    }

    @Test
    fun `flow debounce collapses rapid repeated CRASHED events to single confirmed emission`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val emissions = mutableListOf<ConfirmedCrashEmission>()
        try {
            val pipeline = LiveCrashPipeline(scope, debounceWindowMs = 60L) {
                emissions.add(it)
            }
            pipeline.configureSession("sess_debounce_1")

            pipeline.onDetectionFrame(fakeDetection("BETTING_OPEN", null, roundId = "R-100"), MonitoringStatus.MONITORING)
            pipeline.onDetectionFrame(fakeDetection("IN_FLIGHT", 1.25, roundId = "R-100"), MonitoringStatus.MONITORING)
            pipeline.onDetectionFrame(fakeDetection("IN_FLIGHT", 2.40, roundId = "R-100"), MonitoringStatus.MONITORING)

            // No emissions during rising phase
            delay(90L)
            assertEquals(0, emissions.size)
            assertEquals(RoundLifecycleState.MULTIPLIER_RISING, pipeline.lifecycleState.value)

            // Burst of repeated CRASHED accessibility callbacks for the exact same round
            pipeline.onDetectionFrame(fakeDetection("CRASHED", 3.18, roundId = "R-100"), MonitoringStatus.MONITORING)
            delay(20L)
            pipeline.onDetectionFrame(fakeDetection("CRASHED", 3.18, roundId = "R-100"), MonitoringStatus.MONITORING)
            delay(20L)
            pipeline.onDetectionFrame(fakeDetection("CRASHED", 3.18, roundId = "R-100"), MonitoringStatus.MONITORING)

            // Wait past debounce window
            delay(130L)
            assertEquals(1, emissions.size)
            assertEquals(3.18, emissions.first().multiplier, 0.001)
            assertEquals(RoundLifecycleState.ROUND_RECORDED, pipeline.lifecycleState.value)

            // Late duplicate callback for the same round is ignored
            pipeline.onDetectionFrame(fakeDetection("CRASHED", 3.18, roundId = "R-100"), MonitoringStatus.MONITORING)
            delay(100L)
            assertEquals(1, emissions.size)
            assertTrue(pipeline.diagnostics.value.duplicatesIgnoredCount >= 1)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `historyPanelExpanded classifies as HISTORY_ONLY and never records crashes`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val emissions = mutableListOf<ConfirmedCrashEmission>()
        try {
            val pipeline = LiveCrashPipeline(scope, debounceWindowMs = 40L) {
                emissions.add(it)
            }
            pipeline.configureSession("sess_hist_only")

            // User opens Round History modal displaying historical crashed multipliers
            pipeline.onDetectionFrame(
                fakeDetection("CRASHED", 12.50, roundId = "OLD-9", historyExpanded = true),
                MonitoringStatus.MONITORING
            )
            pipeline.onDetectionFrame(
                fakeDetection("CRASHED", 1.05, roundId = "OLD-8", historyExpanded = true),
                MonitoringStatus.MONITORING
            )
            delay(100L)

            assertEquals(0, emissions.size)
            assertTrue(pipeline.diagnostics.value.historyBlocksCount >= 2)
            assertTrue(pipeline.diagnostics.value.lastEventSummary.contains("HISTORY_ONLY"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `back to back legitimate identical multipliers across distinct rounds are both recorded`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val emissions = mutableListOf<ConfirmedCrashEmission>()
        try {
            val pipeline = LiveCrashPipeline(scope, debounceWindowMs = 35L) {
                emissions.add(it)
            }
            pipeline.configureSession("sess_same_mult")

            // Round 1 -> 2.10x
            pipeline.onDetectionFrame(fakeDetection("IN_FLIGHT", 1.50), MonitoringStatus.MONITORING)
            pipeline.onDetectionFrame(fakeDetection("CRASHED", 2.10), MonitoringStatus.MONITORING)
            delay(85L)

            // Round 2 transition -> 2.10x again
            pipeline.onDetectionFrame(fakeDetection("BETTING_OPEN", null), MonitoringStatus.MONITORING)
            pipeline.onDetectionFrame(fakeDetection("IN_FLIGHT", 1.40), MonitoringStatus.MONITORING)
            pipeline.onDetectionFrame(fakeDetection("CRASHED", 2.10), MonitoringStatus.MONITORING)
            delay(85L)

            assertEquals(2, emissions.size)
            assertEquals(2.10, emissions[0].multiplier, 0.001)
            assertEquals(2.10, emissions[1].multiplier, 0.001)
            assertNotEquals(emissions[0].crashRecord.dedupKey, emissions[1].crashRecord.dedupKey)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `suspended or inaccessible window blocks recording without discarding session`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val emissions = mutableListOf<ConfirmedCrashEmission>()
        try {
            val pipeline = LiveCrashPipeline(scope, debounceWindowMs = 35L) {
                emissions.add(it)
            }
            pipeline.configureSession("sess_suspend")

            // Window goes to Settings or becomes inaccessible
            pipeline.onDetectionFrame(
                fakeDetection(
                    phase = "CRASHED",
                    multiplier = 4.44,
                    windowAccessStatus = MonitoringStatus.GAME_NOT_ACCESSIBLE,
                    liveDetectionLost = true
                ),
                MonitoringStatus.GAME_NOT_ACCESSIBLE
            )
            delay(85L)
            assertEquals(0, emissions.size)

            // Window returns -> recording resumes normally into same session
            pipeline.onDetectionFrame(fakeDetection("IN_FLIGHT", 1.80), MonitoringStatus.MONITORING)
            pipeline.onDetectionFrame(fakeDetection("CRASHED", 2.55), MonitoringStatus.MONITORING)
            delay(85L)
            assertEquals(1, emissions.size)
            assertEquals("sess_suspend", emissions.first().crashRecord.sessionId)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `fresh session starts at zero despite historical rows and endSessionAndClearData cleans only active session`() = runBlocking {
        AutomationController.ensureInitialized(app)
        val dao = ScreenEarnerDatabase.getDatabase(app).dao()

        // Seed an old archive crash record from a prior day/session
        val oldTs = System.currentTimeMillis() - 3_600_000L
        dao.insertCrashRecord(
            CrashRecord(
                gameId = "aviator",
                gameName = "Aviator",
                multiplier = 9.99,
                detectedColorHex = "#9333EA",
                colorCategory = CrashColorCategory.Purple,
                isPixelColorVerified = false,
                crashTimestamp = oldTs,
                hourStartTimestamp = CalendarHourUtils.hourStartFor(oldTs),
                sourcePackage = "com.android.chrome",
                sessionId = "old_archive_session",
                isConfirmed = true,
                dedupKey = "old_archive_session_999"
            )
        )

        // Start a brand-new monitoring session
        val sessId = AutomationController.startNewOrResumeMonitoring(app)
        assertTrue(sessId.isNotBlank())
        assertEquals(0, AutomationController.sessionCrashesRecorded.value)
        assertTrue(AutomationController.sessionCrashRecords.value.isEmpty())
        assertNotEquals(MonitoringStatus.STOPPED, AutomationController.monitoringStatus.value)

        // Feed one live round (IN_FLIGHT -> CRASHED) through controller
        val now = System.currentTimeMillis()
        AutomationController.processDetectionFrame(fakeDetection("IN_FLIGHT", 1.50, roundId = "LIVE-1", updatedAtMs = now))
        AutomationController.processDetectionFrame(fakeDetection("CRASHED", 3.33, roundId = "LIVE-1", updatedAtMs = now + 50L))
        delay(320L)

        assertEquals(1, AutomationController.sessionCrashesRecorded.value)
        assertEquals(1, AutomationController.sessionCrashRecords.value.size)
        assertEquals("LIVE-1", AutomationController.engine!!.gameDetection.gameState.value.currentRoundId)
        assertEquals(3.33, AutomationController.engine!!.gameDetection.gameState.value.latestCrashMultiplier!!, 0.001)

        // Verify persisted SharedPreferences session restoration fields
        val prefs = app.getSharedPreferences(AutomationController.PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals(sessId, prefs.getString(AutomationController.KEY_ACTIVE_SESSION_ID, ""))
        assertEquals(MonitoringStatus.MONITORING.name, prefs.getString(AutomationController.KEY_MONITORING_DESIRED_STATE, ""))

        // Pause then resume retains same sessionId without duplicating rounds
        AutomationController.pauseMonitoring(app)
        assertEquals(MonitoringStatus.PAUSED, AutomationController.monitoringStatus.value)
        val resumedId = AutomationController.startNewOrResumeMonitoring(app)
        assertEquals(sessId, resumedId)
        assertEquals(1, AutomationController.sessionCrashesRecorded.value)

        // End session & clear session data -> deletes active session rows but preserves old_archive_session
        AutomationController.endSessionAndClearData(app)
        delay(200L)

        assertEquals(MonitoringStatus.STOPPED, AutomationController.monitoringStatus.value)
        assertEquals("", AutomationController.activeSessionId.value)
        assertEquals(0, AutomationController.sessionCrashesRecorded.value)
        assertTrue(AutomationController.sessionCrashRecords.value.isEmpty())
        assertNull(AutomationController.engine!!.gameDetection.gameState.value.latestCrashMultiplier)
        assertNull(AutomationController.engine!!.gameDetection.gameState.value.currentRoundId)

        val remainingAll = dao.getAllCrashRecords().first()
        assertTrue("Active session row should be deleted", remainingAll.none { it.sessionId == sessId })
        assertTrue("Archive row from prior session must remain preserved", remainingAll.any { it.sessionId == "old_archive_session" })
    }

    @Test
    fun `overlay clampOverlayPosition constrains HUD, bubble, and panel inside safeBounds`() {
        val safeBounds = Rect(0, 80, 1080, 2260)

        // Dragged negative off top-left
        val (leftX, topY) = ScreenEarnerOverlayService.clampOverlayPosition(
            x = -250,
            y = -120,
            viewWidthPx = 240,
            viewHeightPx = 120,
            safeBounds = safeBounds
        )
        assertEquals(0, leftX)
        assertEquals(80, topY)

        // Dragged beyond bottom-right navigation bar
        val (rightX, bottomY) = ScreenEarnerOverlayService.clampOverlayPosition(
            x = 1400,
            y = 2900,
            viewWidthPx = 300,
            viewHeightPx = 400,
            safeBounds = safeBounds
        )
        assertEquals(1080 - 300, rightX)
        assertEquals(2260 - 400, bottomY)
    }

    @Test
    fun `AlarmAudioController manages ringing, Stop, Snooze, and versioned notification channels without High-Probability`() {
        val settings = NotificationSettings(
            highPriorityAlarmEnabled = true,
            alarmVibrationPattern = "DISTINCTIVE",
            alarmMaxDurationMinutes = 5,
            channelConfigVersion = 3
        )

        // Versioned channel ID resolution
        assertEquals(
            "monitoring_alarm_v3",
            AndroidSystemNotificationDispatcher.resolveVersionedChannelId(AndroidSystemNotificationDispatcher.CHANNEL_ALARM, settings)
        )
        assertEquals(
            "monitoring_standard_v3",
            AndroidSystemNotificationDispatcher.resolveVersionedChannelId(AndroidSystemNotificationDispatcher.CHANNEL_STANDARD, settings)
        )
        assertEquals(
            "monitoring_service",
            AndroidSystemNotificationDispatcher.resolveVersionedChannelId(AndroidSystemNotificationDispatcher.CHANNEL_SERVICE, settings)
        )

        // Start -> ringing
        AlarmAudioController.startAlarm(context = null, settings = settings, title = "PATTERN MATCH — Strategy A")
        assertTrue(AlarmAudioController.isAlarmRinging.value)
        assertEquals("PATTERN MATCH — Strategy A", AlarmAudioController.activeAlarmTitle.value)

        // Stop -> releases & clears
        AlarmAudioController.stopAlarm()
        assertFalse(AlarmAudioController.isAlarmRinging.value)
        assertNull(AlarmAudioController.activeAlarmTitle.value)

        // Snooze -> suppresses subsequent alarm within snooze window
        val t0 = 1_700_000_000_000L
        AlarmAudioController.snoozeAlarm(snoozeMinutes = 5, nowMs = t0)
        assertTrue(AlarmAudioController.isAlarmSnoozed(nowMs = t0 + 60_000L))
        AlarmAudioController.startAlarm(context = null, settings = settings, title = "Should Suppress", nowMs = t0 + 60_000L)
        assertFalse(AlarmAudioController.isAlarmRinging.value)
    }

    @Test
    fun `AlarmStopReceiver stops ringing alarm in background without launching activity`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settings = NotificationSettings(highPriorityAlarmEnabled = true)
        AlarmAudioController.clearSnooze()
        AlarmAudioController.startAlarm(context = null, settings = settings, title = "Background Stop Test")
        assertTrue(AlarmAudioController.isAlarmRinging.value)

        val receiver = AlarmStopReceiver()
        receiver.onReceive(context, Intent(AlarmAudioController.ACTION_STOP_ALARM))

        assertFalse(AlarmAudioController.isAlarmRinging.value)
        assertNull(AlarmAudioController.activeAlarmTitle.value)
    }

    @Test
    fun `LiveCrashHeaderCard and SettingsAppearanceScreen separate Round ID from Session Crashes and show Pattern Match profiles`() {
        composeRule.setContent {
            AutoPredictorTheme {
                androidx.compose.foundation.layout.Column {
                    LiveCrashHeaderCard(
                        gameState = GameState(
                            isDetected = true,
                            displayedGameName = "Aviator",
                            platformName = "BetPawa Aviator",
                            monitoringStatus = MonitoringStatus.WAITING_FOR_GAME,
                            latestCrashMultiplier = null,
                            currentRoundId = null,
                            sessionCrashesRecorded = 0
                        ),
                        automationState = AutomationState.RUNNING
                    )
                    SettingsAppearanceScreen(
                        notificationSettings = NotificationSettings(),
                        onEndSessionAndClearData = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText("LAST CRASH: —").assertIsDisplayed()
        composeRule.onNodeWithText("CURRENT GAME ROUND: —").assertIsDisplayed()
        composeRule.onNodeWithText("SESSION CRASHES RECORDED: 0").assertIsDisplayed()
        composeRule.onNodeWithText("Waiting for first confirmed crash").assertIsDisplayed()

        // Dedicated Section 1 card visible & labeled Pattern Match (never High-Probability)
        composeRule.onNodeWithTag("settings_alarm_profiles_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("ALARM & NOTIFICATION PROFILES").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("B. Signal Alarm (Pattern Match)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_test_alarm_btn").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_stop_alarm_btn").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_snooze_alarm_btn").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `OverlaySettingsPanelComposable saves changes in overlay mode and expands to Full Screen only when tapped`() {
        var savedConfig: StrategyConfig? = null
        var expandCount = 0
        var closeCount = 0
        var stopAlarmCount = 0

        composeRule.setContent {
            AutoPredictorTheme {
                OverlaySettingsPanelComposable(
                    strategyConfig = StrategyConfig(
                        baseStake = 10.0,
                        targetMultiplier = 2.0,
                        isAutoBetEnabled = false
                    ),
                    notificationSettings = NotificationSettings(),
                    isMonitoringRunning = true,
                    isAlarmRinging = true,
                    activeAlarmTitle = "PATTERN MATCH — Aviator",
                    onSaveOverlaySettings = { cfg, _, _, _, _ -> savedConfig = cfg },
                    onStopAlarm = { stopAlarmCount++ },
                    onExpandFullScreen = { expandCount++ },
                    onClose = { closeCount++ }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("overlay_settings_window").assertIsDisplayed()
        composeRule.onNodeWithTag("overlay_settings_alarm_banner").assertIsDisplayed()

        // Stop alarm inside overlay settings
        composeRule.onNodeWithTag("overlay_settings_stop_alarm_btn").performClick()
        assertEquals(1, stopAlarmCount)

        // Toggle auto-bet and update stake to 1.0 + target 2.50 directly inside overlay
        composeRule.onNodeWithTag("overlay_settings_auto_bet_switch").performClick()
        composeRule.onNodeWithTag("overlay_settings_base_stake_input").performTextReplacement("1.0")
        composeRule.onNodeWithTag("overlay_settings_target_multiplier_input").performTextReplacement("2.50")

        // Save in overlay mode without navigating to Full Screen
        composeRule.onNodeWithTag("overlay_settings_save_btn").performClick()
        composeRule.waitForIdle()

        assertEquals(0, expandCount)
        assertTrue(savedConfig != null)
        assertTrue(savedConfig!!.isAutoBetEnabled)
        assertEquals(1.0, savedConfig!!.baseStake, 0.001)
        assertEquals(2.50, savedConfig!!.targetMultiplier, 0.001)

        // Tapping Full Screen explicitly expands
        composeRule.onNodeWithTag("overlay_settings_fullscreen_btn").performClick()
        assertEquals(1, expandCount)

        // Tapping Close dismisses overlay settings
        composeRule.onNodeWithTag("overlay_settings_close_btn").performClick()
        assertEquals(1, closeCount)
    }
}
