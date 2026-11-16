package com.example.data.engine

import com.example.data.model.CalendarHourUtils
import com.example.data.model.CrashColorCategory
import com.example.data.model.CrashRecord
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.PatternOccurrenceRecord
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyMode
import java.util.Locale
import kotlin.math.abs

enum class MultiplierBucket(val label: String) {
    LOW("Low (< threshold)"),
    BOUNDARY("Boundary (== threshold)"),
    MEDIUM("Medium"),
    BIG("Big Multiplier")
}

data class BigMultiplierGap(
    val firstTimestampMs: Long,
    val firstMultiplier: Double,
    val secondTimestampMs: Long,
    val secondMultiplier: Double,
    val gapMinutes: Double,
    val isWithinQualifyingRange: Boolean
)

data class StrategyOccurrenceTelemetry(
    val strategyId: Long,
    val strategyName: String,
    val gameId: String,
    val totalOccurrences: Int,
    val currentHourOccurrences: Int,
    val requiredPerHour: Int,
    val firstOccurrenceMs: Long?,
    val mostRecentOccurrenceMs: Long?,
    val averageIntervalMs: Long?,
    val shortestIntervalMs: Long?,
    val longestIntervalMs: Long?,
    val currentConsecutiveSequence: Int,
    val targetSequenceLength: Int,
    val remainingToTrigger: Int
)

data class StatisticalReport(
    val gameId: String,
    val gameName: String,
    val totalCompletedRounds: Int,
    val lowCount: Int,
    val boundaryCount: Int,
    val mediumCount: Int,
    val bigCount: Int,
    val currentConsecutiveLows: Int,
    val longestConsecutiveLows: Int,
    val bigMultiplierCount: Int,
    val bigMultiplierClusters: Int,
    val bigMultiplierGaps: List<BigMultiplierGap>,
    val currentAlternatingLowMedLength: Int,
    val maxAlternatingLowMedLength: Int,
    val lowsBeforeBigCounts: List<Int>,
    val trailingColorCategories: List<CrashColorCategory>,
    val colorDistribution: Map<CrashColorCategory, Int>,
    val topRepeatedColorSequences: List<Pair<String, Int>>,
    val sampleSufficient: Boolean,
    val factualSummary: String
)

/**
 * Pure statistical pattern analysis engine operating exclusively on completed [CrashRecord] entries.
 * Never consumes live climbing multipliers and never makes predictive claims about future rounds.
 */
object StatisticalAnalysisEngine {

    private const val MIN_SUFFICIENT_SAMPLE = 5

    fun classifyBucket(
        multiplier: Double,
        lowThreshold: Double = 2.00,
        boundaryValue: Double = 2.00,
        bigThreshold: Double = 10.00
    ): MultiplierBucket {
        return when {
            abs(multiplier - boundaryValue) <= 0.0001 -> MultiplierBucket.BOUNDARY
            multiplier < lowThreshold -> MultiplierBucket.LOW
            multiplier >= bigThreshold -> MultiplierBucket.BIG
            else -> MultiplierBucket.MEDIUM
        }
    }

    fun analyzeCompletedCrashes(
        records: List<CrashRecord>,
        config: StrategyConfig = StrategyConfig(),
        minBigGapMinutes: Int = 5,
        maxBigGapMinutes: Int = 15,
        gameId: String = "ALL",
        gameName: String = "All Games"
    ): StatisticalReport {
        val lowTh = config.lowThreshold.takeIf { it.isFinite() && it >= 1.01 } ?: 2.00
        val boundaryVal = config.boundaryValue.takeIf { it.isFinite() && it >= 1.00 } ?: 2.00
        val bigTh = config.bigThreshold.takeIf { it.isFinite() && it > lowTh } ?: 10.00

        val scoped = if (gameId == "ALL") records else records.filter { it.gameId == gameId }
        val sorted = scoped.sortedWith(compareBy({ it.crashTimestamp }, { it.id }))

        if (sorted.isEmpty()) {
            return StatisticalReport(
                gameId = gameId,
                gameName = gameName,
                totalCompletedRounds = 0,
                lowCount = 0,
                boundaryCount = 0,
                mediumCount = 0,
                bigCount = 0,
                currentConsecutiveLows = 0,
                longestConsecutiveLows = 0,
                bigMultiplierCount = 0,
                bigMultiplierClusters = 0,
                bigMultiplierGaps = emptyList(),
                currentAlternatingLowMedLength = 0,
                maxAlternatingLowMedLength = 0,
                lowsBeforeBigCounts = emptyList(),
                trailingColorCategories = emptyList(),
                colorDistribution = emptyMap(),
                topRepeatedColorSequences = emptyList(),
                sampleSufficient = false,
                factualSummary = "INSUFFICIENT DATA — Waiting for completed crash rounds."
            )
        }

        var lowCount = 0
        var boundaryCount = 0
        var mediumCount = 0
        var bigCount = 0

        var runningLowStreak = 0
        var longestLowStreak = 0
        var lowsBeforeCurrentBig = 0
        val lowsBeforeBig = mutableListOf<Int>()

        var bigClusters = 0
        var prevWasBig = false
        val bigRecords = mutableListOf<CrashRecord>()

        val colorCounts = linkedMapOf<CrashColorCategory, Int>()

        // Alternating Low / Medium run tracker
        var altLength = 0
        var maxAltLength = 0
        var lastAltWasLow: Boolean? = null

        for (rec in sorted) {
            colorCounts[rec.colorCategory] = (colorCounts[rec.colorCategory] ?: 0) + 1
            val bucket = classifyBucket(rec.multiplier, lowTh, boundaryVal, bigTh)
            when (bucket) {
                MultiplierBucket.LOW -> {
                    lowCount++
                    runningLowStreak++
                    if (runningLowStreak > longestLowStreak) longestLowStreak = runningLowStreak
                    lowsBeforeCurrentBig++
                    prevWasBig = false
                    if (lastAltWasLow == false) {
                        altLength++
                    } else if (lastAltWasLow == null) {
                        altLength = 1
                    } else {
                        altLength = 1
                    }
                    lastAltWasLow = true
                    if (altLength > maxAltLength) maxAltLength = altLength
                }
                MultiplierBucket.BOUNDARY -> {
                    boundaryCount++
                    runningLowStreak = 0
                    lowsBeforeCurrentBig = 0
                    prevWasBig = false
                    altLength = 0
                    lastAltWasLow = null
                }
                MultiplierBucket.MEDIUM -> {
                    mediumCount++
                    runningLowStreak = 0
                    lowsBeforeCurrentBig = 0
                    prevWasBig = false
                    if (lastAltWasLow == true) {
                        altLength++
                    } else {
                        altLength = 1
                    }
                    lastAltWasLow = false
                    if (altLength > maxAltLength) maxAltLength = altLength
                }
                MultiplierBucket.BIG -> {
                    bigCount++
                    bigRecords.add(rec)
                    if (!prevWasBig) {
                        lowsBeforeBig.add(lowsBeforeCurrentBig)
                    } else {
                        bigClusters++
                    }
                    prevWasBig = true
                    runningLowStreak = 0
                    lowsBeforeCurrentBig = 0
                    altLength = 0
                    lastAltWasLow = null
                }
            }
        }

        val bigGaps = mutableListOf<BigMultiplierGap>()
        for (i in 1 until bigRecords.size) {
            val prev = bigRecords[i - 1]
            val curr = bigRecords[i]
            val gapMin = ((curr.crashTimestamp - prev.crashTimestamp).coerceAtLeast(0L)) / 60_000.0
            bigGaps.add(
                BigMultiplierGap(
                    firstTimestampMs = prev.crashTimestamp,
                    firstMultiplier = prev.multiplier,
                    secondTimestampMs = curr.crashTimestamp,
                    secondMultiplier = curr.multiplier,
                    gapMinutes = gapMin,
                    isWithinQualifyingRange = gapMin in minBigGapMinutes.toDouble()..maxBigGapMinutes.toDouble()
                )
            )
        }

        // Repeated 3-color motif frequency across actual color sequence
        val colorTriples = linkedMapOf<String, Int>()
        if (sorted.size >= 3) {
            for (i in 0..(sorted.size - 3)) {
                val seqKey = "${sorted[i].colorCategory.displayName}->${sorted[i + 1].colorCategory.displayName}->${sorted[i + 2].colorCategory.displayName}"
                colorTriples[seqKey] = (colorTriples[seqKey] ?: 0) + 1
            }
        }
        val topColors = colorTriples.entries
            .filter { it.value >= 1 }
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value }

        val sampleOk = sorted.size >= MIN_SUFFICIENT_SAMPLE
        val summary = buildString {
            append("Observed ${sorted.size} completed rounds: ")
            append("$lowCount low (<${String.format(Locale.US, "%.2fx", lowTh)}), ")
            append("$mediumCount medium, $bigCount big (>=${String.format(Locale.US, "%.2fx", bigTh)}). ")
            append("Current consecutive low chain: $runningLowStreak (longest observed: $longestLowStreak).")
            if (!sampleOk) {
                append(" INSUFFICIENT DATA for stable trend baselines (< $MIN_SUFFICIENT_SAMPLE rounds).")
            }
        }

        return StatisticalReport(
            gameId = gameId,
            gameName = gameName,
            totalCompletedRounds = sorted.size,
            lowCount = lowCount,
            boundaryCount = boundaryCount,
            mediumCount = mediumCount,
            bigCount = bigCount,
            currentConsecutiveLows = runningLowStreak,
            longestConsecutiveLows = longestLowStreak,
            bigMultiplierCount = bigCount,
            bigMultiplierClusters = bigClusters,
            bigMultiplierGaps = bigGaps,
            currentAlternatingLowMedLength = altLength,
            maxAlternatingLowMedLength = maxAltLength,
            lowsBeforeBigCounts = lowsBeforeBig,
            trailingColorCategories = sorted.takeLast(8).map { it.colorCategory },
            colorDistribution = colorCounts,
            topRepeatedColorSequences = topColors,
            sampleSufficient = sampleOk,
            factualSummary = summary
        )
    }

    fun buildStrategyTelemetry(
        strategy: CustomPatternStrategy,
        occurrences: List<PatternOccurrenceRecord>,
        report: StatisticalReport,
        config: StrategyConfig,
        nowMs: Long = System.currentTimeMillis()
    ): StrategyOccurrenceTelemetry {
        val currentHourStart = CalendarHourUtils.hourStartFor(nowMs)
        val scopedOccurrences = occurrences
            .filter { it.strategyId == strategy.id && (strategy.targetGameId == "ALL" || it.gameId == strategy.targetGameId) }
            .sortedBy { it.triggerTimestampMs }

        val hourOccurrences = scopedOccurrences.count { it.hourStartTimestamp == currentHourStart }
        val firstTs = scopedOccurrences.firstOrNull()?.triggerTimestampMs
        val lastTs = scopedOccurrences.lastOrNull()?.triggerTimestampMs

        val gaps = mutableListOf<Long>()
        for (i in 1 until scopedOccurrences.size) {
            val delta = (scopedOccurrences[i].triggerTimestampMs - scopedOccurrences[i - 1].triggerTimestampMs).coerceAtLeast(0L)
            gaps.add(delta)
        }

        val targetSeq = when (strategy.strategyMode) {
            StrategyMode.STRATEGY_A_CONSECUTIVE_LOWS ->
                strategy.getConditions().firstOrNull()?.consecutiveRounds ?: 14
            StrategyMode.STRATEGY_B_BIG_INTERVALS -> 2
            StrategyMode.STRATEGY_C_COLOR_PATTERNS ->
                strategy.getColorSequence().size.coerceAtLeast(1)
            StrategyMode.CUSTOM_CONDITION ->
                strategy.getConditions().firstOrNull()?.consecutiveRounds ?: 1
        }

        val currentSeq = when (strategy.strategyMode) {
            StrategyMode.STRATEGY_A_CONSECUTIVE_LOWS -> report.currentConsecutiveLows
            StrategyMode.STRATEGY_C_COLOR_PATTERNS -> {
                val pattern = strategy.getColorSequence()
                val tail = report.trailingColorCategories
                var matched = 0
                if (pattern.isNotEmpty() && tail.isNotEmpty()) {
                    for (len in minOf(pattern.size, tail.size) downTo 1) {
                        if (tail.takeLast(len) == pattern.take(len)) {
                            matched = len
                            break
                        }
                    }
                }
                matched
            }
            StrategyMode.STRATEGY_B_BIG_INTERVALS ->
                report.bigMultiplierGaps.count {
                    it.gapMinutes in strategy.minIntervalMinutes.toDouble()..strategy.maxIntervalMinutes.toDouble()
                }.coerceAtMost(2)
            StrategyMode.CUSTOM_CONDITION -> report.currentConsecutiveLows
        }

        val remaining = (targetSeq - currentSeq).coerceAtLeast(0)

        return StrategyOccurrenceTelemetry(
            strategyId = strategy.id,
            strategyName = strategy.name,
            gameId = report.gameId,
            totalOccurrences = scopedOccurrences.size,
            currentHourOccurrences = hourOccurrences,
            requiredPerHour = strategy.requiredOccurrencesPerHour.coerceAtLeast(1),
            firstOccurrenceMs = firstTs,
            mostRecentOccurrenceMs = lastTs,
            averageIntervalMs = if (gaps.isEmpty()) null else gaps.average().toLong(),
            shortestIntervalMs = gaps.minOrNull(),
            longestIntervalMs = gaps.maxOrNull(),
            currentConsecutiveSequence = currentSeq,
            targetSequenceLength = targetSeq,
            remainingToTrigger = remaining
        )
    }
}
