package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_logs")
data class SessionLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val platform: String = "BetPawa",
    val roundNumber: Int,
    val detectedMultiplier: Double,
    val outcome: String, // "WIN", "LOSS", "SKIPPED", "TRIGGER_WATCH"
    val stake: Double,
    val payout: Double,
    val netProfit: Double,
    val triggerReason: String
)
