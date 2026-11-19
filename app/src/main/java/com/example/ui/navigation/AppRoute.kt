package com.example.ui.navigation

import com.example.MainActivity

/**
 * Shared module routing destinations used symmetrically by both the unified
 * Floating Overlay Container and optional Full-Screen MainActivity.
 */
enum class AppRoute(
    val tabIndex: Int,
    val title: String,
    val shortLabel: String
) {
    HOME(MainActivity.TAB_HOME, "Home Overview", "Home"),
    LIVE_MONITOR(MainActivity.TAB_LIVE_MONITOR, "Live Game Monitor", "Monitor"),
    CRASH_HISTORY(MainActivity.TAB_CRASH_HISTORY, "Crash History", "History"),
    PATTERNS(MainActivity.TAB_PATTERNS, "Patterns & Smart Agent", "Patterns"),
    STRATEGIES(MainActivity.TAB_STRATEGY, "Strategy Manager", "Strategies"),
    HOURLY_ANALYSIS(MainActivity.TAB_HOURLY_ANALYSIS, "Hourly Analysis", "Hourly"),
    NOTIFICATIONS(MainActivity.TAB_NOTIFICATIONS, "Notification Center", "Alerts"),
    SETTINGS(MainActivity.TAB_SETTINGS, "Settings & Appearance", "Settings"),
    RISK_MANAGER(MainActivity.TAB_RISK_MANAGER, "Risk & Safety Locks", "Risk");

    companion object {
        fun fromTabIndex(tabIndex: Int): AppRoute =
            entries.firstOrNull { it.tabIndex == tabIndex } ?: HOME
    }
}
