package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MainActivity
import com.example.data.database.ClearHistoryScope
import com.example.data.database.ScreenEarnerDatabase
import com.example.data.database.ScreenEarnerRepository
import com.example.data.engine.AutomationState
import com.example.data.engine.GameAdapterRegistry
import com.example.data.engine.HistoryCollectionMetadata
import com.example.data.engine.HourlyAnalysisEngine
import com.example.data.engine.HourlyWindowMetrics
import com.example.data.engine.MultiWindowAnalysisBundle
import com.example.data.engine.SmartAgentExplanation
import com.example.data.engine.StatisticalReport
import com.example.data.model.AlarmProfileEntity
import com.example.data.model.CrashColorCategory
import com.example.data.model.CrashCorrectionAudit
import com.example.data.model.CrashRecord
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.FloatingShortcut
import com.example.data.model.GameState
import com.example.data.model.HourlySessionSummary
import com.example.data.model.MonitoringSessionEntity
import com.example.data.model.MonitoringStatus
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
import com.example.service.AutomationController
import com.example.service.BatteryOptimizationWorker
import com.example.service.FloatingOverlayService
import com.example.service.ScreenCaptureProjectionService
import com.example.service.ScreenEarnerAccessibilityService
import com.example.service.ScreenEarnerOverlayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ScreenEarnerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "PredictManagerViewModel"
    }

    private val dao = ScreenEarnerDatabase.getDatabase(application).dao()
    val repository = ScreenEarnerRepository(dao)

    init {
        AutomationController.ensureInitialized(application)
        AutomationController.initialize(repository, application)
    }

    val automationEngine = AutomationController.engine!!
    val strategyEngine = AutomationController.engine!!.strategyEngine
    val riskManager = AutomationController.engine!!.riskManager
    val gameDetection = AutomationController.engine!!.gameDetection

    val shortcuts: StateFlow<List<FloatingShortcut>> = repository.shortcuts.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val profiles: StateFlow<List<PlatformProfile>> = repository.profiles.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val strategyConfig: StateFlow<StrategyConfig?> = repository.strategyConfig.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = StrategyConfig()
    )

    val riskLimits: StateFlow<RiskLimits?> = repository.riskLimits.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = RiskLimits()
    )

    val sessionLogs: StateFlow<List<SessionLog>> = repository.sessionLogs.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val multiplierRecords: StateFlow<List<MultiplierRecord>> = repository.multiplierRecords.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val crashRecords: StateFlow<List<CrashRecord>> = repository.crashRecords.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val sessionCrashRecords: StateFlow<List<CrashRecord>> = AutomationController.activeSessionCrashRecords

    val crashCorrectionAudits: StateFlow<List<CrashCorrectionAudit>> = repository.crashCorrectionAudits.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val hourlySummaries: StateFlow<List<HourlySessionSummary>> = repository.hourlySummaries.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val patternOccurrences: StateFlow<List<PatternOccurrenceRecord>> = repository.patternOccurrences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val customStrategies: StateFlow<List<CustomPatternStrategy>> = repository.customStrategies.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val notificationSettings: StateFlow<NotificationSettings?> = repository.notificationSettings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = NotificationSettings()
    )

    val notificationHistory: StateFlow<List<NotificationHistoryRecord>> = repository.notificationHistory.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val alarmProfiles: StateFlow<List<AlarmProfileEntity>> = repository.alarmProfiles.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AlarmProfileEntity.defaultProfiles()
    )

    val monitoringSessions: StateFlow<List<MonitoringSessionEntity>> = repository.monitoringSessions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val gameState: StateFlow<GameState> = gameDetection.gameState
    val historyMetadata: StateFlow<HistoryCollectionMetadata> = gameDetection.historyMetadata
    val automationState: StateFlow<AutomationState> = automationEngine.automationState
    val monitoringStatus: StateFlow<MonitoringStatus> = AutomationController.monitoringStatus
    val isStandbyOutsideSchedule: StateFlow<Boolean> = automationEngine.isStandbyOutsideSchedule
    val isScreenHeld: StateFlow<Boolean> = ScreenEarnerOverlayService.isScreenHeldState
    val isCpuAwakeHeld: StateFlow<Boolean> = ScreenEarnerOverlayService.isCpuActiveState
    val selectedGameId: StateFlow<String> = AutomationController.selectedGameId
    val statisticalReport: StateFlow<StatisticalReport> = AutomationController.statisticalReport
    val multiWindowBundle: StateFlow<MultiWindowAnalysisBundle> = AutomationController.multiWindowBundle
    val smartAgentExplanation: StateFlow<SmartAgentExplanation> = AutomationController.smartAgentExplanation
    val isPixelCaptureActive: StateFlow<Boolean> = com.example.service.ScreenColorSampler.isCaptureActive

    private val _selectedTimeRangePreset = MutableStateFlow(HourlyAnalysisEngine.TimeRangePreset.ONE_HOUR)
    val selectedTimeRangePreset: StateFlow<HourlyAnalysisEngine.TimeRangePreset> = _selectedTimeRangePreset.asStateFlow()

    private val _customRangeStartMs = MutableStateFlow(System.currentTimeMillis() - 6 * 3_600_000L)
    val customRangeStartMs: StateFlow<Long> = _customRangeStartMs.asStateFlow()

    private val _customRangeEndMs = MutableStateFlow(System.currentTimeMillis())
    val customRangeEndMs: StateFlow<Long> = _customRangeEndMs.asStateFlow()

    val timeRangeMetrics: StateFlow<HourlyWindowMetrics> = combine(
        crashRecords,
        selectedGameId,
        strategyConfig,
        selectedTimeRangePreset,
        combine(_customRangeStartMs, _customRangeEndMs) { s, e -> s to e }
    ) { records, gameId, cfg, preset, customPair ->
        val resolvedName = when {
            gameId == "ALL" -> "All Games"
            gameId == "unknown" -> "Unknown"
            else -> GameAdapterRegistry.allAdapters().firstOrNull { it.gameId == gameId }?.displayedGameName
                ?: records.lastOrNull { it.gameId == gameId }?.gameName
                ?: "Unknown"
        }
        HourlyAnalysisEngine.analyzeTimeRange(
            preset = preset,
            records = records,
            config = cfg ?: StrategyConfig(),
            gameId = gameId,
            gameName = resolvedName,
            customStartMs = customPair.first,
            customEndMs = customPair.second
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HourlyAnalysisEngine.analyzeTimeRange(
            preset = HourlyAnalysisEngine.TimeRangePreset.ONE_HOUR,
            records = emptyList()
        )
    )

    private val _selectedTab = MutableStateFlow(MainActivity.TAB_HOME)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _previousTab = MutableStateFlow(MainActivity.TAB_HOME)
    val previousTab: StateFlow<Int> = _previousTab.asStateFlow()

    private val _isOverlayServiceRunning = MutableStateFlow(ScreenEarnerOverlayService.isRunning)
    val isOverlayServiceRunning: StateFlow<Boolean> = _isOverlayServiceRunning.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    fun refreshOverlayRunningState() {
        _isOverlayServiceRunning.value = ScreenEarnerOverlayService.isRunning
    }

    fun setSelectedGameId(gameId: String) {
        AutomationController.setSelectedGameId(gameId)
    }

    fun selectTab(index: Int) {
        val current = _selectedTab.value
        val coerced = index.coerceIn(MainActivity.TAB_HOME, MainActivity.TAB_SETTINGS)
        if (coerced != current) {
            if (current == MainActivity.TAB_HOME ||
                current == MainActivity.TAB_LIVE_MONITOR ||
                current == MainActivity.TAB_CRASH_HISTORY ||
                current == MainActivity.TAB_PATTERNS ||
                current == MainActivity.TAB_HOURLY_ANALYSIS ||
                current == MainActivity.TAB_NOTIFICATIONS
            ) {
                _previousTab.value = current
            }
            _selectedTab.value = coerced
        }
    }

    fun closeEditorScreen() {
        val current = _selectedTab.value
        val candidatePrev = _previousTab.value
        val target = if (candidatePrev != current &&
            candidatePrev != MainActivity.TAB_STRATEGY &&
            candidatePrev != MainActivity.TAB_SETTINGS
        ) {
            candidatePrev
        } else {
            MainActivity.TAB_HOME
        }
        _selectedTab.value = target
    }

    /**
     * Starts observation-only Live Game Monitoring (does NOT require calibrated Bet hand).
     */
    fun startAutomation() {
        startMonitoring()
    }

    fun startMonitoring() {
        try {
            val result = AutomationController.startMonitoringSession(
                isAccessibilityConnected = ScreenEarnerAccessibilityService.isServiceConnected()
            )
            _userMessage.value = if (result.started) {
                "Predict Manager Live Monitor STARTED"
            } else {
                result.message
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start monitoring", e)
            _userMessage.value = "Failed to start monitoring"
        }
    }

    fun pauseAutomation() {
        try {
            AutomationController.pauseMonitoringSession()
            _userMessage.value = "Monitoring PAUSED"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause monitoring", e)
        }
    }

    fun resumeAutomation() {
        try {
            val result = AutomationController.startMonitoringSession(
                isAccessibilityConnected = ScreenEarnerAccessibilityService.isServiceConnected()
            )
            _userMessage.value = if (result.started) "Monitoring RESUMED" else result.message
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume monitoring", e)
        }
    }

    fun quitAutomation() {
        try {
            AutomationController.stopMonitoringSession()
            _userMessage.value = "Monitoring STOPPED"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop monitoring", e)
        }
    }

    fun endSessionAndClearSessionData() {
        try {
            AutomationController.requestEndSessionAndClearSessionData()
            _userMessage.value = "Session ended & current session data cleared"
        } catch (e: Exception) {
            Log.e(TAG, "Failed clearing current session data", e)
        }
    }

    fun exitUtility(context: Context) {
        try {
            AutomationController.stopMonitoringSession()
            BatteryOptimizationWorker.cancel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            nm?.cancelAll()
            context.stopService(Intent(context, ScreenEarnerOverlayService::class.java))
            context.stopService(Intent(context, FloatingOverlayService::class.java))
            context.stopService(Intent(context, ScreenCaptureProjectionService::class.java))
            _isOverlayServiceRunning.value = false
            _userMessage.value = "Predict Manager floating utility stopped."
        } catch (e: Exception) {
            Log.e(TAG, "Failed exiting overlay utility", e)
        }
    }

    private fun sanitizeStrategyConfig(config: StrategyConfig): StrategyConfig {
        val sanitizedRules = config.getRuleConfigs().map { rule ->
            rule.copy(
                customAddAmount = rule.customAddAmount.takeIf { it.isFinite() && it >= 0.0 } ?: 10.0,
                targetWinsToFetch = rule.targetWinsToFetch.coerceAtLeast(1),
                requiredLosses = rule.requiredLosses.coerceAtLeast(0),
                waitMinutes = rule.waitMinutes.coerceAtLeast(0),
                minMultiplier = rule.minMultiplier.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                maxMultiplier = rule.maxMultiplier.takeIf { it.isFinite() && it >= 0.0 } ?: 1000.0
            )
        }
        val safeLow = config.lowThreshold.takeIf { it.isFinite() && it >= 1.01 } ?: 2.00
        val safeBoundary = config.boundaryValue.takeIf { it.isFinite() && it >= 1.01 } ?: safeLow
        val safeBig = config.bigThreshold.takeIf { it.isFinite() && it > safeLow } ?: 10.00
        return config.copy(
            lowThreshold = safeLow,
            boundaryValue = safeBoundary,
            bigThreshold = safeBig,
            gridRows = config.gridRows.coerceIn(4, 5),
            gridColumns = config.gridColumns.coerceIn(7, 8),
            strategy1LowThreshold = config.strategy1LowThreshold.takeIf { it.isFinite() && it >= 1.01 } ?: 2.00,
            strategy1RequiredLowRounds = config.strategy1RequiredLowRounds.coerceAtLeast(1),
            strategy2HighThreshold = config.strategy2HighThreshold.takeIf { it.isFinite() && it >= 1.01 } ?: 10.00,
            strategy2WaitRounds = config.strategy2WaitRounds.coerceAtLeast(1),
            baseStake = config.baseStake.takeIf { it.isFinite() && it > 0.0 } ?: 10.0,
            multiplierProgression = config.multiplierProgression.takeIf { it.isFinite() && it >= 1.0 } ?: 2.0,
            stopAfterConsecutiveWins = config.stopAfterConsecutiveWins.coerceAtLeast(1),
            targetMultiplier = config.targetMultiplier.takeIf { it.isFinite() && it >= 1.01 } ?: 2.00,
            sessionTimeoutMinutes = config.sessionTimeoutMinutes.coerceAtLeast(1),
            scheduleStartHour = config.scheduleStartHour.coerceIn(0, 23),
            scheduleStartMinute = config.scheduleStartMinute.coerceIn(0, 59),
            scheduleEndHour = config.scheduleEndHour.coerceIn(0, 23),
            scheduleEndMinute = config.scheduleEndMinute.coerceIn(0, 59)
        ).withUpdatedRuleConfigs(sanitizedRules)
    }

    private fun sanitizeRiskLimits(limits: RiskLimits): RiskLimits {
        return limits.copy(
            maxSingleStake = limits.maxSingleStake.takeIf { it.isFinite() && it > 0.0 } ?: 500.0,
            maxSessionLoss = limits.maxSessionLoss.takeIf { it.isFinite() && it >= 0.0 } ?: 1000.0,
            maxTotalRounds = limits.maxTotalRounds.coerceAtLeast(1),
            maxProgressionLevel = limits.maxProgressionLevel.coerceAtLeast(1),
            profitTarget = limits.profitTarget.takeIf { it.isFinite() && it >= 0.0 } ?: 1500.0,
            minBalanceThreshold = limits.minBalanceThreshold.takeIf { it.isFinite() && it >= 0.0 } ?: 50.0
        )
    }

    fun applyStrategySelection(config: StrategyConfig) {
        try {
            val safeConfig = sanitizeStrategyConfig(config)
            strategyEngine.bindStrategyConfig(safeConfig)
            viewModelScope.launch {
                try {
                    repository.saveStrategyConfig(safeConfig)
                } catch (e: Exception) {
                    Log.e(TAG, "Save failed while applying config", e)
                    _userMessage.value = "Failed to save settings, please check input"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save failed while binding config", e)
            _userMessage.value = "Failed to save settings, please check input"
        }
    }

    fun saveStrategyConfig(config: StrategyConfig, ensureOverlayActive: Boolean = false, context: Context? = null) {
        try {
            val safeConfig = sanitizeStrategyConfig(config)
            strategyEngine.bindStrategyConfig(safeConfig)
            if (ensureOverlayActive && context != null) {
                ensureOverlayServiceRunning(context)
            }
            viewModelScope.launch {
                try {
                    repository.saveStrategyConfig(safeConfig)
                    _userMessage.value = "Settings Saved"
                } catch (e: Exception) {
                    Log.e(TAG, "Save failed for config", e)
                    _userMessage.value = "Failed to save settings, please check input"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save failed for config", e)
            _userMessage.value = "Failed to save settings, please check input"
        }
    }

    fun saveRiskLimits(limits: RiskLimits, ensureOverlayActive: Boolean = false, context: Context? = null) {
        try {
            val safeLimits = sanitizeRiskLimits(limits)
            if (ensureOverlayActive && context != null) {
                ensureOverlayServiceRunning(context)
            }
            viewModelScope.launch {
                try {
                    repository.saveRiskLimits(safeLimits)
                    _userMessage.value = "Safety Locks Saved"
                } catch (e: Exception) {
                    Log.e(TAG, "Save failed for limits", e)
                    _userMessage.value = "Failed to save settings, please check input"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save failed for limits", e)
            _userMessage.value = "Failed to save settings, please check input"
        }
    }

    fun updateGridDimensions(rows: Int, cols: Int) {
        val current = strategyConfig.value ?: StrategyConfig()
        applyStrategySelection(current.copy(gridRows = rows.coerceIn(4, 5), gridColumns = cols.coerceIn(7, 8)))
    }

    fun updateBaseStake(newBaseStake: Double) {
        if (!newBaseStake.isFinite() || newBaseStake < 1.0) {
            _userMessage.value = "Base stake must be at least 1.0"
            return
        }
        val current = strategyConfig.value ?: StrategyConfig()
        saveStrategyConfig(current.copy(baseStake = newBaseStake))
    }

    fun updateTargetMultiplier(newMultiplier: Double) {
        if (!newMultiplier.isFinite() || newMultiplier < 1.01) {
            _userMessage.value = "Target multiplier must be at least 1.01x"
            return
        }
        val current = strategyConfig.value ?: StrategyConfig()
        saveStrategyConfig(current.copy(targetMultiplier = newMultiplier))
    }

    fun toggleAutoBet(enabled: Boolean) {
        val current = strategyConfig.value ?: StrategyConfig()
        saveStrategyConfig(current.copy(isAutoBetEnabled = enabled, isAutoCashOutEnabled = enabled))
        _userMessage.value = if (enabled) "Live automation enabled (Betting & Editing Active)" else "Live automation paused (Recording Only)"
    }

    fun updateCategoryThresholds(low: Double, boundary: Double, big: Double) {
        if (!low.isFinite() || !boundary.isFinite() || !big.isFinite() || low < 1.01 || big <= low) {
            _userMessage.value = "Failed to save settings, please check input"
            return
        }
        val current = strategyConfig.value ?: StrategyConfig()
        saveStrategyConfig(current.copy(lowThreshold = low, boundaryValue = boundary, bigThreshold = big))
    }

    fun applyManualCrashCorrection(
        recordId: Long,
        newMultiplier: Double,
        newCategory: CrashColorCategory,
        reason: String
    ) {
        viewModelScope.launch {
            try {
                if (!newMultiplier.isFinite() || newMultiplier < 1.0) {
                    _userMessage.value = "Failed to save settings, please check input"
                    return@launch
                }
                val ok = repository.applyManualCrashCorrection(
                    recordId = recordId,
                    newMultiplier = newMultiplier,
                    newColorHex = newCategory.defaultHex,
                    newCategory = newCategory,
                    reason = reason.ifBlank { "Manual operator verification" }
                )
                _userMessage.value = if (ok) {
                    "Crash record updated with audit trail."
                } else {
                    "Crash record not found."
                }
            } catch (e: Exception) {
                Log.e(TAG, "Manual crash correction failed", e)
                _userMessage.value = "Failed to save correction"
            }
        }
    }

    fun saveCustomStrategy(strategy: CustomPatternStrategy) {
        viewModelScope.launch {
            try {
                if (strategy.name.isBlank()) {
                    _userMessage.value = "Failed to save settings, please check input"
                    return@launch
                }
                if (strategy.id == 0L) {
                    repository.insertCustomStrategy(strategy)
                    _userMessage.value = "Strategy '${strategy.name}' created"
                } else {
                    repository.updateCustomStrategy(strategy)
                    _userMessage.value = "Strategy '${strategy.name}' updated"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Save failed for custom strategy", e)
                _userMessage.value = "Failed to save settings, please check input"
            }
        }
    }

    fun toggleCustomStrategyEnabled(strategy: CustomPatternStrategy, enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateCustomStrategy(
                    strategy.copy(
                        isEnabled = enabled,
                        currentlyApproaching = if (!enabled) false else strategy.currentlyApproaching,
                        currentlyReached = if (!enabled) false else strategy.currentlyReached
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed toggling strategy", e)
            }
        }
    }

    fun toggleCustomStrategyNotifications(strategy: CustomPatternStrategy, enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateCustomStrategy(strategy.copy(notificationEnabled = enabled))
            } catch (e: Exception) {
                Log.e(TAG, "Failed toggling strategy notifications", e)
            }
        }
    }

    fun deleteCustomStrategy(id: Long) {
        viewModelScope.launch {
            try {
                repository.deleteCustomStrategy(id)
                _userMessage.value = "Strategy deleted"
            } catch (e: Exception) {
                Log.e(TAG, "Failed deleting strategy", e)
            }
        }
    }

    fun swapStrategyOrder(first: CustomPatternStrategy, second: CustomPatternStrategy) {
        viewModelScope.launch {
            try {
                repository.swapStrategyOrder(first, second)
            } catch (e: Exception) {
                Log.e(TAG, "Failed reordering strategies", e)
            }
        }
    }

    fun saveAlarmProfile(profile: AlarmProfileEntity) {
        viewModelScope.launch {
            try {
                repository.upsertAlarmProfile(profile)
                _userMessage.value = "${profile.category().displayTitle} updated"
            } catch (e: Exception) {
                Log.e(TAG, "Failed saving alarm profile", e)
                _userMessage.value = "Failed to save settings, please check input"
            }
        }
    }

    fun setTimeRangePreset(
        preset: HourlyAnalysisEngine.TimeRangePreset,
        customStartMs: Long = _customRangeStartMs.value,
        customEndMs: Long = _customRangeEndMs.value
    ) {
        _selectedTimeRangePreset.value = preset
        if (preset == HourlyAnalysisEngine.TimeRangePreset.CUSTOM) {
            _customRangeStartMs.value = customStartMs
            _customRangeEndMs.value = customEndMs
        }
    }

    suspend fun countClearScopeRecords(scope: ClearHistoryScope, gameId: String): Int {
        return repository.countClearScopeRecords(
            scope = scope,
            activeSessionId = AutomationController.activeSessionId.value,
            selectedGameId = gameId
        )
    }

    fun clearHistoryByScope(scope: ClearHistoryScope, gameId: String) {
        viewModelScope.launch {
            try {
                AutomationController.clearHistoryByScope(scope, gameId)
                _userMessage.value = when (scope) {
                    ClearHistoryScope.CURRENT_SESSION -> "Current session crash records cleared"
                    ClearHistoryScope.SELECTED_GAME_ARCHIVED -> "Archived history for selected game cleared"
                    ClearHistoryScope.ALL_HISTORY -> "All local crash history & stats cleared"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed clearing history by scope $scope", e)
                _userMessage.value = "Failed to clear history"
            }
        }
    }

    fun saveNotificationSettings(settings: NotificationSettings) {
        viewModelScope.launch {
            try {
                repository.saveNotificationSettings(
                    settings.copy(
                        repeatIntervalMinutes = settings.repeatIntervalMinutes.coerceAtLeast(0),
                        quietStartHour = settings.quietStartHour.coerceIn(0, 23),
                        quietStartMinute = settings.quietStartMinute.coerceIn(0, 59),
                        quietEndHour = settings.quietEndHour.coerceIn(0, 23),
                        quietEndMinute = settings.quietEndMinute.coerceIn(0, 59)
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed saving notification settings", e)
                _userMessage.value = "Failed to save settings, please check input"
            }
        }
    }

    fun markNotificationOpened(historyId: Long) {
        if (historyId <= 0L) return
        viewModelScope.launch {
            try {
                repository.updateNotificationInteraction(historyId, NotificationInteractionStatus.OPENED_BY_USER)
            } catch (e: Exception) {
                Log.e(TAG, "Failed marking notification opened", e)
            }
        }
    }

    fun clearNotificationHistory() {
        viewModelScope.launch {
            try {
                repository.clearNotificationHistory()
                AutomationController.notificationManager?.clearSessionCache()
                _userMessage.value = "Notification history cleared"
            } catch (e: Exception) {
                Log.e(TAG, "Failed clearing notification history", e)
            }
        }
    }

    fun triggerRibbonHistoryCollection(context: Context = getApplication()) {
        try {
            AutomationController.triggerManualHistoryCollection(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed triggering ribbon history collection", e)
        }
    }

    fun stopPixelColorCapture(context: Context) {
        try {
            context.stopService(Intent(context, ScreenCaptureProjectionService::class.java))
            val current = strategyConfig.value ?: StrategyConfig()
            applyStrategySelection(current.copy(pixelColorCaptureEnabled = false))
            _userMessage.value = "Pixel color capture stopped."
        } catch (e: Exception) {
            Log.e(TAG, "Failed stopping pixel capture", e)
        }
    }

    fun onPixelColorConsentResult(context: Context, resultCode: Int, data: Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            _userMessage.value = "Pixel color capture permission declined."
            return
        }
        try {
            val intent = ScreenCaptureProjectionService.createStartIntent(context, resultCode, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            val current = strategyConfig.value ?: StrategyConfig()
            applyStrategySelection(current.copy(pixelColorCaptureEnabled = true))
            _userMessage.value = "Pixel color capture active."
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting pixel capture service", e)
            _userMessage.value = "Unable to start pixel color capture."
        }
    }

    fun ensureOverlayServiceRunning(context: Context) {
        try {
            if (!Settings.canDrawOverlays(context) || ScreenEarnerOverlayService.isRunning) {
                _isOverlayServiceRunning.value = ScreenEarnerOverlayService.isRunning
                return
            }
            val intent = Intent(context, ScreenEarnerOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            _isOverlayServiceRunning.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure overlay service running", e)
        }
    }

    fun triggerEmergencyStop() {
        try {
            val limits = (riskLimits.value ?: RiskLimits()).copy(emergencyStop = true)
            saveRiskLimits(limits)
            quitAutomation()
            _userMessage.value = "MONITORING LOCK ENGAGED"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lock monitoring", e)
        }
    }

    fun clearEmergencyStop() {
        try {
            val limits = (riskLimits.value ?: RiskLimits()).copy(emergencyStop = false)
            saveRiskLimits(limits)
            _userMessage.value = "Monitoring Lock Cleared"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear monitoring lock", e)
        }
    }

    fun toggleOverlayService(context: Context) {
        try {
            if (!Settings.canDrawOverlays(context)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                _userMessage.value = "Please grant Display Over Other Apps permission."
                _isOverlayServiceRunning.value = false
                return
            }

            val intent = Intent(context, ScreenEarnerOverlayService::class.java)
            if (ScreenEarnerOverlayService.isRunning) {
                context.stopService(intent)
                _isOverlayServiceRunning.value = false
                _userMessage.value = "Floating Monitor Stopped."
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    _isOverlayServiceRunning.value = ScreenEarnerOverlayService.isRunning
                }, 200L)
                _isOverlayServiceRunning.value = true
                _userMessage.value = "Floating Monitor Started."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle overlay service", e)
            _isOverlayServiceRunning.value = ScreenEarnerOverlayService.isRunning
            _userMessage.value = "Unable to start overlay service: ${e.localizedMessage ?: "Check permissions"}"
        }
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    fun clearSessionLogs() {
        viewModelScope.launch {
            try {
                repository.clearSessionLogs()
                _userMessage.value = "Logs cleared"
            } catch (e: Exception) {
                Log.e(TAG, "Failed clearing session logs", e)
            }
        }
    }

    fun clearMultiplierRecords() {
        viewModelScope.launch {
            try {
                repository.clearMultiplierRecords()
                repository.clearAllCrashRecords()
                _userMessage.value = "Crash history cleared"
            } catch (e: Exception) {
                Log.e(TAG, "Failed clearing crash records", e)
            }
        }
    }

    fun setActiveProfile(profileId: String) {
        viewModelScope.launch {
            try {
                repository.setActiveProfile(profileId)
                _userMessage.value = "Profile activated: $profileId"
            } catch (e: Exception) {
                Log.e(TAG, "Save failed activating profile", e)
                _userMessage.value = "Failed to save settings, please check input"
            }
        }
    }

    // Legacy compatibility stubs so nothing breaks if older composables call them
    fun insertShortcut(shortcut: FloatingShortcut) {
        viewModelScope.launch { runCatching { repository.insertShortcut(shortcut) } }
    }
    fun updateShortcut(shortcut: FloatingShortcut) {
        viewModelScope.launch { runCatching { repository.updateShortcut(shortcut) } }
    }
    fun deleteShortcut(id: Long) {
        viewModelScope.launch { runCatching { repository.deleteShortcut(id) } }
    }
    fun addNewShortcut(label: String, type: ShortcutType, colorHex: String) {}
    fun updateShortcutVisualPosition(shortcutId: Long, visualX: Float, visualY: Float) {}
}
