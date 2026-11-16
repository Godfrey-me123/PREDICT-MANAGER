package com.example.service

import android.util.Log
import android.view.accessibility.AccessibilityEvent
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
            val resolvedSite = GameDetection.resolvePlatformFromSignals(
                packageName = packageName,
                detectedUrl = targets.detectedUrl,
                pageHints = targets.pageTextHints
            )

            val gameDetected = resolvedSite != null ||
                packageName.contains("chrome", ignoreCase = true) ||
                packageName.contains("browser", ignoreCase = true) ||
                targets.detectedMultiplier != null ||
                targets.ribbonMultipliers.isNotEmpty() ||
                targets.isBetBtnDetected ||
                targets.isCashOutBtnDetected ||
                targets.isCancelBtnDetected ||
                targets.detectedBalance != null

            val detectedPlatform = resolvedSite ?: when {
                packageName.contains("chrome", ignoreCase = true) -> "Chrome Aviator Web"
                targets.detectedUrl?.contains("betpawa", ignoreCase = true) == true -> "BetPawa Aviator"
                targets.detectedUrl?.contains("sportybet", ignoreCase = true) == true -> "SportyBet Aviator"
                else -> "Detected Game"
            }

            val missing = buildList {
                if (!targets.isBetBtnDetected && !targets.isCancelBtnDetected &&
                    !targets.isCashOutBtnDetected
                ) add("Bet Control Target")
                if (!targets.isCashOutBtnDetected) add("Cash Out Target")
                if (!targets.isStakeInputDetected && !targets.isDoubleBtnDetected &&
                    !targets.isAddBtnDetected
                ) add("Stake Input / 2x / + Controls")
            }

            _detectionState.value = GameDetectionState(
                isGameDetected = gameDetected,
                platformName = detectedPlatform,
                displayedGameName = detectedPlatform,
                gameId = detectedPlatform.lowercase().replace("\\s+".toRegex(), "_"),
                roundId = targets.detectedCurrentRoundId,
                detectedRoundId = targets.detectedCurrentRoundId,
                originalDetectedTitle = detectedPlatform,
                supportedPlatforms = listOf("BetPawa", "SportyBet", "Chrome"),
                isReady = gameDetected && (
                    targets.isBetBtnDetected ||
                        targets.isCancelBtnDetected ||
                        targets.isCashOutBtnDetected ||
                        targets.detectedMultiplier != null ||
                        targets.ribbonMultipliers.isNotEmpty()
                    ),
                gamePhase = targets.gamePhase,
                missingElements = missing,
                detectedMultiplier = targets.detectedMultiplier,
                foregroundPackage = packageName,
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
