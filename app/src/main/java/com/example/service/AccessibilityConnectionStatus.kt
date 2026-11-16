package com.example.service

/**
 * Explicit connection state machine enum for Android Accessibility Service health (Section 1).
 * Never infer connectivity merely from whether an overlay is visible.
 */
enum class AccessibilityConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}
