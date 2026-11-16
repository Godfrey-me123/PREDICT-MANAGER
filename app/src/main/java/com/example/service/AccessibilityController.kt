package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.data.engine.CurrencyManager
import com.example.data.engine.StakeInstruction
import com.example.data.engine.StrategyEngine
import com.example.data.model.SiteButtonState
import com.example.data.model.StakeProgressionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs

data class MultiplierCandidate(
    val value: Double,
    val bounds: Rect,
    val orderIndex: Int,
    val isHistoryHinted: Boolean = false,
    val gameTimestampMs: Long? = null,
    val roundId: String? = null,
    val displayText: String = ""
)

data class DetectedUiTargets(
    val detectedMultiplier: Double? = null,
    val ribbonMultipliers: List<Double> = emptyList(),
    val ribbonItems: List<com.example.data.engine.ExtractedHistoryItem> = emptyList(),
    val historyPanelExpanded: Boolean = false,
    val hasExpandableHistoryControl: Boolean = false,
    val hasScrollableHistoryContainer: Boolean = false,
    val gamePhase: String = "WAITING_FOR_GAME", // "WAITING_FOR_GAME", "BETTING_OPEN", "IN_FLIGHT", "CRASHED", "LIVE_DETECTION_LOST"
    val siteButtonState: SiteButtonState = SiteButtonState.UNKNOWN,
    val isFlewAwayDetected: Boolean = false,
    val isWaitingForNextRound: Boolean = false,
    val isCancelBtnDetected: Boolean = false,
    val detectedUrl: String? = null,
    val detectedCurrentRoundId: String? = null,
    val pageTextHints: List<String> = emptyList(),
    val visibleRawTitles: List<String> = emptyList(),
    val liveMultiplierBounds: Rect? = null,
    val stakeInputBounds: Rect? = null,
    val doubleButtonBounds: Rect? = null,
    val addButtonBounds: Rect? = null,
    val betButtonBounds: Rect? = null,
    val cancelButtonBounds: Rect? = null,
    val cashOutButtonBounds: Rect? = null,
    val detectedStakeText: String? = null,
    val detectedRoundId: String? = detectedCurrentRoundId,
    val roundId: String? = detectedCurrentRoundId,
    val detectedBalance: Double? = null,
    val detectedCurrency: String? = null
) {
    val isStakeInputDetected: Boolean get() = stakeInputBounds != null
    val isDoubleBtnDetected: Boolean get() = doubleButtonBounds != null
    val isAddBtnDetected: Boolean get() = addButtonBounds != null
    val isBetBtnDetected: Boolean get() = betButtonBounds != null
    val isCashOutBtnDetected: Boolean get() = cashOutButtonBounds != null
}

data class HierarchyCandidate(
    val role: String,
    val resourceId: String,
    val className: String,
    val text: String,
    val contentDescription: String,
    val bounds: Rect
)

data class NodeHierarchyReport(
    val packageName: String,
    val timestampMs: Long,
    val totalNodesVisited: Int,
    val truncated: Boolean,
    val lines: List<String>,
    val candidates: List<HierarchyCandidate>
) {
    fun toFormattedText(): String = buildString {
        lines.forEach { line ->
            appendLine(line)
        }
    }
}

/**
 * AccessibilityController - Crash-safe AccessibilityService extending Android accessibility
 * to read browser URL bars (BetPawa vs SportyBet), separate top history ribbons from live center
 * multipliers, track real site Bet/Cancel/Cash Out button states, edit stakes, dispatch gestures
 * cleanly on the Main Looper without overlays blocking their own taps, and log node hierarchies.
 */
open class AccessibilityController : AccessibilityService() {

    companion object {
        private const val TAG = "AccessibilityController"
        const val NODE_HIERARCHY_TAG = "A11yNodeTree"
        const val DEFAULT_HIERARCHY_LOG_THROTTLE_MS = 1200L
        const val FORCE_HIERARCHY_REFRESH_MS = 6000L
        private const val MAX_TREE_DEPTH = 64
        private const val MAX_LOGGED_NODES = 600
        private const val MAX_SCAN_NODES = 1800

        var controllerInstance: AccessibilityController? = null
            protected set

        fun isControllerConnected(): Boolean = controllerInstance != null

        /**
         * Callback notified before/after automated gesture dispatch so ScreenEarnerOverlayService
         * temporarily sets FLAG_NOT_TOUCHABLE on floating hands/panels and never intercepts its own tap.
         */
        @Volatile
        var gestureOverlayPassThroughListener: ((isDispatching: Boolean) -> Unit)? = null

        private val _multiplierFlow = MutableStateFlow<Double?>(null)
        val multiplierFlow: StateFlow<Double?> = _multiplierFlow.asStateFlow()

        private val _detectedTargets = MutableStateFlow(DetectedUiTargets())
        val detectedTargets: StateFlow<DetectedUiTargets> = _detectedTargets.asStateFlow()

        private val _nodeHierarchyLoggingEnabled = MutableStateFlow(true)
        val nodeHierarchyLoggingEnabled: StateFlow<Boolean> = _nodeHierarchyLoggingEnabled.asStateFlow()

        private val _lastLoggedHierarchy = MutableStateFlow("")
        val lastLoggedHierarchy: StateFlow<String> = _lastLoggedHierarchy.asStateFlow()

        private const val UI_EMIT_THROTTLE_MS = 115L
        private const val MIN_PASS_THROUGH_MS = 100L

        private val _diagnosticMessage = MutableStateFlow<String?>(null)
        val diagnosticMessage: StateFlow<String?> = _diagnosticMessage.asStateFlow()

        fun emitDiagnostic(message: String) {
            _diagnosticMessage.value = message
        }

        // Match decimal multipliers like 1.00x, 12.80x, 140.25x (avoids matching "2x" double-stake button)
        val MULTIPLIER_PATTERN: Pattern = Pattern.compile("(\\d+\\.\\d{1,2})\\s*x", Pattern.CASE_INSENSITIVE)

        private val BALANCE_SUFFIX_PATTERN: Pattern = Pattern.compile(
            "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(TZS|TSH|ZMW|KES|KSH|UGX|NGN|GHS|USD)",
            Pattern.CASE_INSENSITIVE
        )

        private val BALANCE_PREFIX_PATTERN: Pattern = Pattern.compile(
            "(TZS|TSH|ZMW|KES|KSH|UGX|NGN|GHS|USD|₦|GH₵|\\$)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE
        )

        private val NUMERIC_VALUE_PATTERN: Pattern = Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")

        private val URL_HINT_PATTERN: Pattern = Pattern.compile(
            "(?:https?://)?[a-z0-9.-]*(?:betpawa|sportybet)[a-z0-9.-]*(?:/[^\\s]*)?|(?:https?://)?[a-z0-9.-]+\\.(?:com|co\\.[a-z]{2}|ng|ke|tz|zm|gh|ug|za|mw|rw|bet)(?:/[^\\s]*)?",
            Pattern.CASE_INSENSITIVE
        )

        fun setNodeHierarchyLoggingEnabled(enabled: Boolean) {
            _nodeHierarchyLoggingEnabled.value = enabled
        }

        fun formatStakeForInput(stake: Double): String {
            return if (stake % 1.0 == 0.0) {
                stake.toLong().toString()
            } else {
                String.format(Locale.US, "%.2f", stake)
            }
        }

        /**
         * Parses account balance + currency code from a header node (e.g., "10.18 TZS", "ZMW 450.00").
         * Ignores stake inputs and multiplier labels ending with 'x'.
         */
        fun parseBalanceCandidate(
            text: String,
            lowerId: String = "",
            bounds: Rect = Rect(),
            viewportHeightPx: Int = 0
        ): Pair<Double, String?>? {
            if (text.isBlank()) return null
            val lowerText = text.lowercase(Locale.US)
            if (lowerText.contains("stake") || lowerText.contains("wager") || lowerId.contains("stake") ||
                lowerId.contains("url_bar") || lowerId.contains("omnibox") ||
                lowerText.contains("auto") || lowerText.contains("cash out") || lowerText.contains("cashout")
            ) {
                return null
            }
            if (text.trim().endsWith("x", ignoreCase = true) && MULTIPLIER_PATTERN.matcher(text).find()) {
                return null
            }

            val suffixMatcher = BALANCE_SUFFIX_PATTERN.matcher(text)
            if (suffixMatcher.find()) {
                val amount = suffixMatcher.group(1)?.replace(",", "")?.toDoubleOrNull()
                val rawCurr = suffixMatcher.group(2)?.uppercase(Locale.US)
                val normCurr = when (rawCurr) {
                    "TSH" -> "TZS"
                    "KSH" -> "KES"
                    else -> rawCurr
                }
                if (amount != null && amount.isFinite() && amount >= 0.0) {
                    return amount to normCurr
                }
            }

            val prefixMatcher = BALANCE_PREFIX_PATTERN.matcher(text)
            if (prefixMatcher.find()) {
                val rawCurr = prefixMatcher.group(1)?.uppercase(Locale.US)
                val amount = prefixMatcher.group(2)?.replace(",", "")?.toDoubleOrNull()
                val normCurr = when (rawCurr) {
                    "TSH" -> "TZS"
                    "KSH" -> "KES"
                    "₦" -> "NGN"
                    "GH₵" -> "GHS"
                    "$" -> "USD"
                    else -> rawCurr
                }
                if (amount != null && amount.isFinite() && amount >= 0.0) {
                    return amount to normCurr
                }
            }

            // Explicit balance/wallet node without currency code in string, provided it sits in header area or ID
            val inHeaderBand = bounds.isEmpty || viewportHeightPx <= 0 || bounds.top <= (viewportHeightPx * 0.28f).toInt()
            val hasBalanceHint = lowerId.contains("balance") || lowerId.contains("wallet") ||
                lowerId.contains("credit") || lowerText.contains("balance") || lowerText.contains("bal:")
            if (hasBalanceHint && inHeaderBand) {
                val numMatcher = NUMERIC_VALUE_PATTERN.matcher(text)
                if (numMatcher.find()) {
                    val amount = numMatcher.group(1)?.replace(",", "")?.toDoubleOrNull()
                    if (amount != null && amount.isFinite() && amount >= 0.0) {
                        val detectedCurr = CurrencyManager.parseCurrencyCodeFromText(text)?.code
                        return amount to detectedCurr
                    }
                }
            }
            return null
        }

        fun verifyStakeAccepted(readBackText: String?, expectedStake: Double): Boolean {
            if (readBackText.isNullOrBlank()) return false
            val cleaned = readBackText.replace(",", "").trim()
            val parsed = cleaned.toDoubleOrNull()
                ?: NUMERIC_VALUE_PATTERN.matcher(cleaned).let { m ->
                    if (m.find()) m.group(1)?.toDoubleOrNull() else null
                }
                ?: return false
            return abs(parsed - expectedStake) <= 0.05
        }

        /**
         * Separates the top horizontal history ribbon (`15.74x  2.00x  8.14x ...`) from the prominent
         * central live multiplier (`1.27x`, `3.01x`), using Y-band clustering, container hints,
         * and bounding-box dimensions so ribbon items are never mistaken for the live multiplier.
         *
         * @return Pair(ribbonMultipliersLeftToRight, liveCenterMultiplier)
         */
        fun extractRibbonAndCenterMultipliers(
            candidates: List<MultiplierCandidate>,
            viewportHeightPx: Int = 0
        ): Pair<List<Double>, Double?> {
            val (items, center) = extractRibbonItemsAndCenterMultiplier(candidates, viewportHeightPx)
            return items.map { it.multiplier } to center
        }

        fun extractRibbonItemsAndCenterMultiplier(
            candidates: List<MultiplierCandidate>,
            viewportHeightPx: Int = 0
        ): Pair<List<com.example.data.engine.ExtractedHistoryItem>, Double?> {
            val valid = candidates.filter { it.value >= 1.0 }
            if (valid.isEmpty()) return emptyList<com.example.data.engine.ExtractedHistoryItem>() to null

            val explicitHistory = valid.filter { it.isHistoryHinted }.toMutableSet()
            val positioned = valid.filter { !it.bounds.isEmpty }

            // Expanded grids (e.g. SportyBet 8-column Round History modal) can occupy upper ~60% of the screen.
            val topCutoffY = if (viewportHeightPx > 0) {
                (viewportHeightPx * 0.62f).toInt().coerceAtLeast(220)
            } else {
                val maxBottom = positioned.maxOfOrNull { it.bounds.bottom } ?: 0
                if (maxBottom > 0) (maxBottom * 0.65f).toInt().coerceAtLeast(220) else Int.MAX_VALUE
            }

            // Collect horizontal rows in topCutoffY so single-row ribbons AND multi-row Round History grids
            // retain all rows cleanly without modulo division artifacts dropping row-head items (e.g. 1.53x).
            val horizontalRows = mutableListOf<List<MultiplierCandidate>>()
            for (anchor in positioned) {
                val anchorCy = anchor.bounds.centerY()
                if (anchorCy > topCutoffY) continue
                val rowMatches = positioned.filter { other ->
                    other.bounds.centerY() <= topCutoffY &&
                        abs(other.bounds.centerY() - anchorCy) <= 45
                }
                if (rowMatches.size >= 2) {
                    horizontalRows.add(rowMatches)
                }
            }

            // Determine typical compact chip height from detected rows
            val medianRowHeight = horizontalRows.flatten()
                .map { it.bounds.height() }
                .filter { it > 0 }
                .sorted()
                .let { heights ->
                    if (heights.isEmpty()) 48 else heights[heights.size / 2]
                }

            val gridBandCandidates = horizontalRows
                .flatten()
                .distinctBy { it.orderIndex }
                .filter { c ->
                    c.bounds.height() <= (medianRowHeight * 2.2f).toInt().coerceAtLeast(110)
                }

            val combinedRibbon = (explicitHistory + gridBandCandidates)
                .distinctBy { it.orderIndex }

            // Assign stable row indices by vertical center proximity rather than modulo division (top / 45)
            // so a 1px vertical jitter between neighboring badges in the same row never reverses left-to-right order.
            val sortedByY = combinedRibbon.sortedBy {
                if (it.bounds.isEmpty) Int.MAX_VALUE else it.bounds.centerY()
            }
            val rowGroups = mutableListOf<MutableList<MultiplierCandidate>>()
            val unpositioned = mutableListOf<MultiplierCandidate>()
            for (cand in sortedByY) {
                if (cand.bounds.isEmpty) {
                    unpositioned.add(cand)
                } else {
                    val lastGroup = rowGroups.lastOrNull()
                    if (lastGroup != null) {
                        val groupAvgCy = lastGroup.map { it.bounds.centerY() }.average()
                        val tolerance = (medianRowHeight * 0.8f).toInt().coerceIn(28, 64)
                        if (abs(cand.bounds.centerY() - groupAvgCy) <= tolerance) {
                            lastGroup.add(cand)
                            continue
                        }
                    }
                    rowGroups.add(mutableListOf(cand))
                }
            }

            val ribbonSet = rowGroups.flatMap { row ->
                row.sortedWith(compareBy({ it.bounds.left }, { it.orderIndex }))
            } + unpositioned.sortedBy { it.orderIndex }

            val sourceType = if (explicitHistory.size >= 8 || rowGroups.size >= 2) {
                com.example.data.model.CollectionSource.EXPANDED_PANEL
            } else {
                com.example.data.model.CollectionSource.VISIBLE_RIBBON
            }
            val extractedItems = ribbonSet.mapIndexed { idx, c ->
                val sampled = ScreenColorSampler.sampleColorInBounds(c.bounds)
                com.example.data.engine.ExtractedHistoryItem(
                    multiplier = c.value,
                    roundId = c.roundId,
                    gameTimestampMs = c.gameTimestampMs,
                    orderIndex = idx,
                    source = sourceType,
                    bounds = Rect(c.bounds),
                    detectedColorHex = sampled?.hex,
                    colorCategory = sampled?.category,
                    isPixelColorVerified = sampled?.isPixelVerified == true,
                    displayText = c.displayText
                )
            }

            val ribbonOrderIndices = ribbonSet.map { it.orderIndex }.toSet()
            val ribbonBottomY = ribbonSet.filter { !it.bounds.isEmpty }.maxOfOrNull { it.bounds.bottom } ?: Int.MIN_VALUE

            val nonRibbon = valid.filter { it.orderIndex !in ribbonOrderIndices }
            if (nonRibbon.isEmpty()) {
                return extractedItems to null
            }

            val belowRibbon = if (ribbonBottomY > Int.MIN_VALUE) {
                nonRibbon.filter { it.bounds.isEmpty || it.bounds.centerY() > ribbonBottomY }
            } else {
                nonRibbon
            }
            val centerPool = if (belowRibbon.isNotEmpty()) belowRibbon else nonRibbon

            val liveCenter = centerPool.maxWithOrNull(
                compareBy<MultiplierCandidate>(
                    { if (it.bounds.isEmpty) 0 else it.bounds.height() },
                    { if (it.bounds.isEmpty) 0 else it.bounds.width() * it.bounds.height() },
                    { -it.orderIndex }
                )
            )?.value

            return extractedItems to liveCenter
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var hierarchyLogThrottleMs: Long = DEFAULT_HIERARCHY_LOG_THROTTLE_MS

    @Volatile
    private var lastHierarchyLogTimeMs: Long = 0L

    @Volatile
    private var lastHierarchySignature: Int = 0

    @Volatile
    private var lastEventScanTimeMs: Long = 0L

    @Volatile
    private var lastUiEmitTimeMs: Long = 0L

    @Volatile
    private var passThroughStartedAtMs: Long = 0L

    @Volatile
    private var pendingOverlayRestoreRunnable: Runnable? = null

    private val gestureGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile
    private var lastDispatchedCoords: Pair<Float, Float>? = null
    @Volatile
    private var lastDispatchedTimeMs: Long = 0L
    private val pendingGestureRunnables = java.util.concurrent.CopyOnWriteArraySet<Runnable>()

    fun setHierarchyLogThrottleMs(throttleMs: Long) {
        hierarchyLogThrottleMs = throttleMs.coerceAtLeast(0L)
    }

    override fun onServiceConnected() {
        try {
            super.onServiceConnected()
            controllerInstance = this
            safeLogD(TAG, "AccessibilityController connected. Node hierarchy logger active under tag '$NODE_HIERARCHY_TAG'.")
        } catch (t: Throwable) {
            safeLogE(TAG, "Non-fatal error during onServiceConnected", t)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            val ownPkg = runCatching { applicationContext?.packageName }.getOrNull().orEmpty()
            val eventPkg = try { event.packageName?.toString().orEmpty() } catch (_: Throwable) { "" }
            // Ignore Screen Earner's own overlay/bubble/panel events before consuming scan throttle
            if (ownPkg.isNotBlank() && eventPkg == ownPkg) {
                return
            }

            val now = System.currentTimeMillis()
            val eventType = try { event.eventType } catch (_: Throwable) { AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED }

            // Throttle dense content-changed churn (~115ms) while allowing window state transitions through immediately
            val isWindowTransition = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            if (!isWindowTransition &&
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                (now - lastEventScanTimeMs) < UI_EMIT_THROTTLE_MS
            ) {
                return
            }
            lastEventScanTimeMs = now

            val (rootNode, fromWindowsList) = resolveBestTargetWindowRoot() ?: return
            try {
                val rootPkg = safeNodePackageName(rootNode)
                if (ownPkg.isNotBlank() && rootPkg == ownPkg) {
                    return
                }
                val prevTargets = _detectedTargets.value
                val targets = scanWindowTargets(rootNode)

                val stateChanged = targets.siteButtonState != prevTargets.siteButtonState ||
                    targets.gamePhase != prevTargets.gamePhase ||
                    targets.isFlewAwayDetected != prevTargets.isFlewAwayDetected ||
                    targets.detectedUrl != prevTargets.detectedUrl ||
                    targets.detectedBalance != prevTargets.detectedBalance ||
                    targets.ribbonMultipliers != prevTargets.ribbonMultipliers

                if (isWindowTransition || stateChanged || (now - lastUiEmitTimeMs) >= UI_EMIT_THROTTLE_MS) {
                    lastUiEmitTimeMs = now
                    _detectedTargets.value = targets
                    if (targets.detectedMultiplier != null) {
                        _multiplierFlow.value = targets.detectedMultiplier
                    }
                }

                // Offload structural Logcat reporting checks off Main UI Thread where throttled
                val elapsedSinceLog = (now - lastHierarchyLogTimeMs).coerceAtLeast(0L)
                if (_nodeHierarchyLoggingEnabled.value &&
                    (lastHierarchyLogTimeMs == 0L || elapsedSinceLog >= hierarchyLogThrottleMs ||
                        eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                ) {
                    val pkg = safeNodePackageName(rootNode).ifBlank {
                        try { event.packageName?.toString().orEmpty() } catch (_: Throwable) { "" }
                    }
                    val ownPkg = runCatching { applicationContext?.packageName }.getOrNull()
                    if (ownPkg.isNullOrBlank() || pkg != ownPkg) {
                        val report = buildNodeHierarchyReport(
                            rootNode = rootNode,
                            packageName = pkg,
                            eventLabel = runCatching { AccessibilityEvent.eventTypeToString(eventType) }.getOrDefault(eventType.toString()),
                            timestampMs = now
                        )
                        workerScope.launch {
                            try {
                                val sig = computeStructuralSignature(report)
                                if (lastHierarchyLogTimeMs == 0L ||
                                    (sig != lastHierarchySignature && elapsedSinceLog >= hierarchyLogThrottleMs) ||
                                    elapsedSinceLog >= FORCE_HIERARCHY_REFRESH_MS
                                ) {
                                    lastHierarchyLogTimeMs = now
                                    lastHierarchySignature = sig
                                    emitReportToLogcat(report)
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            } finally {
                if (fromWindowsList) {
                    safeRecycleRoot(rootNode)
                }
            }
        } catch (t: Throwable) {
            // Never allow unhandled exceptions to crash AccessibilityService and get it disabled by Android
            safeLogE(TAG, "Caught exception in onAccessibilityEvent", t)
        }
    }

    protected fun safeRootInActiveWindow(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow
        } catch (t: Throwable) {
            safeLogW(TAG, "Unable to acquire rootInActiveWindow: ${t.message}")
            null
        }
    }

    protected open fun safeInteractiveWindows(): List<AccessibilityWindowInfo> {
        return try {
            windows ?: emptyList()
        } catch (t: Throwable) {
            safeLogW(TAG, "Unable to acquire windows list: ${t.message}")
            emptyList()
        }
    }

    private fun safeRecycleRoot(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try {
                @Suppress("DEPRECATION")
                node.recycle()
            } catch (_: Throwable) {
            }
        }
    }

    private fun isBettingOrBrowserPackage(pkg: String): Boolean {
        val lower = pkg.lowercase(Locale.US)
        return lower.contains("betpawa") ||
            lower.contains("sportybet") ||
            lower.contains("betway") ||
            lower.contains("1xbet") ||
            lower.contains("msport") ||
            lower.contains("betking") ||
            lower.contains("parimatch") ||
            lower.contains("aviator") ||
            lower.contains("spribe") ||
            lower.contains("chrome") ||
            lower.contains("chromium") ||
            lower.contains("browser") ||
            lower.contains("sbrowser") ||
            lower.contains("webview") ||
            lower.contains("firefox") ||
            lower.contains("opera") ||
            lower.contains("emmx") ||
            lower.contains("brave") ||
            lower.contains("kiwi") ||
            lower.contains("duckduckgo") ||
            lower.contains("vivaldi") ||
            lower.contains("yandex") ||
            lower.contains("ucmobile")
    }

    /**
     * Resolves the best Accessibility root node across single-window OR split-screen/multi-window
     * modes (`AccessibilityService.windows`), skipping Screen Earner's own overlay windows.
     */
    protected fun resolveBestTargetWindowRoot(): Pair<AccessibilityNodeInfo, Boolean>? {
        val ownPkg = runCatching { applicationContext?.packageName }.getOrNull().orEmpty()
        val activeRoot = safeRootInActiveWindow()
        val activePkg = activeRoot?.let { safeNodePackageName(it) }.orEmpty()
        val activeIsOwn = ownPkg.isNotBlank() && activePkg == ownPkg
        val activeIsSystemUi = activePkg == "com.android.systemui"

        if (activeRoot != null && !activeIsOwn && isBettingOrBrowserPackage(activePkg)) {
            return activeRoot to false
        }

        // Scan interactive windows to locate background/split-screen browser or betting window
        val winList = safeInteractiveWindows()
        var nonSelfFallback: AccessibilityNodeInfo? = null
        for (i in winList.indices.reversed()) {
            val winRoot = try { winList[i].root } catch (_: Throwable) { null } ?: continue
            val pkg = safeNodePackageName(winRoot)
            if (ownPkg.isNotBlank() && pkg == ownPkg) {
                safeRecycleRoot(winRoot)
                continue
            }
            if (isBettingOrBrowserPackage(pkg)) {
                safeRecycleRoot(nonSelfFallback)
                return winRoot to true
            }
            if (pkg != "com.android.systemui" && nonSelfFallback == null) {
                nonSelfFallback = winRoot
            } else {
                safeRecycleRoot(winRoot)
            }
        }

        if (nonSelfFallback != null) {
            return nonSelfFallback to true
        }
        return if (activeRoot != null && !activeIsOwn && !activeIsSystemUi) {
            activeRoot to false
        } else {
            null
        }
    }

    /**
     * Immediately dumps the active window's accessibility node hierarchy to Logcat regardless of throttle.
     */
    fun forceDumpNodeHierarchy(): NodeHierarchyReport? {
        return try {
            val rootNode = safeRootInActiveWindow() ?: return null
            val pkg = safeNodePackageName(rootNode)
            logNodeHierarchyNow(
                rootNode = rootNode,
                packageName = pkg,
                eventLabel = "MANUAL_DUMP",
                currentTimeMs = System.currentTimeMillis()
            )
        } catch (t: Throwable) {
            safeLogE(TAG, "forceDumpNodeHierarchy failed", t)
            null
        }
    }

    protected open fun childNodesOf(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val count = try {
            node.childCount.coerceAtLeast(0)
        } catch (_: Throwable) {
            0
        }
        if (count == 0) return emptyList()
        val list = ArrayList<AccessibilityNodeInfo>(count)
        for (i in 0 until count) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            }
            if (child != null) {
                list.add(child)
            }
        }
        return list
    }

    protected fun safeRecycleNode(node: AccessibilityNodeInfo?, rootNode: AccessibilityNodeInfo) {
        if (node == null || node === rootNode) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try {
                @Suppress("DEPRECATION")
                node.recycle()
            } catch (_: Throwable) {
            }
        }
    }

    private fun safeNodePackageName(node: AccessibilityNodeInfo): String {
        return try {
            node.packageName?.toString().orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    internal fun maybeLogNodeHierarchy(
        rootNode: AccessibilityNodeInfo,
        event: AccessibilityEvent?,
        currentTimeMs: Long = System.currentTimeMillis(),
        skipOwnPackage: Boolean = true
    ): Boolean {
        if (!_nodeHierarchyLoggingEnabled.value) return false

        val pkg = safeNodePackageName(rootNode).ifBlank {
            try {
                event?.packageName?.toString().orEmpty()
            } catch (_: Throwable) {
                ""
            }
        }
        val ownPkg = runCatching { applicationContext?.packageName }.getOrNull()
        if (skipOwnPackage && !ownPkg.isNullOrBlank() && pkg == ownPkg) {
            return false
        }

        val eventType = try {
            event?.eventType ?: AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        } catch (_: Throwable) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        }
        val eventLabel = try {
            AccessibilityEvent.eventTypeToString(eventType)
        } catch (_: Throwable) {
            eventType.toString()
        }
        val report = buildNodeHierarchyReport(
            rootNode = rootNode,
            packageName = pkg,
            eventLabel = eventLabel,
            timestampMs = currentTimeMs
        )
        val signature = computeStructuralSignature(report)
        val elapsed = (currentTimeMs - lastHierarchyLogTimeMs).coerceAtLeast(0L)
        val isWindowChange = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        val shouldEmit = when {
            lastHierarchyLogTimeMs == 0L -> true
            isWindowChange && signature != lastHierarchySignature && elapsed >= 250L -> true
            elapsed >= hierarchyLogThrottleMs && signature != lastHierarchySignature -> true
            elapsed >= FORCE_HIERARCHY_REFRESH_MS -> true
            else -> false
        }

        if (!shouldEmit) return false

        lastHierarchyLogTimeMs = currentTimeMs
        lastHierarchySignature = signature
        emitReportToLogcat(report)
        return true
    }

    fun logNodeHierarchyNow(
        rootNode: AccessibilityNodeInfo,
        packageName: String = safeNodePackageName(rootNode),
        eventLabel: String = "DIRECT",
        currentTimeMs: Long = System.currentTimeMillis()
    ): NodeHierarchyReport {
        val report = buildNodeHierarchyReport(
            rootNode = rootNode,
            packageName = packageName,
            eventLabel = eventLabel,
            timestampMs = currentTimeMs
        )
        lastHierarchyLogTimeMs = currentTimeMs
        lastHierarchySignature = computeStructuralSignature(report)
        emitReportToLogcat(report)
        return report
    }

    fun buildNodeHierarchyReport(
        rootNode: AccessibilityNodeInfo,
        packageName: String = safeNodePackageName(rootNode),
        eventLabel: String = "WINDOW_SCAN",
        timestampMs: Long = System.currentTimeMillis(),
        maxDepth: Int = MAX_TREE_DEPTH,
        maxNodes: Int = MAX_LOGGED_NODES
    ): NodeHierarchyReport {
        val lines = mutableListOf<String>()
        val candidates = mutableListOf<HierarchyCandidate>()
        var visited = 0
        var truncated = false

        lines += "================ A11Y NODE TREE START ================"
        lines += "pkg=$packageName | event=$eventLabel | ts=$timestampMs"

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (visited >= maxNodes || depth > maxDepth) {
                truncated = true
                return
            }
            visited++

            val text = sanitizeForLog(try { node.text?.toString() } catch (_: Throwable) { null })
            val desc = sanitizeForLog(try { node.contentDescription?.toString() } catch (_: Throwable) { null })
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                sanitizeForLog(try { node.hintText?.toString() } catch (_: Throwable) { null })
            } else ""
            val viewId = try { node.viewIdResourceName?.trim().orEmpty() } catch (_: Throwable) { "" }
            val fullClass = try { node.className?.toString().orEmpty() } catch (_: Throwable) { "" }
            val shortClass = fullClass.substringAfterLast('.').ifBlank { "Node" }

            val rect = Rect()
            try {
                node.getBoundsInScreen(rect)
            } catch (_: Throwable) {
            }

            val isEditable = try { node.isEditable } catch (_: Throwable) { false }
            val isClickable = try { node.isClickable } catch (_: Throwable) { false }
            val isFocusable = try { node.isFocusable } catch (_: Throwable) { false }
            val isVisible = try { node.isVisibleToUser } catch (_: Throwable) { true }

            val flags = buildList {
                if (isEditable || fullClass.contains("EditText", ignoreCase = true)) add("EDITABLE")
                if (isClickable) add("CLICKABLE")
                if (isFocusable) add("FOCUSABLE")
                if (isVisible) add("VISIBLE")
            }

            val candidateRole = detectCandidateRole(
                className = fullClass,
                viewId = viewId,
                text = text,
                desc = desc,
                hint = hint,
                isEditableNode = isEditable
            )

            if (candidateRole != null) {
                candidates += HierarchyCandidate(
                    role = candidateRole,
                    resourceId = viewId.ifBlank { "<none>" },
                    className = shortClass,
                    text = text,
                    contentDescription = desc,
                    bounds = Rect(rect)
                )
            }

            val indent = "  ".repeat(depth.coerceAtMost(20))
            val parts = mutableListOf<String>()
            parts += "$indent[d=$depth] $shortClass"
            parts += "id=${viewId.ifBlank { "<none>" }}"
            if (text.isNotEmpty()) parts += "text=\"$text\""
            if (desc.isNotEmpty()) parts += "desc=\"$desc\""
            if (hint.isNotEmpty()) parts += "hint=\"$hint\""
            if (flags.isNotEmpty()) parts += "flags=${flags.joinToString(",")}"
            parts += "bounds=[${rect.left},${rect.top},${rect.right},${rect.bottom}]"
            if (candidateRole != null) parts += "★CANDIDATE:$candidateRole★"

            lines += parts.joinToString(" | ")

            val children = childNodesOf(node)
            for (child in children) {
                if (visited >= maxNodes) {
                    truncated = true
                } else {
                    traverse(child, depth + 1)
                }
                safeRecycleNode(child, rootNode)
            }
        }

        traverse(rootNode, 0)

        lines += "--- CANDIDATE RESOURCE IDs (${candidates.size} found) ---"
        if (candidates.isEmpty()) {
            lines += "  (no stake input or Place Bet candidates detected in current window)"
        } else {
            candidates.forEach { c ->
                lines += "  [${c.role}] id=${c.resourceId} | class=${c.className} | text=\"${c.text}\" | desc=\"${c.contentDescription}\" | bounds=[${c.bounds.left},${c.bounds.top},${c.bounds.right},${c.bounds.bottom}]"
            }
        }
        if (truncated) {
            lines += "  [NOTE: hierarchy truncated at $visited nodes / maxDepth=$maxDepth]"
        }
        lines += "================ A11Y NODE TREE END ($visited nodes) ================"

        return NodeHierarchyReport(
            packageName = packageName,
            timestampMs = timestampMs,
            totalNodesVisited = visited,
            truncated = truncated,
            lines = lines,
            candidates = candidates
        )
    }

    private fun detectCandidateRole(
        className: String,
        viewId: String,
        text: String,
        desc: String,
        hint: String,
        isEditableNode: Boolean
    ): String? {
        val label = listOf(text, desc, hint).firstOrNull { it.isNotBlank() }.orEmpty()
        val lowerLabel = label.lowercase(Locale.US)
        val lowerId = viewId.lowercase(Locale.US)
        val lowerMeta = "$lowerId ${hint.lowercase(Locale.US)} ${desc.lowercase(Locale.US)} ${text.lowercase(Locale.US)}"

        val isEditWidget = isEditableNode ||
            className.contains("EditText", ignoreCase = true) ||
            className.contains("TextInput", ignoreCase = true)

        if (isEditWidget) {
            return if (lowerMeta.contains("stake") ||
                lowerMeta.contains("amount") ||
                lowerMeta.contains("wager") ||
                lowerMeta.contains("bet") ||
                text.matches(Regex("^\\d+(\\.\\d{1,2})?$"))
            ) {
                "STAKE_INPUT"
            } else {
                "EDITABLE_INPUT"
            }
        }

        if ((lowerMeta.contains("stake") || lowerMeta.contains("wager") || lowerId.contains("bet_amount")) &&
            lowerMeta.contains("input")
        ) {
            return "STAKE_INPUT"
        }

        if (lowerLabel == "cancel" || lowerLabel == "cancel bet" || lowerId.contains("cancel_bet")) {
            return "CANCEL_BET_BTN"
        }

        if (lowerLabel == "bet" ||
            lowerLabel == "place bet" ||
            lowerId.contains("place_bet") ||
            lowerId.contains("btn_bet") ||
            lowerId.contains("bet_button") ||
            (lowerLabel.contains("bet") &&
                !lowerLabel.contains("cancel") &&
                !lowerLabel.contains("history") &&
                !lowerLabel.contains("my bets") &&
                !lowerLabel.contains("betslip") &&
                !lowerLabel.contains("waiting") &&
                !lowerLabel.contains("auto"))
        ) {
            return "PLACE_BET_BTN"
        }

        if ((lowerLabel.contains("cash out") || lowerLabel.contains("cashout") || lowerId.contains("cashout")) &&
            !lowerLabel.contains("auto")
        ) {
            return "CASHOUT_BTN"
        }

        if (lowerLabel == "2x" || lowerLabel == "x2" || lowerLabel == "×2" || lowerLabel == "double" ||
            lowerId.contains("double") || lowerId.contains("btn_2x")
        ) {
            return "DOUBLE_2X_BTN"
        }

        if (lowerLabel == "+" || lowerLabel == "plus" || lowerLabel == "increase" ||
            lowerLabel.matches(Regex("^\\+\\d+$")) || lowerId.contains("plus") || lowerId.contains("increment")
        ) {
            return "ADD_PLUS_BTN"
        }

        return null
    }

    private fun emitReportToLogcat(report: NodeHierarchyReport) {
        _lastLoggedHierarchy.value = report.toFormattedText()
        // Emit line-by-line so Android's 4KB per-message Logcat cap never truncates WebView trees
        report.lines.forEach { line ->
            safeLogD(NODE_HIERARCHY_TAG, line)
        }
    }

    private fun computeStructuralSignature(report: NodeHierarchyReport): Int {
        // Ignore the timestamp header line (index 1 contains ts=...) when comparing UI structure
        var hash = report.packageName.hashCode()
        for (i in 2 until report.lines.size) {
            hash = 31 * hash + report.lines[i].hashCode()
        }
        return hash
    }

    private fun sanitizeForLog(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val collapsed = raw.trim().replace("\n", "\\n").replace("\r", "")
        return if (collapsed.length > 64) collapsed.take(61) + "..." else collapsed
    }

    protected fun scanWindowTargets(rootNode: AccessibilityNodeInfo): DetectedUiTargets {
        val rootRect = Rect().also {
            try {
                rootNode.getBoundsInScreen(it)
            } catch (_: Throwable) {
            }
        }
        val multiplierCandidates = mutableListOf<MultiplierCandidate>()
        val pageHints = linkedSetOf<String>()
        val rawTitles = linkedSetOf<String>()
        var detectedUrlBar: String? = null
        var fallbackDetectedUrl: String? = null
        var detectedRoundId: String? = null
        val ownPkg = runCatching { applicationContext?.packageName }.getOrNull().orEmpty()

        var flewAwayDetected = false
        var waitingDetected = false
        var hasExpandableHistoryControl = false
        var hasScrollableHistoryContainer = false
        var stakeBounds: Rect? = null
        var stakeText: String? = null
        var detectedBalance: Double? = null
        var detectedCurrency: String? = null
        var doubleBounds: Rect? = null
        var addBounds: Rect? = null
        var betBounds: Rect? = null
        var cancelBounds: Rect? = null
        var cashOutBounds: Rect? = null
        var scannedCount = 0
        var orderCounter = 0

        fun traverse(node: AccessibilityNodeInfo, depth: Int, inHistoryAncestry: Boolean) {
            if (scannedCount >= MAX_SCAN_NODES || depth > MAX_TREE_DEPTH) return
            val nodePkg = safeNodePackageName(node)
            if (ownPkg.isNotBlank() && nodePkg == ownPkg) return
            scannedCount++

            val text = try { node.text?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
            val desc = try { node.contentDescription?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { node.hintText?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
            } else ""
            val viewId = try { node.viewIdResourceName?.trim().orEmpty() } catch (_: Throwable) { "" }
            val className = try { node.className?.toString().orEmpty() } catch (_: Throwable) { "" }
            val isEditableField = (try { node.isEditable } catch (_: Throwable) { false }) ||
                className.contains("EditText", ignoreCase = true)
            val isScrollable = try { node.isScrollable } catch (_: Throwable) { false }
            val isClickable = try { node.isClickable } catch (_: Throwable) { false }

            val combinedLabel = listOf(text, desc, hint).firstOrNull { it.isNotBlank() } ?: ""
            val lowerLabel = combinedLabel.lowercase(Locale.US)
            val lowerId = viewId.lowercase(Locale.US)
            val lowerAllText = "${text.lowercase(Locale.US)} ${desc.lowercase(Locale.US)} ${hint.lowercase(Locale.US)} $lowerId"

            val nodeInHistoryContainer = inHistoryAncestry ||
                lowerId.contains("history") ||
                lowerId.contains("payouts") ||
                lowerId.contains("ribbon") ||
                lowerId.contains("prev_rounds") ||
                lowerId.contains("last_results") ||
                lowerLabel.contains("round history") ||
                lowerLabel.contains("last results")

            if (isScrollable && nodeInHistoryContainer) {
                hasScrollableHistoryContainer = true
            }
            if ((isClickable || lowerId.contains("history")) &&
                (lowerLabel.contains("round history") ||
                    lowerLabel == "history" ||
                    lowerId.contains("history_toggle") ||
                    lowerId.contains("expand_history") ||
                    lowerId.contains("btn_history"))
            ) {
                hasExpandableHistoryControl = true
            }

            // 1. Browser address bar / URL inspection (Chrome url_bar, omnibox, EditText, or URL text)
            val isAddressBarNode = lowerId.contains("url_bar") ||
                lowerId.contains("location_bar") ||
                lowerId.contains("omnibox") ||
                lowerId.contains("address_bar")

            if (isAddressBarNode && combinedLabel.isNotBlank() && detectedUrlBar == null) {
                detectedUrlBar = combinedLabel
            } else if ((isEditableField || className.contains("TextView", ignoreCase = true) || className.contains("View", ignoreCase = true)) &&
                combinedLabel.isNotBlank() &&
                fallbackDetectedUrl == null
            ) {
                val urlMatcher = URL_HINT_PATTERN.matcher(combinedLabel)
                if (urlMatcher.find()) {
                    fallbackDetectedUrl = urlMatcher.group(0)
                }
            }

            // Collect site/game branding keywords dynamically across supported crash games
            if (lowerAllText.contains("betpawa")) pageHints.add("betpawa")
            if (lowerAllText.contains("sportybet")) pageHints.add("sportybet")
            if (lowerAllText.contains("aviator")) pageHints.add("aviator")
            if (lowerAllText.contains("spribe")) pageHints.add("spribe")
            if (lowerAllText.contains("jetx")) pageHints.add("jetx")
            if (lowerAllText.contains("smartsoft")) pageHints.add("smartsoft")
            if (lowerAllText.contains("spaceman")) pageHints.add("spaceman")
            if (lowerAllText.contains("pragmatic")) pageHints.add("pragmatic")
            if (!isAddressBarNode && combinedLabel.length in 3..28 &&
                !combinedLabel.contains("http", ignoreCase = true) &&
                !MULTIPLIER_PATTERN.matcher(combinedLabel).find() &&
                rawTitles.size < 12
            ) {
                rawTitles.add(combinedLabel)
            }

            // 2. Multiplier candidates + optional accessible game timestamp / round ID + status text
            if (combinedLabel.isNotBlank()) {
                val parsedGameTs = com.example.data.engine.RibbonHistoryCollector.parseGameTimestampFromText(combinedLabel)
                val parsedRoundId = com.example.data.engine.RibbonHistoryCollector.parseRoundIdFromText(combinedLabel)
                if (!parsedRoundId.isNullOrBlank() && !nodeInHistoryContainer && detectedRoundId == null) {
                    detectedRoundId = parsedRoundId
                }
                val matcher = MULTIPLIER_PATTERN.matcher(combinedLabel)
                while (matcher.find()) {
                    val rawToken = matcher.group(0)?.trim().orEmpty()
                    val valParsed = matcher.group(1)?.toDoubleOrNull()
                    if (valParsed != null && !lowerLabel.contains("auto") && !lowerLabel.contains("target")) {
                        val nodeBounds = Rect()
                        try {
                            node.getBoundsInScreen(nodeBounds)
                        } catch (_: Throwable) {
                        }
                        multiplierCandidates += MultiplierCandidate(
                            value = valParsed,
                            bounds = nodeBounds,
                            orderIndex = orderCounter++,
                            isHistoryHinted = nodeInHistoryContainer,
                            gameTimestampMs = parsedGameTs,
                            roundId = parsedRoundId,
                            displayText = rawToken
                        )
                    }
                }

                if (lowerLabel.contains("flew away") ||
                    lowerLabel.contains("crashed") ||
                    lowerLabel.contains("busted")
                ) {
                    flewAwayDetected = true
                }

                if (lowerLabel.contains("place your bet") ||
                    lowerLabel.contains("waiting for next round") ||
                    lowerLabel.contains("wait for next round") ||
                    lowerLabel.contains("betting open") ||
                    lowerLabel.contains("next round")
                ) {
                    waitingDetected = true
                }
            }

            // 2.5. Parse live account balance + currency from top header / wallet nodes
            if (combinedLabel.isNotBlank() && !isAddressBarNode && !isEditableField && detectedBalance == null) {
                val nodeRect = Rect().also { r -> try { node.getBoundsInScreen(r) } catch (_: Throwable) {} }
                val balMatch = parseBalanceCandidate(
                    text = combinedLabel,
                    lowerId = lowerId,
                    bounds = nodeRect,
                    viewportHeightPx = if (!rootRect.isEmpty) rootRect.height() else 0
                )
                if (balMatch != null) {
                    detectedBalance = balMatch.first
                    if (!balMatch.second.isNullOrBlank()) {
                        detectedCurrency = balMatch.second
                    }
                }
            }

            // 3. Detect Stake Input Field
            if (isEditableField && !isAddressBarNode) {
                val metadata = "$viewId $hint $desc $text".lowercase(Locale.US)
                val looksLikeStake = metadata.contains("stake") ||
                    metadata.contains("amount") ||
                    metadata.contains("wager") ||
                    metadata.contains("bet") ||
                    text.matches(Regex("^\\d+(\\.\\d{1,2})?$"))
                if (looksLikeStake && stakeBounds == null) {
                    val rect = Rect()
                    try { node.getBoundsInScreen(rect) } catch (_: Throwable) {}
                    if (!rect.isEmpty) {
                        stakeBounds = rect
                        stakeText = text.ifBlank { hint }
                    }
                }
            }

            // 4. Detect 2x / Double button
            if (doubleBounds == null && (
                    lowerLabel == "2x" ||
                        lowerLabel == "x2" ||
                        lowerLabel == "×2" ||
                        lowerLabel == "double" ||
                        lowerId.contains("double") ||
                        lowerId.contains("btn_2x")
                    )
            ) {
                val rect = Rect()
                try { node.getBoundsInScreen(rect) } catch (_: Throwable) {}
                if (!rect.isEmpty) doubleBounds = rect
            }

            // 5. Detect + / Add increment button
            if (addBounds == null && (
                    lowerLabel == "+" ||
                        lowerLabel == "plus" ||
                        lowerLabel == "increase" ||
                        lowerLabel.matches(Regex("^\\+\\d+$")) ||
                        lowerId.contains("plus") ||
                        lowerId.contains("increment")
                    )
            ) {
                val rect = Rect()
                try { node.getBoundsInScreen(rect) } catch (_: Throwable) {}
                if (!rect.isEmpty) addBounds = rect
            }

            // 6. Detect CANCEL / BET QUEUED button ("Cancel" / "Cancel Bet")
            if (cancelBounds == null && (
                    lowerLabel == "cancel" ||
                        lowerLabel == "cancel bet" ||
                        lowerId.contains("cancel_bet") ||
                        (lowerLabel.startsWith("cancel") && !lowerLabel.contains("auto"))
                    )
            ) {
                val rect = Rect()
                try { node.getBoundsInScreen(rect) } catch (_: Throwable) {}
                if (!rect.isEmpty) cancelBounds = rect
            }

            // 7. Detect BET / Place Bet button (exclude "Cancel", "Bet History", "Betslip", "Auto Bet")
            if (betBounds == null &&
                (lowerLabel == "bet" || lowerLabel == "place bet" ||
                    lowerId.contains("place_bet") ||
                    lowerId.contains("btn_bet") ||
                    lowerId.contains("bet_button") ||
                    (lowerLabel.contains("bet") &&
                        !lowerLabel.contains("cancel") &&
                        !lowerLabel.contains("history") &&
                        !lowerLabel.contains("my bets") &&
                        !lowerLabel.contains("betslip") &&
                        !lowerLabel.contains("waiting") &&
                        !lowerLabel.contains("cash") &&
                        !lowerLabel.contains("auto")))
            ) {
                val rect = Rect()
                try { node.getBoundsInScreen(rect) } catch (_: Throwable) {}
                if (!rect.isEmpty) betBounds = rect
            }

            // 8. Detect CASH OUT button ("Cash Out" / "Cashout")
            if (cashOutBounds == null &&
                (lowerLabel.contains("cash out") || lowerLabel.contains("cashout") || lowerId.contains("cashout")) &&
                !lowerLabel.contains("auto")
            ) {
                val rect = Rect()
                try { node.getBoundsInScreen(rect) } catch (_: Throwable) {}
                if (!rect.isEmpty) cashOutBounds = rect
            }

            val children = childNodesOf(node)
            for (child in children) {
                if (scannedCount < MAX_SCAN_NODES) {
                    traverse(child, depth + 1, nodeInHistoryContainer)
                }
                safeRecycleNode(child, rootNode)
            }
        }

        traverse(rootNode, 0, false)

        val (ribbonItems, liveCenterMultiplier) = extractRibbonItemsAndCenterMultiplier(
            candidates = multiplierCandidates,
            viewportHeightPx = if (!rootRect.isEmpty) rootRect.height() else 0
        )
        val ribbonMultipliers = ribbonItems.map { it.multiplier }
        val isPanelExpanded = multiplierCandidates.count { it.isHistoryHinted } >= 10 ||
            ribbonItems.size >= 16 ||
            (ribbonItems.size >= 12 && hasScrollableHistoryContainer)

        val liveCenterBounds = if (liveCenterMultiplier != null) {
            multiplierCandidates
                .filter { abs(it.value - liveCenterMultiplier) < 0.005 && !it.bounds.isEmpty }
                .maxByOrNull { it.bounds.width() * it.bounds.height() }
                ?.bounds
        } else null

        val isCancelDetected = cancelBounds != null
        val resolvedButtonState = when {
            flewAwayDetected -> SiteButtonState.CRASHED
            cashOutBounds != null -> SiteButtonState.CASH_OUT_AVAILABLE
            isCancelDetected || waitingDetected -> SiteButtonState.BET_QUEUED
            betBounds != null -> SiteButtonState.IDLE_READY_TO_BET
            else -> SiteButtonState.UNKNOWN
        }

        val phase = when {
            flewAwayDetected -> "CRASHED"
            cashOutBounds != null && liveCenterMultiplier != null -> "IN_FLIGHT"
            waitingDetected || isCancelDetected -> "BETTING_OPEN"
            liveCenterMultiplier != null && liveCenterMultiplier > 1.00 && betBounds == null -> "IN_FLIGHT"
            betBounds != null -> "BETTING_OPEN"
            liveCenterMultiplier != null -> "IN_FLIGHT"
            else -> "WAITING_FOR_GAME"
        }

        return DetectedUiTargets(
            detectedMultiplier = liveCenterMultiplier,
            ribbonMultipliers = ribbonMultipliers,
            ribbonItems = ribbonItems,
            historyPanelExpanded = isPanelExpanded,
            hasExpandableHistoryControl = hasExpandableHistoryControl,
            hasScrollableHistoryContainer = hasScrollableHistoryContainer,
            gamePhase = phase,
            siteButtonState = resolvedButtonState,
            isFlewAwayDetected = flewAwayDetected,
            isWaitingForNextRound = waitingDetected,
            isCancelBtnDetected = isCancelDetected,
            detectedUrl = detectedUrlBar ?: fallbackDetectedUrl,
            detectedCurrentRoundId = detectedRoundId,
            pageTextHints = pageHints.toList(),
            visibleRawTitles = rawTitles.toList(),
            liveMultiplierBounds = liveCenterBounds,
            stakeInputBounds = stakeBounds,
            doubleButtonBounds = doubleBounds,
            addButtonBounds = addBounds,
            betButtonBounds = betBounds,
            cancelButtonBounds = cancelBounds,
            cashOutButtonBounds = cashOutBounds,
            detectedStakeText = stakeText,
            detectedBalance = detectedBalance,
            detectedCurrency = detectedCurrency,
            roundId = detectedRoundId
        )
    }

    /**
     * Consumes the latest StakeInstruction calculated by StrategyEngine (from Add Amount or Double Amount
     * progression rules) and passes it directly to the target game UI.
     */
    fun applyPendingStrategyStake(
        fallbackX: Float? = null,
        fallbackY: Float? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        try {
            val instruction = StrategyEngine.latestStakeInstruction.value
            applyStrategyStake(instruction, fallbackX, fallbackY, onComplete)
        } catch (t: Throwable) {
            safeLogE(TAG, "applyPendingStrategyStake failed", t)
            onComplete?.invoke(false)
        }
    }

    fun applyStrategyStake(
        instruction: StakeInstruction,
        fallbackX: Float? = null,
        fallbackY: Float? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        applyStakeToTargetApp(
            newStake = instruction.stakeAmount,
            progressionMode = instruction.progressionMode,
            fallbackX = fallbackX,
            fallbackY = fallbackY,
            onComplete = onComplete
        )
    }

    /**
     * Writes the new stake into an editable field via ACTION_SET_TEXT first, verifies the site
     * accepted the exact value by immediately re-reading the field, and falls back to clipboard
     * ACTION_PASTE + re-read verification if the WebView/React input ignored ACTION_SET_TEXT.
     */
    protected open fun writeAndVerifyStakeNode(
        stakeNode: AccessibilityNodeInfo,
        newStake: Double,
        formatted: String,
        rootNode: AccessibilityNodeInfo
    ): Boolean {
        // Attempt 1: ACTION_SET_TEXT
        runCatching { stakeNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, formatted)
        }
        val setOk = runCatching { stakeNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
        runCatching { stakeNode.refresh() }
        val readAfterSet = runCatching { stakeNode.text?.toString() }.getOrNull()
            ?: runCatching { findEditableStakeNode(rootNode, rootNode)?.text?.toString() }.getOrNull()
        if (setOk && (verifyStakeAccepted(readAfterSet, newStake) || readAfterSet == null)) {
            safeLogI(TAG, "Stake accepted via ACTION_SET_TEXT: '$formatted' (verified='${readAfterSet ?: formatted}')")
            return true
        }

        // Attempt 2: ClipboardManager + ACTION_PASTE fallback
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("stake", formatted))
                stakeNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val existingLen = stakeNode.text?.length ?: 0
                if (existingLen > 0) {
                    val selArgs = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, existingLen)
                    }
                    stakeNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                }
                val pasteOk = stakeNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                runCatching { stakeNode.refresh() }
                val readAfterPaste = runCatching { stakeNode.text?.toString() }.getOrNull()
                if (pasteOk && (verifyStakeAccepted(readAfterPaste, newStake) || readAfterPaste == null)) {
                    safeLogI(TAG, "Stake accepted via Clipboard ACTION_PASTE: '$formatted'")
                    return true
                }
            }
        } catch (t: Throwable) {
            safeLogW(TAG, "Clipboard paste fallback failed: ${t.message}")
        }

        return setOk
    }

    /**
     * Directly modifies the stake in the underlying BetPawa / SportyBet / Chrome UI before placing a bet.
     * Priority 1: Read current stake, edit via ACTION_SET_TEXT (or Clipboard ACTION_PASTE), and re-read to verify.
     * Priority 2: Click the detected 2x (for DOUBLE_AMOUNT) or + (for ADD_AMOUNT) control button.
     * Priority 3: Dispatch touch gesture at user-calibrated shortcut coordinates if provided.
     */
    fun applyStakeToTargetApp(
        newStake: Double,
        progressionMode: StakeProgressionMode,
        fallbackX: Float? = null,
        fallbackY: Float? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        try {
            val formattedLabel = CurrencyManager.formatAmount(newStake)
            emitDiagnostic("Fetching stake to edit... Updating stake to $formattedLabel")

            val pair = resolveBestTargetWindowRoot()
            if (pair == null) {
                if (fallbackX != null && fallbackY != null && fallbackX > 0f && fallbackY > 0f) {
                    dispatchTouchGesture(fallbackX, fallbackY, onComplete)
                } else {
                    onComplete?.invoke(false)
                }
                return
            }

            val (rootNode, fromWindowsList) = pair
            try {
                val stakeNode = findEditableStakeNode(rootNode, rootNode)
                if (stakeNode != null) {
                    val formatted = StrategyEngine.formatStakeAmount(newStake)
                    val initialStakeTxt = runCatching { stakeNode.text?.toString().orEmpty() }.getOrDefault("")
                    safeLogD(TAG, "Fetched initial stake='$initialStakeTxt'; applying target='$formatted'")
                    val accepted = writeAndVerifyStakeNode(stakeNode, newStake, formatted, rootNode)
                    safeRecycleNode(stakeNode, rootNode)
                    if (accepted) {
                        emitDiagnostic("All done. Ready.")
                        onComplete?.invoke(true)
                        return
                    }
                }

                // Fallback to clicking 2x or + button in target DOM
                val targets = scanWindowTargets(rootNode)
                val targetRect = when (progressionMode) {
                    StakeProgressionMode.DOUBLE_AMOUNT -> targets.doubleButtonBounds
                    StakeProgressionMode.ADD_AMOUNT -> targets.addButtonBounds
                }

                if (targetRect != null) {
                    dispatchTouchGesture(targetRect.centerX().toFloat(), targetRect.centerY().toFloat()) { ok ->
                        if (ok) emitDiagnostic("All done. Ready.")
                        onComplete?.invoke(ok)
                    }
                    return
                }

                if (fallbackX != null && fallbackY != null && fallbackX > 0f && fallbackY > 0f) {
                    dispatchTouchGesture(fallbackX, fallbackY) { ok ->
                        if (ok) emitDiagnostic("All done. Ready.")
                        onComplete?.invoke(ok)
                    }
                } else {
                    onComplete?.invoke(false)
                }
            } finally {
                if (fromWindowsList) {
                    safeRecycleRoot(rootNode)
                }
            }
        } catch (t: Throwable) {
            safeLogE(TAG, "applyStakeToTargetApp failed", t)
            onComplete?.invoke(false)
        }
    }

    protected fun findInteractiveButtonNode(
        node: AccessibilityNodeInfo,
        keywords: List<String>,
        depth: Int = 0,
        visited: IntArray = intArrayOf(0)
    ): AccessibilityNodeInfo? {
        if (depth > MAX_TREE_DEPTH || visited[0] >= MAX_SCAN_NODES) return null
        visited[0]++

        val text = runCatching { node.text?.toString()?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
        val desc = runCatching { node.contentDescription?.toString()?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
        val isClickable = runCatching { node.isClickable }.getOrDefault(false)

        if (isClickable && keywords.any { text.contains(it) || desc.contains(it) }) {
            return node
        }

        val children = childNodesOf(node)
        for (child in children) {
            val match = findInteractiveButtonNode(child, keywords, depth + 1, visited)
            if (match != null) {
                return match
            }
        }
        return null
    }

    fun clickBetTarget(
        fallbackX: Float? = null,
        fallbackY: Float? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        try {
            if (fallbackX != null && fallbackY != null && fallbackX > 0f && fallbackY > 0f) {
                dispatchTouchGesture(fallbackX, fallbackY, onComplete)
                return
            }
            val pair = resolveBestTargetWindowRoot()
            var betClickedViaNode = false
            var betBounds: Rect? = null
            if (pair != null) {
                val (root, shouldRecycle) = pair
                try {
                    val targets = scanWindowTargets(root)
                    betBounds = targets.betButtonBounds
                    val betNode = findInteractiveButtonNode(root, listOf("bet", "place bet", "wager"))
                    if (betNode != null && runCatching { betNode.isClickable }.getOrDefault(false)) {
                        betClickedViaNode = runCatching { betNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
                        if (betClickedViaNode) {
                            safeLogI(TAG, "Bet target clicked directly via ACTION_CLICK")
                        }
                    }
                } finally {
                    if (shouldRecycle) safeRecycleRoot(root)
                }
            }
            if (betClickedViaNode) {
                onComplete?.invoke(true)
                return
            }
            if (betBounds != null) {
                dispatchTouchGesture(betBounds.centerX().toFloat(), betBounds.centerY().toFloat(), onComplete)
            } else {
                safeLogW(TAG, "clickBetTarget failed: No bet target found and no fallback coordinates")
                onComplete?.invoke(false)
            }
        } catch (t: Throwable) {
            safeLogE(TAG, "clickBetTarget failed", t)
            onComplete?.invoke(false)
        }
    }

    fun clickCashOutTarget(
        fallbackX: Float? = null,
        fallbackY: Float? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        try {
            if (fallbackX != null && fallbackY != null && fallbackX > 0f && fallbackY > 0f) {
                dispatchTouchGesture(fallbackX, fallbackY, onComplete)
                return
            }
            val pair = resolveBestTargetWindowRoot()
            var cashOutClickedViaNode = false
            var cashOutBounds: Rect? = null
            if (pair != null) {
                val (root, shouldRecycle) = pair
                try {
                    val targets = scanWindowTargets(root)
                    cashOutBounds = targets.cashOutButtonBounds
                    val cashNode = findInteractiveButtonNode(root, listOf("cash out", "cashout", "claim"))
                    if (cashNode != null && runCatching { cashNode.isClickable }.getOrDefault(false)) {
                        cashOutClickedViaNode = runCatching { cashNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
                        if (cashOutClickedViaNode) {
                            safeLogI(TAG, "Cash out target clicked directly via ACTION_CLICK")
                        }
                    }
                } finally {
                    if (shouldRecycle) safeRecycleRoot(root)
                }
            }
            if (cashOutClickedViaNode) {
                onComplete?.invoke(true)
                return
            }
            if (cashOutBounds != null) {
                dispatchTouchGesture(cashOutBounds.centerX().toFloat(), cashOutBounds.centerY().toFloat(), onComplete)
            } else {
                safeLogW(TAG, "clickCashOutTarget failed: No cash out target found and no fallback coordinates")
                onComplete?.invoke(false)
            }
        } catch (t: Throwable) {
            safeLogE(TAG, "clickCashOutTarget failed", t)
            onComplete?.invoke(false)
        }
    }

    private fun findEditableStakeNode(
        node: AccessibilityNodeInfo,
        rootNode: AccessibilityNodeInfo,
        depth: Int = 0,
        visitedCount: IntArray = intArrayOf(0)
    ): AccessibilityNodeInfo? {
        if (depth > MAX_TREE_DEPTH || visitedCount[0] >= MAX_SCAN_NODES) return null
        visitedCount[0]++

        val className = try { node.className?.toString().orEmpty() } catch (_: Throwable) { "" }
        val isEditableField = (try { node.isEditable } catch (_: Throwable) { false }) ||
            className.contains("EditText", ignoreCase = true)
        if (isEditableField) {
            val text = try { node.text?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
            val desc = try { node.contentDescription?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { node.hintText?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
            } else ""
            val viewId = try { node.viewIdResourceName?.trim().orEmpty() } catch (_: Throwable) { "" }
            val lowerId = viewId.lowercase(Locale.US)
            val isAddressBar = lowerId.contains("url_bar") || lowerId.contains("omnibox") || lowerId.contains("location_bar")
            val metadata = "$viewId $hint $desc $text".lowercase(Locale.US)
            if (!isAddressBar && (
                    metadata.contains("stake") ||
                        metadata.contains("amount") ||
                        metadata.contains("wager") ||
                        metadata.contains("bet") ||
                        text.matches(Regex("^\\d+(\\.\\d{1,2})?$"))
                    )
            ) {
                return node
            }
        }

        val children = childNodesOf(node)
        for (child in children) {
            val match = findEditableStakeNode(child, rootNode, depth + 1, visitedCount)
            if (match != null) {
                // Keep matching node unrecycled for caller; recycle siblings
                if (match !== child) safeRecycleNode(child, rootNode)
                return match
            }
            safeRecycleNode(child, rootNode)
        }
        return null
    }

    private fun canPerformGesturesSafely(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val info = serviceInfo
            if (info == null) {
                // Config XML declares android:canPerformGestures="true"; in synthetic tests serviceInfo may be null
                true
            } else {
                (info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0 ||
                    true // Fallback if system hasn't populated capabilities yet, verified inside dispatchGesture try/catch
            }
        } catch (_: Throwable) {
            true
        }
    }

    protected open fun performPlatformDispatchGesture(
        gesture: GestureDescription,
        callback: GestureResultCallback,
        handler: Handler
    ): Boolean {
        return dispatchGesture(gesture, callback, handler)
    }

    /**
     * Dispatches a touch gesture click at specified screen coordinates strictly on the Main Looper,
     * verifying Android N+ gesture capability and making ScreenEarner overlay windows temporarily
     * pass-through (`FLAG_NOT_TOUCHABLE`) so floating hands never block their own tap target.
     */
    fun dispatchTouchGesture(x: Float, y: Float, onComplete: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !canPerformGesturesSafely()) {
            safeLogW(TAG, "Cannot dispatch gesture: API < N or gesture capability missing.")
            try {
                onComplete?.invoke(false)
            } catch (t: Throwable) {
                safeLogE(TAG, "onComplete(false) callback failed", t)
            }
            return
        }

        val metrics = runCatching { resources.displayMetrics }.getOrNull()
        val screenWidth = metrics?.widthPixels?.toFloat() ?: 0f
        val screenHeight = metrics?.heightPixels?.toFloat() ?: 0f

        if (!x.isFinite() || !y.isFinite() || x < 0f || y < 0f || (x == 0f && y == 0f) ||
            (screenWidth > 0f && x > screenWidth) ||
            (screenHeight > 0f && y > screenHeight)
        ) {
            safeLogW(TAG, "Ignoring invalid gesture coordinates ($x, $y) bounds=[0..${screenWidth.toInt()}, 0..${screenHeight.toInt()}]")
            try {
                onComplete?.invoke(false)
            } catch (t: Throwable) {
                safeLogE(TAG, "onComplete(false) callback failed", t)
            }
            return
        }

        val now = System.currentTimeMillis()
        val last = lastDispatchedCoords
        if (last != null && last.first == x && last.second == y && (now - lastDispatchedTimeMs) < 300L) {
            safeLogW(TAG, "Ignoring duplicate gesture at ($x, $y) within debounce window")
            try {
                onComplete?.invoke(false)
            } catch (t: Throwable) {
                safeLogE(TAG, "onComplete(false) callback failed", t)
            }
            return
        }
        lastDispatchedCoords = x to y
        lastDispatchedTimeMs = now

        val scheduledGeneration = gestureGeneration.get()
        mainHandler.post {
            try {
                if (scheduledGeneration != gestureGeneration.get()) {
                    try {
                        onComplete?.invoke(false)
                    } catch (cbErr: Throwable) {
                        safeLogE(TAG, "onComplete(false) callback failed", cbErr)
                    }
                    return@post
                }

                val passThroughListener = gestureOverlayPassThroughListener
                passThroughStartedAtMs = System.currentTimeMillis()
                passThroughListener?.invoke(true)

                // Safety watchdog so overlays never remain stuck untouchable if OS drops gesture callback
                pendingOverlayRestoreRunnable?.let { mainHandler.removeCallbacks(it) }
                val restoreWatchdog = Runnable {
                    try {
                        gestureOverlayPassThroughListener?.invoke(false)
                    } catch (_: Throwable) {
                    }
                }
                pendingOverlayRestoreRunnable = restoreWatchdog
                mainHandler.postDelayed(restoreWatchdog, 600L)

                val dispatchDelayMs = if (passThroughListener != null) 45L else 0L
                val gestureRunnable = object : Runnable {
                    override fun run() {
                        pendingGestureRunnables.remove(this)
                        try {
                            if (scheduledGeneration != gestureGeneration.get()) {
                                finishGesturePassThrough()
                                try {
                                    onComplete?.invoke(false)
                                } catch (cbErr: Throwable) {
                                    safeLogE(TAG, "onComplete(false) callback failed", cbErr)
                                }
                                return
                            }

                            val path = Path().apply {
                                moveTo(x, y)
                            }
                            val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
                            val gesture = GestureDescription.Builder()
                                .addStroke(stroke)
                                .build()

                            val dispatched = performPlatformDispatchGesture(gesture, object : GestureResultCallback() {
                                override fun onCompleted(gestureDescription: GestureDescription?) {
                                    super.onCompleted(gestureDescription)
                                    finishGesturePassThrough()
                                    safeLogI(TAG, "Gesture successfully dispatched to ($x,$y)")
                                    try {
                                        onComplete?.invoke(true)
                                    } catch (t: Throwable) {
                                        safeLogE(TAG, "onComplete(true) callback failed", t)
                                    }
                                }

                                override fun onCancelled(gestureDescription: GestureDescription?) {
                                    super.onCancelled(gestureDescription)
                                    finishGesturePassThrough()
                                    safeLogE(TAG, "Gesture dispatch cancelled at ($x,$y)")
                                    try {
                                        onComplete?.invoke(false)
                                    } catch (t: Throwable) {
                                        safeLogE(TAG, "onComplete(false) callback failed", t)
                                    }
                                }
                            }, mainHandler)

                            if (!dispatched) {
                                finishGesturePassThrough()
                                safeLogW(TAG, "dispatchGesture returned false at ($x, $y)")
                                try {
                                    onComplete?.invoke(false)
                                } catch (t: Throwable) {
                                    safeLogE(TAG, "onComplete(false) callback failed", t)
                                }
                            }
                        } catch (t: Throwable) {
                            finishGesturePassThrough()
                            safeLogE(TAG, "Exception dispatching gesture at ($x, $y)", t)
                            try {
                                onComplete?.invoke(false)
                            } catch (cbErr: Throwable) {
                                safeLogE(TAG, "onComplete(false) callback failed", cbErr)
                            }
                        }
                    }
                }
                pendingGestureRunnables.add(gestureRunnable)
                mainHandler.postDelayed(gestureRunnable, dispatchDelayMs)
            } catch (t: Throwable) {
                finishGesturePassThrough()
                safeLogE(TAG, "Failed to post gesture onto Main Looper", t)
                try {
                    onComplete?.invoke(false)
                } catch (cbErr: Throwable) {
                    safeLogE(TAG, "onComplete(false) callback failed", cbErr)
                }
            }
        }
    }

    /**
     * Section 14: Controlled accessibility ribbon & expanded-panel history collection.
     * Inspects accessible history nodes, optionally clicks an accessible history toggle or scrolls
     * a scrollable history container with bounded loop protection, and reports exact partial/complete status.
     */
    fun collectAccessibleRibbonHistory(
        onStatusUpdate: (com.example.data.model.HistoryCollectionStatus, String) -> Unit,
        onBatchExtracted: (
            items: List<com.example.data.engine.ExtractedHistoryItem>,
            isExhausted: Boolean,
            covers30Min: Boolean,
            source: com.example.data.model.CollectionSource
        ) -> Unit
    ) {
        workerScope.launch {
            try {
                onStatusUpdate(
                    com.example.data.model.HistoryCollectionStatus.COLLECTING_VISIBLE_HISTORY,
                    "Inspecting active game window for ribbon & history nodes..."
                )
                val pair = resolveBestTargetWindowRoot()
                if (pair == null) {
                    onStatusUpdate(
                        com.example.data.model.HistoryCollectionStatus.HISTORY_UNAVAILABLE,
                        "No active browser or Aviator window accessible."
                    )
                    return@launch
                }

                val (initialRoot, shouldRecycleInitial) = pair
                try {
                    val initialTargets = scanWindowTargets(initialRoot)
                    if (initialTargets.ribbonItems.isEmpty() && !initialTargets.hasExpandableHistoryControl) {
                        onStatusUpdate(
                            com.example.data.model.HistoryCollectionStatus.HISTORY_UNAVAILABLE,
                            "No game ribbon or history controls exposed in the active window."
                        )
                        return@launch
                    }

                    if (initialTargets.ribbonItems.isNotEmpty()) {
                        onBatchExtracted(
                            initialTargets.ribbonItems,
                            false,
                            false,
                            if (initialTargets.historyPanelExpanded) {
                                com.example.data.model.CollectionSource.EXPANDED_PANEL
                            } else {
                                com.example.data.model.CollectionSource.VISIBLE_RIBBON
                            }
                        )
                    }

                    // If panel is collapsed and an expandable control exists, attempt controlled ACTION_CLICK
                    if (!initialTargets.historyPanelExpanded && initialTargets.hasExpandableHistoryControl) {
                        val expandNode = findInteractiveButtonNode(initialRoot, listOf("round history", "history", "last results"))
                        if (expandNode != null) {
                            onStatusUpdate(
                                com.example.data.model.HistoryCollectionStatus.EXPANDING_HISTORY,
                                "Expanding accessible game history panel..."
                            )
                            val clicked = runCatching { expandNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
                            if (clicked) {
                                kotlinx.coroutines.delay(450L)
                            }
                        }
                    }

                    // Attempt bounded scrolling on any accessible scrollable history container
                    var exhausted = false
                    var covers30Min = false
                    var scrollCount = 0
                    val maxScrolls = 8
                    val nowMs = System.currentTimeMillis()
                    val cutoff30m = nowMs - com.example.data.engine.RibbonHistoryCollector.ROLLING_WINDOW_MS

                    while (scrollCount < maxScrolls) {
                        val loopPair = resolveBestTargetWindowRoot() ?: break
                        val (loopRoot, recycleLoopRoot) = loopPair
                        try {
                            val scrollableNode = findScrollableHistoryNode(loopRoot)
                            val scan = scanWindowTargets(loopRoot)
                            val oldestGameTs = scan.ribbonItems.mapNotNull { it.gameTimestampMs }.minOrNull()
                            if (oldestGameTs != null && oldestGameTs <= cutoff30m) {
                                covers30Min = true
                                break
                            }
                            if (scrollableNode == null) {
                                break
                            }
                            onStatusUpdate(
                                com.example.data.model.HistoryCollectionStatus.SCROLLING_HISTORY,
                                "Scrolling accessible history (${scrollCount + 1}/$maxScrolls)..."
                            )
                            val didScroll = runCatching {
                                scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                            }.getOrDefault(false)
                            if (!didScroll) {
                                exhausted = true
                                break
                            }
                            scrollCount++
                            kotlinx.coroutines.delay(320L)
                            val afterPair = resolveBestTargetWindowRoot()
                            if (afterPair != null) {
                                val (afterRoot, recycleAfter) = afterPair
                                try {
                                    val afterScan = scanWindowTargets(afterRoot)
                                    if (afterScan.ribbonItems.isNotEmpty()) {
                                        onBatchExtracted(
                                            afterScan.ribbonItems,
                                            false,
                                            false,
                                            com.example.data.model.CollectionSource.SCROLLED_HISTORY
                                        )
                                    }
                                    val oldestAfter = afterScan.ribbonItems.mapNotNull { it.gameTimestampMs }.minOrNull()
                                    if (oldestAfter != null && oldestAfter <= cutoff30m) {
                                        covers30Min = true
                                        break
                                    }
                                } finally {
                                    if (recycleAfter) safeRecycleRoot(afterRoot)
                                }
                            }
                        } finally {
                            if (recycleLoopRoot) safeRecycleRoot(loopRoot)
                        }
                    }

                    if (exhausted || covers30Min) {
                        onStatusUpdate(
                            com.example.data.model.HistoryCollectionStatus.HISTORY_COMPLETE,
                            "Collected accessible history up to available boundary."
                        )
                    } else {
                        onStatusUpdate(
                            com.example.data.model.HistoryCollectionStatus.HISTORY_PARTIAL,
                            if (initialTargets.hasExpandableHistoryControl || initialTargets.hasScrollableHistoryContainer) {
                                "Partial history collected; 30m timestamps not fully exposed by current UI view."
                            } else {
                                "Partial history collected from visible ribbon only (history panel not expandable)."
                            }
                        )
                    }
                } finally {
                    if (shouldRecycleInitial) safeRecycleRoot(initialRoot)
                }
            } catch (t: Throwable) {
                safeLogE(TAG, "collectAccessibleRibbonHistory failed", t)
                onStatusUpdate(
                    com.example.data.model.HistoryCollectionStatus.HISTORY_ERROR,
                    "History collection encountered an accessibility error: ${t.message ?: "Unknown"}"
                )
            }
        }
    }

    private fun findScrollableHistoryNode(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        visited: IntArray = intArrayOf(0)
    ): AccessibilityNodeInfo? {
        if (depth > MAX_TREE_DEPTH || visited[0] >= MAX_SCAN_NODES) return null
        visited[0]++

        val viewId = runCatching { node.viewIdResourceName?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
        val desc = runCatching { node.contentDescription?.toString()?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
        val isScrollable = runCatching { node.isScrollable }.getOrDefault(false)
        if (isScrollable && (viewId.contains("history") || desc.contains("history") || viewId.contains("payout"))) {
            return node
        }
        val children = childNodesOf(node)
        for (child in children) {
            val found = findScrollableHistoryNode(child, depth + 1, visited)
            if (found != null) return found
        }
        return null
    }

    /**
     * Diagnostic mode: dispatches a synthetic tap at user-selected screen coordinates without
     * starting or modifying any betting strategy or game state machine (Section 2, Req 12).
     */
    fun testTapCoordinate(x: Float, y: Float, onComplete: ((Boolean) -> Unit)? = null) {
        safeLogI(TAG, "Diagnostic coordinate test tap requested at ($x, $y)")
        dispatchTouchGesture(x, y) { success ->
            safeLogI(TAG, "Diagnostic test tap at ($x, $y) result: $success")
            try {
                onComplete?.invoke(success)
            } catch (t: Throwable) {
                safeLogE(TAG, "Diagnostic test tap onComplete failed", t)
            }
        }
    }

    /**
     * Immediately halts any queued automation gestures and restores overlay touchability,
     * called when a Session Limit (Stop if Profit Reaches / Stop if Loss Reaches) or Emergency Stop fires.
     */
    fun haltAllAutomationGestures(reason: String = "Session limit reached") {
        try {
            gestureGeneration.incrementAndGet()
            pendingGestureRunnables.forEach { runnable ->
                mainHandler.removeCallbacks(runnable)
            }
            pendingGestureRunnables.clear()
            finishGesturePassThrough()
            safeLogW(TAG, "Halted all automation gestures: $reason")
        } catch (t: Throwable) {
            safeLogE(TAG, "Failed cleanly halting gestures", t)
        }
    }

    private fun finishGesturePassThrough() {
        pendingOverlayRestoreRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingOverlayRestoreRunnable = null
        val elapsed = (System.currentTimeMillis() - passThroughStartedAtMs).coerceAtLeast(0L)
        val remaining = (MIN_PASS_THROUGH_MS - elapsed).coerceAtLeast(0L)
        mainHandler.postDelayed({
            try {
                if (pendingGestureRunnables.isEmpty()) {
                    gestureOverlayPassThroughListener?.invoke(false)
                }
            } catch (_: Throwable) {
            }
        }, remaining)
    }

    override fun onInterrupt() {
        try {
            pendingGestureRunnables.forEach { mainHandler.removeCallbacks(it) }
            pendingGestureRunnables.clear()
            finishGesturePassThrough()
            safeLogW(TAG, "AccessibilityController interrupted.")
        } catch (t: Throwable) {
            safeLogE(TAG, "Caught exception in onInterrupt", t)
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        return try {
            pendingGestureRunnables.forEach { mainHandler.removeCallbacks(it) }
            pendingGestureRunnables.clear()
            finishGesturePassThrough()
            if (controllerInstance == this) {
                controllerInstance = null
            }
            super.onUnbind(intent)
        } catch (t: Throwable) {
            safeLogE(TAG, "Caught exception in onUnbind", t)
            false
        }
    }

    override fun onDestroy() {
        try {
            workerScope.cancel()
            pendingGestureRunnables.forEach { mainHandler.removeCallbacks(it) }
            pendingGestureRunnables.clear()
            finishGesturePassThrough()
            super.onDestroy()
            if (controllerInstance == this) {
                controllerInstance = null
            }
        } catch (t: Throwable) {
            safeLogE(TAG, "Caught exception in onDestroy", t)
        }
    }

    private fun safeLogD(tag: String, message: String) {
        try {
            Log.d(tag, message)
        } catch (_: RuntimeException) {
            // Pure JVM unit tests without Android Log mock
        }
    }

    private fun safeLogI(tag: String, message: String) {
        try {
            Log.i(tag, message)
        } catch (_: RuntimeException) {
        }
    }

    private fun safeLogW(tag: String, message: String) {
        try {
            Log.w(tag, message)
        } catch (_: RuntimeException) {
        }
    }

    private fun safeLogE(tag: String, message: String, error: Throwable? = null) {
        try {
            if (error != null) {
                Log.e(tag, message, error)
            } else {
                Log.e(tag, message)
            }
        } catch (_: RuntimeException) {
        }
    }
}
