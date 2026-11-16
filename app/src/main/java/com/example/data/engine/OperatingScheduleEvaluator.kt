package com.example.data.engine

import com.example.data.model.StrategyConfig
import java.util.Calendar
import java.util.Locale

data class ScheduleEvaluation(
    val isScheduleEnabled: Boolean,
    val isWithinOperatingHours: Boolean,
    val formattedWindow: String
) {
    val standbyBanner: String
        get() = "Standby — Outside scheduled hours ($formattedWindow)"
}

object OperatingScheduleEvaluator {

    fun formatTime(hour: Int, minute: Int): String {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        return String.format(Locale.US, "%02d:%02d", h, m)
    }

    fun formatWindow(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int): String {
        return "${formatTime(startHour, startMinute)}–${formatTime(endHour, endMinute)}"
    }

    fun isWithinOperatingHours(
        scheduleEnabled: Boolean,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!scheduleEnabled) return true
        val cal = Calendar.getInstance().apply { timeInMillis = currentTimeMs }
        val nowMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return isMinuteOfDayWithinWindow(
            minuteOfDay = nowMins,
            startHour = startHour,
            startMinute = startMinute,
            endHour = endHour,
            endMinute = endMinute
        )
    }

    fun isMinuteOfDayWithinWindow(
        minuteOfDay: Int,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int
    ): Boolean {
        val now = minuteOfDay.coerceIn(0, 24 * 60 - 1)
        val start = startHour.coerceIn(0, 23) * 60 + startMinute.coerceIn(0, 59)
        val end = endHour.coerceIn(0, 23) * 60 + endMinute.coerceIn(0, 59)

        return when {
            start == end -> true // Identical start/end implies 24h continuous window
            start < end -> now in start until end
            else -> now >= start || now < end // Overnight window e.g. 22:00 - 04:00
        }
    }

    fun evaluate(config: StrategyConfig, currentTimeMs: Long = System.currentTimeMillis()): ScheduleEvaluation {
        val window = formatWindow(
            config.scheduleStartHour,
            config.scheduleStartMinute,
            config.scheduleEndHour,
            config.scheduleEndMinute
        )
        val active = isWithinOperatingHours(
            scheduleEnabled = config.scheduleEnabled,
            startHour = config.scheduleStartHour,
            startMinute = config.scheduleStartMinute,
            endHour = config.scheduleEndHour,
            endMinute = config.scheduleEndMinute,
            currentTimeMs = currentTimeMs
        )
        return ScheduleEvaluation(
            isScheduleEnabled = config.scheduleEnabled,
            isWithinOperatingHours = active,
            formattedWindow = window
        )
    }
}
