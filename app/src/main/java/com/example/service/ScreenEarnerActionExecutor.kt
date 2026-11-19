package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Reusable UI-action layer for Predict Manager (Screen Earner).
 *
 * This is deliberately separate from multiplier collection and StrategyEngine:
 * it performs one requested UI action and reports whether Android accepted it.
 * It does not assume that a dispatched gesture means the game accepted a bet.
 */
class ScreenEarnerActionExecutor(
    private val service: ScreenEarnerAccessibilityService
) {
    data class Result(
        val success: Boolean,
        val message: String,
        val observedValue: String? = null
    )

    suspend fun tap(x: Float, y: Float): Result =
        dispatchGesture(x, y, x, y, 70L)

    suspend fun doubleTap(x: Float, y: Float): Result {
        val first = tap(x, y)
        if (!first.success) return first
        kotlinx.coroutines.delay(100L)
        return tap(x, y)
    }

    suspend fun longPress(x: Float, y: Float, durationMs: Long = 800L): Result =
        dispatchGesture(x, y, x, y, durationMs.coerceIn(300L, 10_000L))

    suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300L
    ): Result = dispatchGesture(
        startX, startY, endX, endY, durationMs.coerceIn(80L, 10_000L)
    )

    suspend fun clickText(query: String): Result {
        val root = service.rootInActiveWindow
            ?: return Result(false, "No active accessibility window")
        val node = findNode(root, query, requireEditable = false)
            ?: return Result(false, "Element not found: $query")
        var clickable: AccessibilityNodeInfo? = null
        return try {
            clickable = clickableAncestor(node)
            val target = clickable ?: node
            val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) Result(true, "Android accepted click on '$query'")
            else {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.isEmpty) Result(false, "Element has no usable screen bounds: $query")
                else tap(bounds.exactCenterX(), bounds.exactCenterY())
            }
        } finally {
            clickable?.let { runCatching { it.recycle() } }
            recycleTreeNode(node, root)
            runCatching { root.recycle() }
        }
    }

    suspend fun focusField(label: String): Result {
        val root = service.rootInActiveWindow
            ?: return Result(false, "No active accessibility window")
        val node = findNode(root, label, requireEditable = true)
            ?: return Result(false, "Editable field not found: $label")
        return try {
            val focused = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val inputFocused = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            if (focused || inputFocused) Result(true, "Field focus action accepted: '$label'")
            else Result(false, "Android rejected focus action for '$label'")
        } finally {
            recycleTreeNode(node, root)
            runCatching { root.recycle() }
        }
    }

    /** Replace text in a named editable field (clearing existing text first) and read it back before reporting success. */
    suspend fun replaceFieldText(label: String, value: String): Result {
        val root = service.rootInActiveWindow
            ?: return Result(false, "No active accessibility window")
        val node = findNode(root, label, requireEditable = true)
            ?: return Result(false, "Editable field not found: $label")
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            setNodeTextAndVerify(node, value)
        } finally {
            recycleTreeNode(node, root)
            runCatching { root.recycle() }
        }
    }

    suspend fun typeIntoFocusedField(value: String): Result {
        val root = service.rootInActiveWindow
            ?: return Result(false, "No active accessibility window")
        val node = findFocusedEditable(root)
            ?: return Result(false, "No focused editable field")
        return try {
            setNodeTextAndVerify(node, value)
        } finally {
            recycleTreeNode(node, root)
            runCatching { root.recycle() }
        }
    }

    suspend fun appendToFocusedField(value: String): Result {
        val root = service.rootInActiveWindow
            ?: return Result(false, "No active accessibility window")
        val node = findFocusedEditable(root)
            ?: return Result(false, "No focused editable field")
        return try {
            val current = node.text?.toString().orEmpty()
            setNodeTextAndVerify(node, current + value)
        } finally {
            recycleTreeNode(node, root)
            runCatching { root.recycle() }
        }
    }

    suspend fun clearFocusedField(): Result = typeIntoFocusedField("")

    fun pressBack(): Result = globalAction(AccessibilityService.GLOBAL_ACTION_BACK, "Back")
    fun pressHome(): Result = globalAction(AccessibilityService.GLOBAL_ACTION_HOME, "Home")
    fun openRecents(): Result = globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS, "Recent Apps")
    fun openNotifications(): Result =
        globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notifications")

    fun launchApp(packageName: String): Result {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return Result(false, "Package name is empty")
        return try {
            val intent = service.packageManager.getLaunchIntentForPackage(pkg)
                ?: return Result(false, "No launchable activity for package: $pkg")
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent)
            Result(true, "Launch request sent for $pkg")
        } catch (t: Throwable) {
            Result(false, "Could not launch $pkg: ${t.message}")
        }
    }

    fun isSoftKeyboardOpen(): Boolean {
        return runCatching {
            service.windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: false
        }.getOrDefault(false)
    }

    /** Dismisses the IME ONLY if AccessibilityWindowInfo confirms an active soft keyboard window. */
    fun dismissKeyboardSafely(): Result {
        if (!isSoftKeyboardOpen()) {
            return Result(true, "No active soft keyboard window")
        }
        return pressBack()
    }

    fun isTargetButtonReady(expectBet: Boolean): Boolean {
        val targets = service.getLatestUiTargets()
        return if (expectBet) {
            targets.gamePhase == "BETTING_OPEN" ||
                targets.siteButtonState == com.example.data.model.SiteButtonState.IDLE_READY_TO_BET ||
                targets.betButtonBounds != null
        } else {
            targets.siteButtonState == com.example.data.model.SiteButtonState.CASH_OUT_AVAILABLE ||
                targets.cashOutButtonBounds != null
        }
    }

    private suspend fun setNodeTextAndVerify(
        node: AccessibilityNodeInfo,
        value: String
    ): Result {
        if (!node.isEditable) return Result(false, "Focused node is not editable")
        val existing = runCatching { node.text?.toString().orEmpty() }.getOrDefault("")
        if (existing.isNotEmpty() && value.isNotEmpty()) {
            val clearArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs) }
        }
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value
            )
        }
        val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        runCatching { node.refresh() }
        val readBack = runCatching { node.text?.toString() }.getOrNull()
        val verified = accepted && readBack != null && normalize(readBack) == normalize(value)
        return if (verified) Result(true, "Text verified", readBack)
        else Result(false, "Text action not verified (observed=${readBack ?: "unavailable"})", readBack)
    }

    suspend fun pressEnter(): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Result(false, "IME Enter action requires Android 11 or newer")
        }
        val root = service.rootInActiveWindow
            ?: return Result(false, "No active accessibility window")
        val node = findFocusedEditable(root)
            ?: return Result(false, "No focused editable field")
        return try {
            val accepted = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            if (accepted) Result(true, "Keyboard Enter action accepted")
            else Result(false, "Keyboard Enter action was rejected")
        } finally {
            recycleTreeNode(node, root)
            runCatching { root.recycle() }
        }
    }

    private fun globalAction(action: Int, label: String): Result =
        if (service.performGlobalAction(action)) Result(true, "$label action accepted")
        else Result(false, "$label action rejected by Android")

    private suspend fun dispatchGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return Result(false, "Gesture dispatch requires Android 7.0 or newer")
        }
        val metrics = service.resources.displayMetrics
        val maxX = metrics.widthPixels.toFloat()
        val maxY = metrics.heightPixels.toFloat()
        if (!startX.isFinite() || !startY.isFinite() ||
            !endX.isFinite() || !endY.isFinite() ||
            startX < 0f || startY < 0f || endX < 0f || endY < 0f ||
            startX >= maxX || endX >= maxX || startY >= maxY || endY >= maxY
        ) {
            return Result(false, "Gesture coordinates are outside the current display")
        }

        AccessibilityController.notifyOverlayPassThrough(true)
        kotlinx.coroutines.delay(45L)
        return suspendCancellableCoroutine { continuation ->
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path, 0L, durationMs.coerceAtLeast(1L)
                    )
                )
                .build()

            val dispatched = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        AccessibilityController.notifyOverlayPassThrough(false)
                        if (continuation.isActive) {
                            continuation.resume(Result(true, "Gesture dispatched"))
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        AccessibilityController.notifyOverlayPassThrough(false)
                        if (continuation.isActive) {
                            continuation.resume(Result(false, "Gesture cancelled by Android"))
                        }
                    }
                },
                null
            )
            if (!dispatched) {
                AccessibilityController.notifyOverlayPassThrough(false)
                if (continuation.isActive) {
                    continuation.resume(Result(false, "Android rejected gesture dispatch"))
                }
            }
            continuation.invokeOnCancellation {
                AccessibilityController.notifyOverlayPassThrough(false)
            }
        }
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = runCatching {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }.getOrNull()
        if (focused != null && runCatching { focused.isEditable && focused.isEnabled }.getOrDefault(false)) {
            return focused
        }
        focused?.let { runCatching { it.recycle() } }
        return null
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        query: String,
        requireEditable: Boolean,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        if (depth > 35) return null
        val q = query.trim().lowercase()
        val text = runCatching { root.text?.toString().orEmpty() }.getOrDefault("")
        val desc = runCatching { root.contentDescription?.toString().orEmpty() }.getOrDefault("")
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { root.hintText?.toString().orEmpty() }.getOrDefault("")
        } else ""
        val id = runCatching { root.viewIdResourceName.orEmpty() }.getOrDefault("")
        val editable = runCatching { root.isEditable }.getOrDefault(false)
        val enabled = runCatching { root.isEnabled }.getOrDefault(false)
        val lowerText = text.trim().lowercase()
        val lowerDesc = desc.trim().lowercase()
        val lowerId = id.lowercase()
        val metadata = "$text $desc $hint $id".lowercase()

        val isBrowserChrome = lowerId.contains("url_bar") || lowerId.contains("omnibox") || lowerId.contains("location_bar")
        val isMisleadingActionMatch = !requireEditable && (q == "bet" || q == "cash out") && (
            metadata.contains("betpawa") || metadata.contains("sportybet") ||
                metadata.contains("all bets") || metadata.contains("my bets") ||
                metadata.contains("top bets") || metadata.contains("betslip") ||
                metadata.contains("bet history") || metadata.contains("auto") ||
                metadata.contains("cancel")
            )

        val matches = q.isNotEmpty() && !isBrowserChrome && !isMisleadingActionMatch &&
            (lowerText == q || lowerDesc == q ||
                (q == "bet" && lowerText.startsWith("bet") && !lowerText.contains("bets")) ||
                (q != "bet" && metadata.contains(q)))
        if (matches && enabled && (!requireEditable || editable)) {
            return AccessibilityNodeInfo.obtain(root)
        }

        for (index in 0 until root.childCount.coerceAtMost(100)) {
            val child = runCatching { root.getChild(index) }.getOrNull() ?: continue
            val found = findNode(child, query, requireEditable, depth + 1)
            runCatching { child.recycle() }
            if (found != null) return found
        }
        return null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        repeat(8) {
            val candidate = current ?: return null
            if (candidate.isClickable && candidate.isEnabled) return candidate
            val parent = runCatching { candidate.parent }.getOrNull()
            runCatching { candidate.recycle() }
            current = parent
        }
        current?.let { runCatching { it.recycle() } }
        return null
    }

    private fun recycleTreeNode(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo) {
        if (node !== root) runCatching { node.recycle() }
    }

    private fun normalize(value: String): String =
        value.trim().replace(",", "").replace(" ", "")
}
