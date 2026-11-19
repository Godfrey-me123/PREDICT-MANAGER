package com.example.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.engine.GameAdapterRegistry
import com.example.data.model.CalendarHourUtils
import com.example.data.model.ColorMatchDirection
import com.example.data.model.CrashColorCategory
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.MultiplierRuleSubtype
import com.example.data.model.NotificationPriorityLevel
import com.example.data.model.PatternComparator
import com.example.data.model.PatternCondition
import com.example.data.model.PatternLogicOperator
import com.example.data.model.PatternOccurrenceRecord
import com.example.data.model.RepeatNotificationPolicy
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyGroup
import com.example.data.model.StrategyMode
import com.example.data.model.StrategyRangeValidator
import com.example.ui.components.parseSafeCrashColor
import com.example.ui.theme.ActionCashOutAmber
import com.example.ui.theme.ActionStartEmerald
import com.example.ui.theme.ActionStopRed
import com.example.ui.theme.PrimaryCobalt
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StrategyScreen(
    currentConfig: StrategyConfig?,
    customStrategies: List<CustomPatternStrategy> = emptyList(),
    patternOccurrences: List<PatternOccurrenceRecord> = emptyList(),
    onSaveCustomStrategy: (CustomPatternStrategy) -> Unit = {},
    onToggleCustomStrategyEnabled: (CustomPatternStrategy, Boolean) -> Unit = { _, _ -> },
    onToggleCustomStrategyNotifications: (CustomPatternStrategy, Boolean) -> Unit = { _, _ -> },
    onDeleteCustomStrategy: (Long) -> Unit = {},
    onReorderStrategies: (CustomPatternStrategy, CustomPatternStrategy) -> Unit = { _, _ -> },
    onSaveConfig: (StrategyConfig) -> Unit
) {
    val context = LocalContext.current
    val config = currentConfig ?: StrategyConfig()

    var lowThreshText by remember(config.lowThreshold) {
        mutableStateOf(String.format(Locale.US, "%.2f", config.lowThreshold))
    }
    var bigThreshText by remember(config.bigThreshold) {
        mutableStateOf(String.format(Locale.US, "%.2f", config.bigThreshold))
    }
    var defaultConsecutiveText by remember(config.strategy1RequiredLowRounds) {
        mutableStateOf(config.strategy1RequiredLowRounds.toString())
    }
    var monitoringEnabled by remember(config.monitoringEnabled) {
        mutableStateOf(config.monitoringEnabled)
    }
    var maxAttemptsText by remember(config.maxStrategyBetAttempts) {
        mutableStateOf(config.maxStrategyBetAttempts.toString())
    }
    var martingaleEnabled by remember(config.isMartingaleEnabled) {
        mutableStateOf(config.isMartingaleEnabled)
    }

    var selectedGroupFilter by remember { mutableStateOf<StrategyGroup?>(null) }
    var editingCustomStrategy by remember { mutableStateOf<CustomPatternStrategy?>(null) }
    var preselectedGroup by remember { mutableStateOf(StrategyGroup.MULTIPLIER_INTERVAL) }
    var preselectedMode by remember { mutableStateOf(StrategyMode.STRATEGY_A_CONSECUTIVE_LOWS) }
    var preselectedSubtype by remember { mutableStateOf(MultiplierRuleSubtype.STANDARD_THRESHOLD) }
    var showCustomBuilder by remember { mutableStateOf(false) }

    val orderedStrategies = remember(customStrategies, selectedGroupFilter) {
        val sorted = customStrategies.sortedWith(compareBy<CustomPatternStrategy> { it.sortOrder }.thenBy { it.id })
        if (selectedGroupFilter == null) sorted else sorted.filter { it.effectiveGroup() == selectedGroupFilter }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "STRATEGY MANAGER",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = PrimaryCobalt
        )
        Text(
            text = "3 Strategy Groups: (A) Multiplier Interval (LOW / ABOVE->BELOW / BIG), (B) Time Interval (2–55m wait & 1h/3h/6h/Custom frequency), and (C) Color Sequence (Horizontal & Vertical).",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Strategy Group Organizer Tabs
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = selectedGroupFilter == null,
                onClick = { selectedGroupFilter = null },
                label = { Text("All Groups (${customStrategies.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                modifier = Modifier.testTag("strategy_group_tab_ALL")
            )
            StrategyGroup.entries.forEach { group ->
                val count = customStrategies.count { it.effectiveGroup() == group }
                FilterChip(
                    selected = selectedGroupFilter == group,
                    onClick = { selectedGroupFilter = group },
                    label = { Text("${group.displayTitle} ($count)", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    modifier = Modifier.testTag("strategy_group_tab_${group.name}")
                )
            }
        }

        // Active & Saved Strategies List + Reordering + Per-Strategy Occurrence History
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("custom_pattern_strategies_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "USER STRATEGIES & INDEPENDENT HISTORIES (${orderedStrategies.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = PrimaryCobalt
                )

                if (orderedStrategies.isEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_strategies_empty_state")
                    ) {
                        Text(
                            text = "No strategies defined in this group yet. Create a Multiplier Interval, Time Interval, or Color Sequence strategy below.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        orderedStrategies.forEachIndexed { idx, strategy ->
                            val stratOccurrences = remember(patternOccurrences, strategy.id) {
                                patternOccurrences.filter { it.strategyId == strategy.id }
                            }
                            CustomStrategyItemCard(
                                strategy = strategy,
                                occurrences = stratOccurrences,
                                canMoveUp = idx > 0,
                                canMoveDown = idx < orderedStrategies.lastIndex,
                                onMoveUp = {
                                    if (idx > 0) onReorderStrategies(strategy, orderedStrategies[idx - 1])
                                },
                                onMoveDown = {
                                    if (idx < orderedStrategies.lastIndex) onReorderStrategies(strategy, orderedStrategies[idx + 1])
                                },
                                onToggleEnabled = { onToggleCustomStrategyEnabled(strategy, it) },
                                onToggleNotifications = { onToggleCustomStrategyNotifications(strategy, it) },
                                onEdit = {
                                    editingCustomStrategy = strategy
                                    preselectedGroup = strategy.effectiveGroup()
                                    preselectedMode = strategy.strategyMode
                                    preselectedSubtype = strategy.multiplierRuleSubtype
                                    showCustomBuilder = true
                                },
                                onDelete = { onDeleteCustomStrategy(strategy.id) }
                            )
                        }
                    }
                }
            }
        }

        // Quick Strategy Templates Across All 3 Groups
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "CREATE STRATEGY",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = PrimaryCobalt
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            editingCustomStrategy = null
                            preselectedGroup = StrategyGroup.MULTIPLIER_INTERVAL
                            preselectedMode = StrategyMode.STRATEGY_A_CONSECUTIVE_LOWS
                            preselectedSubtype = MultiplierRuleSubtype.LOW_MULTIPLIER
                            showCustomBuilder = true
                        },
                        modifier = Modifier.testTag("new_strategy_a_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("A · LOW Rule", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            editingCustomStrategy = null
                            preselectedGroup = StrategyGroup.MULTIPLIER_INTERVAL
                            preselectedMode = StrategyMode.STRATEGY_A_CONSECUTIVE_LOWS
                            preselectedSubtype = MultiplierRuleSubtype.ABOVE_THEN_BELOW
                            showCustomBuilder = true
                        },
                        modifier = Modifier.testTag("new_above_below_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("A · Above X -> Below Y", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            editingCustomStrategy = null
                            preselectedGroup = StrategyGroup.TIME_INTERVAL
                            preselectedMode = StrategyMode.STRATEGY_B_BIG_INTERVALS
                            preselectedSubtype = MultiplierRuleSubtype.BIG_MULTIPLIER
                            showCustomBuilder = true
                        },
                        modifier = Modifier.testTag("new_strategy_b_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("B · Time Interval", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            editingCustomStrategy = null
                            preselectedGroup = StrategyGroup.COLOR_SEQUENCE
                            preselectedMode = StrategyMode.STRATEGY_C_COLOR_PATTERNS
                            preselectedSubtype = MultiplierRuleSubtype.STANDARD_THRESHOLD
                            showCustomBuilder = true
                        },
                        modifier = Modifier.testTag("new_strategy_c_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("C · Color Sequence", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            editingCustomStrategy = null
                            preselectedGroup = StrategyGroup.MULTIPLIER_INTERVAL
                            preselectedMode = StrategyMode.CUSTOM_CONDITION
                            preselectedSubtype = MultiplierRuleSubtype.STANDARD_THRESHOLD
                            showCustomBuilder = true
                        },
                        modifier = Modifier.testTag("create_custom_strategy_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Custom Strategy", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("CUSTOM RULE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Global Observation Classification Thresholds
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "DEFAULT OBSERVATION THRESHOLDS",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = PrimaryCobalt
                )
                Text(
                    text = "Used for statistical LOW / BIG baselines across analytics views. Alerts are strictly observation-only.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = lowThreshText,
                        onValueChange = { lowThreshText = it },
                        label = { Text("Low Threshold (x)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("strategy_low_threshold_input")
                    )
                    OutlinedTextField(
                        value = bigThreshText,
                        onValueChange = { bigThreshText = it },
                        label = { Text("Big Threshold (x)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("strategy_big_threshold_input")
                    )
                }
                OutlinedTextField(
                    value = defaultConsecutiveText,
                    onValueChange = { defaultConsecutiveText = it },
                    label = { Text("Default Consecutive Low Alert Baseline") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("strategy_consecutive_baseline_input")
                )
                StrategyToggleRow(
                    label = "Live Monitoring Observation Enabled",
                    checked = monitoringEnabled,
                    testTag = "strategy_monitoring_switch"
                ) { monitoringEnabled = it }
                OutlinedTextField(
                    value = maxAttemptsText,
                    onValueChange = { maxAttemptsText = it },
                    label = { Text("Max Strategy Bet Attempts per Event") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("max_strategy_attempts_input")
                )
                StrategyToggleRow(
                    label = "Martingale Progression (Double stake on loss, reset on win)",
                    checked = martingaleEnabled,
                    testTag = "martingale_switch"
                ) { martingaleEnabled = it }
            }
        }

        // Save & Close
        var isSaving by remember { mutableStateOf(false) }
        Button(
            enabled = !isSaving,
            onClick = {
                if (isSaving) return@Button
                try {
                    val parsedLow = lowThreshText.trim().toDoubleOrNull()
                    val parsedBig = bigThreshText.trim().toDoubleOrNull()
                    val parsedConsec = defaultConsecutiveText.trim().toIntOrNull()
                    val parsedMaxAttempts = maxAttemptsText.trim().toIntOrNull()?.coerceAtLeast(1) ?: 2

                    if (parsedLow == null || !parsedLow.isFinite() || parsedLow < 1.01 ||
                        parsedBig == null || !parsedBig.isFinite() || parsedBig <= parsedLow ||
                        parsedConsec == null || parsedConsec < 1
                    ) {
                        Toast.makeText(
                            context,
                            "Failed to save settings, please check input",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@Button
                    }

                    isSaving = true
                    val updatedConfig = config.copy(
                        lowThreshold = parsedLow,
                        bigThreshold = parsedBig,
                        strategy1LowThreshold = parsedLow,
                        strategy1RequiredLowRounds = parsedConsec,
                        strategy2HighThreshold = parsedBig,
                        monitoringEnabled = monitoringEnabled,
                        maxStrategyBetAttempts = parsedMaxAttempts,
                        isMartingaleEnabled = martingaleEnabled
                    )
                    onSaveConfig(updatedConfig)
                } catch (e: Exception) {
                    isSaving = false
                    Log.e("StrategyScreen", "Save failed", e)
                    Toast.makeText(
                        context,
                        "Failed to save settings, please check input",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("save_strategy_btn")
        ) {
            Icon(Icons.Default.Save, contentDescription = "Save & Close")
            Spacer(modifier = Modifier.width(8.dp))
            Text("SAVE & CLOSE", fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(80.dp))
    }

    if (showCustomBuilder) {
        CustomStrategyBuilderDialog(
            initial = editingCustomStrategy,
            initialGroup = editingCustomStrategy?.effectiveGroup() ?: preselectedGroup,
            initialMode = editingCustomStrategy?.strategyMode ?: preselectedMode,
            initialSubtype = editingCustomStrategy?.multiplierRuleSubtype ?: preselectedSubtype,
            existingStrategies = customStrategies,
            defaultLowThreshold = config.lowThreshold,
            defaultBigThreshold = config.bigThreshold,
            onDismiss = {
                showCustomBuilder = false
                editingCustomStrategy = null
            },
            onSave = { built ->
                val nextOrder = built.takeIf { it.id != 0L }?.sortOrder
                    ?: ((customStrategies.maxOfOrNull { it.sortOrder } ?: -1) + 1)
                onSaveCustomStrategy(built.copy(sortOrder = nextOrder))
                showCustomBuilder = false
                editingCustomStrategy = null
            }
        )
    }
    }
}

@Composable
private fun CustomStrategyItemCard(
    strategy: CustomPatternStrategy,
    occurrences: List<PatternOccurrenceRecord>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleNotifications: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val statusText = when {
        !strategy.isEnabled -> "DISABLED"
        strategy.currentlyReached -> "DETECTED"
        strategy.currentlyApproaching -> "APPROACHING"
        else -> "MONITORING"
    }
    val statusColor = when {
        !strategy.isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
        strategy.currentlyReached -> ActionStartEmerald
        strategy.currentlyApproaching -> ActionCashOutAmber
        else -> PrimaryCobalt
    }

    val currentHourStart = CalendarHourUtils.hourStartFor(System.currentTimeMillis())
    val thisHourCount = occurrences.count { it.hourStartTimestamp == currentHourStart }
    val latestOcc = occurrences.maxByOrNull { it.triggerTimestampMs }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(
            width = 1.dp,
            color = if (strategy.isEnabled) statusColor.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("custom_strategy_card_${strategy.id}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strategy.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${strategy.effectiveGroup().displayTitle} · Target: ${strategy.targetGameId.uppercase(Locale.US)}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PrimaryCobalt
                    )
                    Text(
                        text = strategy.summaryDescription(),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = strategy.isEnabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.testTag("custom_strategy_switch_${strategy.id}")
                )
            }

            // Independent per-strategy occurrence telemetry & delivery config
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val repeatCapLabel = if (strategy.repeatLimit > 0) "${strategy.repeatLimit}/hr max" else "Unlimited repeats"
                    Text(
                        text = "Hour: $thisHourCount / ${strategy.requiredOccurrencesPerHour} · All-time: ${occurrences.size} · Cooldown ${strategy.repeatCooldownMinutes}m · $repeatCapLabel",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (latestOcc != null) {
                        Text(
                            text = "Latest: ${CalendarHourUtils.formatDateTime(latestOcc.triggerTimestampMs)} (${latestOcc.gameName}) — ${latestOcc.detailsSummary}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = statusColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = statusText,
                            color = statusColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (strategy.useAlarmStyle) "ALARM" else "NOTIFY",
                            color = PrimaryCobalt,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canMoveUp) {
                        IconButton(
                            onClick = onMoveUp,
                            modifier = Modifier.testTag("strategy_move_up_${strategy.id}")
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up", tint = PrimaryCobalt, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (canMoveDown) {
                        IconButton(
                            onClick = onMoveDown,
                            modifier = Modifier.testTag("strategy_move_down_${strategy.id}")
                        ) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down", tint = PrimaryCobalt, modifier = Modifier.size(18.dp))
                        }
                    }
                    IconButton(
                        onClick = { onToggleNotifications(!strategy.notificationEnabled) },
                        modifier = Modifier.testTag("custom_strategy_notif_btn_${strategy.id}")
                    ) {
                        Icon(
                            imageVector = if (strategy.notificationEnabled) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                            contentDescription = "Toggle Strategy Notifications",
                            tint = if (strategy.notificationEnabled) ActionStartEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.testTag("strategy_edit_btn_${strategy.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Custom Strategy",
                            tint = PrimaryCobalt,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("strategy_delete_btn_${strategy.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Custom Strategy",
                            tint = ActionStopRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomStrategyBuilderDialog(
    initial: CustomPatternStrategy?,
    initialGroup: StrategyGroup,
    initialMode: StrategyMode,
    initialSubtype: MultiplierRuleSubtype,
    existingStrategies: List<CustomPatternStrategy>,
    defaultLowThreshold: Double,
    defaultBigThreshold: Double,
    onDismiss: () -> Unit,
    onSave: (CustomPatternStrategy) -> Unit
) {
    val context = LocalContext.current

    var strategyGroup by remember { mutableStateOf(initial?.effectiveGroup() ?: initialGroup) }
    var strategyMode by remember { mutableStateOf(initial?.strategyMode ?: initialMode) }
    var multiplierSubtype by remember { mutableStateOf(initial?.multiplierRuleSubtype ?: initialSubtype) }

    var name by remember {
        mutableStateOf(
            initial?.name ?: when {
                initialSubtype == MultiplierRuleSubtype.ABOVE_THEN_BELOW -> "Above X (3x) -> Below X (2x) Ring"
                initialGroup == StrategyGroup.TIME_INTERVAL -> "Time Interval · Wait Before Bet"
                initialGroup == StrategyGroup.COLOR_SEQUENCE -> "Color Sequence Rule"
                initialSubtype == MultiplierRuleSubtype.LOW_MULTIPLIER -> "LOW Multiplier Consecutive"
                else -> "Custom Multiplier Strategy"
            }
        )
    }
    var targetGameId by remember { mutableStateOf(initial?.targetGameId ?: "ALL") }
    var analysisWindowMinutes by remember { mutableStateOf(initial?.analysisWindowMinutes ?: 60) }
    var customWindowMinutesText by remember { mutableStateOf((initial?.analysisWindowMinutes ?: 60).toString()) }
    var requiredHourlyOccText by remember { mutableStateOf((initial?.requiredOccurrencesPerHour ?: 2).toString()) }
    var inclusiveBounds by remember { mutableStateOf(initial?.inclusiveBounds ?: false) }

    // Multiplier Interval fields
    val initCond = initial?.getConditions()?.firstOrNull()
    var aboveThresholdXText by remember { mutableStateOf(String.format(Locale.US, "%.2f", initial?.aboveThresholdX ?: defaultLowThreshold)) }
    var aboveCountText by remember { mutableStateOf((initial?.aboveConsecutiveCount ?: 3).toString()) }
    var belowThresholdYText by remember { mutableStateOf(String.format(Locale.US, "%.2f", initial?.belowThresholdY ?: defaultLowThreshold)) }
    var belowCountText by remember { mutableStateOf((initial?.belowConsecutiveCount ?: 2).toString()) }
    var logicOperator by remember { mutableStateOf(initial?.logicOperator ?: PatternLogicOperator.AND) }
    var conditions by remember {
        mutableStateOf(
            initial?.getConditions()?.takeIf { it.isNotEmpty() }
                ?: listOf(
                    PatternCondition(
                        comparator = if (initialSubtype == MultiplierRuleSubtype.BIG_MULTIPLIER) PatternComparator.GREATER_OR_EQUAL else PatternComparator.LESS_THAN,
                        threshold = if (initialSubtype == MultiplierRuleSubtype.BIG_MULTIPLIER) defaultBigThreshold else defaultLowThreshold,
                        consecutiveRounds = if (initialSubtype == MultiplierRuleSubtype.BIG_MULTIPLIER) 1 else 6
                    )
                )
        )
    }

    // Time Interval fields
    var bigMultThreshText by remember {
        mutableStateOf(
            String.format(
                Locale.US,
                "%.2f",
                initial?.bigMultiplierThreshold ?: initCond?.threshold ?: defaultBigThreshold
            )
        )
    }
    var postBigWaitText by remember { mutableStateOf((initial?.postBigWaitMinutes ?: 5).toString()) }
    var specificAlertTimesCsv by remember { mutableStateOf(initial?.specificAlertTimesCsv ?: "") }
    var minGapText by remember { mutableStateOf((initial?.minIntervalMinutes ?: 5).toString()) }
    var maxGapText by remember { mutableStateOf((initial?.maxIntervalMinutes ?: 15).toString()) }

    // Color Sequence fields
    var colorDirection by remember { mutableStateOf(initial?.colorMatchDirection ?: ColorMatchDirection.HORIZONTAL) }
    var gridColsVertical by remember { mutableStateOf(initial?.gridColumnsForVertical ?: 8) }
    var colorSequence by remember {
        mutableStateOf(
            initial?.getColorSequence()?.takeIf { it.isNotEmpty() }
                ?: listOf(CrashColorCategory.Purple, CrashColorCategory.Purple, CrashColorCategory.Blue)
        )
    }
    var customHexCsv by remember { mutableStateOf(initial?.customColorHexFilterCsv ?: "") }

    // Notifications, Cooldown & Repeats
    var waitingPeriodText by remember { mutableStateOf((initial?.waitingPeriodSeconds ?: 0).toString()) }
    var priority by remember { mutableStateOf(initial?.notificationLevel ?: NotificationPriorityLevel.HIGH) }
    var approachingEnabled by remember { mutableStateOf(initial?.approachingEnabled ?: true) }
    var notificationEnabled by remember { mutableStateOf(initial?.notificationEnabled ?: true) }
    var useAlarmStyle by remember { mutableStateOf(initial?.useAlarmStyle ?: true) }
    var vibrationEnabled by remember { mutableStateOf(initial?.vibrationEnabled ?: true) }
    var customMessage by remember { mutableStateOf(initial?.customMessage ?: "") }
    var repeatPolicy by remember { mutableStateOf(initial?.repeatPolicy ?: RepeatNotificationPolicy.ONCE_PER_OCCURRENCE) }
    var cooldownMinutesText by remember { mutableStateOf((initial?.repeatCooldownMinutes ?: 0).toString()) }
    var repeatLimitText by remember { mutableStateOf((initial?.repeatLimit ?: 0).toString()) }

    // Review state
    var reviewCandidate by remember { mutableStateOf<CustomPatternStrategy?>(null) }

    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler {
            if (reviewCandidate != null) reviewCandidate = null else onDismiss()
        }
    }

    val selectableColors = remember {
        listOf(
            CrashColorCategory.Blue,
            CrashColorCategory.Purple,
            CrashColorCategory.Pink,
            CrashColorCategory.Red,
            CrashColorCategory.Green,
            CrashColorCategory.Orange
        )
    }

    fun tryBuildCandidate(): Pair<CustomPatternStrategy?, String?> {
        val trimmedName = name.trim()
        val waitSec = waitingPeriodText.trim().toIntOrNull()?.coerceAtLeast(0)
        val reqOcc = requiredHourlyOccText.trim().toIntOrNull()?.coerceAtLeast(1)
        val cooldownMin = cooldownMinutesText.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
        val repLimit = repeatLimitText.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
        val winMin = (if (analysisWindowMinutes <= 0) customWindowMinutesText.trim().toIntOrNull() else analysisWindowMinutes)
            ?.coerceAtLeast(5)

        if (trimmedName.isEmpty() || waitSec == null || reqOcc == null || winMin == null) {
            return null to "Failed to save settings, please check input"
        }

        val finalConditions: List<PatternCondition>
        var resolvedMode = strategyMode
        var aboveX = aboveThresholdXText.trim().toDoubleOrNull() ?: defaultLowThreshold
        var aboveN = aboveCountText.trim().toIntOrNull() ?: 3
        var belowY = belowThresholdYText.trim().toDoubleOrNull() ?: defaultLowThreshold
        var belowM = belowCountText.trim().toIntOrNull() ?: 2
        var bigTh = bigMultThreshText.trim().toDoubleOrNull() ?: defaultBigThreshold
        var postWait = postBigWaitText.trim().toIntOrNull() ?: 0
        var minGap = minGapText.trim().toIntOrNull() ?: 5
        var maxGap = maxGapText.trim().toIntOrNull() ?: 15
        var colorCsv = ""

        when (strategyGroup) {
            StrategyGroup.MULTIPLIER_INTERVAL -> {
                if (multiplierSubtype == MultiplierRuleSubtype.ABOVE_THEN_BELOW) {
                    if (aboveX < 1.0 || belowY < 1.0 || aboveN < 1 || belowM < 1) {
                        return null to "Enter valid thresholds (>= 1.00) and consecutive counts (>= 1)."
                    }
                    resolvedMode = StrategyMode.STRATEGY_A_CONSECUTIVE_LOWS
                    finalConditions = listOf(
                        PatternCondition(
                            comparator = if (inclusiveBounds) PatternComparator.GREATER_OR_EQUAL else PatternComparator.GREATER_THAN,
                            threshold = aboveX,
                            consecutiveRounds = aboveN,
                            timeWindowMinutes = winMin
                        ),
                        PatternCondition(
                            comparator = if (inclusiveBounds) PatternComparator.LESS_OR_EQUAL else PatternComparator.LESS_THAN,
                            threshold = belowY,
                            consecutiveRounds = belowM,
                            timeWindowMinutes = winMin
                        )
                    )
                } else {
                    if (conditions.isEmpty()) {
                        return null to "Add at least one multiplier condition."
                    }
                    resolvedMode = if (strategyMode == StrategyMode.CUSTOM_CONDITION) StrategyMode.CUSTOM_CONDITION else StrategyMode.STRATEGY_A_CONSECUTIVE_LOWS
                    finalConditions = conditions
                }
            }

            StrategyGroup.TIME_INTERVAL -> {
                if (bigTh < 1.01 || postWait < 0 || minGap < 0 || maxGap < minGap) {
                    return null to "Check BIG threshold (>= 1.01), wait minutes (>= 0), and gap range."
                }
                resolvedMode = StrategyMode.STRATEGY_B_BIG_INTERVALS
                finalConditions = listOf(
                    PatternCondition(
                        comparator = if (inclusiveBounds) PatternComparator.GREATER_OR_EQUAL else PatternComparator.GREATER_THAN,
                        threshold = bigTh,
                        consecutiveRounds = 1,
                        timeWindowMinutes = winMin
                    )
                )
            }

            StrategyGroup.COLOR_SEQUENCE -> {
                if (colorSequence.isEmpty() && customHexCsv.isBlank()) {
                    return null to "Tap at least one color or specify custom HEX colors."
                }
                resolvedMode = StrategyMode.STRATEGY_C_COLOR_PATTERNS
                colorCsv = colorSequence.joinToString(",") { it.name }
                finalConditions = listOf(
                    PatternCondition(
                        comparator = PatternComparator.LESS_THAN,
                        threshold = 2.00,
                        consecutiveRounds = maxOf(colorSequence.size, 1),
                        timeWindowMinutes = winMin
                    )
                )
            }
        }

        val built = (initial ?: CustomPatternStrategy(name = trimmedName))
            .copy(
                name = trimmedName,
                isEnabled = initial?.isEnabled ?: true,
                strategyGroup = strategyGroup,
                strategyMode = resolvedMode,
                multiplierRuleSubtype = multiplierSubtype,
                logicOperator = logicOperator,
                aboveThresholdX = aboveX,
                aboveConsecutiveCount = aboveN,
                belowThresholdY = belowY,
                belowConsecutiveCount = belowM,
                inclusiveBounds = inclusiveBounds,
                postBigWaitMinutes = postWait,
                bigMultiplierThreshold = bigTh,
                specificAlertTimesCsv = specificAlertTimesCsv.trim(),
                colorMatchDirection = colorDirection,
                gridColumnsForVertical = gridColsVertical,
                customColorHexFilterCsv = customHexCsv.trim(),
                waitingPeriodSeconds = waitSec,
                notificationLevel = priority,
                approachingEnabled = approachingEnabled,
                customMessage = customMessage.trim(),
                repeatPolicy = repeatPolicy,
                repeatCooldownMinutes = cooldownMin,
                repeatLimit = repLimit,
                notificationEnabled = notificationEnabled,
                useAlarmStyle = useAlarmStyle,
                vibrationEnabled = vibrationEnabled,
                requiredOccurrencesPerHour = reqOcc,
                analysisWindowMinutes = winMin,
                minIntervalMinutes = minGap,
                maxIntervalMinutes = maxGap,
                colorSequenceCsv = colorCsv,
                targetGameId = targetGameId
            )
            .withConditions(finalConditions)
        return built to null
    }

    val livePreviewValidation = remember(
        strategyGroup, multiplierSubtype, name, targetGameId, analysisWindowMinutes,
        aboveThresholdXText, aboveCountText, belowThresholdYText, belowCountText,
        inclusiveBounds, bigMultThreshText, postBigWaitText, minGapText, maxGapText,
        colorSequence, customHexCsv, conditions, customWindowMinutesText
    ) {
        tryBuildCandidate().first?.let { candidate ->
            StrategyRangeValidator.validate(candidate, existingStrategies, defaultLowThreshold, defaultBigThreshold)
        }
    }

    val hasActivityRegistry = LocalActivityResultRegistryOwner.current != null
    val builderCardContent: @Composable () -> Unit = {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
        ) {
            val activeReview = reviewCandidate
            if (activeReview != null) {
                // Step 2: Explicit Pre-Save Review Screen
                val validation = StrategyRangeValidator.validate(
                    activeReview,
                    existingStrategies,
                    defaultLowThreshold,
                    defaultBigThreshold
                )
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                        .testTag("strategy_review_panel"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "REVIEW STRATEGY BEFORE SAVING",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        color = PrimaryCobalt
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("Name: ${activeReview.name}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Group: ${activeReview.effectiveGroup().displayTitle}", fontSize = 12.sp)
                            Text("Target Game: ${activeReview.targetGameId}", fontSize = 12.sp)
                            Text("Condition Summary: ${activeReview.summaryDescription()}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = PrimaryCobalt)
                            Text("Boundary Mode: ${if (activeReview.inclusiveBounds) "Inclusive (>= / <=)" else "Exclusive (> / <)"}", fontSize = 11.sp)
                            Text("Analysis Window: ${activeReview.analysisWindowMinutes}m · Min Occurrences: ${activeReview.requiredOccurrencesPerHour}", fontSize = 11.sp)
                            Text("Alert Style: ${if (activeReview.useAlarmStyle) "Alarm" else "Normal Notification"} · Vibration: ${if (activeReview.vibrationEnabled) "ON" else "OFF"}", fontSize = 11.sp)
                            Text("Cooldown: ${activeReview.repeatCooldownMinutes}m · Repeat Limit: ${if (activeReview.repeatLimit == 0) "Unlimited" else "${activeReview.repeatLimit}/hr"}", fontSize = 11.sp)
                            if (activeReview.specificAlertTimesCsv.isNotBlank()) {
                                Text("Specific Alert Times: ${activeReview.specificAlertTimesCsv}", fontSize = 11.sp)
                            }
                            if (activeReview.customColorHexFilterCsv.isNotBlank()) {
                                Text("Custom HEX Colors: ${activeReview.customColorHexFilterCsv}", fontSize = 11.sp)
                            }
                        }
                    }

                    if (validation.blockingErrors.isNotEmpty() || validation.warnings.isNotEmpty()) {
                        Surface(
                            color = if (validation.blockingErrors.isNotEmpty()) Color(0xFFFEE2E2) else Color(0xFFFEF3C7),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                validation.blockingErrors.forEach { err ->
                                    Text("Error: $err", color = ActionStopRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                validation.warnings.forEach { warn ->
                                    Text("Warning: $warn", color = Color(0xFF92400E), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { reviewCandidate = null },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("EDIT SETTINGS")
                        }
                        Button(
                            enabled = validation.isValid,
                            onClick = { onSave(activeReview) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("builder_save_strategy_btn")
                        ) {
                            Text("CONFIRM SAVE", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // Step 1: Configuration Form
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (initial == null) "CREATE STRATEGY" else "EDIT STRATEGY",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = PrimaryCobalt
                    )

                    Text("Strategy Group", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StrategyGroup.entries.forEach { grp ->
                            FilterChip(
                                selected = strategyGroup == grp,
                                onClick = {
                                    strategyGroup = grp
                                    strategyMode = when (grp) {
                                        StrategyGroup.MULTIPLIER_INTERVAL -> StrategyMode.STRATEGY_A_CONSECUTIVE_LOWS
                                        StrategyGroup.TIME_INTERVAL -> StrategyMode.STRATEGY_B_BIG_INTERVALS
                                        StrategyGroup.COLOR_SEQUENCE -> StrategyMode.STRATEGY_C_COLOR_PATTERNS
                                    }
                                },
                                label = { Text(grp.displayTitle, fontSize = 10.sp) },
                                modifier = Modifier.testTag("builder_group_${grp.name}")
                            )
                        }
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Strategy Name") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("builder_strategy_name_input")
                    )

                    Text("Target Game", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = targetGameId == "ALL",
                            onClick = { targetGameId = "ALL" },
                            label = { Text("All Games", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = targetGameId == "unknown",
                            onClick = { targetGameId = "unknown" },
                            label = { Text("Unknown", fontSize = 11.sp) }
                        )
                        GameAdapterRegistry.allAdapters().forEach { adapter ->
                            FilterChip(
                                selected = targetGameId == adapter.gameId,
                                onClick = { targetGameId = adapter.gameId },
                                label = { Text(adapter.displayedGameName, fontSize = 11.sp) }
                            )
                        }
                    }

                    when (strategyGroup) {
                        StrategyGroup.MULTIPLIER_INTERVAL -> {
                            Text("Condition Type", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                MultiplierRuleSubtype.entries.forEach { sub ->
                                    FilterChip(
                                        selected = multiplierSubtype == sub,
                                        onClick = { multiplierSubtype = sub },
                                        label = { Text(sub.label, fontSize = 10.sp) },
                                        modifier = Modifier.testTag("builder_subtype_${sub.name}")
                                    )
                                }
                            }

                            StrategyToggleRow(
                                label = "Inclusive Boundaries (>= / <= instead of > / <)",
                                checked = inclusiveBounds,
                                testTag = "builder_inclusive_switch"
                            ) { inclusiveBounds = it }

                            if (multiplierSubtype == MultiplierRuleSubtype.ABOVE_THEN_BELOW) {
                                Text(
                                    text = "Trigger when ABOVE X occurs N times in a row, immediately followed by BELOW Y occurring M times in a row:",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = aboveThresholdXText,
                                        onValueChange = { aboveThresholdXText = it },
                                        label = { Text("Above Threshold X") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).testTag("builder_above_x_input")
                                    )
                                    OutlinedTextField(
                                        value = aboveCountText,
                                        onValueChange = { aboveCountText = it },
                                        label = { Text("Above Count (e.g. 3)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).testTag("builder_above_count_input")
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = belowThresholdYText,
                                        onValueChange = { belowThresholdYText = it },
                                        label = { Text("Below Threshold Y") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).testTag("builder_below_y_input")
                                    )
                                    OutlinedTextField(
                                        value = belowCountText,
                                        onValueChange = { belowCountText = it },
                                        label = { Text("Below Count (e.g. 2)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).testTag("builder_below_count_input")
                                    )
                                }
                            } else {
                                Text("Condition Logic", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PatternLogicOperator.entries.forEach { op ->
                                        FilterChip(
                                            selected = logicOperator == op,
                                            onClick = { logicOperator = op },
                                            label = { Text(op.name, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                        )
                                    }
                                }
                                conditions.forEachIndexed { idx, cond ->
                                    ConditionEditorBlock(
                                        index = idx,
                                        condition = cond,
                                        canRemove = conditions.size > 1,
                                        onUpdate = { updated ->
                                            conditions = conditions.toMutableList().also { it[idx] = updated }
                                        },
                                        onRemove = {
                                            conditions = conditions.toMutableList().also { it.removeAt(idx) }
                                        }
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        conditions = conditions + PatternCondition(
                                            comparator = PatternComparator.LESS_THAN,
                                            threshold = defaultLowThreshold,
                                            consecutiveRounds = 5
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Add Condition", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("ADD CONDITION", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        StrategyGroup.TIME_INTERVAL -> {
                            OutlinedTextField(
                                value = bigMultThreshText,
                                onValueChange = { bigMultThreshText = it },
                                label = { Text("BIG Multiplier Threshold (>= x)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("builder_strat_b_thresh")
                            )

                            Text("Wait Time Before Betting After BIG (minutes):", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(2, 5, 10, 15, 30, 45, 55).forEach { mins ->
                                    FilterChip(
                                        selected = postBigWaitText.trim().toIntOrNull() == mins,
                                        onClick = { postBigWaitText = mins.toString() },
                                        label = { Text("${mins}m", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                        modifier = Modifier.testTag("builder_wait_chip_${mins}m")
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = postBigWaitText,
                                onValueChange = { postBigWaitText = it },
                                label = { Text("Wait Minutes After BIG (e.g. 2, 5, 10, 15, 30, 45, 55)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("builder_post_big_wait_input")
                            )

                            Text("Analysis Period:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(60 to "1 Hour", 180 to "3 Hours", 360 to "6 Hours", 0 to "Custom").forEach { (mins, label) ->
                                    FilterChip(
                                        selected = if (mins == 0) analysisWindowMinutes !in listOf(60, 180, 360) else analysisWindowMinutes == mins,
                                        onClick = {
                                            if (mins > 0) {
                                                analysisWindowMinutes = mins
                                                customWindowMinutesText = mins.toString()
                                            } else {
                                                analysisWindowMinutes = 120
                                                customWindowMinutesText = "120"
                                            }
                                        },
                                        label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = customWindowMinutesText,
                                onValueChange = { raw ->
                                    customWindowMinutesText = raw
                                    raw.trim().toIntOrNull()?.takeIf { it >= 5 }?.let { analysisWindowMinutes = it }
                                },
                                label = { Text("Period Minutes (60=1h, 180=3h, 360=6h)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = minGapText,
                                    onValueChange = { minGapText = it },
                                    label = { Text("Min Gap (min)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f).testTag("builder_strat_b_min")
                                )
                                OutlinedTextField(
                                    value = maxGapText,
                                    onValueChange = { maxGapText = it },
                                    label = { Text("Max Gap (min)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f).testTag("builder_strat_b_max")
                                )
                            }
                            OutlinedTextField(
                                value = specificAlertTimesCsv,
                                onValueChange = { specificAlertTimesCsv = it },
                                label = { Text("Specific Alert Times (HH:mm, comma-separated, opt)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        StrategyGroup.COLOR_SEQUENCE -> {
                            Text("Matching Direction:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ColorMatchDirection.entries.forEach { dir ->
                                    FilterChip(
                                        selected = colorDirection == dir,
                                        onClick = { colorDirection = dir },
                                        label = { Text(dir.label, fontSize = 10.sp) },
                                        modifier = Modifier.testTag("builder_color_dir_${dir.name}")
                                    )
                                }
                            }
                            if (colorDirection != ColorMatchDirection.HORIZONTAL) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(7, 8).forEach { cols ->
                                        FilterChip(
                                            selected = gridColsVertical == cols,
                                            onClick = { gridColsVertical = cols },
                                            label = { Text("$cols Col Grid Alignment", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                        )
                                    }
                                }
                            }

                            Text("Tap colors to append to sequence:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                selectableColors.forEach { cat ->
                                    FilterChip(
                                        selected = false,
                                        onClick = { colorSequence = colorSequence + cat },
                                        leadingIcon = {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(parseSafeCrashColor(cat.defaultHex))
                                            )
                                        },
                                        label = { Text("+ ${cat.displayName}", fontSize = 11.sp) }
                                    )
                                }
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (colorSequence.isEmpty()) {
                                            "Sequence empty — tap colors above or enter HEX below"
                                        } else {
                                            colorSequence.joinToString(" -> ") { it.displayName }
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (colorSequence.isNotEmpty()) {
                                        OutlinedButton(onClick = { colorSequence = colorSequence.dropLast(1) }) {
                                            Text("Undo", fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = customHexCsv,
                                onValueChange = { customHexCsv = it },
                                label = { Text("Custom Observed HEX Sequence (optional e.g. #9333EA,#9333EA)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    HorizontalDivider()

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = requiredHourlyOccText,
                            onValueChange = { requiredHourlyOccText = it },
                            label = { Text("Min Occurrences / Period") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = waitingPeriodText,
                            onValueChange = { waitingPeriodText = it },
                            label = { Text("Alert Delay (sec)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = cooldownMinutesText,
                            onValueChange = { cooldownMinutesText = it },
                            label = { Text("Cooldown (min, 0=none)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = repeatLimitText,
                            onValueChange = { repeatLimitText = it },
                            label = { Text("Max Repeats/hr (0=∞)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text("Notification Priority", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NotificationPriorityLevel.entries.forEach { lvl ->
                            FilterChip(
                                selected = priority == lvl,
                                onClick = { priority = lvl },
                                label = { Text(lvl.label, fontSize = 11.sp) }
                            )
                        }
                    }

                    StrategyToggleRow("Use Alarm Style (Full Sound Profile vs Normal Notification)", useAlarmStyle) {
                        useAlarmStyle = it
                    }
                    StrategyToggleRow("Enable Vibration for This Strategy", vibrationEnabled) {
                        vibrationEnabled = it
                    }
                    StrategyToggleRow("Send Approaching Alert (1 round before)", approachingEnabled) {
                        approachingEnabled = it
                    }
                    StrategyToggleRow("Enable Notifications for This Strategy", notificationEnabled) {
                        notificationEnabled = it
                    }

                    Text("Repeat Policy", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RepeatNotificationPolicy.entries.forEach { pol ->
                            FilterChip(
                                selected = repeatPolicy == pol,
                                onClick = { repeatPolicy = pol },
                                label = { Text(pol.label, fontSize = 10.sp) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = customMessage,
                        onValueChange = { customMessage = it },
                        label = { Text("Optional Custom Note in Alert") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    livePreviewValidation?.let { valRes ->
                        if (valRes.blockingErrors.isNotEmpty() || valRes.warnings.isNotEmpty()) {
                            Surface(
                                color = if (valRes.blockingErrors.isNotEmpty()) Color(0xFFFEE2E2) else Color(0xFFFEF3C7),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("builder_validation_banner")
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    valRes.blockingErrors.forEach { err ->
                                        Text("Conflict: $err", color = ActionStopRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    valRes.warnings.forEach { warn ->
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFF92400E), modifier = Modifier.size(14.dp))
                                            Text(warn, color = Color(0xFF92400E), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                            Text("CANCEL")
                        }
                        Button(
                            onClick = {
                                val (built, err) = tryBuildCandidate()
                                if (built == null) {
                                    Toast.makeText(context, err ?: "Failed to save settings, please check input", Toast.LENGTH_SHORT).show()
                                } else {
                                    val validation = StrategyRangeValidator.validate(
                                        built,
                                        existingStrategies,
                                        defaultLowThreshold,
                                        defaultBigThreshold
                                    )
                                    if (!validation.isValid) {
                                        Toast.makeText(context, validation.blockingErrors.firstOrNull() ?: "Invalid configuration", Toast.LENGTH_SHORT).show()
                                    } else {
                                        reviewCandidate = built
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("builder_review_strategy_btn")
                        ) {
                            Text("REVIEW & SAVE", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
    if (hasActivityRegistry) {
        Dialog(onDismissRequest = onDismiss) {
            builderCardContent()
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            builderCardContent()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionEditorBlock(
    index: Int,
    condition: PatternCondition,
    canRemove: Boolean,
    onUpdate: (PatternCondition) -> Unit,
    onRemove: () -> Unit
) {
    var threshText by remember(condition) { mutableStateOf(condition.threshold.toString()) }
    var upperText by remember(condition) { mutableStateOf(condition.upperThreshold?.toString() ?: "10.0") }
    var consecutiveText by remember(condition) { mutableStateOf(condition.consecutiveRounds.toString()) }
    var windowMinText by remember(condition) { mutableStateOf(condition.timeWindowMinutes?.toString() ?: "") }
    var sequenceText by remember(condition) {
        mutableStateOf(condition.sequenceMultipliers.joinToString(", "))
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Condition #${index + 1}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                if (canRemove) {
                    IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove Condition", tint = ActionStopRed, modifier = Modifier.size(16.dp))
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PatternComparator.entries.forEach { comp ->
                    FilterChip(
                        selected = condition.comparator == comp,
                        onClick = { onUpdate(condition.copy(comparator = comp)) },
                        label = { Text(comp.symbol, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = threshText,
                    onValueChange = { raw ->
                        threshText = raw
                        raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 1.0 }?.let {
                            onUpdate(condition.copy(threshold = it))
                        }
                    },
                    label = { Text(if (condition.comparator == PatternComparator.BETWEEN) "Min (x)" else "Threshold (x)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                if (condition.comparator == PatternComparator.BETWEEN) {
                    OutlinedTextField(
                        value = upperText,
                        onValueChange = { raw ->
                            upperText = raw
                            raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 1.0 }?.let {
                                onUpdate(condition.copy(upperThreshold = it))
                            }
                        },
                        label = { Text("Max (x)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    OutlinedTextField(
                        value = consecutiveText,
                        onValueChange = { raw ->
                            consecutiveText = raw
                            raw.trim().toIntOrNull()?.coerceAtLeast(1)?.let {
                                onUpdate(condition.copy(consecutiveRounds = it))
                            }
                        },
                        label = { Text("Consecutive") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = windowMinText,
                    onValueChange = { raw ->
                        windowMinText = raw
                        val parsed = raw.trim().toIntOrNull()?.takeIf { it > 0 }
                        onUpdate(condition.copy(timeWindowMinutes = parsed))
                    },
                    label = { Text("Window (min, opt)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = sequenceText,
                    onValueChange = { raw ->
                        sequenceText = raw
                        val seqList = raw.split(",")
                            .mapNotNull { it.trim().toDoubleOrNull()?.takeIf { v -> v.isFinite() && v >= 1.0 } }
                        onUpdate(condition.copy(sequenceMultipliers = seqList))
                    },
                    label = { Text("Exact Seq (1.2,1.5)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun StrategyToggleRow(
    label: String,
    checked: Boolean,
    testTag: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
        )
    }
}
