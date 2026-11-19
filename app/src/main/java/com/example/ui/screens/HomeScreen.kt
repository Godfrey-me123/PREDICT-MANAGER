package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainActivity
import com.example.data.engine.AutomationState
import com.example.data.engine.OperatingScheduleEvaluator
import com.example.data.engine.SmartAgentExplanation
import com.example.data.model.CrashRecord
import com.example.data.model.FloatingShortcut
import com.example.data.model.GameState
import com.example.data.model.MonitoringStatus
import com.example.data.model.StrategyConfig
import com.example.service.AccessibilityConnectionStatus
import com.example.service.ScreenEarnerAccessibilityService
import com.example.service.ScreenEarnerOverlayService
import com.example.ui.components.BoundedCrashHistoryGrid
import com.example.ui.components.LiveCrashHeaderCard
import com.example.ui.theme.ActionStartEmerald
import com.example.ui.theme.ActionStopRed
import com.example.ui.theme.PrimaryCobalt

@Composable
fun HomeScreen(
    gameState: GameState,
    automationState: AutomationState,
    isOverlayServiceRunning: Boolean,
    strategyConfig: StrategyConfig?,
    crashRecords: List<CrashRecord> = emptyList(),
    smartAgentExplanation: SmartAgentExplanation = SmartAgentExplanation(
        gameName = "",
        liveStatusLine = "Awaiting game detection...",
        sampleConfidenceLabel = "INSUFFICIENT DATA",
        windowComparisonText = "",
        strategyBreakdowns = emptyList()
    ),
    isStandbyOutsideSchedule: Boolean = false,
    onToggleOverlayService: () -> Unit,
    onStartMonitoring: () -> Unit,
    onPauseMonitoring: () -> Unit,
    onNavigateToTab: (Int) -> Unit,
    monitoringStatus: MonitoringStatus = gameState.monitoringStatus,
    isAlarmRinging: Boolean = false,
    activeAlarmTitle: String? = null,
    onStopAlarm: () -> Unit = {},
    onSnoozeAlarm: () -> Unit = {},
    onEndSessionAndClearData: () -> Unit = {},
    onToggleAutoBet: (Boolean) -> Unit = {},
    betShortcut: FloatingShortcut? = null,
    cashOutShortcut: FloatingShortcut? = null,
    onToggleBetHand: () -> Unit = {},
    onToggleCashOutHand: () -> Unit = {},
    queuedOpportunitiesCount: Int = 0,
    onStartAutomationAssistant: (() -> Unit)? = null,
    onPauseAutomationAssistant: (() -> Unit)? = null,
    onStopAutomationAssistant: (() -> Unit)? = null,
    onOpenOverlaySettings: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val hasActivityRegistry = LocalActivityResultRegistryOwner.current != null

    val isDrawOverAppsGranted = Settings.canDrawOverlays(context)
    val a11yStatus by ScreenEarnerAccessibilityService.connectionStatus.collectAsState()
    val isScreenHeld by ScreenEarnerOverlayService.isScreenHeldState.collectAsState()
    val config = strategyConfig ?: StrategyConfig()
    val isMonitoringRunning = monitoringStatus == MonitoringStatus.MONITORING ||
        monitoringStatus == MonitoringStatus.WAITING_FOR_GAME ||
        monitoringStatus == MonitoringStatus.GAME_NOT_ACCESSIBLE ||
        monitoringStatus == MonitoringStatus.GAME_SUSPENDED
    var showConfirmClearDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
        // Brand Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(PrimaryCobalt, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "PM",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "PREDICT MANAGER",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp,
                        letterSpacing = 0.6.sp
                    )
                    Text(
                        text = "Live Game Observation & Statistical Pattern Analyzer",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }
        }

        if (isAlarmRinging) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_active_alarm_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEE2E2)),
                border = BorderStroke(1.dp, ActionStopRed)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = activeAlarmTitle ?: "SIGNAL DETECTED — PATTERN MATCH",
                        color = ActionStopRed,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = onStopAlarm,
                            colors = ButtonDefaults.buttonColors(containerColor = ActionStopRed, contentColor = Color.White),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Icon(Icons.Default.NotificationsOff, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("STOP ALARM", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = onSnoozeAlarm,
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

        // Monitoring State & Schedule / Keep-Awake Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LiveCrashHeaderCard(
                    gameState = gameState.copy(monitoringStatus = monitoringStatus),
                    automationState = automationState
                )

                val scheduleSummary = if (config.scheduleEnabled) {
                    val label = OperatingScheduleEvaluator.formatWindow(
                        config.scheduleStartHour,
                        config.scheduleStartMinute,
                        config.scheduleEndHour,
                        config.scheduleEndMinute
                    )
                    if (isStandbyOutsideSchedule) "Standby — Outside scheduled hours ($label)" else "Schedule Active ($label)"
                } else {
                    "Schedule: 24/7 Continuous Observation"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = if (isScreenHeld) {
                            ActionStartEmerald.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = when {
                                isScreenHeld -> "Keep Awake: ON"
                                config.keepScreenAwake -> "Keep Awake: STANDBY"
                                else -> "Keep Awake: OFF"
                            },
                            color = if (isScreenHeld) ActionStartEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                    Surface(
                        color = if (isStandbyOutsideSchedule) {
                            Color(0xFFFEF3C7)
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1.4f)
                    ) {
                        Text(
                            text = scheduleSummary,
                            color = if (isStandbyOutsideSchedule) Color(0xFF92400E) else MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { if (isMonitoringRunning) onPauseMonitoring() else onStartMonitoring() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isMonitoringRunning) ActionStopRed else ActionStartEmerald,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("home_toggle_monitoring_btn")
                    ) {
                        Icon(
                            imageVector = if (isMonitoringRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Toggle Monitoring"
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isMonitoringRunning) "PAUSE MONITOR" else "START MONITOR",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            if (!isOverlayServiceRunning) {
                                onToggleOverlayService()
                            } else {
                                (context as? Activity)?.moveTaskToBack(true)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("launch_floating_se_hero_btn")
                    ) {
                        Icon(Icons.Default.RocketLaunch, contentDescription = "Floating Monitor", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isOverlayServiceRunning) "GO TO GAME" else "LAUNCH OVERLAY",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }

                if (isMonitoringRunning || crashRecords.isNotEmpty() || gameState.sessionCrashesRecorded > 0) {
                    OutlinedButton(
                        onClick = { showConfirmClearDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("home_end_session_btn")
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "End Session", tint = ActionStopRed)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "END SESSION & CLEAR SESSION DATA",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = ActionStopRed
                        )
                    }
                }
            }
        }

        ExecutionHandsControlCard(
            isAutoBetEnabled = config.isAutoBetEnabled,
            betShortcut = betShortcut,
            cashOutShortcut = cashOutShortcut,
            onToggleAutoBet = onToggleAutoBet,
            onToggleBetHand = onToggleBetHand,
            onToggleCashOutHand = onToggleCashOutHand
        )

        // Master Live Automation Assistant Card (Independent from Monitoring Engine)
        Card(
            modifier = Modifier.fillMaxWidth().testTag("master_automation_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (config.isAutoBetEnabled && automationState == AutomationState.RUNNING)
                    ActionStartEmerald.copy(alpha = 0.1f)
                else
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            border = BorderStroke(
                1.dp,
                if (config.isAutoBetEnabled && automationState == AutomationState.RUNNING) ActionStartEmerald else MaterialTheme.colorScheme.outlineVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (config.isAutoBetEnabled) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = null,
                                tint = if (config.isAutoBetEnabled) ActionStartEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (config.isAutoBetEnabled) {
                                    "Automation Assistant: ${automationState.name} (Queue: $queuedOpportunitiesCount)"
                                } else {
                                    "Live Automation: PAUSED (Recording Only)"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (config.isAutoBetEnabled) ActionStartEmerald else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (config.isAutoBetEnabled)
                                "Consumes confirmed strategy triggers for stake entry & Cash Out without interrupting monitoring."
                            else
                                "App monitors multipliers, crash history, and alarms normally without editing fields or placing bets.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = config.isAutoBetEnabled,
                        onCheckedChange = { onToggleAutoBet(it) },
                        modifier = Modifier.testTag("master_automation_switch")
                    )
                }

                if (onStartAutomationAssistant != null || onPauseAutomationAssistant != null || onStopAutomationAssistant != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (onStartAutomationAssistant != null) {
                            Button(
                                onClick = onStartAutomationAssistant,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ActionStartEmerald,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("home_start_automation_btn")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Start Automation", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("AUTO ON", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (onPauseAutomationAssistant != null) {
                            OutlinedButton(
                                onClick = onPauseAutomationAssistant,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("home_pause_automation_btn")
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = "Pause Automation", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("PAUSE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (onStopAutomationAssistant != null) {
                            OutlinedButton(
                                onClick = onStopAutomationAssistant,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("home_stop_automation_btn")
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop Automation", tint = ActionStopRed, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("STOP", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ActionStopRed)
                            }
                        }
                    }
                }
            }
        }

        // System Services Readiness
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "LIVE CONNECTION TELEMETRY",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = PrimaryCobalt
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            Icons.Default.AccessibilityNew,
                            contentDescription = "Accessibility",
                            tint = if (a11yStatus == AccessibilityConnectionStatus.CONNECTED) ActionStartEmerald else ActionStopRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Accessibility Live Reader", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                text = when (a11yStatus) {
                                    AccessibilityConnectionStatus.CONNECTED -> "Connected & inspecting windows"
                                    AccessibilityConnectionStatus.CONNECTING -> "Connecting..."
                                    AccessibilityConnectionStatus.ERROR -> "Restart required in Android Settings"
                                    AccessibilityConnectionStatus.DISCONNECTED -> "Disconnected — required for live monitoring"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                            )
                        },
                        modifier = Modifier.testTag("accessibility_permission_btn")
                    ) {
                        Text(
                            text = if (a11yStatus == AccessibilityConnectionStatus.CONNECTED) "OK" else "ENABLE",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = "Overlay",
                            tint = if (isDrawOverAppsGranted) ActionStartEmerald else Color(0xFFD97706),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Display Over Other Apps", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                text = if (isDrawOverAppsGranted) "Floating utility & Keep-Awake ready" else "Required for floating monitor card",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                            )
                        },
                        modifier = Modifier.testTag("overlay_permission_btn")
                    ) {
                        Text(
                            text = if (isDrawOverAppsGranted) "OK" else "ENABLE",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // Bounded Recent Crash History Preview
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
                    Text(
                        text = "SESSION CRASH GRID (${config.gridRows}×${config.gridColumns}) — ${crashRecords.size} RECORDED",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = PrimaryCobalt
                    )
                    Text(
                        text = "Open Full History →",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryCobalt,
                        modifier = Modifier.clickable { onNavigateToTab(MainActivity.TAB_CRASH_HISTORY) }
                    )
                }
                BoundedCrashHistoryGrid(
                    crashRecords = crashRecords,
                    gridRows = config.gridRows,
                    gridColumns = config.gridColumns
                )
            }
        }

        // Smart Analysis Agent Digest
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Psychology, contentDescription = "Smart Agent", tint = PrimaryCobalt)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SMART ANALYSIS AGENT",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = PrimaryCobalt
                    )
                }
                Text(
                    text = smartAgentExplanation.liveStatusLine,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = smartAgentExplanation.sampleConfidenceLabel,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 8-Module Quick Hub
        Text(
            text = "MODULES",
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val moduleRows = listOf(
            listOf(
                Triple("Live Monitor", Icons.Default.Speed, MainActivity.TAB_LIVE_MONITOR),
                Triple("Crash History", Icons.Default.GridView, MainActivity.TAB_CRASH_HISTORY)
            ),
            listOf(
                Triple("Patterns", Icons.Default.Analytics, MainActivity.TAB_PATTERNS),
                Triple("Strategies", Icons.Default.Tune, MainActivity.TAB_STRATEGY)
            ),
            listOf(
                Triple("Hourly Stats", Icons.Default.Schedule, MainActivity.TAB_HOURLY_ANALYSIS),
                Triple("Notifications", Icons.Default.Notifications, MainActivity.TAB_NOTIFICATIONS)
            )
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            moduleRows.forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pair.forEach { (title, icon, tabIndex) ->
                        ModuleTileButton(
                            title = title,
                            icon = icon,
                            onClick = { onNavigateToTab(tabIndex) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModuleTileButton(
                    title = "Risk Manager",
                    icon = Icons.Default.Security,
                    onClick = { onNavigateToTab(MainActivity.TAB_RISK_MANAGER) },
                    modifier = Modifier.weight(1f)
                )
                ModuleTileButton(
                    title = "Settings & Awake",
                    icon = Icons.Default.Settings,
                    onClick = { onNavigateToTab(MainActivity.TAB_SETTINGS) },
                    modifier = Modifier.weight(1f)
                )
            }
            if (onOpenOverlaySettings != null) {
                OutlinedButton(
                    onClick = onOpenOverlaySettings,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("home_open_overlay_mode_btn")
                ) {
                    Icon(Icons.Default.Layers, contentDescription = "Open Floating Overlay", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("OPEN OVERLAY CONTROLS (KEEP GAME ACTIVE)", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        }

        if (showConfirmClearDialog) {
            if (hasActivityRegistry) {
                AlertDialog(
                    onDismissRequest = { showConfirmClearDialog = false },
                    title = { Text("End Session & Clear Session Data?", fontWeight = FontWeight.Bold) },
                    text = {
                        Text("This stops monitoring, resets session crashes to 0, clears the current round ID, stops any active alarm, and removes records created during this session.")
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showConfirmClearDialog = false
                                onEndSessionAndClearData()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ActionStopRed, contentColor = Color.White),
                            modifier = Modifier.testTag("home_confirm_end_session_btn")
                        ) {
                            Text("END & CLEAR", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showConfirmClearDialog = false }) {
                            Text("CANCEL")
                        }
                    }
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("End Session & Clear Session Data?", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                "This stops monitoring, resets session crashes to 0, clears the current round ID, stops any active alarm, and removes records created during this session.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(
                                    onClick = { showConfirmClearDialog = false },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("CANCEL")
                                }
                                Button(
                                    onClick = {
                                        showConfirmClearDialog = false
                                        onEndSessionAndClearData()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = ActionStopRed, contentColor = Color.White),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("home_confirm_end_session_btn")
                                ) {
                                    Text("END & CLEAR", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleTileButton(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.height(54.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = title, tint = PrimaryCobalt, modifier = Modifier.size(20.dp))
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ExecutionHandsControlCard(
    isAutoBetEnabled: Boolean,
    betShortcut: FloatingShortcut?,
    cashOutShortcut: FloatingShortcut?,
    onToggleAutoBet: (Boolean) -> Unit,
    onToggleBetHand: () -> Unit,
    onToggleCashOutHand: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-Bet & Cash Out Execution",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isAutoBetEnabled) "Active when monitoring runs" else "Observation-only mode",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isAutoBetEnabled,
                    onCheckedChange = onToggleAutoBet
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onToggleBetHand,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).testTag("toggle_bet_hand_btn")
                ) {
                    Text(
                        text = if (betShortcut?.isCalibrated == true) {
                            "BET (${betShortcut.targetX.toInt()}, ${betShortcut.targetY.toInt()})"
                        } else {
                            "+ BET Hand"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }

                OutlinedButton(
                    onClick = onToggleCashOutHand,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).testTag("toggle_cash_hand_btn")
                ) {
                    Text(
                        text = if (cashOutShortcut?.isCalibrated == true) {
                            "CASH (${cashOutShortcut.targetX.toInt()}, ${cashOutShortcut.targetY.toInt()})"
                        } else {
                            "+ CASH Hand"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
