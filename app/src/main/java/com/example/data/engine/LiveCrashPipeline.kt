package com.example.data.engine

import android.util.Log
import com.example.data.model.CalendarHourUtils
import com.example.data.model.CollectionSource
import com.example.data.model.ColorClassifier
import com.example.data.model.CrashColorCategory
import com.example.data.model.CrashRecord
import com.example.data.model.HistoryCollectionStatus
import com.example.data.model.MonitoringStatus
import com.example.data.model.MultiplierRecord
import com.example.data.model.RoundLifecycleState
import com.example.data.model.TimestampSource
import com.example.service.GameDetectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

data class PendingCrashCandidate(
    val sessionId: String,
    val gameId: String,
    val gameName: String,
    val platformName: String,
    val sourcePackage: String,
    val multiplier: Double,
    val roundId: String?,
    val roundSequence: Int,
    val colorHex: String,
    val colorCategory: CrashColorCategory,
    val isPixelVerified: Boolean,
    val detectedAtMs: Long,
    val dedupKey: String
)

data class LiveCrashPipelineDiagnostics(
    val lifecycleState: RoundLifecycleState = RoundLifecycleState.WAITING_FOR_ROUND,
    val lastEventSummary: String = "WAITING_FOR_ROUND",
    val roundSequence: Int = 0,
    val duplicatesIgnoredCount: Int = 0,
    val historyBlocksCount: Int = 0
)

/**
 * Authoritative Flow-debounced Live Crash Pipeline with explicit 7-state round lifecycle.
 *
 * Strictly enforces:
 * - Only ONE confirmed record per completed live round (Flow debounce + dedupKey uniqueness).
 * - Never records rising (IN_FLIGHT) multipliers, stale values on GAME_NOT_ACCESSIBLE /
 *   GAME_SUSPENDED, or entries while the game's Round History panel is open (HISTORY_ONLY).
 * - Preserves legitimate consecutive rounds with identical multipliers (e.g. 2.10x, 2.10x)
 *   using `(sessionId, gameId, roundId or sequenceIndex)` rather than multiplier value alone.
 */
class LiveCrashPipeline(
    private val scope: CoroutineScope,
    private val debounceWindowMs: Long = DEFAULT_DEBOUNCE_MS,
    private val onConfirmedCrash: suspend (ConfirmedCrashEmission) -> Unit
) {
    companion object {
        private const val TAG = "LiveCrashPipeline"
        const val DEFAULT_DEBOUNCE_MS = 220L
    }

    private val _lifecycleState = MutableStateFlow(RoundLifecycleState.WAITING_FOR_ROUND)
    val lifecycleState: StateFlow<RoundLifecycleState> = _lifecycleState.asStateFlow()

    private val _diagnostics = MutableStateFlow(LiveCrashPipelineDiagnostics())
    val diagnostics: StateFlow<LiveCrashPipelineDiagnostics> = _diagnostics.asStateFlow()

    private val pendingCrashFlow = MutableSharedFlow<PendingCrashCandidate>(extraBufferCapacity = 64)
    private var collectorJob: Job? = null

    private var activeSessionId: String = ""
    private var roundSequence: Int = 0
    private var lastFlightPeakMultiplier: Double? = null
    private var lastFlightColorHex: String? = null
    private var lastFlightColorVerified: Boolean = false
    private var activeRoundRecorded: Boolean = false
    private var lastRecordedRoundId: String? = null
    private var lastRecordedDedupKey: String? = null
    private var recordedDedupKeysInSession = linkedSetOf<String>()

    private var latestPendingCandidate: PendingCrashCandidate? = null
    private var duplicatesIgnoredCount: Int = 0
    private var historyBlocksCount: Int = 0

    @Volatile
    private var currentLowThreshold: Double = 2.00

    @Volatile
    private var currentBigThreshold: Double = 10.00

    init {
        startCollector()
    }

    @OptIn(FlowPreview::class)
    private fun startCollector() {
        if (collectorJob != null) return
        collectorJob = scope.launch {
            pendingCrashFlow
                .debounce(debounceWindowMs.coerceAtLeast(0L))
                .collectLatest { candidate ->
                    confirmAndEmitCandidate(candidate)
                }
        }
    }

    fun configureSession(
        sessionId: String,
        initialSequence: Int = 0,
        existingDedupKeys: Collection<String> = emptyList()
    ) {
        activeSessionId = sessionId
        roundSequence = initialSequence.coerceAtLeast(0)
        recordedDedupKeysInSession = linkedSetOf<String>().apply {
            addAll(existingDedupKeys)
        }
        latestPendingCandidate = null
        lastFlightPeakMultiplier = null
        lastFlightColorHex = null
        lastFlightColorVerified = false
        activeRoundRecorded = false
        lastRecordedRoundId = null
        lastRecordedDedupKey = existingDedupKeys.lastOrNull()
        _lifecycleState.value = RoundLifecycleState.WAITING_FOR_ROUND
        updateDiagnostics("SESSION_CONFIGURED | session=$sessionId | seq=$roundSequence")
    }

    fun resetForStoppedSession() {
        activeSessionId = ""
        roundSequence = 0
        recordedDedupKeysInSession.clear()
        latestPendingCandidate = null
        lastFlightPeakMultiplier = null
        lastFlightColorHex = null
        lastFlightColorVerified = false
        activeRoundRecorded = false
        lastRecordedRoundId = null
        lastRecordedDedupKey = null
        duplicatesIgnoredCount = 0
        historyBlocksCount = 0
        _lifecycleState.value = RoundLifecycleState.WAITING_FOR_ROUND
        updateDiagnostics("SESSION_STOPPED")
    }

    suspend fun onDetectionFrame(
        detection: GameDetectionState,
        monitoringStatus: MonitoringStatus,
        lowThreshold: Double = 2.00,
        bigThreshold: Double = 10.00,
        nowMs: Long = detection.updatedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
    ) {
        currentLowThreshold = lowThreshold
        currentBigThreshold = bigThreshold

        if (activeSessionId.isBlank()) return

        // Only collect during active Monitoring state
        if (monitoringStatus != MonitoringStatus.MONITORING &&
            monitoringStatus != MonitoringStatus.WAITING_FOR_GAME
        ) {
            return
        }

        // Strict History Panel isolation: when expanded Round History modal is visible,
        // classify as HISTORY_ONLY and NEVER record or trigger alarms.
        if (detection.uiTargets.historyPanelExpanded) {
            historyBlocksCount++
            updateDiagnostics("HISTORY_ONLY | BLOCKED (historyPanelExpanded=true)")
            safeLogD(TAG, "HISTORY_ONLY | BLOCKED while history modal is visible")
            return
        }

        // Strict accessibility/window validity gate
        val blockedByWindowAccess = detection.windowAccessStatus == MonitoringStatus.GAME_NOT_ACCESSIBLE ||
            detection.windowAccessStatus == MonitoringStatus.GAME_SUSPENDED ||
            detection.gameWindowSuspended
        if (!detection.isGameDetected || detection.liveDetectionLost || blockedByWindowAccess) {
            return
        }

        val phase = detection.gamePhase
        val mult = detection.detectedMultiplier

        when (phase) {
            "BETTING_OPEN" -> {
                // If transitioning directly from an un-settled flight, confirm that round before starting next
                val prevPeak = lastFlightPeakMultiplier
                if (!activeRoundRecorded && prevPeak != null && prevPeak >= 1.0 &&
                    (_lifecycleState.value == RoundLifecycleState.MULTIPLIER_RISING ||
                        _lifecycleState.value == RoundLifecycleState.ROUND_STARTED)
                ) {
                    enqueueCrashCandidate(
                        detection = detection,
                        multiplier = prevPeak,
                        colorHex = lastFlightColorHex,
                        isPixelVerified = lastFlightColorVerified,
                        nowMs = nowMs
                    )
                    // Flush immediately before opening next round so consecutive rounds stay in order
                    flushPendingNow()
                } else if (latestPendingCandidate != null) {
                    // Rapid settle before next round started
                    flushPendingNow()
                }

                lastFlightPeakMultiplier = null
                lastFlightColorHex = null
                lastFlightColorVerified = false

                if (_lifecycleState.value == RoundLifecycleState.ROUND_RECORDED ||
                    _lifecycleState.value == RoundLifecycleState.FINAL_MULTIPLIER_CONFIRMED ||
                    _lifecycleState.value == RoundLifecycleState.CRASH_DETECTED
                ) {
                    _lifecycleState.value = RoundLifecycleState.WAITING_FOR_NEXT_ROUND
                    updateDiagnostics("WAITING_FOR_NEXT_ROUND")
                } else if (_lifecycleState.value == RoundLifecycleState.WAITING_FOR_ROUND ||
                    _lifecycleState.value == RoundLifecycleState.WAITING_FOR_NEXT_ROUND
                ) {
                    _lifecycleState.value = RoundLifecycleState.ROUND_STARTED
                    updateDiagnostics("ROUND_STARTED | seq=$roundSequence")
                }
            }

            "IN_FLIGHT" -> {
                // If there's still a pending candidate from previous round, flush it cleanly before new flight
                if (latestPendingCandidate != null) {
                    flushPendingNow()
                }

                val isStartingNewRound = _lifecycleState.value == RoundLifecycleState.WAITING_FOR_ROUND ||
                    _lifecycleState.value == RoundLifecycleState.WAITING_FOR_NEXT_ROUND ||
                    _lifecycleState.value == RoundLifecycleState.ROUND_STARTED ||
                    _lifecycleState.value == RoundLifecycleState.ROUND_RECORDED ||
                    activeRoundRecorded

                if (isStartingNewRound) {
                    roundSequence += 1
                    activeRoundRecorded = false
                    lastFlightPeakMultiplier = null
                    lastFlightColorHex = null
                    lastFlightColorVerified = false
                }

                _lifecycleState.value = RoundLifecycleState.MULTIPLIER_RISING
                if (mult != null && mult.isFinite() && mult >= 1.0) {
                    lastFlightPeakMultiplier = maxOf(lastFlightPeakMultiplier ?: 1.0, mult)
                    if (!detection.liveColorHex.isNullOrBlank()) {
                        lastFlightColorHex = detection.liveColorHex
                        lastFlightColorVerified = detection.isLiveColorPixelVerified
                    }
                    updateDiagnostics("MULTIPLIER_RISING | ${String.format(Locale.US, "%.2fx", mult)} | NOT_RECORDED")
                }
            }

            "CRASHED" -> {
                val crashedValue = mult?.takeIf { it.isFinite() && it >= 1.0 }
                    ?: lastFlightPeakMultiplier?.takeIf { it.isFinite() && it >= 1.0 }
                    ?: return

                val incomingRoundId = detection.roundId?.takeIf { it.isNotBlank() }
                val isDistinctRoundId = incomingRoundId != null && incomingRoundId != lastRecordedRoundId

                if (activeRoundRecorded && !isDistinctRoundId) {
                    duplicatesIgnoredCount++
                    updateDiagnostics("REPEATED_CALLBACK | DUPLICATE_IGNORED (${String.format(Locale.US, "%.2fx", crashedValue)})")
                    safeLogD(TAG, "REPEATED_CALLBACK | DUPLICATE_IGNORED for seq=$roundSequence value=$crashedValue")
                    return
                }

                if (roundSequence == 0 || isDistinctRoundId && activeRoundRecorded) {
                    roundSequence += 1
                    activeRoundRecorded = false
                }

                _lifecycleState.value = RoundLifecycleState.CRASH_DETECTED
                updateDiagnostics("CRASH_DETECTED | ${String.format(Locale.US, "%.2fx", crashedValue)} | DEBOUNCING")

                enqueueCrashCandidate(
                    detection = detection,
                    multiplier = crashedValue,
                    colorHex = detection.liveColorHex ?: lastFlightColorHex,
                    isPixelVerified = detection.isLiveColorPixelVerified || lastFlightColorVerified,
                    nowMs = nowMs
                )
            }
        }
    }

    private fun enqueueCrashCandidate(
        detection: GameDetectionState,
        multiplier: Double,
        colorHex: String?,
        isPixelVerified: Boolean,
        nowMs: Long
    ) {
        if (roundSequence <= 0) {
            roundSequence = 1
        }
        val cleanRoundId = detection.roundId?.takeIf { it.isNotBlank() }
        val cleanGameId = detection.gameId.ifBlank { "unknown" }
        val cleanGameName = detection.displayedGameName.ifBlank { "Unknown" }
        val dedupKey = CrashRecord.buildLiveRoundDedupKey(
            sessionId = activeSessionId,
            gameId = cleanGameId,
            roundIdentifier = cleanRoundId,
            sequenceIndex = roundSequence
        )
        if (recordedDedupKeysInSession.contains(dedupKey)) {
            duplicatesIgnoredCount++
            updateDiagnostics("REPEATED_CALLBACK | DUPLICATE_IGNORED ($dedupKey)")
            return
        }

        val fallbackCat = ColorClassifier.fallbackCategoryForMultiplier(
            multiplier = multiplier,
            lowThreshold = currentLowThreshold,
            bigThreshold = currentBigThreshold
        )
        val resolvedHex = colorHex?.takeIf { it.isNotBlank() } ?: fallbackCat.defaultHex
        val resolvedCat = if (isPixelVerified) ColorClassifier.classifyHex(resolvedHex) else fallbackCat

        val candidate = PendingCrashCandidate(
            sessionId = activeSessionId,
            gameId = cleanGameId,
            gameName = cleanGameName,
            platformName = detection.platformName.ifBlank { "Unknown" },
            sourcePackage = detection.foregroundPackage,
            multiplier = multiplier,
            roundId = cleanRoundId,
            roundSequence = roundSequence,
            colorHex = resolvedHex,
            colorCategory = resolvedCat,
            isPixelVerified = isPixelVerified,
            detectedAtMs = nowMs,
            dedupKey = dedupKey
        )
        latestPendingCandidate = candidate
        pendingCrashFlow.tryEmit(candidate)
    }

    /**
     * Flushes any pending debounced crash candidate immediately (used on clean round transition or tests).
     */
    suspend fun flushPendingNow() {
        val pending = latestPendingCandidate ?: return
        confirmAndEmitCandidate(pending)
    }

    private suspend fun confirmAndEmitCandidate(candidate: PendingCrashCandidate) {
        if (candidate.sessionId != activeSessionId || activeSessionId.isBlank()) {
            return
        }
        if (recordedDedupKeysInSession.contains(candidate.dedupKey)) {
            latestPendingCandidate = null
            duplicatesIgnoredCount++
            updateDiagnostics("REPEATED_CALLBACK | DUPLICATE_IGNORED (${candidate.dedupKey})")
            return
        }

        latestPendingCandidate = null
        _lifecycleState.value = RoundLifecycleState.FINAL_MULTIPLIER_CONFIRMED

        recordedDedupKeysInSession.add(candidate.dedupKey)
        activeRoundRecorded = true
        lastRecordedRoundId = candidate.roundId
        lastRecordedDedupKey = candidate.dedupKey
        lastFlightPeakMultiplier = null
        lastFlightColorHex = null
        lastFlightColorVerified = false

        val crashRecord = CrashRecord(
            gameId = candidate.gameId,
            gameName = candidate.gameName,
            multiplier = candidate.multiplier,
            detectedColorHex = candidate.colorHex,
            colorCategory = candidate.colorCategory,
            isPixelColorVerified = candidate.isPixelVerified,
            crashTimestamp = candidate.detectedAtMs,
            hourStartTimestamp = CalendarHourUtils.hourStartFor(candidate.detectedAtMs),
            sourcePackage = candidate.sourcePackage,
            roundIdentifier = candidate.roundId ?: "#${candidate.roundSequence}",
            confidence = 0f,
            manuallyCorrected = false,
            collectionSource = CollectionSource.LIVE,
            sessionId = candidate.sessionId,
            isConfirmed = true,
            dedupKey = candidate.dedupKey
        )

        val multiplierRecord = MultiplierRecord(
            gameId = candidate.gameId,
            sessionId = candidate.sessionId,
            roundId = candidate.roundId ?: "#${candidate.roundSequence}",
            multiplier = candidate.multiplier,
            gameTimestampMs = null,
            observationTimestampMs = candidate.detectedAtMs,
            crashTimestamp = candidate.detectedAtMs,
            platform = candidate.platformName,
            isConfirmed = true,
            timestampSource = TimestampSource.LOCAL_OBSERVATION,
            collectionSource = CollectionSource.LIVE,
            collectionStatus = HistoryCollectionStatus.HISTORY_PARTIAL,
            dedupKey = candidate.dedupKey,
            sequenceIndex = candidate.roundSequence,
            hasGapBefore = false
        )

        _lifecycleState.value = RoundLifecycleState.ROUND_RECORDED
        updateDiagnostics(
            "ROUND_RECORDED | seq=${candidate.roundSequence} | ${String.format(Locale.US, "%.2fx", candidate.multiplier)} | key=${candidate.dedupKey}"
        )
        safeLogD(TAG, "ROUND_RECORDED | ${candidate.dedupKey} | ${candidate.multiplier}x")

        onConfirmedCrash(
            ConfirmedCrashEmission(
                multiplier = candidate.multiplier,
                crashRecord = crashRecord,
                multiplierRecord = multiplierRecord
            )
        )
    }

    fun getCurrentRoundSequence(): Int = roundSequence

    private fun updateDiagnostics(summary: String) {
        _diagnostics.value = LiveCrashPipelineDiagnostics(
            lifecycleState = _lifecycleState.value,
            lastEventSummary = summary,
            roundSequence = roundSequence,
            duplicatesIgnoredCount = duplicatesIgnoredCount,
            historyBlocksCount = historyBlocksCount
        )
    }

    private fun safeLogD(tag: String, msg: String) {
        try {
            Log.d(tag, msg)
        } catch (_: RuntimeException) {
        }
    }
}
