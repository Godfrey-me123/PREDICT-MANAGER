package com.example.data.engine

import com.example.data.model.CollectionSource
import com.example.data.model.HistoryCollectionStatus
import com.example.data.model.MultiplierRecord
import com.example.data.model.TimestampSource
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.min

import android.graphics.Rect
import com.example.data.model.CrashColorCategory
import com.example.service.PersistedRibbonBaselineItem

data class ExtractedHistoryItem(
    val multiplier: Double,
    val roundId: String? = null,
    val gameTimestampMs: Long? = null,
    val orderIndex: Int = 0,
    val source: CollectionSource = CollectionSource.VISIBLE_RIBBON,
    val bounds: Rect? = null,
    val detectedColorHex: String? = null,
    val colorCategory: CrashColorCategory? = null,
    val isPixelColorVerified: Boolean = false,
    val displayText: String = ""
)

data class AuthoritativeAlignmentResult(
    val isBaselineCaptureOnly: Boolean,
    val newCompletedItems: List<ExtractedHistoryItem>,
    val updatedBaseline: List<PersistedRibbonBaselineItem>?,
    val rejectedReason: String? = null,
    val isUnchanged: Boolean = false,
    val isScrollOrExpandRejected: Boolean = false
)

data class HistoryCollectionMetadata(
    val status: HistoryCollectionStatus = HistoryCollectionStatus.HISTORY_NOT_STARTED,
    val statusReason: String = "History collection has not started yet.",
    val windowMinutes: Int = 30,
    val totalCollectedInWindow: Int = 0,
    val totalVisibleInRibbon: Int = 0,
    val latestMultiplier: Double? = null,
    val latestRecordTimestampMs: Long? = null,
    val latestTimestampSource: TimestampSource? = null,
    val earliestTimestampMs: Long? = null,
    val lastCollectionAttemptMs: Long = 0L,
    val hasGapsOrPartial: Boolean = false,
    val hasExpandableHistoryControl: Boolean = false,
    val hasScrollableHistoryContainer: Boolean = false,
    val isHistoryPanelExpanded: Boolean = false
)

data class RibbonSyncResult(
    val newRecordsToPersist: List<MultiplierRecord>,
    val status: HistoryCollectionStatus,
    val reason: String,
    val isRepeatedSnapshot: Boolean
)

/**
 * Pure, deterministic ribbon & history collector/deduplicator for Section 14 and authoritative
 * live round capture.
 * - Multipliers may naturally repeat; never deduplicates by multiplier value alone.
 * - Captures initial ribbon as baseline only (zero historical backfill).
 * - Aligns visible ribbon snapshots via suffix-prefix overlap to capture newly finished rounds
 *   while rejecting unchanged renders, scroll jumps, and history panel expansions.
 */
class RibbonHistoryCollector {

    companion object {
        const val ROLLING_WINDOW_MINUTES = 30
        const val ROLLING_WINDOW_MS = ROLLING_WINDOW_MINUTES * 60_000L

        private val TIME_HMS_PATTERN: Pattern = Pattern.compile("\\b(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\b")
        private val ROUND_ID_PATTERN: Pattern = Pattern.compile("(?:round|#)\\s*[:#]?\\s*([a-z0-9_-]{4,24})", Pattern.CASE_INSENSITIVE)

        fun parseGameTimestampFromText(text: String?, nowMs: Long = System.currentTimeMillis()): Long? {
            if (text.isNullOrBlank()) return null
            val matcher = TIME_HMS_PATTERN.matcher(text)
            if (!matcher.find()) return null
            val hour = matcher.group(1)?.toIntOrNull() ?: return null
            val minute = matcher.group(2)?.toIntOrNull() ?: return null
            val second = matcher.group(3)?.toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null

            val cal = Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, second)
                set(Calendar.MILLISECOND, 0)
            }
            // Handle midnight rollover if parsed time is ahead of current time by > 5 minutes
            if (cal.timeInMillis > nowMs + 5 * 60_000L) {
                cal.add(Calendar.DAY_OF_YEAR, -1)
            }
            return cal.timeInMillis
        }

        fun parseRoundIdFromText(text: String?): String? {
            if (text.isNullOrBlank()) return null
            val matcher = ROUND_ID_PATTERN.matcher(text)
            return if (matcher.find()) matcher.group(1) else null
        }

        fun formatMultKey(multiplier: Double): String = String.format(Locale.US, "%.2f", multiplier)

        fun toBaselineEntries(items: List<ExtractedHistoryItem>): List<PersistedRibbonBaselineItem> {
            return items.mapIndexed { idx, item ->
                val rounded = kotlin.math.round(item.multiplier * 100.0) / 100.0
                val label = item.displayText.ifBlank { "${formatMultKey(rounded)}x" }
                PersistedRibbonBaselineItem(
                    multiplier = rounded,
                    displayText = label,
                    roundId = item.roundId?.takeIf { it.isNotBlank() },
                    gameTimestampMs = item.gameTimestampMs,
                    orderIndex = idx,
                    left = item.bounds?.left ?: 0,
                    top = item.bounds?.top ?: 0,
                    right = item.bounds?.right ?: 0,
                    bottom = item.bounds?.bottom ?: 0
                )
            }
        }

        private fun sameNormalizedEntry(a: PersistedRibbonBaselineItem, b: PersistedRibbonBaselineItem): Boolean {
            if (abs(a.multiplier - b.multiplier) >= 0.009) return false
            if (!a.roundId.isNullOrBlank() && !b.roundId.isNullOrBlank() && a.roundId != b.roundId) return false
            if (a.gameTimestampMs != null && b.gameTimestampMs != null && abs(a.gameTimestampMs - b.gameTimestampMs) > 1000L) {
                return false
            }
            return true
        }

        /**
         * Authoritative live ribbon sequence alignment:
         * - First observation captures BASELINE ONLY (0 new items).
         * - Expanding history / loading older tail items / reverse-scrolling / unchanged frames emit 0 items.
         * - Handles consecutive equal multipliers and finite ribbons dropping oldest right-edge items.
         */
        fun alignAuthoritativeLiveSnapshot(
            previousBaseline: List<PersistedRibbonBaselineItem>?,
            currentItems: List<ExtractedHistoryItem>,
            previousExpanded: Boolean = false,
            currentExpanded: Boolean = false,
            sawRoundCompletionTransition: Boolean = false
        ): AuthoritativeAlignmentResult {
            val validItems = currentItems.filter { it.multiplier.isFinite() && it.multiplier >= 1.0 }
            if (validItems.isEmpty()) {
                return AuthoritativeAlignmentResult(
                    isBaselineCaptureOnly = false,
                    newCompletedItems = emptyList(),
                    updatedBaseline = previousBaseline,
                    rejectedReason = "No readable history ribbon items in snapshot",
                    isUnchanged = false,
                    isScrollOrExpandRejected = false
                )
            }

            val currentNormalized = toBaselineEntries(validItems)
            if (previousBaseline.isNullOrEmpty()) {
                return AuthoritativeAlignmentResult(
                    isBaselineCaptureOnly = true,
                    newCompletedItems = emptyList(),
                    updatedBaseline = currentNormalized,
                    rejectedReason = "Initial baseline captured (${currentNormalized.size} visible items; 0 imported)",
                    isUnchanged = false,
                    isScrollOrExpandRejected = false
                )
            }

            // Check if all items appear identical to previous (same head or unchanged)
            val allEqualPrevious = currentNormalized.size == previousBaseline.size &&
                currentNormalized.indices.all { sameNormalizedEntry(currentNormalized[it], previousBaseline[it]) }
            if (allEqualPrevious) {
                val uniformRibbon = currentNormalized.all { sameNormalizedEntry(it, currentNormalized.first()) }
                val headRoundChanged = !currentNormalized.first().roundId.isNullOrBlank() &&
                    !previousBaseline.first().roundId.isNullOrBlank() &&
                    currentNormalized.first().roundId != previousBaseline.first().roundId
                if (uniformRibbon && (sawRoundCompletionTransition || headRoundChanged)) {
                    return AuthoritativeAlignmentResult(
                        isBaselineCaptureOnly = false,
                        newCompletedItems = listOf(validItems.first()),
                        updatedBaseline = currentNormalized,
                        rejectedReason = null,
                        isUnchanged = false,
                        isScrollOrExpandRejected = false
                    )
                }
                return AuthoritativeAlignmentResult(
                    isBaselineCaptureOnly = false,
                    newCompletedItems = emptyList(),
                    updatedBaseline = previousBaseline,
                    rejectedReason = "Ribbon unchanged",
                    isUnchanged = true,
                    isScrollOrExpandRejected = false
                )
            }

            // Reject pure tail extension/truncation at shift == 0 (e.g. expanded history panel showing older items)
            val minLen = min(previousBaseline.size, currentNormalized.size)
            val prefixMatchCount = (0 until minLen).all { sameNormalizedEntry(currentNormalized[it], previousBaseline[it]) }
            if (minLen >= 2 && prefixMatchCount) {
                val isExpansion = currentExpanded && !previousExpanded || currentNormalized.size > previousBaseline.size
                return AuthoritativeAlignmentResult(
                    isBaselineCaptureOnly = false,
                    newCompletedItems = emptyList(),
                    updatedBaseline = if (isExpansion) previousBaseline else currentNormalized,
                    rejectedReason = if (isExpansion) "History panel expanded/tail-extended — 0 new crashes" else "Ribbon head unchanged",
                    isUnchanged = !isExpansion,
                    isScrollOrExpandRejected = isExpansion
                )
            }

            // Reject panel-open layout jumps when no genuine crash lifecycle transition occurred
            if (currentExpanded && !previousExpanded && !sawRoundCompletionTransition) {
                return AuthoritativeAlignmentResult(
                    isBaselineCaptureOnly = false,
                    newCompletedItems = emptyList(),
                    updatedBaseline = previousBaseline,
                    rejectedReason = "History panel opened — ignored expansion shift",
                    isUnchanged = false,
                    isScrollOrExpandRejected = true
                )
            }

            // Detect valid forward shift: current.drop(shift) overlaps prefix of previousBaseline
            val maxPossibleShift = min(5, currentNormalized.size - 1)
            for (shift in 1..maxPossibleShift) {
                val remainingCurrent = currentNormalized.size - shift
                val maxOverlap = min(remainingCurrent, previousBaseline.size)
                val requiredOverlap = when {
                    previousBaseline.size >= 3 && currentNormalized.size >= 3 -> min(maxOverlap, 2)
                    else -> 1
                }
                if (maxOverlap >= requiredOverlap && requiredOverlap >= 1) {
                    val matches = (0 until maxOverlap).all { idx ->
                        sameNormalizedEntry(currentNormalized[shift + idx], previousBaseline[idx])
                    }
                    if (matches) {
                        return AuthoritativeAlignmentResult(
                            isBaselineCaptureOnly = false,
                            newCompletedItems = validItems.take(shift),
                            updatedBaseline = currentNormalized,
                            rejectedReason = null,
                            isUnchanged = false,
                            isScrollOrExpandRejected = false
                        )
                    }
                }
            }

            // Detect reverse shift (user scrolled older rounds into view so head dropped without new crash)
            for (k in 1 until previousBaseline.size) {
                val remainingPrev = previousBaseline.size - k
                val maxOverlap = min(remainingPrev, currentNormalized.size)
                val requiredOverlap = min(maxOverlap, 2)
                if (maxOverlap >= requiredOverlap && requiredOverlap >= 2) {
                    val reverseMatches = (0 until maxOverlap).all { idx ->
                        sameNormalizedEntry(currentNormalized[idx], previousBaseline[k + idx])
                    }
                    if (reverseMatches) {
                        return AuthoritativeAlignmentResult(
                            isBaselineCaptureOnly = false,
                            newCompletedItems = emptyList(),
                            updatedBaseline = previousBaseline,
                            rejectedReason = "Detected reverse ribbon scroll — ignored without inserting",
                            isUnchanged = false,
                            isScrollOrExpandRejected = true
                        )
                    }
                }
            }

            // Disjoint jump (navigation away and back / complete panel reload): re-anchor baseline cleanly, 0 inserts
            return AuthoritativeAlignmentResult(
                isBaselineCaptureOnly = true,
                newCompletedItems = emptyList(),
                updatedBaseline = currentNormalized,
                rejectedReason = "Ribbon jumped cleanly without overlap — baseline re-anchored (0 imported)",
                isUnchanged = false,
                isScrollOrExpandRejected = false
            )
        }

        /**
         * Aligns `current` (newest-first) against `previous` (newest-first) to return how many
         * new items appear at the head of `current`.
         * Returns null if there is no reliable overlap (disjoint refresh / first scan).
         */
        fun findShiftPrefixCount(previous: List<Double>, current: List<Double>): Int? {
            if (previous.isEmpty() || current.isEmpty()) return null
            if (current.size == previous.size &&
                current.indices.all { abs(current[it] - previous[it]) < 0.009 }
            ) {
                return 0
            }
            // Check prefix match at shift 0 when one list is just a shorter/longer snapshot of the same head
            val minLen = min(previous.size, current.size)
            if (minLen >= 2 && (0 until minLen).all { abs(current[it] - previous[it]) < 0.009 }) {
                return 0
            }

            for (shift in 1 until current.size) {
                val remainingCurrent = current.size - shift
                val maxOverlap = min(remainingCurrent, previous.size)
                val requiredOverlap = min(maxOverlap, if (previous.size >= 3 && current.size >= 3) 2 else 1)
                if (maxOverlap >= requiredOverlap && requiredOverlap >= 1) {
                    val matches = (0 until maxOverlap).all { idx ->
                        abs(current[shift + idx] - previous[idx]) < 0.009
                    }
                    if (matches) {
                        return shift
                    }
                }
            }
            return null
        }
    }

    private var lastVisibleRibbon: List<Double> = emptyList()
    private var lastPlatform: String = ""
    private var monotonicCounter: Long = 0L

    fun reset() {
        lastVisibleRibbon = emptyList()
        lastPlatform = ""
    }

    /**
     * Converts a newly observed visible ribbon (left-to-right = newest-to-oldest) into
     * deduplicated [MultiplierRecord] entries while strictly preserving repeated multipliers
     * across distinct rounds and never claiming HISTORY_COMPLETE for partial visible slices.
     */
    fun processVisibleRibbonSnapshot(
        items: List<ExtractedHistoryItem>,
        platformName: String,
        gameId: String = "unknown",
        isPanelExpanded: Boolean = false,
        hasExpandableControl: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): RibbonSyncResult {
        val validItems = items.filter { it.multiplier.isFinite() && it.multiplier >= 1.0 }
        if (validItems.isEmpty()) {
            return RibbonSyncResult(
                newRecordsToPersist = emptyList(),
                status = HistoryCollectionStatus.HISTORY_UNAVAILABLE,
                reason = "No visible game ribbon multipliers detected.",
                isRepeatedSnapshot = false
            )
        }

        val currentValues = validItems.map { it.multiplier }
        if (platformName != lastPlatform && lastPlatform.isNotBlank()) {
            lastVisibleRibbon = emptyList()
        }
        lastPlatform = platformName

        val shift = findShiftPrefixCount(lastVisibleRibbon, currentValues)
        if (shift == 0 && !isPanelExpanded) {
            return RibbonSyncResult(
                newRecordsToPersist = emptyList(),
                status = if (isPanelExpanded) HistoryCollectionStatus.COLLECTING_VISIBLE_HISTORY else HistoryCollectionStatus.HISTORY_PARTIAL,
                reason = "Visible ribbon unchanged (repeated snapshot skipped).",
                isRepeatedSnapshot = true
            )
        }

        val cleanPlatform = platformName.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "_")
        val newRecords = mutableListOf<MultiplierRecord>()

        if (shift != null && shift > 0) {
            // Only the newly shifted items at the front are genuinely new rounds
            val newHeadItems = validItems.take(shift)
            for (idx in newHeadItems.indices) {
                val item = newHeadItems[idx]
                // Reverse relative order offset so newest (idx=0) has highest timestamp
                val obsTs = nowMs - (idx * 50L)
                val tsSource = if (item.gameTimestampMs != null) {
                    TimestampSource.GAME_PROVIDED
                } else {
                    TimestampSource.LOCAL_OBSERVATION
                }
                val effectiveTs = item.gameTimestampMs ?: obsTs
                val batchSeq = ++monotonicCounter
                val key = when {
                    !item.roundId.isNullOrBlank() -> "${gameId}_${cleanPlatform}_rid_${item.roundId}"
                    item.gameTimestampMs != null -> "${gameId}_${cleanPlatform}_gt_${item.gameTimestampMs}_${formatMultKey(item.multiplier)}"
                    else -> "${gameId}_${cleanPlatform}_seq_${nowMs}_${batchSeq}_${idx}_${formatMultKey(item.multiplier)}"
                }
                newRecords.add(
                    MultiplierRecord(
                        gameId = gameId,
                        roundId = item.roundId,
                        multiplier = item.multiplier,
                        gameTimestampMs = item.gameTimestampMs,
                        observationTimestampMs = obsTs,
                        crashTimestamp = effectiveTs,
                        platform = platformName,
                        timestampSource = tsSource,
                        collectionSource = if (isPanelExpanded) CollectionSource.HISTORY_IMPORT else item.source,
                        collectionStatus = if (isPanelExpanded) {
                            HistoryCollectionStatus.COLLECTING_VISIBLE_HISTORY
                        } else {
                            HistoryCollectionStatus.HISTORY_PARTIAL
                        },
                        dedupKey = key,
                        sequenceIndex = idx,
                        hasGapBefore = false
                    )
                )
            }
        } else {
            // First scan or disjoint refresh / expanded panel items: persist all visible entries cleanly
            val hadPrevious = lastVisibleRibbon.isNotEmpty()
            for (idx in validItems.indices) {
                val item = validItems[idx]
                val obsTs = nowMs - (idx * 100L)
                val tsSource = if (item.gameTimestampMs != null) {
                    TimestampSource.GAME_PROVIDED
                } else {
                    TimestampSource.LOCAL_OBSERVATION
                }
                val effectiveTs = item.gameTimestampMs ?: obsTs
                val batchSeq = ++monotonicCounter
                val key = when {
                    !item.roundId.isNullOrBlank() -> "${gameId}_${cleanPlatform}_rid_${item.roundId}"
                    item.gameTimestampMs != null -> "${gameId}_${cleanPlatform}_gt_${item.gameTimestampMs}_${formatMultKey(item.multiplier)}"
                    else -> "${gameId}_${cleanPlatform}_gap_${nowMs}_${batchSeq}_${idx}_${formatMultKey(item.multiplier)}"
                }
                val isGapBoundary = hadPrevious && idx == validItems.lastIndex
                newRecords.add(
                    MultiplierRecord(
                        gameId = gameId,
                        roundId = item.roundId,
                        multiplier = item.multiplier,
                        gameTimestampMs = item.gameTimestampMs,
                        observationTimestampMs = obsTs,
                        crashTimestamp = effectiveTs,
                        platform = platformName,
                        timestampSource = tsSource,
                        collectionSource = if (isPanelExpanded) CollectionSource.HISTORY_IMPORT else item.source,
                        collectionStatus = if (isPanelExpanded) {
                            HistoryCollectionStatus.COLLECTING_VISIBLE_HISTORY
                        } else {
                            HistoryCollectionStatus.HISTORY_PARTIAL
                        },
                        dedupKey = key,
                        sequenceIndex = idx,
                        hasGapBefore = isGapBoundary
                    )
                )
            }
        }

        lastVisibleRibbon = currentValues
        val status = if (isPanelExpanded) {
            HistoryCollectionStatus.COLLECTING_VISIBLE_HISTORY
        } else {
            HistoryCollectionStatus.HISTORY_PARTIAL
        }
        val reason = when {
            isPanelExpanded -> "Collecting from expanded history panel (${validItems.size} visible)."
            hasExpandableControl -> "Collecting visible ribbon (${validItems.size} visible) — expand history panel for older rounds."
            else -> "Collected visible ribbon (${validItems.size} visible); full history panel not expanded."
        }
        return RibbonSyncResult(
            newRecordsToPersist = newRecords,
            status = status,
            reason = reason,
            isRepeatedSnapshot = false
        )
    }

    /**
     * Processes records read from an expanded/scrolled history panel.
     * Deduplicates using game timestamps or round IDs when available, or position/batch fallback.
     */
    fun processExpandedHistoryBatch(
        items: List<ExtractedHistoryItem>,
        platformName: String,
        gameId: String = "unknown",
        isScrolledExhausted: Boolean = false,
        coversFull30Minutes: Boolean = false,
        source: CollectionSource = CollectionSource.EXPANDED_PANEL,
        nowMs: Long = System.currentTimeMillis()
    ): RibbonSyncResult {
        val validItems = items.filter { it.multiplier.isFinite() && it.multiplier >= 1.0 }
        if (validItems.isEmpty()) {
            return RibbonSyncResult(
                newRecordsToPersist = emptyList(),
                status = HistoryCollectionStatus.HISTORY_PARTIAL,
                reason = "Expanded panel contained no readable multiplier history items.",
                isRepeatedSnapshot = false
            )
        }

        val cleanPlatform = platformName.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "_")
        val finalStatus = if (isScrolledExhausted || coversFull30Minutes) {
            HistoryCollectionStatus.HISTORY_COMPLETE
        } else {
            HistoryCollectionStatus.HISTORY_PARTIAL
        }

        val records = validItems.mapIndexed { idx, item ->
            val obsTs = nowMs - (idx * 100L)
            val tsSource = if (item.gameTimestampMs != null) {
                TimestampSource.GAME_PROVIDED
            } else {
                TimestampSource.LOCAL_OBSERVATION
            }
            val effectiveTs = item.gameTimestampMs ?: obsTs
            val batchSeq = ++monotonicCounter
            val key = when {
                !item.roundId.isNullOrBlank() -> "${gameId}_${cleanPlatform}_rid_${item.roundId}"
                item.gameTimestampMs != null -> "${gameId}_${cleanPlatform}_gt_${item.gameTimestampMs}_${formatMultKey(item.multiplier)}"
                else -> "${gameId}_${cleanPlatform}_exp_${nowMs}_${batchSeq}_${idx}_${formatMultKey(item.multiplier)}"
            }
            MultiplierRecord(
                gameId = gameId,
                roundId = item.roundId,
                multiplier = item.multiplier,
                gameTimestampMs = item.gameTimestampMs,
                observationTimestampMs = obsTs,
                crashTimestamp = effectiveTs,
                platform = platformName,
                timestampSource = tsSource,
                collectionSource = source,
                collectionStatus = finalStatus,
                dedupKey = key,
                sequenceIndex = idx,
                hasGapBefore = false
            )
        }

        val reason = if (finalStatus == HistoryCollectionStatus.HISTORY_COMPLETE) {
            "Collected ${records.size} accessible history records covering available 30-minute span."
        } else {
            "Collected ${records.size} records from accessible panel; older history still scrollable or unexposed."
        }

        return RibbonSyncResult(
            newRecordsToPersist = records,
            status = finalStatus,
            reason = reason,
            isRepeatedSnapshot = false
        )
    }
}
