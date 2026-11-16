package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.database.ClearHistoryScope
import com.example.data.engine.OperatingScheduleEvaluator
import com.example.data.model.AlarmProfileCategory
import com.example.data.model.AlarmProfileEntity
import com.example.data.model.NotificationSettings
import com.example.data.model.StrategyConfig
import com.example.service.AlarmAudioController
import com.example.service.AndroidSystemNotificationDispatcher
import com.example.service.ScreenEarnerOverlayService
import com.example.ui.theme.ActionStartEmerald
import com.example.ui.theme.ActionStopRed
import com.example.ui.theme.PrimaryCobalt
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsAppearanceScreen(
    strategyConfig: StrategyConfig? = null,
    notificationSettings: NotificationSettings? = null,
    isStandbyOutsideSchedule: Boolean = false,
    onStrategyConfigChanged: (StrategyConfig) -> Unit = {},
    onNotificationSettingsChanged: (NotificationSettings) -> Unit = {},
    onResetSessionStats: () -> Unit = {},
    onSaveAndReturn: (StrategyConfig) -> Unit = {},
    isAlarmRinging: Boolean = false,
    onStopAlarm: () -> Unit = { AlarmAudioController.stopAlarm() },
    onSnoozeAlarm: () -> Unit = { AlarmAudioController.snoozeAlarm(5) },
    onEndSessionAndClearData: (() -> Unit)? = null,
    alarmProfiles: List<AlarmProfileEntity> = AlarmProfileEntity.defaultProfiles(),
    onSaveAlarmProfile: (AlarmProfileEntity) -> Unit = {},
    isCpuAwakeHeld: Boolean = false,
    onCountClearScopeRecords: (suspend (ClearHistoryScope, String) -> Int)? = null,
    onClearHistoryByScope: ((ClearHistoryScope, String) -> Unit)? = null,
    selectedGameId: String = "unknown"
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(ScreenEarnerOverlayService.PREFS_NAME, Context.MODE_PRIVATE)
    }
    val scrollState = rememberScrollState()

    val isScreenHeld by ScreenEarnerOverlayService.isScreenHeldState.collectAsState()
    val liveCpuHeld by ScreenEarnerOverlayService.isCpuActiveState.collectAsState()
    val effectiveCpuHeld = isCpuAwakeHeld || liveCpuHeld
    val awakeError by ScreenEarnerOverlayService.awakeErrorState.collectAsState()
    val liveAudioStatus by AlarmAudioController.lastAudioStatus.collectAsState()
    val liveAlarmRinging by AlarmAudioController.isAlarmRinging.collectAsState()
    var showConfirmEndSessionDialog by remember { mutableStateOf(false) }

    val isIgnoringBatteryOpt = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else true
    }

    var opacityValue by remember {
        mutableStateOf(prefs.getFloat(ScreenEarnerOverlayService.PREF_BUBBLE_OPACITY, 0.95f))
    }
    var hapticFeedbackEnabled by remember {
        mutableStateOf(prefs.getBoolean(ScreenEarnerOverlayService.PREF_HAPTICS_ENABLED, true))
    }
    var bubbleSizeDp by remember {
        mutableStateOf(prefs.getFloat(ScreenEarnerOverlayService.PREF_BUBBLE_SIZE_DP, 56f))
    }

    val incomingConfig = strategyConfig ?: StrategyConfig()
    var localConfig by remember(incomingConfig) { mutableStateOf(incomingConfig) }

    var lowThreshText by remember(incomingConfig.lowThreshold) {
        mutableStateOf(String.format(Locale.US, "%.2f", incomingConfig.lowThreshold))
    }
    var boundaryText by remember(incomingConfig.boundaryValue) {
        mutableStateOf(String.format(Locale.US, "%.2f", incomingConfig.boundaryValue))
    }
    var bigThreshText by remember(incomingConfig.bigThreshold) {
        mutableStateOf(String.format(Locale.US, "%.2f", incomingConfig.bigThreshold))
    }

    var startTimeText by remember(incomingConfig.scheduleStartHour, incomingConfig.scheduleStartMinute) {
        mutableStateOf(
            String.format(Locale.US, "%02d:%02d", incomingConfig.scheduleStartHour, incomingConfig.scheduleStartMinute)
        )
    }
    var stopTimeText by remember(incomingConfig.scheduleEndHour, incomingConfig.scheduleEndMinute) {
        mutableStateOf(
            String.format(Locale.US, "%02d:%02d", incomingConfig.scheduleEndHour, incomingConfig.scheduleEndMinute)
        )
    }

    val incomingNotif = notificationSettings ?: NotificationSettings()
    var localNotifSettings by remember(incomingNotif) { mutableStateOf(incomingNotif) }
    var repeatIntervalText by remember(incomingNotif.repeatIntervalMinutes) {
        mutableStateOf(incomingNotif.repeatIntervalMinutes.toString())
    }
    var alarmRepeatIntervalText by remember(incomingNotif.alarmRepeatIntervalMinutes) {
        mutableStateOf(incomingNotif.alarmRepeatIntervalMinutes.toString())
    }
    var alarmMaxDurationText by remember(incomingNotif.alarmMaxDurationMinutes) {
        mutableStateOf(incomingNotif.alarmMaxDurationMinutes.toString())
    }
    var quietStartHourText by remember(incomingNotif.quietStartHour) {
        mutableStateOf(incomingNotif.quietStartHour.toString())
    }
    var quietEndHourText by remember(incomingNotif.quietEndHour) {
        mutableStateOf(incomingNotif.quietEndHour.toString())
    }

    var pickingProfileCategory by remember { mutableStateOf<AlarmProfileCategory?>(null) }

    fun applyConfigUpdate(updated: StrategyConfig) {
        localConfig = updated
        onStrategyConfigChanged(updated)
    }

    fun applyNotifUpdate(updated: NotificationSettings) {
        localNotifSettings = updated
        onNotificationSettingsChanged(updated)
    }

    fun resolvePickedSoundTitle(uri: Uri?): String {
        if (uri == null) return "No sound selected"
        return runCatching {
            RingtoneManager.getRingtone(context, uri)?.getTitle(context)?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: "Selected Audio"
    }

    val standardRingtonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val pickedUri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            applyNotifUpdate(localNotifSettings.copy(standardSoundUri = pickedUri?.toString().orEmpty()))
        }
    }

    val alarmRingtonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val pickedUri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            applyNotifUpdate(localNotifSettings.copy(alarmSoundUri = pickedUri?.toString().orEmpty()))
        }
    }

    val categoryRingtonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val targetCat = pickingProfileCategory
        pickingProfileCategory = null
        if (result.resultCode == Activity.RESULT_OK && targetCat != null) {
            @Suppress("DEPRECATION")
            val pickedUri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            val existing = alarmProfiles.firstOrNull { it.categoryName == targetCat.name }
                ?: AlarmProfileEntity.defaultProfiles().first { it.categoryName == targetCat.name }
            onSaveAlarmProfile(
                existing.copy(
                    soundUri = pickedUri?.toString().orEmpty(),
                    soundLabel = resolvePickedSoundTitle(pickedUri)
                )
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "SETTINGS & PERMISSIONS",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = PrimaryCobalt
        )

        // Permissions Compliance Component
        PermissionsScreen(scrollable = false)

        // Crash Category Thresholds Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_thresholds_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = "Thresholds", tint = PrimaryCobalt)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "STATISTICAL CATEGORY THRESHOLDS",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = PrimaryCobalt
                    )
                }
                Text(
                    text = "Low (< Low), Boundary (== Value), Medium (Boundary..Big), Big (>= Big).",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = lowThreshText,
                        onValueChange = { lowThreshText = it },
                        label = { Text("Low (< x)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_low_threshold_input")
                    )
                    OutlinedTextField(
                        value = boundaryText,
                        onValueChange = { boundaryText = it },
                        label = { Text("Boundary (==)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_boundary_input")
                    )
                    OutlinedTextField(
                        value = bigThreshText,
                        onValueChange = { bigThreshText = it },
                        label = { Text("Big (>= x)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_big_threshold_input")
                    )
                }
            }
        }

        // Crash Grid Dimensions Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_grid_size_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.GridView, contentDescription = "Grid Layout", tint = PrimaryCobalt)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "BOUNDED CRASH GRID SIZE (${localConfig.gridRows}×${localConfig.gridColumns})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = PrimaryCobalt
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(4, 5).forEach { r ->
                        FilterChip(
                            selected = localConfig.gridRows == r,
                            onClick = { applyConfigUpdate(localConfig.copy(gridRows = r)) },
                            label = { Text("$r Rows", fontWeight = FontWeight.Bold) },
                            modifier = Modifier.testTag("settings_grid_rows_$r")
                        )
                    }
                    listOf(7, 8).forEach { c ->
                        FilterChip(
                            selected = localConfig.gridColumns == c,
                            onClick = { applyConfigUpdate(localConfig.copy(gridColumns = c)) },
                            label = { Text("$c Cols", fontWeight = FontWeight.Bold) },
                            modifier = Modifier.testTag("settings_grid_cols_$c")
                        )
                    }
                }
            }
        }

        // Never-Sleep, Truthful Awake Telemetry, Battery Optimization & Operating Hours Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_awake_schedule_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BatteryChargingFull, contentDescription = "Never Sleep", tint = PrimaryCobalt)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "SCREEN AWAKE & OPERATING HOURS",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = PrimaryCobalt
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (isScreenHeld) ActionStartEmerald.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = if (isScreenHeld) "SCREEN HELD" else "NORMAL SLEEP",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isScreenHeld) ActionStartEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Real WakeLock & Screen Keep-On Telemetry
                Text(
                    text = "Hardware Awake State: Screen=${if (isScreenHeld) "ACTIVE (FLAG_KEEP_SCREEN_ON)" else "INACTIVE"} · CPU WakeLock=${if (effectiveCpuHeld) "HELD (PARTIAL_WAKE_LOCK)" else "RELEASED"}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isScreenHeld || effectiveCpuHeld) ActionStartEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("settings_awake_hardware_state")
                )

                StrategyToggleRow(
                    label = "Keep Screen Awake During Active Monitoring",
                    checked = localConfig.keepScreenAwake,
                    testTag = "settings_keep_awake_switch"
                ) { applyConfigUpdate(localConfig.copy(keepScreenAwake = it)) }

                if (!awakeError.isNullOrBlank()) {
                    Text(
                        text = awakeError!!,
                        color = ActionStopRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (isIgnoringBatteryOpt) {
                                "Battery Optimization: Unrestricted (Recommended for background foreground monitoring)."
                            } else {
                                "Battery Optimization: Restricted — Android may pause background services when the screen locks. Disable optimization for reliable monitoring."
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = {
                                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                } else {
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                }
                                runCatching { context.startActivity(intent) }
                            },
                            modifier = Modifier.testTag("settings_battery_opt_btn")
                        ) {
                            Text("Battery Optimization Settings", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                HorizontalDivider()

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, contentDescription = "Schedule", tint = PrimaryCobalt, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Scheduled Operating Hours (supports overnight e.g. 22:00–04:00)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                StrategyToggleRow(
                    label = "Enable Scheduled Operating Window",
                    checked = localConfig.scheduleEnabled,
                    testTag = "settings_schedule_switch"
                ) { applyConfigUpdate(localConfig.copy(scheduleEnabled = it)) }

                if (localConfig.scheduleEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = startTimeText,
                            onValueChange = { startTimeText = it },
                            label = { Text("Start Operating (HH:MM)") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_schedule_start_input")
                        )
                        OutlinedTextField(
                            value = stopTimeText,
                            onValueChange = { stopTimeText = it },
                            label = { Text("Stop Operating (HH:MM)") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_schedule_stop_input")
                        )
                    }
                    Surface(
                        color = if (isStandbyOutsideSchedule) Color(0xFFFEF3C7) else ActionStartEmerald.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val windowLabel = OperatingScheduleEvaluator.formatWindow(
                            localConfig.scheduleStartHour,
                            localConfig.scheduleStartMinute,
                            localConfig.scheduleEndHour,
                            localConfig.scheduleEndMinute
                        )
                        Text(
                            text = if (isStandbyOutsideSchedule) {
                                "Standby — Outside scheduled hours ($windowLabel)"
                            } else {
                                "Schedule Active ($windowLabel)"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isStandbyOutsideSchedule) Color(0xFF92400E) else ActionStartEmerald,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(
                        onClick = onResetSessionStats,
                        label = { Text("Reset Session Stats", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                        leadingIcon = {
                            Icon(Icons.Default.RestartAlt, contentDescription = "Reset Session Stats", modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.testTag("settings_reset_session_btn")
                    )
                    if (onEndSessionAndClearData != null) {
                        AssistChip(
                            onClick = { showConfirmEndSessionDialog = true },
                            label = {
                                Text(
                                    "End Session & Clear Data",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ActionStopRed
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.DeleteSweep,
                                    contentDescription = "End Session & Clear Data",
                                    tint = ActionStopRed,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier.testTag("settings_end_session_clear_btn")
                        )
                    }
                }
            }
        }

        // Notification Delivery Rules Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_notifications_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Notifications, contentDescription = "Notifications", tint = PrimaryCobalt)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "NOTIFICATION PREFERENCES",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = PrimaryCobalt
                    )
                }

                StrategyToggleRow(
                    label = "Master Pattern & System Alerts",
                    checked = localNotifSettings.masterEnabled,
                    testTag = "settings_notif_master_switch"
                ) { applyNotifUpdate(localNotifSettings.copy(masterEnabled = it)) }

                StrategyToggleRow(
                    label = "Approaching Alerts (1 round before)",
                    checked = localNotifSettings.approachingEnabled,
                    testTag = "settings_notif_approaching_switch"
                ) { applyNotifUpdate(localNotifSettings.copy(approachingEnabled = it)) }

                StrategyToggleRow(
                    label = "Pattern Detected Alerts",
                    checked = localNotifSettings.reachedEnabled,
                    testTag = "settings_notif_reached_switch"
                ) { applyNotifUpdate(localNotifSettings.copy(reachedEnabled = it)) }

                StrategyToggleRow(
                    label = "Hourly Transition Summaries",
                    checked = localNotifSettings.hourlySummaryEnabled,
                    testTag = "settings_notif_hourly_switch"
                ) { applyNotifUpdate(localNotifSettings.copy(hourlySummaryEnabled = it)) }

                StrategyToggleRow(
                    label = "Notification Sound",
                    checked = localNotifSettings.soundEnabled,
                    testTag = "settings_notif_sound_switch"
                ) { applyNotifUpdate(localNotifSettings.copy(soundEnabled = it)) }

                StrategyToggleRow(
                    label = "Notification Vibration",
                    checked = localNotifSettings.vibrationEnabled,
                    testTag = "settings_notif_vibration_switch"
                ) { applyNotifUpdate(localNotifSettings.copy(vibrationEnabled = it)) }

                OutlinedTextField(
                    value = repeatIntervalText,
                    onValueChange = { repeatIntervalText = it },
                    label = { Text("Minimum Repeat Cooldown (minutes, 0 = alert every match)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_notif_repeat_input")
                )

                StrategyToggleRow(
                    label = "Quiet Hours",
                    checked = localNotifSettings.quietHoursEnabled,
                    testTag = "settings_notif_quiet_switch"
                ) { applyNotifUpdate(localNotifSettings.copy(quietHoursEnabled = it)) }

                if (localNotifSettings.quietHoursEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = quietStartHourText,
                            onValueChange = { quietStartHourText = it },
                            label = { Text("Quiet Start (0-23h)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_notif_quiet_start_hour")
                        )
                        OutlinedTextField(
                            value = quietEndHourText,
                            onValueChange = { quietEndHourText = it },
                            label = { Text("Quiet End (0-23h)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_notif_quiet_end_hour")
                        )
                    }
                }
            }
        }

        // Dedicated Section: ALARM & NOTIFICATION PROFILES (Manual Sound Selection Only)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_alarm_profiles_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Alarm, contentDescription = "Alarm Profiles", tint = PrimaryCobalt)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ALARM & NOTIFICATION PROFILES",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = PrimaryCobalt
                    )
                }

                // A. Standard Alert Profile
                Text(
                    text = "A. Standard Alert",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Used for game detected, confirmed crash recorded, window unavailable, and normal updates.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                StrategyToggleRow(
                    label = "Enable Standard Alerts",
                    checked = localNotifSettings.standardAlertEnabled,
                    testTag = "settings_standard_alert_switch"
                ) { applyNotifUpdate(localNotifSettings.copy(standardAlertEnabled = it)) }

                Spacer(modifier = Modifier.height(4.dp))
                Text("Sound Source:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PrimaryCobalt)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = localNotifSettings.soundSourceType == "SYSTEM",
                        onClick = { applyNotifUpdate(localNotifSettings.copy(soundSourceType = "SYSTEM")) },
                        label = { Text("System Sounds", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    )
                    FilterChip(
                        selected = localNotifSettings.soundSourceType == "LOCAL_AUDIO",
                        onClick = { applyNotifUpdate(localNotifSettings.copy(soundSourceType = "LOCAL_AUDIO")) },
                        label = { Text("Local Audio Tones", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    )
                }

                Text("Delivery Mode:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PrimaryCobalt)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = localNotifSettings.deliveryMode == "ALARM",
                        onClick = { applyNotifUpdate(localNotifSettings.copy(deliveryMode = "ALARM")) },
                        label = { Text("Alarm Stream", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    )
                    FilterChip(
                        selected = localNotifSettings.deliveryMode == "NORMAL",
                        onClick = { applyNotifUpdate(localNotifSettings.copy(deliveryMode = "NORMAL")) },
                        label = { Text("Normal Notification", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val soundLabel = if (localNotifSettings.standardSoundUri.isBlank()) {
                        "No sound selected"
                    } else {
                        "Selected: ${resolvePickedSoundTitle(Uri.parse(localNotifSettings.standardSoundUri))}"
                    }
                    Text(
                        text = "Sound: $soundLabel",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (localNotifSettings.standardSoundUri.isNotBlank()) {
                        OutlinedButton(
                            onClick = { applyNotifUpdate(localNotifSettings.copy(standardSoundUri = "")) },
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear Sound", modifier = Modifier.size(14.dp))
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val pickerIntent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                                val current = localNotifSettings.standardSoundUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
                            }
                            runCatching { standardRingtonePickerLauncher.launch(pickerIntent) }
                        },
                        modifier = Modifier.testTag("settings_standard_sound_picker_btn")
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Select Sound", fontSize = 11.sp)
                    }
                }

                Text("Standard Vibration Pattern:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("DEFAULT", "SHORT_PULSE", "DISTINCTIVE", "LONG_CONTINUOUS", "NONE").forEach { pattern ->
                        FilterChip(
                            selected = localNotifSettings.standardVibrationPattern == pattern,
                            onClick = { applyNotifUpdate(localNotifSettings.copy(standardVibrationPattern = pattern)) },
                            label = { Text(pattern.replace('_', ' '), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        )
                    }
                }

                StrategyToggleRow(
                    label = "Enable Repeated Standard Alerts",
                    checked = localNotifSettings.standardRepeatedAlerts,
                    testTag = "settings_standard_repeated_switch"
                ) { applyNotifUpdate(localNotifSettings.copy(standardRepeatedAlerts = it)) }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { AlarmAudioController.testStandardSound(context, localNotifSettings) },
                        modifier = Modifier.testTag("settings_test_standard_sound_btn")
                    ) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Preview Sound", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { AlarmAudioController.testVibration(context, localNotifSettings.standardVibrationPattern) },
                        modifier = Modifier.testTag("settings_test_standard_vib_btn")
                    ) {
                        Icon(Icons.Default.Vibration, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test Vibration", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val chId = AndroidSystemNotificationDispatcher.resolveVersionedChannelId(
                                AndroidSystemNotificationDispatcher.CHANNEL_STANDARD,
                                localNotifSettings
                            )
                            AlarmAudioController.openChannelSettings(context, chId)
                        },
                        modifier = Modifier.testTag("settings_standard_channel_btn")
                    ) {
                        Text("Channel Settings", fontSize = 11.sp)
                    }
                }

                HorizontalDivider()

                // B. Pattern Match Signal Alarm Profile
                Text(
                    text = "B. Signal Alarm (Pattern Match)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = ActionStopRed
                )
                Text(
                    text = "Triggered on PATTERN MATCH / SIGNAL DETECTED. No sound is auto-assigned unless chosen manually.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                StrategyToggleRow(
                    label = "Enable Signal Alarm",
                    checked = localNotifSettings.highPriorityAlarmEnabled,
                    testTag = "settings_signal_alarm_switch"
                ) { applyNotifUpdate(localNotifSettings.copy(highPriorityAlarmEnabled = it)) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val alarmToneLabel = if (localNotifSettings.alarmSoundUri.isBlank()) {
                        "No sound selected"
                    } else {
                        "Selected: ${resolvePickedSoundTitle(Uri.parse(localNotifSettings.alarmSoundUri))}"
                    }
                    Text(
                        text = "Alarm Sound: $alarmToneLabel",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (localNotifSettings.alarmSoundUri.isNotBlank()) {
                        OutlinedButton(
                            onClick = { applyNotifUpdate(localNotifSettings.copy(alarmSoundUri = "")) },
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear Alarm Sound", modifier = Modifier.size(14.dp))
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val pickerIntent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                                val current = localNotifSettings.alarmSoundUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
                            }
                            runCatching { alarmRingtonePickerLauncher.launch(pickerIntent) }
                        },
                        modifier = Modifier.testTag("settings_alarm_sound_picker_btn")
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Alarm Tone", fontSize = 11.sp)
                    }
                }

                Text("Alarm Vibration Pattern:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("DISTINCTIVE", "LONG_CONTINUOUS", "DEFAULT", "SHORT_PULSE", "NONE").forEach { pattern ->
                        FilterChip(
                            selected = localNotifSettings.alarmVibrationPattern == pattern,
                            onClick = { applyNotifUpdate(localNotifSettings.copy(alarmVibrationPattern = pattern)) },
                            label = { Text(pattern.replace('_', ' '), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        )
                    }
                }

                StrategyToggleRow(
                    label = "Repeat Signal Alarm Until Stopped",
                    checked = localNotifSettings.alarmRepeated,
                    testTag = "settings_alarm_repeated_switch"
                ) { applyNotifUpdate(localNotifSettings.copy(alarmRepeated = it)) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = alarmRepeatIntervalText,
                        onValueChange = { alarmRepeatIntervalText = it },
                        label = { Text("Repeat Cooldown (min, 0=every match)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_alarm_repeat_interval_input")
                    )
                    OutlinedTextField(
                        value = alarmMaxDurationText,
                        onValueChange = { alarmMaxDurationText = it },
                        label = { Text("Max Duration (min)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_alarm_max_duration_input")
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { AlarmAudioController.testSignalAlarm(context, localNotifSettings) },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryCobalt, contentColor = Color.White),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("settings_test_alarm_btn")
                    ) {
                        Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("TEST ALARM", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                    Button(
                        onClick = onStopAlarm,
                        enabled = isAlarmRinging || liveAlarmRinging,
                        colors = ButtonDefaults.buttonColors(containerColor = ActionStopRed, contentColor = Color.White),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("settings_stop_alarm_btn")
                    ) {
                        Icon(Icons.Default.NotificationsOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("STOP ALARM", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onSnoozeAlarm,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("settings_snooze_alarm_btn")
                    ) {
                        Icon(Icons.Default.Snooze, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("SNOOZE 5M", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val chId = AndroidSystemNotificationDispatcher.resolveVersionedChannelId(
                                AndroidSystemNotificationDispatcher.CHANNEL_ALARM,
                                localNotifSettings
                            )
                            AlarmAudioController.openChannelSettings(context, chId)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("settings_alarm_channel_btn")
                    ) {
                        Text("ALARM CHANNEL", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }

                if (!liveAudioStatus.isNullOrBlank()) {
                    Text(
                        text = liveAudioStatus!!,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PrimaryCobalt
                    )
                }

                HorizontalDivider()

                // C. All 5 Independent Strategy Category Alarm Profiles
                Text(
                    text = "PER-CATEGORY ALARM PROFILES (5 INDEPENDENT PROFILES)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = PrimaryCobalt
                )
                Text(
                    text = "Configure independent sounds, previews, vibration patterns, repeat behavior, and cooldowns for each alert category.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val profileByCat = alarmProfiles.associateBy { it.categoryName }
                AlarmProfileCategory.entries.forEach { cat ->
                    val profile = profileByCat[cat.name]
                        ?: AlarmProfileEntity.defaultProfiles().first { it.categoryName == cat.name }
                    CategoryAlarmProfileCard(
                        category = cat,
                        profile = profile,
                        onUpdateProfile = onSaveAlarmProfile,
                        onPickSound = {
                            pickingProfileCategory = cat
                            val pickerIntent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                val rType = if (cat.defaultUseAlarmStyle) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, rType)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                                val current = profile.soundUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
                            }
                            runCatching { categoryRingtonePickerLauncher.launch(pickerIntent) }
                        },
                        onClearSound = {
                            onSaveAlarmProfile(profile.copy(soundUri = "", soundLabel = "No sound selected"))
                        },
                        onPreviewSound = {
                            AlarmAudioController.previewSoundUri(context, profile.soundUri, asAlarmStream = profile.useAlarmStyle)
                        },
                        onTestProfile = {
                            AlarmAudioController.testAlarmProfile(context, profile)
                        }
                    )
                }

                Text(
                    text = "Android channel settings may retain prior sound/vibration choices after creation. Alarms respect Do Not Disturb, device alarm volume, and notification permissions.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Floating Utility Appearance Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Palette, contentDescription = "Appearance", tint = PrimaryCobalt)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "FLOATING OVERLAY APPEARANCE",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = PrimaryCobalt
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                Text("Floating Bubble Opacity (${(opacityValue * 100).toInt()}%)", fontSize = 13.sp)
                Slider(
                    value = opacityValue,
                    onValueChange = { opacityValue = it },
                    valueRange = 0.3f..1.0f,
                    modifier = Modifier.testTag("opacity_slider")
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text("Floating Bubble Size (${bubbleSizeDp.toInt()}dp)", fontSize = 13.sp)
                Slider(
                    value = bubbleSizeDp,
                    onValueChange = { bubbleSizeDp = it },
                    valueRange = 40f..80f,
                    modifier = Modifier.testTag("bubble_size_slider")
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Vibration, contentDescription = "Haptics", tint = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Haptic Feedback", fontSize = 13.sp)
                    }
                    Switch(
                        checked = hapticFeedbackEnabled,
                        onCheckedChange = { hapticFeedbackEnabled = it },
                        modifier = Modifier.testTag("haptics_switch")
                    )
                }
            }
        }

        // Optional 3-Scope Clear History Shortcut Card
        if (onClearHistoryByScope != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DATA CLEANUP", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = ActionStopRed)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { onClearHistoryByScope(ClearHistoryScope.CURRENT_SESSION, selectedGameId) }
                        ) {
                            Text("Clear Current Session", fontSize = 11.sp, color = ActionStopRed)
                        }
                        OutlinedButton(
                            onClick = { onClearHistoryByScope(ClearHistoryScope.SELECTED_GAME_ARCHIVED, selectedGameId) }
                        ) {
                            Text("Clear Selected Game Archive", fontSize = 11.sp, color = ActionStopRed)
                        }
                        OutlinedButton(
                            onClick = { onClearHistoryByScope(ClearHistoryScope.ALL_HISTORY, selectedGameId) }
                        ) {
                            Text("Clear All History", fontSize = 11.sp, color = ActionStopRed)
                        }
                    }
                }
            }
        }

        var isSaving by remember { mutableStateOf(false) }
        Button(
            enabled = !isSaving,
            onClick = {
                if (isSaving) return@Button
                try {
                    val safeOpacity = opacityValue.takeIf { it.isFinite() }?.coerceIn(0.3f, 1.0f) ?: 0.95f
                    val safeBubbleSize = bubbleSizeDp.takeIf { it.isFinite() }?.coerceIn(40f, 80f) ?: 56f

                    val parsedLow = lowThreshText.trim().toDoubleOrNull()
                    val parsedBoundary = boundaryText.trim().toDoubleOrNull()
                    val parsedBig = bigThreshText.trim().toDoubleOrNull()

                    val parsedRepeat = repeatIntervalText.trim().toIntOrNull()
                    val parsedAlarmRepeat = alarmRepeatIntervalText.trim().toIntOrNull()
                    val parsedAlarmDuration = alarmMaxDurationText.trim().toIntOrNull()
                    val parsedQuietStart = quietStartHourText.trim().toIntOrNull()
                    val parsedQuietEnd = quietEndHourText.trim().toIntOrNull()

                    val startPair = parseHourMinute(startTimeText)
                    val stopPair = parseHourMinute(stopTimeText)

                    val thresholdsInvalid = parsedLow == null || !parsedLow.isFinite() || parsedLow < 1.01 ||
                        parsedBoundary == null || !parsedBoundary.isFinite() || parsedBoundary < 1.01 ||
                        parsedBig == null || !parsedBig.isFinite() || parsedBig <= parsedLow

                    val scheduleInvalid = localConfig.scheduleEnabled && (startPair == null || stopPair == null)
                    val notifInvalid = (parsedRepeat == null || parsedRepeat < 0) ||
                        (parsedAlarmRepeat == null || parsedAlarmRepeat < 0) ||
                        (parsedAlarmDuration == null || parsedAlarmDuration !in 1..30) ||
                        (localNotifSettings.quietHoursEnabled &&
                            (parsedQuietStart == null || parsedQuietStart !in 0..23 ||
                                parsedQuietEnd == null || parsedQuietEnd !in 0..23))

                    if (thresholdsInvalid || scheduleInvalid || notifInvalid) {
                        Toast.makeText(
                            context,
                            "Failed to save settings, please check input",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@Button
                    }

                    isSaving = true
                    prefs.edit()
                        .putFloat(ScreenEarnerOverlayService.PREF_BUBBLE_OPACITY, safeOpacity)
                        .putFloat(ScreenEarnerOverlayService.PREF_BUBBLE_SIZE_DP, safeBubbleSize)
                        .putBoolean(ScreenEarnerOverlayService.PREF_HAPTICS_ENABLED, hapticFeedbackEnabled)
                        .apply()

                    val finalizedConfig = localConfig.copy(
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

                    val finalizedNotif = localNotifSettings.copy(
                        repeatIntervalMinutes = parsedRepeat!!,
                        alarmRepeatIntervalMinutes = parsedAlarmRepeat!!,
                        alarmMaxDurationMinutes = parsedAlarmDuration!!,
                        quietStartHour = parsedQuietStart ?: localNotifSettings.quietStartHour,
                        quietEndHour = parsedQuietEnd ?: localNotifSettings.quietEndHour
                    )

                    onNotificationSettingsChanged(finalizedNotif)
                    onSaveAndReturn(finalizedConfig)
                } catch (e: Exception) {
                    isSaving = false
                    Log.e("SettingsAppearance", "Save failed", e)
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
                .testTag("save_appearance_settings_btn")
        ) {
            Icon(Icons.Default.Save, contentDescription = "Save Settings")
            Spacer(modifier = Modifier.width(8.dp))
            Text("SAVE & CLOSE", fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(80.dp))
    }

    if (showConfirmEndSessionDialog && onEndSessionAndClearData != null) {
        BackHandler { showConfirmEndSessionDialog = false }
        AlertDialog(
            onDismissRequest = { showConfirmEndSessionDialog = false },
            title = { Text("End Session & Clear Session Data?", fontWeight = FontWeight.Bold) },
            text = {
                Text("This stops monitoring, resets session crashes to 0, clears the current round indicator, stops any active alarm, and removes records logged during this session.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmEndSessionDialog = false
                        onEndSessionAndClearData()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ActionStopRed, contentColor = Color.White)
                ) {
                    Text("END & CLEAR", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmEndSessionDialog = false }) {
                    Text("CANCEL")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryAlarmProfileCard(
    category: AlarmProfileCategory,
    profile: AlarmProfileEntity,
    onUpdateProfile: (AlarmProfileEntity) -> Unit,
    onPickSound: () -> Unit,
    onClearSound: () -> Unit,
    onPreviewSound: () -> Unit,
    onTestProfile: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("alarm_profile_card_${category.name}")
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(category.displayTitle, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(category.subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = profile.enabled,
                    onCheckedChange = { onUpdateProfile(profile.copy(enabled = it)) },
                    modifier = Modifier.testTag("alarm_profile_enable_${category.name}")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sound: ${if (profile.soundUri.isBlank()) "No sound selected" else profile.soundLabel}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (profile.soundUri.isNotBlank()) {
                    OutlinedButton(onClick = onClearSound, modifier = Modifier.padding(end = 4.dp)) {
                        Text("Clear", fontSize = 10.sp)
                    }
                }
                OutlinedButton(
                    onClick = onPickSound,
                    modifier = Modifier.testTag("alarm_profile_pick_${category.name}")
                ) {
                    Text("Choose", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = onPreviewSound,
                    modifier = Modifier.testTag("alarm_profile_preview_${category.name}")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Preview", fontSize = 10.sp)
                }
                OutlinedButton(
                    onClick = onTestProfile,
                    modifier = Modifier.testTag("alarm_profile_test_${category.name}")
                ) {
                    Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Test Playback", fontSize = 10.sp)
                }
                FilterChip(
                    selected = profile.vibrationEnabled,
                    onClick = { onUpdateProfile(profile.copy(vibrationEnabled = !profile.vibrationEnabled)) },
                    label = { Text(if (profile.vibrationEnabled) "Vibrate ON" else "Vibrate OFF", fontSize = 10.sp) }
                )
                FilterChip(
                    selected = profile.repeatEnabled,
                    onClick = { onUpdateProfile(profile.copy(repeatEnabled = !profile.repeatEnabled)) },
                    label = { Text(if (profile.repeatEnabled) "Repeat ON" else "Once", fontSize = 10.sp) }
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("DEFAULT", "SHORT_PULSE", "DISTINCTIVE", "LONG_CONTINUOUS", "NONE").forEach { pattern ->
                    FilterChip(
                        selected = profile.vibrationPattern == pattern,
                        onClick = { onUpdateProfile(profile.copy(vibrationPattern = pattern)) },
                        label = { Text(pattern.replace('_', ' '), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                    )
                }
            }
        }
    }
}

private fun parseHourMinute(raw: String): Pair<Int, Int>? {
    val parts = raw.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].trim().toIntOrNull() ?: return null
    val m = parts[1].trim().toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h to m
}
