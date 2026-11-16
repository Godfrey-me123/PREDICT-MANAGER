package com.example.data.engine

import com.example.data.model.MultiplierRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TimeWindowFilter(val label: String, val durationMinutes: Int) {
    THIRTY_MINUTES("30m", 30),
    ONE_HOUR("1h", 60),
    THREE_HOURS("3h", 180),
    FIVE_HOURS("5h", 300);

    val durationMs: Long get() = durationMinutes * 60_000L
}

data class WindowStatistics(
    val window: TimeWindowFilter,
    val totalRounds: Int,
    val below2xCount: Int,
    val below2xRecords: List<MultiplierRecord>,
    val above50xCount: Int,
    val above50xRecords: List<MultiplierRecord>,
    val above100xCount: Int,
    val above100xRecords: List<MultiplierRecord>
)

data class BigMultiplierSpotlight(
    val highestMultiplier: Double,
    val crashTimestamp: Long,
    val platform: String
)

object MultiplierHistoryAnalyzer {

    fun filterRecordsByWindow(
        records: List<MultiplierRecord>,
        window: TimeWindowFilter,
        nowMs: Long = System.currentTimeMillis()
    ): List<MultiplierRecord> {
        val cutoff = nowMs - window.durationMs
        return records
            .filter { it.crashTimestamp in cutoff..nowMs }
            .sortedByDescending { it.crashTimestamp }
    }

    fun analyzeWindow(
        records: List<MultiplierRecord>,
        window: TimeWindowFilter,
        nowMs: Long = System.currentTimeMillis()
    ): WindowStatistics {
        val windowRecords = filterRecordsByWindow(records, window, nowMs)
        val below2x = windowRecords.filter { it.multiplier < 2.00 }
        val above50x = windowRecords.filter { it.multiplier > 50.00 }
        val above100x = windowRecords.filter { it.multiplier > 100.00 }

        return WindowStatistics(
            window = window,
            totalRounds = windowRecords.size,
            below2xCount = below2x.size,
            below2xRecords = below2x,
            above50xCount = above50x.size,
            above50xRecords = above50x,
            above100xCount = above100x.size,
            above100xRecords = above100x
        )
    }

    fun getLastHourSpotlight(
        records: List<MultiplierRecord>,
        nowMs: Long = System.currentTimeMillis()
    ): BigMultiplierSpotlight? {
        val lastHourRecords = filterRecordsByWindow(records, TimeWindowFilter.ONE_HOUR, nowMs)
        val peak = lastHourRecords.maxByOrNull { it.multiplier } ?: return null
        return BigMultiplierSpotlight(
            highestMultiplier = peak.multiplier,
            crashTimestamp = peak.crashTimestamp,
            platform = peak.platform
        )
    }

    fun formatTimestamp(timestampMs: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestampMs))
    }

    fun formatFullTimestamp(timestampMs: Long): String {
        val sdf = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestampMs))
    }
}
