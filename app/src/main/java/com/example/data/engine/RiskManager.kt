package com.example.data.engine

import com.example.data.model.RiskLimits
import java.util.Locale
import kotlin.math.max

data class RiskCheckResult(
    val isAllowed: Boolean,
    val violationReason: String? = null
)

class RiskManager {

    /**
     * Checks session-wide Stop if Profit Reaches and Stop if Loss Reaches thresholds.
     */
    fun evaluateSessionLimits(
        currentSessionProfit: Double,
        currentSessionLoss: Double = max(0.0, -currentSessionProfit),
        limits: RiskLimits,
        currentDailyLoss: Double = 0.0
    ): RiskCheckResult {
        if (limits.emergencyStop) {
            return RiskCheckResult(false, "Emergency Stop switch is activated.")
        }

        if (limits.stopOnProfitEnabled && limits.profitTarget > 0.0 && currentSessionProfit >= limits.profitTarget) {
            return RiskCheckResult(
                false,
                "Stop if Profit Reaches triggered (+${formatMoney(currentSessionProfit)} >= ${formatMoney(limits.profitTarget)})."
            )
        }

        val effectiveLoss = max(currentSessionLoss, max(0.0, -currentSessionProfit))
        if (limits.stopOnLossEnabled && limits.maxSessionLoss > 0.0 && effectiveLoss >= limits.maxSessionLoss) {
            return RiskCheckResult(
                false,
                "Stop if Loss Reaches triggered (-${formatMoney(effectiveLoss)} >= ${formatMoney(limits.maxSessionLoss)})."
            )
        }

        if (limits.stopOnLossEnabled && limits.maxDailyLoss > 0.0 && currentDailyLoss >= limits.maxDailyLoss) {
            return RiskCheckResult(
                false,
                "Daily loss limit reached (-${formatMoney(currentDailyLoss)} >= ${formatMoney(limits.maxDailyLoss)})."
            )
        }

        return RiskCheckResult(true, null)
    }

    fun validateBetExecution(
        proposedStake: Double,
        currentSessionLoss: Double,
        currentRounds: Int,
        currentProgressionLevel: Int,
        currentBalance: Double,
        limits: RiskLimits,
        currentSessionProfit: Double = 0.0,
        balanceStatus: com.example.data.model.BalanceDetectionStatus = com.example.data.model.BalanceDetectionStatus.VERIFIED,
        balanceFailureReason: String? = null,
        currentDailyLoss: Double = 0.0,
        currentSequenceExposure: Double = proposedStake
    ): RiskCheckResult {
        val sessionLimitCheck = evaluateSessionLimits(
            currentSessionProfit = currentSessionProfit,
            currentSessionLoss = currentSessionLoss,
            limits = limits,
            currentDailyLoss = currentDailyLoss
        )
        if (!sessionLimitCheck.isAllowed) {
            return sessionLimitCheck
        }

        if (balanceStatus == com.example.data.model.BalanceDetectionStatus.UNAVAILABLE) {
            return RiskCheckResult(
                false,
                "Cannot place bet: Balance is UNAVAILABLE (${balanceFailureReason ?: "Not visible on screen"})."
            )
        }

        if (balanceStatus == com.example.data.model.BalanceDetectionStatus.SCANNING) {
            return RiskCheckResult(
                false,
                "Cannot place bet: Balance is still SCANNING. Waiting for verified reading."
            )
        }

        if (balanceStatus == com.example.data.model.BalanceDetectionStatus.STALE) {
            return RiskCheckResult(
                false,
                "Cannot place bet: Balance reading is STALE (${balanceFailureReason ?: "Needs refresh"})."
            )
        }

        if (balanceStatus != com.example.data.model.BalanceDetectionStatus.VERIFIED &&
            balanceStatus != com.example.data.model.BalanceDetectionStatus.DETECTED
        ) {
            return RiskCheckResult(
                false,
                "Cannot place bet: Verified account balance is unavailable (${balanceFailureReason ?: balanceStatus.name})."
            )
        }

        if (!currentBalance.isFinite() || currentBalance < 0.0) {
            return RiskCheckResult(
                false,
                "Cannot place bet: Verified account balance is unavailable ($currentBalance)."
            )
        }

        if (proposedStake > limits.maxSingleStake) {
            return RiskCheckResult(
                false,
                "Proposed stake (${formatMoney(proposedStake)}) exceeds Max Single Stake (${formatMoney(limits.maxSingleStake)})."
            )
        }

        if (limits.maxSequenceExposure > 0.0 && currentSequenceExposure > limits.maxSequenceExposure) {
            return RiskCheckResult(
                false,
                "Sequence exposure (${formatMoney(currentSequenceExposure)}) exceeds Max Sequence Exposure (${formatMoney(limits.maxSequenceExposure)})."
            )
        }

        val effectiveLoss = max(currentSessionLoss, max(0.0, -currentSessionProfit))
        if (limits.stopOnLossEnabled && limits.maxSessionLoss > 0.0 && (effectiveLoss + proposedStake) > limits.maxSessionLoss) {
            return RiskCheckResult(
                false,
                "Potential loss exceeds Stop if Loss Reaches limit (${formatMoney(limits.maxSessionLoss)})."
            )
        }

        if (limits.stopOnLossEnabled && limits.maxDailyLoss > 0.0 && (currentDailyLoss + proposedStake) > limits.maxDailyLoss) {
            return RiskCheckResult(
                false,
                "Potential loss exceeds Daily Loss limit (${formatMoney(limits.maxDailyLoss)})."
            )
        }

        if (currentRounds >= limits.maxTotalRounds) {
            return RiskCheckResult(
                false,
                "Session round limit reached (${limits.maxTotalRounds} rounds max)."
            )
        }

        if (currentProgressionLevel > limits.maxProgressionLevel) {
            return RiskCheckResult(
                false,
                "Maximum stake progression level (${limits.maxProgressionLevel}) reached."
            )
        }

        if (currentBalance < proposedStake) {
            return RiskCheckResult(
                false,
                "Insufficient balance (${formatMoney(currentBalance)}) for required stake (${formatMoney(proposedStake)})."
            )
        }

        val requiredReserve = max(limits.minBalanceThreshold, limits.safetyReserveAmount)
        if ((currentBalance - proposedStake) < requiredReserve) {
            return RiskCheckResult(
                false,
                "Balance would drop below protected reserve (${formatMoney(requiredReserve)})."
            )
        }

        return RiskCheckResult(true, null)
    }

    private fun formatMoney(value: Double): String = CurrencyManager.formatAmount(value)
}
