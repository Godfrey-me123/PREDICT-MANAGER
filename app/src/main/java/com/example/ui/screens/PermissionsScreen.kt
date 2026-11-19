package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.service.ScreenEarnerAccessibilityService
import com.example.ui.theme.ActionStartEmerald
import com.example.ui.theme.ActionStopRed
import com.example.ui.theme.PrimaryCobalt

@Composable
fun PermissionsScreen(scrollable: Boolean = true) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val isDrawOverAppsGranted = Settings.canDrawOverlays(context)
    val isAccessibilityConnected = ScreenEarnerAccessibilityService.isServiceConnected()
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val isBatteryUnrestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    } else {
        true
    }

    var isNotificationGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val hasActivityRegistry = LocalActivityResultRegistryOwner.current != null
    val notificationPermissionLauncher = if (hasActivityRegistry) {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
            isNotificationGranted = granted
        }
    } else null

    val containerModifier = if (scrollable) {
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    } else {
        Modifier.fillMaxWidth()
    }

    Column(
        modifier = containerModifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Permissions Header",
                        tint = PrimaryCobalt,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SYSTEM PERMISSIONS & BACKGROUND HEALTH",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        color = PrimaryCobalt
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Predict Manager uses Accessibility multi-window inspection, Overlay rendering, Unrestricted Battery, and System Notifications to monitor games reliably.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        PermissionActionCard(
            icon = Icons.Default.Layers,
            title = "Display Over Other Apps",
            description = "Renders the unclamped floating AP bubble and bounded crash grid over Chrome or game apps.",
            granted = isDrawOverAppsGranted,
            grantedLabel = "GRANTED",
            missingLabel = "REQUIRED",
            buttonLabel = if (isDrawOverAppsGranted) "OVERLAY AUTHORIZED (MANAGE)" else "ALLOW DISPLAY OVER APPS",
            buttonTestTag = "open_overlay_settings_btn",
            onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(intent)
            }
        )

        PermissionActionCard(
            icon = Icons.Default.AccessibilityNew,
            title = "Accessibility Live Monitor",
            description = "Reads multi-window crash game interfaces, split-screen tabs, and completed round multipliers without modifying or clicking game controls.",
            granted = isAccessibilityConnected,
            grantedLabel = "CONNECTED",
            missingLabel = "DISCONNECTED",
            buttonLabel = if (isAccessibilityConnected) "ACCESSIBILITY CONNECTED (MANAGE)" else "OPEN ACCESSIBILITY SETTINGS",
            buttonTestTag = "open_accessibility_settings_btn",
            onClick = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        )

        PermissionActionCard(
            icon = Icons.Default.Notifications,
            title = "Android System Notifications",
            description = "Delivers Critical Pattern Alerts, Hourly Analysis digests, Game Detection notices, and Monitoring Error alerts.",
            granted = isNotificationGranted,
            grantedLabel = "GRANTED",
            missingLabel = "DENIED",
            buttonLabel = if (isNotificationGranted) "NOTIFICATIONS GRANTED (MANAGE)" else "ALLOW SYSTEM NOTIFICATIONS",
            buttonTestTag = "request_notification_permission_btn",
            cardTestTag = "notification_permission_card",
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isNotificationGranted && notificationPermissionLauncher != null) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            }
        )

        PermissionActionCard(
            icon = Icons.Default.BatteryChargingFull,
            title = "Unrestricted Background Activity",
            description = "Prevents Android from putting live game tracking, hourly transitions, and Keep-Awake monitoring to sleep during multitasking.",
            granted = isBatteryUnrestricted,
            grantedLabel = "UNRESTRICTED",
            missingLabel = "RESTRICTED",
            buttonLabel = if (isBatteryUnrestricted) "BATTERY UNRESTRICTED (MANAGE)" else "DISABLE BATTERY OPTIMIZATION",
            buttonTestTag = "open_battery_optimization_btn",
            onClick = {
                try {
                    val req = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")
                    ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    context.startActivity(req)
                } catch (_: Exception) {
                    try {
                        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(fallback)
                    } catch (_: Exception) {
                    }
                }
            }
        )
    }
}

@Composable
private fun PermissionActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    grantedLabel: String,
    missingLabel: String,
    buttonLabel: String,
    buttonTestTag: String,
    cardTestTag: String? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = (if (cardTestTag != null) Modifier.testTag(cardTestTag) else Modifier).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (granted) ActionStartEmerald.copy(alpha = 0.45f) else Color(0xFFF59E0B).copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = if (granted) ActionStartEmerald else Color(0xFFD97706)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                PermissionStatusBadge(
                    granted = granted,
                    grantedLabel = grantedLabel,
                    missingLabel = missingLabel
                )
            }
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (granted) ActionStartEmerald else PrimaryCobalt,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag(buttonTestTag)
            ) {
                Text(text = buttonLabel, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PermissionStatusBadge(
    granted: Boolean,
    grantedLabel: String,
    missingLabel: String
) {
    Surface(
        color = if (granted) ActionStartEmerald.copy(alpha = 0.14f) else ActionStopRed.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, if (granted) ActionStartEmerald else ActionStopRed),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = if (granted) grantedLabel else missingLabel,
                tint = if (granted) ActionStartEmerald else ActionStopRed,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (granted) grantedLabel else missingLabel,
                color = if (granted) ActionStartEmerald else ActionStopRed,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
