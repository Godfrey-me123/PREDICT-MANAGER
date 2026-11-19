package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoPredictorTopBar(
    isMonitoringActive: Boolean,
    isOutsideSchedule: Boolean = false,
    onToggleMonitoring: () -> Unit,
    onSwitchToOverlayMode: (() -> Unit)? = null
) {
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Radar,
                    contentDescription = "Predict Manager",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "PREDICT MANAGER",
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        actions = {
            if (onSwitchToOverlayMode != null) {
                FilledTonalButton(
                    onClick = onSwitchToOverlayMode,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .testTag("topbar_overlay_mode_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = "Overlay Mode",
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(16.dp)
                    )
                    Text("Overlay", fontWeight = FontWeight.SemiBold)
                }
            }
            val label = when {
                isOutsideSchedule -> "Standby"
                isMonitoringActive -> "Pause Monitor"
                else -> "Start Monitor"
            }
            FilledTonalButton(
                onClick = onToggleMonitoring,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (isMonitoringActive) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    contentColor = if (isMonitoringActive) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    }
                ),
                modifier = Modifier
                    .padding(end = 8.dp)
                    .testTag("monitoring_toggle_button")
            ) {
                Icon(
                    imageVector = if (isMonitoringActive) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                    contentDescription = label,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(18.dp)
                )
                Text(label, fontWeight = FontWeight.SemiBold)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
fun ScreenEarnerTopBar(
    onEmergencyStop: () -> Unit
) {
    AutoPredictorTopBar(
        isMonitoringActive = true,
        onToggleMonitoring = onEmergencyStop
    )
}
