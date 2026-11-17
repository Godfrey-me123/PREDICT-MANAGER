package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.example.data.database.ScreenEarnerRepository
import com.example.data.engine.AutomationEngine
import com.example.data.model.FloatingShortcut
import com.example.data.model.ShortcutType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs

/**
 * Manages draggable, off-screen parkable BET and CASH OUT calibration hands for Predict Manager.
 * Persists calibrated (targetX, targetY) coordinates directly to Room without creating duplicates
 * and coordinates global pass-through (`FLAG_NOT_TOUCHABLE`) whenever gestures are dispatched.
 */
class FloatingHandsOverlayManager(
    private val context: Context,
    private val repository: ScreenEarnerRepository,
    private val automationEngine: AutomationEngine
) {
    companion object {
        private val activeManagers = CopyOnWriteArraySet<FloatingHandsOverlayManager>()

        fun setAllPassThrough(isPassThrough: Boolean) {
            activeManagers.forEach { it.setPassThroughState(isPassThrough) }
        }

        fun pulsePassThroughAll(durationMs: Long = 220L) {
            activeManagers.forEach { it.setPassThroughTemporarily(durationMs) }
        }

        fun hasVisibleHands(): Boolean = activeManagers.any { it.hasActiveViews() }
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistMutex = Mutex()

    private val activeHandViews = mutableMapOf<ShortcutType, View>()
    private val activeLayoutParams = mutableMapOf<ShortcutType, WindowManager.LayoutParams>()
    private val persistedShortcutIds = ConcurrentHashMap<ShortcutType, Long>()
    private var pendingRestoreRunnable: Runnable? = null

    fun hasActiveViews(): Boolean = activeHandViews.isNotEmpty()

    init {
        activeManagers.add(this)
    }

    private val overlayWindowType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /**
     * Spawns or toggles a floating calibration hand (BET or CASH_OUT) on screen.
     */
    fun showOrToggleHand(
        type: ShortcutType,
        existingShortcut: FloatingShortcut? = null,
        defaultTargetMultiplier: Double = 2.00
    ) {
        mainHandler.post {
            if (activeHandViews.containsKey(type)) {
                removeHand(type)
                Toast.makeText(context, "${type.name} hand hidden", Toast.LENGTH_SHORT).show()
                return@post
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Toast.makeText(
                    context,
                    "Enable 'Display over other apps' first to use floating execution hands.",
                    Toast.LENGTH_LONG
                ).show()
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    )
                }
                return@post
            }

            val handSizePx = dpToPx(58)
            val dm = context.resources.displayMetrics
            val maxVisibleX = (dm.widthPixels - handSizePx).coerceAtLeast(24)
            val maxVisibleY = (dm.heightPixels - handSizePx - 80).coerceAtLeast(120)

            val rawX = existingShortcut?.targetX?.takeIf { it > 0f }?.minus(handSizePx / 2f)?.toInt()
                ?: if (type == ShortcutType.BET) (dm.widthPixels * 0.20f).toInt() else (dm.widthPixels * 0.62f).toInt()
            val rawY = existingShortcut?.targetY?.takeIf { it > 0f }?.minus(handSizePx / 2f)?.toInt()
                ?: (dm.heightPixels * 0.62f).toInt()

            val startX = rawX.coerceIn(16, maxVisibleX)
            val startY = rawY.coerceIn(120, maxVisibleY)

            if (existingShortcut != null && existingShortcut.id > 0L) {
                persistedShortcutIds[type] = existingShortcut.id
            }

            val params = WindowManager.LayoutParams(
                handSizePx,
                handSizePx,
                overlayWindowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, // Allows dragging partially off-screen
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = startX
                y = startY
            }

            val handView = createHandBadgeView(type, existingShortcut?.isCalibrated == true)
            attachDragAndCalibrateListener(
                view = handView,
                params = params,
                type = type,
                targetMultiplier = existingShortcut?.targetMultiplier ?: defaultTargetMultiplier
            )

            runCatching {
                windowManager.addView(handView, params)
                activeHandViews[type] = handView
                activeLayoutParams[type] = params
                Toast.makeText(
                    context,
                    "Drag ${if (type == ShortcutType.BET) "BET" else "CASH"} over game button, tap to lock, long-press to test tap",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    context,
                    "Could not show hand overlay: ${err.message ?: "permission required"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Synchronizes FLAG_NOT_TOUCHABLE state across all visible floating hands so automated
     * accessibility gestures pass cleanly through to the betting app underneath.
     */
    fun setPassThroughState(isPassThrough: Boolean) {
        mainHandler.post {
            if (activeHandViews.isEmpty()) return@post
            pendingRestoreRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingRestoreRunnable = null
            applyPassThroughFlags(isPassThrough)
        }
    }

    /**
     * Temporarily sets FLAG_NOT_TOUCHABLE on all hand overlays during automated gesture dispatch
     * so the floating icon never intercepts its own tap before reaching the browser/app underneath.
     */
    fun setPassThroughTemporarily(durationMs: Long = 220L) {
        mainHandler.post {
            if (activeHandViews.isEmpty()) return@post
            pendingRestoreRunnable?.let { mainHandler.removeCallbacks(it) }
            applyPassThroughFlags(true)
            val restore = Runnable {
                applyPassThroughFlags(false)
            }
            pendingRestoreRunnable = restore
            mainHandler.postDelayed(restore, durationMs.coerceAtLeast(100L))
        }
    }

    private fun applyPassThroughFlags(isPassThrough: Boolean) {
        activeHandViews.forEach { (type, view) ->
            val lp = activeLayoutParams[type] ?: return@forEach
            val nextFlags = if (isPassThrough) {
                lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            if (lp.flags != nextFlags) {
                lp.flags = nextFlags
                if (view.isAttachedToWindow) {
                    runCatching { windowManager.updateViewLayout(view, lp) }
                }
            }
        }
    }

    fun removeHand(type: ShortcutType) {
        mainHandler.post {
            activeHandViews.remove(type)?.let { view ->
                runCatching { windowManager.removeView(view) }
            }
            activeLayoutParams.remove(type)
        }
    }

    fun removeAllHands() {
        ShortcutType.entries.forEach { removeHand(it) }
        activeManagers.remove(this)
    }

    private fun createHandBadgeView(type: ShortcutType, isCalibrated: Boolean): TextView {
        val isBet = type == ShortcutType.BET
        val fillColor = if (isBet) Color.parseColor("#16A34A") else Color.parseColor("#EA580C")
        val strokeColor = if (isCalibrated) Color.parseColor("#FACC15") else Color.WHITE

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            setStroke(dpToPx(3), strokeColor)
        }

        return TextView(context).apply {
            text = if (isBet) "BET\n●" else "CASH\n●"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = bg
            elevation = 12f
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndCalibrateListener(
        view: TextView,
        params: WindowManager.LayoutParams,
        type: ShortcutType,
        targetMultiplier: Double
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var downAtMs = 0L
        var isDragging = false
        val touchSlop = 12f

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    downAtMs = SystemClock.uptimeMillis()
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = (initialX + dx).toInt()
                        params.y = (initialY + dy).toInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val centerX = params.x + (dpToPx(58) / 2f)
                    val centerY = params.y + (dpToPx(58) / 2f)
                    val pressDuration = SystemClock.uptimeMillis() - downAtMs

                    if (!isDragging && pressDuration >= 550L) {
                        calibrateAndPersistTarget(
                            view = view,
                            type = type,
                            centerX = centerX,
                            centerY = centerY,
                            targetMultiplier = targetMultiplier
                        )
                        val a11y = AccessibilityController.controllerInstance
                        if (a11y != null) {
                            setPassThroughTemporarily(250L)
                            mainHandler.postDelayed({
                                a11y.testTapCoordinate(centerX, centerY) { ok ->
                                    mainHandler.post {
                                        Toast.makeText(
                                            context,
                                            if (ok) "Test tap dispatched at (${centerX.toInt()}, ${centerY.toInt()})" else "Test tap blocked",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }, 55L)
                        } else {
                            Toast.makeText(context, "Target locked. Enable Accessibility to test tap.", Toast.LENGTH_SHORT).show()
                        }
                    } else if (!isDragging) {
                        calibrateAndPersistTarget(
                            view = view,
                            type = type,
                            centerX = centerX,
                            centerY = centerY,
                            targetMultiplier = targetMultiplier
                        )
                    } else {
                        persistShortcutToRoom(
                            type = type,
                            centerX = centerX,
                            centerY = centerY,
                            targetMultiplier = targetMultiplier
                        )
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun calibrateAndPersistTarget(
        view: TextView,
        type: ShortcutType,
        centerX: Float,
        centerY: Float,
        targetMultiplier: Double
    ) {
        (view.background as? GradientDrawable)?.setStroke(dpToPx(3), Color.parseColor("#FACC15"))
        persistShortcutToRoom(type, centerX, centerY, targetMultiplier)

        val label = if (type == ShortcutType.BET) "BET" else "CASH OUT"
        val msg = String.format(
            Locale.US,
            "%s target locked at (%.0f, %.0f)",
            label,
            centerX,
            centerY
        )
        automationEngine.publishStatusTicker(msg)
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    private fun persistShortcutToRoom(
        type: ShortcutType,
        centerX: Float,
        centerY: Float,
        targetMultiplier: Double
    ) {
        val activeSite = com.example.data.engine.GameDetection.resolveSiteKey(
            automationEngine.gameDetection.gameState.value.platformName
        )
        scope.launch {
            persistMutex.withLock {
                val allExisting = runCatching { repository.shortcuts.first() }.getOrDefault(emptyList())
                val knownId = persistedShortcutIds[type]?.takeIf { it > 0L }
                val matchedExisting = allExisting.firstOrNull { knownId != null && it.id == knownId }
                    ?: allExisting.firstOrNull {
                        it.type == type && it.isCalibrated &&
                            (activeSite.isEmpty() || it.targetPlatform.equals(activeSite, ignoreCase = true))
                    }
                    ?: allExisting.firstOrNull {
                        it.type == type &&
                            (activeSite.isEmpty() || it.targetPlatform.equals(activeSite, ignoreCase = true) || it.targetPlatform.isBlank())
                    }

                val shortcut = FloatingShortcut(
                    id = matchedExisting?.id ?: 0L,
                    label = if (type == ShortcutType.BET) "BET HAND" else "CASH OUT HAND",
                    type = type,
                    panelId = 1,
                    visualX = centerX,
                    visualY = centerY,
                    targetX = centerX,
                    targetY = centerY,
                    targetMultiplier = targetMultiplier,
                    targetPlatform = activeSite,
                    backgroundColorHex = if (type == ShortcutType.BET) "#16A34A" else "#EA580C",
                    isEnabled = true,
                    isCalibrated = true
                )

                val savedId = if (shortcut.id > 0L) {
                    repository.updateShortcut(shortcut)
                    shortcut.id
                } else {
                    repository.insertShortcut(shortcut)
                }
                if (savedId > 0L) {
                    persistedShortcutIds[type] = savedId
                }
            }
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}
