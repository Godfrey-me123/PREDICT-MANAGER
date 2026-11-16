package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.engine.AutomationState
import com.example.data.engine.GameAdapterRegistry
import com.example.data.engine.HistoryCollectionMetadata
import com.example.data.engine.MultiplierHistoryAnalyzer
import com.example.data.model.CrashRecord
import com.example.data.model.GameState
import com.example.data.model.StrategyConfig
import com.example.ui.components.BoundedCrashHistoryGrid
import com.example.ui.components.LiveCrashHeaderCard
import com.example.ui.components.parseSafeCrashColor
import com.example.ui.theme.ActionStartEmerald
import com.example.ui.theme.ActionStopRed
import com.example.ui.theme.PrimaryCobalt
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveMonitorScreen(
    gameState: GameState,
    automationState: AutomationState,
    strategyConfig: StrategyConfig?,
    crashRecords: List<CrashRecord>,
    historyMetadata: HistoryCollectionMetadata,
    selectedGameId: String,
    isPixelCaptureActive: Boolean,
    onSelectGameId: (String) -> Unit,
    onStartMonitoring: () -> Unit,
    onPauseMonitoring: () -> Unit,
    onCollectHistory: () -> Unit,
    onRequestPixelCapture: () -> Unit,
    onStopPixelCapture: () -> Unit,
    onEndSessionAndClear: () -> Unit = {}
) {
    val config = strategyConfig ?: StrategyConfig()
    val scrollState = rememberScrollState()
    val isRunning = automationState == AutomationState.RUNNING
    val adapters = remember { GameAdapterRegistry.allAdapters() }
    val activeGameRecords = remember(crashRecords, gameState.isDetected, gameState.gameId, selectedGameId) {
        val target = if (gameState.isDetected && gameState.gameId.isNotBlank()) gameState.gameId else selectedGameId
        if (target == "ALL") crashRecords else crashRecords.filter { it.gameId == target }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "LIVE GAME MONITOR",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = PrimaryCobalt
        )

        // Dynamic Game Selector Pills (including Unknown)
        val dynamicGames = remember(crashRecords, adapters) {
            val map = linkedMapOf<String, String>()
            map["unknown"] = "Unknown"
            adapters.forEach { map[it.gameId] = it.displayedGameName }
            crashRecords.forEach { rec ->
                if (rec.gameId.isNotBlank() && !map.containsKey(rec.gameId)) {
                    map[rec.gameId] = rec.gameName.ifBlank { "Unknown" }
                }
            }
            map.entries.toList()
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = selectedGameId == "ALL",
                onClick = { onSelectGameId("ALL") },
                label = { Text("All Games", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            )
            dynamicGames.forEach { (gId, gName) ->
                val activeHere = gameState.isDetected && gameState.gameId == gId
                FilterChip(
                    selected = selectedGameId == gId || activeHere,
                    onClick = { onSelectGameId(gId) },
                    label = {
                        Text(
                            text = if (activeHere) "$gName (LIVE)" else gName,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                )
            }
        }

        // Real-time Detection State Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("live_monitor_telemetry_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveCrashHeaderCard(gameState = gameState, automationState = automationState)

                if (gameState.liveDetectionLost) {
                    Surface(
                        color = Color(0xFFFEE2E2),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = "Lost Signal", tint = ActionStopRed)
                            Text(
                                text = "LIVE DETECTION LOST — Stale multiplier suppressed. Return the active crash game onto the viewport.",
                                color = ActionStopRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Truthful Pipeline State & Current Session Telemetry (zero fabricated confidence %)
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("live_pipeline_status_badge")
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = gameState.pipelineStatus.displayLabel,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = PrimaryCobalt
                            )
                            Text(
                                text = if (gameState.lastDetectionTimestampMs > 0L) {
                                    "Updated: ${MultiplierHistoryAnalyzer.formatTimestamp(gameState.lastDetectionTimestampMs)}"
                                } else {
                                    "Awaiting signal"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = gameState.pipelineDiagnosticReason,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Session Crashes: ${gameState.activeSessionCrashCount} · Latest Crash: ${gameState.latestCrashMultiplier?.let { String.format(Locale.US, "%.2fx", it) } ?: "—"} · Round: ${gameState.latestRoundId ?: "—"}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Supported platforms tags
                if (gameState.supportedPlatforms.isNotEmpty()) {
                    Text(
                        text = "Supported Platforms: ${gameState.supportedPlatforms.joinToString(" • ")}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Action controls
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { if (isRunning) onPauseMonitoring() else onStartMonitoring() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) ActionStopRed else ActionStartEmerald,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isRunning) "PAUSE" else "START MONITOR", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onCollectHistory,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync Ribbon")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("SYNC RIBBON", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                OutlinedButton(
                    onClick = onEndSessionAndClear,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("end_session_clear_btn")
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "End Session and Clear Session Data", tint = ActionStopRed)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "END SESSION & CLEAR SESSION DATA",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = ActionStopRed
                    )
                }
            }
        }

        // Consent-Gated Pixel Color Sampling Card (MediaProjection)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ColorLens, contentDescription = "Pixel Color Capture", tint = PrimaryCobalt)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Actual Game Color Capture", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                text = if (isPixelCaptureActive) {
                                    "Pixel HEX verification active for canvas & ribbon badges"
                                } else {
                                    "Optional MediaProjection consent to read exact UI badge colors"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                gameState.latestCrashColorHex?.let { hex ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(parseSafeCrashColor(hex, gameState.latestCrashMultiplier ?: 1.0), CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Latest Crash Color: ${gameState.latestCrashColorCategory.displayName} ($hex) • ${if (gameState.isLatestCrashColorVerified) "Pixel-Verified" else "Normalized"}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Button(
                    onClick = { if (isPixelCaptureActive) onStopPixelCapture() else onRequestPixelCapture() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPixelCaptureActive) ActionStopRed else PrimaryCobalt,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("toggle_pixel_capture_btn")
                ) {
                    Icon(Icons.Default.Sensors, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isPixelCaptureActive) "STOP PIXEL COLOR CAPTURE" else "ENABLE PIXEL COLOR CAPTURE",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Bounded Live Crash Grid
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "LIVE CRASH GRID (${config.gridRows}×${config.gridColumns}) — ${activeGameRecords.size} RECORDED",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = PrimaryCobalt
                )
                BoundedCrashHistoryGrid(
                    crashRecords = activeGameRecords,
                    gridRows = config.gridRows,
                    gridColumns = config.gridColumns
                )
                Text(
                    text = "Ribbon Status: ${historyMetadata.status.name.replace('_', ' ')} — ${historyMetadata.statusReason}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
    }
}
