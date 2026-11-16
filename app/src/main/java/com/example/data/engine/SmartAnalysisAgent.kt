package com.example.data.engine

import com.example.data.model.CalendarHourUtils
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.GameState
import com.example.data.model.PatternOccurrenceRecord
import com.example.data.model.StrategyConfig
import java.util.Locale

data class SmartStrategyBreakdown(
    val strategyName: String,
    val ruleExplanation: String,
    val hourlyProgressText: String,
    val streakProgressText: String,
    val timestampsSummary: String,
    val remainingToTriggerText: String
)

data class SmartAgentExplanation(
    val gameName: String,
    val liveStatusLine: String,
    val sampleConfidenceLabel: String,
    val windowComparisonText: String,
    val strategyBreakdowns: List<SmartStrategyBreakdown>,
    val integrityDisclaimer: String = "Statistical observation only — patterns summarize completed rounds and never guarantee future outcomes."
)

/**
 * Generates plain-language, strictly factual explanations of active strategies,
 * occurrence counts, exact timestamps, streaks, and 30m vs. hourly comparisons
 * purely from real saved CrashRecords and live state.
 */
object SmartAnalysisAgent {

    fun generateExplanation(
        gameState: GameState,
        strategies: List<CustomPatternStrategy>,
        report: StatisticalReport,
        hourlyBundle: MultiWindowAnalysisBundle,
        occurrences: List<PatternOccurrenceRecord>,
        config: StrategyConfig,
        nowMs: Long = System.currentTimeMillis()
    ): SmartAgentExplanation {
        val enabledStrategies = strategies.filter { it.isEnabled }
        val breakdowns = enabledStrategies.map { strat ->
            val telemetry = StatisticalAnalysisEngine.buildStrategyTelemetry(
                strategy = strat,
                occurrences = occurrences,
                report = report,
                config = config,
                nowMs = nowMs
            )

            val firstStr = telemetry.firstOccurrenceMs?.let { CalendarHourUtils.formatExactTime(it) } ?: "None yet"
            val lastStr = telemetry.mostRecentOccurrenceMs?.let { CalendarHourUtils.formatExactTime(it) } ?: "None yet"

            SmartStrategyBreakdown(
                strategyName = strat.name,
                ruleExplanation = strat.summaryDescription(),
                hourlyProgressText = "${telemetry.currentHourOccurrences} of ${telemetry.requiredPerHour} observed in current hour",
                streakProgressText = "${telemetry.currentConsecutiveSequence} of ${telemetry.targetSequenceLength} active sequence",
                timestampsSummary = "First: $firstStr · Latest: $lastStr (Total recorded: ${telemetry.totalOccurrences})",
                remainingToTriggerText = if (telemetry.remainingToTrigger == 0) {
                    "Condition threshold met in completed rounds"
                } else {
                    "${telemetry.remainingToTrigger} remaining to match rule"
                }
            )
        }

        val r30 = hourlyBundle.rolling30m
        val curHr = hourlyBundle.currentHour
        val comparisonText = buildString {
            append("30m Rolling: ${r30.totalRounds} rounds (${HourlyAnalysisEngine.formatPercent(r30.lowPct)} <${String.format(Locale.US, "%.2fx", config.lowThreshold)}, longest chain ${r30.longestConsecutiveLowStreak}). ")
            append("Current Hour: ${curHr.totalRounds} rounds (${HourlyAnalysisEngine.formatPercent(curHr.lowPct)} low, longest chain ${curHr.longestConsecutiveLowStreak}).")
        }

        val sampleConfidence = when {
            curHr.totalRounds < 5 -> "INSUFFICIENT DATA (${curHr.totalRounds} completed rounds this hour)"
            curHr.totalRounds < 20 -> "Low sample (${curHr.totalRounds} rounds) — building hour baseline"
            curHr.totalRounds < 50 -> "Moderate sample (${curHr.totalRounds} rounds)"
            else -> "High sample (${curHr.totalRounds} completed rounds)"
        }

        val liveStatus = when {
            gameState.isStandbyOutsideSchedule -> gameState.scheduleStandbyMessage ?: "Standby — Outside scheduled hours"
            gameState.liveDetectionLost -> "LIVE DETECTION LOST — Re-scanning displayed game content"
            !gameState.isDetected -> gameState.pipelineStatus.displayLabel
            else -> "${gameState.displayedGameName} · ${gameState.pipelineStatus.displayLabel}"
        }

        return SmartAgentExplanation(
            gameName = gameState.displayedGameName,
            liveStatusLine = liveStatus,
            sampleConfidenceLabel = sampleConfidence,
            windowComparisonText = comparisonText,
            strategyBreakdowns = breakdowns
        )
    }
}
