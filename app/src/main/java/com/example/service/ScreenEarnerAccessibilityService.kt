package com.example.service

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.data.engine.GameAdapterRegistry
import com.example.data.engine.GameDetection
import com.example.data.model.MonitoringStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GameDetectionState(
    val isGameDetected: Boolean = false,
    val platformName: String = "Unknown",
    val displayedGameName: String = "",
    val gameId: String = "",
    val roundId: String? = null,
    val detectedRoundId: String? = null,
    val originalDetectedTitle: String = "",
    val supportedPlatforms: List<String> = emptyList(),
    val isReady: Boolean = false,
    val gamePhase: String = "WAITING_FOR_GAME",
    val missingElements: List<String> = emptyList(),
    val detectedMultiplier: Double? = null,
    val foregroundPackage: String = "",
    val liveColorHex: String? = null,
    val isLiveColorPixelVerified: Boolean = false,
    val liveDetectionLost: Boolean = false,
    val detectionConfidence: Float = 1f,
    val windowAccessStatus: MonitoringStatus = MonitoringStatus.MONITORING,
    val gameWindowAccessible: Boolean = true,
    val gameWindowSuspended: Boolean = false,
    val uiTargets: DetectedUiTargets = DetectedUiTargets(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

class ScreenEarnerAccessibilityService : AccessibilityController() {

    companion object {
        private const val TAG = "PredictManagerA11ySvc"
        @Volatile
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

    /** Shared action executor. StrategyEngine remains the authority for strategy decisions. */
    val actionExecutor: ScreenEarnerActionExecutor by lazy(LazyThreadSafetyMode.NONE) {
        ScreenEarnerActionExecutor(this)
    }

    /** Optional ordered UI macro runner; it does not collect multipliers or create strategies. */
    val macroRunner: ScreenEarnerMacroRunner by lazy(LazyThreadSafetyMode.NONE) {
        ScreenEarnerMacroRunner(actionExecutor)
    }

    override fun onServiceConnected() {
        try {
            super.onServiceConnected()
            instance = this
            _connectionStatus.value = AccessibilityConnectionStatus.CONNECTED
            Log.i(TAG, "ScreenEarnerAccessibilityService connected successfully.")
        } catch (t: Throwable) {
            _connectionStatus.value = AccessibilityConnectionStatus.ERROR
            Log.e(TAG, "Error in ScreenEarnerAccessibilityService.onServiceConnected", t)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return

            val packageName = runCatching { event.packageName?.toString().orEmpty() }
                .getOrDefault("")
            val ownPkg = runCatching { applicationContext?.packageName }.getOrNull()

            // Ignore Predict Manager's own overlay events; do not let its controls become game targets.
            if (!ownPkg.isNullOrBlank() && packageName == ownPkg) return

            super.onAccessibilityEvent(event)

            val targets = detectedTargets.value
            val hasMultiplierSignals = targets.detectedMultiplier != null ||
                targets.ribbonMultipliers.isNotEmpty() ||
                targets.isBetBtnDetected ||
                targets.isCashOutBtnDetected ||
                targets.isCancelBtnDetected

            val resolvedAdapter = GameAdapterRegistry.resolveAdapter(
                packageName = packageName,
                url = targets.detectedUrl,
                pageHints = targets.pageTextHints,
                rawTexts = targets.visibleRawTitles,
                hasMultiplierSignals = hasMultiplierSignals
            )
            val platformName = GameAdapterRegistry.resolvePlatformDisplay(
                packageName = packageName,
                url = targets.detectedUrl,
                pageHints = targets.pageTextHints
            )

            val resolvedGameTitle = resolvedAdapter?.displayedGameName
                ?: GameDetection.resolvePlatformFromSignals(
                    packageName = packageName,
                    detectedUrl = targets.detectedUrl,
                    pageHints = targets.pageTextHints
                )
                ?: platformName.takeIf { it != "Unknown" }
                ?: "Detected Game"

            val resolvedGameId = resolvedAdapter?.gameId?.takeIf { it.isNotBlank() && it != "unknown" }
                ?: resolvedGameTitle.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "unknown" }

            var confidence = 0f
            if (resolvedAdapter != null && resolvedAdapter.gameId != "unknown") confidence += 0.45f
            else if (resolvedAdapter != null) confidence += 0.25f
            if (!targets.detectedUrl.isNullOrBlank() || platformName != "Unknown") confidence += 0.20f
            if (targets.detectedMultiplier != null) confidence += 0.15f
            if (targets.ribbonMultipliers.isNotEmpty()) confidence += 0.10f
            if (targets.isBetBtnDetected || targets.isCashOutBtnDetected || targets.isCancelBtnDetected || targets.isStakeInputDetected) {
                confidence += 0.15f
            }
            val boundedConfidence = confidence.coerceIn(0f, 1f)

            val gameDetected = resolvedAdapter != null ||
                hasMultiplierSignals ||
                targets.detectedBalance != null ||
                packageName.contains("chrome", ignoreCase = true) ||
                packageName.contains("browser", ignoreCase = true)

            val missing = buildList {
                if (!targets.isBetBtnDetected && !targets.isCancelBtnDetected &&
                    !targets.isCashOutBtnDetected
                ) add("Bet Control Target")
                if (!targets.isCashOutBtnDetected) add("Cash Out Target")
                if (!targets.isStakeInputDetected && !targets.isDoubleBtnDetected &&
                    !targets.isAddBtnDetected
                ) add("Stake Input / 2x / + Controls")
            }

            val allSupportedPlatforms = (resolvedAdapter?.supportedPlatforms
                ?: GameAdapterRegistry.allAdapters().flatMap { it.supportedPlatforms }).distinct()

            _detectionState.value = GameDetectionState(
                isGameDetected = gameDetected,
                platformName = platformName,
                displayedGameName = resolvedGameTitle,
                gameId = resolvedGameId,
                roundId = targets.detectedCurrentRoundId,
                detectedRoundId = targets.detectedCurrentRoundId,
                originalDetectedTitle = resolvedGameTitle,
                supportedPlatforms = allSupportedPlatforms,
                isReady = gameDetected && hasMultiplierSignals,
                gamePhase = targets.gamePhase,
                missingElements = missing,
                detectedMultiplier = targets.detectedMultiplier,
                foregroundPackage = packageName,
                detectionConfidence = boundedConfidence,
                gameWindowAccessible = true,
                uiTargets = targets,
                updatedAtMs = System.currentTimeMillis()
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Error processing accessibility event", t)
        }
    }

    fun performClickAtCoordinates(
        x: Float,
        y: Float,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        dispatchTouchGesture(x, y, onComplete)
    }

    override fun onInterrupt() {
        try {
            _connectionStatus.value = AccessibilityConnectionStatus.ERROR
            macroRunner.stop()
            super.onInterrupt()
        } catch (t: Throwable) {
            Log.e(TAG, "Error in onInterrupt", t)
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        return try {
            macroRunner.stop()
            if (instance === this) instance = null
            _connectionStatus.value = AccessibilityConnectionStatus.DISCONNECTED
            super.onUnbind(intent)
        } catch (t: Throwable) {
            _connectionStatus.value = AccessibilityConnectionStatus.ERROR
            Log.e(TAG, "Error in onUnbind", t)
            false
        }
    }

    override fun onDestroy() {
        try {
            macroRunner.stop()
            super.onDestroy()
            if (instance === this) instance = null
            _connectionStatus.value = AccessibilityConnectionStatus.DISCONNECTED
        } catch (t: Throwable) {
            _connectionStatus.value = AccessibilityConnectionStatus.ERROR
            Log.e(TAG, "Error in onDestroy", t)
        }
    }
}
