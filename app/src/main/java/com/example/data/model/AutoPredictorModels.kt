package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

enum class MonitoringStatus(val displayLabel: String) {
    WAITING_FOR_GAME("Waiting for Game"),
    MONITORING("Monitoring Live"),
    GAME_NOT_ACCESSIBLE("Game Not Accessible"),
    GAME_SUSPENDED("Game Window Suspended"),
    PAUSED("Monitoring Paused"),
    STOPPED("Monitoring Stopped"),
    PERMISSION_REQUIRED("Permission Required"),
    SERVICE_INTERRUPTED("Service Interrupted")
}

enum class RoundLifecycleState {
    WAITING_FOR_ROUND,
    ROUND_STARTED,
    MULTIPLIER_RISING,
    CRASH_DETECTED,
    FINAL_MULTIPLIER_CONFIRMED,
    ROUND_RECORDED,
    WAITING_FOR_NEXT_ROUND
}

enum class CrashColorCategory(
    val displayName: String,
    val defaultHex: String
) {
    Pink("Pink", "#EC4899"),
    Red("Red", "#DC2626"),
    Purple("Purple", "#7C3AED"),
    Blue("Blue", "#2563EB"),
    Green("Green", "#16A34A"),
    Yellow("Yellow", "#CA8A04"),
    Orange("Orange", "#EA580C"),
    Other("Other", "#64748B")
}

data class CustomColorPaletteRule(
    val label: String,
    val hex: String,
    val category: CrashColorCategory = CrashColorCategory.Other,
    val minMultiplier: Double? = null,
    val maxMultiplier: Double? = null
)

object ColorClassifier {

    fun formatArgbHex(argb: Int): String {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return String.format(Locale.US, "#%02X%02X%02X", r, g, b)
    }

    fun parseHexToArgb(hex: String?): Int? {
        if (hex.isNullOrBlank()) return null
        val clean = hex.trim().removePrefix("#")
        return when (clean.length) {
            6 -> clean.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
            8 -> clean.toLongOrNull(16)?.toInt()
            else -> null
        }
    }

    fun normalizeHex(hex: String?): String {
        val argb = parseHexToArgb(hex) ?: return CrashColorCategory.Other.defaultHex
        return formatArgbHex(argb)
    }

    fun classifyArgb(argb: Int): CrashColorCategory {
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f

        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val delta = maxC - minC
        val saturation = if (maxC <= 0f) 0f else delta / maxC
        val brightness = maxC

        if (saturation < 0.16f || brightness < 0.12f) {
            return CrashColorCategory.Other
        }

        val hue = when {
            delta <= 0.0001f -> 0f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }

        return when {
            hue in 312f..348f -> CrashColorCategory.Pink
            hue >= 348f || hue < 15f -> {
                if (b > 0.45f && r > 0.7f && g < 0.45f) CrashColorCategory.Pink else CrashColorCategory.Red
            }
            hue in 15f..38f -> CrashColorCategory.Orange
            hue in 38f..70f -> CrashColorCategory.Yellow
            hue in 70f..165f -> CrashColorCategory.Green
            hue in 165f..252f -> CrashColorCategory.Blue
            hue in 252f..312f -> CrashColorCategory.Purple
            else -> CrashColorCategory.Other
        }
    }

    fun classifyHex(hex: String?): CrashColorCategory {
        val argb = parseHexToArgb(hex) ?: return CrashColorCategory.Other
        return classifyArgb(argb)
    }

    fun fallbackCategoryForMultiplier(
        multiplier: Double,
        lowThreshold: Double = 2.00,
        bigThreshold: Double = 10.00
    ): CrashColorCategory {
        return when {
            multiplier < lowThreshold -> CrashColorCategory.Blue
            abs(multiplier - lowThreshold) < 0.0001 -> CrashColorCategory.Purple
            multiplier < bigThreshold -> CrashColorCategory.Purple
            else -> CrashColorCategory.Pink
        }
    }
}

@Entity(
    tableName = "crash_records",
    indices = [
        Index(value = ["dedupKey"], unique = true),
        Index(value = ["sessionId"]),
        Index(value = ["gameId"]),
        Index(value = ["crashTimestamp"]),
        Index(value = ["hourStartTimestamp"])
    ]
)
data class CrashRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String = "",
    val gameId: String,
    val gameName: String,
    val originalDetectedTitle: String = "",
    val multiplier: Double,
    val detectedColorHex: String,
    val colorCategory: CrashColorCategory,
    val isPixelColorVerified: Boolean = false,
    val crashTimestamp: Long,
    val hourStartTimestamp: Long,
    val sourcePackage: String = "",
    val roundIdentifier: String? = null,
    val confidence: Float = 0.85f,
    val manuallyCorrected: Boolean = false,
    val isConfirmed: Boolean = true,
    val observationOrder: Int = 0,
    val collectionSource: CollectionSource = CollectionSource.LIVE_CRASH,
    val dedupKey: String
) {
    companion object {
        fun buildDedupKey(
            gameId: String,
            roundIdentifier: String?,
            crashTimestamp: Long,
            multiplier: Double,
            sequenceIndex: Int = 0
        ): String {
            val cleanGame = gameId.lowercase(Locale.US).replace(Regex("[^a-z0-9_]"), "_")
            val multStr = String.format(Locale.US, "%.2f", multiplier)
            if (!roundIdentifier.isNullOrBlank()) {
                return "${cleanGame}_rid_${roundIdentifier.trim()}"
            }
            return "${cleanGame}_ts_${crashTimestamp}_${multStr}_s$sequenceIndex"
        }

        fun buildLiveRoundDedupKey(
            sessionId: String,
            gameId: String,
            roundIdentifier: String?,
            sequenceIndex: Int
        ): String {
            val cleanSess = sessionId.ifBlank { "default" }.lowercase(Locale.US).replace(Regex("[^a-z0-9_]"), "_")
            val cleanGame = gameId.ifBlank { "unknown" }.lowercase(Locale.US).replace(Regex("[^a-z0-9_]"), "_")
            if (!roundIdentifier.isNullOrBlank()) {
                return "${cleanSess}_${cleanGame}_rid_${roundIdentifier.trim()}"
            }
            return "${cleanSess}_${cleanGame}_seq_$sequenceIndex"
        }
    }
}

@Entity(
    tableName = "monitoring_sessions",
    indices = [
        Index(value = ["gameId"]),
        Index(value = ["startedAtMs"])
    ]
)
data class MonitoringSessionEntity(
    @PrimaryKey
    val sessionId: String,
    val gameId: String,
    val gameName: String,
    val originalDetectedTitle: String = "",
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val isActive: Boolean = true,
    val confirmedCrashCount: Int = 0,
    val status: String = "ACTIVE"
)

@Entity(
    tableName = "crash_correction_audits",
    indices = [Index(value = ["crashRecordId"])]
)
data class CrashCorrectionAudit(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val crashRecordId: Long,
    val gameId: String,
    val originalMultiplier: Double,
    val originalColorHex: String,
    val originalColorCategory: CrashColorCategory,
    val correctedMultiplier: Double,
    val correctedColorHex: String,
    val correctedColorCategory: CrashColorCategory,
    val reason: String = "Manual user correction",
    val correctedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "hourly_session_summaries",
    indices = [
        Index(value = ["gameId", "hourStartTimestamp"], unique = true),
        Index(value = ["hourStartTimestamp"])
    ]
)
data class HourlySessionSummary(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val gameId: String,
    val gameName: String,
    val hourStartTimestamp: Long,
    val hourEndTimestamp: Long,
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
    val colorDistributionJson: String,
    val sampleStatus: String,
    val finalizedAtMs: Long
)

@Entity(
    tableName = "pattern_occurrence_records",
    indices = [
        Index(value = ["occurrenceKey"], unique = true),
        Index(value = ["strategyId"]),
        Index(value = ["gameId"]),
        Index(value = ["hourStartTimestamp"])
    ]
)
data class PatternOccurrenceRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val occurrenceKey: String,
    val strategyId: Long,
    val strategyName: String,
    val gameId: String,
    val gameName: String,
    val patternType: String,
    val triggerTimestampMs: Long,
    val hourStartTimestamp: Long,
    val detailsSummary: String,
    val firstMatchedTimestampMs: Long,
    val intervalSincePrevMs: Long? = null
)

object CalendarHourUtils {
    fun hourStartFor(timestampMs: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestampMs
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun hourEndFor(hourStartMs: Long): Long = hourStartMs + 3_600_000L - 1L

    fun formatHourWindow(hourStartMs: Long): String {
        val startFmt = SimpleDateFormat("HH:00", Locale.getDefault())
        val endFmt = SimpleDateFormat("HH:59", Locale.getDefault())
        val hourNum = SimpleDateFormat("H", Locale.getDefault()).format(Date(hourStartMs))
        return "${startFmt.format(Date(hourStartMs))}–${endFmt.format(Date(hourStartMs))} (Hour $hourNum)"
    }

    fun formatExactTime(timestampMs: Long): String {
        return if (timestampMs <= 0L) "--:--:--"
        else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
    }

    fun formatDateTime(timestampMs: Long): String {
        return if (timestampMs <= 0L) "--"
        else SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
    }
}
