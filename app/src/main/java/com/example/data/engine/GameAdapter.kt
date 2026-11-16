package com.example.data.engine

import com.example.data.model.ColorClassifier
import com.example.data.model.CrashColorCategory
import com.example.service.DetectedUiTargets
import java.util.Locale

data class GameDetectionSnapshot(
    val gameId: String,
    val displayedGameName: String,
    val supportedPlatforms: List<String>,
    val platformName: String,
    val liveMultiplier: Double?,
    val crashMultiplier: Double?,
    val detectedColorHex: String,
    val colorCategory: CrashColorCategory,
    val isPixelColorVerified: Boolean,
    val roundState: String,
    val roundIdentifier: String?,
    val confidence: Float = 0f,
    val lastDetectionTimestampMs: Long,
    val liveDetectionLost: Boolean,
    val detectedUrl: String?,
    val sourcePackage: String,
    val ribbonItems: List<ExtractedHistoryItem>
)

interface GameAdapter {
    val gameId: String
    val displayedGameName: String
    val supportedPlatforms: List<String>

    fun detectGame(
        packageName: String,
        url: String?,
        pageHints: List<String>,
        rawTexts: List<String>
    ): Boolean

    fun detectLiveMultiplier(targets: DetectedUiTargets): Double?

    fun detectCrashMultiplier(
        targets: DetectedUiTargets,
        lastInFlightMultiplier: Double?,
        previousRibbon: List<Double>
    ): Double?

    fun detectMultiplierColor(
        pixelSampleHex: String?,
        multiplier: Double?,
        lowThreshold: Double = 2.00,
        bigThreshold: Double = 10.00
    ): Triple<String, CrashColorCategory, Boolean> {
        if (!pixelSampleHex.isNullOrBlank()) {
            val normalized = ColorClassifier.normalizeHex(pixelSampleHex)
            val category = ColorClassifier.classifyHex(normalized)
            return Triple(normalized, category, true)
        }
        val cat = if (multiplier != null && multiplier >= 1.0) {
            ColorClassifier.fallbackCategoryForMultiplier(multiplier, lowThreshold, bigThreshold)
        } else {
            CrashColorCategory.Other
        }
        return Triple(cat.defaultHex, cat, false)
    }

    fun detectRoundState(
        targets: DetectedUiTargets,
        hasActiveGameBefore: Boolean,
        nowMs: Long,
        lastValidDetectionMs: Long
    ): String

    fun detectRoundIdentifier(targets: DetectedUiTargets): String? =
        targets.ribbonItems.firstOrNull()?.roundId
}

class AviatorGameAdapter : GameAdapter {
    override val gameId: String = "aviator"
    override val displayedGameName: String = "Aviator"
    override val supportedPlatforms: List<String> = listOf(
        "SportyBet",
        "BetPawa",
        "Chrome Web",
        "Mobile Browser",
        "Spribe Aviator"
    )

    override fun detectGame(
        packageName: String,
        url: String?,
        pageHints: List<String>,
        rawTexts: List<String>
    ): Boolean {
        val lowerUrl = url?.lowercase(Locale.US).orEmpty()
        val lowerPkg = packageName.lowercase(Locale.US)
        val combined = (pageHints + rawTexts).joinToString(" ").lowercase(Locale.US)
        if (lowerUrl.contains("jetx") || lowerUrl.contains("spaceman")) return false
        return lowerUrl.contains("aviator") ||
            lowerUrl.contains("spribe") ||
            lowerUrl.contains("betpawa") ||
            lowerUrl.contains("sportybet") ||
            combined.contains("aviator") ||
            combined.contains("spribe") ||
            combined.contains("flew away") ||
            lowerPkg.contains("betpawa") ||
            lowerPkg.contains("sportybet")
    }

    override fun detectLiveMultiplier(targets: DetectedUiTargets): Double? {
        return targets.detectedMultiplier?.takeIf { it.isFinite() && it >= 1.0 }
    }

    override fun detectCrashMultiplier(
        targets: DetectedUiTargets,
        lastInFlightMultiplier: Double?,
        previousRibbon: List<Double>
    ): Double? {
        if (targets.gamePhase == "CRASHED" || targets.isFlewAwayDetected) {
            val direct = targets.detectedMultiplier?.takeIf { it.isFinite() && it >= 1.0 }
            if (direct != null) return direct
            if (lastInFlightMultiplier != null && lastInFlightMultiplier >= 1.0) return lastInFlightMultiplier
        }
        val currentRibbon = targets.ribbonMultipliers
        return GameDetection.detectNewRibbonHead(
            previousRibbon = previousRibbon,
            currentRibbon = currentRibbon,
            sawInFlightOrCrashSinceSync = lastInFlightMultiplier != null || targets.isFlewAwayDetected
        )
    }

    override fun detectRoundState(
        targets: DetectedUiTargets,
        hasActiveGameBefore: Boolean,
        nowMs: Long,
        lastValidDetectionMs: Long
    ): String {
        if (targets.detectedMultiplier == null &&
            targets.ribbonItems.isEmpty() &&
            !targets.isFlewAwayDetected &&
            !targets.isWaitingForNextRound &&
            hasActiveGameBefore &&
            lastValidDetectionMs > 0L &&
            (nowMs - lastValidDetectionMs) > LIVE_DETECTION_TIMEOUT_MS
        ) {
            return "LIVE_DETECTION_LOST"
        }
        return targets.gamePhase
    }

    companion object {
        const val LIVE_DETECTION_TIMEOUT_MS = 4_500L
    }
}

class JetXGameAdapter : GameAdapter {
    override val gameId: String = "jetx"
    override val displayedGameName: String = "JetX"
    override val supportedPlatforms: List<String> = listOf(
        "SmartSoft JetX",
        "BetPawa",
        "SportyBet",
        "Chrome Web"
    )

    override fun detectGame(
        packageName: String,
        url: String?,
        pageHints: List<String>,
        rawTexts: List<String>
    ): Boolean {
        val lowerUrl = url?.lowercase(Locale.US).orEmpty()
        val combined = (pageHints + rawTexts).joinToString(" ").lowercase(Locale.US)
        return lowerUrl.contains("jetx") ||
            combined.contains("jetx") ||
            combined.contains("smartsoft") ||
            combined.contains("collect") && combined.contains("jet")
    }

    override fun detectLiveMultiplier(targets: DetectedUiTargets): Double? =
        targets.detectedMultiplier?.takeIf { it.isFinite() && it >= 1.0 }

    override fun detectCrashMultiplier(
        targets: DetectedUiTargets,
        lastInFlightMultiplier: Double?,
        previousRibbon: List<Double>
    ): Double? {
        if (targets.gamePhase == "CRASHED" || targets.isFlewAwayDetected) {
            targets.detectedMultiplier?.takeIf { it >= 1.0 }?.let { return it }
            if (lastInFlightMultiplier != null && lastInFlightMultiplier >= 1.0) return lastInFlightMultiplier
        }
        return GameDetection.detectNewRibbonHead(
            previousRibbon = previousRibbon,
            currentRibbon = targets.ribbonMultipliers,
            sawInFlightOrCrashSinceSync = lastInFlightMultiplier != null
        )
    }

    override fun detectRoundState(
        targets: DetectedUiTargets,
        hasActiveGameBefore: Boolean,
        nowMs: Long,
        lastValidDetectionMs: Long
    ): String {
        if (targets.detectedMultiplier == null &&
            targets.ribbonItems.isEmpty() &&
            hasActiveGameBefore &&
            lastValidDetectionMs > 0L &&
            (nowMs - lastValidDetectionMs) > AviatorGameAdapter.LIVE_DETECTION_TIMEOUT_MS
        ) {
            return "LIVE_DETECTION_LOST"
        }
        return targets.gamePhase
    }
}

class SpacemanGameAdapter : GameAdapter {
    override val gameId: String = "spaceman"
    override val displayedGameName: String = "Spaceman"
    override val supportedPlatforms: List<String> = listOf(
        "Pragmatic Spaceman",
        "SportyBet",
        "Chrome Web"
    )

    override fun detectGame(
        packageName: String,
        url: String?,
        pageHints: List<String>,
        rawTexts: List<String>
    ): Boolean {
        val lowerUrl = url?.lowercase(Locale.US).orEmpty()
        val combined = (pageHints + rawTexts).joinToString(" ").lowercase(Locale.US)
        return lowerUrl.contains("spaceman") ||
            combined.contains("spaceman") ||
            combined.contains("pragmatic")
    }

    override fun detectLiveMultiplier(targets: DetectedUiTargets): Double? =
        targets.detectedMultiplier?.takeIf { it.isFinite() && it >= 1.0 }

    override fun detectCrashMultiplier(
        targets: DetectedUiTargets,
        lastInFlightMultiplier: Double?,
        previousRibbon: List<Double>
    ): Double? {
        if (targets.gamePhase == "CRASHED" || targets.isFlewAwayDetected) {
            targets.detectedMultiplier?.takeIf { it >= 1.0 }?.let { return it }
            if (lastInFlightMultiplier != null && lastInFlightMultiplier >= 1.0) return lastInFlightMultiplier
        }
        return GameDetection.detectNewRibbonHead(
            previousRibbon = previousRibbon,
            currentRibbon = targets.ribbonMultipliers,
            sawInFlightOrCrashSinceSync = lastInFlightMultiplier != null
        )
    }

    override fun detectRoundState(
        targets: DetectedUiTargets,
        hasActiveGameBefore: Boolean,
        nowMs: Long,
        lastValidDetectionMs: Long
    ): String {
        if (targets.detectedMultiplier == null &&
            targets.ribbonItems.isEmpty() &&
            hasActiveGameBefore &&
            lastValidDetectionMs > 0L &&
            (nowMs - lastValidDetectionMs) > AviatorGameAdapter.LIVE_DETECTION_TIMEOUT_MS
        ) {
            return "LIVE_DETECTION_LOST"
        }
        return targets.gamePhase
    }
}

class GenericCrashGameAdapter(
    private val dynamicGameId: String = "unknown",
    private val dynamicGameTitle: String = "Unknown"
) : GameAdapter {
    override val gameId: String = dynamicGameId
    override val displayedGameName: String = dynamicGameTitle
    override val supportedPlatforms: List<String> = listOf("Chrome Web", "Mobile Browser", "Native App")

    override fun detectGame(
        packageName: String,
        url: String?,
        pageHints: List<String>,
        rawTexts: List<String>
    ): Boolean {
        val pkgLower = packageName.lowercase(Locale.US)
        val combined = (pageHints + rawTexts).joinToString(" ").lowercase(Locale.US)
        return pkgLower.contains("chrome") ||
            pkgLower.contains("browser") ||
            combined.contains("crash") ||
            combined.contains("round history") ||
            combined.contains("flew away") ||
            combined.contains("multiplier")
    }

    override fun detectLiveMultiplier(targets: DetectedUiTargets): Double? =
        targets.detectedMultiplier?.takeIf { it.isFinite() && it >= 1.0 }

    override fun detectCrashMultiplier(
        targets: DetectedUiTargets,
        lastInFlightMultiplier: Double?,
        previousRibbon: List<Double>
    ): Double? {
        if (targets.gamePhase == "CRASHED" || targets.isFlewAwayDetected) {
            targets.detectedMultiplier?.takeIf { it >= 1.0 }?.let { return it }
            if (lastInFlightMultiplier != null && lastInFlightMultiplier >= 1.0) return lastInFlightMultiplier
        }
        return GameDetection.detectNewRibbonHead(
            previousRibbon = previousRibbon,
            currentRibbon = targets.ribbonMultipliers,
            sawInFlightOrCrashSinceSync = lastInFlightMultiplier != null
        )
    }

    override fun detectRoundState(
        targets: DetectedUiTargets,
        hasActiveGameBefore: Boolean,
        nowMs: Long,
        lastValidDetectionMs: Long
    ): String {
        if (targets.detectedMultiplier == null &&
            targets.ribbonItems.isEmpty() &&
            hasActiveGameBefore &&
            lastValidDetectionMs > 0L &&
            (nowMs - lastValidDetectionMs) > AviatorGameAdapter.LIVE_DETECTION_TIMEOUT_MS
        ) {
            return "LIVE_DETECTION_LOST"
        }
        return targets.gamePhase
    }
}

object GameAdapterRegistry {
    private val specificAdapters: List<GameAdapter> = listOf(
        JetXGameAdapter(),
        SpacemanGameAdapter(),
        AviatorGameAdapter()
    )

    private val ignoredTitleTokens = setOf(
        "bet", "place bet", "cancel", "cash out", "cashout", "auto", "history",
        "round history", "last results", "all bets", "my bets", "top", "predict manager",
        "screen earner", "chrome", "browser", "home", "menu", "login", "deposit",
        "play", "game", "crash", "live", "lobby", "index", "start", "app", "webview"
    )

    fun allAdapters(): List<GameAdapter> = specificAdapters + GenericCrashGameAdapter()

    fun resolveAdapter(
        packageName: String,
        url: String?,
        pageHints: List<String>,
        rawTexts: List<String> = emptyList(),
        hasMultiplierSignals: Boolean = false
    ): GameAdapter? {
        specificAdapters.firstOrNull {
            it.detectGame(packageName, url, pageHints, rawTexts)
        }?.let { return it }

        if (hasMultiplierSignals || GenericCrashGameAdapter().detectGame(packageName, url, pageHints, rawTexts)) {
            val slug = url?.substringAfterLast("/")
                ?.substringBefore("?")
                ?.replace(Regex("[^a-zA-Z0-9]+"), " ")
                ?.trim()
                ?.takeIf { cand ->
                    cand.length in 3..24 &&
                        !cand.equals("games", ignoreCase = true) &&
                        !cand.equals("casino", ignoreCase = true) &&
                        !cand.equals("m", ignoreCase = true) &&
                        cand.lowercase(Locale.US) !in ignoredTitleTokens
                }
                ?.replaceFirstChar { it.uppercaseChar() }
            val textTitle = rawTexts.firstOrNull { raw ->
                val trimmed = raw.trim()
                val lower = trimmed.lowercase(Locale.US)
                trimmed.length in 3..24 &&
                    !lower.contains("http") &&
                    !lower.matches(Regex("^\\d+([.,:]\\d+)*x?$")) &&
                    lower !in ignoredTitleTokens
            }?.trim()
            val title = slug ?: textTitle ?: "Unknown"
            val slugId = if (title == "Unknown") {
                "unknown"
            } else {
                title.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "unknown" }
            }
            return GenericCrashGameAdapter(dynamicGameId = slugId, dynamicGameTitle = title)
        }
        return null
    }

    fun resolvePlatformDisplay(packageName: String, url: String?, pageHints: List<String>): String {
        val lowerUrl = url?.lowercase(Locale.US).orEmpty()
        val hints = pageHints.joinToString(" ").lowercase(Locale.US)
        return when {
            lowerUrl.contains("sportybet") || hints.contains("sportybet") -> "SportyBet"
            lowerUrl.contains("betpawa") || hints.contains("betpawa") -> "BetPawa"
            packageName.contains("chrome", ignoreCase = true) -> "Chrome Web"
            packageName.contains("browser", ignoreCase = true) -> "Mobile Browser"
            packageName.isNotBlank() -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercaseChar() }
            else -> "Unknown"
        }
    }
}
