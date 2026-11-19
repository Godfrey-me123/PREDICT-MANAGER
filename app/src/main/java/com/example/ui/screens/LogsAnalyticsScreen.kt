package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.database.ClearHistoryScope
import com.example.data.engine.GameAdapterRegistry
import com.example.data.engine.HistoryCollectionMetadata
import com.example.data.model.CalendarHourUtils
import com.example.data.model.CrashColorCategory
import com.example.data.model.CrashCorrectionAudit
import com.example.data.model.CrashRecord
import com.example.data.model.StrategyConfig
import com.example.ui.components.BoundedCrashHistoryGrid
import com.example.ui.components.parseSafeCrashColor
import com.example.ui.theme.ActionStopRed
import com.example.ui.theme.PrimaryCobalt
import java.util.Locale

private enum class HistoryScopeFilter(val label: String) {
    CURRENT_SESSION("Current Session"),
    ARCHIVED_SESSIONS("Archived Sessions"),
    FULL_HISTORY("Full History")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogsAnalyticsScreen(
    crashRecords: List<CrashRecord> = emptyList(),
    @Suppress("UNUSED_PARAMETER")
    crashAudits: List<CrashCorrectionAudit> = emptyList(),
    historyMetadata: HistoryCollectionMetadata = HistoryCollectionMetadata(),
    strategyConfig: StrategyConfig? = null,
    selectedGameId: String = "ALL",
    allCrashRecords: List<CrashRecord> = emptyList(),
    onSelectGameId: (String) -> Unit = {},
    onUpdateGridDimensions: (Int, Int) -> Unit = { _, _ -> },
    @Suppress("UNUSED_PARAMETER")
    onApplyManualCorrection: (recordId: Long, newMultiplier: Double, newCategory: CrashColorCategory, reason: String) -> Unit = { _, _, _, _ -> },
    onClearCrashHistory: () -> Unit = {},
    onEndSessionAndClear: () -> Unit = {},
    onCollectHistory: () -> Unit = {},
    onCountClearScopeRecords: (suspend (ClearHistoryScope, String) -> Int)? = null,
    onClearHistoryByScope: ((ClearHistoryScope, String) -> Unit)? = null
) {
    val config = strategyConfig ?: StrategyConfig()
    var scopeFilter by remember { mutableStateOf(HistoryScopeFilter.CURRENT_SESSION) }
    var expandedRecordKey by remember { mutableStateOf<String?>(null) }
    var showClearScopeDialog by remember { mutableStateOf(false) }
    var pendingClearScope by remember { mutableStateOf(ClearHistoryScope.CURRENT_SESSION) }
    var previewDeleteCount by remember { mutableStateOf(0) }

    val currentSessionKeys = remember(crashRecords) {
        crashRecords.map { it.dedupKey }.toSet()
    }
    val archivedRecords = remember(allCrashRecords, currentSessionKeys) {
        allCrashRecords.filter { it.dedupKey !in currentSessionKeys }
    }

    val sourceRecords = when (scopeFilter) {
        HistoryScopeFilter.CURRENT_SESSION -> crashRecords
        HistoryScopeFilter.ARCHIVED_SESSIONS -> archivedRecords
        HistoryScopeFilter.FULL_HISTORY -> if (allCrashRecords.isNotEmpty()) allCrashRecords else crashRecords
    }

    val dynamicGames = remember(allCrashRecords, crashRecords) {
        val map = linkedMapOf<String, String>()
        map["unknown"] = "Unknown"
        GameAdapterRegistry.allAdapters().forEach { adapter ->
            map[adapter.gameId] = adapter.displayedGameName
        }
        (allCrashRecords + crashRecords).forEach { rec ->
            if (rec.gameId.isNotBlank() && !map.containsKey(rec.gameId)) {
                map[rec.gameId] = rec.gameName.ifBlank { "Unknown" }
            }
        }
        map.entries.toList()
    }

    val filteredRecords = remember(sourceRecords, selectedGameId) {
        val base = if (selectedGameId == "ALL") sourceRecords else sourceRecords.filter { it.gameId == selectedGameId }
        base.sortedWith(compareByDescending<CrashRecord> { it.crashTimestamp }.thenByDescending { it.id })
    }

    LaunchedEffect(showClearScopeDialog, pendingClearScope, selectedGameId, crashRecords.size, allCrashRecords.size) {
        if (showClearScopeDialog) {
            previewDeleteCount = if (onCountClearScopeRecords != null) {
                runCatching { onCountClearScopeRecords(pendingClearScope, selectedGameId) }.getOrDefault(0)
            } else {
                when (pendingClearScope) {
                    ClearHistoryScope.CURRENT_SESSION -> crashRecords.size
                    ClearHistoryScope.SELECTED_GAME_ARCHIVED -> {
                        val target = if (selectedGameId == "ALL") "unknown" else selectedGameId
                        archivedRecords.count { it.gameId == target }
                    }
                    ClearHistoryScope.ALL_HISTORY -> if (allCrashRecords.isNotEmpty()) allCrashRecords.size else crashRecords.size
                }
            }
        }
    }

    val isOverlayHost = LocalActivityResultRegistryOwner.current == null
    val hasBackOwner = LocalOnBackPressedDispatcherOwner.current != null

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CRASH HISTORY",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryCobalt
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onCollectHistory,
                        modifier = Modifier.testTag("history_sync_btn")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sync", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { showClearScopeDialog = true },
                        modifier = Modifier.testTag("history_clear_scope_btn")
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear History", tint = ActionStopRed, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear", fontSize = 11.sp, color = ActionStopRed)
                    }
                }
            }
        }

        // 3-Scope Session & Per-Game Isolation Filters
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = scopeFilter == HistoryScopeFilter.CURRENT_SESSION,
                        onClick = { scopeFilter = HistoryScopeFilter.CURRENT_SESSION },
                        label = { Text("Current Session (${crashRecords.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        modifier = Modifier.testTag("history_scope_current_chip")
                    )
                    FilterChip(
                        selected = scopeFilter == HistoryScopeFilter.ARCHIVED_SESSIONS,
                        onClick = { scopeFilter = HistoryScopeFilter.ARCHIVED_SESSIONS },
                        label = { Text("Archived Sessions (${archivedRecords.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        modifier = Modifier.testTag("history_scope_archived_chip")
                    )
                    FilterChip(
                        selected = scopeFilter == HistoryScopeFilter.FULL_HISTORY,
                        onClick = { scopeFilter = HistoryScopeFilter.FULL_HISTORY },
                        label = {
                            val total = if (allCrashRecords.isNotEmpty()) allCrashRecords.size else crashRecords.size
                            Text("Full History ($total)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        },
                        modifier = Modifier.testTag("history_scope_full_chip")
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = selectedGameId == "ALL",
                        onClick = { onSelectGameId("ALL") },
                        label = { Text("All Games", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    )
                    dynamicGames.forEach { (gameId, gameName) ->
                        FilterChip(
                            selected = selectedGameId == gameId,
                            onClick = { onSelectGameId(gameId) },
                            label = { Text(gameName, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.testTag("history_game_chip_$gameId")
                        )
                    }
                }
            }
        }

        // Bounded Multiplier Grid (strictly observational, zero edit/fix controls)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "BOUNDED GRID (${config.gridRows}×${config.gridColumns})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = PrimaryCobalt
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(4 to 7, 4 to 8, 5 to 7, 5 to 8).forEach { (r, c) ->
                                FilterChip(
                                    selected = config.gridRows == r && config.gridColumns == c,
                                    onClick = { onUpdateGridDimensions(r, c) },
                                    label = { Text("${r}×$c", fontSize = 10.sp) }
                                )
                            }
                        }
                    }

                    BoundedCrashHistoryGrid(
                        crashRecords = filteredRecords,
                        gridRows = config.gridRows,
                        gridColumns = config.gridColumns
                    )

                    Text(
                        text = "Tap any cell or row below to inspect timestamp, Round ID, observed HEX color, and session metadata.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Recent Confirmed Crash Log (tap row to expand metadata downwards)
        item {
            Text(
                text = "CONFIRMED CRASHES (${filteredRecords.size}) · ${historyMetadata.status.name.replace('_', ' ')}",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (filteredRecords.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Text(
                        text = "INSUFFICIENT DATA — No confirmed crash multipliers recorded for the current filter yet.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(filteredRecords.take(100), key = { "${it.id}_${it.dedupKey}" }) { rec ->
                val chipColor = parseSafeCrashColor(rec.detectedColorHex, rec.multiplier)
                val rowKey = "${rec.id}_${rec.dedupKey}"
                val isExpanded = expandedRecordKey == rowKey

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isExpanded) PrimaryCobalt else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expandedRecordKey = if (isExpanded) null else rowKey
                        }
                        .testTag("crash_history_row_${rec.id}")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(chipColor, CircleShape)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "${rec.gameName.ifBlank { "Unknown" }} • ${String.format(Locale.US, "%.2fx", rec.multiplier)} (${rec.colorCategory.displayName})",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${CalendarHourUtils.formatDateTime(rec.crashTimestamp)} · ${rec.detectedColorHex}${if (rec.isPixelColorVerified) " (Pixel)" else ""}",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (isExpanded) "Collapse crash details" else "Expand crash details",
                                tint = PrimaryCobalt
                            )
                        }

                        AnimatedVisibility(
                            visible = isExpanded,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("crash_history_detail_${rec.id}")
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    val roundText = rec.roundIdentifier?.takeIf { it.isNotBlank() } ?: "Unknown"
                                    Text("Round ID: $roundText", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Game ID: ${rec.gameId.ifBlank { "unknown" }} (${rec.gameName.ifBlank { "Unknown" }})", fontSize = 11.sp)
                                    Text("Session ID: ${rec.sessionId.ifBlank { "Archived" }}", fontSize = 11.sp)
                                    Text(
                                        text = "Color HEX: ${rec.detectedColorHex} · Verified: ${if (rec.isPixelColorVerified) "Yes (Pixel)" else "No (Range-normalized)"}",
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = "Source: ${rec.collectionSource.name} · App: ${rec.sourcePackage.ifBlank { "Unknown" }}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(88.dp)) }
    }

    if (showClearScopeDialog) {
        if (hasBackOwner) {
            BackHandler { showClearScopeDialog = false }
        }
        val clearScopeBody: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Choose which records to delete cleanly from local Room storage:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                listOf(
                    ClearHistoryScope.CURRENT_SESSION to "Current Session Only (resets session count to 0)",
                    ClearHistoryScope.SELECTED_GAME_ARCHIVED to "Archived History for Selected Game (${if (selectedGameId == "ALL") "unknown" else selectedGameId})",
                    ClearHistoryScope.ALL_HISTORY to "All History (all sessions, stats, & hourly summaries)"
                ).forEach { (scopeOpt, desc) ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (pendingClearScope == scopeOpt) PrimaryCobalt.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(
                            1.dp,
                            if (pendingClearScope == scopeOpt) PrimaryCobalt else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { pendingClearScope = scopeOpt }
                            .testTag("clear_scope_option_${scopeOpt.name}")
                    ) {
                        Text(
                            text = desc,
                            fontSize = 12.sp,
                            fontWeight = if (pendingClearScope == scopeOpt) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
                Surface(
                    color = Color(0xFFFEE2E2),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Records to delete: $previewDeleteCount crash record(s)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = ActionStopRed,
                        modifier = Modifier
                            .padding(10.dp)
                            .testTag("clear_scope_preview_count")
                    )
                }
            }
        }
        val onConfirmClearScope = {
            showClearScopeDialog = false
            if (onClearHistoryByScope != null) {
                onClearHistoryByScope(pendingClearScope, selectedGameId)
            } else {
                when (pendingClearScope) {
                    ClearHistoryScope.CURRENT_SESSION -> onEndSessionAndClear()
                    ClearHistoryScope.SELECTED_GAME_ARCHIVED,
                    ClearHistoryScope.ALL_HISTORY -> onClearCrashHistory()
                }
            }
        }

        if (isOverlayHost) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { showClearScopeDialog = false }
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = false) {},
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Clear Local History", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        clearScopeBody()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { showClearScopeDialog = false },
                                modifier = Modifier.weight(1f)
                            ) { Text("CANCEL") }
                            Button(
                                onClick = onConfirmClearScope,
                                colors = ButtonDefaults.buttonColors(containerColor = ActionStopRed, contentColor = Color.White),
                                modifier = Modifier.weight(1f).testTag("confirm_clear_scope_btn")
                            ) { Text("DELETE ($previewDeleteCount)", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        } else {
            AlertDialog(
                onDismissRequest = { showClearScopeDialog = false },
                title = { Text("Clear Local History", fontWeight = FontWeight.Bold) },
                text = clearScopeBody,
                confirmButton = {
                    Button(
                        onClick = onConfirmClearScope,
                        colors = ButtonDefaults.buttonColors(containerColor = ActionStopRed, contentColor = Color.White),
                        modifier = Modifier.testTag("confirm_clear_scope_btn")
                    ) {
                        Text("DELETE ($previewDeleteCount)", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearScopeDialog = false }) {
                        Text("CANCEL")
                    }
                }
            )
        }
    }
    }
}
