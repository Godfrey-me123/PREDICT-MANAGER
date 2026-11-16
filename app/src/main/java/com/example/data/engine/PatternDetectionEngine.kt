package com.example.data.engine

import com.example.data.model.CrashRecord
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.MultiplierRecord
import com.example.data.model.PatternCondition
import com.example.data.model.PatternLogicOperator
import com.example.data.model.RepeatNotificationPolicy
import com.example.data.model.StrategyNotificationType
import java.util.Locale
import kotlin.math.abs

data class PatternAlertEvent(
    val strategy: CustomPatternStrategy,
    val notificationType: StrategyNotificationType,
    val title: String,
    val message: String,
    val triggeringRecordKey: String? = null,
    val triggeringMultiplier: Double? = null,
    val occurrenceKey: String,
    val timestampMs: Long
)

data class PatternConditionProgress(
    val matchedCount: Int = 0,
    val requiredCount: Int = 1,
    val isApproaching: Boolean = false,
    val isReached: Boolean = false,
    val streakStartKey: String? = null,
    val streakStartTimestampMs: Long = 0L,
    val lastEvaluatedRecordKey: String? = null,
    val lastEvaluatedMultiplier: Double? = null
)

data class PatternEvaluationBatch(
    val updatedStrategies: List<CustomPatternStrategy>,
    val events: List<PatternAlertEvent>
)

/**
 * Chronological, non-predictive pattern detection engine for Section 15.
 * - Evaluates user-defined CustomPatternStrategy conditions in chronological order.
 * - Prevents double-counting via per-strategy lastEvaluatedRecordKey tracking.
 * - Resets consecutive streaks immediately when a breaking round occurs and emits
 *   STRATEGY_INVALIDATED when an approaching or active occurrence is broken.
 * - Uses strictly neutral factual status language (never predictive claims).
 */
class PatternDetectionEngine {

    companion object {
        fun formatMultiplier(value: Double): String = String.format(Locale.US, "%.2fx", value)
    }

    /**
     * Evaluates a single condition across a chronologically sorted list (`oldest -> newest`).
     * Uses records filtered to `condition.timeWindowMinutes` (relative to `nowMs`) when specified.
     */
    fun evaluateCondition(
        condition: PatternCondition,
        chronologicalRecords: List<MultiplierRecord>,
        nowMs: Long = System.currentTimeMillis()
    ): PatternConditionProgress {
        val scopedRecords = if (condition.timeWindowMinutes != null && condition.timeWindowMinutes > 0) {
            val cutoff = nowMs - (condition.timeWindowMinutes * 60_000L)
            chronologicalRecords.filter { it.crashTimestamp >= cutoff }
        } else {
            chronologicalRecords
        }

        if (scopedRecords.isEmpty()) {
            val req = if (condition.sequenceMultipliers.isNotEmpty()) {
                condition.sequenceMultipliers.size
            } else {
                condition.consecutiveRounds.coerceAtLeast(1)
            }
            return PatternConditionProgress(matchedCount = 0, requiredCount = req)
        }

        // Mode 1: Exact sequence pattern check against trailing rounds
        if (condition.sequenceMultipliers.isNotEmpty()) {
            val seq = condition.sequenceMultipliers
            val req = seq.size
            var longestTailMatch = 0
            var startKey: String? = null
            var startTs = 0L

            for (startIdx in scopedRecords.indices) {
                var matched = 0
                for (offset in seq.indices) {
                    val recIdx = startIdx + offset
                    if (recIdx >= scopedRecords.size) break
                    if (abs(scopedRecords[recIdx].multiplier - seq[offset]) <= 0.01) {
                        matched++
                    } else {
                        break
                    }
                }
                if (startIdx + matched == scopedRecords.size && matched > 0) {
                    longestTailMatch = matched
                    startKey = scopedRecords[startIdx].dedupKey
                    startTs = scopedRecords[startIdx].crashTimestamp
                    break
                }
            }

            val reached = longestTailMatch >= req
            val approaching = !reached && req >= 2 && longestTailMatch == req - 1
            val lastRec = scopedRecords.last()
            return PatternConditionProgress(
                matchedCount = longestTailMatch,
                requiredCount = req,
                isApproaching = approaching,
                isReached = reached,
                streakStartKey = startKey,
                streakStartTimestampMs = startTs,
                lastEvaluatedRecordKey = lastRec.dedupKey,
                lastEvaluatedMultiplier = lastRec.multiplier
            )
        }

        // Mode 2: Consecutive rounds meeting threshold comparison
        val required = condition.consecutiveRounds.coerceAtLeast(1)
        var streak = 0
        var streakStartKey: String? = null
        var streakStartTs = 0L

        for (rec in scopedRecords) {
            if (condition.comparator.matches(rec.multiplier, condition.threshold, condition.upperThreshold)) {
                if (streak == 0) {
                    streakStartKey = rec.dedupKey
                    streakStartTs = rec.crashTimestamp
                }
                streak++
            } else {
                streak = 0
                streakStartKey = null
                streakStartTs = 0L
            }
        }

        val reached = streak >= required
        val approaching = !reached && required >= 2 && streak == required - 1
        val lastRec = scopedRecords.last()
        return PatternConditionProgress(
            matchedCount = streak,
            requiredCount = required,
            isApproaching = approaching,
            isReached = reached,
            streakStartKey = streakStartKey,
            streakStartTimestampMs = streakStartTs,
            lastEvaluatedRecordKey = lastRec.dedupKey,
            lastEvaluatedMultiplier = lastRec.multiplier
        )
    }

    /**
     * Evaluates all enabled [CustomPatternStrategy] rules against the current history records.
     * Processes only records strictly newer than each strategy's `lastEvaluatedRecordKey` step-by-step
     * so every intermediate approaching -> reached -> invalidated transition is captured accurately
     * without double-counting any round.
     */
    fun evaluateAllStrategies(
        strategies: List<CustomPatternStrategy>,
        records: List<MultiplierRecord>,
        nowMs: Long = System.currentTimeMillis()
    ): PatternEvaluationBatch {
        if (strategies.isEmpty() || records.isEmpty()) {
            return PatternEvaluationBatch(updatedStrategies = strategies, events = emptyList())
        }

        val chronological = records.sortedWith(
            compareBy<MultiplierRecord>(
                { it.crashTimestamp },
                { -it.sequenceIndex },
                { it.id }
            )
        )

        val updatedStrategies = mutableListOf<CustomPatternStrategy>()
        val emittedEvents = mutableListOf<PatternAlertEvent>()

        for (strategy in strategies) {
            if (!strategy.isEnabled) {
                updatedStrategies.add(strategy)
                continue
            }

            val conditions = strategy.getConditions()
            if (conditions.isEmpty()) {
                updatedStrategies.add(strategy)
                continue
            }

            val lastIdx = if (strategy.lastEvaluatedRecordKey != null) {
                chronological.indexOfLast { it.dedupKey == strategy.lastEvaluatedRecordKey }
            } else {
                -1
            }

            // If no new records exist after lastEvaluatedRecordKey, check only optional cooldown repeat
            if (lastIdx >= chronological.lastIndex) {
                var currentStrategy = strategy
                if (strategy.currentlyReached &&
                    strategy.repeatPolicy == RepeatNotificationPolicy.COOLDOWN_MINUTES &&
                    strategy.repeatCooldownMinutes > 0 &&
                    strategy.lastNotifiedAtMs > 0L &&
                    (nowMs - strategy.lastNotifiedAtMs) >= strategy.repeatCooldownMinutes * 60_000L
                ) {
                    val latestRec = chronological.last()
                    val occKey = strategy.lastTriggeredOccurrenceKey ?: "${strategy.id}_reach_${latestRec.dedupKey}"
                    emittedEvents.add(
                        buildEvent(
                            strategy = currentStrategy,
                            type = StrategyNotificationType.STRATEGY_REACHED,
                            triggerRec = latestRec,
                            occurrenceKey = occKey,
                            progressSummary = strategy.summaryDescription(),
                            nowMs = nowMs
                        )
                    )
                    currentStrategy = currentStrategy.copy(lastNotifiedAtMs = nowMs)
                }
                updatedStrategies.add(currentStrategy)
                continue
            }

            // Replay step-by-step through newly unseen records so rapid batches (e.g. 7 then 8th) never miss milestones
            var workingStrategy = strategy
            val startIndex = (lastIdx + 1).coerceAtLeast(0)
            for (endIdx in startIndex until chronological.size) {
                val prefixSlice = chronological.subList(0, endIdx + 1)
                val latestRec = chronological[endIdx]
                val stepResult = evaluateSingleSnapshotStep(
                    strategy = workingStrategy,
                    conditions = conditions,
                    chronologicalPrefix = prefixSlice,
                    latestRecord = latestRec,
                    nowMs = nowMs
                )
                workingStrategy = stepResult.first
                emittedEvents.addAll(stepResult.second)
            }
            updatedStrategies.add(workingStrategy)
        }

        return PatternEvaluationBatch(
            updatedStrategies = updatedStrategies,
            events = emittedEvents
        )
    }

    private fun evaluateSingleSnapshotStep(
        strategy: CustomPatternStrategy,
        conditions: List<PatternCondition>,
        chronologicalPrefix: List<MultiplierRecord>,
        latestRecord: MultiplierRecord,
        nowMs: Long
    ): Pair<CustomPatternStrategy, List<PatternAlertEvent>> {
        val progresses = conditions.map { evaluateCondition(it, chronologicalPrefix, nowMs) }
        val events = mutableListOf<PatternAlertEvent>()

        val anyBroken = progresses.any { !it.isReached && !it.isApproaching }
        val allReached = progresses.all { it.isReached }
        val anyReached = progresses.any { it.isReached }
        val isReachedNow = when (strategy.logicOperator) {
            PatternLogicOperator.AND -> allReached
            PatternLogicOperator.OR -> anyReached
        }

        val isApproachingNow = when (strategy.logicOperator) {
            PatternLogicOperator.AND -> !isReachedNow &&
                progresses.any { it.isApproaching } &&
                progresses.all { it.isApproaching || it.isReached }
            PatternLogicOperator.OR -> !isReachedNow && progresses.any { it.isApproaching }
        }

        val anchorKey = progresses.firstOrNull { it.streakStartKey != null }?.streakStartKey ?: latestRecord.dedupKey
        val waitingSatisfied = if (strategy.waitingPeriodSeconds <= 0) {
            true
        } else {
            val triggerWindowStart = progresses.maxOfOrNull { it.streakStartTimestampMs } ?: latestRecord.crashTimestamp
            (nowMs - triggerWindowStart) >= strategy.waitingPeriodSeconds * 1000L
        }

        var nextStrategy = strategy.copy(lastEvaluatedRecordKey = latestRecord.dedupKey)

        when {
            isReachedNow && waitingSatisfied -> {
                val occKey = "${strategy.id}_reach_$anchorKey"
                val shouldNotifyNew = strategy.lastTriggeredOccurrenceKey != occKey
                val shouldNotifyRepeat = !shouldNotifyNew &&
                    strategy.repeatPolicy == RepeatNotificationPolicy.COOLDOWN_MINUTES &&
                    strategy.repeatCooldownMinutes > 0 &&
                    (nowMs - strategy.lastNotifiedAtMs) >= strategy.repeatCooldownMinutes * 60_000L

                if (shouldNotifyNew || shouldNotifyRepeat) {
                    val primaryProg = progresses.firstOrNull { it.isReached } ?: progresses.first()
                    val summary = if (primaryProg.requiredCount > 1) {
                        "${primaryProg.matchedCount} of ${primaryProg.requiredCount} consecutive rounds (${strategy.summaryDescription()})"
                    } else {
                        strategy.summaryDescription()
                    }
                    events.add(
                        buildEvent(
                            strategy = nextStrategy,
                            type = StrategyNotificationType.STRATEGY_REACHED,
                            triggerRec = latestRecord,
                            occurrenceKey = occKey,
                            progressSummary = summary,
                            nowMs = nowMs
                        )
                    )
                    nextStrategy = nextStrategy.copy(
                        lastTriggeredOccurrenceKey = occKey,
                        lastNotifiedAtMs = nowMs
                    )
                }
                nextStrategy = nextStrategy.copy(
                    currentlyApproaching = false,
                    currentlyReached = true
                )
            }

            isApproachingNow && strategy.approachingEnabled -> {
                val occKey = "${strategy.id}_appr_$anchorKey"
                if (strategy.lastApproachedOccurrenceKey != occKey) {
                    val primaryProg = progresses.firstOrNull { it.isApproaching } ?: progresses.first()
                    val summary = "${primaryProg.matchedCount} of ${primaryProg.requiredCount} (${strategy.summaryDescription()})"
                    events.add(
                        buildEvent(
                            strategy = nextStrategy,
                            type = StrategyNotificationType.STRATEGY_APPROACHING,
                            triggerRec = latestRecord,
                            occurrenceKey = occKey,
                            progressSummary = summary,
                            nowMs = nowMs
                        )
                    )
                    nextStrategy = nextStrategy.copy(
                        lastApproachedOccurrenceKey = occKey,
                        lastNotifiedAtMs = nowMs
                    )
                }
                nextStrategy = nextStrategy.copy(
                    currentlyApproaching = true,
                    currentlyReached = false
                )
            }

            else -> {
                val wasActiveOrApproaching = strategy.currentlyApproaching || strategy.currentlyReached
                if (wasActiveOrApproaching && (anyBroken || !isReachedNow)) {
                    val occKey = "${strategy.id}_inv_${latestRecord.dedupKey}"
                    events.add(
                        buildEvent(
                            strategy = nextStrategy,
                            type = StrategyNotificationType.STRATEGY_INVALIDATED,
                            triggerRec = latestRecord,
                            occurrenceKey = occKey,
                            progressSummary = "Condition reset after ${formatMultiplier(latestRecord.multiplier)}",
                            nowMs = nowMs
                        )
                    )
                    nextStrategy = nextStrategy.copy(lastNotifiedAtMs = nowMs)
                }
                nextStrategy = nextStrategy.copy(
                    currentlyApproaching = false,
                    currentlyReached = false
                )
            }
        }

        return nextStrategy to events
    }

    private fun buildEvent(
        strategy: CustomPatternStrategy,
        type: StrategyNotificationType,
        triggerRec: MultiplierRecord,
        occurrenceKey: String,
        progressSummary: String,
        nowMs: Long
    ): PatternAlertEvent {
        val customNote = strategy.customMessage.trim().takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
        val (title, message) = when (type) {
            StrategyNotificationType.STRATEGY_APPROACHING -> {
                "Pattern Approaching: ${strategy.name}" to
                    "PATTERN APPROACHING: $progressSummary. Latest round: ${formatMultiplier(triggerRec.multiplier)}. Current status: Monitoring.$customNote"
            }
            StrategyNotificationType.STRATEGY_REACHED -> {
                "Pattern Detected: ${strategy.name}" to
                    "PATTERN DETECTED: $progressSummary. Latest round: ${formatMultiplier(triggerRec.multiplier)}. Open the game and review the current betting opportunity.$customNote"
            }
            StrategyNotificationType.STRATEGY_INVALIDATED -> {
                "Pattern Reset: ${strategy.name}" to
                    "PATTERN INVALIDATED: ${strategy.name} — $progressSummary. Waiting for next sequence."
            }
            StrategyNotificationType.HISTORY_UNAVAILABLE -> {
                "History Unavailable: ${strategy.name}" to
                    "HISTORY UNAVAILABLE: Game ribbon history could not be read; pattern monitoring is paused until history resumes."
            }
            StrategyNotificationType.STRATEGY_PAUSED -> {
                "Strategy Paused: ${strategy.name}" to
                    "STRATEGY PAUSED: ${strategy.name} monitoring paused."
            }
            else -> {
                "Predict Manager Update: ${strategy.name}" to
                    "${type.displayLabel}: $progressSummary"
            }
        }

        return PatternAlertEvent(
            strategy = strategy,
            notificationType = type,
            title = title,
            message = message,
            triggeringRecordKey = triggerRec.dedupKey,
            triggeringMultiplier = triggerRec.multiplier,
            occurrenceKey = occurrenceKey,
            timestampMs = nowMs
        )
    }

    /**
     * Evaluates Strategy A (Consecutive Lows), Strategy B (Big Multiplier Intervals),
     * Strategy C (Color Patterns), and Custom strategies against completed CrashRecord entries.
     */
    fun evaluateStrategiesOnCrashRecords(
        strategies: List<CustomPatternStrategy>,
        crashRecords: List<CrashRecord>,
        lowThreshold: Double = 2.00,
        bigThreshold: Double = 10.00,
        nowMs: Long = System.currentTimeMillis()
    ): CrashPatternEvaluationBatch {
        if (strategies.isEmpty() || crashRecords.isEmpty()) {
            return CrashPatternEvaluationBatch(updatedStrategies = strategies, events = emptyList(), newOccurrences = emptyList())
        }

        val updatedStrategies = mutableListOf<CustomPatternStrategy>()
        val alertEvents = mutableListOf<PatternAlertEvent>()
        val occurrences = mutableListOf<com.example.data.model.PatternOccurrenceRecord>()

        val chronological = crashRecords.sortedWith(compareBy({ it.crashTimestamp }, { it.id }))

        for (strategy in strategies) {
            if (!strategy.isEnabled) {
                updatedStrategies.add(strategy)
                continue
            }

            val gameScoped = if (strategy.targetGameId == "ALL") {
                val activePartitionGameId = chronological.last().gameId
                chronological.filter { it.gameId == activePartitionGameId }
            } else {
                chronological.filter { it.gameId == strategy.targetGameId }
            }
            if (gameScoped.isEmpty()) {
                updatedStrategies.add(strategy)
                continue
            }

            val latest = gameScoped.last()
            if (strategy.lastEvaluatedRecordKey == latest.dedupKey) {
                updatedStrategies.add(strategy)
                continue
            }

            if (strategy.multiplierRuleSubtype == com.example.data.model.MultiplierRuleSubtype.ABOVE_THEN_BELOW) {
                val aboveN = strategy.aboveConsecutiveCount.coerceAtLeast(1)
                val belowM = strategy.belowConsecutiveCount.coerceAtLeast(1)
                val totalNeeded = aboveN + belowM
                var nextStrat = strategy.copy(lastEvaluatedRecordKey = latest.dedupKey)

                val exactTailWindow = if (gameScoped.size >= totalNeeded) gameScoped.takeLast(totalNeeded) else null
                val exactReached = exactTailWindow != null && run {
                    val aboveSlice = exactTailWindow.take(aboveN)
                    val belowSlice = exactTailWindow.takeLast(belowM)
                    val aboveOk = aboveSlice.all { r ->
                        if (strategy.inclusiveBounds) r.multiplier >= strategy.aboveThresholdX else r.multiplier > strategy.aboveThresholdX
                    }
                    val belowOk = belowSlice.all { r ->
                        if (strategy.inclusiveBounds) r.multiplier <= strategy.belowThresholdY else r.multiplier < strategy.belowThresholdY
                    }
                    aboveOk && belowOk
                }

                if (exactReached && exactTailWindow != null) {
                    val anchor = exactTailWindow.first()
                    val occKey = "aboveBelow_${strategy.id}_${latest.gameId}_${anchor.dedupKey}"
                    if (strategy.lastTriggeredOccurrenceKey != occKey) {
                        val opAbove = if (strategy.inclusiveBounds) ">=" else ">"
                        val opBelow = if (strategy.inclusiveBounds) "<=" else "<"
                        val desc = "${aboveN}x $opAbove ${formatMultiplier(strategy.aboveThresholdX)} then ${belowM}x $opBelow ${formatMultiplier(strategy.belowThresholdY)}"
                        val hourStart = com.example.data.model.CalendarHourUtils.hourStartFor(latest.crashTimestamp)
                        occurrences.add(
                            com.example.data.model.PatternOccurrenceRecord(
                                occurrenceKey = occKey,
                                strategyId = strategy.id,
                                strategyName = strategy.name,
                                gameId = latest.gameId,
                                gameName = latest.gameName,
                                patternType = "ABOVE_THEN_BELOW",
                                triggerTimestampMs = latest.crashTimestamp,
                                hourStartTimestamp = hourStart,
                                detailsSummary = desc,
                                firstMatchedTimestampMs = anchor.crashTimestamp
                            )
                        )
                        alertEvents.add(
                            PatternAlertEvent(
                                strategy = nextStrat,
                                notificationType = StrategyNotificationType.STRATEGY_REACHED,
                                title = "${latest.gameName}: ${strategy.name}",
                                message = "PATTERN DETECTED: $desc (latest ${formatMultiplier(latest.multiplier)}).",
                                triggeringRecordKey = latest.dedupKey,
                                triggeringMultiplier = latest.multiplier,
                                occurrenceKey = occKey,
                                timestampMs = nowMs
                            )
                        )
                        nextStrat = nextStrat.copy(
                            lastTriggeredOccurrenceKey = occKey,
                            lastNotifiedAtMs = nowMs,
                            currentlyReached = true,
                            currentlyApproaching = false
                        )
                    }
                } else {
                    val approxNeeded = aboveN + (belowM - 1)
                    val isApprox = strategy.approachingEnabled && belowM >= 2 && gameScoped.size >= approxNeeded && run {
                        val sub = gameScoped.takeLast(approxNeeded)
                        val aOk = sub.take(aboveN).all { r ->
                            if (strategy.inclusiveBounds) r.multiplier >= strategy.aboveThresholdX else r.multiplier > strategy.aboveThresholdX
                        }
                        val bOk = sub.takeLast(belowM - 1).all { r ->
                            if (strategy.inclusiveBounds) r.multiplier <= strategy.belowThresholdY else r.multiplier < strategy.belowThresholdY
                        }
                        aOk && bOk
                    }
                    if (isApprox) {
                        val anchor = gameScoped[gameScoped.size - approxNeeded]
                        val occKey = "aboveBelow_appr_${strategy.id}_${latest.gameId}_${anchor.dedupKey}"
                        if (strategy.lastApproachedOccurrenceKey != occKey) {
                            alertEvents.add(
                                PatternAlertEvent(
                                    strategy = nextStrat,
                                    notificationType = StrategyNotificationType.STRATEGY_APPROACHING,
                                    title = "Approaching: ${strategy.name}",
                                    message = "PATTERN APPROACHING: $aboveN above ${formatMultiplier(strategy.aboveThresholdX)} followed by ${belowM - 1}/$belowM below ${formatMultiplier(strategy.belowThresholdY)}.",
                                    triggeringRecordKey = latest.dedupKey,
                                    triggeringMultiplier = latest.multiplier,
                                    occurrenceKey = occKey,
                                    timestampMs = nowMs
                                )
                            )
                            nextStrat = nextStrat.copy(lastApproachedOccurrenceKey = occKey, lastNotifiedAtMs = nowMs)
                        }
                        nextStrat = nextStrat.copy(currentlyApproaching = true, currentlyReached = false)
                    } else {
                        nextStrat = nextStrat.copy(currentlyApproaching = false, currentlyReached = false)
                    }
                }
                updatedStrategies.add(nextStrat)
                continue
            }

            when (strategy.strategyMode) {
                com.example.data.model.StrategyMode.STRATEGY_A_CONSECUTIVE_LOWS -> {
                    val cond = strategy.getConditions().firstOrNull()
                    val th = cond?.threshold ?: lowThreshold
                    val requiredCount = (cond?.consecutiveRounds ?: 14).coerceAtLeast(1)
                    val windowCutoff = nowMs - (strategy.analysisWindowMinutes.coerceAtLeast(5) * 60_000L)
                    val windowed = gameScoped.filter { it.crashTimestamp >= windowCutoff }

                    var streak = 0
                    var startRec: CrashRecord? = null
                    for (r in windowed) {
                        val matched = if (cond != null) {
                            cond.comparator.matches(r.multiplier, cond.threshold, cond.upperThreshold)
                        } else if (strategy.inclusiveBounds) {
                            r.multiplier <= th
                        } else {
                            r.multiplier < th
                        }
                        if (matched) {
                            if (streak == 0) startRec = r
                            streak++
                        } else {
                            streak = 0
                            startRec = null
                        }
                    }

                    val reached = streak >= requiredCount
                    val approaching = !reached && requiredCount >= 2 && streak == requiredCount - 1
                    var nextStrat = strategy.copy(lastEvaluatedRecordKey = latest.dedupKey)

                    if (reached) {
                        val occKey = "stratA_${strategy.id}_${latest.gameId}_${startRec?.dedupKey ?: latest.dedupKey}"
                        if (strategy.lastTriggeredOccurrenceKey != occKey) {
                            val hourStart = com.example.data.model.CalendarHourUtils.hourStartFor(latest.crashTimestamp)
                            occurrences.add(
                                com.example.data.model.PatternOccurrenceRecord(
                                    occurrenceKey = occKey,
                                    strategyId = strategy.id,
                                    strategyName = strategy.name,
                                    gameId = latest.gameId,
                                    gameName = latest.gameName,
                                    patternType = strategy.strategyMode.name,
                                    triggerTimestampMs = latest.crashTimestamp,
                                    hourStartTimestamp = hourStart,
                                    detailsSummary = "$streak consecutive rounds < ${String.format(Locale.US, "%.2fx", th)}",
                                    firstMatchedTimestampMs = startRec?.crashTimestamp ?: latest.crashTimestamp
                                )
                            )
                            alertEvents.add(
                                PatternAlertEvent(
                                    strategy = nextStrat,
                                    notificationType = StrategyNotificationType.STRATEGY_REACHED,
                                    title = "${latest.gameName}: ${strategy.name}",
                                    message = "Observed $streak consecutive rounds below ${String.format(Locale.US, "%.2fx", th)} (latest: ${formatMultiplier(latest.multiplier)}).",
                                    triggeringRecordKey = latest.dedupKey,
                                    triggeringMultiplier = latest.multiplier,
                                    occurrenceKey = occKey,
                                    timestampMs = nowMs
                                )
                            )
                            nextStrat = nextStrat.copy(lastTriggeredOccurrenceKey = occKey, lastNotifiedAtMs = nowMs)
                        }
                        nextStrat = nextStrat.copy(currentlyApproaching = false, currentlyReached = true)
                    } else if (approaching && strategy.approachingEnabled) {
                        val occKey = "stratA_appr_${strategy.id}_${latest.gameId}_${startRec?.dedupKey ?: latest.dedupKey}"
                        if (strategy.lastApproachedOccurrenceKey != occKey) {
                            alertEvents.add(
                                PatternAlertEvent(
                                    strategy = nextStrat,
                                    notificationType = StrategyNotificationType.STRATEGY_APPROACHING,
                                    title = "Approaching: ${strategy.name}",
                                    message = "$streak of $requiredCount consecutive rounds below ${String.format(Locale.US, "%.2fx", th)} in ${latest.gameName}.",
                                    triggeringRecordKey = latest.dedupKey,
                                    triggeringMultiplier = latest.multiplier,
                                    occurrenceKey = occKey,
                                    timestampMs = nowMs
                                )
                            )
                            nextStrat = nextStrat.copy(lastApproachedOccurrenceKey = occKey, lastNotifiedAtMs = nowMs)
                        }
                        nextStrat = nextStrat.copy(currentlyApproaching = true, currentlyReached = false)
                    } else {
                        nextStrat = nextStrat.copy(currentlyApproaching = false, currentlyReached = false)
                    }
                    updatedStrategies.add(nextStrat)
                }

                com.example.data.model.StrategyMode.STRATEGY_B_BIG_INTERVALS -> {
                    var nextStrat = strategy.copy(lastEvaluatedRecordKey = latest.dedupKey)
                    val bigTh = if (strategy.bigMultiplierThreshold > 1.0) strategy.bigMultiplierThreshold else bigThreshold
                    val bigs = gameScoped.filter { it.multiplier >= bigTh }

                    // Sub-mode 1: Post-Big Wait Interval (e.g., wait 2, 5, 10, 15, 30, 45, 55 min after Big multiplier >= threshold)
                    if (strategy.postBigWaitMinutes > 0 && bigs.isNotEmpty()) {
                        val lastBig = bigs.last()
                        val elapsedMs = (nowMs - lastBig.crashTimestamp).coerceAtLeast(0L)
                        val waitTargetMs = strategy.postBigWaitMinutes * 60_000L
                        val minOcc = strategy.requiredOccurrencesPerHour.coerceAtLeast(1)
                        val periodCutoff = nowMs - (strategy.analysisWindowMinutes.coerceAtLeast(60) * 60_000L)
                        val bigsInPeriod = bigs.count { it.crashTimestamp >= periodCutoff }

                        if (elapsedMs >= waitTargetMs && bigsInPeriod >= minOcc) {
                            val occKey = "timeWait_${strategy.id}_${lastBig.gameId}_${lastBig.dedupKey}_${strategy.postBigWaitMinutes}m"
                            if (strategy.lastTriggeredOccurrenceKey != occKey) {
                                val hourStart = com.example.data.model.CalendarHourUtils.hourStartFor(nowMs)
                                val elapsedMin = elapsedMs / 60_000.0
                                val summary = "${strategy.postBigWaitMinutes}m wait elapsed after ${formatMultiplier(lastBig.multiplier)} (${String.format(Locale.US, "%.1f", elapsedMin)}m ago; $bigsInPeriod in window)"
                                occurrences.add(
                                    com.example.data.model.PatternOccurrenceRecord(
                                        occurrenceKey = occKey,
                                        strategyId = strategy.id,
                                        strategyName = strategy.name,
                                        gameId = lastBig.gameId,
                                        gameName = lastBig.gameName,
                                        patternType = "TIME_INTERVAL_WAIT",
                                        triggerTimestampMs = nowMs,
                                        hourStartTimestamp = hourStart,
                                        detailsSummary = summary,
                                        firstMatchedTimestampMs = lastBig.crashTimestamp,
                                        intervalSincePrevMs = elapsedMs
                                    )
                                )
                                alertEvents.add(
                                    PatternAlertEvent(
                                        strategy = nextStrat,
                                        notificationType = StrategyNotificationType.STRATEGY_REACHED,
                                        title = "${lastBig.gameName}: ${strategy.name}",
                                        message = "TIME INTERVAL: $summary",
                                        triggeringRecordKey = lastBig.dedupKey,
                                        triggeringMultiplier = lastBig.multiplier,
                                        occurrenceKey = occKey,
                                        timestampMs = nowMs
                                    )
                                )
                                nextStrat = nextStrat.copy(lastTriggeredOccurrenceKey = occKey, lastNotifiedAtMs = nowMs, currentlyReached = true)
                            }
                            updatedStrategies.add(nextStrat)
                            continue
                        }
                    }

                    // Sub-mode 2: Consecutive Big gaps within min..max minutes
                    val cond = strategy.getConditions().firstOrNull()
                    val th = cond?.threshold ?: bigTh
                    val gapBigs = gameScoped.filter { it.multiplier >= th }
                    if (gapBigs.size >= 2 && latest.multiplier >= th) {
                        val prevBig = gapBigs[gapBigs.size - 2]
                        val currBig = gapBigs.last()
                        val gapMin = ((currBig.crashTimestamp - prevBig.crashTimestamp).coerceAtLeast(0L)) / 60_000.0
                        if (gapMin in strategy.minIntervalMinutes.toDouble()..strategy.maxIntervalMinutes.toDouble()) {
                            val occKey = "stratB_${strategy.id}_${latest.gameId}_${currBig.dedupKey}"
                            if (strategy.lastTriggeredOccurrenceKey != occKey) {
                                val hourStart = com.example.data.model.CalendarHourUtils.hourStartFor(currBig.crashTimestamp)
                                occurrences.add(
                                    com.example.data.model.PatternOccurrenceRecord(
                                        occurrenceKey = occKey,
                                        strategyId = strategy.id,
                                        strategyName = strategy.name,
                                        gameId = currBig.gameId,
                                        gameName = currBig.gameName,
                                        patternType = strategy.strategyMode.name,
                                        triggerTimestampMs = currBig.crashTimestamp,
                                        hourStartTimestamp = hourStart,
                                        detailsSummary = "${formatMultiplier(currBig.multiplier)} occurred ${String.format(Locale.US, "%.1f", gapMin)}m after ${formatMultiplier(prevBig.multiplier)}",
                                        firstMatchedTimestampMs = prevBig.crashTimestamp,
                                        intervalSincePrevMs = currBig.crashTimestamp - prevBig.crashTimestamp
                                    )
                                )
                                alertEvents.add(
                                    PatternAlertEvent(
                                        strategy = nextStrat,
                                        notificationType = StrategyNotificationType.STRATEGY_REACHED,
                                        title = "${currBig.gameName}: ${strategy.name}",
                                        message = "Big multiplier ${formatMultiplier(currBig.multiplier)} occurred ${String.format(Locale.US, "%.1f", gapMin)}m after ${formatMultiplier(prevBig.multiplier)} (within ${strategy.minIntervalMinutes}–${strategy.maxIntervalMinutes}m).",
                                        triggeringRecordKey = currBig.dedupKey,
                                        triggeringMultiplier = currBig.multiplier,
                                        occurrenceKey = occKey,
                                        timestampMs = nowMs
                                    )
                                )
                                nextStrat = nextStrat.copy(lastTriggeredOccurrenceKey = occKey, lastNotifiedAtMs = nowMs, currentlyReached = true)
                            }
                        } else {
                            nextStrat = nextStrat.copy(currentlyReached = false, currentlyApproaching = false)
                        }
                    } else {
                        nextStrat = nextStrat.copy(currentlyReached = false, currentlyApproaching = false)
                    }
                    updatedStrategies.add(nextStrat)
                }

                com.example.data.model.StrategyMode.STRATEGY_C_COLOR_PATTERNS -> {
                    val targetColors = strategy.getColorSequence()
                    val hexFilters = strategy.getCustomHexSequence()
                    var nextStrat = strategy.copy(lastEvaluatedRecordKey = latest.dedupKey)
                    val dir = strategy.colorMatchDirection

                    val horizontalMatched: List<CrashRecord>? =
                        if ((dir == com.example.data.model.ColorMatchDirection.HORIZONTAL ||
                                dir == com.example.data.model.ColorMatchDirection.BOTH) &&
                            targetColors.isNotEmpty() && gameScoped.size >= targetColors.size
                        ) {
                            val slice = gameScoped.takeLast(targetColors.size)
                            if (matchesColorAndHexSequence(slice, targetColors, hexFilters)) slice else null
                        } else null

                    val cols = strategy.gridColumnsForVertical.coerceIn(2, 12)
                    val verticalMatched: List<CrashRecord>? =
                        if ((dir == com.example.data.model.ColorMatchDirection.VERTICAL ||
                                dir == com.example.data.model.ColorMatchDirection.BOTH) &&
                            targetColors.isNotEmpty() &&
                            gameScoped.size >= ((targetColors.size - 1) * cols + 1)
                        ) {
                            // Top-to-bottom column alignment in a `cols`-column crash grid
                            val verticalSlice = (0 until targetColors.size).map { idx ->
                                val offsetFromNewest = (targetColors.size - 1 - idx) * cols
                                gameScoped[gameScoped.size - 1 - offsetFromNewest]
                            }
                            if (matchesColorAndHexSequence(verticalSlice, targetColors, hexFilters)) verticalSlice else null
                        } else null

                    val matchedSlice = horizontalMatched ?: verticalMatched
                    val matchModeLabel = when {
                        horizontalMatched != null && verticalMatched != null -> "Horizontal & Vertical"
                        verticalMatched != null -> "Vertical (${cols} cols)"
                        else -> "Horizontal"
                    }

                    if (matchedSlice != null) {
                        val anchor = matchedSlice.first()
                        val occKey = "stratC_${strategy.id}_${latest.gameId}_${anchor.dedupKey}_${latest.dedupKey}"
                        if (strategy.lastTriggeredOccurrenceKey != occKey) {
                            val hourStart = com.example.data.model.CalendarHourUtils.hourStartFor(latest.crashTimestamp)
                            val seqDesc = targetColors.joinToString(" -> ") { it.displayName }
                            val hexNote = matchedSlice.joinToString(", ") { it.detectedColorHex }
                            occurrences.add(
                                com.example.data.model.PatternOccurrenceRecord(
                                    occurrenceKey = occKey,
                                    strategyId = strategy.id,
                                    strategyName = strategy.name,
                                    gameId = latest.gameId,
                                    gameName = latest.gameName,
                                    patternType = "${strategy.strategyMode.name}_$matchModeLabel",
                                    triggerTimestampMs = latest.crashTimestamp,
                                    hourStartTimestamp = hourStart,
                                    detailsSummary = "Observed $matchModeLabel color sequence: $seqDesc ($hexNote)",
                                    firstMatchedTimestampMs = anchor.crashTimestamp
                                )
                            )
                            alertEvents.add(
                                PatternAlertEvent(
                                    strategy = nextStrat,
                                    notificationType = StrategyNotificationType.STRATEGY_REACHED,
                                    title = "${latest.gameName}: ${strategy.name}",
                                    message = "Observed $matchModeLabel color sequence $seqDesc (latest ${formatMultiplier(latest.multiplier)} · ${latest.detectedColorHex}).",
                                    triggeringRecordKey = latest.dedupKey,
                                    triggeringMultiplier = latest.multiplier,
                                    occurrenceKey = occKey,
                                    timestampMs = nowMs
                                )
                            )
                            nextStrat = nextStrat.copy(lastTriggeredOccurrenceKey = occKey, lastNotifiedAtMs = nowMs, currentlyReached = true)
                        }
                    } else {
                        nextStrat = nextStrat.copy(currentlyReached = false, currentlyApproaching = false)
                    }
                    updatedStrategies.add(nextStrat)
                }

                com.example.data.model.StrategyMode.CUSTOM_CONDITION -> {
                    run {
                        // Convert CrashRecords to MultiplierRecords so general custom threshold/sequence/between conditions evaluate directly
                        val multEquiv = gameScoped.map { cr ->
                            MultiplierRecord(
                                id = cr.id,
                                gameId = cr.gameId,
                                sessionId = cr.sessionId,
                                roundId = cr.roundIdentifier,
                                multiplier = cr.multiplier,
                                observationTimestampMs = cr.crashTimestamp,
                                crashTimestamp = cr.crashTimestamp,
                                platform = cr.gameName,
                                dedupKey = cr.dedupKey
                            )
                        }
                        val subBatch = evaluateAllStrategies(listOf(strategy), multEquiv, nowMs = nowMs)
                        val upd = subBatch.updatedStrategies.firstOrNull() ?: strategy
                        subBatch.events.filter { it.notificationType == StrategyNotificationType.STRATEGY_REACHED }.forEach { ev ->
                            val hourStart = com.example.data.model.CalendarHourUtils.hourStartFor(ev.timestampMs)
                            occurrences.add(
                                com.example.data.model.PatternOccurrenceRecord(
                                    occurrenceKey = ev.occurrenceKey,
                                    strategyId = strategy.id,
                                    strategyName = strategy.name,
                                    gameId = latest.gameId,
                                    gameName = latest.gameName,
                                    patternType = strategy.strategyMode.name,
                                    triggerTimestampMs = latest.crashTimestamp,
                                    hourStartTimestamp = hourStart,
                                    detailsSummary = ev.message,
                                    firstMatchedTimestampMs = latest.crashTimestamp
                                )
                            )
                        }
                        alertEvents.addAll(subBatch.events)
                        updatedStrategies.add(upd)
                    }
                }
            }
        }

        return CrashPatternEvaluationBatch(
            updatedStrategies = updatedStrategies,
            events = alertEvents,
            newOccurrences = occurrences
        )
    }

    /**
     * Evaluates time-based strategies even between crash rounds (post-Big elapsed countdowns and HH:mm specific alert times).
     */
    fun evaluateTimeSchedulesTick(
        strategies: List<CustomPatternStrategy>,
        crashRecords: List<CrashRecord>,
        bigThreshold: Double = 10.00,
        nowMs: Long = System.currentTimeMillis()
    ): CrashPatternEvaluationBatch {
        val timeStrategies = strategies.filter {
            it.isEnabled && (
                it.strategyGroup == com.example.data.model.StrategyGroup.TIME_INTERVAL ||
                    it.strategyMode == com.example.data.model.StrategyMode.STRATEGY_B_BIG_INTERVALS
                )
        }
        if (timeStrategies.isEmpty()) {
            return CrashPatternEvaluationBatch(strategies, emptyList(), emptyList())
        }

        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
        val currentHm = String.format(
            Locale.US,
            "%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE)
        )
        val dayKey = String.format(
            Locale.US,
            "%04d%02d%02d_%02d%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE)
        )

        val updatedMap = strategies.associateBy { it.id }.toMutableMap()
        val events = mutableListOf<PatternAlertEvent>()
        val occs = mutableListOf<com.example.data.model.PatternOccurrenceRecord>()

        for (strategy in timeStrategies) {
            var nextStrat = strategy
            val scoped = if (strategy.targetGameId == "ALL") crashRecords else crashRecords.filter { it.gameId == strategy.targetGameId }
            val bigTh = if (strategy.bigMultiplierThreshold > 1.0) strategy.bigMultiplierThreshold else bigThreshold
            val bigs = scoped.filter { it.multiplier >= bigTh }.sortedBy { it.crashTimestamp }

            // Check Post-Big wait timer
            if (strategy.postBigWaitMinutes > 0 && bigs.isNotEmpty()) {
                val lastBig = bigs.last()
                val elapsed = (nowMs - lastBig.crashTimestamp).coerceAtLeast(0L)
                if (elapsed >= strategy.postBigWaitMinutes * 60_000L) {
                    val occKey = "timeWait_${strategy.id}_${lastBig.gameId}_${lastBig.dedupKey}_${strategy.postBigWaitMinutes}m"
                    if (strategy.lastTriggeredOccurrenceKey != occKey) {
                        val hourStart = com.example.data.model.CalendarHourUtils.hourStartFor(nowMs)
                        val summary = "${strategy.postBigWaitMinutes}m wait complete after ${formatMultiplier(lastBig.multiplier)} in ${lastBig.gameName}"
                        occs.add(
                            com.example.data.model.PatternOccurrenceRecord(
                                occurrenceKey = occKey,
                                strategyId = strategy.id,
                                strategyName = strategy.name,
                                gameId = lastBig.gameId,
                                gameName = lastBig.gameName,
                                patternType = "TIME_INTERVAL_WAIT",
                                triggerTimestampMs = nowMs,
                                hourStartTimestamp = hourStart,
                                detailsSummary = summary,
                                firstMatchedTimestampMs = lastBig.crashTimestamp,
                                intervalSincePrevMs = elapsed
                            )
                        )
                        events.add(
                            PatternAlertEvent(
                                strategy = nextStrat,
                                notificationType = StrategyNotificationType.STRATEGY_REACHED,
                                title = "Time Alert: ${strategy.name}",
                                message = summary,
                                triggeringRecordKey = lastBig.dedupKey,
                                triggeringMultiplier = lastBig.multiplier,
                                occurrenceKey = occKey,
                                timestampMs = nowMs
                            )
                        )
                        nextStrat = nextStrat.copy(lastTriggeredOccurrenceKey = occKey, lastNotifiedAtMs = nowMs, currentlyReached = true)
                    }
                }
            }

            // Check specific HH:mm scheduled alerts
            val scheduledTimes = strategy.getSpecificAlertTimes()
                .map { (h, m) -> String.format(Locale.US, "%02d:%02d", h, m) }
            if (scheduledTimes.contains(currentHm)) {
                val occKey = "specTime_${strategy.id}_$dayKey"
                if (strategy.lastTriggeredOccurrenceKey != occKey) {
                    val hourStart = com.example.data.model.CalendarHourUtils.hourStartFor(nowMs)
                    val gId = strategy.targetGameId.takeIf { it != "ALL" } ?: scoped.lastOrNull()?.gameId ?: "unknown"
                    val gName = scoped.lastOrNull()?.gameName ?: "All Games"
                    occs.add(
                        com.example.data.model.PatternOccurrenceRecord(
                            occurrenceKey = occKey,
                            strategyId = strategy.id,
                            strategyName = strategy.name,
                            gameId = gId,
                            gameName = gName,
                            patternType = "TIME_SCHEDULE_HM",
                            triggerTimestampMs = nowMs,
                            hourStartTimestamp = hourStart,
                            detailsSummary = "Scheduled time alert at $currentHm",
                            firstMatchedTimestampMs = nowMs
                        )
                    )
                    events.add(
                        PatternAlertEvent(
                            strategy = nextStrat,
                            notificationType = StrategyNotificationType.STRATEGY_REACHED,
                            title = "Scheduled Time Alert: ${strategy.name}",
                            message = "Configured alert time $currentHm reached for ${strategy.name}.",
                            occurrenceKey = occKey,
                            timestampMs = nowMs
                        )
                    )
                    nextStrat = nextStrat.copy(lastTriggeredOccurrenceKey = occKey, lastNotifiedAtMs = nowMs)
                }
            }

            updatedMap[strategy.id] = nextStrat
        }

        return CrashPatternEvaluationBatch(
            updatedStrategies = strategies.map { updatedMap[it.id] ?: it },
            events = events,
            newOccurrences = occs
        )
    }

    private fun matchesColorAndHexSequence(
        slice: List<CrashRecord>,
        targetColors: List<com.example.data.model.CrashColorCategory>,
        hexFilters: List<String>
    ): Boolean {
        if (slice.size != targetColors.size) return false
        return slice.indices.all { idx ->
            val rec = slice[idx]
            val catOk = rec.colorCategory == targetColors[idx]
            val expectedHex = hexFilters.getOrNull(idx)?.trim().orEmpty()
            val hexOk = expectedHex.isBlank() || expectedHex.equals("ANY", ignoreCase = true) ||
                rec.detectedColorHex.equals(expectedHex, ignoreCase = true)
            catOk && hexOk
        }
    }
}

data class CrashPatternEvaluationBatch(
    val updatedStrategies: List<CustomPatternStrategy>,
    val events: List<PatternAlertEvent>,
    val newOccurrences: List<com.example.data.model.PatternOccurrenceRecord>
)
