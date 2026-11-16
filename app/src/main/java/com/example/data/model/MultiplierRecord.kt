package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Locale

enum class HistoryCollectionStatus(val displayLabel: String) {
    HISTORY_NOT_STARTED("Not Started"),
    COLLECTING_VISIBLE_HISTORY("Reading Visible Ribbon"),
    EXPANDING_HISTORY("Expanding History Panel"),
    SCROLLING_HISTORY("Scrolling History Panel"),
    HISTORY_PARTIAL("Partial History"),
    HISTORY_COMPLETE("30m History Complete"),
    HISTORY_UNAVAILABLE("History Unavailable"),
    HISTORY_ERROR("Collection Error")
}

enum class TimestampSource(val displayLabel: String) {
    GAME_PROVIDED("Game Time"),
    LOCAL_OBSERVATION("Observed Time")
}

enum class CollectionSource(val displayLabel: String) {
    VISIBLE_RIBBON("Visible Ribbon"),
    EXPANDED_PANEL("Expanded Panel"),
    SCROLLED_HISTORY("Scrolled History"),
    LIVE_CRASH("Live Crash"),
    LIVE("Live"),
    HISTORY_IMPORT("History Import")
}

@Entity(
    tableName = "multiplier_records",
    indices = [
        Index(value = ["dedupKey"], unique = true),
        Index(value = ["sessionId"]),
        Index(value = ["crashTimestamp"])
    ]
)
data class MultiplierRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String = "",
    val gameId: String = "unknown",
    val roundId: String? = null,
    val multiplier: Double,
    val gameTimestampMs: Long? = null,
    val observationTimestampMs: Long = System.currentTimeMillis(),
    val crashTimestamp: Long = gameTimestampMs ?: observationTimestampMs,
    val platform: String = "Unknown",
    val isConfirmed: Boolean = true,
    val timestampSource: TimestampSource = if (gameTimestampMs != null) {
        TimestampSource.GAME_PROVIDED
    } else {
        TimestampSource.LOCAL_OBSERVATION
    },
    val collectionSource: CollectionSource = CollectionSource.LIVE_CRASH,
    val collectionStatus: HistoryCollectionStatus = HistoryCollectionStatus.HISTORY_PARTIAL,
    val dedupKey: String = buildFallbackDedupKey(
        gameId = gameId,
        platform = platform,
        roundId = roundId,
        gameTimestampMs = gameTimestampMs,
        observationTimestampMs = observationTimestampMs,
        crashTimestamp = crashTimestamp,
        multiplier = multiplier,
        sequenceIndex = 0
    ),
    val sequenceIndex: Int = 0,
    val hasGapBefore: Boolean = false
) {
    companion object {
        fun buildFallbackDedupKey(
            gameId: String,
            platform: String,
            roundId: String?,
            gameTimestampMs: Long?,
            observationTimestampMs: Long,
            crashTimestamp: Long,
            multiplier: Double,
            sequenceIndex: Int
        ): String {
            val cleanPlatform = platform.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "_")
            val multStr = String.format(Locale.US, "%.2f", multiplier)
            if (!roundId.isNullOrBlank()) {
                return "${gameId}_${cleanPlatform}_rid_${roundId.trim()}"
            }
            if (gameTimestampMs != null && gameTimestampMs > 0L) {
                return "${gameId}_${cleanPlatform}_gt_${gameTimestampMs}_${multStr}_s$sequenceIndex"
            }
            val effectiveTs = if (crashTimestamp > 0L) crashTimestamp else observationTimestampMs
            return "${gameId}_${cleanPlatform}_obs_${effectiveTs}_${multStr}_s$sequenceIndex"
        }
    }
}
