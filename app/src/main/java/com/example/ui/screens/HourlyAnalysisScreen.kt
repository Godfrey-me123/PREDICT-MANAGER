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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.engine.GameAdapterRegistry
import com.example.data.engine.HourlyAnalysisEngine
import com.example.data.engine.HourlyWindowMetrics
import com.example.data.engine.MultiWindowAnalysisBundle
import com.example.data.model.CalendarHourUtils
import com.example.data.model.HourlySessionSummary
import com.example.ui.theme.ActionStartEmerald
import com.example.ui.theme.PrimaryCobalt
import java.util.Locale

private enum class HourlyWindowTab(val title: String) {
    CURRENT_HOUR("Current Hour"),
    PREVIOUS_HOUR("Previous Hour"),
    ROLLING_30M("30m Rolling"),
    ROLLING_60M("60m Rolling")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HourlyAnalysisScreen(
    bundle: MultiWindowAnalysisBundle,
    archivedSummaries: List<HourlySessionSummary>,
    selectedGameId: String,
    onSelectGameId: (String) -> Unit,
    selectedTimeRangePreset: HourlyAnalysisEngine.TimeRangePreset = HourlyAnalysisEngine.TimeRangePreset.ONE_HOUR,
    timeRangeMetrics: HourlyWindowMetrics? = null,
    onSelectTimeRangePreset: (HourlyAnalysisEngine.TimeRangePreset, Long, Long) -> Unit = { _, _, _ -> }
) {
    var activeTab by remember { mutableStateOf(HourlyWindowTab.CURRENT_HOUR) }
    var customHoursText by remember { mutableStateOf("6") }
    val scrollState = rememberScrollState()

    val selectedStats: HourlyWindowMetrics = when (activeTab) {
        HourlyWindowTab.CURRENT_HOUR -> bundle.currentHour
        HourlyWindowTab.PREVIOUS_HOUR -> bundle.previousHour
        HourlyWindowTab.ROLLING_30M -> bundle.rolling30m
        HourlyWindowTab.ROLLING_60M -> bundle.rolling60m
    }

    val filteredArchived = remember(archivedSummaries, selectedGameId) {
        if (selectedGameId == "ALL") archivedSummaries else archivedSummaries.filter { it.gameId == selectedGameId }
    }

    val dynamicGameChips = remember(archivedSummaries) {
        val map = linkedMapOf<String, String>()
        map["unknown"] = "Unknown"
        GameAdapterRegistry.allAdapters().forEach { map[it.gameId] = it.displayedGameName }
        archivedSummaries.forEach { s ->
            if (s.gameId.isNotBlank() && s.gameId != "ALL" && !map.containsKey(s.gameId)) {
                map[s.gameId] = s.gameName.ifBlank { "Unknown" }
            }
        }
        map.entries.toList()
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
            text = "HOURLY & MULTI-RANGE ANALYSIS",
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
            dynamicGameChips.forEach { (gId, gName) ->
                FilterChip(
                    selected = selectedGameId == gId,
                    onClick = { onSelectGameId(gId) },
                    label = { Text(gName, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
            }
        }

        // Window Mode Switcher
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HourlyWindowTab.entries.forEach { tab ->
                FilterChip(
                    selected = activeTab == tab,
                    onClick = { activeTab = tab },
                    label = { Text(tab.title, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    modifier = Modifier.testTag("hourly_tab_${tab.name}")
                )
            }
        }

        // Selected Window Detail Card
        MetricsDetailCard(
            stats = selectedStats,
            emptyMessage = "INSUFFICIENT DATA — No confirmed rounds recorded in ${selectedStats.windowLabel} yet.",
            testTag = "hourly_window_stats_card"
        )

        // Configurable 1h, 3h, 6h, 12h, 24h & Custom History Analyzer Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("hourly_range_analyzer_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "HISTORICAL TIME RANGE SELECTOR (1h – 24h & CUSTOM)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = PrimaryCobalt
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HourlyAnalysisEngine.TimeRangePreset.entries.forEach { preset ->
                        FilterChip(
                            selected = selectedTimeRangePreset == preset,
                            onClick = {
                                val now = System.currentTimeMillis()
                                val hrs = customHoursText.trim().toLongOrNull()?.coerceIn(1L, 720L) ?: 6L
                                val start = if (preset == HourlyAnalysisEngine.TimeRangePreset.CUSTOM) {
                                    now - hrs * 3_600_000L
                                } else {
                                    now - (preset.durationMs ?: 3_600_000L)
                                }
                                onSelectTimeRangePreset(preset, start, now)
                            },
                            label = { Text(preset.label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.testTag("time_range_chip_${preset.name}")
                        )
                    }
                }
                if (selectedTimeRangePreset == HourlyAnalysisEngine.TimeRangePreset.CUSTOM) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customHoursText,
                            onValueChange = { customHoursText = it },
                            label = { Text("Custom Lookback (hours)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("custom_range_hours_input")
                        )
                        Button(
                            onClick = {
                                val now = System.currentTimeMillis()
                                val hrs = customHoursText.trim().toLongOrNull()?.coerceIn(1L, 720L) ?: 6L
                                onSelectTimeRangePreset(
                                    HourlyAnalysisEngine.TimeRangePreset.CUSTOM,
                                    now - hrs * 3_600_000L,
                                    now
                                )
                            },
                            modifier = Modifier.testTag("apply_custom_range_btn")
                        ) {
                            Text("Apply", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                timeRangeMetrics?.let { rangeStats ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    Text(
                        text = "${rangeStats.windowLabel} • ${rangeStats.totalRounds} confirmed round(s)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = PrimaryCobalt
                    )
                    Text(
                        text = "Low: ${rangeStats.lowCount} (${HourlyAnalysisEngine.formatPercent(rangeStats.lowPct)}) · Big: ${rangeStats.bigCount} (${HourlyAnalysisEngine.formatPercent(rangeStats.bigPct)}) · Avg: ${String.format(Locale.US, "%.2fx", rangeStats.averageMultiplier)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Longest Low Streak: ${rangeStats.longestConsecutiveLowStreak} · Qualifying Big Intervals: ${rangeStats.qualifyingBigIntervalsCount}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Archived Hourly Sessions (Finalized on every clock hour rollover)
        Text(
            text = "FINALIZED HOURLY ARCHIVE (${filteredArchived.size})",
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (filteredArchived.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Text(
                    text = "Closed hour summaries appear automatically at the start of each clock hour (e.g. 15:00 archives 14:00–14:59).",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp)
                )
            }
        } else {
            filteredArchived.take(12).forEach { summary ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "${summary.gameName.ifBlank { "Unknown" }} • ${CalendarHourUtils.formatHourWindow(summary.hourStartTimestamp)}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "${summary.totalRounds} rounds | Low ${summary.lowCount} | Big ${summary.bigCount} | Max Low Streak ${summary.longestConsecutiveLowStreak} | Avg ${String.format(Locale.US, "%.2fx", summary.averageMultiplier)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
private fun MetricsDetailCard(
    stats: HourlyWindowMetrics,
    emptyMessage: String,
    testTag: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stats.windowLabel,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                    color = PrimaryCobalt
                )
                Surface(
                    color = if (stats.isInsufficientData) Color(0xFFFEF3C7) else ActionStartEmerald.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (stats.isInsufficientData) "INSUFFICIENT DATA" else "${stats.totalRounds} ROUNDS",
                        color = if (stats.isInsufficientData) Color(0xFF92400E) else ActionStartEmerald,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            if (stats.totalRounds == 0) {
                Text(
                    text = emptyMessage,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (stats.isInsufficientData) {
                    Text(
                        text = "INSUFFICIENT DATA — Sample size below 5 completed rounds (${stats.totalRounds}); treat ratios cautiously.",
                        fontSize = 11.sp,
                        color = Color(0xFFB45309),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    WindowMetricPill(
                        label = "Low (<2x)",
                        value = "${stats.lowCount} (${String.format(Locale.US, "%.0f%%", stats.lowPct)})",
                        modifier = Modifier.weight(1f)
                    )
                    WindowMetricPill(
                        label = "Boundary (==2x)",
                        value = "${stats.boundaryCount} (${String.format(Locale.US, "%.0f%%", stats.boundaryPct)})",
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    WindowMetricPill(
                        label = "Medium (2–10x)",
                        value = "${stats.mediumCount} (${String.format(Locale.US, "%.0f%%", stats.mediumPct)})",
                        modifier = Modifier.weight(1f)
                    )
                    WindowMetricPill(
                        label = "Big (>=10x)",
                        value = "${stats.bigCount} (${String.format(Locale.US, "%.0f%%", stats.bigPct)})",
                        modifier = Modifier.weight(1f)
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                Text(
                    text = "Average Multiplier: ${String.format(Locale.US, "%.2fx", stats.averageMultiplier)} • Median: ${String.format(Locale.US, "%.2fx", stats.medianMultiplier)}",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Text(
                    text = "Longest Consecutive Low Streak: ${stats.longestConsecutiveLowStreak} rounds • Qualifying Big Intervals: ${stats.qualifyingBigIntervalsCount}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (stats.colorDistribution.isNotEmpty()) {
                    val colorSummary = stats.colorDistribution.entries.joinToString(" • ") {
                        "${it.key.displayName}: ${it.value}"
                    }
                    Text(
                        text = "Color Distribution: $colorSummary",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun WindowMetricPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
