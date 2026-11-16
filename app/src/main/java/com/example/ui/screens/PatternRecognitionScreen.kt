package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.engine.GameAdapterRegistry
import com.example.data.engine.SmartAgentExplanation
import com.example.data.engine.StatisticalReport
import com.example.data.model.CalendarHourUtils
import com.example.ui.theme.ActionStartEmerald
import com.example.ui.theme.PrimaryCobalt
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PatternRecognitionScreen(
    report: StatisticalReport,
    agentExplanation: SmartAgentExplanation,
    selectedGameId: String,
    onSelectGameId: (String) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "PATTERN RECOGNITION & SMART AGENT",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = PrimaryCobalt
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedGameId == "ALL",
                onClick = { onSelectGameId("ALL") },
                label = { Text("All Games", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            )
            FilterChip(
                selected = selectedGameId == "unknown",
                onClick = { onSelectGameId("unknown") },
                label = { Text("Unknown", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            )
            GameAdapterRegistry.allAdapters().forEach { adapter ->
                FilterChip(
                    selected = selectedGameId == adapter.gameId,
                    onClick = { onSelectGameId(adapter.gameId) },
                    label = { Text(adapter.displayedGameName, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
            }
        }

        // Prompt 9: Smart Analysis Agent Factual Breakdown
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("smart_agent_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Psychology, contentDescription = "Smart Agent", tint = PrimaryCobalt)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SMART ANALYSIS AGENT (OBSERVATIONAL)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = PrimaryCobalt
                    )
                }
                Text(
                    text = agentExplanation.liveStatusLine,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                agentExplanation.strategyBreakdowns.forEach { item ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(item.strategyName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(
                                    text = item.hourlyProgressText,
                                    color = PrimaryCobalt,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 11.sp
                                )
                            }
                            Text(item.ruleExplanation, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = "${item.streakProgressText} · ${item.remainingToTriggerText}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ActionStartEmerald
                            )
                            Text(
                                text = item.timestampsSummary,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Text(
                    text = agentExplanation.windowComparisonText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${agentExplanation.sampleConfidenceLabel}. ${agentExplanation.integrityDisclaimer}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Consecutive Lows & Alternating Runs
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Analytics, contentDescription = "Streaks", tint = PrimaryCobalt)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CONSECUTIVE LOWS & ALTERNATING SEQUENCES",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = PrimaryCobalt
                    )
                }
                if (!report.sampleSufficient && report.totalCompletedRounds == 0) {
                    Text(
                        text = "INSUFFICIENT DATA — Waiting for completed crash rounds.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Current Consecutive Low Chain: ${report.currentConsecutiveLows} rounds • Longest Chain: ${report.longestConsecutiveLows} rounds",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "Distribution (${report.totalCompletedRounds} completed rounds): Low ${report.lowCount} • ==Boundary ${report.boundaryCount} • Med ${report.mediumCount} • Big ${report.bigCount}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val avgLowsBeforeBig = if (report.lowsBeforeBigCounts.isEmpty()) 0.0 else report.lowsBeforeBigCounts.average()
                    Text(
                        text = "Lows before Big (>=10x): Avg ${String.format(Locale.US, "%.1f", avgLowsBeforeBig)} rounds (${report.lowsBeforeBigCounts.size} big hits observed)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Alternating Low/Med Sequences: Current ${report.currentAlternatingLowMedLength} · Longest ${report.maxAlternatingLowMedLength} rounds",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Back-to-Back Big Clusters: ${report.bigMultiplierClusters} cluster(s)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Big Multiplier Minute Intervals
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "BIG MULTIPLIER INTERVALS & COLOR MOTIFS",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = PrimaryCobalt
                )
                if (report.bigMultiplierGaps.isEmpty()) {
                    Text(
                        text = "Need at least 2 big multipliers to compute time intervals.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    report.bigMultiplierGaps.takeLast(8).reversed().forEach { gap ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${String.format(Locale.US, "%.2fx", gap.firstMultiplier)} -> ${String.format(Locale.US, "%.2fx", gap.secondMultiplier)}",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "${String.format(Locale.US, "%.1f min", gap.gapMinutes)}${if (gap.isWithinQualifyingRange) " (In Range)" else ""} @ ${CalendarHourUtils.formatExactTime(gap.secondTimestampMs)}",
                                fontSize = 11.sp,
                                color = if (gap.isWithinQualifyingRange) ActionStartEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Observed 3-Color Motifs:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                if (report.topRepeatedColorSequences.isEmpty()) {
                    Text(
                        text = "No repeated 3-color motifs recorded yet.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    report.topRepeatedColorSequences.take(5).forEach { (seqLabel, count) ->
                        Text(
                            text = "$seqLabel: Observed $count times",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
    }
}
