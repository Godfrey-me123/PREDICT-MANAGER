package com.example.data.engine

import android.util.Log
import com.example.data.model.StakeProgressionMode
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyRuleConfig
import com.example.data.model.StrategyRuleId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import java.util.Locale

sealed class StrategyTrigger {
    object None : StrategyTrigger()
    data class Strategy1Fired(val consecutiveLowRounds: Int) : StrategyTrigger()
    data class Strategy2Fired(val waitRoundsCompleted: Int, val initialHighMultiplier: Double) : StrategyTrigger()
    data class HighMultiplierLossTrigger(val multiplier: Double, val doubledStake: Double) : StrategyTrigger()
    data class LossRecoveryFired(
        val ruleConfig: StrategyRuleConfig,
        val observedLosses: Int
    ) : StrategyTrigger()
    data class RangeWaitStarted(
        val ruleConfig: StrategyRuleConfig,
        val observedMultiplier: Double,
        val waitUntilTimestamp: Long
    ) : StrategyTrigger()
    data class WaitTimerCompleted(
        val ruleConfig: StrategyRuleConfig
    ) : StrategyTrigger()
    data class SessionTimedOut(
        val previousRuleName: String,
        val elapsedMs: Long
    ) : StrategyTrigger()
}

data class StrategyState(
    val consecutiveLowCount: Int = 0,
    val consecutiveObservedLosses: Int = 0,
    val postHighWaitCount: Int = 0,
    val lastHighMultiplier: Double = 0.0,
    val isWaitingForPostHigh: Boolean = false,
    val activeTrigger: StrategyTrigger = StrategyTrigger.None,
    val activeRuleId: StrategyRuleId? = null,
    val activeRuleLabel: String? = null,
    val activeProgressionMode: StakeProgressionMode = StakeProgressionMode.DOUBLE_AMOUNT,
    val activeCustomAddAmount: Double = 10.0,
    val targetWinsToFetch: Int = 2,
    val pendingWaitRuleId: StrategyRuleId? = null,
    val pendingWaitRuleLabel: String? = null,
    val waitUntilTimestamp: Long = 0L,
    val activeWaitRemainingMs: Long = 0L,
    val isBettingSessionActive: Boolean = false,
    val sessionStartedTimestamp: Long = 0L,
    val sessionTimeoutRemainingMs: Long = 0L,
    val currentStakeLevel: Int = 1,
    val consecutiveWinsInSession: Int = 0,
    val consecutiveLossesInSession: Int = 0,
    val currentCalculatedStake: Double = 10.0,
    val highMultiplierLossesInHour: Int = 0,
    val isNearHourEndRuleActive: Boolean = false,
    val enabledRuleIds: Set<StrategyRuleId> = StrategyRuleId.entries.filter { it.defaultEnabled }.toSet()
)

enum class ActionType {
    BET,
    CASH_OUT
}

data class AutomationActionEvent(
    val actionType: ActionType,
    val amountOrMultiplier: Double = 0.0,
    val reason: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val eventId: Long = System.nanoTime()
)

/**
 * Snapshot of the calculated stake ready to be passed to AccessibilityController
 * and written into the target betting app UI (or stepped via 2x / + buttons).
 */
data class StakeInstruction(
    val stakeAmount: Double = 10.0,
    val formattedStake: String = "10",
    val progressionMode: StakeProgressionMode = StakeProgressionMode.DOUBLE_AMOUNT,
    val incrementAmount: Double = 10.0,
    val targetMultiplier: Double = 2.00,
    val stakeLevel: Int = 1,
    val isSessionActive: Boolean = false,
    val reason: String = "Base Stake"
)

class StrategyEngine {

    companion object {
        private const val TAG = "StrategyEngine"
        const val DEFAULT_SESSION_TIMEOUT_MS = 180_000L // 3-minute guard

        private val _actionEvent = MutableStateFlow<AutomationActionEvent?>(null)
        val actionEvent: StateFlow<AutomationActionEvent?> = _actionEvent.asStateFlow()

        private val _latestStakeInstruction = MutableStateFlow(StakeInstruction())
        val latestStakeInstruction: StateFlow<StakeInstruction> = _latestStakeInstruction.asStateFlow()

        fun triggerAction(event: AutomationActionEvent) {
            _actionEvent.value = event
            safeLogI("Automation Action Triggered: ${event.actionType} (${event.amountOrMultiplier}) - ${event.reason}")
        }

        fun roundStake(value: Double): Double {
            return kotlin.math.round(value.coerceAtLeast(0.0) * 100.0) / 100.0
        }

        fun formatStakeAmount(stake: Double): String {
            val rounded = roundStake(stake)
            return if (rounded % 1.0 == 0.0) {
                rounded.toLong().toString()
            } else {
                String.format(Locale.US, "%.2f", rounded)
            }
        }

        private fun safeLogI(message: String) {
            try {
                Log.i(TAG, message)
            } catch (_: RuntimeException) {
                // Unit test JVM without Android Log mock
            }
        }

        private fun safeLogW(message: String) {
            try {
                Log.w(TAG, message)
            } catch (_: RuntimeException) {
                // Unit test JVM without Android Log mock
            }
        }
    }

    private var state = StrategyState()
    private var currentStakeInstruction = StakeInstruction()
    private val _boundConfig = MutableStateFlow(StrategyConfig())
    val boundConfig: StateFlow<StrategyConfig> = _boundConfig.asStateFlow()

    private val highMultiplierLossTimestamps = mutableListOf<Long>()
    private var totalRounds: Int = 0
    private var totalWins: Int = 0
    private var totalLosses: Int = 0

    fun getState(): StrategyState = state

    fun getPendingStakeInstruction(): StakeInstruction = currentStakeInstruction

    /**
     * Binds the active StrategyConfig selections (from Settings or Strategy tab) directly to StrategyEngine
     * so single, multi, or all-strategy selections take effect immediately.
     */
    fun bindStrategyConfig(config: StrategyConfig) {
        _boundConfig.value = config
        val enabledIds = config.getRuleConfigs()
            .filter { it.isEnabled }
            .map { it.ruleId }
            .toSet()

        val shouldClearPendingWait = state.pendingWaitRuleId != null && state.pendingWaitRuleId !in enabledIds
        val firstEnabledRule = config.getRuleConfigs().firstOrNull { it.isEnabled }
        val updatedStake = if (!state.isBettingSessionActive) roundStake(config.baseStake) else state.currentCalculatedStake

        state = state.copy(
            enabledRuleIds = enabledIds,
            pendingWaitRuleId = if (shouldClearPendingWait) null else state.pendingWaitRuleId,
            pendingWaitRuleLabel = if (shouldClearPendingWait) null else state.pendingWaitRuleLabel,
            waitUntilTimestamp = if (shouldClearPendingWait) 0L else state.waitUntilTimestamp,
            activeWaitRemainingMs = if (shouldClearPendingWait) 0L else state.activeWaitRemainingMs,
            activeProgressionMode = if (!state.isBettingSessionActive && firstEnabledRule != null) {
                firstEnabledRule.progressionMode
            } else {
                state.activeProgressionMode
            },
            activeCustomAddAmount = if (!state.isBettingSessionActive && firstEnabledRule != null) {
                firstEnabledRule.customAddAmount
            } else {
                state.activeCustomAddAmount
            },
            currentCalculatedStake = updatedStake
        )

        if (!state.isBettingSessionActive) {
            publishStakeInstruction(
                stake = updatedStake,
                mode = state.activeProgressionMode,
                addAmount = state.activeCustomAddAmount,
                targetMultiplier = config.targetMultiplier,
                stakeLevel = 1,
                isSessionActive = false,
                reason = if (enabledIds.isEmpty()) "No Recovery Rule Selected" else "${enabledIds.size} Rules Bound"
            )
        }
        safeLogI("Bound ${enabledIds.size} strategy rules to StrategyEngine: $enabledIds")
    }

    fun updateStrategySelection(
        selectedRuleIds: Set<StrategyRuleId>,
        isStrategy1Enabled: Boolean? = null,
        isStrategy2Enabled: Boolean? = null,
        isHighMultiplierLossRuleEnabled: Boolean? = null
    ): StrategyConfig {
        val current = _boundConfig.value
        val updatedRules = current.getRuleConfigs().map { rule ->
            rule.copy(isEnabled = rule.ruleId in selectedRuleIds)
        }
        val updatedConfig = current.copy(
            isStrategy1Enabled = isStrategy1Enabled ?: current.isStrategy1Enabled,
            isStrategy2Enabled = isStrategy2Enabled ?: current.isStrategy2Enabled,
            isHighMultiplierLossRuleEnabled = isHighMultiplierLossRuleEnabled ?: current.isHighMultiplierLossRuleEnabled
        ).withUpdatedRuleConfigs(updatedRules)
        bindStrategyConfig(updatedConfig)
        return updatedConfig
    }

    /**
     * Core stake calculation after a loss event based on 'Add Amount' or 'Double Amount' rules
     * (and optional >13.00x high-multiplier loss doubling override), returning a rounded value
     * ready for AccessibilityController to pass to the game interface.
     */
    fun calculateNextStakeAfterLoss(
        currentStake: Double,
        progressionMode: StakeProgressionMode,
        customAddAmount: Double,
        lossMultiplier: Double = 0.0,
        isHighMultiplierLossRuleEnabled: Boolean = false
    ): Double {
        val safeCurrent = currentStake.coerceAtLeast(0.0)
        val rawNext = if (isHighMultiplierLossRuleEnabled && lossMultiplier > 13.00) {
            safeCurrent * 2.0
        } else {
            when (progressionMode) {
                StakeProgressionMode.ADD_AMOUNT -> safeCurrent + customAddAmount.coerceAtLeast(0.0)
                StakeProgressionMode.DOUBLE_AMOUNT -> safeCurrent * 2.0
            }
        }
        return roundStake(rawNext)
    }

    private fun publishStakeInstruction(
        stake: Double,
        mode: StakeProgressionMode,
        addAmount: Double,
        targetMultiplier: Double,
        stakeLevel: Int,
        isSessionActive: Boolean,
        reason: String
    ) {
        val rounded = roundStake(stake)
        val instruction = StakeInstruction(
            stakeAmount = rounded,
            formattedStake = formatStakeAmount(rounded),
            progressionMode = mode,
            incrementAmount = roundStake(addAmount),
            targetMultiplier = targetMultiplier,
            stakeLevel = stakeLevel,
            isSessionActive = isSessionActive,
            reason = reason
        )
        currentStakeInstruction = instruction
        _latestStakeInstruction.value = instruction
    }

    fun reset(baseStake: Double = _boundConfig.value.baseStake) {
        val cleanBase = roundStake(baseStake)
        val boundEnabledIds = _boundConfig.value.getRuleConfigs()
            .filter { it.isEnabled }
            .map { it.ruleId }
            .toSet()
        state = StrategyState(
            currentCalculatedStake = cleanBase,
            enabledRuleIds = boundEnabledIds
        )
        highMultiplierLossTimestamps.clear()
        totalRounds = 0
        totalWins = 0
        totalLosses = 0
        publishStakeInstruction(
            stake = cleanBase,
            mode = state.activeProgressionMode,
            addAmount = state.activeCustomAddAmount,
            targetMultiplier = _boundConfig.value.targetMultiplier,
            stakeLevel = 1,
            isSessionActive = false,
            reason = "Reset to Base Stake"
        )
        safeLogI("Strategy engine reset.")
    }

    /**
     * Checks:
     * 1. 3-minute active session timeout guard: if an active betting session exceeds
     *    config.sessionTimeoutMinutes (default 3m), gracefully terminates the session and resets counters.
     * 2. Pending multiplier-range wait timers (e.g., 5m or 10m) — starts recovery ONLY when unlocked.
     */
    fun checkPendingTimers(
        config: StrategyConfig = _boundConfig.value,
        currentTimeMs: Long = System.currentTimeMillis()
    ): StrategyTrigger {
        val timeoutLimitMs = config.sessionTimeoutMinutes.coerceAtLeast(1) * 60_000L

        // 1. Enforce 3-minute grace period & auto-cancellation on active session
        if (state.isBettingSessionActive && state.sessionStartedTimestamp > 0L) {
            val elapsed = (currentTimeMs - state.sessionStartedTimestamp).coerceAtLeast(0L)
            if (elapsed >= timeoutLimitMs) {
                val timedOutLabel = state.activeRuleLabel ?: state.activeRuleId?.displayName ?: "Active Session"
                safeLogW("3-minute timeout guard triggered for '$timedOutLabel' after ${elapsed}ms. Terminating cycle.")
                val timeoutTrigger = StrategyTrigger.SessionTimedOut(timedOutLabel, elapsed)
                state = state.copy(
                    isBettingSessionActive = false,
                    sessionStartedTimestamp = 0L,
                    sessionTimeoutRemainingMs = 0L,
                    activeRuleId = null,
                    activeRuleLabel = null,
                    activeTrigger = timeoutTrigger,
                    currentStakeLevel = 1,
                    consecutiveWinsInSession = 0,
                    consecutiveLossesInSession = 0,
                    consecutiveObservedLosses = 0,
                    currentCalculatedStake = config.baseStake,
                    consecutiveLowCount = 0,
                    postHighWaitCount = 0
                )
                publishStakeInstruction(
                    stake = config.baseStake,
                    mode = state.activeProgressionMode,
                    addAmount = state.activeCustomAddAmount,
                    targetMultiplier = config.targetMultiplier,
                    stakeLevel = 1,
                    isSessionActive = false,
                    reason = "3m Guard Timed Out -> Reset to Base Stake"
                )
                return timeoutTrigger
            } else {
                state = state.copy(sessionTimeoutRemainingMs = timeoutLimitMs - elapsed)
            }
        }

        // 2. Exclusive lock: if a strategy session is already running, wait before firing another
        if (state.isBettingSessionActive) {
            return StrategyTrigger.None
        }

        // 3. Check multiplier-range wait timer
        val pendingRuleId = state.pendingWaitRuleId ?: return StrategyTrigger.None
        val waitUntil = state.waitUntilTimestamp
        if (waitUntil <= 0L) return StrategyTrigger.None

        val remaining = (waitUntil - currentTimeMs).coerceAtLeast(0L)
        if (remaining > 0L) {
            state = state.copy(activeWaitRemainingMs = remaining)
            return StrategyTrigger.None
        }

        val ruleConfig = config.getRuleConfig(pendingRuleId)
        val trigger = StrategyTrigger.WaitTimerCompleted(ruleConfig)
        val initialStake = roundStake(config.baseStake)

        state = state.copy(
            pendingWaitRuleId = null,
            pendingWaitRuleLabel = null,
            waitUntilTimestamp = 0L,
            activeWaitRemainingMs = 0L,
            activeRuleId = pendingRuleId,
            activeRuleLabel = ruleConfig.dynamicTitle(),
            activeProgressionMode = ruleConfig.progressionMode,
            activeCustomAddAmount = ruleConfig.customAddAmount,
            targetWinsToFetch = ruleConfig.targetWinsToFetch,
            activeTrigger = trigger,
            isBettingSessionActive = true,
            sessionStartedTimestamp = currentTimeMs,
            sessionTimeoutRemainingMs = timeoutLimitMs,
            currentCalculatedStake = initialStake,
            consecutiveWinsInSession = 0,
            consecutiveLossesInSession = 0
        )

        publishStakeInstruction(
            stake = initialStake,
            mode = ruleConfig.progressionMode,
            addAmount = ruleConfig.customAddAmount,
            targetMultiplier = config.targetMultiplier,
            stakeLevel = 1,
            isSessionActive = true,
            reason = "${ruleConfig.dynamicTitle()} Ready"
        )

        triggerAction(
            AutomationActionEvent(
                actionType = ActionType.BET,
                amountOrMultiplier = initialStake,
                reason = "${ruleConfig.dynamicTitle()} wait elapsed -> Fetch ${ruleConfig.targetWinsToFetch} wins",
                timestamp = currentTimeMs
            )
        )
        return trigger
    }

    /**
     * Activates a betting recovery session when an external CustomPatternStrategy rule fires,
     * respecting the exclusive lock (no-op if a session is already active).
     */
    fun activateExternalPatternTrigger(
        strategyName: String,
        config: StrategyConfig = _boundConfig.value,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (state.isBettingSessionActive) return false
        val firstEnabledRule = config.getRuleConfigs().firstOrNull { it.isEnabled }
        val mode = firstEnabledRule?.progressionMode ?: state.activeProgressionMode
        val addAmt = firstEnabledRule?.customAddAmount ?: state.activeCustomAddAmount
        val targetWins = firstEnabledRule?.targetWinsToFetch ?: config.stopAfterConsecutiveWins.coerceAtLeast(1)
        val initialStake = roundStake(config.baseStake)
        val timeoutLimitMs = config.sessionTimeoutMinutes.coerceAtLeast(1) * 60_000L

        state = state.copy(
            isBettingSessionActive = true,
            activeRuleId = null,
            activeRuleLabel = strategyName,
            activeProgressionMode = mode,
            activeCustomAddAmount = addAmt,
            targetWinsToFetch = targetWins,
            sessionStartedTimestamp = currentTimeMs,
            sessionTimeoutRemainingMs = timeoutLimitMs,
            currentCalculatedStake = initialStake,
            currentStakeLevel = 1,
            consecutiveWinsInSession = 0,
            consecutiveLossesInSession = 0
        )

        publishStakeInstruction(
            stake = initialStake,
            mode = mode,
            addAmount = addAmt,
            targetMultiplier = config.targetMultiplier,
            stakeLevel = 1,
            isSessionActive = true,
            reason = "$strategyName Triggered"
        )
        triggerAction(
            AutomationActionEvent(
                actionType = ActionType.BET,
                amountOrMultiplier = initialStake,
                reason = "$strategyName matched -> Fetch $targetWins wins",
                timestamp = currentTimeMs
            )
        )
        safeLogI("External pattern strategy '$strategyName' activated betting session (stake=$initialStake)")
        return true
    }

    /**
     * Evaluates completed round multipliers against all rules while strictly enforcing
     * exclusive non-overlapping strategy lock and 3-minute session timeout guard.
     */
    fun processCompletedRound(
        multiplier: Double,
        config: StrategyConfig = _boundConfig.value,
        currentTimeMs: Long = System.currentTimeMillis()
    ): StrategyTrigger {
        totalRounds++

        // Clean up high multiplier loss timestamps older than 1 hour (3600000 ms)
        highMultiplierLossTimestamps.removeAll { currentTimeMs - it > 3_600_000L }

        // Enforce 3-minute timeout guard & check elapsed wait timers first
        val timerTrigger = checkPendingTimers(config, currentTimeMs)
        val timeoutLimitMs = config.sessionTimeoutMinutes.coerceAtLeast(1) * 60_000L

        // Exclusive Lock Check: while a betting session is active, do NOT start or overlap any other strategy
        if (state.isBettingSessionActive) {
            state = state.copy(
                highMultiplierLossesInHour = highMultiplierLossTimestamps.size,
                isNearHourEndRuleActive = shouldContinueBettingBasedOnHourRule(currentTimeMs)
            )
            return StrategyTrigger.None
        }

        var consecutiveLow = state.consecutiveLowCount
        var consecutiveObservedLosses = state.consecutiveObservedLosses
        var postHighWait = state.postHighWaitCount
        var isWaiting = state.isWaitingForPostHigh
        var lastHigh = state.lastHighMultiplier

        // Track consecutive observed losses (< targetMultiplier) while unlocked
        if (multiplier < config.targetMultiplier) {
            consecutiveObservedLosses += 1
        } else {
            consecutiveObservedLosses = 0
        }

        // Strategy 1 Evaluation: Count consecutive rounds < strategy1LowThreshold
        if (config.isStrategy1Enabled && multiplier < config.strategy1LowThreshold) {
            consecutiveLow += 1
        } else {
            consecutiveLow = 0
        }

        // Strategy 2 Evaluation: Detect > strategy2HighThreshold and count wait rounds
        if (config.isStrategy2Enabled) {
            if (multiplier > config.strategy2HighThreshold && !isWaiting) {
                isWaiting = true
                postHighWait = 0
                lastHigh = multiplier
            } else if (isWaiting) {
                postHighWait += 1
            }
        }

        val allRules = config.getRuleConfigs()
        var pendingWaitRuleId = state.pendingWaitRuleId
        var pendingWaitRuleLabel = state.pendingWaitRuleLabel
        var waitUntilTimestamp = state.waitUntilTimestamp

        // Evaluate Multiplier-Range Waiting Rules using editable min/max/waitMinutes
        var rangeTrigger: StrategyTrigger? = null
        if (pendingWaitRuleId == null) {
            val matchedRangeRule = allRules.filter {
                it.isEnabled &&
                    it.ruleId.category == com.example.data.model.StrategyCategory.MULTIPLIER_WAIT &&
                    it.waitDurationMs > 0L &&
                    multiplier > it.minMultiplier &&
                    multiplier < it.maxMultiplier
            }.maxByOrNull { it.minMultiplier }

            if (matchedRangeRule != null) {
                pendingWaitRuleId = matchedRangeRule.ruleId
                pendingWaitRuleLabel = matchedRangeRule.dynamicTitle()
                waitUntilTimestamp = currentTimeMs + matchedRangeRule.waitDurationMs
                rangeTrigger = StrategyTrigger.RangeWaitStarted(
                    ruleConfig = matchedRangeRule,
                    observedMultiplier = multiplier,
                    waitUntilTimestamp = waitUntilTimestamp
                )
                safeLogI("Range rule triggered: ${matchedRangeRule.dynamicTitle()} at ${multiplier}x")
            }
        }

        // Evaluate Loss Recovery Rules using editable requiredLosses (only if unlocked & no wait timer pending)
        var triggerFired: StrategyTrigger = if (timerTrigger is StrategyTrigger.WaitTimerCompleted) {
            timerTrigger
        } else {
            StrategyTrigger.None
        }

        var activatedRule: StrategyRuleConfig? = null
        if (triggerFired is StrategyTrigger.None && pendingWaitRuleId == null) {
            val matchingLossRule = allRules
                .filter {
                    it.isEnabled &&
                        it.ruleId.category != com.example.data.model.StrategyCategory.MULTIPLIER_WAIT &&
                        it.requiredLosses > 0 &&
                        consecutiveObservedLosses >= it.requiredLosses
                }
                .minByOrNull { it.requiredLosses }

            if (matchingLossRule != null) {
                activatedRule = matchingLossRule
                triggerFired = StrategyTrigger.LossRecoveryFired(
                    ruleConfig = matchingLossRule,
                    observedLosses = consecutiveObservedLosses
                )
            } else if (config.isStrategy1Enabled && consecutiveLow >= config.strategy1RequiredLowRounds) {
                triggerFired = StrategyTrigger.Strategy1Fired(consecutiveLow)
            } else if (config.isStrategy2Enabled && isWaiting && postHighWait >= config.strategy2WaitRounds) {
                triggerFired = StrategyTrigger.Strategy2Fired(postHighWait, lastHigh)
            }
        }

        val hourRuleActive = shouldContinueBettingBasedOnHourRule(currentTimeMs)
        val sessionActive = state.isBettingSessionActive ||
            (triggerFired is StrategyTrigger.LossRecoveryFired ||
                triggerFired is StrategyTrigger.WaitTimerCompleted ||
                triggerFired is StrategyTrigger.Strategy1Fired ||
                triggerFired is StrategyTrigger.Strategy2Fired ||
                hourRuleActive)

        val initialStake = if (!state.isBettingSessionActive && sessionActive) {
            config.baseStake
        } else {
            state.currentCalculatedStake
        }

        val resolvedActiveRuleId = when {
            activatedRule != null -> activatedRule.ruleId
            triggerFired is StrategyTrigger.WaitTimerCompleted -> triggerFired.ruleConfig.ruleId
            state.isBettingSessionActive -> state.activeRuleId
            else -> null
        }

        val resolvedRuleConfig = resolvedActiveRuleId?.let { config.getRuleConfig(it) }
        val resolvedRuleLabel = when {
            resolvedRuleConfig != null -> resolvedRuleConfig.dynamicTitle()
            triggerFired is StrategyTrigger.Strategy1Fired -> "Strategy 1 (<${config.strategy1LowThreshold}x)"
            triggerFired is StrategyTrigger.Strategy2Fired -> "Strategy 2 (>${config.strategy2HighThreshold}x)"
            else -> state.activeRuleLabel
        }

        val sessionStarted = when {
            !state.isBettingSessionActive && sessionActive -> currentTimeMs
            sessionActive -> state.sessionStartedTimestamp
            else -> 0L
        }

        state = state.copy(
            consecutiveLowCount = consecutiveLow,
            consecutiveObservedLosses = consecutiveObservedLosses,
            postHighWaitCount = postHighWait,
            lastHighMultiplier = lastHigh,
            isWaitingForPostHigh = if (isWaiting && postHighWait >= config.strategy2WaitRounds) false else isWaiting,
            activeRuleId = resolvedActiveRuleId,
            activeRuleLabel = resolvedRuleLabel,
            activeProgressionMode = resolvedRuleConfig?.progressionMode ?: state.activeProgressionMode,
            activeCustomAddAmount = resolvedRuleConfig?.customAddAmount ?: state.activeCustomAddAmount,
            targetWinsToFetch = resolvedRuleConfig?.targetWinsToFetch ?: config.stopAfterConsecutiveWins,
            pendingWaitRuleId = pendingWaitRuleId,
            pendingWaitRuleLabel = pendingWaitRuleLabel,
            waitUntilTimestamp = waitUntilTimestamp,
            activeWaitRemainingMs = if (pendingWaitRuleId != null) (waitUntilTimestamp - currentTimeMs).coerceAtLeast(0L) else 0L,
            activeTrigger = when {
                triggerFired !is StrategyTrigger.None -> triggerFired
                rangeTrigger != null -> rangeTrigger
                timerTrigger is StrategyTrigger.SessionTimedOut -> timerTrigger
                else -> state.activeTrigger
            },
            isBettingSessionActive = sessionActive,
            sessionStartedTimestamp = sessionStarted,
            sessionTimeoutRemainingMs = if (sessionActive) timeoutLimitMs else 0L,
            currentCalculatedStake = initialStake,
            highMultiplierLossesInHour = highMultiplierLossTimestamps.size,
            isNearHourEndRuleActive = hourRuleActive
        )

        when (val fired = triggerFired) {
            is StrategyTrigger.LossRecoveryFired -> {
                publishStakeInstruction(
                    stake = initialStake,
                    mode = fired.ruleConfig.progressionMode,
                    addAmount = fired.ruleConfig.customAddAmount,
                    targetMultiplier = config.targetMultiplier,
                    stakeLevel = state.currentStakeLevel,
                    isSessionActive = true,
                    reason = "${fired.ruleConfig.dynamicTitle()} Triggered"
                )
                triggerAction(
                    AutomationActionEvent(
                        actionType = ActionType.BET,
                        amountOrMultiplier = initialStake,
                        reason = "${fired.ruleConfig.dynamicTitle()} (${fired.observedLosses} losses) -> Fetch ${fired.ruleConfig.targetWinsToFetch} wins",
                        timestamp = currentTimeMs
                    )
                )
            }
            is StrategyTrigger.Strategy1Fired -> {
                publishStakeInstruction(
                    stake = initialStake,
                    mode = state.activeProgressionMode,
                    addAmount = state.activeCustomAddAmount,
                    targetMultiplier = config.targetMultiplier,
                    stakeLevel = state.currentStakeLevel,
                    isSessionActive = true,
                    reason = "Strategy 1 Triggered"
                )
                triggerAction(
                    AutomationActionEvent(
                        actionType = ActionType.BET,
                        amountOrMultiplier = initialStake,
                        reason = "Strategy 1: <${config.strategy1LowThreshold}x streak ($consecutiveLow rounds)",
                        timestamp = currentTimeMs
                    )
                )
            }
            is StrategyTrigger.Strategy2Fired -> {
                publishStakeInstruction(
                    stake = initialStake,
                    mode = state.activeProgressionMode,
                    addAmount = state.activeCustomAddAmount,
                    targetMultiplier = config.targetMultiplier,
                    stakeLevel = state.currentStakeLevel,
                    isSessionActive = true,
                    reason = "Strategy 2 Triggered"
                )
                triggerAction(
                    AutomationActionEvent(
                        actionType = ActionType.BET,
                        amountOrMultiplier = initialStake,
                        reason = "Strategy 2: >${config.strategy2HighThreshold}x (${lastHigh}x) + ${config.strategy2WaitRounds} rounds wait",
                        timestamp = currentTimeMs
                    )
                )
            }
            else -> Unit
        }

        return when {
            triggerFired !is StrategyTrigger.None -> triggerFired
            rangeTrigger != null -> rangeTrigger
            timerTrigger is StrategyTrigger.SessionTimedOut -> timerTrigger
            else -> StrategyTrigger.None
        }
    }

    /**
     * Process betting outcome for an active automated bet.
     * Enforces per-strategy stake progression (Option A: Add Amount vs Option B: Double Amount),
     * target wins reset (e.g., fetch 2 wins then unlock/reset), and >13.00x loss rule,
     * publishing the next StakeInstruction ready for AccessibilityController.
     */
    fun processBetOutcome(
        isWin: Boolean,
        multiplier: Double,
        config: StrategyConfig = _boundConfig.value,
        currentTimeMs: Long = System.currentTimeMillis()
    ): StrategyState {
        val activeRuleConfig = state.activeRuleId?.let { config.getRuleConfig(it) }
        val targetWins = activeRuleConfig?.targetWinsToFetch ?: config.stopAfterConsecutiveWins
        val activeMode = activeRuleConfig?.progressionMode ?: state.activeProgressionMode
        val activeAddAmount = activeRuleConfig?.customAddAmount ?: state.activeCustomAddAmount

        if (isWin) {
            totalWins++
            val wins = state.consecutiveWinsInSession + 1
            safeLogI("Bet Won! Verified wins in recovery session: $wins / $targetWins")
            triggerAction(
                AutomationActionEvent(
                    actionType = ActionType.CASH_OUT,
                    amountOrMultiplier = multiplier,
                    reason = "Auto Cashout Won ($wins/$targetWins) @ ${String.format(Locale.US, "%.2f", multiplier)}x",
                    timestamp = currentTimeMs
                )
            )

            if (wins >= targetWins && !shouldContinueBettingBasedOnHourRule(currentTimeMs)) {
                val resetBase = roundStake(config.baseStake)
                // Fetched required verified wins -> release session lock and reset cleanly
                state = state.copy(
                    isBettingSessionActive = false,
                    sessionStartedTimestamp = 0L,
                    sessionTimeoutRemainingMs = 0L,
                    activeTrigger = StrategyTrigger.None,
                    activeRuleId = null,
                    activeRuleLabel = null,
                    currentStakeLevel = 1,
                    consecutiveWinsInSession = 0,
                    consecutiveLossesInSession = 0,
                    consecutiveObservedLosses = 0,
                    currentCalculatedStake = resetBase,
                    consecutiveLowCount = 0,
                    postHighWaitCount = 0
                )
                publishStakeInstruction(
                    stake = resetBase,
                    mode = activeMode,
                    addAmount = activeAddAmount,
                    targetMultiplier = config.targetMultiplier,
                    stakeLevel = 1,
                    isSessionActive = false,
                    reason = "Target $targetWins Wins Fetched -> Reset to Base Stake"
                )
            } else {
                state = state.copy(
                    consecutiveWinsInSession = wins,
                    consecutiveLossesInSession = 0
                )
                publishStakeInstruction(
                    stake = state.currentCalculatedStake,
                    mode = activeMode,
                    addAmount = activeAddAmount,
                    targetMultiplier = config.targetMultiplier,
                    stakeLevel = state.currentStakeLevel,
                    isSessionActive = true,
                    reason = "Win $wins/$targetWins Verified -> Continue Session"
                )
            }
        } else {
            totalLosses++
            val losses = state.consecutiveLossesInSession + 1

            val isHighMultLoss = config.isHighMultiplierLossRuleEnabled && multiplier > 13.00
            val nextStake = calculateNextStakeAfterLoss(
                currentStake = state.currentCalculatedStake,
                progressionMode = activeMode,
                customAddAmount = activeAddAmount,
                lossMultiplier = multiplier,
                isHighMultiplierLossRuleEnabled = config.isHighMultiplierLossRuleEnabled
            )

            safeLogI("Bet Lost. Session losses: $losses, mode: $activeMode, next stake ready: $nextStake")

            if (isHighMultLoss) {
                highMultiplierLossTimestamps.add(currentTimeMs)
                safeLogW("High multiplier (>13.00x) loss recorded! Doubled stake: $nextStake")
                triggerAction(
                    AutomationActionEvent(
                        actionType = ActionType.BET,
                        amountOrMultiplier = nextStake,
                        reason = ">13.00x Loss Rule: Doubled stake to $nextStake",
                        timestamp = currentTimeMs
                    )
                )
            }

            val hourRuleActive = shouldContinueBettingBasedOnHourRule(currentTimeMs)
            val newLevel = state.currentStakeLevel + 1

            state = state.copy(
                isBettingSessionActive = true,
                currentStakeLevel = newLevel,
                consecutiveWinsInSession = 0,
                consecutiveLossesInSession = losses,
                currentCalculatedStake = nextStake,
                highMultiplierLossesInHour = highMultiplierLossTimestamps.size,
                isNearHourEndRuleActive = hourRuleActive
            )

            publishStakeInstruction(
                stake = nextStake,
                mode = if (isHighMultLoss) StakeProgressionMode.DOUBLE_AMOUNT else activeMode,
                addAmount = activeAddAmount,
                targetMultiplier = config.targetMultiplier,
                stakeLevel = newLevel,
                isSessionActive = true,
                reason = "Loss #$losses (${activeMode.label}) -> Next Stake ${formatStakeAmount(nextStake)}"
            )
        }

        return state
    }

    /**
     * Checks if 3 high-multiplier losses occurred in the current hour and if it is
     * within 10 minutes of the hour ending (minute >= 50).
     */
    private fun shouldContinueBettingBasedOnHourRule(currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        if (highMultiplierLossTimestamps.size < 3) return false
        val calendar = Calendar.getInstance().apply { timeInMillis = currentTimeMs }
        val minute = calendar.get(Calendar.MINUTE)
        return minute >= 50
    }
}
