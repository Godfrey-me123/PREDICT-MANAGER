package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.engine.AutomationState
import com.example.data.model.CalendarHourUtils
import com.example.data.model.CrashRecord
import com.example.data.model.GameState
import com.example.ui.theme.ActionStartEmerald
import com.example.ui.theme.ActionStopRed
import com.example.ui.theme.PrimaryCobalt
import java.util.Locale

fun parseSafeCrashColor(hex: String?, fallbackMultiplier: Double = 1.0): Color {
    if (!hex.isNullOrBlank()) {
        try {
            val cleaned = hex.trim().let { if (it.startsWith("#")) it else "#$it" }
            return Color(android.graphics.Color.parseColor(cleaned))
        } catch (_: Throwable) {
        }
    }
    return when {
        fallbackMultiplier >= 10.0 -> Color(0xFFEC4899)
        fallbackMultiplier > 2.0 -> Color(0xFF9333EA)
        fallbackMultiplier == 2.0 -> Color(0xFF8B5CF6)
        else -> Color(0xFF2563EB)
    }
}

fun formatDurationShort(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

@Composable
fun LiveCrashHeaderCard(
    gameState: GameState,
    automationState: AutomationState,
    nowMs: Long = System.currentTimeMillis(),
    modifier: Modifier = Modifier
) {
    val liveText = when {
        gameState.isStandbyOutsideSchedule -> "STANDBY"
        gameState.liveDetectionLost -> "LIVE DETECTION LOST"
        !gameState.isDetected -> "WAITING..."
        gameState.currentMultiplier > 0.0 -> "${String.format(Locale.US, "%.2f", gameState.currentMultiplier)}x"
        else -> "--.--x"
    }
    val liveColor = when {
        gameState.liveDetectionLost -> ActionStopRed
        gameState.status == "IN_FLIGHT" -> ActionStartEmerald
        gameState.status == "CRASHED" -> ActionStopRed
        else -> PrimaryCobalt
    }

    val durationText = if (gameState.monitoringStartedAtMs > 0L && gameState.isDetected) {
        formatDurationShort((nowMs - gameState.monitoringStartedAtMs).coerceAtLeast(0L))
    } else {
        "00:00"
    }

    val sessionCrashes = maxOf(gameState.sessionCrashesRecorded, gameState.activeSessionCrashCount)
    val roundIdValue = gameState.currentRoundId?.takeIf { it.isNotBlank() }
        ?: gameState.latestRoundId?.takeIf { it.isNotBlank() }
    val lastCrashText = gameState.latestCrashMultiplier
        ?.takeIf { it.isFinite() && it >= 1.0 }
        ?.let { "LAST CRASH: ${String.format(Locale.US, "%.2fx", it)}" }
        ?: "LAST CRASH: —"
    val roundIdText = roundIdValue?.let { "CURRENT GAME ROUND: $it" } ?: "CURRENT GAME ROUND: —"

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = gameState.displayedGameName.ifBlank { "Unknown" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${gameState.monitoringStatus.displayLabel} • ${gameState.pipelineStatus.displayLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = liveText,
                    color = liveColor,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (gameState.liveDetectionLost) 11.sp else 18.sp
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lastCrashText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = PrimaryCobalt
                )
                Text(
                    text = "Monitored: $durationText",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = roundIdText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = "SESSION CRASHES RECORDED: $sessionCrashes",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (sessionCrashes == 0 && gameState.latestCrashMultiplier == null) {
                Text(
                    text = "Waiting for first confirmed crash",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFB45309)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            val balanceStatus = gameState.balanceStatus
            val balanceAmount = gameState.detectedBalance
            val balanceCurrency = gameState.detectedCurrency ?: "KES"
            val balanceFreshnessSec = if (gameState.balanceLastUpdatedMs > 0L) {
                ((nowMs - gameState.balanceLastUpdatedMs) / 1000L).coerceAtLeast(0L)
            } else {
                0L
            }
            val balanceDisplayStr = when (balanceStatus) {
                com.example.data.model.BalanceDetectionStatus.VERIFIED, com.example.data.model.BalanceDetectionStatus.DETECTED -> {
                    val amt = balanceAmount ?: 0.0
                    "${com.example.data.engine.CurrencyManager.formatAmount(amt)} $balanceCurrency [${balanceStatus.name}]"
                }
                com.example.data.model.BalanceDetectionStatus.SCANNING -> "BALANCE: SCANNING..."
                com.example.data.model.BalanceDetectionStatus.STALE -> "BALANCE: STALE (${balanceFreshnessSec}s ago)"
                com.example.data.model.BalanceDetectionStatus.UNAVAILABLE -> "BALANCE: UNAVAILABLE${gameState.balanceFailureReason?.let { " ($it)" } ?: ""}"
            }
            val balanceColor = when (balanceStatus) {
                com.example.data.model.BalanceDetectionStatus.VERIFIED -> Color(0xFF047857)
                com.example.data.model.BalanceDetectionStatus.DETECTED -> PrimaryCobalt
                com.example.data.model.BalanceDetectionStatus.SCANNING -> Color(0xFFB45309)
                com.example.data.model.BalanceDetectionStatus.STALE, com.example.data.model.BalanceDetectionStatus.UNAVAILABLE -> ActionStopRed
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF1F5F9), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(balanceColor, CircleShape)
                    )
                    Text(
                        text = balanceDisplayStr,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = balanceColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("header_balance_display")
                    )
                }
                if (balanceStatus != com.example.data.model.BalanceDetectionStatus.VERIFIED && balanceStatus != com.example.data.model.BalanceDetectionStatus.DETECTED) {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            com.example.service.AutomationController.engine?.gameDetection?.retryBalanceDetection(nowMs)
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier
                            .height(26.dp)
                            .testTag("balance_retry_btn")
                    ) {
                        Text("RETRY", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = PrimaryCobalt)
                    }
                }
            }
        }
    }
}

@Composable
fun BoundedCrashHistoryGrid(
    crashRecords: List<CrashRecord>,
    gridRows: Int = 5,
    gridColumns: Int = 8,
    compactMode: Boolean = false,
    @Suppress("UNUSED_PARAMETER") onRequestManualCorrection: ((CrashRecord) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val safeRows = gridRows.coerceIn(4, 5)
    val safeCols = gridColumns.coerceIn(7, 8)
    val maxCells = safeRows * safeCols
    val sortedNewestFirst = remember(crashRecords, maxCells) {
        crashRecords.sortedWith(compareByDescending<CrashRecord> { it.crashTimestamp }.thenByDescending { it.id })
            .take(maxCells)
    }
    var inspectedRecord by remember { mutableStateOf<CrashRecord?>(null) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 140.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                .verticalScroll(rememberScrollState())
                .padding(6.dp)
                .testTag("bounded_crash_history_grid")
        ) {
            if (sortedNewestFirst.isEmpty()) {
                Text(
                    text = "INSUFFICIENT DATA — Waiting for confirmed round crashes...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
            } else {
                val rowsChunked = sortedNewestFirst.chunked(safeCols)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    rowsChunked.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            rowItems.forEach { rec ->
                                val chipColor = parseSafeCrashColor(rec.detectedColorHex, rec.multiplier)
                                val textColor = if (chipColor.luminance() > 0.58f) Color(0xFF0F172A) else Color.White
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(chipColor)
                                        .clickable {
                                            inspectedRecord = if (inspectedRecord?.id == rec.id && inspectedRecord?.crashTimestamp == rec.crashTimestamp) {
                                                null
                                            } else {
                                                rec
                                            }
                                        }
                                        .padding(vertical = if (compactMode) 4.dp else 5.dp, horizontal = 2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "%.2fx", rec.multiplier),
                                        color = textColor,
                                        fontSize = if (compactMode) 9.sp else 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            repeat((safeCols - rowItems.size).coerceAtLeast(0)) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        inspectedRecord?.let { detail ->
            val chipColor = parseSafeCrashColor(detail.detectedColorHex, detail.multiplier)
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(chipColor, CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "${detail.gameName}: ${String.format(Locale.US, "%.2fx", detail.multiplier)} (${detail.colorCategory.displayName} · ${detail.detectedColorHex})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val roundText = detail.roundIdentifier?.takeIf { it.isNotBlank() } ?: "Unknown"
                        Text(
                            text = "Round: $roundText · Session: ${detail.sessionId.ifBlank { "—" }} · ${CalendarHourUtils.formatDateTime(detail.crashTimestamp)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
