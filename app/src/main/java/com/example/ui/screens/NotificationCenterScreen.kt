package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Snooze
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.data.engine.MultiplierHistoryAnalyzer
import com.example.data.model.NotificationDeliveryStatus
import com.example.data.model.NotificationHistoryRecord
import com.example.data.model.NotificationSettings
import com.example.service.AlarmAudioController
import com.example.service.AndroidSystemNotificationDispatcher
import com.example.ui.theme.ActionStartEmerald
import com.example.ui.theme.ActionStopRed
import com.example.ui.theme.PrimaryCobalt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotificationCenterScreen(
    notificationSettings: NotificationSettings?,
    notificationHistory: List<NotificationHistoryRecord>,
    onUpdateSettings: (NotificationSettings) -> Unit,
    onClearHistory: () -> Unit,
    onOpenRecordTarget: (NotificationHistoryRecord) -> Unit
) {
    val context = LocalContext.current
    val settings = notificationSettings ?: NotificationSettings()
    var selectedChannelFilter by remember { mutableStateOf("ALL") }

    var hasNotifPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val isAlarmRinging by AlarmAudioController.isAlarmRinging.collectAsState()
    val activeAlarmTitle by AlarmAudioController.activeAlarmTitle.collectAsState()

    val hasActivityRegistry = LocalActivityResultRegistryOwner.current != null
    val permLauncher = if (hasActivityRegistry) {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
            hasNotifPermission = granted
        }
    } else null

    val filteredHistory = remember(notificationHistory, selectedChannelFilter) {
        if (selectedChannelFilter == "ALL") {
            notificationHistory
        } else {
            notificationHistory.filter {
                it.channelId == selectedChannelFilter || it.channelId.startsWith("${selectedChannelFilter}_v")
            }
        }
    }

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
                    text = "NOTIFICATION CENTER",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryCobalt
                )
                OutlinedButton(onClick = onClearHistory) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear", tint = ActionStopRed, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear", fontSize = 11.sp, color = ActionStopRed)
                }
            }
        }

        if (isAlarmRinging) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("notif_center_active_alarm_card"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEE2E2)),
                    border = BorderStroke(1.dp, ActionStopRed)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Alarm, contentDescription = "Active Alarm", tint = ActionStopRed)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = activeAlarmTitle ?: "SIGNAL DETECTED — PATTERN MATCH",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp,
                                color = ActionStopRed
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { AlarmAudioController.stopAlarm(context) },
                                colors = ButtonDefaults.buttonColors(containerColor = ActionStopRed, contentColor = Color.White),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Icon(Icons.Default.NotificationsOff, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("STOP ALARM", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { AlarmAudioController.snoozeAlarm(5) },
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Icon(Icons.Default.Snooze, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("SNOOZE 5M", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Android 13+ POST_NOTIFICATIONS Permission Banner
        if (!hasNotifPermission) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("notif_permission_denied_banner"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEE2E2)),
                    border = BorderStroke(1.dp, ActionStopRed)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = "Denied", tint = ActionStopRed)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "System Notification Permission Required",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = ActionStopRed
                            )
                        }
                        Text(
                            text = "Android blocked notifications (POST_NOTIFICATIONS denied). Alerts are logged below until notification permission is granted.",
                            fontSize = 12.sp,
                            color = Color(0xFF7F1D1D)
                        )
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && permLauncher != null) {
                                    permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                        )
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ActionStopRed, contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("GRANT NOTIFICATION PERMISSION", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Notification Controls & Channels Card
        item {
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Master System Alerts", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Delivers alerts outside the app via 4 Android channels", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = settings.masterEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(masterEnabled = it)) },
                            modifier = Modifier.testTag("notif_center_master_switch")
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sound & Vibration", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Switch(
                            checked = settings.soundEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(soundEnabled = it, vibrationEnabled = it)) }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bet & Cash-Out Execution Alerts", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Notify on automated stake entry, bet placement & cash-out", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = settings.executionAlertsEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(executionAlertsEnabled = it)) },
                            modifier = Modifier.testTag("notif_execution_alerts_switch")
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Failure & History Unavailable Alerts", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Alert if Accessibility disconnects or ribbon history stops updating", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = settings.failureAlertsEnabled,
                            onCheckedChange = { onUpdateSettings(settings.copy(failureAlertsEnabled = it)) },
                            modifier = Modifier.testTag("notif_failure_alerts_switch")
                        )
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = "Channels", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("MANAGE ANDROID NOTIFICATION CHANNELS", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Channel Filters
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "ALL" to "All",
                    AndroidSystemNotificationDispatcher.CHANNEL_ALARM to "Signal Alarm",
                    AndroidSystemNotificationDispatcher.CHANNEL_STANDARD to "Standard Alerts",
                    AndroidSystemNotificationDispatcher.CHANNEL_HOURLY to "Hourly",
                    AndroidSystemNotificationDispatcher.CHANNEL_SERVICE to "Service"
                ).forEach { (id, label) ->
                    FilterChip(
                        selected = selectedChannelFilter == id,
                        onClick = { selectedChannelFilter = id },
                        label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    )
                }
            }
        }

        if (filteredHistory.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Text(
                        text = "No notifications recorded in this channel yet. Triggered pattern alerts, hour transitions, and system notices appear here.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(filteredHistory.take(60), key = { it.id }) { item ->
                val isDelivered = item.deliveryStatus == NotificationDeliveryStatus.DELIVERED
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        if (isDelivered) ActionStartEmerald.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRecordTarget(item) }
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                color = if (isDelivered) ActionStartEmerald.copy(alpha = 0.14f) else Color(0xFFFEF3C7),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = item.deliveryStatus.name,
                                    color = if (isDelivered) ActionStartEmerald else Color(0xFF92400E),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(item.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val meta = buildString {
                            append(com.example.data.model.CalendarHourUtils.formatDateTime(item.timestampMs))
                            item.gameId?.let { append(" · Game: $it") }
                            item.occurrenceRecordId?.let { append(" · Occurrence #$it") }
                        }
                        Text(meta, fontSize = 10.sp, color = PrimaryCobalt, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}
