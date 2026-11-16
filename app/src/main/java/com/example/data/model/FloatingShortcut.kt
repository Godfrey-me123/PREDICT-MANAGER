package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ShortcutType {
    BET,
    CASH_OUT,
    TOGGLE_AUTO_BET,
    TOGGLE_AUTO_CASH_OUT,
    START_STOP
}

@Entity(tableName = "floating_shortcuts")
data class FloatingShortcut(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val taskName: String = "Task Robot",
    val label: String,
    val type: ShortcutType,
    val panelId: Int = 1,
    val visualX: Float = 100f,
    val visualY: Float = 200f,
    val widthDp: Int = 50,
    val heightDp: Int = 50,
    val opacity: Float = 0.9f,
    val backgroundColorHex: String = "#16A34A",
    val targetPlatform: String = "BetPawa",
    val associatedGame: String = "Aviator",
    val targetX: Float = 400f,
    val targetY: Float = 1200f,
    val stakeAmount: Double = 10.0,
    val targetMultiplier: Double = 2.00,
    val assignedStrategy: String = "Strategy 1",
    val taskMode: String = "AUTO", // "AUTO" or "MANUAL"
    val isCalibrated: Boolean = false,
    val isEnabled: Boolean = true
)
