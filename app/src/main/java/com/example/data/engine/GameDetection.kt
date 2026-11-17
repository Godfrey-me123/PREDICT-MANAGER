package com.example.data.engine

import com.example.data.model.ActionExecutionRecord
import com.example.data.model.BalanceDetectionStatus
import com.example.data.model.BalanceState
import com.example.data.model.CalendarHourUtils
import com.example.data.model.CollectionSource
import com.example.data.model.ColorClassifier
import com.example.data.model.CrashRecord
import com.example.data.model.GameState
import com.example.data.model.HistoryCollectionStatus
import com.example.data.model.MonitoringPipelineStatus
import com.example.data.model.MultiplierRecord
import com.example.data.model.SiteButtonState
import com.example.service.GameDetectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

data class ConfirmedCrashEmission(
    val multiplier: Double,
    val crashRecord: CrashRecord,
    val multiplierRecord: MultiplierRecord
)

class GameDetection {

    companion object {
        private const val RIBBON_DUPLICATE_GUARD_WINDOW_MS = 15_000L
        private const val BALANCE_STALENESS_THRESHOLD_MS = 90_000L
        private const val BALANCE_INITIAL_SCAN_TIMEOUT_MS = 6_000L

        fun resolvePlatformFromSignals(
            packageName: String,
            detectedUrl: String?,
            pageHints: List<String>
        ): String? {
            val urlLower = detectedUrl?.lowercase(Locale.US).orEmpty()
            val pkgLower = packageName.lowercase(Locale.US)
            val hintsLower = pageHints.joinToString(" ").lowercase(Locale.US)

            val gameTitle = when {
                urlLower.contains("jetx") || hintsLower.contains("jetx") || hintsLower.contains("smartsoft") -> "JetX"
                urlLower.contains("spaceman") || hintsLower.contains("spaceman") || hintsLower.contains("pragmatic") -> "Spaceman"
                urlLower.contains("aviator") || urlLower.contains("spribe") || hintsLower.contains("aviator") || hintsLower.contains("spribe") || hintsLower.contains("flew away") -> "Aviator"
                else -> ""
            }
            val suffix = if (gameTitle.isNotBlank()) " $gameTitle" else ""

            return when {
                urlLower.contains("betpawa") -> "BetPawa$suffix"
                urlLower.contains("sportybet") -> "SportyBet$suffix"
                pkgLower.contains("betpawa") || hintsLower.contains("betpawa") -> "BetPawa$suffix"
                pkgLower.contains("sportybet") || hintsLower.contains("sportybet") -> "SportyBet$suffix"
                (pkgLower.contains("chrome") || pkgLower.contains("browser")) && gameTitle.isNotBlank() ->
                    "Chrome $gameTitle Web"
                else -> null
            }
        }

        fun mapPipelineToMonitoringStatus(status: MonitoringPipelineStatus): com.example.data.model.MonitoringStatus {
            return when (status) {
                MonitoringPipelineStatus.MONITORING_LIVE,
                MonitoringPipelineStatus.BASELINE_CAPTURED_WAITING_FOR_NEW_CRASH,
                MonitoringPipelineStatus.GAME_DETECTED_WAITING_FOR_HISTORY ->
                    com.example.data.model.MonitoringStatus.MONITORING
                MonitoringPipelineStatus.WAITING_FOR_GAME ->
                    com.example.data.model.MonitoringStatus.WAITING_FOR_GAME
                MonitoringPipelineStatus.PAUSED ->
                    com.example.data.model.MonitoringStatus.PAUSED
                MonitoringPipelineStatus.STOPPED ->
                    com.example.data.model.MonitoringStatus.STOPPED
                MonitoringPipelineStatus.ACCESSIBILITY_DISCONNECTED ->
                    com.example.data.model.MonitoringStatus.PERMISSION_REQUIRED
                MonitoringPipelineStatus.HISTORY_NOT_READABLE ->
                    com.example.data.model.MonitoringStatus.SERVICE_INTERRUPTED
            }
        }

        fun resolveProfileIdForPlatform(platformName: String): String? {
            val lower = platformName.lowercase(Locale.US)
            return when {
                lower.contains("jetx") -> "smartsoft_jetx"
                lower.contains("spaceman") -> "pragmatic_spaceman"
                lower.contains("betpawa") -> "betpawa_aviator"
                lower.contains("sportybet") -> "sportybet_aviator"
                else -> null
            }
        }

        fun resolveSiteKey(platformName: String): String {
            val lower = platformName.lowercase(Locale.US)
            return when {
                lower.contains("betpawa") -> "BetPawa"
                lower.contains("sportybet") -> "SportyBet"
                else -> ""
            }
        }

        fun detectNewRibbonHead(
            previousRibbon: List<Double>,
            currentRibbon: List<Double>,
            sawInFlightOrCrashSinceSync: Boolean = false
        ): Double? {
            if (currentRibbon.isEmpty() || previousRibbon.isEmpty()) return null
            val newHead = currentRibbon.first()
            val prevHead = previousRibbon.first()

            if (abs(newHead - prevHead) > 0.009) {
                return newHead
            }

            if (sawInFlightOrCrashSinceSync && currentRibbon.size >= 2 && previousRibbon.size >= 2) {
                val overlap = min(3, min(currentRibbon.size - 1, previousRibbon.size))
                val shifted = currentRibbon.drop(1).take(overlap) == previousRibbon.take(overlap)
                if (shifted) {
                    return newHead
                }
            }
            return null
        }
    }

    private val _gameState = MutableStateFlow(
        GameState(
            isDetected = false,
            platformName = "Waiting for Game...",
            displayedGameName = "Waiting for Game...",
            status = "WAITING_FOR_GAME",
            siteButtonState = SiteButtonState.UNKNOWN,
            currentMultiplier = 0.0,
            multiplierHistory = emptyList(),
            balanceStatus = BalanceDetectionStatus.SCANNING
        )
    )
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    private val _balanceState = MutableStateFlow(
        BalanceState(status = BalanceDetectionStatus.SCANNING)
    )
    val balanceState: StateFlow<BalanceState> = _balanceState.asStateFlow()

    private val _historyMetadata = MutableStateFlow(HistoryCollectionMetadata())
    val historyMetadata: StateFlow<HistoryCollectionMetadata> = _historyMetadata.asStateFlow()

    val ribbonHistoryCollector = RibbonHistoryCollector()

    private var scanStartTimeMs: Long = 0L
    private var monitoringSessionStartedMs: Long = 0L
    private var lastInFlightMultiplier: Double? = null
    private var lastInFlightColorHex: String? = null
    private var lastInFlightColorVerified: Boolean = false
    private var hasSettledCurrentCrash: Boolean = false
    private var lastSettledRoundValue: Double? = null
    private var lastSettledTimestampMs: Long = 0L
    private var lastObservedRibbon: List<Double> = emptyList()
    private var sawActiveRoundSinceRibbonSync: Boolean = false
    private var gameDetectedWithoutHistorySinceMs: Long = 0L

    fun updatePipelineStatus(
        pipelineStatus: MonitoringPipelineStatus,
        reason: String,
        sessionId: String = _gameState.value.activeSessionId,
        sessionCrashCount: Int = _gameState.value.activeSessionCrashCount,
        latestRoundId: String? = _gameState.value.latestRoundId,
        monitoringStatusOverride: com.example.data.model.MonitoringStatus? = null
    ) {
        val current = _gameState.value
        val mappedStatus = monitoringStatusOverride ?: mapPipelineToMonitoringStatus(pipelineStatus)
        _gameState.value = current.copy(
            pipelineStatus = pipelineStatus,
            monitoringStatus = mappedStatus,
            pipelineDiagnosticReason = reason,
            activeSessionId = sessionId,
            activeSessionCrashCount = sessionCrashCount,
            sessionCrashesRecorded = sessionCrashCount,
            latestRoundId = latestRoundId,
            currentRoundId = latestRoundId
        )
    }

    fun updateScreenAwakeState(wakeLockActive: Boolean, keepScreenOnApplied: Boolean) {
        val current = _gameState.value
        if (current.isWakeLockActive != wakeLockActive || current.isKeepScreenOnFlagApplied != keepScreenOnApplied) {
            _gameState.value = current.copy(
                isWakeLockActive = wakeLockActive,
                isKeepScreenOnFlagApplied = keepScreenOnApplied
            )
        }
    }

    fun updateAccessibilityConnected(connected: Boolean) {
        val current = _gameState.value
        if (current.isAccessibilityConnected != connected) {
            _gameState.value = current.copy(isAccessibilityConnected = connected)
        }
    }

    fun restoreSessionSnapshot(sessionId: String, records: List<CrashRecord>) {
        val sortedAsc = records.sortedWith(compareBy<CrashRecord> { it.crashTimestamp }.thenBy { it.id })
        val newestFirstMults = sortedAsc.asReversed().map { it.multiplier }.take(50)
        val latest = sortedAsc.lastOrNull()
        val resolvedRound = latest?.roundIdentifier ?: _gameState.value.latestRoundId
        val current = _gameState.value
        _gameState.value = current.copy(
            activeSessionId = sessionId,
            activeSessionCrashCount = sortedAsc.size,
            sessionCrashesRecorded = sortedAsc.size,
            multiplierHistory = newestFirstMults,
            latestCrashMultiplier = latest?.multiplier,
            latestCrashTimestampMs = latest?.crashTimestamp ?: 0L,
            latestCrashColorHex = latest?.detectedColorHex ?: com.example.data.model.CrashColorCategory.Other.defaultHex,
            latestCrashColorCategory = latest?.colorCategory ?: com.example.data.model.CrashColorCategory.Other,
            isLatestCrashColorVerified = latest?.isPixelColorVerified == true,
            latestRoundId = resolvedRound,
            currentRoundId = resolvedRound
        )
    }

    fun resetLiveSessionTelemetry(sessionId: String = "", clearLastInFlight: Boolean = true) {
        if (clearLastInFlight) {
            lastInFlightMultiplier = null
            lastInFlightColorHex = null
            lastInFlightColorVerified = false
            hasSettledCurrentCrash = false
            sawActiveRoundSinceRibbonSync = false
            lastSettledRoundValue = null
            lastSettledTimestampMs = 0L
        }
        lastObservedRibbon = emptyList()
        gameDetectedWithoutHistorySinceMs = 0L
        ribbonHistoryCollector.reset()
        val current = _gameState.value
        _gameState.value = current.copy(
            activeSessionId = sessionId,
            activeSessionCrashCount = 0,
            sessionCrashesRecorded = 0,
            latestCrashMultiplier = null,
            latestCrashTimestampMs = 0L,
            latestRoundId = null,
            currentRoundId = null,
            multiplierHistory = emptyList(),
            recentRibbonHistory = emptyList(),
            latestCrashColorHex = com.example.data.model.CrashColorCategory.Other.defaultHex,
            latestCrashColorCategory = com.example.data.model.CrashColorCategory.Other,
            isLatestCrashColorVerified = false
        )
    }

    fun recordConfirmedLiveCrash(
        crashRecord: CrashRecord,
        sessionCrashCount: Int
    ) {
        lastSettledRoundValue = crashRecord.multiplier
        lastSettledTimestampMs = crashRecord.crashTimestamp
        sawActiveRoundSinceRibbonSync = false
        val current = _gameState.value
        val updatedHistory = (listOf(crashRecord.multiplier) + current.multiplierHistory).take(50)
        val resolvedRound = crashRecord.roundIdentifier ?: current.latestRoundId
        _gameState.value = current.copy(
            activeSessionId = crashRecord.sessionId.ifBlank { current.activeSessionId },
            activeSessionCrashCount = sessionCrashCount,
            sessionCrashesRecorded = sessionCrashCount,
            latestCrashMultiplier = crashRecord.multiplier,
            latestCrashTimestampMs = crashRecord.crashTimestamp,
            latestCrashColorHex = crashRecord.detectedColorHex,
            latestCrashColorCategory = crashRecord.colorCategory,
            isLatestCrashColorVerified = crashRecord.isPixelColorVerified,
            latestRoundId = resolvedRound,
            currentRoundId = resolvedRound,
            multiplierHistory = updatedHistory
        )
    }

    /**
     * Authoritative observation-only UI telemetry update.
     * Tracks game detection, phase, rising in-flight multiplier for live header display, balance,
     * and round lifecycle transition flags — NEVER inserts DB rows or mutates latestCrashMultiplier.
     *
     * @return Pair(sawRoundCompletionTransitionSinceSync, gameDetectedWithoutHistoryElapsedMs)
     */
    fun observeLiveUiSnapshot(
        detection: GameDetectionState,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Pair<Boolean, Long> {
        val current = _gameState.value
        if (!detection.isGameDetected) {
            scanStartTimeMs = 0L
            monitoringSessionStartedMs = 0L
            gameDetectedWithoutHistorySinceMs = 0L
            _balanceState.value = BalanceState(
                status = BalanceDetectionStatus.UNAVAILABLE,
                amount = null,
                currency = null,
                lastReadTimestampMs = current.balanceLastUpdatedMs,
                failureReason = "No supported game detected"
            )
            _gameState.value = current.copy(
                isDetected = false,
                displayedGameName = "Waiting for Game...",
                platformName = "Waiting for Game...",
                sourcePackage = "",
                detectedUrl = null,
                status = "NO_GAME_DETECTED",
                liveDetectionLost = false,
                detectionConfidence = 0f,
                monitoringStartedAtMs = 0L,
                siteButtonState = SiteButtonState.UNKNOWN,
                currentMultiplier = 0.0,
                isStakeInputDetected = false,
                isDoubleBtnDetected = false,
                isAddBtnDetected = false,
                isBetBtnDetected = false,
                isCashOutBtnDetected = false,
                detectedStakeText = null,
                detectedBalance = null,
                balanceStatus = BalanceDetectionStatus.UNAVAILABLE,
                balanceFailureReason = "No supported game detected"
            )
            return sawActiveRoundSinceRibbonSync to 0L
        }

        if (scanStartTimeMs == 0L) scanStartTimeMs = currentTimeMs
        if (monitoringSessionStartedMs == 0L) monitoringSessionStartedMs = currentTimeMs

        val targets = detection.uiTargets
        val resolvedPlatform = resolvePlatformFromSignals(
            packageName = detection.foregroundPackage,
            detectedUrl = targets.detectedUrl,
            pageHints = targets.pageTextHints
        ) ?: detection.platformName

        val resolvedGameName = detection.displayedGameName.ifBlank { resolvedPlatform }
        val multiplier = detection.detectedMultiplier
        val phase = detection.gamePhase

        when (phase) {
            "IN_FLIGHT" -> {
                sawActiveRoundSinceRibbonSync = true
                if (multiplier != null && multiplier >= 1.0) {
                    lastInFlightMultiplier = multiplier
                    if (!detection.liveColorHex.isNullOrBlank()) {
                        lastInFlightColorHex = detection.liveColorHex
                        lastInFlightColorVerified = detection.isLiveColorPixelVerified
                    }
                }
            }
            "CRASHED" -> {
                sawActiveRoundSinceRibbonSync = true
                lastInFlightMultiplier = null
                lastInFlightColorHex = null
                lastInFlightColorVerified = false
            }
            "BETTING_OPEN" -> {
                if (lastInFlightMultiplier != null) {
                    sawActiveRoundSinceRibbonSync = true
                }
                lastInFlightMultiplier = null
                lastInFlightColorHex = null
                lastInFlightColorVerified = false
            }
        }

        val displayMultiplier = when {
            detection.liveDetectionLost || phase == "LIVE_DETECTION_LOST" -> 0.0
            phase == "IN_FLIGHT" -> multiplier?.takeIf { it >= 1.0 } ?: 0.0
            phase == "CRASHED" -> multiplier ?: lastSettledRoundValue ?: 0.0
            else -> 0.0
        }

        val hasRibbon = targets.ribbonItems.isNotEmpty() || targets.ribbonMultipliers.isNotEmpty()
        if (!hasRibbon) {
            if (gameDetectedWithoutHistorySinceMs == 0L) {
                gameDetectedWithoutHistorySinceMs = currentTimeMs
            }
        } else {
            gameDetectedWithoutHistorySinceMs = 0L
        }
        val missingHistoryElapsed = if (gameDetectedWithoutHistorySinceMs > 0L) {
            (currentTimeMs - gameDetectedWithoutHistorySinceMs).coerceAtLeast(0L)
        } else 0L

        val incomingBalance = targets.detectedBalance
        val incomingCurrency = targets.detectedCurrency ?: current.detectedCurrency
        val resolution = when {
            incomingBalance != null && incomingBalance.isFinite() && incomingBalance >= 0.0 -> {
                BalanceResolution(incomingBalance, BalanceDetectionStatus.VERIFIED, currentTimeMs, null)
            }
            current.detectedBalance != null && current.balanceLastUpdatedMs > 0L -> {
                val age = currentTimeMs - current.balanceLastUpdatedMs
                if (age > BALANCE_STALENESS_THRESHOLD_MS) {
                    BalanceResolution(
                        current.detectedBalance,
                        BalanceDetectionStatus.STALE,
                        current.balanceLastUpdatedMs,
                        "Balance reading is older than 90s; refresh game page"
                    )
                } else {
                    BalanceResolution(
                        current.detectedBalance,
                        current.balanceStatus,
                        current.balanceLastUpdatedMs,
                        current.balanceFailureReason
                    )
                }
            }
            currentTimeMs - scanStartTimeMs > BALANCE_INITIAL_SCAN_TIMEOUT_MS -> {
                BalanceResolution(null, BalanceDetectionStatus.UNAVAILABLE, 0L, "Balance element not visible in game interface")
            }
            else -> {
                BalanceResolution(null, BalanceDetectionStatus.SCANNING, 0L, null)
            }
        }

        _balanceState.value = BalanceState(
            status = resolution.status,
            amount = resolution.balance,
            currency = incomingCurrency,
            lastReadTimestampMs = resolution.lastUpdatedMs,
            failureReason = resolution.failureReason
        )

        val liveRoundId = detection.detectedRoundId ?: detection.roundId ?: targets.detectedCurrentRoundId ?: current.latestRoundId

        _gameState.value = current.copy(
            isDetected = true,
            gameId = detection.gameId.ifBlank { "unknown" },
            displayedGameName = resolvedGameName.ifBlank { "Unknown" },
            originalDetectedTitle = detection.originalDetectedTitle,
            supportedPlatforms = detection.supportedPlatforms,
            platformName = resolvedPlatform.ifBlank { "Unknown" },
            sourcePackage = detection.foregroundPackage,
            detectedUrl = targets.detectedUrl ?: current.detectedUrl,
            status = if (detection.liveDetectionLost) "LIVE_DETECTION_LOST" else phase,
            liveDetectionLost = detection.liveDetectionLost,
            lastDetectionTimestampMs = if (!detection.liveDetectionLost) detection.updatedAtMs else current.lastDetectionTimestampMs,
            detectionConfidence = detection.detectionConfidence,
            monitoringStartedAtMs = monitoringSessionStartedMs,
            siteButtonState = targets.siteButtonState,
            currentMultiplier = displayMultiplier,
            latestRoundId = liveRoundId,
            currentRoundId = liveRoundId,
            recentRibbonHistory = if (targets.ribbonMultipliers.isNotEmpty()) targets.ribbonMultipliers else current.recentRibbonHistory,
            isStakeInputDetected = targets.isStakeInputDetected,
            isDoubleBtnDetected = targets.isDoubleBtnDetected,
            isAddBtnDetected = targets.isAddBtnDetected,
            isBetBtnDetected = targets.isBetBtnDetected,
            isCashOutBtnDetected = targets.isCashOutBtnDetected,
            detectedStakeText = targets.detectedStakeText,
            detectedBalance = resolution.balance,
            detectedCurrency = incomingCurrency,
            balanceStatus = resolution.status,
            balanceLastUpdatedMs = resolution.lastUpdatedMs,
            balanceFailureReason = resolution.failureReason
        )

        return sawActiveRoundSinceRibbonSync to missingHistoryElapsed
    }

    fun updateHistoryStatus(
        status: HistoryCollectionStatus,
        reason: String,
        recordsInWindow: List<MultiplierRecord> = emptyList(),
        nowMs: Long = System.currentTimeMillis()
    ) {
        val current = _historyMetadata.value
        val sortedDesc = recordsInWindow.sortedByDescending { it.crashTimestamp }
        val latest = sortedDesc.firstOrNull()
        val earliest = sortedDesc.lastOrNull()
        _historyMetadata.value = current.copy(
            status = status,
            statusReason = reason,
            totalCollectedInWindow = if (recordsInWindow.isNotEmpty()) recordsInWindow.size else current.totalCollectedInWindow,
            latestMultiplier = latest?.multiplier ?: current.latestMultiplier,
            latestRecordTimestampMs = latest?.crashTimestamp ?: current.latestRecordTimestampMs,
            latestTimestampSource = latest?.timestampSource ?: current.latestTimestampSource,
            earliestTimestampMs = earliest?.crashTimestamp ?: current.earliestTimestampMs,
            lastCollectionAttemptMs = nowMs,
            hasGapsOrPartial = status != HistoryCollectionStatus.HISTORY_COMPLETE ||
                recordsInWindow.any { it.hasGapBefore }
        )
    }

    fun refreshHistoryMetadataFromRecords(
        recordsIn30mWindow: List<MultiplierRecord>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val current = _historyMetadata.value
        val sortedDesc = recordsIn30mWindow.sortedByDescending { it.crashTimestamp }
        val latest = sortedDesc.firstOrNull()
        val earliest = sortedDesc.lastOrNull()
        _historyMetadata.value = current.copy(
            totalCollectedInWindow = recordsIn30mWindow.size,
            latestMultiplier = latest?.multiplier ?: current.latestMultiplier,
            latestRecordTimestampMs = latest?.crashTimestamp ?: current.latestRecordTimestampMs,
            latestTimestampSource = latest?.timestampSource ?: current.latestTimestampSource,
            earliestTimestampMs = earliest?.crashTimestamp ?: current.earliestTimestampMs,
            hasGapsOrPartial = current.status != HistoryCollectionStatus.HISTORY_COMPLETE ||
                recordsIn30mWindow.any { it.hasGapBefore }
        )
    }

    fun collectVisibleRibbonHistory(
        detection: GameDetectionState,
        skipHeadIfJustSettled: Boolean = false,
        currentTimeMs: Long = System.currentTimeMillis(),
        lowThreshold: Double = 2.00,
        bigThreshold: Double = 10.00
    ): Pair<List<MultiplierRecord>, List<CrashRecord>> {
        val targets = detection.uiTargets
        val items = if (targets.ribbonItems.isNotEmpty()) {
            targets.ribbonItems
        } else {
            targets.ribbonMultipliers.mapIndexed { idx, value ->
                ExtractedHistoryItem(multiplier = value, orderIndex = idx, source = CollectionSource.VISIBLE_RIBBON)
            }
        }

        if (!detection.isGameDetected || items.isEmpty()) {
            _historyMetadata.value = _historyMetadata.value.copy(
                status = if (!detection.isGameDetected) HistoryCollectionStatus.HISTORY_NOT_STARTED else HistoryCollectionStatus.HISTORY_UNAVAILABLE,
                statusReason = if (!detection.isGameDetected) {
                    "Waiting for active crash game window."
                } else {
                    "No visible ribbon multipliers in game interface."
                },
                totalVisibleInRibbon = 0,
                hasExpandableHistoryControl = targets.hasExpandableHistoryControl,
                hasScrollableHistoryContainer = targets.hasScrollableHistoryContainer,
                isHistoryPanelExpanded = targets.historyPanelExpanded
            )
            return emptyList<MultiplierRecord>() to emptyList()
        }

        val resolvedPlatform = resolvePlatformFromSignals(
            packageName = detection.foregroundPackage,
            detectedUrl = targets.detectedUrl,
            pageHints = targets.pageTextHints
        ) ?: detection.platformName

        val syncResult = ribbonHistoryCollector.processVisibleRibbonSnapshot(
            items = items,
            platformName = resolvedPlatform,
            gameId = detection.gameId,
            isPanelExpanded = targets.historyPanelExpanded,
            hasExpandableControl = targets.hasExpandableHistoryControl,
            nowMs = currentTimeMs
        )

        val filteredRecords = if (skipHeadIfJustSettled &&
            syncResult.newRecordsToPersist.size == 1 &&
            lastSettledRoundValue != null &&
            abs(lastSettledRoundValue!! - syncResult.newRecordsToPersist.first().multiplier) < 0.01 &&
            (currentTimeMs - lastSettledTimestampMs) in 0L..RIBBON_DUPLICATE_GUARD_WINDOW_MS
        ) {
            emptyList()
        } else {
            syncResult.newRecordsToPersist
        }

        val itemByMultIdx = items
        val crashRecords = filteredRecords.mapIndexed { idx, rec ->
            val srcItem = itemByMultIdx.getOrNull(idx)
            val fallbackCat = ColorClassifier.fallbackCategoryForMultiplier(rec.multiplier, lowThreshold, bigThreshold)
            val cat = srcItem?.colorCategory ?: fallbackCat
            val hex = srcItem?.detectedColorHex ?: cat.defaultHex
            CrashRecord(
                gameId = detection.gameId,
                gameName = detection.displayedGameName.ifBlank { resolvedPlatform },
                multiplier = rec.multiplier,
                detectedColorHex = hex,
                colorCategory = cat,
                isPixelColorVerified = srcItem?.isPixelColorVerified == true,
                crashTimestamp = rec.crashTimestamp,
                hourStartTimestamp = CalendarHourUtils.hourStartFor(rec.crashTimestamp),
                sourcePackage = detection.foregroundPackage,
                roundIdentifier = rec.roundId,
                confidence = detection.detectionConfidence.coerceAtLeast(0.75f),
                manuallyCorrected = false,
                collectionSource = rec.collectionSource,
                dedupKey = rec.dedupKey
            )
        }

        val currentMeta = _historyMetadata.value
        _historyMetadata.value = currentMeta.copy(
            status = if (currentMeta.status == HistoryCollectionStatus.HISTORY_COMPLETE && syncResult.isRepeatedSnapshot) {
                HistoryCollectionStatus.HISTORY_COMPLETE
            } else {
                syncResult.status
            },
            statusReason = syncResult.reason,
            totalVisibleInRibbon = items.size,
            lastCollectionAttemptMs = currentTimeMs,
            hasGapsOrPartial = syncResult.status != HistoryCollectionStatus.HISTORY_COMPLETE,
            hasExpandableHistoryControl = targets.hasExpandableHistoryControl,
            hasScrollableHistoryContainer = targets.hasScrollableHistoryContainer,
            isHistoryPanelExpanded = targets.historyPanelExpanded
        )

        return filteredRecords to crashRecords
    }

    fun retryBalanceDetection(currentTimeMs: Long = System.currentTimeMillis()) {
        scanStartTimeMs = currentTimeMs
        val resetState = BalanceState(
            status = BalanceDetectionStatus.SCANNING,
            amount = null,
            currency = _gameState.value.detectedCurrency,
            lastReadTimestampMs = 0L,
            failureReason = null
        )
        _balanceState.value = resetState
        _gameState.value = _gameState.value.copy(
            detectedBalance = null,
            balanceStatus = BalanceDetectionStatus.SCANNING,
            balanceFailureReason = null
        )
    }

    fun setVerifiedBalance(
        balance: Double,
        currency: String = "GHS",
        currentTimeMs: Long = System.currentTimeMillis()
    ) {
        val state = BalanceState(
            status = BalanceDetectionStatus.VERIFIED,
            amount = balance,
            currency = currency,
            lastReadTimestampMs = currentTimeMs,
            failureReason = null
        )
        _balanceState.value = state
        _gameState.value = _gameState.value.copy(
            detectedBalance = balance,
            detectedCurrency = currency,
            balanceStatus = BalanceDetectionStatus.VERIFIED,
            balanceLastUpdatedMs = currentTimeMs,
            balanceFailureReason = null
        )
    }

    fun setUnavailableBalance(reason: String = "Balance not visible on screen") {
        val state = BalanceState(
            status = BalanceDetectionStatus.UNAVAILABLE,
            amount = null,
            currency = _gameState.value.detectedCurrency,
            lastReadTimestampMs = 0L,
            failureReason = reason
        )
        _balanceState.value = state
        _gameState.value = _gameState.value.copy(
            detectedBalance = null,
            balanceStatus = BalanceDetectionStatus.UNAVAILABLE,
            balanceFailureReason = reason
        )
    }

    fun checkBalanceStaleness(
        currentTimeMs: Long = System.currentTimeMillis(),
        stalenessThresholdMs: Long = BALANCE_STALENESS_THRESHOLD_MS
    ) {
        val curr = _balanceState.value
        if ((curr.status == BalanceDetectionStatus.VERIFIED || curr.status == BalanceDetectionStatus.DETECTED) &&
            curr.lastUpdatedMs > 0L
        ) {
            if (currentTimeMs - curr.lastUpdatedMs > stalenessThresholdMs) {
                val stale = curr.copy(
                    status = BalanceDetectionStatus.STALE,
                    failureReason = "Balance reading is older than ${stalenessThresholdMs / 1000}s; refresh game page"
                )
                _balanceState.value = stale
                _gameState.value = _gameState.value.copy(
                    balanceStatus = BalanceDetectionStatus.STALE,
                    balanceFailureReason = stale.failureReason
                )
            }
        }
    }

    fun updateExecutionRecord(record: ActionExecutionRecord) {
        _gameState.value = _gameState.value.copy(
            executionState = record.state,
            lastActionRecord = record
        )
    }

    fun updateFromAccessibilityDetection(
        detection: GameDetectionState,
        onRealRoundCompleted: (Double) -> Unit,
        onPhaseUpdate: ((phase: String, liveMultiplier: Double) -> Unit)? = null,
        currentTimeMs: Long = System.currentTimeMillis(),
        lowThreshold: Double = 2.00,
        bigThreshold: Double = 10.00
    ) {
        val current = _gameState.value

        if (!detection.isGameDetected) {
            scanStartTimeMs = 0L
            monitoringSessionStartedMs = 0L
            val unavailableState = BalanceState(
                status = BalanceDetectionStatus.UNAVAILABLE,
                amount = null,
                currency = null,
                lastReadTimestampMs = current.balanceLastUpdatedMs,
                failureReason = "No supported game detected"
            )
            _balanceState.value = unavailableState
            _gameState.value = current.copy(
                isDetected = false,
                displayedGameName = "Waiting for Game...",
                platformName = "Waiting for Game...",
                sourcePackage = "",
                detectedUrl = null,
                status = "NO_GAME_DETECTED",
                liveDetectionLost = false,
                detectionConfidence = 0f,
                monitoringStartedAtMs = 0L,
                siteButtonState = SiteButtonState.UNKNOWN,
                currentMultiplier = 0.0,
                isStakeInputDetected = false,
                isDoubleBtnDetected = false,
                isAddBtnDetected = false,
                isBetBtnDetected = false,
                isCashOutBtnDetected = false,
                detectedStakeText = null,
                detectedBalance = null,
                balanceStatus = BalanceDetectionStatus.UNAVAILABLE,
                balanceFailureReason = "No supported game detected"
            )
            return
        }

        if (scanStartTimeMs == 0L) scanStartTimeMs = currentTimeMs
        if (monitoringSessionStartedMs == 0L) monitoringSessionStartedMs = currentTimeMs

        val targets = detection.uiTargets
        val resolvedPlatform = resolvePlatformFromSignals(
            packageName = detection.foregroundPackage,
            detectedUrl = targets.detectedUrl,
            pageHints = targets.pageTextHints
        ) ?: detection.platformName

        val resolvedGameName = detection.displayedGameName.ifBlank { resolvedPlatform }
        val multiplier = detection.detectedMultiplier
        val phase = detection.gamePhase
        var updatedHistory = current.multiplierHistory
        var latestCrashVal = current.latestCrashMultiplier
        var latestCrashTs = current.latestCrashTimestampMs
        var latestCrashHex = current.latestCrashColorHex
        var latestCrashCat = current.latestCrashColorCategory
        var latestCrashVerified = current.isLatestCrashColorVerified

        fun recordCompletedRound(crashedValue: Double, headItem: ExtractedHistoryItem? = null) {
            hasSettledCurrentCrash = true
            lastSettledRoundValue = crashedValue
            lastSettledTimestampMs = currentTimeMs
            sawActiveRoundSinceRibbonSync = false
            updatedHistory = (listOf(crashedValue) + updatedHistory).take(50)

            val fallbackCat = ColorClassifier.fallbackCategoryForMultiplier(crashedValue, lowThreshold, bigThreshold)
            val resolvedHex = headItem?.detectedColorHex
                ?: lastInFlightColorHex
                ?: detection.liveColorHex
                ?: fallbackCat.defaultHex
            val resolvedVerified = (headItem?.isPixelColorVerified == true) ||
                lastInFlightColorVerified ||
                detection.isLiveColorPixelVerified
            val resolvedCat = headItem?.colorCategory
                ?: if (resolvedVerified) ColorClassifier.classifyHex(resolvedHex) else fallbackCat

            latestCrashVal = crashedValue
            latestCrashTs = currentTimeMs
            latestCrashHex = resolvedHex
            latestCrashCat = resolvedCat
            latestCrashVerified = resolvedVerified

            onRealRoundCompleted(crashedValue)
        }

        when (phase) {
            "IN_FLIGHT" -> {
                hasSettledCurrentCrash = false
                sawActiveRoundSinceRibbonSync = true
                if (multiplier != null && multiplier >= 1.0) {
                    lastInFlightMultiplier = multiplier
                    if (!detection.liveColorHex.isNullOrBlank()) {
                        lastInFlightColorHex = detection.liveColorHex
                        lastInFlightColorVerified = detection.isLiveColorPixelVerified
                    }
                }
            }
            "CRASHED" -> {
                sawActiveRoundSinceRibbonSync = true
                val crashedValue = multiplier ?: lastInFlightMultiplier
                if (!hasSettledCurrentCrash && crashedValue != null && crashedValue >= 1.0) {
                    recordCompletedRound(crashedValue)
                }
                lastInFlightMultiplier = null
                lastInFlightColorHex = null
                lastInFlightColorVerified = false
            }
            "BETTING_OPEN" -> {
                val prev = lastInFlightMultiplier
                if (!hasSettledCurrentCrash && prev != null && prev >= 1.0) {
                    recordCompletedRound(prev)
                }
                lastInFlightMultiplier = null
                lastInFlightColorHex = null
                lastInFlightColorVerified = false
            }
        }

        val incomingRibbon = targets.ribbonMultipliers
        if (incomingRibbon.isNotEmpty()) {
            val newRibbonHead = detectNewRibbonHead(
                previousRibbon = lastObservedRibbon,
                currentRibbon = incomingRibbon,
                sawInFlightOrCrashSinceSync = sawActiveRoundSinceRibbonSync
            )
            if (newRibbonHead != null && newRibbonHead >= 1.0) {
                val alreadyRecordedByCrash = lastSettledRoundValue != null &&
                    abs(lastSettledRoundValue!! - newRibbonHead) < 0.01 &&
                    (currentTimeMs - lastSettledTimestampMs) in 0L..RIBBON_DUPLICATE_GUARD_WINDOW_MS
                if (!alreadyRecordedByCrash) {
                    recordCompletedRound(newRibbonHead, targets.ribbonItems.firstOrNull())
                } else {
                    sawActiveRoundSinceRibbonSync = false
                }
            }
            lastObservedRibbon = incomingRibbon
        }

        // Never freeze stale live multiplier on LIVE_DETECTION_LOST
        val displayMultiplier = when {
            detection.liveDetectionLost || phase == "LIVE_DETECTION_LOST" -> 0.0
            phase == "IN_FLIGHT" -> multiplier?.takeIf { it >= 1.0 } ?: 0.0
            phase == "CRASHED" -> multiplier ?: lastSettledRoundValue ?: lastInFlightMultiplier ?: 0.0
            else -> 0.0
        }

        val incomingBalance = targets.detectedBalance
        val incomingCurrency = targets.detectedCurrency ?: current.detectedCurrency

        val resolution = when {
            incomingBalance != null && incomingBalance.isFinite() && incomingBalance >= 0.0 -> {
                BalanceResolution(incomingBalance, BalanceDetectionStatus.VERIFIED, currentTimeMs, null)
            }
            current.detectedBalance != null && current.balanceLastUpdatedMs > 0L -> {
                val age = currentTimeMs - current.balanceLastUpdatedMs
                if (age > BALANCE_STALENESS_THRESHOLD_MS) {
                    BalanceResolution(
                        current.detectedBalance,
                        BalanceDetectionStatus.STALE,
                        current.balanceLastUpdatedMs,
                        "Balance reading is older than 90s; refresh game page"
                    )
                } else {
                    BalanceResolution(
                        current.detectedBalance,
                        current.balanceStatus,
                        current.balanceLastUpdatedMs,
                        current.balanceFailureReason
                    )
                }
            }
            currentTimeMs - scanStartTimeMs > BALANCE_INITIAL_SCAN_TIMEOUT_MS -> {
                BalanceResolution(null, BalanceDetectionStatus.UNAVAILABLE, 0L, "Balance element not visible in game interface")
            }
            else -> {
                BalanceResolution(null, BalanceDetectionStatus.SCANNING, 0L, null)
            }
        }

        val updatedBalanceState = BalanceState(
            status = resolution.status,
            amount = resolution.balance,
            currency = incomingCurrency,
            lastReadTimestampMs = resolution.lastUpdatedMs,
            failureReason = resolution.failureReason
        )
        _balanceState.value = updatedBalanceState

        val nextState = current.copy(
            isDetected = true,
            gameId = detection.gameId,
            displayedGameName = resolvedGameName,
            supportedPlatforms = detection.supportedPlatforms,
            platformName = resolvedPlatform,
            sourcePackage = detection.foregroundPackage,
            detectedUrl = targets.detectedUrl ?: current.detectedUrl,
            status = if (detection.liveDetectionLost) "LIVE_DETECTION_LOST" else phase,
            liveDetectionLost = detection.liveDetectionLost,
            lastDetectionTimestampMs = if (!detection.liveDetectionLost) detection.updatedAtMs else current.lastDetectionTimestampMs,
            detectionConfidence = detection.detectionConfidence,
            monitoringStartedAtMs = monitoringSessionStartedMs,
            siteButtonState = targets.siteButtonState,
            currentMultiplier = displayMultiplier,
            latestCrashMultiplier = latestCrashVal,
            latestCrashTimestampMs = latestCrashTs,
            latestCrashColorHex = latestCrashHex,
            latestCrashColorCategory = latestCrashCat,
            isLatestCrashColorVerified = latestCrashVerified,
            multiplierHistory = updatedHistory,
            recentRibbonHistory = incomingRibbon.ifEmpty { current.recentRibbonHistory },
            isStakeInputDetected = targets.isStakeInputDetected,
            isDoubleBtnDetected = targets.isDoubleBtnDetected,
            isAddBtnDetected = targets.isAddBtnDetected,
            isBetBtnDetected = targets.isBetBtnDetected,
            isCashOutBtnDetected = targets.isCashOutBtnDetected,
            isCashOutAvailable = false,
            detectedStakeText = targets.detectedStakeText,
            detectedBalance = resolution.balance,
            detectedCurrency = incomingCurrency,
            balanceStatus = resolution.status,
            balanceLastUpdatedMs = resolution.lastUpdatedMs,
            balanceFailureReason = resolution.failureReason
        )
        _gameState.value = nextState

        onPhaseUpdate?.invoke(nextState.status, displayMultiplier)
    }

    fun updateScheduleStandby(isStandby: Boolean, standbyMessage: String?) {
        val current = _gameState.value
        if (current.isStandbyOutsideSchedule != isStandby || current.scheduleStandbyMessage != standbyMessage) {
            _gameState.value = current.copy(
                isStandbyOutsideSchedule = isStandby,
                scheduleStandbyMessage = standbyMessage
            )
        }
    }

    fun setBetPlaced(placed: Boolean) {
        _gameState.value = _gameState.value.copy(isBetPlaced = placed)
    }

    fun setCashOutAvailable(available: Boolean) {
        _gameState.value = _gameState.value.copy(isCashOutAvailable = available)
    }

    fun updateStrategyTelemetry(strategyState: StrategyState, activeTriggerLabel: String?) {
        _gameState.value = _gameState.value.copy(
            consecutiveLowCount = strategyState.consecutiveLowCount,
            postHighWaitCount = strategyState.postHighWaitCount,
            highMultiplierLossesInHour = strategyState.highMultiplierLossesInHour,
            currentStakeLevel = strategyState.currentStakeLevel,
            currentStake = strategyState.currentCalculatedStake,
            winsFetchedInSession = strategyState.consecutiveWinsInSession,
            targetWinsToFetch = strategyState.targetWinsToFetch,
            waitingRuleName = strategyState.pendingWaitRuleLabel ?: strategyState.pendingWaitRuleId?.displayName,
            activeWaitRemainingMs = strategyState.activeWaitRemainingMs,
            isSessionLocked = strategyState.isBettingSessionActive,
            sessionTimeoutRemainingMs = strategyState.sessionTimeoutRemainingMs,
            activeTrigger = activeTriggerLabel
        )
    }

    fun setTelemetryCounts(consecutiveLow: Int, postHighWait: Int, activeTrigger: String?) {
        _gameState.value = _gameState.value.copy(
            consecutiveLowCount = consecutiveLow,
            postHighWaitCount = postHighWait,
            activeTrigger = activeTrigger
        )
    }

    fun updateSessionStats(stats: com.example.data.model.SessionStats) {
        _gameState.value = _gameState.value.copy(
            sessionWins = stats.winCount,
            sessionLosses = stats.lossCount,
            sessionTotalProfit = stats.totalProfit,
            sessionLimitHaltReason = stats.limitHaltReason
        )
    }
}

private data class BalanceResolution(
    val balance: Double?,
    val status: BalanceDetectionStatus,
    val lastUpdatedMs: Long,
    val failureReason: String?
)
