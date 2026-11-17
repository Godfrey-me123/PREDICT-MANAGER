package com.example.data.engine

import com.example.data.database.ScreenEarnerRepository
import com.example.data.model.FloatingShortcut
import com.example.data.model.MultiplierRecord
import com.example.data.model.RiskLimits
import com.example.data.model.SessionLog
import com.example.data.model.SessionStats
import com.example.data.model.ShortcutType
import com.example.data.model.ActionExecutionRecord
import com.example.data.model.ActionExecutionState
import com.example.data.model.ActivityLogEntry
import com.example.data.model.BalanceDetectionStatus
import com.example.data.model.SiteButtonState
import com.example.data.model.StakeProgressionMode
import com.example.data.model.StrategyConfig
import com.example.service.AccessibilityController
import com.example.service.DetectedUiTargets
import com.example.service.ScreenEarnerAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.example.data.model.CalendarHourUtils
import com.example.data.model.CollectionSource
import com.example.data.model.ColorClassifier
import com.example.data.model.CrashRecord
import com.example.data.model.NotificationPriorityLevel
import com.example.data.model.StrategyNotificationType
import com.example.data.model.TimestampSource
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class AutomationState {
    IDLE,
    RUNNING,
    PAUSED,
    EMERGENCY_STOP
}

data class StartAutomationResult(
    val started: Boolean,
    val message: String
)

interface TargetUiInteractor {
    fun applyStake(
        newStake: Double,
        progressionMode: StakeProgressionMode,
        fallbackX: Float?,
        fallbackY: Float?,
        onComplete: (Boolean) -> Unit
    )

    fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit)
    fun clickCashOut(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit)
    fun cancelPendingGestures(reason: String) {}
}

class AccessibilityTargetUiInteractor : TargetUiInteractor {
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun applyStake(
        newStake: Double,
        progressionMode: StakeProgressionMode,
        fallbackX: Float?,
        fallbackY: Float?,
        onComplete: (Boolean) -> Unit
    ) {
        val controller = AccessibilityController.controllerInstance
        val service = ScreenEarnerAccessibilityService.instance
        val formatted = StrategyEngine.formatStakeAmount(newStake)

        fun tryActionExecutorFallback(controllerAlreadyTriedCoords: Boolean) {
            if (service == null) {
                onComplete(false)
                return
            }
            actionScope.launch {
                val ok = try {
                    val executor = service.actionExecutor
                    val fieldRes = executor.replaceFieldText("stake", formatted).let { first ->
                        if (first.success) first else executor.replaceFieldText("amount", formatted)
                    }.let { second ->
                        if (second.success) second else executor.replaceFieldText("bet", formatted)
                    }
                    if (fieldRes.success) {
                        true
                    } else if (!controllerAlreadyTriedCoords &&
                        fallbackX != null && fallbackY != null &&
                        fallbackX > 0f && fallbackY > 0f
                    ) {
                        executor.tap(fallbackX, fallbackY).success
                    } else {
                        false
                    }
                } catch (_: Throwable) {
                    false
                }
                onComplete(ok)
            }
        }

        if (controller != null) {
            controller.applyStakeToTargetApp(newStake, progressionMode, fallbackX, fallbackY) { ok ->
                if (ok) {
                    onComplete(true)
                } else {
                    tryActionExecutorFallback(controllerAlreadyTriedCoords = true)
                }
            }
        } else {
            tryActionExecutorFallback(controllerAlreadyTriedCoords = false)
        }
    }

    override fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
        val controller = AccessibilityController.controllerInstance
        val service = ScreenEarnerAccessibilityService.instance
        if (service != null && fallbackX != null && fallbackY != null && fallbackX > 0f && fallbackY > 0f) {
            actionScope.launch {
                val tapRes = runCatching { service.actionExecutor.tap(fallbackX, fallbackY) }.getOrNull()
                if (tapRes?.success == true) {
                    onComplete(true)
                } else if (controller != null) {
                    controller.clickBetTarget(fallbackX, fallbackY, onComplete)
                } else {
                    val textRes = runCatching { service.actionExecutor.clickText("Bet") }.getOrNull()
                    onComplete(textRes?.success == true)
                }
            }
            return
        }
        if (controller != null) {
            controller.clickBetTarget(fallbackX, fallbackY) { ok ->
                if (ok || service == null) {
                    onComplete(ok)
                } else {
                    actionScope.launch {
                        val textRes = runCatching { service.actionExecutor.clickText("Bet") }.getOrNull()
                        onComplete(textRes?.success == true)
                    }
                }
            }
        } else if (service != null) {
            actionScope.launch {
                val textRes = runCatching { service.actionExecutor.clickText("Bet") }.getOrNull()
                onComplete(textRes?.success == true)
            }
        } else {
            onComplete(false)
        }
    }

    override fun clickCashOut(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
        val controller = AccessibilityController.controllerInstance
        val service = ScreenEarnerAccessibilityService.instance
        if (service != null && fallbackX != null && fallbackY != null && fallbackX > 0f && fallbackY > 0f) {
            actionScope.launch {
                val tapRes = runCatching { service.actionExecutor.tap(fallbackX, fallbackY) }.getOrNull()
                if (tapRes?.success == true) {
                    onComplete(true)
                } else if (controller != null) {
                    controller.clickCashOutTarget(fallbackX, fallbackY, onComplete)
                } else {
                    val textRes = runCatching { service.actionExecutor.clickText("Cash Out") }.getOrNull()
                    onComplete(textRes?.success == true)
                }
            }
            return
        }
        if (controller != null) {
            controller.clickCashOutTarget(fallbackX, fallbackY) { ok ->
                if (ok || service == null) {
                    onComplete(ok)
                } else {
                    actionScope.launch {
                        val textRes = runCatching { service.actionExecutor.clickText("Cash Out") }.getOrNull()
                        onComplete(textRes?.success == true)
                    }
                }
            }
        } else if (service != null) {
            actionScope.launch {
                val textRes = runCatching { service.actionExecutor.clickText("Cash Out") }.getOrNull()
                onComplete(textRes?.success == true)
            }
        } else {
            onComplete(false)
        }
    }

    override fun cancelPendingGestures(reason: String) {
        AccessibilityController.controllerInstance?.haltAllAutomationGestures(reason)
    }
}

class AutomationEngine(
    val repository: ScreenEarnerRepository,
    val strategyEngine: StrategyEngine,
    val riskManager: RiskManager,
    val gameDetection: GameDetection,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val targetUiInteractor: TargetUiInteractor = AccessibilityTargetUiInteractor()
) {

    private val _automationState = MutableStateFlow(AutomationState.IDLE)
    val automationState: StateFlow<AutomationState> = _automationState.asStateFlow()

    private val _lastRiskViolation = MutableStateFlow<String?>(null)
    val lastRiskViolation: StateFlow<String?> = _lastRiskViolation.asStateFlow()

    private val _sessionStats = MutableStateFlow(SessionStats(sessionStartMs = System.currentTimeMillis()))
    val sessionStats: StateFlow<SessionStats> = _sessionStats.asStateFlow()

    private val _agentStatusTicker = MutableStateFlow("Waiting for crash game window...")
    val agentStatusTicker: StateFlow<String> = _agentStatusTicker.asStateFlow()

    private val _executionState = MutableStateFlow(ActionExecutionState.IDLE)
    val executionState: StateFlow<ActionExecutionState> = _executionState.asStateFlow()

    private val _executionRecord = MutableStateFlow<ActionExecutionRecord?>(null)
    val executionRecord: StateFlow<ActionExecutionRecord?> = _executionRecord.asStateFlow()

    private val _activityLogs = MutableStateFlow<List<ActivityLogEntry>>(emptyList())
    val activityLogs: StateFlow<List<ActivityLogEntry>> = _activityLogs.asStateFlow()

    private val _isStandbyOutsideSchedule = MutableStateFlow(false)
    val isStandbyOutsideSchedule: StateFlow<Boolean> = _isStandbyOutsideSchedule.asStateFlow()

    @Volatile
    private var isPausedBySchedule = false

    private val isActionInProgress = AtomicBoolean(false)
    private val actionStartedAtMs = AtomicLong(0L)
    private val liveCrashSeqCounter = AtomicLong(0L)
    var testBalanceOverride: Double? = if (targetUiInteractor !is AccessibilityTargetUiInteractor) 1000.0 else null

    @Volatile
    private var latestLimitsCache = RiskLimits()

    @Volatile
    private var latestConfigCache = StrategyConfig()

    private var activeSessionLoss = 0.0
    private var activeSessionRounds = 0
    private var activeSessionProfit = 0.0
    private var sessionWins = 0
    private var sessionLosses = 0
    private var sessionObservedCrashes = 0
    private var sessionStartTimestampMs = System.currentTimeMillis()

    // Per-round live execution tracking for BETTING_OPEN -> IN_FLIGHT -> CRASHED lifecycle
    private var currentRoundBetActive = false
    private var currentRoundStake = 0.0
    private var currentRoundCashedOut = false
    private var cashOutAttemptedThisRound = false
    private var currentRoundTriggerLabel = ""

    @Volatile
    private var calibratedShortcuts: List<FloatingShortcut> = emptyList()

    @Volatile
    private var activePlatformName: String = "Unknown"

    var onGestureDispatchRequested: ((targetX: Float, targetY: Float, actionLabel: String) -> Unit)? = null

    var onExecutionNotification: ((
        type: StrategyNotificationType,
        title: String,
        message: String,
        occurrenceKey: String,
        priority: NotificationPriorityLevel
    ) -> Unit)? = null

    private val recentExecutionNoticeKeys = ConcurrentHashMap<String, Long>()
    @Volatile
    private var lastBetFailureEpisodeKey: String? = null

    fun resetBetFailureEpisode() {
        lastBetFailureEpisodeKey = null
    }

    private fun emitExecutionNotice(
        type: StrategyNotificationType,
        title: String,
        message: String,
        occurrenceKey: String,
        priority: NotificationPriorityLevel = NotificationPriorityLevel.STANDARD,
        cooldownMs: Long = 30_000L
    ) {
        val now = System.currentTimeMillis()
        val last = recentExecutionNoticeKeys[occurrenceKey] ?: 0L
        if (cooldownMs > 0L && (now - last) < cooldownMs) return
        recentExecutionNoticeKeys[occurrenceKey] = now
        onExecutionNotification?.invoke(type, title, message, occurrenceKey, priority)
    }

    private fun emitEpisodeFailureNotice(
        episodeTag: String,
        title: String,
        message: String,
        priority: NotificationPriorityLevel = NotificationPriorityLevel.HIGH
    ) {
        val gs = gameDetection.gameState.value
        val roundPart = (gs.currentRoundId ?: gs.latestRoundId)?.takeIf { it.isNotBlank() } ?: "live"
        val key = "${episodeTag}_${roundPart}"
        if (lastBetFailureEpisodeKey == key) return
        lastBetFailureEpisodeKey = key
        emitExecutionNotice(
            type = StrategyNotificationType.MONITORING_ERROR,
            title = title,
            message = message,
            occurrenceKey = "exec_err_${key}_${System.currentTimeMillis() / 30_000L}",
            priority = priority,
            cooldownMs = 15_000L
        )
    }

    fun recordActivityLog(
        actionId: String,
        state: ActionExecutionState,
        target: String,
        coordinates: Pair<Float, Float>?,
        gestureResult: Boolean?,
        eventObserved: String?,
        verificationResult: Boolean?,
        errorDetails: String?
    ) {
        val entry = ActivityLogEntry(
            id = System.currentTimeMillis(),
            timestamp = System.currentTimeMillis(),
            actionId = actionId,
            state = state,
            target = target,
            coordinates = coordinates,
            gestureResult = gestureResult,
            eventObserved = eventObserved,
            verificationResult = verificationResult,
            errorDetails = errorDetails
        )
        _activityLogs.value = (_activityLogs.value + entry).takeLast(100)
    }

    fun resolveEffectiveBalance(): Double {
        if (testBalanceOverride != null) return testBalanceOverride!!
        val balState = gameDetection.balanceState.value
        if ((balState.status == BalanceDetectionStatus.VERIFIED || balState.status == BalanceDetectionStatus.DETECTED) &&
            balState.amount != null && balState.amount.isFinite() && balState.amount >= 0.0
        ) {
            return balState.amount
        }
        val gs = gameDetection.gameState.value
        val liveBalance = gs.detectedBalance
        if ((gs.balanceStatus == BalanceDetectionStatus.VERIFIED || gs.balanceStatus == BalanceDetectionStatus.DETECTED) &&
            liveBalance != null && liveBalance.isFinite() && liveBalance >= 0.0
        ) {
            return liveBalance
        }
        return 0.0
    }

    fun updateCachedLimits(limits: RiskLimits) {
        latestLimitsCache = limits
    }

    fun updateCachedConfig(config: StrategyConfig) {
        latestConfigCache = config
    }

    fun publishStatusTicker(message: String) {
        if (message.isNotBlank() && _agentStatusTicker.value != message) {
            _agentStatusTicker.value = message
        }
    }

    fun updateLiveDiagnostics(
        targets: DetectedUiTargets,
        gameState: com.example.data.model.GameState,
        config: StrategyConfig
    ) {
        latestConfigCache = config
        if (_isStandbyOutsideSchedule.value || gameState.isStandbyOutsideSchedule) {
            val standbyMsg = gameState.scheduleStandbyMessage
                ?: OperatingScheduleEvaluator.evaluate(config).standbyBanner
            publishStatusTicker(standbyMsg)
            return
        }

        if (!gameState.isDetected) {
            publishStatusTicker("Scanning active windows for crash game...")
            return
        }

        val ticker = when {
            gameState.liveDetectionLost ->
                "LIVE DETECTION LOST — Check active game tab or scroll view"
            _automationState.value == AutomationState.PAUSED ->
                "MONITORING PAUSED"
            gameState.status == "IN_FLIGHT" ->
                "${gameState.displayedGameName}: Plane in flight (${String.format(Locale.US, "%.2f", gameState.currentMultiplier)}x)"
            gameState.status == "CRASHED" && gameState.latestCrashMultiplier != null ->
                "${gameState.displayedGameName}: Crashed at ${String.format(Locale.US, "%.2f", gameState.latestCrashMultiplier)}x (${gameState.latestCrashColorCategory.displayName})"
            else ->
                "Monitoring ${gameState.displayedGameName} (${gameState.platformName})"
        }
        publishStatusTicker(ticker)
    }

    fun updateCalibratedShortcuts(shortcuts: List<FloatingShortcut>) {
        calibratedShortcuts = shortcuts
    }

    fun updateActivePlatform(platformName: String) {
        if (platformName.isNotBlank()) {
            activePlatformName = platformName
        }
    }

    fun findActiveShortcut(type: ShortcutType): FloatingShortcut? {
        val siteKey = GameDetection.resolveSiteKey(activePlatformName)
        val candidates = calibratedShortcuts.filter {
            it.type == type && it.isEnabled && it.isCalibrated && it.targetX > 0f && it.targetY > 0f
        }
        if (candidates.isEmpty()) return null
        if (siteKey.isNotEmpty()) {
            val siteMatch = candidates.firstOrNull { it.targetPlatform.equals(siteKey, ignoreCase = true) }
            if (siteMatch != null) return siteMatch
        }
        return candidates.first()
    }

    fun verifyReadyForMonitoring(isAccessibilityConnected: Boolean, limits: RiskLimits): StartAutomationResult {
        if (limits.emergencyStop) {
            return StartAutomationResult(false, "Monitoring locked by Pause / Emergency Lock.")
        }
        if (!isAccessibilityConnected) {
            return StartAutomationResult(false, "Enable Accessibility Service so Predict Manager can monitor live rounds.")
        }
        return StartAutomationResult(true, "Live monitoring started cleanly.")
    }

    fun startMonitoringWithValidation(
        config: StrategyConfig,
        limits: RiskLimits,
        isAccessibilityConnected: Boolean
    ): StartAutomationResult {
        val check = verifyReadyForMonitoring(isAccessibilityConnected, limits)
        if (!check.started) {
            if (limits.emergencyStop) {
                _automationState.value = AutomationState.EMERGENCY_STOP
            }
            _lastRiskViolation.value = check.message
            return check
        }
        startAutomation(config, limits)
        return check
    }

    fun restoreMonitoringRunningPreservingSession(
        config: StrategyConfig,
        limits: RiskLimits,
        confirmedCrashCount: Int
    ) {
        latestConfigCache = config
        latestLimitsCache = limits
        if (limits.emergencyStop) {
            _automationState.value = AutomationState.EMERGENCY_STOP
            return
        }
        sessionObservedCrashes = confirmedCrashCount.coerceAtLeast(0)
        isPausedBySchedule = false
        val scheduleEval = OperatingScheduleEvaluator.evaluate(config)
        if (!scheduleEval.isWithinOperatingHours) {
            isPausedBySchedule = true
            _isStandbyOutsideSchedule.value = true
            _automationState.value = AutomationState.PAUSED
            gameDetection.updateScheduleStandby(true, scheduleEval.standbyBanner)
        } else {
            _isStandbyOutsideSchedule.value = false
            _automationState.value = AutomationState.RUNNING
            gameDetection.updateScheduleStandby(false, null)
        }
        publishSessionStats(limitHaltReason = null)
    }

    fun setObservedCrashCount(count: Int) {
        sessionObservedCrashes = count.coerceAtLeast(0)
        publishSessionStats(limitHaltReason = _sessionStats.value.limitHaltReason)
    }

    fun verifyReadyToStart(isAccessibilityConnected: Boolean, limits: RiskLimits): StartAutomationResult {
        if (limits.emergencyStop) {
            return StartAutomationResult(false, "Emergency Stop is active in Risk Settings.")
        }
        val betHand = findActiveShortcut(ShortcutType.BET)
        if (betHand == null) {
            return StartAutomationResult(false, "Calibrate at least one BET hand target first (single-tap BET icon).")
        }
        if (!isAccessibilityConnected) {
            return StartAutomationResult(false, "Enable Accessibility Service so Predict Manager can read multipliers.")
        }
        return StartAutomationResult(true, "Automation started cleanly.")
    }

    fun startAutomationWithValidation(
        config: StrategyConfig,
        limits: RiskLimits,
        isAccessibilityConnected: Boolean
    ): StartAutomationResult {
        val check = verifyReadyToStart(isAccessibilityConnected, limits)
        if (!check.started) {
            if (limits.emergencyStop) {
                _automationState.value = AutomationState.EMERGENCY_STOP
            }
            _lastRiskViolation.value = check.message
            return check
        }
        startAutomation(config, limits)
        return check
    }

    fun startAutomation(config: StrategyConfig, limits: RiskLimits) {
        latestConfigCache = config
        latestLimitsCache = limits
        if (limits.emergencyStop) {
            _automationState.value = AutomationState.EMERGENCY_STOP
            _lastRiskViolation.value = "Emergency Stop is active in Risk Settings."
            return
        }
        resetSessionStats()
        isPausedBySchedule = false
        val scheduleEval = OperatingScheduleEvaluator.evaluate(config)
        if (!scheduleEval.isWithinOperatingHours) {
            isPausedBySchedule = true
            _isStandbyOutsideSchedule.value = true
            _automationState.value = AutomationState.PAUSED
            gameDetection.updateScheduleStandby(true, scheduleEval.standbyBanner)
            publishStatusTicker(scheduleEval.standbyBanner)
        } else {
            _isStandbyOutsideSchedule.value = false
            _automationState.value = AutomationState.RUNNING
            gameDetection.updateScheduleStandby(false, null)
            publishStatusTicker("Checking strategy for current game state...")
        }
        _lastRiskViolation.value = null
        currentRoundBetActive = false
        currentRoundCashedOut = false
        cashOutAttemptedThisRound = false
        isActionInProgress.set(false)
        lastBetFailureEpisodeKey = null
        strategyEngine.reset(config.baseStake)
    }

    fun resetSessionStats(currentTimeMs: Long = System.currentTimeMillis()) {
        sessionStartTimestampMs = currentTimeMs
        activeSessionLoss = 0.0
        activeSessionRounds = 0
        activeSessionProfit = 0.0
        sessionWins = 0
        sessionLosses = 0
        sessionObservedCrashes = 0
        _lastRiskViolation.value = null
        publishSessionStats(limitHaltReason = null)
    }

    fun syncSessionWithCrashHistory(records: List<MultiplierRecord>, limits: RiskLimits) {
        latestLimitsCache = limits
        if (_automationState.value == AutomationState.RUNNING) {
            enforceLimitsNow(limits)
        }
    }

    fun settleActiveRoundBetIfNeeded(
        crashedMultiplier: Double,
        config: StrategyConfig,
        limits: RiskLimits = latestLimitsCache,
        platformName: String = activePlatformName.ifBlank { gameDetection.gameState.value.platformName },
        currentTimeMs: Long = System.currentTimeMillis()
    ) {
        cashOutAttemptedThisRound = false
        if (_automationState.value == AutomationState.RUNNING && currentRoundBetActive) {
            settleActiveRoundBet(
                stakeUsed = currentRoundStake,
                crashedMultiplier = crashedMultiplier,
                cashedOutInFlight = currentRoundCashedOut,
                triggerLabel = currentRoundTriggerLabel,
                config = config,
                limits = limits,
                platformName = platformName,
                currentTimeMs = currentTimeMs
            )
            currentRoundBetActive = false
            currentRoundCashedOut = false
            gameDetection.setBetPlaced(false)
        }
    }

    fun recordAuthoritativeCompletedRound(
        multiplier: Double,
        config: StrategyConfig,
        sessionCrashCount: Int,
        currentTimeMs: Long = System.currentTimeMillis(),
        limits: RiskLimits = latestLimitsCache,
        platformName: String = activePlatformName.ifBlank { gameDetection.gameState.value.platformName }
    ) {
        latestConfigCache = config
        latestLimitsCache = limits
        sessionObservedCrashes = sessionCrashCount.coerceAtLeast(0)
        publishSessionStats()
        settleActiveRoundBetIfNeeded(
            crashedMultiplier = multiplier,
            config = config,
            limits = limits,
            platformName = platformName,
            currentTimeMs = currentTimeMs
        )
        val trigger = strategyEngine.processCompletedRound(multiplier, config, currentTimeMs)
        val stratState = strategyEngine.getState()
        val triggerName = formatTriggerLabel(trigger, stratState)
        gameDetection.updateStrategyTelemetry(stratState, triggerName)
    }

    fun enforceLimitsNow(limits: RiskLimits): Boolean {
        latestLimitsCache = limits
        if (_automationState.value != AutomationState.RUNNING) return false
        val check = riskManager.evaluateSessionLimits(
            currentSessionProfit = activeSessionProfit,
            currentSessionLoss = activeSessionLoss,
            limits = limits
        )
        if (!check.isAllowed) {
            haltAutomationForLimit(check.violationReason ?: "Session limit reached.")
            return true
        }
        return false
    }

    private fun haltAutomationForLimit(reason: String) {
        currentRoundBetActive = false
        currentRoundCashedOut = false
        cashOutAttemptedThisRound = false
        isActionInProgress.set(false)
        gameDetection.setBetPlaced(false)
        targetUiInteractor.cancelPendingGestures(reason)
        _automationState.value = AutomationState.PAUSED
        _lastRiskViolation.value = reason
        publishSessionStats(limitHaltReason = reason)
        emitExecutionNotice(
            type = StrategyNotificationType.MONITORING_ERROR,
            title = "Automation Paused (Safety Guard)",
            message = reason,
            occurrenceKey = "halt_${reason.hashCode()}",
            priority = NotificationPriorityLevel.HIGH,
            cooldownMs = 30_000L
        )
    }

    private fun publishSessionStats(limitHaltReason: String? = _sessionStats.value.limitHaltReason) {
        val stats = SessionStats(
            sessionStartMs = sessionStartTimestampMs,
            winCount = sessionWins,
            lossCount = sessionLosses,
            totalProfit = activeSessionProfit,
            observedCrashCount = sessionObservedCrashes,
            limitHaltReason = limitHaltReason
        )
        _sessionStats.value = stats
        gameDetection.updateSessionStats(stats)
    }

    fun pauseAutomation() {
        isPausedBySchedule = false
        if (_automationState.value == AutomationState.RUNNING) {
            _automationState.value = AutomationState.PAUSED
        }
    }

    fun resumeAutomation(config: StrategyConfig, limits: RiskLimits) {
        latestConfigCache = config
        latestLimitsCache = limits
        if (_automationState.value == AutomationState.PAUSED) {
            isPausedBySchedule = false
            val check = riskManager.evaluateSessionLimits(
                currentSessionProfit = activeSessionProfit,
                currentSessionLoss = activeSessionLoss,
                limits = limits
            )
            if (!check.isAllowed) {
                val reason = check.violationReason ?: "Session limit reached."
                if (limits.emergencyStop) {
                    _automationState.value = AutomationState.EMERGENCY_STOP
                }
                _lastRiskViolation.value = reason
                publishSessionStats(limitHaltReason = reason)
                return
            }
            _automationState.value = AutomationState.RUNNING
            _lastRiskViolation.value = null
            publishSessionStats(limitHaltReason = null)
        }
    }

    /**
     * QUIT: Stops active automation and cancels pending actions,
     * but keeps floating bubble/utility and last session stats active on screen.
     */
    fun quitAutomation() {
        isPausedBySchedule = false
        _isStandbyOutsideSchedule.value = false
        gameDetection.updateScheduleStandby(false, null)
        targetUiInteractor.cancelPendingGestures("Automation stopped by user")
        _automationState.value = AutomationState.IDLE
        currentRoundBetActive = false
        currentRoundCashedOut = false
        cashOutAttemptedThisRound = false
        isActionInProgress.set(false)
        lastBetFailureEpisodeKey = null
        gameDetection.setBetPlaced(false)
        strategyEngine.reset()
        publishStatusTicker("Automation stopped.")
    }

    /**
     * Enforces configured Scheduled Operating Hours: pauses automation into Standby when outside
     * the chosen window, and automatically resumes monitoring when back inside scheduled hours.
     */
    fun evaluateOperatingSchedule(
        config: StrategyConfig,
        limits: RiskLimits = latestLimitsCache,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Boolean {
        latestConfigCache = config
        latestLimitsCache = limits
        val scheduleEval = OperatingScheduleEvaluator.evaluate(config, currentTimeMs)
        if (!scheduleEval.isWithinOperatingHours) {
            _isStandbyOutsideSchedule.value = true
            gameDetection.updateScheduleStandby(true, scheduleEval.standbyBanner)
            publishStatusTicker(scheduleEval.standbyBanner)
            if (_automationState.value == AutomationState.RUNNING) {
                isPausedBySchedule = true
                currentRoundBetActive = false
                currentRoundCashedOut = false
                cashOutAttemptedThisRound = false
                isActionInProgress.set(false)
                gameDetection.setBetPlaced(false)
                targetUiInteractor.cancelPendingGestures(scheduleEval.standbyBanner)
                _automationState.value = AutomationState.PAUSED
            }
            return false
        } else {
            val wasStandby = _isStandbyOutsideSchedule.value
            _isStandbyOutsideSchedule.value = false
            gameDetection.updateScheduleStandby(false, null)
            if (isPausedBySchedule && _automationState.value == AutomationState.PAUSED) {
                isPausedBySchedule = false
                resumeAutomation(config, limits)
                publishStatusTicker("Scheduled window active — Monitoring resumed")
            } else if (wasStandby) {
                publishStatusTicker("Scheduled window active — Ready")
            }
            return true
        }
    }

    /**
     * Updates pending range-wait countdown timers (5m / 10m), checks scheduled operating hours,
     * and enforces the 3-minute timeout guard.
     */
    fun tickTimers(config: StrategyConfig, currentTimeMs: Long = System.currentTimeMillis()) {
        evaluateOperatingSchedule(config, latestLimitsCache, currentTimeMs)
        val timerTrigger = strategyEngine.checkPendingTimers(config, currentTimeMs)
        if (timerTrigger is StrategyTrigger.SessionTimedOut) {
            currentRoundBetActive = false
            currentRoundCashedOut = false
            cashOutAttemptedThisRound = false
            isActionInProgress.set(false)
            gameDetection.setBetPlaced(false)
        }
        val stratState = strategyEngine.getState()
        val triggerLabel = formatTriggerLabel(timerTrigger, stratState)
        gameDetection.updateStrategyTelemetry(stratState, triggerLabel)
    }

    /**
     * Called continuously as game phases, site button states, & live multipliers arrive from AccessibilityService:
     * - During BETTING_OPEN: places calibrated BET ONLY when siteButtonState == IDLE_READY_TO_BET
     *   (never double-clicks when "Cancel" / "Waiting for next round" is visible).
     * - During IN_FLIGHT: triggers calibrated CASH OUT when liveMultiplier >= targetMultiplier and Cash Out is available.
     */
    fun handlePhaseUpdate(
        phase: String,
        liveMultiplier: Double,
        config: StrategyConfig,
        limits: RiskLimits,
        currentTimeMs: Long = System.currentTimeMillis(),
        siteButtonState: SiteButtonState = SiteButtonState.IDLE_READY_TO_BET,
        observationOnly: Boolean = false
    ) {
        latestConfigCache = config
        latestLimitsCache = limits
        if (!evaluateOperatingSchedule(config, limits, currentTimeMs)) return
        if (_automationState.value != AutomationState.RUNNING) return
        if (observationOnly) {
            // AutoPredictor Prompt 1: Strictly observation & analysis only — never tap game controls or edit stakes
            return
        }
        if (enforceLimitsNow(limits)) return

        val stratState = strategyEngine.getState()

        if (siteButtonState == SiteButtonState.BET_QUEUED) {
            publishStatusTicker("Button state: CANCEL (Red) -> Waiting for round start")
            return
        }

        if (phase == "BETTING_OPEN" &&
            siteButtonState == SiteButtonState.IDLE_READY_TO_BET &&
            stratState.isBettingSessionActive &&
            !currentRoundBetActive &&
            config.isAutoBetEnabled
        ) {
            // Recover from any stale in-progress lock older than 7 seconds
            if (isActionInProgress.get() && (System.currentTimeMillis() - actionStartedAtMs.get()) > 7_000L) {
                isActionInProgress.set(false)
            }
            // Prevent duplicate or overlapping action executions
            if (!isActionInProgress.compareAndSet(false, true)) {
                return
            }
            actionStartedAtMs.set(System.currentTimeMillis())

            try {
                val gs = gameDetection.gameState.value
                val activeGameId = gs.gameId.ifBlank { "unknown" }
                val activeGameName = gs.displayedGameName.ifBlank { "Unknown" }
                val activeRoundId = (gs.currentRoundId ?: gs.latestRoundId)?.takeIf { it.isNotBlank() } ?: "Unknown"
                val strategyLabel = stratState.activeRuleLabel ?: "Auto Progression (Level ${stratState.currentStakeLevel})"

                val uiTargets = AccessibilityController.controllerInstance?.getLatestUiTargets()
                val betShortcut = findActiveShortcut(ShortcutType.BET)
                val fallbackBetX = betShortcut?.targetX
                    ?: uiTargets?.betButtonBounds?.takeIf { !it.isEmpty }?.centerX()?.toFloat()
                val fallbackBetY = betShortcut?.targetY
                    ?: uiTargets?.betButtonBounds?.takeIf { !it.isEmpty }?.centerY()?.toFloat()
                val targetCoords = if (fallbackBetX != null && fallbackBetY != null) Pair(fallbackBetX, fallbackBetY) else null
                val actionId = "bet_${currentTimeMs}_${(1000..9999).random()}"

                val proposedStake = stratState.currentCalculatedStake
                val contextDetails = "game=$activeGameName ($activeGameId), round=$activeRoundId, strategy=$strategyLabel, stake=${CurrencyManager.formatAmount(proposedStake)}"

                _executionState.value = ActionExecutionState.VALIDATING_GAME_STATE
                val initialRecord = ActionExecutionRecord(
                    actionId = actionId,
                    operationName = "PLACE_BET",
                    state = ActionExecutionState.VALIDATING_GAME_STATE,
                    startTimestampMs = currentTimeMs,
                    targetCoordinates = targetCoords,
                    details = contextDetails
                )
                _executionRecord.value = initialRecord
                gameDetection.updateExecutionRecord(initialRecord)
                recordActivityLog(actionId, ActionExecutionState.VALIDATING_GAME_STATE, "PLACE_BET", targetCoords, null, "Validating balance and risk ($contextDetails)", null, null)

                // 1. Accessibility Service connection check (in production)
                if (targetUiInteractor is AccessibilityTargetUiInteractor && !ScreenEarnerAccessibilityService.isServiceConnected()) {
                    val failReason = "Accessibility Service is not connected."
                    val failedRecord = initialRecord.copy(
                        state = ActionExecutionState.FAILED,
                        completionTimestampMs = System.currentTimeMillis(),
                        isSuccess = false,
                        failureReason = failReason
                    )
                    _executionRecord.value = failedRecord
                    _executionState.value = ActionExecutionState.FAILED
                    gameDetection.updateExecutionRecord(failedRecord)
                    recordActivityLog(actionId, ActionExecutionState.FAILED, "PLACE_BET", targetCoords, false, contextDetails, false, failReason)
                    publishStatusTicker(failReason)
                    emitEpisodeFailureNotice("a11y_disconnected", "Bet Paused — Accessibility Off", failReason)
                    isActionInProgress.set(false)
                    return
                }

                // 2. Balance Usability Check
                val balState = gameDetection.balanceState.value
                if (testBalanceOverride == null && !balState.isUsableForBet) {
                    val failReason = when (balState.status) {
                        BalanceDetectionStatus.UNAVAILABLE -> "Cannot place bet: Balance is UNAVAILABLE (${balState.failureReason ?: "Not visible"})."
                        BalanceDetectionStatus.STALE -> "Cannot place bet: Balance is STALE (${balState.failureReason ?: "Needs refresh"})."
                        BalanceDetectionStatus.SCANNING -> "Cannot place bet: Balance is still SCANNING. Waiting for balance reading."
                        else -> "Cannot place bet: Balance is unavailable."
                    }
                    val failedRecord = initialRecord.copy(
                        state = ActionExecutionState.FAILED,
                        completionTimestampMs = System.currentTimeMillis(),
                        isSuccess = false,
                        failureReason = failReason
                    )
                    _executionRecord.value = failedRecord
                    _executionState.value = ActionExecutionState.FAILED
                    gameDetection.updateExecutionRecord(failedRecord)
                    recordActivityLog(actionId, ActionExecutionState.FAILED, "PLACE_BET", targetCoords, null, contextDetails, false, failReason)
                    publishStatusTicker(failReason)
                    emitEpisodeFailureNotice("balance_${balState.status.name}", "Bet Blocked — Balance Unreadable", failReason)
                    isActionInProgress.set(false)
                    return
                }

                // 3. Risk Management Check
                val effectiveBalance = resolveEffectiveBalance()
                val stakeInstruction = strategyEngine.getPendingStakeInstruction()
                val effectiveMode = if (stakeInstruction.isSessionActive &&
                    stakeInstruction.stakeLevel == stratState.currentStakeLevel
                ) {
                    stakeInstruction.progressionMode
                } else {
                    stratState.activeProgressionMode
                }

                val riskCheck = riskManager.validateBetExecution(
                    proposedStake = proposedStake,
                    currentSessionLoss = activeSessionLoss,
                    currentRounds = activeSessionRounds,
                    currentProgressionLevel = stratState.currentStakeLevel,
                    currentBalance = effectiveBalance,
                    limits = limits,
                    currentSessionProfit = activeSessionProfit,
                    balanceStatus = if (testBalanceOverride != null) BalanceDetectionStatus.VERIFIED else balState.status,
                    balanceFailureReason = balState.failureReason
                )

                if (!riskCheck.isAllowed) {
                    val failReason = riskCheck.violationReason ?: "Risk limit reached."
                    val failedRecord = initialRecord.copy(
                        state = ActionExecutionState.FAILED,
                        completionTimestampMs = System.currentTimeMillis(),
                        isSuccess = false,
                        failureReason = failReason
                    )
                    _executionRecord.value = failedRecord
                    _executionState.value = ActionExecutionState.FAILED
                    gameDetection.updateExecutionRecord(failedRecord)
                    recordActivityLog(actionId, ActionExecutionState.FAILED, "PLACE_BET", targetCoords, null, contextDetails, false, failReason)
                    publishStatusTicker(failReason)
                    haltAutomationForLimit(failReason)
                    isActionInProgress.set(false)
                    return
                }

                // 4. Action Execution: Apply Stake then Click Bet
                val stakeFallbackRect = when (effectiveMode) {
                    StakeProgressionMode.DOUBLE_AMOUNT -> uiTargets?.doubleButtonBounds
                    StakeProgressionMode.ADD_AMOUNT -> uiTargets?.addButtonBounds
                }?.takeIf { !it.isEmpty }
                val stakeFallbackX = stakeFallbackRect?.centerX()?.toFloat()
                val stakeFallbackY = stakeFallbackRect?.centerY()?.toFloat()

                _executionState.value = ActionExecutionState.EXECUTING_ACTION
                val executingRecord = initialRecord.copy(state = ActionExecutionState.EXECUTING_ACTION)
                _executionRecord.value = executingRecord
                gameDetection.updateExecutionRecord(executingRecord)
                recordActivityLog(actionId, ActionExecutionState.EXECUTING_ACTION, "APPLY_STAKE", stakeFallbackX?.let { x -> stakeFallbackY?.let { y -> x to y } }, null, "Applying stake ${CurrencyManager.formatAmount(proposedStake)} ($contextDetails)", null, null)
                publishStatusTicker("Updating stake to ${CurrencyManager.formatAmount(proposedStake)}...")

                targetUiInteractor.applyStake(
                    newStake = proposedStake,
                    progressionMode = effectiveMode,
                    fallbackX = stakeFallbackX,
                    fallbackY = stakeFallbackY
                ) { stakeOk ->
                    if (!stakeOk) {
                        val failReason = "Stake modification failed or was not verified by game input for ${CurrencyManager.formatAmount(proposedStake)}."
                        val failedRecord = initialRecord.copy(
                            state = ActionExecutionState.FAILED,
                            completionTimestampMs = System.currentTimeMillis(),
                            isSuccess = false,
                            failureReason = failReason
                        )
                        _executionRecord.value = failedRecord
                        _executionState.value = ActionExecutionState.FAILED
                        gameDetection.updateExecutionRecord(failedRecord)
                        recordActivityLog(actionId, ActionExecutionState.FAILED, "APPLY_STAKE", null, false, contextDetails, false, failReason)
                        publishStatusTicker("Stake update failed")
                        emitEpisodeFailureNotice("stake_edit", "Stake Edit Failed", failReason)
                        isActionInProgress.set(false)
                        return@applyStake
                    }

                    publishStatusTicker("Button state: BET (Green) -> Placing bet now")
                    recordActivityLog(actionId, ActionExecutionState.EXECUTING_ACTION, "CLICK_BET", targetCoords, null, "Stake accepted. Clicking BET target ($contextDetails).", null, null)

                    targetUiInteractor.clickBet(fallbackBetX, fallbackBetY) { gestureOk ->
                        if (!gestureOk) {
                            val failReason = "Bet click failed or gesture was rejected."
                            val failedRecord = initialRecord.copy(
                                state = ActionExecutionState.FAILED,
                                completionTimestampMs = System.currentTimeMillis(),
                                isSuccess = false,
                                gestureDispatched = false,
                                failureReason = failReason
                            )
                            _executionRecord.value = failedRecord
                            _executionState.value = ActionExecutionState.FAILED
                            gameDetection.updateExecutionRecord(failedRecord)
                            recordActivityLog(actionId, ActionExecutionState.FAILED, "CLICK_BET", targetCoords, false, "Gesture rejected ($contextDetails)", false, failReason)
                            publishStatusTicker("Bet click failed")
                            emitEpisodeFailureNotice("bet_click", "Bet Click Failed", failReason)
                            isActionInProgress.set(false)
                            return@clickBet
                        }

                        // 1. Gesture Dispatched!
                        // 2. Target interface responded / entering verification
                        _executionState.value = ActionExecutionState.VERIFYING_ACTION
                        val verifyingRecord = initialRecord.copy(
                            state = ActionExecutionState.VERIFYING_ACTION,
                            gestureDispatched = true,
                            interfaceResponded = true
                        )
                        _executionRecord.value = verifyingRecord
                        gameDetection.updateExecutionRecord(verifyingRecord)
                        recordActivityLog(actionId, ActionExecutionState.VERIFYING_ACTION, "CLICK_BET", targetCoords, true, "Gesture dispatched ($contextDetails). Waiting for confirmation.", null, null)
                        publishStatusTicker("Gesture dispatched. Verifying site confirmation...")

                        if (fallbackBetX != null && fallbackBetY != null) {
                            onGestureDispatchRequested?.invoke(fallbackBetX, fallbackBetY, "PLACE BET")
                        }

                        // 3. Expected action state confirmed:
                        fun confirmBetSuccess() {
                            val completedRecord = initialRecord.copy(
                                state = ActionExecutionState.COMPLETED,
                                completionTimestampMs = System.currentTimeMillis(),
                                isSuccess = true,
                                gestureDispatched = true,
                                interfaceResponded = true,
                                stateConfirmed = true
                            )
                            _executionRecord.value = completedRecord
                            _executionState.value = ActionExecutionState.COMPLETED
                            gameDetection.updateExecutionRecord(completedRecord)
                            recordActivityLog(actionId, ActionExecutionState.COMPLETED, "PLACE_BET", targetCoords, true, "Site confirmed bet ($contextDetails)", true, null)
                            publishStatusTicker("Bet confirmed on site. Waiting for round start.")

                            // DO NOT mark round as having an active bet until verified!
                            currentRoundBetActive = true
                            currentRoundStake = proposedStake
                            currentRoundCashedOut = false
                            cashOutAttemptedThisRound = false
                            currentRoundTriggerLabel = strategyLabel
                            activeSessionRounds += 1
                            lastBetFailureEpisodeKey = null
                            gameDetection.setBetPlaced(true)

                            StrategyEngine.triggerAction(
                                AutomationActionEvent(
                                    actionType = ActionType.BET,
                                    amountOrMultiplier = proposedStake,
                                    reason = "$strategyLabel (${CurrencyManager.formatCompact(proposedStake)})",
                                    timestamp = currentTimeMs
                                )
                            )
                            emitExecutionNotice(
                                type = StrategyNotificationType.AUTOMATION_ACTION,
                                title = "Bet Placed: ${CurrencyManager.formatAmount(proposedStake)}",
                                message = "Confirmed on $activeGameName ($strategyLabel, Round $activeRoundId).",
                                occurrenceKey = "bet_ok_${actionId}",
                                priority = NotificationPriorityLevel.STANDARD,
                                cooldownMs = 0L
                            )
                            isActionInProgress.set(false)
                        }

                        if (targetUiInteractor !is AccessibilityTargetUiInteractor ||
                            gameDetection.gameState.value.siteButtonState == SiteButtonState.BET_QUEUED
                        ) {
                            confirmBetSuccess()
                        } else {
                            // Launch verification coroutine with 2500ms timeout and guaranteed lock release
                            val verifyJob = scope.launch {
                                try {
                                    var elapsed = 0L
                                    var confirmed = false
                                    while (elapsed < 2500L) {
                                        delay(100L)
                                        elapsed += 100L
                                        val currentButtonState = gameDetection.gameState.value.siteButtonState
                                        if (currentButtonState == SiteButtonState.BET_QUEUED) {
                                            confirmed = true
                                            break
                                        }
                                    }
                                    if (confirmed) {
                                        confirmBetSuccess()
                                    } else {
                                        val failReason = "Action verification timed out (site button remained ${gameDetection.gameState.value.siteButtonState})."
                                        val timeoutRecord = initialRecord.copy(
                                            state = ActionExecutionState.FAILED,
                                            completionTimestampMs = System.currentTimeMillis(),
                                            isSuccess = false,
                                            gestureDispatched = true,
                                            interfaceResponded = false,
                                            stateConfirmed = false,
                                            failureReason = failReason
                                        )
                                        _executionRecord.value = timeoutRecord
                                        _executionState.value = ActionExecutionState.FAILED
                                        gameDetection.updateExecutionRecord(timeoutRecord)
                                        recordActivityLog(actionId, ActionExecutionState.FAILED, "PLACE_BET", targetCoords, true, "Timeout waiting for button change ($contextDetails)", false, failReason)
                                        publishStatusTicker("Verification timeout: Site did not confirm bet")
                                        emitEpisodeFailureNotice("bet_verify_timeout", "Bet Not Confirmed", failReason)
                                    }
                                } finally {
                                    isActionInProgress.set(false)
                                }
                            }
                            verifyJob.invokeOnCompletion {
                                isActionInProgress.set(false)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                isActionInProgress.set(false)
                throw t
            }
        } else if (phase == "IN_FLIGHT") {
            evaluateInFlightMultiplier(liveMultiplier, config, siteButtonState, limits)
        }
    }

    /**
     * Triggered when a round reaches CRASHED (Flew Away).
     * Records MultiplierRecord + exact crash timestamp, settles any active round bet,
     * and evaluates strategy rules for the next round.
     */
    fun handleRoundCompleted(
        multiplier: Double,
        config: StrategyConfig,
        limits: RiskLimits,
        platformName: String = gameDetection.gameState.value.platformName,
        currentTimeMs: Long = System.currentTimeMillis(),
        observationOnly: Boolean = false
    ) {
        sessionObservedCrashes += 1
        publishSessionStats()

        if (observationOnly) {
            val trigger = strategyEngine.processCompletedRound(multiplier, config, currentTimeMs)
            val stratState = strategyEngine.getState()
            val triggerName = formatTriggerLabel(trigger, stratState)
            gameDetection.updateStrategyTelemetry(stratState, triggerName)
            return
        }

        val gs = gameDetection.gameState.value
        val activeGameId = gs.gameId.ifBlank { "unknown" }
        val activeGameName = gs.displayedGameName.ifBlank { "Unknown" }
        val resolvedCat = gs.latestCrashColorCategory.takeIf { gs.latestCrashTimestampMs == currentTimeMs }
            ?: ColorClassifier.fallbackCategoryForMultiplier(multiplier, config.lowThreshold, config.bigThreshold)
        val resolvedHex = gs.latestCrashColorHex.takeIf { gs.latestCrashTimestampMs == currentTimeMs && it.isNotBlank() }
            ?: resolvedCat.defaultHex

        val seq = liveCrashSeqCounter.incrementAndGet().toInt()
        val dedup = MultiplierRecord.buildFallbackDedupKey(
            gameId = activeGameId,
            platform = platformName,
            roundId = null,
            gameTimestampMs = null,
            observationTimestampMs = currentTimeMs,
            crashTimestamp = currentTimeMs,
            multiplier = multiplier,
            sequenceIndex = seq
        )
        scope.launch {
            repository.addMultiplierRecord(
                MultiplierRecord(
                    sessionId = gs.activeSessionId,
                    gameId = activeGameId,
                    multiplier = multiplier,
                    gameTimestampMs = null,
                    observationTimestampMs = currentTimeMs,
                    crashTimestamp = currentTimeMs,
                    platform = platformName,
                    timestampSource = TimestampSource.LOCAL_OBSERVATION,
                    collectionSource = CollectionSource.LIVE_CRASH,
                    collectionStatus = gameDetection.historyMetadata.value.status,
                    dedupKey = dedup,
                    sequenceIndex = seq
                )
            )
            repository.insertCrashRecord(
                CrashRecord(
                    sessionId = gs.activeSessionId,
                    gameId = activeGameId,
                    gameName = activeGameName,
                    multiplier = multiplier,
                    detectedColorHex = resolvedHex,
                    colorCategory = resolvedCat,
                    isPixelColorVerified = gs.isLatestCrashColorVerified,
                    crashTimestamp = currentTimeMs,
                    hourStartTimestamp = CalendarHourUtils.hourStartFor(currentTimeMs),
                    sourcePackage = gs.sourcePackage,
                    roundIdentifier = null,
                    confidence = gs.detectionConfidence.coerceAtLeast(0.8f),
                    manuallyCorrected = false,
                    collectionSource = CollectionSource.LIVE_CRASH,
                    dedupKey = dedup
                )
            )
        }

        // If a bet was placed in this round during BETTING_OPEN, settle it at CRASHED
        cashOutAttemptedThisRound = false
        if (_automationState.value == AutomationState.RUNNING && currentRoundBetActive) {
            settleActiveRoundBet(
                stakeUsed = currentRoundStake,
                crashedMultiplier = multiplier,
                cashedOutInFlight = currentRoundCashedOut,
                triggerLabel = currentRoundTriggerLabel,
                config = config,
                limits = limits,
                platformName = platformName,
                currentTimeMs = currentTimeMs
            )
            currentRoundBetActive = false
            currentRoundCashedOut = false
            gameDetection.setBetPlaced(false)
        } else if (_automationState.value == AutomationState.RUNNING &&
            strategyEngine.getState().isBettingSessionActive &&
            config.isAutoBetEnabled
        ) {
            // Direct round-completion fallback (e.g. unit/sync tests where BETTING_OPEN wasn't fired separately)
            val stratBefore = strategyEngine.getState()
            val proposedStake = stratBefore.currentCalculatedStake
            val riskCheck = riskManager.validateBetExecution(
                proposedStake = proposedStake,
                currentSessionLoss = activeSessionLoss,
                currentRounds = activeSessionRounds,
                currentProgressionLevel = stratBefore.currentStakeLevel,
                currentBalance = resolveEffectiveBalance(),
                limits = limits,
                currentSessionProfit = activeSessionProfit
            )

            if (!riskCheck.isAllowed) {
                haltAutomationForLimit(riskCheck.violationReason ?: "Risk limit reached.")
            } else {
                val betShortcut = findActiveShortcut(ShortcutType.BET)
                    ?: calibratedShortcuts.firstOrNull { it.type == ShortcutType.BET && it.isEnabled }
                val fallbackBetX = betShortcut?.targetX ?: 400f
                val fallbackBetY = betShortcut?.targetY ?: 1200f

                val instructionBefore = strategyEngine.getPendingStakeInstruction()
                val effectiveMode = if (instructionBefore.isSessionActive &&
                    instructionBefore.stakeLevel == stratBefore.currentStakeLevel
                ) {
                    instructionBefore.progressionMode
                } else {
                    stratBefore.activeProgressionMode
                }

                targetUiInteractor.applyStake(
                    newStake = proposedStake,
                    progressionMode = effectiveMode,
                    fallbackX = null,
                    fallbackY = null
                ) {
                    targetUiInteractor.clickBet(fallbackBetX, fallbackBetY) { }
                }

                onGestureDispatchRequested?.invoke(fallbackBetX, fallbackBetY, "PLACE BET")
                activeSessionRounds += 1

                StrategyEngine.triggerAction(
                    AutomationActionEvent(
                        actionType = ActionType.BET,
                        amountOrMultiplier = proposedStake,
                        reason = stratBefore.activeRuleLabel ?: "Auto Bet (Level ${stratBefore.currentStakeLevel})",
                        timestamp = currentTimeMs
                    )
                )

                settleActiveRoundBet(
                    stakeUsed = proposedStake,
                    crashedMultiplier = multiplier,
                    cashedOutInFlight = multiplier >= config.targetMultiplier,
                    triggerLabel = stratBefore.activeRuleLabel ?: "Auto Progression (Level ${stratBefore.currentStakeLevel})",
                    config = config,
                    limits = limits,
                    platformName = platformName,
                    currentTimeMs = currentTimeMs
                )
            }
        }

        // Now evaluate the crashed round against StrategyEngine rules for upcoming rounds
        val trigger = strategyEngine.processCompletedRound(multiplier, config, currentTimeMs)
        val stratState = strategyEngine.getState()
        val triggerName = formatTriggerLabel(trigger, stratState)
        gameDetection.updateStrategyTelemetry(stratState, triggerName)
    }

    private fun settleActiveRoundBet(
        stakeUsed: Double,
        crashedMultiplier: Double,
        cashedOutInFlight: Boolean,
        triggerLabel: String,
        config: StrategyConfig,
        limits: RiskLimits,
        platformName: String,
        currentTimeMs: Long
    ) {
        val isWin = cashedOutInFlight || crashedMultiplier >= config.targetMultiplier
        val payout = if (isWin) stakeUsed * config.targetMultiplier else 0.0
        val netProfit = payout - stakeUsed

        if (isWin) {
            sessionWins += 1
        } else {
            sessionLosses += 1
        }
        activeSessionProfit += netProfit
        activeSessionLoss = kotlin.math.max(0.0, -activeSessionProfit)

        val updatedState = strategyEngine.processBetOutcome(isWin, crashedMultiplier, config, currentTimeMs)
        gameDetection.updateStrategyTelemetry(updatedState, formatTriggerLabel(updatedState.activeTrigger, updatedState))

        publishSessionStats()

        // Immediately halt all automation gestures if Stop if Profit Reaches or Stop if Loss Reaches triggered
        enforceLimitsNow(limits)

        scope.launch {
            repository.addSessionLog(
                SessionLog(
                    timestamp = currentTimeMs,
                    platform = platformName,
                    roundNumber = activeSessionRounds,
                    detectedMultiplier = crashedMultiplier,
                    outcome = if (isWin) "WIN" else "LOSS",
                    stake = stakeUsed,
                    payout = payout,
                    netProfit = netProfit,
                    triggerReason = triggerLabel
                )
            )
        }
    }

    /**
     * Evaluates in-flight multipliers read via AccessibilityService.
     * When target multiplier is reached during an active bet and Cash Out is available,
     * triggers immediate calibrated CASH OUT hand and verifies site confirmation.
     */
    fun evaluateInFlightMultiplier(
        currentMultiplier: Double,
        config: StrategyConfig,
        siteButtonState: SiteButtonState = SiteButtonState.CASH_OUT_AVAILABLE,
        limits: RiskLimits = RiskLimits()
    ) {
        if (enforceLimitsNow(limits)) return
        val cashOutAllowed = siteButtonState == SiteButtonState.CASH_OUT_AVAILABLE ||
            siteButtonState == SiteButtonState.UNKNOWN
        val autoCashOutActive = (config.isAutoCashOutEnabled || config.isAutoBetEnabled) &&
            config.targetMultiplier >= 1.01
        if (_automationState.value == AutomationState.RUNNING &&
            autoCashOutActive &&
            cashOutAllowed &&
            (gameDetection.gameState.value.isBetPlaced || currentRoundBetActive) &&
            !currentRoundCashedOut &&
            !cashOutAttemptedThisRound &&
            currentMultiplier >= config.targetMultiplier
        ) {
            val cashOutShortcut = findActiveShortcut(ShortcutType.CASH_OUT)
            val effectiveTarget = cashOutShortcut?.targetMultiplier?.takeIf { it > 1.0 } ?: config.targetMultiplier
            if (currentMultiplier < effectiveTarget) return

            val uiTargets = AccessibilityController.controllerInstance?.getLatestUiTargets()
            val fallbackCashX = cashOutShortcut?.targetX
                ?: uiTargets?.cashOutButtonBounds?.takeIf { !it.isEmpty }?.centerX()?.toFloat()
            val fallbackCashY = cashOutShortcut?.targetY
                ?: uiTargets?.cashOutButtonBounds?.takeIf { !it.isEmpty }?.centerY()?.toFloat()
            val targetCoords = if (fallbackCashX != null && fallbackCashY != null) Pair(fallbackCashX, fallbackCashY) else null

            val gs = gameDetection.gameState.value
            val activeGameName = gs.displayedGameName.ifBlank { "Unknown" }
            val activeGameId = gs.gameId.ifBlank { "unknown" }
            val activeRoundId = (gs.currentRoundId ?: gs.latestRoundId)?.takeIf { it.isNotBlank() } ?: "Unknown"
            val cashOutDetails = "game=$activeGameName ($activeGameId), round=$activeRoundId, target=${String.format(Locale.US, "%.2fx", effectiveTarget)}"
            val nowMs = System.currentTimeMillis()
            val actionId = "cashout_${nowMs}_${(1000..9999).random()}"

            cashOutAttemptedThisRound = true
            val initialRecord = ActionExecutionRecord(
                actionId = actionId,
                operationName = "CASH_OUT",
                state = ActionExecutionState.EXECUTING_ACTION,
                startTimestampMs = nowMs,
                targetCoordinates = targetCoords,
                details = cashOutDetails
            )
            _executionState.value = ActionExecutionState.EXECUTING_ACTION
            _executionRecord.value = initialRecord
            gameDetection.updateExecutionRecord(initialRecord)
            recordActivityLog(actionId, ActionExecutionState.EXECUTING_ACTION, "CASH_OUT", targetCoords, null, "Target ${String.format(Locale.US, "%.2fx", currentMultiplier)} reached ($cashOutDetails)", null, null)

            targetUiInteractor.clickCashOut(fallbackCashX, fallbackCashY) { clickOk ->
                if (!clickOk) {
                    val failReason = "Cash-out gesture/click failed at ${String.format(Locale.US, "%.2fx", currentMultiplier)}."
                    val failedRecord = initialRecord.copy(
                        state = ActionExecutionState.FAILED,
                        completionTimestampMs = System.currentTimeMillis(),
                        isSuccess = false,
                        gestureDispatched = false,
                        failureReason = failReason
                    )
                    _executionRecord.value = failedRecord
                    _executionState.value = ActionExecutionState.FAILED
                    gameDetection.updateExecutionRecord(failedRecord)
                    recordActivityLog(actionId, ActionExecutionState.FAILED, "CASH_OUT", targetCoords, false, cashOutDetails, false, failReason)
                    publishStatusTicker(failReason)
                    emitEpisodeFailureNotice("cashout_click", "Cash Out Failed", failReason)
                    return@clickCashOut
                }

                if (fallbackCashX != null && fallbackCashY != null) {
                    onGestureDispatchRequested?.invoke(fallbackCashX, fallbackCashY, "CASH OUT")
                }

                fun confirmCashOutSuccess() {
                    currentRoundCashedOut = true
                    gameDetection.setBetPlaced(false)

                    val completedRecord = initialRecord.copy(
                        state = ActionExecutionState.COMPLETED,
                        completionTimestampMs = System.currentTimeMillis(),
                        isSuccess = true,
                        gestureDispatched = true,
                        interfaceResponded = true,
                        stateConfirmed = true
                    )
                    _executionRecord.value = completedRecord
                    _executionState.value = ActionExecutionState.COMPLETED
                    gameDetection.updateExecutionRecord(completedRecord)
                    recordActivityLog(actionId, ActionExecutionState.COMPLETED, "CASH_OUT", targetCoords, true, "Cash out confirmed ($cashOutDetails)", true, null)
                    publishStatusTicker("Cashed out at ${String.format(Locale.US, "%.2fx", currentMultiplier)}")

                    StrategyEngine.triggerAction(
                        AutomationActionEvent(
                            actionType = ActionType.CASH_OUT,
                            amountOrMultiplier = currentMultiplier,
                            reason = "In-Flight Target Reached: ${String.format(Locale.US, "%.2f", currentMultiplier)}x >= ${String.format(Locale.US, "%.2f", effectiveTarget)}x"
                        )
                    )
                    emitExecutionNotice(
                        type = StrategyNotificationType.AUTOMATION_ACTION,
                        title = "Cash Out Confirmed (${String.format(Locale.US, "%.2fx", currentMultiplier)})",
                        message = "Cashed out ${CurrencyManager.formatAmount(currentRoundStake)} on $activeGameName (Round $activeRoundId).",
                        occurrenceKey = "cashout_ok_${actionId}",
                        priority = NotificationPriorityLevel.STANDARD,
                        cooldownMs = 0L
                    )
                }

                if (targetUiInteractor !is AccessibilityTargetUiInteractor ||
                    gameDetection.gameState.value.siteButtonState != SiteButtonState.CASH_OUT_AVAILABLE
                ) {
                    confirmCashOutSuccess()
                } else {
                    _executionState.value = ActionExecutionState.VERIFYING_ACTION
                    val verifyingRecord = initialRecord.copy(
                        state = ActionExecutionState.VERIFYING_ACTION,
                        gestureDispatched = true,
                        interfaceResponded = true
                    )
                    _executionRecord.value = verifyingRecord
                    gameDetection.updateExecutionRecord(verifyingRecord)

                    scope.launch {
                        var elapsed = 0L
                        var confirmed = false
                        while (elapsed < 2000L) {
                            delay(100L)
                            elapsed += 100L
                            val btnState = gameDetection.gameState.value.siteButtonState
                            if (btnState != SiteButtonState.CASH_OUT_AVAILABLE) {
                                confirmed = true
                                break
                            }
                        }
                        if (confirmed) {
                            confirmCashOutSuccess()
                        } else {
                            val failReason = "Cash-out verification timed out (Cash Out button still visible)."
                            val failedRecord = initialRecord.copy(
                                state = ActionExecutionState.FAILED,
                                completionTimestampMs = System.currentTimeMillis(),
                                isSuccess = false,
                                gestureDispatched = true,
                                interfaceResponded = false,
                                stateConfirmed = false,
                                failureReason = failReason
                            )
                            _executionRecord.value = failedRecord
                            _executionState.value = ActionExecutionState.FAILED
                            gameDetection.updateExecutionRecord(failedRecord)
                            recordActivityLog(actionId, ActionExecutionState.FAILED, "CASH_OUT", targetCoords, true, cashOutDetails, false, failReason)
                            publishStatusTicker("Cash-out not confirmed by site")
                            emitEpisodeFailureNotice("cashout_verify", "Cash Out Unconfirmed", failReason)
                        }
                    }
                }
            }
        }
    }

    private fun formatTriggerLabel(trigger: StrategyTrigger, state: StrategyState): String? {
        return when (trigger) {
            is StrategyTrigger.LossRecoveryFired -> "${trigger.ruleConfig.dynamicTitle()} (Fetch ${trigger.ruleConfig.targetWinsToFetch} wins)"
            is StrategyTrigger.RangeWaitStarted -> "${trigger.ruleConfig.dynamicTitle()} (Waiting)"
            is StrategyTrigger.WaitTimerCompleted -> "${trigger.ruleConfig.dynamicTitle()} Ready"
            is StrategyTrigger.SessionTimedOut -> "3m Guard Timed Out (${trigger.previousRuleName})"
            is StrategyTrigger.Strategy1Fired -> "Strategy 1 Triggered (${trigger.consecutiveLowRounds}x low)"
            is StrategyTrigger.Strategy2Fired -> "Strategy 2 Triggered (>10x + ${trigger.waitRoundsCompleted} rounds)"
            is StrategyTrigger.HighMultiplierLossTrigger -> ">13.00x Doubled Stake (${CurrencyManager.formatCompact(trigger.doubledStake)})"
            StrategyTrigger.None -> state.activeRuleLabel?.let { "$it (${state.consecutiveWinsInSession}/${state.targetWinsToFetch} wins)" }
        }
    }
}
