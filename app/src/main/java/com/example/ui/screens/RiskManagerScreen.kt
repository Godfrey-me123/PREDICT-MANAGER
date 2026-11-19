package com.example.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.RiskLimits

@Composable
fun RiskManagerScreen(
    currentLimits: RiskLimits?,
    lastViolation: String?,
    onSaveLimits: (RiskLimits) -> Unit,
    onTriggerEmergencyStop: () -> Unit,
    onClearEmergencyStop: () -> Unit,
    currentDailyLoss: Double = 0.0
) {
    val context = LocalContext.current
    val limits = currentLimits ?: RiskLimits()

    var maxSingleStake by remember(limits.maxSingleStake) { mutableStateOf(limits.maxSingleStake.toString()) }
    var maxSessionLoss by remember(limits.maxSessionLoss) { mutableStateOf(limits.maxSessionLoss.toString()) }
    var maxTotalRounds by remember(limits.maxTotalRounds) { mutableStateOf(limits.maxTotalRounds.toString()) }
    var maxProgressionLevel by remember(limits.maxProgressionLevel) { mutableStateOf(limits.maxProgressionLevel.toString()) }
    var profitTarget by remember(limits.profitTarget) { mutableStateOf(limits.profitTarget.toString()) }
    var minBalanceThreshold by remember(limits.minBalanceThreshold) { mutableStateOf(limits.minBalanceThreshold.toString()) }
    var maxSequenceExposure by remember(limits.maxSequenceExposure) { mutableStateOf(limits.maxSequenceExposure.toString()) }
    var maxDailyLoss by remember(limits.maxDailyLoss) { mutableStateOf(limits.maxDailyLoss.toString()) }
    var safetyReserveAmount by remember(limits.safetyReserveAmount) { mutableStateOf(limits.safetyReserveAmount.toString()) }
    var emergencyStop by remember(limits.emergencyStop) { mutableStateOf(limits.emergencyStop) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Violation Warning Banner
        if (lastViolation != null) {
            Surface(
                color = Color(0xFFFEF2F2),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.Red),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = "Alert", tint = Color.Red)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "RISK STOP TRIGGERED: $lastViolation",
                        color = Color.Red,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Emergency Stop Master Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (emergencyStop) Color(0xFFFEF2F2) else MaterialTheme.colorScheme.surface
            )
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
                            Icons.Default.Security,
                            contentDescription = "Security",
                            tint = if (emergencyStop) Color.Red else MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "EMERGENCY STOP SWITCH",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = if (emergencyStop) Color.Red else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "Immediately halts all active automation & cancels pending bets",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
                Switch(
                    checked = emergencyStop,
                    onCheckedChange = {
                        emergencyStop = it
                        if (it) onTriggerEmergencyStop() else onClearEmergencyStop()
                    },
                    modifier = Modifier.testTag("emergency_switch")
                )
            }
        }

        // Hard Risk Limits Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "HARD RISK SAFETY LIMITS",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Automation automatically pauses if any limit is reached.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = maxSingleStake,
                        onValueChange = { maxSingleStake = it },
                        label = { Text("Max Single Stake") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("max_stake_input")
                    )
                    OutlinedTextField(
                        value = maxSessionLoss,
                        onValueChange = { maxSessionLoss = it },
                        label = { Text("Max Session Loss") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("max_loss_input")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = maxTotalRounds,
                        onValueChange = { maxTotalRounds = it },
                        label = { Text("Max Total Rounds") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("max_rounds_input")
                    )
                    OutlinedTextField(
                        value = maxProgressionLevel,
                        onValueChange = { maxProgressionLevel = it },
                        label = { Text("Max Progression Level") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("max_prog_level_input")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = profitTarget,
                        onValueChange = { profitTarget = it },
                        label = { Text("Session Profit Target") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("profit_target_input")
                    )
                    OutlinedTextField(
                        value = minBalanceThreshold,
                        onValueChange = { minBalanceThreshold = it },
                        label = { Text("Min Balance Threshold") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("min_balance_input")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = maxSequenceExposure,
                        onValueChange = { maxSequenceExposure = it },
                        label = { Text("Max Seq Exposure") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("max_sequence_exposure_input")
                    )
                    OutlinedTextField(
                        value = maxDailyLoss,
                        onValueChange = { maxDailyLoss = it },
                        label = { Text("Max Daily Loss") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("max_daily_loss_input")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = safetyReserveAmount,
                    onValueChange = { safetyReserveAmount = it },
                    label = { Text("Safety Balance Reserve") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("safety_reserve_input")
                )

                if (currentDailyLoss > 0.0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Today's Recorded Loss: ${String.format(java.util.Locale.US, "%.2f", currentDailyLoss)}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        var isSaving by remember { mutableStateOf(false) }
        Button(
            enabled = !isSaving,
            onClick = {
                if (isSaving) return@Button
                try {
                    val parsedMaxStake = maxSingleStake.trim().toDoubleOrNull()
                    val parsedMaxLoss = maxSessionLoss.trim().toDoubleOrNull()
                    val parsedMaxRounds = maxTotalRounds.trim().toIntOrNull()
                    val parsedMaxProg = maxProgressionLevel.trim().toIntOrNull()
                    val parsedProfitTarget = profitTarget.trim().toDoubleOrNull()
                    val parsedMinBalance = minBalanceThreshold.trim().toDoubleOrNull()
                    val parsedSeqExposure = maxSequenceExposure.trim().toDoubleOrNull()
                    val parsedDailyLoss = maxDailyLoss.trim().toDoubleOrNull()
                    val parsedReserve = safetyReserveAmount.trim().toDoubleOrNull()

                    val invalid = parsedMaxStake == null || !parsedMaxStake.isFinite() || parsedMaxStake <= 0.0 ||
                        parsedMaxLoss == null || !parsedMaxLoss.isFinite() || parsedMaxLoss < 0.0 ||
                        parsedMaxRounds == null || parsedMaxRounds < 1 ||
                        parsedMaxProg == null || parsedMaxProg < 1 ||
                        parsedProfitTarget == null || !parsedProfitTarget.isFinite() || parsedProfitTarget < 0.0 ||
                        parsedMinBalance == null || !parsedMinBalance.isFinite() || parsedMinBalance < 0.0 ||
                        parsedSeqExposure == null || !parsedSeqExposure.isFinite() || parsedSeqExposure < 0.0 ||
                        parsedDailyLoss == null || !parsedDailyLoss.isFinite() || parsedDailyLoss < 0.0 ||
                        parsedReserve == null || !parsedReserve.isFinite() || parsedReserve < 0.0

                    if (invalid) {
                        Toast.makeText(
                            context,
                            "Failed to save settings, please check input",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@Button
                    }

                    isSaving = true
                    onSaveLimits(
                        limits.copy(
                            maxSingleStake = parsedMaxStake!!,
                            maxSessionLoss = parsedMaxLoss!!,
                            maxTotalRounds = parsedMaxRounds!!,
                            maxProgressionLevel = parsedMaxProg!!,
                            profitTarget = parsedProfitTarget!!,
                            minBalanceThreshold = parsedMinBalance!!,
                            maxSequenceExposure = parsedSeqExposure!!,
                            maxDailyLoss = parsedDailyLoss!!,
                            safetyReserveAmount = parsedReserve!!,
                            emergencyStop = emergencyStop
                        )
                    )
                    isSaving = false
                } catch (e: Exception) {
                    isSaving = false
                    Log.e("RiskManagerScreen", "Save failed", e)
                    Toast.makeText(
                        context,
                        "Failed to save settings, please check input",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("save_risk_limits_btn")
        ) {
            Icon(Icons.Default.Save, contentDescription = "Save Limits")
            Spacer(modifier = Modifier.width(8.dp))
            Text("SAVE & CLOSE", fontWeight = FontWeight.Bold)
        }
    }
}
