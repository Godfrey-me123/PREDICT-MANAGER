package com.example.data.model

import com.example.data.engine.CurrencyManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Explicit states for account balance detection from the game interface.
 */
enum class BalanceDetectionStatus {
    SCANNING,
    DETECTED,
    VERIFIED,
    UNAVAILABLE,
    STALE
}

/**
 * Tracks the live account balance parsed directly from the Aviator header node.
 */
data class BalanceState(
    val status: BalanceDetectionStatus = BalanceDetectionStatus.SCANNING,
    val amount: Double? = null,
    val currency: String? = null,
    val lastReadTimestampMs: Long = 0L,
    val failureReason: String? = null
) {
    val lastUpdatedMs: Long
        get() = lastReadTimestampMs
    val isUsableForBet: Boolean
        get() = (status == BalanceDetectionStatus.VERIFIED || status == BalanceDetectionStatus.DETECTED) &&
            amount != null &&
            amount.isFinite() &&
            amount >= 0.0

    val isStale: Boolean
        get() = status == BalanceDetectionStatus.STALE

    fun formattedDisplay(): String {
        return when (status) {
            BalanceDetectionStatus.VERIFIED -> {
                val amt = amount ?: 0.0
                "${CurrencyManager.formatAmount(amt)} [VERIFIED]"
            }
            BalanceDetectionStatus.DETECTED -> {
                val amt = amount ?: 0.0
                "${CurrencyManager.formatAmount(amt)} [DETECTED]"
            }
            BalanceDetectionStatus.SCANNING -> "SCANNING..."
            BalanceDetectionStatus.UNAVAILABLE -> "UNAVAILABLE${failureReason?.let { " ($it)" } ?: ""}"
            BalanceDetectionStatus.STALE -> "STALE (Needs refresh)"
        }
    }
}

/**
 * Centralized action execution state machine states.
 */
enum class ActionExecutionState {
    IDLE,
    SCANNING,
    READING_BALANCE,
    DETECTING_CONTROLS,
    VALIDATING_GAME_STATE,
    EXECUTING_ACTION,
    VERIFYING_ACTION,
    COMPLETED,
    FAILED,
    PAUSED
}

/**
 * Detailed record of a single atomic automation action (Set Stake, Place Bet, Cash Out).
 */
data class ActionExecutionRecord(
    val actionId: String = "act_${System.currentTimeMillis()}_${(1000..9999).random()}",
    val operationName: String, // "APPLY_STAKE", "PLACE_BET", "CASH_OUT"
    val state: ActionExecutionState = ActionExecutionState.IDLE,
    val startTimestampMs: Long = System.currentTimeMillis(),
    val completionTimestampMs: Long? = null,
    val targetCoordinates: Pair<Float, Float>? = null,
    val isSuccess: Boolean? = null,
    val failureReason: String? = null,
    val gestureDispatched: Boolean = false,
    val interfaceResponded: Boolean = false,
    val stateConfirmed: Boolean = false,
    val details: String = ""
) {
    val durationMs: Long
        get() = (completionTimestampMs ?: System.currentTimeMillis()) - startTimestampMs
}

/**
 * Structured diagnostic log entry for every important operation in Screen Earner.
 */
data class ActivityLogEntry(
    val id: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis(),
    val actionId: String,
    val state: ActionExecutionState,
    val target: String,
    val coordinates: Pair<Float, Float>?,
    val gestureResult: Boolean?,
    val eventObserved: String?,
    val verificationResult: Boolean?,
    val errorDetails: String?
) {
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return sdf.format(Date(timestamp))
    }

    fun formattedSummary(): String {
        val coordStr = coordinates?.let { " @ (${it.first.toInt()}, ${it.second.toInt()})" } ?: ""
        val resStr = when {
            verificationResult == true -> "[VERIFIED]"
            verificationResult == false -> "[UNVERIFIED]"
            gestureResult == true -> "[DISPATCHED]"
            gestureResult == false -> "[DISPATCH FAILED]"
            else -> "[${state.name}]"
        }
        val errStr = errorDetails?.let { " - $it" } ?: ""
        return "${formattedTime()} $target$coordStr $resStr$errStr"
    }
}
