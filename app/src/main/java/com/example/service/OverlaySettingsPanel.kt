package com.example.service

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.engine.OperatingScheduleEvaluator
import com.example.data.model.NotificationSettings
import com.example.data.model.StrategyConfig
import com.example.ui.theme.ActionStartEmerald
import com.example.ui.theme.ActionStopRed
import com.example.ui.theme.PrimaryCobalt
import java.util.Locale

/**
 * Floating Overlay Settings Window composable designed to hover cleanly over the monitored
 * crash game without interrupting live detection or pulling the user into full-screen MainActivity.
 * Includes an explicit Full Screen button when the user intentionally wants to expand.
 * Note: Intentionally avoids ActivityResult launchers so it runs safely inside a Service ComposeView.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverlaySettingsPanelComposable(
    strategyConfig: StrategyConfig,
    notificationSettings: NotificationSettings,
    isStandbyOutsideSchedule: Boolean = false,
    isScreenHeld: Boolean = false,
    isCpuAwakeHeld: Boolean = false,
    awakeError: String? = null,
    isMonitoringRunning: Boolean = false,
    isAlarmRinging: Boolean = false,
    activeAlarmTitle: String? = null,
    initialBubbleOpacity: Float = 0.95f,
    initialBubbleSizeDp: Float = 58f,
    initialHapticsEnabled: Boolean = true,
    onStrategyConfigChanged: (StrategyConfig) -> Unit = {},
    onNotificationSettingsChanged: (NotificationSettings) -> Unit = {},
    onSaveOverlaySettings: (
        config: StrategyConfig,
        notif: NotificationSettings,
        opacity: Float,
        bubbleSizeDp: Float,
        hapticsEnabled: Boolean
    ) -> Unit = { _, _, _, _, _ -> },
    onResetSessionStats: () -> Unit = {},
    onStopAlarm: () -> Unit = {},
    onSnoozeAlarm: () -> Unit = {},
    onTestAlarm: (NotificationSettings) -> Unit = {},
    onBackToMonitorPanel: (() -> Unit)? = null,
    onExpandFullScreen: () -> Unit = {},
    onClose: () -> Unit = {},
    onToggleBetHand: (() -> Unit)? = null,
    onToggleCashOutHand: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var localConfig by remember(strategyConfig) { mutableStateOf(strategyConfig) }
    var localNotif by remember(notificationSettings) { mutableStateOf(notificationSettings) }

    var baseStakeText by remember(strategyConfig.baseStake) {
        mutableStateOf(String.format(Locale.US, "%.2f", strategyConfig.baseStake))
    }
    var targetMultText by remember(strategyConfig.targetMultiplier) {
        mutableStateOf(String.format(Locale.US, "%.2f", strategyConfig.targetMultiplier))
    }
    var lowThreshText by remember(strategyConfig.lowThreshold) {
        mutableStateOf(String.format(Locale.US, "%.2f", strategyConfig.lowThreshold))
    }
    var boundaryText by remember(strategyConfig.boundaryValue) {
        mutableStateOf(String.format(Locale.US, "%.2f", strategyConfig.boundaryValue))
    }
    var bigThreshText by remember(strategyConfig.bigThreshold) {
        mutableStateOf(String.format(Locale.US, "%.2f", strategyConfig.bigThreshold))
    }
    var startTimeText by remember(strategyConfig.scheduleStartHour, strategyConfig.scheduleStartMinute) {
        mutableStateOf(
            String.format(Locale.US, "%02d:%02d", strategyConfig.scheduleStartHour, strategyConfig.scheduleStartMinute)
        )
    }
    var stopTimeText by remember(strategyConfig.scheduleEndHour, strategyConfig.scheduleEndMinute) {
        mutableStateOf(
            String.format(Locale.US, "%02d:%02d", strategyConfig.scheduleEndHour, strategyConfig.scheduleEndMinute)
        )
    }
    var quietStartHourText by remember(notificationSettings.quietStartHour) {
        mutableStateOf(notificationSettings.quietStartHour.toString())
    }
    var quietEndHourText by remember(notificationSettings.quietEndHour) {
        mutableStateOf(notificationSettings.quietEndHour.toString())
    }

    var opacityValue by remember { mutableFloatStateOf(initialBubbleOpacity.coerceIn(0.3f, 1.0f)) }
    var bubbleSizeDp by remember { mutableFloatStateOf(initialBubbleSizeDp.coerceIn(44f, 80f)) }
    var hapticsEnabled by remember { mutableStateOf(initialHapticsEnabled) }
    var validationError by remember { mutableStateOf<String?>(null) }

    fun pushConfig(updated: StrategyConfig) {
        localConfig = updated
        onStrategyConfigChanged(updated)
    }

    fun pushNotif(updated: NotificationSettings) {
        localNotif = updated
        onNotificationSettingsChanged(updated)
    }

    Card(
        modifier = modifier
            .shadow(18.dp, RoundedCornerShape(24.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
            .testTag("overlay_settings_window"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Sticky Floating Window Header (drag grip + live dot + Full Screen expand + Close)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (onBackToMonitorPanel != null) {
                            IconButton(
                                onClick = onBackToMonitorPanel,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("overlay_settings_back_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to Monitor Panel",
                                    tint = PrimaryCobalt,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.DragIndicator,
                                contentDescription = "Drag Overlay Settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(start = 4.dp, end = 4.dp)
                                    .size(18.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (isMonitoringRunning) ActionStartEmerald else Color(0xFF94A3B8),
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "OVERLAY SETTINGS",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isMonitoringRunning) "Monitoring live · Game stays active" else "Compact floating mode",
                                color = if (isMonitoringRunning) ActionStartEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onExpandFullScreen,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .heightIn(min = 40.dp)
                                .minimumInteractiveComponentSize()
                                .testTag("overlay_settings_fullscreen_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInFull,
                                contentDescription = "Full Screen Mode",
                                modifier = Modifier.size(14.dp),
                                tint = PrimaryCobalt
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Full Screen",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryCobalt,
                                maxLines = 1
                            )
                        }
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("overlay_settings_close_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Overlay Settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // Scrollable Settings Body
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isAlarmRinging) {
                    Surface(
                        color = Color(0xFFFEE2E2),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ActionStopRed),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("overlay_settings_alarm_banner")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = activeAlarmTitle ?: "SIGNAL ALARM RINGING",
                                color = ActionStopRed,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onStopAlarm,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ActionStopRed,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("overlay_settings_stop_alarm_btn")
                                ) {
                                    Icon(Icons.Default.NotificationsOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("STOP ALARM", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                }
                                OutlinedButton(
                                    onClick = onSnoozeAlarm,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("overlay_settings_snooze_alarm_btn")
                                ) {
                                    Icon(Icons.Default.Snooze, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("SNOOZE 5M", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // 1. Automated Betting & Cash-Out
                OverlaySettingsSectionCard(
                    title = "AUTOMATED BETTING & CASH-OUT",
                    icon = Icons.Default.PlayArrow,
                    modifier = Modifier.testTag("overlay_settings_auto_bet_card")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (localConfig.isAutoBetEnabled) "ACTIVE (Betting & Editing)" else "PAUSED (Recording Only)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (localConfig.isAutoBetEnabled) ActionStartEmerald else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Disable to keep recording rounds without clicking or editing stakes.",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = localConfig.isAutoBetEnabled,
                            onCheckedChange = {
                                pushConfig(
                                    localConfig.copy(
                                        isAutoBetEnabled = it,
                                        isAutoCashOutEnabled = it
                                    )
                                )
                            },
                            modifier = Modifier.testTag("overlay_settings_auto_bet_switch")
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = baseStakeText,
                            onValueChange = { raw ->
                                baseStakeText = raw
                                raw.toDoubleOrNull()?.let { v ->
                                    if (v >= 1.0) pushConfig(localConfig.copy(baseStake = v))
                                }
                            },
                            label = { Text("Base Stake (min 1.0)", fontSize = 11.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("overlay_settings_base_stake_input")
                        )
                        OutlinedTextField(
                            value = targetMultText,
                            onValueChange = { raw ->
                                targetMultText = raw
                                raw.toDoubleOrNull()?.let { v ->
                                    if (v >= 1.01) pushConfig(localConfig.copy(targetMultiplier = v))
                                }
                            },
                            label = { Text("Cash-Out (e.g. 2.00x)", fontSize = 11.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("overlay_settings_target_multiplier_input")
                        )
                    }

                    OverlayToggleRow(
                        label = "Automation Master Lock",
                        checked = localConfig.automationMasterEnabled,
                        testTag = "overlay_settings_automation_master_switch",
                        onCheckedChange = { pushConfig(localConfig.copy(automationMasterEnabled = it)) }
                    )
                    OverlayToggleRow(
                        label = "Dismiss Keyboard Safely Before Bet",
                        checked = localConfig.dismissKeyboardBeforeBet,
                        testTag = "overlay_settings_dismiss_keyboard_switch",
                        onCheckedChange = { pushConfig(localConfig.copy(dismissKeyboardBeforeBet = it)) }
                    )
                    OverlayToggleRow(
                        label = "Verify Stake Input Read-Back",
                        checked = localConfig.verifyStakeReadBack,
                        testTag = "overlay_settings_verify_stake_switch",
                        onCheckedChange = { pushConfig(localConfig.copy(verifyStakeReadBack = it)) }
                    )
                    OverlayToggleRow(
                        label = "Auto Game Recognition",
                        checked = localConfig.autoGameRecognitionEnabled,
                        testTag = "overlay_settings_auto_game_switch",
                        onCheckedChange = { pushConfig(localConfig.copy(autoGameRecognitionEnabled = it)) }
                    )
                    OverlayToggleRow(
                        label = "Collect & Record Crash History",
                        checked = localConfig.historyCollectionEnabled,
                        testTag = "overlay_settings_history_collection_switch",
                        onCheckedChange = { pushConfig(localConfig.copy(historyCollectionEnabled = it)) }
                    )

                    if (onToggleBetHand != null && onToggleCashOutHand != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onToggleBetHand,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("overlay_settings_bet_hand_btn")
                            ) {
                                Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("BET Hand", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = onToggleCashOutHand,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("overlay_settings_cash_hand_btn")
                            ) {
                                Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("CASH Hand", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                // 2. Screen Awake & Operating Schedule
                OverlaySettingsSectionCard(
                    title = "SCREEN AWAKE & OPERATING HOURS",
                    icon = Icons.Default.BatteryChargingFull,
                    trailingBadge = {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (isScreenHeld) ActionStartEmerald.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = if (isScreenHeld) "SCREEN HELD" else "NORMAL SLEEP",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isScreenHeld) ActionStartEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    },
                    modifier = Modifier.testTag("overlay_settings_awake_schedule_card")
                ) {
                    Text(
                        text = "Hardware: Screen=${if (isScreenHeld) "ACTIVE" else "INACTIVE"} · CPU=${if (isCpuAwakeHeld) "HELD" else "RELEASED"}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isScreenHeld || isCpuAwakeHeld) ActionStartEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!awakeError.isNullOrBlank()) {
                        Text(
                            text = awakeError,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ActionStopRed
                        )
                    }
                    OverlayToggleRow(
                        label = "Keep Screen Awake During Active Monitoring",
                        checked = localConfig.keepScreenAwake,
                        testTag = "overlay_settings_keep_awake_switch",
                        onCheckedChange = { pushConfig(localConfig.copy(keepScreenAwake = it)) }
                    )
                    OverlayToggleRow(
                        label = "Enable Scheduled Operating Window",
                        checked = localConfig.scheduleEnabled,
                        testTag = "overlay_settings_schedule_switch",
                        onCheckedChange = { pushConfig(localConfig.copy(scheduleEnabled = it)) }
                    )
                    if (localConfig.scheduleEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = startTimeText,
                                onValueChange = { startTimeText = it },
                                label = { Text("Start (HH:MM)", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("overlay_settings_schedule_start_input")
                            )
                            OutlinedTextField(
                                value = stopTimeText,
                                onValueChange = { stopTimeText = it },
                                label = { Text("Stop (HH:MM)", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("overlay_settings_schedule_stop_input")
                            )
                        }
                        val windowLabel = OperatingScheduleEvaluator.formatWindow(
                            localConfig.scheduleStartHour,
                            localConfig.scheduleStartMinute,
                            localConfig.scheduleEndHour,
                            localConfig.scheduleEndMinute
                        )
                        Surface(
                            color = if (isStandbyOutsideSchedule) Color(0xFFFEF3C7) else ActionStartEmerald.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = if (isStandbyOutsideSchedule) Color(0xFF92400E) else ActionStartEmerald,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isStandbyOutsideSchedule) "Standby ($windowLabel)" else "Active ($windowLabel)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isStandbyOutsideSchedule) Color(0xFF92400E) else ActionStartEmerald
                                )
                            }
                        }
                    }
                }

                // 3. Crash Grid Size & Thresholds
                OverlaySettingsSectionCard(
                    title = "GRID SIZE (${localConfig.gridRows}×${localConfig.gridColumns}) & THRESHOLDS",
                    icon = Icons.Default.GridView,
                    modifier = Modifier.testTag("overlay_settings_grid_thresholds_card")
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(4, 5).forEach { r ->
                            FilterChip(
                                selected = localConfig.gridRows == r,
                                onClick = { pushConfig(localConfig.copy(gridRows = r)) },
                                label = { Text("$r Rows", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                modifier = Modifier.testTag("overlay_settings_grid_rows_$r")
                            )
                        }
                        listOf(7, 8).forEach { c ->
                            FilterChip(
                                selected = localConfig.gridColumns == c,
                                onClick = { pushConfig(localConfig.copy(gridColumns = c)) },
                                label = { Text("$c Cols", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                modifier = Modifier.testTag("overlay_settings_grid_cols_$c")
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = lowThreshText,
                            onValueChange = { lowThreshText = it },
                            label = { Text("Low (< x)", fontSize = 10.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("overlay_settings_low_threshold_input")
                        )
                        OutlinedTextField(
                            value = boundaryText,
                            onValueChange = { boundaryText = it },
                            label = { Text("Bound (==)", fontSize = 10.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("overlay_settings_boundary_input")
                        )
                        OutlinedTextField(
                            value = bigThreshText,
                            onValueChange = { bigThreshText = it },
                            label = { Text("Big (>= x)", fontSize = 10.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("overlay_settings_big_threshold_input")
                        )
                    }
                }

                // 4. Alerts, Quiet Hours & Bubble Appearance
                OverlaySettingsSectionCard(
                    title = "ALERTS & OVERLAY APPEARANCE",
                    icon = Icons.Default.Notifications,
                    modifier = Modifier.testTag("overlay_settings_alerts_appearance_card")
                ) {
                    OverlayToggleRow(
                        label = "Master Pattern & System Alerts",
                        checked = localNotif.masterEnabled,
                        testTag = "overlay_settings_notif_master_switch",
                        onCheckedChange = { pushNotif(localNotif.copy(masterEnabled = it)) }
                    )
                    OverlayToggleRow(
                        label = "Pattern Detected Alerts",
                        checked = localNotif.reachedEnabled,
                        testTag = "overlay_settings_notif_reached_switch",
                        onCheckedChange = { pushNotif(localNotif.copy(reachedEnabled = it)) }
                    )
                    OverlayToggleRow(
                        label = "Signal Alarm (Pattern Match)",
                        checked = localNotif.highPriorityAlarmEnabled,
                        testTag = "overlay_settings_signal_alarm_switch",
                        onCheckedChange = { pushNotif(localNotif.copy(highPriorityAlarmEnabled = it)) }
                    )
                    OverlayToggleRow(
                        label = "Quiet Hours",
                        checked = localNotif.quietHoursEnabled,
                        testTag = "overlay_settings_notif_quiet_switch",
                        onCheckedChange = { pushNotif(localNotif.copy(quietHoursEnabled = it)) }
                    )
                    if (localNotif.quietHoursEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = quietStartHourText,
                                onValueChange = { quietStartHourText = it },
                                label = { Text("Quiet Start (0-23)", fontSize = 10.sp) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("overlay_settings_quiet_start_input")
                            )
                            OutlinedTextField(
                                value = quietEndHourText,
                                onValueChange = { quietEndHourText = it },
                                label = { Text("Quiet End (0-23)", fontSize = 10.sp) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("overlay_settings_quiet_end_input")
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Palette, contentDescription = null, tint = PrimaryCobalt, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Bubble Opacity (${(opacityValue * 100).toInt()}%)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Slider(
                        value = opacityValue,
                        onValueChange = { opacityValue = it },
                        valueRange = 0.3f..1.0f,
                        modifier = Modifier.testTag("overlay_settings_opacity_slider")
                    )

                    Text(
                        text = "Bubble Size (${bubbleSizeDp.toInt()}dp)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = bubbleSizeDp,
                        onValueChange = { bubbleSizeDp = it },
                        valueRange = 44f..80f,
                        modifier = Modifier.testTag("overlay_settings_bubble_size_slider")
                    )

                    OverlayToggleRow(
                        label = "Haptic Feedback",
                        checked = hapticsEnabled,
                        testTag = "overlay_settings_haptics_switch",
                        onCheckedChange = { hapticsEnabled = it }
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AssistChip(
                            onClick = { onTestAlarm(localNotif) },
                            label = { Text("Test Alarm", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            leadingIcon = {
                                Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(15.dp))
                            },
                            modifier = Modifier.testTag("overlay_settings_test_alarm_btn")
                        )
                        AssistChip(
                            onClick = onResetSessionStats,
                            label = { Text("Reset Stats", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            leadingIcon = {
                                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(15.dp))
                            },
                            modifier = Modifier.testTag("overlay_settings_reset_session_btn")
                        )
                    }

                    Text(
                        text = "Need ringtone pickers, per-category alarm profiles, or Android permissions? Tap Full Screen in the top bar.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!validationError.isNullOrBlank()) {
                    Surface(
                        color = Color(0xFFFEE2E2),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("overlay_settings_error_banner")
                    ) {
                        Text(
                            text = validationError ?: "",
                            color = ActionStopRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }

            HorizontalDivider()

            // Sticky Bottom Save Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onExpandFullScreen,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("overlay_settings_footer_fullscreen_btn")
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Full Screen", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        val parsedStake = baseStakeText.trim().toDoubleOrNull()
                        val parsedTarget = targetMultText.trim().toDoubleOrNull()
                        val parsedLow = lowThreshText.trim().toDoubleOrNull()
                        val parsedBoundary = boundaryText.trim().toDoubleOrNull()
                        val parsedBig = bigThreshText.trim().toDoubleOrNull()
                        val startPair = parseOverlayHourMinute(startTimeText)
                        val stopPair = parseOverlayHourMinute(stopTimeText)
                        val quietStart = quietStartHourText.trim().toIntOrNull()
                        val quietEnd = quietEndHourText.trim().toIntOrNull()

                        val stakeInvalid = parsedStake == null || !parsedStake.isFinite() || parsedStake < 1.0
                        val targetInvalid = parsedTarget == null || !parsedTarget.isFinite() || parsedTarget < 1.01
                        val thresholdsInvalid = parsedLow == null || !parsedLow.isFinite() || parsedLow < 1.01 ||
                            parsedBoundary == null || !parsedBoundary.isFinite() || parsedBoundary < 1.01 ||
                            parsedBig == null || !parsedBig.isFinite() || parsedBig <= parsedLow
                        val scheduleInvalid = localConfig.scheduleEnabled && (startPair == null || stopPair == null)
                        val quietInvalid = localNotif.quietHoursEnabled &&
                            (quietStart == null || quietStart !in 0..23 || quietEnd == null || quietEnd !in 0..23)

                        if (stakeInvalid || targetInvalid || thresholdsInvalid || scheduleInvalid || quietInvalid) {
                            validationError = "Please check numeric inputs (Stake >= 1.0, Target >= 1.01, Big > Low, HH:MM)."
                            runCatching {
                                Toast.makeText(context, "Please check settings inputs", Toast.LENGTH_SHORT).show()
                            }
                            return@Button
                        }

                        validationError = null
                        val finalizedConfig = localConfig.copy(
                            baseStake = parsedStake!!,
                            targetMultiplier = parsedTarget!!,
                            lowThreshold = parsedLow!!,
                            boundaryValue = parsedBoundary!!,
                            bigThreshold = parsedBig!!,
                            strategy1LowThreshold = parsedLow,
                            strategy2HighThreshold = parsedBig,
                            scheduleStartHour = startPair?.first ?: localConfig.scheduleStartHour,
                            scheduleStartMinute = startPair?.second ?: localConfig.scheduleStartMinute,
                            scheduleEndHour = stopPair?.first ?: localConfig.scheduleEndHour,
                            scheduleEndMinute = stopPair?.second ?: localConfig.scheduleEndMinute
                        )
                        val finalizedNotif = localNotif.copy(
                            quietStartHour = quietStart ?: localNotif.quietStartHour,
                            quietEndHour = quietEnd ?: localNotif.quietEndHour
                        )
                        onSaveOverlaySettings(
                            finalizedConfig,
                            finalizedNotif,
                            opacityValue.coerceIn(0.3f, 1.0f),
                            bubbleSizeDp.coerceIn(44f, 80f),
                            hapticsEnabled
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("overlay_settings_save_btn")
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("SAVE & CLOSE", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun OverlaySettingsSectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    trailingBadge: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
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
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(icon, contentDescription = null, tint = PrimaryCobalt, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = PrimaryCobalt,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (trailingBadge != null) {
                    trailingBadge()
                }
            }
            content()
        }
    }
}

@Composable
private fun OverlayToggleRow(
    label: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

private fun parseOverlayHourMinute(raw: String): Pair<Int, Int>? {
    val parts = raw.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].trim().toIntOrNull() ?: return null
    val m = parts[1].trim().toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h to m
}
