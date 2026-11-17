package com.example

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.example.service.AlarmAudioController
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.engine.AutomationState
import com.example.data.model.ShortcutType
import com.example.service.FloatingHandsOverlayManager
import com.example.ui.ScreenEarnerViewModel
import com.example.ui.components.AutoPredictorTopBar
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.HourlyAnalysisScreen
import com.example.ui.screens.LiveMonitorScreen
import com.example.ui.screens.LogsAnalyticsScreen
import com.example.ui.screens.NotificationCenterScreen
import com.example.ui.screens.PatternRecognitionScreen
import com.example.ui.screens.SettingsAppearanceScreen
import com.example.ui.screens.StrategyScreen
import com.example.ui.theme.AutoPredictorTheme
import com.example.ui.theme.PrimaryCobalt

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_OPEN_TAB = "extra_open_tab"
        const val EXTRA_NOTIFICATION_HISTORY_ID = "extra_notification_history_id"
        const val EXTRA_GAME_ID = "extra_game_id"
        const val ACTION_EXIT_APP = "com.example.action.EXIT_APP"

        // 8 AutoPredictor Primary Modules
        const val TAB_HOME = 0
        const val TAB_LIVE_MONITOR = 1
        const val TAB_CRASH_HISTORY = 2
        const val TAB_PATTERNS = 3
        const val TAB_STRATEGY = 4
        const val TAB_HOURLY_ANALYSIS = 5
        const val TAB_NOTIFICATIONS = 6
        const val TAB_SETTINGS = 7

        // Compatibility aliases for deep links & existing navigation references
        const val TAB_OVERLAY = TAB_LIVE_MONITOR
        const val TAB_LOGS = TAB_CRASH_HISTORY
        const val TAB_RISK = TAB_STRATEGY
        const val TAB_PROFILES = TAB_NOTIFICATIONS
    }

    private val viewModel: ScreenEarnerViewModel by viewModels()

    private fun handleAlarmStopIntent(intent: Intent?): Boolean {
        if (intent?.action == AlarmAudioController.ACTION_STOP_ALARM) {
            AlarmAudioController.stopAlarm(this)
            return true
        }
        return false
    }

    private fun handleExitIntent(intent: Intent?): Boolean {
        if (intent?.action == ACTION_EXIT_APP) {
            runCatching {
                finishAndRemoveTask()
            }.onFailure {
                finish()
            }
            return true
        }
        return false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleAlarmStopIntent(intent)) return
        if (handleExitIntent(intent)) return
        handleIncomingTabExtra(intent)
    }

    override fun onResume() {
        super.onResume()
        if (handleAlarmStopIntent(intent)) return
        if (handleExitIntent(intent)) return
        viewModel.refreshOverlayRunningState()
    }

    private fun handleIncomingTabExtra(intent: Intent?) {
        try {
            if (intent == null) return
            val historyId = intent.getLongExtra(EXTRA_NOTIFICATION_HISTORY_ID, -1L)
            if (historyId > 0L) {
                viewModel.markNotificationOpened(historyId)
            }
            val targetGameId = intent.getStringExtra(EXTRA_GAME_ID)
            if (!targetGameId.isNullOrBlank()) {
                viewModel.setSelectedGameId(targetGameId)
            }
            if (intent.hasExtra(EXTRA_OPEN_TAB)) {
                val targetTab = intent.getIntExtra(EXTRA_OPEN_TAB, TAB_HOME)
                viewModel.selectTab(targetTab.coerceIn(TAB_HOME, TAB_SETTINGS))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed handling incoming tab extra", e)
        }
    }

    private fun closeCurrentEditorPanel() {
        try {
            viewModel.closeEditorScreen()
        } catch (e: Exception) {
            Log.e(TAG, "Failed returning from editor panel", e)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handleAlarmStopIntent(intent)) return
        if (handleExitIntent(intent)) return
        enableEdgeToEdge()
        handleIncomingTabExtra(intent)

        setContent {
            AutoPredictorTheme(darkTheme = false) {
                val context = LocalContext.current
                val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
                val strategyConfig by viewModel.strategyConfig.collectAsStateWithLifecycle()
                val crashRecords by viewModel.crashRecords.collectAsStateWithLifecycle()
                val sessionCrashRecords by viewModel.sessionCrashRecords.collectAsStateWithLifecycle()
                val crashAudits by viewModel.crashCorrectionAudits.collectAsStateWithLifecycle()
                val hourlySummaries by viewModel.hourlySummaries.collectAsStateWithLifecycle()
                val patternOccurrences by viewModel.patternOccurrences.collectAsStateWithLifecycle()
                val customStrategies by viewModel.customStrategies.collectAsStateWithLifecycle()
                val notificationSettings by viewModel.notificationSettings.collectAsStateWithLifecycle()
                val notificationHistory by viewModel.notificationHistory.collectAsStateWithLifecycle()
                val alarmProfiles by viewModel.alarmProfiles.collectAsStateWithLifecycle()
                val historyMetadata by viewModel.historyMetadata.collectAsStateWithLifecycle()
                val gameState by viewModel.gameState.collectAsStateWithLifecycle()
                val automationState by viewModel.automationState.collectAsStateWithLifecycle()
                val monitoringStatus by viewModel.monitoringStatus.collectAsStateWithLifecycle()
                val isStandbyOutsideSchedule by viewModel.isStandbyOutsideSchedule.collectAsStateWithLifecycle()
                val isCpuAwakeHeld by viewModel.isCpuAwakeHeld.collectAsStateWithLifecycle()
                val selectedGameId by viewModel.selectedGameId.collectAsStateWithLifecycle()
                val statisticalReport by viewModel.statisticalReport.collectAsStateWithLifecycle()
                val multiWindowBundle by viewModel.multiWindowBundle.collectAsStateWithLifecycle()
                val selectedTimeRangePreset by viewModel.selectedTimeRangePreset.collectAsStateWithLifecycle()
                val timeRangeMetrics by viewModel.timeRangeMetrics.collectAsStateWithLifecycle()
                val smartAgentExplanation by viewModel.smartAgentExplanation.collectAsStateWithLifecycle()
                val isOverlayRunning by viewModel.isOverlayServiceRunning.collectAsStateWithLifecycle()
                val isPixelCaptureActive by viewModel.isPixelCaptureActive.collectAsStateWithLifecycle()
                val userMessage by viewModel.userMessage.collectAsStateWithLifecycle()
                val isAlarmRinging by AlarmAudioController.isAlarmRinging.collectAsStateWithLifecycle()
                val activeAlarmTitle by AlarmAudioController.activeAlarmTitle.collectAsStateWithLifecycle()

                val shortcuts by viewModel.shortcuts.collectAsStateWithLifecycle()
                val betShortcut = shortcuts.firstOrNull { it.type == ShortcutType.BET && it.isCalibrated && it.targetX > 0f }
                    ?: shortcuts.firstOrNull { it.type == ShortcutType.BET && it.label.contains("HAND", ignoreCase = true) }
                val cashOutShortcut = shortcuts.firstOrNull { it.type == ShortcutType.CASH_OUT && it.isCalibrated && it.targetX > 0f }
                    ?: shortcuts.firstOrNull { it.type == ShortcutType.CASH_OUT }

                val floatingHandsManager = remember(context) {
                    FloatingHandsOverlayManager(
                        context = context.applicationContext,
                        repository = viewModel.repository,
                        automationEngine = viewModel.automationEngine
                    )
                }

                LaunchedEffect(Unit) {
                    viewModel.automationEngine.onGestureDispatchRequested = { _, _, _ ->
                        floatingHandsManager.setPassThroughTemporarily(240L)
                    }
                }

                var showMoreModulesSheet by remember { mutableStateOf(false) }
                val snackbarHostState = remember { SnackbarHostState() }

                val projectionConsentLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    viewModel.onPixelColorConsentResult(context, result.resultCode, result.data)
                }

                LaunchedEffect(userMessage) {
                    userMessage?.let { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        viewModel.clearUserMessage()
                    }
                }

                BackHandler(enabled = selectedTab != TAB_HOME) {
                    viewModel.closeEditorScreen()
                }

                val isMonitoringActive = automationState == AutomationState.RUNNING

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        AutoPredictorTopBar(
                            isMonitoringActive = isMonitoringActive,
                            isOutsideSchedule = isStandbyOutsideSchedule,
                            onToggleMonitoring = {
                                if (isMonitoringActive) {
                                    viewModel.pauseAutomation()
                                } else {
                                    viewModel.startMonitoring()
                                }
                            }
                        )
                    },
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.testTag("main_navigation_bar")
                        ) {
                            NavigationBarItem(
                                selected = selectedTab == TAB_HOME,
                                onClick = { viewModel.selectTab(TAB_HOME) },
                                icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                                label = { Text("Home", fontSize = 11.sp, maxLines = 1) },
                                modifier = Modifier.testTag("nav_home")
                            )
                            NavigationBarItem(
                                selected = selectedTab == TAB_LIVE_MONITOR,
                                onClick = { viewModel.selectTab(TAB_LIVE_MONITOR) },
                                icon = { Icon(Icons.Default.Speed, contentDescription = "Monitor") },
                                label = { Text("Monitor", fontSize = 11.sp, maxLines = 1) },
                                modifier = Modifier.testTag("nav_overlay")
                            )
                            NavigationBarItem(
                                selected = selectedTab == TAB_CRASH_HISTORY,
                                onClick = { viewModel.selectTab(TAB_CRASH_HISTORY) },
                                icon = { Icon(Icons.Default.GridView, contentDescription = "History") },
                                label = { Text("History", fontSize = 11.sp, maxLines = 1) },
                                modifier = Modifier.testTag("nav_logs")
                            )
                            NavigationBarItem(
                                selected = selectedTab == TAB_PATTERNS,
                                onClick = { viewModel.selectTab(TAB_PATTERNS) },
                                icon = { Icon(Icons.Default.Analytics, contentDescription = "Patterns") },
                                label = { Text("Patterns", fontSize = 11.sp, maxLines = 1) },
                                modifier = Modifier.testTag("nav_patterns")
                            )
                            NavigationBarItem(
                                selected = selectedTab >= TAB_STRATEGY,
                                onClick = { showMoreModulesSheet = true },
                                icon = { Icon(Icons.Default.Menu, contentDescription = "Modules") },
                                label = { Text("Modules", fontSize = 11.sp, maxLines = 1) },
                                modifier = Modifier.testTag("nav_more_modules")
                            )
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (selectedTab) {
                            TAB_HOME -> HomeScreen(
                                gameState = gameState,
                                automationState = automationState,
                                isOverlayServiceRunning = isOverlayRunning,
                                strategyConfig = strategyConfig,
                                crashRecords = sessionCrashRecords,
                                smartAgentExplanation = smartAgentExplanation,
                                isStandbyOutsideSchedule = isStandbyOutsideSchedule,
                                onToggleOverlayService = { viewModel.toggleOverlayService(context) },
                                onStartMonitoring = { viewModel.startMonitoring() },
                                onPauseMonitoring = { viewModel.pauseAutomation() },
                                onNavigateToTab = { viewModel.selectTab(it) },
                                monitoringStatus = monitoringStatus,
                                isAlarmRinging = isAlarmRinging,
                                activeAlarmTitle = activeAlarmTitle,
                                onStopAlarm = { AlarmAudioController.stopAlarm(context) },
                                onSnoozeAlarm = { AlarmAudioController.snoozeAlarm(5) },
                                onEndSessionAndClearData = { viewModel.endSessionAndClearSessionData() },
                                onToggleAutoBet = { viewModel.toggleAutoBet(it) },
                                betShortcut = betShortcut,
                                cashOutShortcut = cashOutShortcut,
                                onToggleBetHand = {
                                    floatingHandsManager.showOrToggleHand(
                                        ShortcutType.BET,
                                        betShortcut,
                                        strategyConfig?.targetMultiplier ?: 2.00
                                    )
                                },
                                onToggleCashOutHand = {
                                    floatingHandsManager.showOrToggleHand(
                                        ShortcutType.CASH_OUT,
                                        cashOutShortcut,
                                        strategyConfig?.targetMultiplier ?: 2.00
                                    )
                                }
                            )

                            TAB_LIVE_MONITOR -> LiveMonitorScreen(
                                gameState = gameState,
                                automationState = automationState,
                                strategyConfig = strategyConfig,
                                crashRecords = sessionCrashRecords,
                                historyMetadata = historyMetadata,
                                selectedGameId = selectedGameId,
                                isPixelCaptureActive = isPixelCaptureActive,
                                onSelectGameId = { viewModel.setSelectedGameId(it) },
                                onStartMonitoring = { viewModel.startMonitoring() },
                                onPauseMonitoring = { viewModel.pauseAutomation() },
                                onCollectHistory = { viewModel.triggerRibbonHistoryCollection(context) },
                                onRequestPixelCapture = {
                                    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                                    if (mpm != null) {
                                        projectionConsentLauncher.launch(mpm.createScreenCaptureIntent())
                                    } else {
                                        Toast.makeText(context, "Screen capture service unavailable on device", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onStopPixelCapture = { viewModel.stopPixelColorCapture(context) },
                                onEndSessionAndClear = { viewModel.endSessionAndClearSessionData() }
                            )

                            TAB_CRASH_HISTORY -> LogsAnalyticsScreen(
                                crashRecords = sessionCrashRecords,
                                crashAudits = crashAudits,
                                historyMetadata = historyMetadata,
                                strategyConfig = strategyConfig,
                                selectedGameId = selectedGameId,
                                allCrashRecords = crashRecords,
                                onSelectGameId = { viewModel.setSelectedGameId(it) },
                                onUpdateGridDimensions = { rows, cols -> viewModel.updateGridDimensions(rows, cols) },
                                onClearCrashHistory = { viewModel.clearMultiplierRecords() },
                                onEndSessionAndClear = { viewModel.endSessionAndClearSessionData() },
                                onCollectHistory = { viewModel.triggerRibbonHistoryCollection(context) },
                                onCountClearScopeRecords = { scope, gId -> viewModel.countClearScopeRecords(scope, gId) },
                                onClearHistoryByScope = { scope, gId -> viewModel.clearHistoryByScope(scope, gId) }
                            )

                            TAB_PATTERNS -> PatternRecognitionScreen(
                                report = statisticalReport,
                                agentExplanation = smartAgentExplanation,
                                selectedGameId = selectedGameId,
                                onSelectGameId = { viewModel.setSelectedGameId(it) }
                            )

                            TAB_STRATEGY -> StrategyScreen(
                                currentConfig = strategyConfig,
                                customStrategies = customStrategies,
                                patternOccurrences = patternOccurrences,
                                onSaveCustomStrategy = { viewModel.saveCustomStrategy(it) },
                                onToggleCustomStrategyEnabled = { strat, enabled ->
                                    viewModel.toggleCustomStrategyEnabled(strat, enabled)
                                },
                                onToggleCustomStrategyNotifications = { strat, enabled ->
                                    viewModel.toggleCustomStrategyNotifications(strat, enabled)
                                },
                                onDeleteCustomStrategy = { viewModel.deleteCustomStrategy(it) },
                                onReorderStrategies = { first, second -> viewModel.swapStrategyOrder(first, second) },
                                onSaveConfig = { updatedConfig ->
                                    try {
                                        viewModel.saveStrategyConfig(updatedConfig, ensureOverlayActive = false, context = context)
                                        Toast.makeText(context, "Strategy settings saved", Toast.LENGTH_SHORT).show()
                                        closeCurrentEditorPanel()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Save failed in StrategyScreen", e)
                                        Toast.makeText(context, "Failed to save settings, please check input", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )

                            TAB_HOURLY_ANALYSIS -> HourlyAnalysisScreen(
                                bundle = multiWindowBundle,
                                archivedSummaries = hourlySummaries,
                                selectedGameId = selectedGameId,
                                onSelectGameId = { viewModel.setSelectedGameId(it) },
                                selectedTimeRangePreset = selectedTimeRangePreset,
                                timeRangeMetrics = timeRangeMetrics,
                                onSelectTimeRangePreset = { preset, startMs, endMs ->
                                    viewModel.setTimeRangePreset(preset, startMs, endMs)
                                }
                            )

                            TAB_NOTIFICATIONS -> NotificationCenterScreen(
                                notificationSettings = notificationSettings,
                                notificationHistory = notificationHistory,
                                onUpdateSettings = { viewModel.saveNotificationSettings(it) },
                                onClearHistory = { viewModel.clearNotificationHistory() },
                                onOpenRecordTarget = { record ->
                                    if (record.id > 0L) viewModel.markNotificationOpened(record.id)
                                    if (record.gameId.isNotBlank()) viewModel.setSelectedGameId(record.gameId)
                                    val destTab = if (record.channelId == com.example.service.AndroidSystemNotificationDispatcher.CHANNEL_HOURLY_ANALYSIS) {
                                        TAB_HOURLY_ANALYSIS
                                    } else {
                                        TAB_PATTERNS
                                    }
                                    viewModel.selectTab(destTab)
                                }
                            )

                            TAB_SETTINGS -> SettingsAppearanceScreen(
                                strategyConfig = strategyConfig,
                                notificationSettings = notificationSettings,
                                isStandbyOutsideSchedule = isStandbyOutsideSchedule,
                                onStrategyConfigChanged = { updatedConfig ->
                                    viewModel.applyStrategySelection(updatedConfig)
                                },
                                onNotificationSettingsChanged = { updatedNotif ->
                                    viewModel.saveNotificationSettings(updatedNotif)
                                },
                                onResetSessionStats = {
                                    viewModel.automationEngine.resetSessionStats()
                                },
                                onSaveAndReturn = { updatedConfig ->
                                    try {
                                        viewModel.saveStrategyConfig(
                                            config = updatedConfig,
                                            ensureOverlayActive = false,
                                            context = context
                                        )
                                        Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
                                        closeCurrentEditorPanel()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Save failed in SettingsAppearanceScreen", e)
                                        Toast.makeText(context, "Failed to save settings, please check input", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onEndSessionAndClearData = { viewModel.endSessionAndClearSessionData() },
                                isAlarmRinging = isAlarmRinging,
                                onStopAlarm = { AlarmAudioController.stopAlarm(context) },
                                onSnoozeAlarm = { AlarmAudioController.snoozeAlarm(5) },
                                alarmProfiles = alarmProfiles,
                                onSaveAlarmProfile = { viewModel.saveAlarmProfile(it) },
                                isCpuAwakeHeld = isCpuAwakeHeld,
                                onCountClearScopeRecords = { scope, gId -> viewModel.countClearScopeRecords(scope, gId) },
                                onClearHistoryByScope = { scope, gId -> viewModel.clearHistoryByScope(scope, gId) },
                                selectedGameId = selectedGameId
                            )
                        }
                    }
                }

                if (showMoreModulesSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showMoreModulesSheet = false },
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "PREDICT MANAGER MODULES",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = PrimaryCobalt,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            SheetModuleItem(
                                title = "5. Strategy Manager",
                                subtitle = "Strategy A/B/C, custom sequence rules & per-strategy history",
                                icon = Icons.Default.Tune,
                                selected = selectedTab == TAB_STRATEGY,
                                testTag = "nav_strategy",
                                onClick = {
                                    showMoreModulesSheet = false
                                    viewModel.selectTab(TAB_STRATEGY)
                                }
                            )
                            SheetModuleItem(
                                title = "6. Hourly Analysis",
                                subtitle = "30m, 60m, Current Hour, Previous Hour & finalized archive",
                                icon = Icons.Default.Schedule,
                                selected = selectedTab == TAB_HOURLY_ANALYSIS,
                                testTag = "nav_hourly",
                                onClick = {
                                    showMoreModulesSheet = false
                                    viewModel.selectTab(TAB_HOURLY_ANALYSIS)
                                }
                            )
                            SheetModuleItem(
                                title = "7. Notification Center",
                                subtitle = "Android channels, POST_NOTIFICATIONS & alert delivery logs",
                                icon = Icons.Default.Notifications,
                                selected = selectedTab == TAB_NOTIFICATIONS,
                                testTag = "nav_notifications",
                                onClick = {
                                    showMoreModulesSheet = false
                                    viewModel.selectTab(TAB_NOTIFICATIONS)
                                }
                            )
                            SheetModuleItem(
                                title = "8. Settings & Awake Schedule",
                                subtitle = "Category thresholds, grid size, Keep-Awake & overnight schedule",
                                icon = Icons.Default.Settings,
                                selected = selectedTab == TAB_SETTINGS,
                                testTag = "nav_settings",
                                onClick = {
                                    showMoreModulesSheet = false
                                    viewModel.selectTab(TAB_SETTINGS)
                                }
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetModuleItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = title, tint = PrimaryCobalt)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
