package com.example.data.engine

import com.example.data.model.CalendarHourUtils
import com.example.data.model.CrashColorCategory
import com.example.data.model.CrashRecord
import com.example.data.model.HourlySessionSummary
import com.example.data.model.StrategyConfig
import java.util.Locale

data class HourlyWindowMetrics(
    val windowLabel: String,
    val startMs: Long,
    val endMs: Long,
    val gameId: String,
    val gameName: String,
    val totalRounds: Int,
    val lowCount: Int,
    val lowPct: Double,
    val boundaryCount: Int,
    val boundaryPct: Double,
    val mediumCount: Int,
    val mediumPct: Double,
    val bigCount: Int,
    val bigPct: Double,
    val longestConsecutiveLowStreak: Int,
    val qualifyingBigIntervalsCount: Int,
    val averageMultiplier: Double,
    val medianMultiplier: Double,
    val colorDistribution: Map<CrashColorCategory, Int>,
    val isInsufficientData: Boolean
) {
    val sampleStatusLabel: String
        get() = if (isInsufficientData) "INSUFFICIENT DATA" else "$totalRounds rounds analyzed"
}

data class MultiWindowAnalysisBundle(
    val rolling30m: HourlyWindowMetrics,
    val rolling60m: HourlyWindowMetrics,
    val currentHour: HourlyWindowMetrics,
    val previousHour: HourlyWindowMetrics,
    val currentHourStartMs: Long
)

data class HourTransitionResult(
    val closedHourStartMs: Long,
    val newHourStartMs: Long,
    val savedSummaries: List<HourlySessionSummary>
)

/**
 * Computes independent 30m, 60m, Current Calendar Hour (default e.g. 14:00-14:59),
 * and Previous Completed Hour metrics, and handles clock-hour boundary finalization.
 */
object HourlyAnalysisEngine {

    private const val MIN_SAMPLE_FOR_STATS = 5

    fun computeMetricsForWindow(
        windowLabel: String,
        records: List<CrashRecord>,
        startMs: Long,
        endMs: Long,
        config: StrategyConfig = StrategyConfig(),
        minBigIntervalMinutes: Int = 5,
        maxBigIntervalMinutes: Int = 15,
        gameId: String = "ALL",
        gameName: String = "All Games"
    ): HourlyWindowMetrics {
        val filtered = records.filter { rec ->
            rec.crashTimestamp in startMs..endMs &&
                (gameId == "ALL" || rec.gameId == gameId)
        }
        val report = StatisticalAnalysisEngine.analyzeCompletedCrashes(
            records = filtered,
            config = config,
            minBigGapMinutes = minBigIntervalMinutes,
            maxBigGapMinutes = maxBigIntervalMinutes,
            gameId = gameId,
            gameName = gameName
        )

        val total = filtered.size
        fun pct(count: Int): Double = if (total == 0) 0.0 else (count * 100.0) / total
        val multsSorted = filtered.map { it.multiplier }.sorted()
        val avg = if (multsSorted.isEmpty()) 0.0 else multsSorted.average()
        val median = when {
            multsSorted.isEmpty() -> 0.0
            multsSorted.size % 2 == 1 -> multsSorted[multsSorted.size / 2]
            else -> (multsSorted[multsSorted.size / 2 - 1] + multsSorted[multsSorted.size / 2]) / 2.0
        }
        val qualifyingGaps = report.bigMultiplierGaps.count { it.isWithinQualifyingRange }

        return HourlyWindowMetrics(
            windowLabel = windowLabel,
            startMs = startMs,
            endMs = endMs,
            gameId = gameId,
            gameName = gameName,
            totalRounds = total,
            lowCount = report.lowCount,
            lowPct = pct(report.lowCount),
            boundaryCount = report.boundaryCount,
            boundaryPct = pct(report.boundaryCount),
            mediumCount = report.mediumCount,
            mediumPct = pct(report.mediumCount),
            bigCount = report.bigCount,
            bigPct = pct(report.bigCount),
            longestConsecutiveLowStreak = report.longestConsecutiveLows,
            qualifyingBigIntervalsCount = qualifyingGaps,
            averageMultiplier = avg,
            medianMultiplier = median,
            colorDistribution = report.colorDistribution,
            isInsufficientData = total < MIN_SAMPLE_FOR_STATS
        )
    }

    fun buildMultiWindowBundle(
        allRecords: List<CrashRecord>,
        config: StrategyConfig = StrategyConfig(),
        minBigIntervalMinutes: Int = 5,
        maxBigIntervalMinutes: Int = 15,
        gameId: String = "ALL",
        gameName: String = "All Games",
        nowMs: Long = System.currentTimeMillis()
    ): MultiWindowAnalysisBundle {
        val currentHourStart = CalendarHourUtils.hourStartFor(nowMs)
        val currentHourEnd = CalendarHourUtils.hourEndFor(currentHourStart)
        val prevHourStart = currentHourStart - 3_600_000L
        val prevHourEnd = currentHourStart - 1L

        val rolling30 = computeMetricsForWindow(
            windowLabel = "Rolling 30m",
            records = allRecords,
            startMs = nowMs - 30 * 60_000L,
            endMs = nowMs,
            config = config,
            minBigIntervalMinutes = minBigIntervalMinutes,
            maxBigIntervalMinutes = maxBigIntervalMinutes,
            gameId = gameId,
            gameName = gameName
        )

        val rolling60 = computeMetricsForWindow(
            windowLabel = "Rolling 60m",
            records = allRecords,
            startMs = nowMs - 60 * 60_000L,
            endMs = nowMs,
            config = config,
            minBigIntervalMinutes = minBigIntervalMinutes,
            maxBigIntervalMinutes = maxBigIntervalMinutes,
            gameId = gameId,
            gameName = gameName
        )

        val currentHour = computeMetricsForWindow(
            windowLabel = "Current Hour (${CalendarHourUtils.formatHourWindow(currentHourStart)})",
            records = allRecords,
            startMs = currentHourStart,
            endMs = currentHourEnd.coerceAtMost(nowMs),
            config = config,
            minBigIntervalMinutes = minBigIntervalMinutes,
            maxBigIntervalMinutes = maxBigIntervalMinutes,
            gameId = gameId,
            gameName = gameName
        )

        val previousHour = computeMetricsForWindow(
            windowLabel = "Previous Hour (${CalendarHourUtils.formatHourWindow(prevHourStart)})",
            records = allRecords,
            startMs = prevHourStart,
            endMs = prevHourEnd,
            config = config,
            minBigIntervalMinutes = minBigIntervalMinutes,
            maxBigIntervalMinutes = maxBigIntervalMinutes,
            gameId = gameId,
            gameName = gameName
        )

        return MultiWindowAnalysisBundle(
            rolling30m = rolling30,
            rolling60m = rolling60,
            currentHour = currentHour,
            previousHour = previousHour,
            currentHourStartMs = currentHourStart
        )
    }

    fun toHourlySummaryEntity(
        metrics: HourlyWindowMetrics,
        finalizedAtMs: Long = System.currentTimeMillis()
    ): HourlySessionSummary {
        val colorJson = metrics.colorDistribution.entries.joinToString(",") { (cat, count) ->
            "\"${cat.name}\":$count"
        }.let { "{$it}" }

        return HourlySessionSummary(
            gameId = metrics.gameId,
            gameName = metrics.gameName,
            hourStartTimestamp = metrics.startMs,
            hourEndTimestamp = metrics.endMs,
            totalRounds = metrics.totalRounds,
            lowCount = metrics.lowCount,
            lowPct = metrics.lowPct,
            boundaryCount = metrics.boundaryCount,
            boundaryPct = metrics.boundaryPct,
            mediumCount = metrics.mediumCount,
            mediumPct = metrics.mediumPct,
            bigCount = metrics.bigCount,
            bigPct = metrics.bigPct,
            longestConsecutiveLowStreak = metrics.longestConsecutiveLowStreak,
            qualifyingBigIntervalsCount = metrics.qualifyingBigIntervalsCount,
            averageMultiplier = metrics.averageMultiplier,
            medianMultiplier = metrics.medianMultiplier,
            colorDistributionJson = colorJson,
            sampleStatus = if (metrics.isInsufficientData) "INSUFFICIENT_DATA" else "SUFFICIENT",
            finalizedAtMs = finalizedAtMs
        )
    }

    fun finalizeClosingHourSummaries(
        allRecords: List<CrashRecord>,
        closingHourStartMs: Long,
        newHourStartMs: Long,
        config: StrategyConfig = StrategyConfig(),
        finalizedAtMs: Long = System.currentTimeMillis()
    ): HourTransitionResult {
        val closingEndMs = CalendarHourUtils.hourEndFor(closingHourStartMs)
        val closingRecords = allRecords.filter { it.crashTimestamp in closingHourStartMs..closingEndMs }
        val distinctGames = closingRecords.groupBy { it.gameId }
        val summaries = mutableListOf<HourlySessionSummary>()

        // Save overall + per-game summaries
        if (closingRecords.isNotEmpty()) {
            val overallMetrics = computeMetricsForWindow(
                windowLabel = CalendarHourUtils.formatHourWindow(closingHourStartMs),
                records = closingRecords,
                startMs = closingHourStartMs,
                endMs = closingEndMs,
                config = config,
                gameId = "ALL",
                gameName = "All Monitored Games"
            )
            summaries.add(toHourlySummaryEntity(overallMetrics, finalizedAtMs))

            distinctGames.forEach { (gId, gRecords) ->
                val gName = gRecords.lastOrNull()?.gameName ?: gId.replaceFirstChar { it.uppercaseChar() }
                val gameMetrics = computeMetricsForWindow(
                    windowLabel = CalendarHourUtils.formatHourWindow(closingHourStartMs),
                    records = gRecords,
                    startMs = closingHourStartMs,
                    endMs = closingEndMs,
                    config = config,
                    gameId = gId,
                    gameName = gName
                )
                summaries.add(toHourlySummaryEntity(gameMetrics, finalizedAtMs))
            }
        }

        return HourTransitionResult(
            closedHourStartMs = closingHourStartMs,
            newHourStartMs = newHourStartMs,
            savedSummaries = summaries
        )
    }

    fun formatPercent(pct: Double): String = String.format(Locale.US, "%.1f%%", pct)

    enum class TimeRangePreset(val label: String, val durationMs: Long?) {
        ONE_HOUR("1h", 1 * 3_600_000L),
        THREE_HOURS("3h", 3 * 3_600_000L),
        SIX_HOURS("6h", 6 * 3_600_000L),
        TWELVE_HOURS("12h", 12 * 3_600_000L),
        TWENTY_FOUR_HOURS("24h", 24 * 3_600_000L),
        CUSTOM("Custom", null)
    }

    fun analyzeTimeRange(
        preset: TimeRangePreset,
        records: List<CrashRecord>,
        config: StrategyConfig = StrategyConfig(),
        gameId: String = "ALL",
        gameName: String = "All Games",
        customStartMs: Long = 0L,
        customEndMs: Long = System.currentTimeMillis(),
        nowMs: Long = System.currentTimeMillis()
    ): HourlyWindowMetrics {
        val start = if (preset == TimeRangePreset.CUSTOM) {
            customStartMs.coerceAtMost(customEndMs)
        } else {
            nowMs - (preset.durationMs ?: 3_600_000L)
        }
        val end = if (preset == TimeRangePreset.CUSTOM) customEndMs else nowMs
        return computeMetricsForWindow(
            windowLabel = if (preset == TimeRangePreset.CUSTOM) "Custom Range" else "Last ${preset.label}",
            records = records,
            startMs = start,
            endMs = end,
            config = config,
            gameId = gameId,
            gameName = gameName
        )
    }
}
