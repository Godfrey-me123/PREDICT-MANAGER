package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.FloatingShortcut
import com.example.data.model.GameState
import com.example.data.model.ShortcutType
import com.example.ui.theme.ActionBetGreen
import com.example.ui.theme.ActionCashOutAmber
import com.example.ui.theme.PrimaryIndigo

@Composable
fun OverlayManagerScreen(
    shortcuts: List<FloatingShortcut>,
    gameState: GameState,
    isEditMode: Boolean,
    isPanelExpanded: Boolean,
    isCalibrating: Boolean,
    calibratingShortcutId: Long?,
    onSetEditMode: (Boolean) -> Unit,
    onTogglePanel: () -> Unit,
    onStartAutomation: () -> Unit,
    onPauseAutomation: () -> Unit,
    onResumeAutomation: () -> Unit,
    onQuitAutomation: () -> Unit,
    onExitUtility: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddBetShortcut: () -> Unit,
    onAddCashOutShortcut: () -> Unit,
    onUpdateShortcutPos: (id: Long, x: Float, y: Float) -> Unit,
    onSetCalibratedTarget: (x: Float, y: Float) -> Unit,
    onStartCalibration: (id: Long) -> Unit,
    onSaveShortcutSettings: (FloatingShortcut) -> Unit,
    onDeleteShortcut: (id: Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Mode Header & Switch
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isEditMode) Icons.Default.LockOpen else Icons.Default.Lock,
                            contentDescription = "Mode Icon",
                            tint = if (isEditMode) Color(0xFFF59E0B) else ActionBetGreen
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isEditMode) "CALIBRATION MODE" else "AUTOMATION MODE",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                    Text(
                        text = if (isEditMode) "Add independent floating task buttons & calibrate targets" else "Target coordinates locked for gameplay",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
                Switch(
                    checked = isEditMode,
                    onCheckedChange = onSetEditMode,
                    modifier = Modifier.testTag("edit_mode_switch")
                )
            }
        }

        // Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onAddBetShortcut,
                colors = ButtonDefaults.buttonColors(containerColor = ActionBetGreen),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("add_bet_btn")
            ) {
                Icon(Icons.Default.AddCircle, contentDescription = "Add Bet", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("ADD BET BUTTON", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onAddCashOutShortcut,
                colors = ButtonDefaults.buttonColors(containerColor = ActionCashOutAmber),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("add_cashout_btn")
            ) {
                Icon(Icons.Default.Wallet, contentDescription = "Add Cash Out", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("ADD CASH OUT", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Header for list
        Text(
            text = "INDEPENDENT FLOATING TASK ROBOTS (${shortcuts.size})",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            items(shortcuts) { shortcut ->
                val isBet = shortcut.type == ShortcutType.BET
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = if (isBet) ActionBetGreen else ActionCashOutAmber,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = shortcut.label,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Surface(
                                    color = if (shortcut.isCalibrated) ActionBetGreen.copy(alpha = 0.2f) else Color.Red.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (shortcut.isCalibrated) "Target Set" else "Uncalibrated",
                                        color = if (shortcut.isCalibrated) ActionBetGreen else Color.Red,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            IconButton(onClick = { onDeleteShortcut(shortcut.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444))
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Target Coordinates: (${shortcut.targetX.toInt()}px, ${shortcut.targetY.toInt()}px)",
                                    fontSize = 12.sp,
                                    color = Color.LightGray,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Platform: ${shortcut.targetPlatform} | ${if (isBet) "Stake: $${shortcut.stakeAmount}" else "Multiplier: ${shortcut.targetMultiplier}x"}",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }

                            Button(
                                onClick = { onStartCalibration(shortcut.id) },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("calibrate_${shortcut.id}")
                            ) {
                                Icon(Icons.Default.Layers, contentDescription = "Calibrate", modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("CALIBRATE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
