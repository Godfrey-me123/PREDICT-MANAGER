package com.example.service

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.MonitoringStatus
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class PersistedRibbonBaselineItem(
    val multiplier: Double,
    val displayText: String,
    val roundId: String?,
    val gameTimestampMs: Long?,
    val orderIndex: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/**
 * Persists monitoring session metadata, per-game baseline snapshots, confirmation sequence,
 * intended monitoring state, top status pill coordinates, and resizable overlay panel geometry
 * across Android service restarts.
 */
class SessionStateManager(context: Context) {

    companion object {
        const val PREFS_NAME = "screen_earner_prefs"
        const val KEY_ACTIVE_SESSION_ID = "active_session_id"
        const val KEY_MONITORING_DESIRED_STATE = "monitoring_desired_state"
        private const val KEY_SESSION_STARTED_MS = "active_session_started_ms"
        private const val KEY_SESSION_GAME_ID = "active_session_game_id"
        private const val KEY_BASELINE_CAPTURED = "baseline_captured"
        private const val KEY_BASELINE_GAME_ID = "baseline_game_id"
        private const val KEY_BASELINE_SNAPSHOT_JSON = "baseline_snapshot_json"
        private const val KEY_LAST_CONFIRMED_ROUND_KEY = "last_confirmed_round_key"
        private const val KEY_CONFIRMED_SEQUENCE = "confirmed_crash_sequence"
        private const val KEY_INTENDED_MONITORING_ACTIVE = "intended_monitoring_active"
        private const val KEY_TOP_PILL_X = "top_pill_x"
        private const val KEY_TOP_PILL_Y = "top_pill_y"
        private const val KEY_TOP_PILL_HAS_POS = "top_pill_has_pos"
        private const val KEY_PANEL_WIDTH_DP = "overlay_panel_width_dp"
        private const val KEY_PANEL_HEIGHT_DP = "overlay_panel_height_dp"
        private const val KEY_PANEL_COMPACT = "overlay_panel_compact"
        private const val KEY_PANEL_X = "overlay_panel_x"
        private const val KEY_PANEL_Y = "overlay_panel_y"
        private const val KEY_PANEL_HAS_POS = "overlay_panel_has_pos"
        private const val KEY_DAILY_LOSS_DATE = "daily_loss_date_key"
        private const val KEY_DAILY_LOSS_AMOUNT = "daily_loss_amount"
        private const val KEY_AUTOMATION_ENABLED = "automation_assistant_enabled"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getActiveSessionId(): String = prefs.getString(KEY_ACTIVE_SESSION_ID, "").orEmpty()

    fun getSessionStartedMs(): Long = prefs.getLong(KEY_SESSION_STARTED_MS, 0L)

    fun getSessionGameId(): String = prefs.getString(KEY_SESSION_GAME_ID, "").orEmpty()

    fun getActiveSessionForGame(gameId: String): String {
        val norm = gameId.ifBlank { "unknown" }
        return prefs.getString("active_session_for_$norm", "").orEmpty()
    }

    fun setActiveSessionForGame(gameId: String, sessionId: String) {
        val norm = gameId.ifBlank { "unknown" }
        prefs.edit().putString("active_session_for_$norm", sessionId).apply()
    }

    fun isBaselineCaptured(): Boolean = prefs.getBoolean(KEY_BASELINE_CAPTURED, false)

    fun getBaselineGameId(): String = prefs.getString(KEY_BASELINE_GAME_ID, "").orEmpty()

    fun getLastConfirmedRoundKey(): String? = prefs.getString(KEY_LAST_CONFIRMED_ROUND_KEY, null)

    fun isIntendedMonitoringActive(): Boolean = prefs.getBoolean(KEY_INTENDED_MONITORING_ACTIVE, false)

    fun getDesiredMonitoringStatus(): MonitoringStatus {
        val raw = prefs.getString(KEY_MONITORING_DESIRED_STATE, MonitoringStatus.STOPPED.name)
        return runCatching { MonitoringStatus.valueOf(raw ?: MonitoringStatus.STOPPED.name) }
            .getOrDefault(MonitoringStatus.STOPPED)
    }

    fun setIntendedMonitoringActive(active: Boolean) {
        val desired = if (active) MonitoringStatus.MONITORING.name else MonitoringStatus.STOPPED.name
        prefs.edit()
            .putBoolean(KEY_INTENDED_MONITORING_ACTIVE, active)
            .putString(KEY_MONITORING_DESIRED_STATE, desired)
            .commit()
    }

    fun setDesiredMonitoringStatus(status: MonitoringStatus) {
        prefs.edit()
            .putString(KEY_MONITORING_DESIRED_STATE, status.name)
            .putBoolean(
                KEY_INTENDED_MONITORING_ACTIVE,
                status == MonitoringStatus.MONITORING || status == MonitoringStatus.WAITING_FOR_GAME
            )
            .commit()
    }

    fun isAutomationEnabled(): Boolean = prefs.getBoolean(KEY_AUTOMATION_ENABLED, false)

    fun setAutomationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOMATION_ENABLED, enabled).commit()
    }

    private fun dateKeyFor(nowMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(Date(nowMs))
    }

    fun getDailyLoss(nowMs: Long = System.currentTimeMillis()): Double {
        val todayKey = dateKeyFor(nowMs)
        val storedDate = prefs.getString(KEY_DAILY_LOSS_DATE, null)
        if (storedDate != todayKey) {
            prefs.edit()
                .putString(KEY_DAILY_LOSS_DATE, todayKey)
                .putFloat(KEY_DAILY_LOSS_AMOUNT, 0f)
                .apply()
            return 0.0
        }
        return prefs.getFloat(KEY_DAILY_LOSS_AMOUNT, 0f).toDouble().coerceAtLeast(0.0)
    }

    fun recordDailyNetOutcome(netProfit: Double, nowMs: Long = System.currentTimeMillis()): Double {
        val current = getDailyLoss(nowMs)
        val updated = if (netProfit < 0.0) {
            current + (-netProfit)
        } else {
            (current - netProfit).coerceAtLeast(0.0)
        }
        prefs.edit()
            .putString(KEY_DAILY_LOSS_DATE, dateKeyFor(nowMs))
            .putFloat(KEY_DAILY_LOSS_AMOUNT, updated.toFloat())
            .apply()
        return updated
    }

    fun resetDailyLoss(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_DAILY_LOSS_DATE, dateKeyFor(nowMs))
            .putFloat(KEY_DAILY_LOSS_AMOUNT, 0f)
            .apply()
    }

    fun ensureActiveSession(nowMs: Long = System.currentTimeMillis(), gameId: String = "unknown"): String {
        val existing = getActiveSessionId()
        if (existing.isNotBlank()) return existing
        return startFreshSession(nowMs, gameId)
    }

    fun startFreshSession(nowMs: Long = System.currentTimeMillis(), gameId: String = "unknown"): String {
        val normGame = gameId.ifBlank { "unknown" }
        val newId = "sess_${nowMs}_${UUID.randomUUID().toString().take(8)}"
        prefs.edit()
            .putString(KEY_ACTIVE_SESSION_ID, newId)
            .putLong(KEY_SESSION_STARTED_MS, nowMs)
            .putString(KEY_SESSION_GAME_ID, normGame)
            .putString("active_session_for_$normGame", newId)
            .putBoolean(KEY_BASELINE_CAPTURED, false)
            .putString(KEY_BASELINE_GAME_ID, "")
            .remove(KEY_BASELINE_SNAPSHOT_JSON)
            .remove(KEY_LAST_CONFIRMED_ROUND_KEY)
            .putLong(KEY_CONFIRMED_SEQUENCE, 0L)
            .commit()
        return newId
    }

    fun nextConfirmedSequence(): Long {
        val next = prefs.getLong(KEY_CONFIRMED_SEQUENCE, 0L) + 1L
        prefs.edit().putLong(KEY_CONFIRMED_SEQUENCE, next).apply()
        return next
    }

    fun setLastConfirmedRoundKey(roundKey: String?) {
        val editor = prefs.edit()
        if (roundKey.isNullOrBlank()) {
            editor.remove(KEY_LAST_CONFIRMED_ROUND_KEY)
        } else {
            editor.putString(KEY_LAST_CONFIRMED_ROUND_KEY, roundKey)
        }
        editor.apply()
    }

    fun saveBaselineSnapshot(gameId: String, items: List<PersistedRibbonBaselineItem>, captured: Boolean = true) {
        val normGame = gameId.ifBlank { "unknown" }
        val array = JSONArray()
        items.forEach { item ->
            val obj = JSONObject().apply {
                put("m", item.multiplier)
                put("t", item.displayText)
                put("r", item.roundId ?: JSONObject.NULL)
                put("gt", item.gameTimestampMs ?: JSONObject.NULL)
                put("i", item.orderIndex)
                put("l", item.left)
                put("tp", item.top)
                put("rt", item.right)
                put("b", item.bottom)
            }
            array.put(obj)
        }
        val json = array.toString()
        prefs.edit()
            .putBoolean(KEY_BASELINE_CAPTURED, captured)
            .putString(KEY_BASELINE_GAME_ID, normGame)
            .putString(KEY_BASELINE_SNAPSHOT_JSON, json)
            .putBoolean("baseline_${normGame}_captured", captured)
            .putString("baseline_${normGame}_json", json)
            .apply()
    }

    fun loadBaselineSnapshot(expectedGameId: String? = null): List<PersistedRibbonBaselineItem>? {
        val normExpected = expectedGameId?.takeIf { it.isNotBlank() }
        if (normExpected != null && prefs.getBoolean("baseline_${normExpected}_captured", false)) {
            val gameJson = prefs.getString("baseline_${normExpected}_json", null)
            val parsedGame = parseBaselineJson(gameJson)
            if (parsedGame != null) return parsedGame
        }
        if (!isBaselineCaptured()) return null
        val savedGame = getBaselineGameId()
        if (normExpected != null && savedGame.isNotBlank() && savedGame != normExpected) {
            return null
        }
        return parseBaselineJson(prefs.getString(KEY_BASELINE_SNAPSHOT_JSON, null))
    }

    private fun parseBaselineJson(raw: String?): List<PersistedRibbonBaselineItem>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val array = JSONArray(raw)
            val result = ArrayList<PersistedRibbonBaselineItem>(array.length())
            for (idx in 0 until array.length()) {
                val obj = array.getJSONObject(idx)
                val roundId = if (obj.isNull("r")) null else obj.optString("r", "").takeIf { it.isNotBlank() }
                val gameTs = if (obj.isNull("gt")) null else obj.optLong("gt", -1L).takeIf { it > 0L }
                result.add(
                    PersistedRibbonBaselineItem(
                        multiplier = obj.getDouble("m"),
                        displayText = obj.optString("t", ""),
                        roundId = roundId,
                        gameTimestampMs = gameTs,
                        orderIndex = obj.optInt("i", idx),
                        left = obj.optInt("l", 0),
                        top = obj.optInt("tp", 0),
                        right = obj.optInt("rt", 0),
                        bottom = obj.optInt("b", 0)
                    )
                )
            }
            result
        } catch (_: Throwable) {
            null
        }
    }

    fun clearBaselineForGame(gameId: String) {
        val norm = gameId.ifBlank { "unknown" }
        prefs.edit()
            .remove("baseline_${norm}_captured")
            .remove("baseline_${norm}_json")
            .apply()
    }

    fun clearSessionRuntimeState() {
        val allKeys = prefs.all.keys.filter {
            it.startsWith("baseline_") || it.startsWith("active_session_for_")
        }
        val ed = prefs.edit()
            .remove(KEY_ACTIVE_SESSION_ID)
            .remove(KEY_SESSION_STARTED_MS)
            .remove(KEY_SESSION_GAME_ID)
            .putBoolean(KEY_BASELINE_CAPTURED, false)
            .remove(KEY_BASELINE_GAME_ID)
            .remove(KEY_BASELINE_SNAPSHOT_JSON)
            .remove(KEY_LAST_CONFIRMED_ROUND_KEY)
            .putLong(KEY_CONFIRMED_SEQUENCE, 0L)
            .putBoolean(KEY_INTENDED_MONITORING_ACTIVE, false)
            .putString(KEY_MONITORING_DESIRED_STATE, MonitoringStatus.STOPPED.name)
        allKeys.forEach { ed.remove(it) }
        ed.commit()
    }

    fun saveTopPillPosition(x: Int, y: Int) {
        prefs.edit()
            .putBoolean(KEY_TOP_PILL_HAS_POS, true)
            .putInt(KEY_TOP_PILL_X, x)
            .putInt(KEY_TOP_PILL_Y, y)
            .apply()
    }

    fun loadTopPillPosition(defaultX: Int, defaultY: Int): Pair<Int, Int> {
        if (!prefs.getBoolean(KEY_TOP_PILL_HAS_POS, false)) {
            return defaultX to defaultY
        }
        return prefs.getInt(KEY_TOP_PILL_X, defaultX) to prefs.getInt(KEY_TOP_PILL_Y, defaultY)
    }

    fun savePanelSize(widthDp: Int, heightDp: Int) {
        prefs.edit()
            .putInt(KEY_PANEL_WIDTH_DP, widthDp.coerceIn(220, 420))
            .putInt(KEY_PANEL_HEIGHT_DP, heightDp.coerceIn(180, 560))
            .apply()
    }

    fun loadPanelSize(defaultWidthDp: Int = 276, defaultHeightDp: Int = 340): Pair<Int, Int> {
        val w = prefs.getInt(KEY_PANEL_WIDTH_DP, defaultWidthDp).coerceIn(220, 420)
        val h = prefs.getInt(KEY_PANEL_HEIGHT_DP, defaultHeightDp).coerceIn(180, 560)
        return w to h
    }

    fun savePanelCompactMode(compact: Boolean) {
        prefs.edit().putBoolean(KEY_PANEL_COMPACT, compact).apply()
    }

    fun isPanelCompactMode(): Boolean = prefs.getBoolean(KEY_PANEL_COMPACT, false)

    fun savePanelPosition(x: Int, y: Int) {
        prefs.edit()
            .putBoolean(KEY_PANEL_HAS_POS, true)
            .putInt(KEY_PANEL_X, x)
            .putInt(KEY_PANEL_Y, y)
            .apply()
    }

    fun loadPanelPosition(defaultX: Int, defaultY: Int): Pair<Int, Int> {
        if (!prefs.getBoolean(KEY_PANEL_HAS_POS, false)) {
            return defaultX to defaultY
        }
        return prefs.getInt(KEY_PANEL_X, defaultX) to prefs.getInt(KEY_PANEL_Y, defaultY)
    }
}
