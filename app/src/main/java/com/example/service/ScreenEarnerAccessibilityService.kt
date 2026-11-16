package com.example.service

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.data.engine.AviatorGameAdapter
import com.example.data.engine.GameAdapterRegistry
import com.example.data.engine.GameDetection
import com.example.data.model.MonitoringStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GameDetectionState(
    val isGameDetected: Boolean = false,
    val gameId: String = "unknown",
    val displayedGameName: String = "Unknown",
    val supportedPlatforms: List<String> = emptyList(),
    val platformName: String = "Unknown",
    val isReady: Boolean = false,
    val gamePhase: String = "WAITING_FOR_GAME", // "WAITING_FOR_GAME", "BETTING_OPEN", "IN_FLIGHT", "CRASHED", "LIVE_DETECTION_LOST"
    val missingElements: List<String> = emptyList(),
    val detectedMultiplier: Double? = null,
    val liveColorHex: String? = null,
    val isLiveColorPixelVerified: Boolean = false,
    val liveDetectionLost: Boolean = false,
    val detectionConfidence: Float = 0f,
    val detectedRoundId: String? = null,
    val roundId: String? = detectedRoundId,
    val foregroundPackage: String = "",
    val uiTargets: DetectedUiTargets = DetectedUiTargets(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val windowAccessStatus: MonitoringStatus = MonitoringStatus.MONITORING,
    val gameWindowAccessible: Boolean = true,
    val gameWindowSuspended: Boolean = false,
    val originalDetectedTitle: String = ""
)

class ScreenEarnerAccessibilityService : AccessibilityController() {

    companion object {
        private const val TAG = "ScreenEarnerA11ySvc"
        var instance: ScreenEarnerAccessibilityService? = null
            private set

        private val _connectionStatus = MutableStateFlow(AccessibilityConnectionStatus.DISCONNECTED)
        val connectionStatus: StateFlow<AccessibilityConnectionStatus> = _connectionStatus.asStateFlow()

        fun isServiceConnected(): Boolean =
            _connectionStatus.value == AccessibilityConnectionStatus.CONNECTED && instance != null

        fun markConnecting() {
            if (_connectionStatus.value != AccessibilityConnectionStatus.CONNECTED) {
                _connectionStatus.value = AccessibilityConnectionStatus.CONNECTING
            }
        }

        private val _detectionState = MutableStateFlow(GameDetectionState())
        val detectionState: StateFlow<GameDetectionState> = _detectionState.asStateFlow()
    }

    @Volatile
    private var lastValidMultiplierOrRoundMs: Long = 0L

    @Volatile
    private var hadValidGameSignalInSession: Boolean = false

    override fun onServiceConnected() {
        try {
            super.onServiceConnected()
            instance = this
            _connectionStatus.value = AccessibilityConnectionStatus.CONNECTED
            Log.i(TAG, "Predict Manager Live Monitor onServiceConnected: Service active (multi-window inspection enabled).")
        } catch (t: Throwable) {
            _connectionStatus.value = AccessibilityConnectionStatus.ERROR
            Log.e(TAG, "Error in onServiceConnected during initialization", t)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return

            val packageName = try {
                event.packageName?.toString().orEmpty()
            } catch (_: Throwable) {
                ""
            }
            val ownPkg = runCatching { applicationContext?.packageName }.getOrNull()
            if (!ownPkg.isNullOrBlank() && packageName == ownPkg) {
                return
            }

            super.onAccessibilityEvent(event)

            val now = System.currentTimeMillis()
            val targets = detectedTargets.value
            val hasSignals = targets.detectedMultiplier != null ||
                targets.ribbonMultipliers.isNotEmpty() ||
                targets.isBetBtnDetected ||
                targets.isCashOutBtnDetected ||
                targets.isCancelBtnDetected ||
                targets.isFlewAwayDetected

            val resolvedSite = GameDetection.resolvePlatformFromSignals(
                packageName = packageName,
                detectedUrl = targets.detectedUrl,
                pageHints = targets.pageTextHints
            )

            val adapter = GameAdapterRegistry.resolveAdapter(
                packageName = packageName,
                url = targets.detectedUrl,
                pageHints = targets.pageTextHints,
                rawTexts = targets.visibleRawTitles,
                hasMultiplierSignals = hasSignals
            )

            // Even if title is Unknown, record when crash/multiplier signals are visible
            val isGameFound = hasSignals || (adapter != null && (
                resolvedSite != null ||
                    targets.pageTextHints.isNotEmpty()
                ))

            val platformDisplay = GameAdapterRegistry.resolvePlatformDisplay(
                packageName = packageName,
                url = targets.detectedUrl,
                pageHints = targets.pageTextHints
            )

            val rawTitleCandidate = targets.visibleRawTitles.firstOrNull().orEmpty()
            val resolvedGameId = adapter?.gameId?.ifBlank { "unknown" } ?: "unknown"
            val resolvedGameName = when {
                adapter != null && adapter.displayedGameName != "Unknown" ->
                    if (platformDisplay != "Unknown") "$platformDisplay ${adapter.displayedGameName}".trim() else adapter.displayedGameName
                resolvedSite != null -> resolvedSite
                else -> "Unknown"
            }
            val combinedPlatformLabel = resolvedSite ?: platformDisplay.ifBlank { "Unknown" }

            if (isGameFound && (targets.detectedMultiplier != null || targets.ribbonMultipliers.isNotEmpty() || targets.isFlewAwayDetected || targets.isWaitingForNextRound)) {
                lastValidMultiplierOrRoundMs = now
                hadValidGameSignalInSession = true
            } else if (!isGameFound) {
                hadValidGameSignalInSession = false
                lastValidMultiplierOrRoundMs = 0L
            }

            val roundState = adapter?.detectRoundState(
                targets = targets,
                hasActiveGameBefore = hadValidGameSignalInSession,
                nowMs = now,
                lastValidDetectionMs = lastValidMultiplierOrRoundMs
            ) ?: targets.gamePhase

            val liveLost = roundState == "LIVE_DETECTION_LOST" ||
                (isGameFound && hadValidGameSignalInSession &&
                    targets.detectedMultiplier == null &&
                    targets.ribbonMultipliers.isEmpty() &&
                    lastValidMultiplierOrRoundMs > 0L &&
                    (now - lastValidMultiplierOrRoundMs) > AviatorGameAdapter.LIVE_DETECTION_TIMEOUT_MS)

            val liveSample = if (targets.detectedMultiplier != null && targets.liveMultiplierBounds != null) {
                ScreenColorSampler.sampleColorInBounds(targets.liveMultiplierBounds)
            } else null

            val confidence = when {
                !isGameFound -> 0f
                liveLost -> 0.2f
                targets.detectedMultiplier != null && targets.ribbonItems.isNotEmpty() -> 0.96f
                targets.detectedMultiplier != null || targets.ribbonItems.isNotEmpty() -> 0.86f
                else -> 0.60f
            }

            val missing = mutableListOf<String>()
            if (targets.detectedMultiplier == null) missing.add("Live Center Multiplier")
            if (targets.ribbonItems.isEmpty()) missing.add("History Ribbon / Grid")

            val activeRoundId = targets.detectedCurrentRoundId ?: targets.roundId ?: targets.ribbonItems.firstOrNull()?.roundId
            val accessStatus = when {
                !isGameFound -> MonitoringStatus.WAITING_FOR_GAME
                liveLost -> MonitoringStatus.GAME_NOT_ACCESSIBLE
                else -> MonitoringStatus.MONITORING
            }

            _detectionState.value = GameDetectionState(
                isGameDetected = isGameFound,
                gameId = resolvedGameId,
                displayedGameName = resolvedGameName,
                supportedPlatforms = adapter?.supportedPlatforms ?: emptyList(),
                platformName = combinedPlatformLabel,
                isReady = isGameFound && !liveLost,
                gamePhase = if (liveLost) "LIVE_DETECTION_LOST" else roundState,
                missingElements = missing,
                detectedMultiplier = if (liveLost) null else targets.detectedMultiplier,
                liveColorHex = liveSample?.hex,
                isLiveColorPixelVerified = liveSample?.isPixelVerified == true,
                liveDetectionLost = liveLost,
                detectionConfidence = confidence,
                detectedRoundId = activeRoundId,
                roundId = activeRoundId,
                foregroundPackage = packageName,
                uiTargets = targets,
                updatedAtMs = now,
                windowAccessStatus = accessStatus,
                gameWindowAccessible = isGameFound && !liveLost,
                gameWindowSuspended = liveLost,
                originalDetectedTitle = rawTitleCandidate.ifBlank { adapter?.displayedGameName.orEmpty() }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Caught non-fatal exception in onAccessibilityEvent (service preserved)", t)
        }
    }

    fun performClickAtCoordinates(x: Float, y: Float, onComplete: ((Boolean) -> Unit)? = null) {
        dispatchTouchGesture(x, y, onComplete)
    }

    override fun onInterrupt() {
        try {
            super.onInterrupt()
            Log.w(TAG, "Predict Manager Live Monitor onInterrupt called by system.")
        } catch (t: Throwable) {
            _connectionStatus.value = AccessibilityConnectionStatus.ERROR
            Log.e(TAG, "Error in onInterrupt", t)
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        return try {
            if (instance == this) {
                instance = null
            }
            _connectionStatus.value = AccessibilityConnectionStatus.DISCONNECTED
            Log.i(TAG, "Predict Manager Live Monitor onUnbind: Service unbound by system.")
            super.onUnbind(intent)
        } catch (t: Throwable) {
            _connectionStatus.value = AccessibilityConnectionStatus.ERROR
            Log.e(TAG, "Error in onUnbind", t)
            false
        }
    }

    override fun onDestroy() {
        try {
            super.onDestroy()
            if (instance == this) {
                instance = null
            }
            _connectionStatus.value = AccessibilityConnectionStatus.DISCONNECTED
            Log.i(TAG, "Predict Manager Live Monitor onDestroy: Service destroyed cleanly.")
        } catch (t: Throwable) {
            _connectionStatus.value = AccessibilityConnectionStatus.ERROR
            Log.e(TAG, "Error in onDestroy", t)
        }
    }
}
