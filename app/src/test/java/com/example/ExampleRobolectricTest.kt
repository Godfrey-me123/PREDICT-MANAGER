package com.example

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.ScreenEarnerDatabase
import com.example.data.engine.AutomationState
import com.example.data.model.CrashColorCategory
import com.example.data.model.CrashRecord
import com.example.data.model.MultiplierRecord
import com.example.data.model.RiskLimits
import com.example.data.model.SessionStats
import com.example.data.model.StrategyConfig
import com.example.service.FloatingSessionStatsHudComposable
import com.example.service.ScreenEarnerOverlayService
import com.example.ui.screens.RiskManagerScreen
import com.example.ui.screens.SettingsAppearanceScreen
import com.example.ui.screens.StrategyScreen
import com.example.ui.theme.AutoPredictorTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `read Predict Manager app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Predict Manager", appName)
    }

    @Test
    fun `room v10 persists crash records, multiplier records, and strategy config`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ScreenEarnerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.dao()
            dao.saveStrategyConfig(StrategyConfig(gridRows = 4, gridColumns = 7))
            dao.insertMultiplierRecord(MultiplierRecord(multiplier = 112.40, crashTimestamp = 1_700_000_000_000L, platform = "SportyBet Aviator"))
            dao.insertCrashRecord(
                CrashRecord(
                    gameId = "aviator",
                    gameName = "Aviator",
                    multiplier = 112.40,
                    detectedColorHex = "#EC4899",
                    colorCategory = CrashColorCategory.Pink,
                    isPixelColorVerified = true,
                    crashTimestamp = 1_700_000_000_000L,
                    hourStartTimestamp = 1_699_999_200_000L,
                    sourcePackage = "com.android.chrome",
                    dedupKey = "aviator_11240_1"
                )
            )

            val records = dao.getAllMultiplierRecords().first()
            assertEquals(1, records.size)
            assertEquals(112.40, records.first().multiplier, 0.001)

            val crashes = dao.getAllCrashRecords().first()
            assertEquals(1, crashes.size)
            assertEquals("#EC4899", crashes.first().detectedColorHex)

            val savedConfig = dao.getStrategyConfig().first()
            assertEquals(4, savedConfig?.gridRows)
            assertEquals(7, savedConfig?.gridColumns)
        } finally {
            db.close()
        }
    }

    @Test
    fun `settings screen updates grid dimensions, keep awake, schedule switches and validates inputs`() {
        var saveReturnedCount = 0
        val configUpdates = mutableListOf<StrategyConfig>()
        val context = ApplicationProvider.getApplicationContext<Context>()

        composeRule.setContent {
            val currentConfigState = remember { mutableStateOf(StrategyConfig()) }
            AutoPredictorTheme {
                Column {
                    FloatingSessionStatsHudComposable(
                        stats = SessionStats(
                            observedCrashCount = 12,
                            limitHaltReason = null
                        ),
                        observedRounds = 12,
                        automationState = AutomationState.RUNNING,
                        gameName = "Aviator",
                        latestCrashMultiplier = 1.53
                    )
                    SettingsAppearanceScreen(
                        strategyConfig = currentConfigState.value,
                        onStrategyConfigChanged = { updated ->
                            currentConfigState.value = updated
                            configUpdates.add(updated)
                        },
                        onSaveAndReturn = {
                            saveReturnedCount++
                        }
                    )
                }
            }
        }

        // Toggle 4x7 grid chips
        composeRule.onNodeWithTag("settings_grid_rows_4").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_grid_cols_7").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertTrue(configUpdates.isNotEmpty())
        assertEquals(4, configUpdates.last().gridRows)
        assertEquals(7, configUpdates.last().gridColumns)
        composeRule.onNodeWithTag("settings_grid_rows_4").assertIsSelected()
        composeRule.onNodeWithTag("settings_grid_cols_7").assertIsSelected()

        // Toggle Keep Screen Awake off then back on
        composeRule.onNodeWithTag("settings_keep_awake_switch").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertFalse(configUpdates.last().keepScreenAwake)

        // Invalid Low threshold blocks save and shows Toast
        val lowInput = composeRule.onNodeWithTag("settings_low_threshold_input")
        lowInput.performScrollTo()
        lowInput.performTextClearance()

        composeRule.onNodeWithTag("save_appearance_settings_btn").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(0, saveReturnedCount)
        assertEquals("Failed to save settings, please check input", ShadowToast.getTextOfLatestToast())

        // Restore valid threshold -> Save & Close succeeds & writes overlay SharedPreferences
        lowInput.performScrollTo()
        lowInput.performTextReplacement("2.00")
        composeRule.onNodeWithTag("save_appearance_settings_btn").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(1, saveReturnedCount)

        val prefs = context.getSharedPreferences(ScreenEarnerOverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        assertTrue(prefs.contains(ScreenEarnerOverlayService.PREF_BUBBLE_OPACITY))
    }

    @Test
    fun `clicking Save with empty or invalid inputs shows Toast without crashing or saving bad state`() {
        var strategySavedCount = 0
        var riskSavedCount = 0
        val showRisk = mutableStateOf(false)

        composeRule.setContent {
            AutoPredictorTheme {
                if (!showRisk.value) {
                    StrategyScreen(
                        currentConfig = StrategyConfig(),
                        onSaveConfig = { strategySavedCount++ }
                    )
                } else {
                    RiskManagerScreen(
                        currentLimits = RiskLimits(),
                        lastViolation = null,
                        onSaveLimits = { riskSavedCount++ },
                        onTriggerEmergencyStop = {},
                        onClearEmergencyStop = {}
                    )
                }
            }
        }

        // Clear Low Threshold input in StrategyScreen so it becomes invalid empty string, then tap Save
        val lowThreshNode = composeRule.onNodeWithTag("strategy_low_threshold_input")
        lowThreshNode.performScrollTo()
        lowThreshNode.performTextClearance()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("save_strategy_btn"))
        composeRule.onNodeWithTag("save_strategy_btn").performClick()
        composeRule.waitForIdle()

        assertEquals(0, strategySavedCount)
        assertEquals("Failed to save settings, please check input", ShadowToast.getTextOfLatestToast())

        // Also test RiskManagerScreen with malformed input
        showRisk.value = true
        composeRule.waitForIdle()
        val maxStakeNode = composeRule.onNodeWithTag("max_stake_input")
        maxStakeNode.performScrollTo()
        maxStakeNode.performTextReplacement("NaN")
        composeRule.onNodeWithTag("save_risk_limits_btn").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(0, riskSavedCount)
        assertEquals("Failed to save settings, please check input", ShadowToast.getTextOfLatestToast())
    }
}
