package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "risk_limits")
data class RiskLimits(
    @PrimaryKey
    val id: Int = 1,
    val maxSingleStake: Double = 500.0,
    val maxSessionLoss: Double = 1000.0,
    val maxTotalRounds: Int = 50,
    val maxProgressionLevel: Int = 5,
    val profitTarget: Double = 1500.0,
    val minBalanceThreshold: Double = 50.0,
    val emergencyStop: Boolean = false,
    val stopOnProfitEnabled: Boolean = true,
    val stopOnLossEnabled: Boolean = true,
    val maxSequenceExposure: Double = 500.0,
    val maxDailyLoss: Double = 500.0,
    val safetyReserveAmount: Double = 0.0
)
