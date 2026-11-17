package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.R
import com.example.data.engine.AutomationState
import com.example.data.engine.OperatingScheduleEvaluator
import com.example.data.model.GameState
import com.example.data.model.SessionStats
import com.example.ui.components.BoundedCrashHistoryGrid
import com.example.ui.components.LiveCrashHeaderCard
import com.example.ui.theme.ActionCashOutAmber
import com.example.ui.theme.ActionStartEmerald
import com.example.ui.theme.ActionStopRed
import com.example.ui.theme.AutoPredictorTheme
import com.example.ui.theme.PrimaryCobalt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Lightweight observation-only floating utility service for Predict Manager.
 * Provides a freely draggable "PM" bubble, compact live HUD pill (last crash + round ID),
 * resizable & compact/expanded live monitor card, FLAG_KEEP_SCREEN_ON management,
 * and bounded CPU WakeLock lifecycle.
 */
open class ScreenEarnerOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "PredictManagerOverlay"
        const val CHANNEL_ID = "monitoring_service"
        const val NOTIFICATION_ID = 1001
        const val PREFS_NAME = "screen_earner_prefs"
        const val PREF_BUBBLE_OPACITY = "bubble_opacity"
        const val PREF_BUBBLE_SIZE_DP = "bubble_size_dp"
        const val PREF_HAPTICS_ENABLED = "haptics_enabled"
        const val ACTION_OPEN_OVERLAY_SETTINGS = "ACTION_OPEN_OVERLAY_SETTINGS"

        var isRunning = false
            private set

        private val _isScreenHeldState = MutableStateFlow(false)
        val isScreenHeldState: StateFlow<Boolean> = _isScreenHeldState

        private val _isCpuActiveState = MutableStateFlow(false)
        val isCpuActiveState: StateFlow<Boolean> = _isCpuActiveState

        private val _awakeErrorState = MutableStateFlow<String?>(null)
        val awakeErrorState: StateFlow<String?> = _awakeErrorState

        fun clampOverlayPosition(
            x: Int,
            y: Int,
            viewWidthPx: Int,
            viewHeightPx: Int,
            safeBounds: Rect
        ): Pair<Int, Int> {
            val maxX = (safeBounds.right - viewWidthPx).coerceAtLeast(safeBounds.left)
            val maxY = (safeBounds.bottom - viewHeightPx).coerceAtLeast(safeBounds.top)
            return x.coerceIn(safeBounds.left, maxX) to y.coerceIn(safeBounds.top, maxY)
        }
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var sessionHudView: View? = null
    private var sessionHudParams: WindowManager.LayoutParams? = null

    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var settingsOverlayView: View? = null
    private var settingsOverlayParams: WindowManager.LayoutParams? = null

    private var isPanelVisible = false
    private var isSettingsOverlayVisible = false
    private var statusBannerMessage = MutableStateFlow<String?>(null)
    private var wakeLock: PowerManager.WakeLock? = null
    private var isKeepAwakeEngaged = false
    private var lastToastTicker: String = ""
    private var lastToastTimestampMs: Long = 0L

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_STOP_MONITORING" -> {
                AutomationController.stopMonitoringSession()
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_OPEN_OVERLAY_SETTINGS -> {
                removeCompactControlPanel()
                showSettingsOverlayPanel()
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        isRunning = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start foreground service in ScreenEarnerOverlayService", t)
            isRunning = false
            stopSelf()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Display over other apps permission (canDrawOverlays) is missing.")
            try {
                Toast.makeText(this, "Display over other apps permission is recommended for overlay UI.", Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {
            }
        }

        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        AutomationController.ensureInitialized(applicationContext)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        setupFloatingBubbleOverlay()
        setupSessionStatsHudOverlay()
        registerGesturePassThroughListener()
        observeKeepAwakeAndDiagnostics()
    }

    private fun registerGesturePassThroughListener() {
        AccessibilityController.gestureOverlayPassThroughListener = { isDispatching ->
            val wm = windowManager
            if (wm != null) {
                fun updateTouchable(view: View?, params: WindowManager.LayoutParams?) {
                    if (view == null || params == null || !view.isAttachedToWindow) return
                    val nextFlags = if (isDispatching) {
                        params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    } else {
                        params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    }
                    if (params.flags != nextFlags) {
                        params.flags = nextFlags
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                }
                updateTouchable(bubbleView, bubbleParams)
                updateTouchable(sessionHudView, sessionHudParams)
                updateTouchable(panelView, panelParams)
                updateTouchable(settingsOverlayView, settingsOverlayParams)
            }
        }
    }

    private fun observeKeepAwakeAndDiagnostics() {
        val engine = AutomationController.engine

        // Req 5: Acquire FLAG_KEEP_SCREEN_ON and bounded CPU wake lock ONLY while active scheduled monitoring is running AND enabled
        serviceScope.launch {
            if (engine != null) {
                combine(
                    AutomationController.latestConfig,
                    engine.automationState,
                    engine.isStandbyOutsideSchedule
                ) { cfg, autoState, standby ->
                    cfg.keepScreenAwake && autoState == AutomationState.RUNNING && !standby
                }.distinctUntilChanged().collect { shouldHold ->
                    isKeepAwakeEngaged = shouldHold
                    applyScreenAwakeFlag(shouldHold)
                    applyCpuWakeLock(shouldHold)
                    engine.gameDetection.updateScreenAwakeState(
                        wakeLockActive = _isCpuActiveState.value,
                        keepScreenOnApplied = _isScreenHeldState.value
                    )
                }
            } else {
                isKeepAwakeEngaged = false
                applyScreenAwakeFlag(false)
                applyCpuWakeLock(false)
            }
        }

        serviceScope.launch {
            engine?.agentStatusTicker?.collect { ticker ->
                val cfg = AutomationController.latestConfig.value
                val now = System.currentTimeMillis()
                if (cfg.diagnosticToastsEnabled &&
                    ticker.isNotBlank() &&
                    ticker != lastToastTicker &&
                    now - lastToastTimestampMs >= 2500L
                ) {
                    lastToastTimestampMs = now
                    lastToastTicker = ticker
                    try {
                        Toast.makeText(this@ScreenEarnerOverlayService, ticker, Toast.LENGTH_SHORT).show()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private fun applyScreenAwakeFlag(keepOn: Boolean) {
        val wm = windowManager ?: run {
            _isScreenHeldState.value = false
            if (keepOn) _awakeErrorState.value = "WindowManager not ready"
            return
        }

        var anyUpdated = false
        fun applyToView(view: View?, params: WindowManager.LayoutParams?): Boolean {
            if (view == null || params == null) return false
            val updated = if (keepOn) {
                params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            } else {
                params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            }
            if (params.flags != updated) {
                params.flags = updated
            }
            if (view.isAttachedToWindow) {
                return try {
                    wm.updateViewLayout(view, params)
                    true
                } catch (t: Throwable) {
                    Log.e(TAG, "Error updating FLAG_KEEP_SCREEN_ON on overlay", t)
                    false
                }
            }
            return false
        }

        try {
            if (applyToView(bubbleView, bubbleParams)) anyUpdated = true
            if (applyToView(sessionHudView, sessionHudParams)) anyUpdated = true
            if (applyToView(panelView, panelParams)) anyUpdated = true
            if (applyToView(settingsOverlayView, settingsOverlayParams)) anyUpdated = true

            if (keepOn) {
                if (anyUpdated) {
                    _isScreenHeldState.value = true
                    _awakeErrorState.value = null
                } else {
                    _isScreenHeldState.value = false
                    _awakeErrorState.value = "No attached overlay window"
                }
            } else {
                _isScreenHeldState.value = false
                _awakeErrorState.value = null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed applying keep screen awake flag", t)
            _isScreenHeldState.value = false
            _awakeErrorState.value = "Awake error: ${t.localizedMessage ?: "Unknown"}"
        }
    }

    @Suppress("WakelockTimeout")
    private fun applyCpuWakeLock(shouldHoldCpu: Boolean) {
        try {
            if (shouldHoldCpu) {
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                    wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PredictManager:CpuWakeLock")?.apply {
                        setReferenceCounted(false)
                    }
                }
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire(4 * 60 * 60 * 1000L)
                }
                _isCpuActiveState.value = wakeLock?.isHeld == true
            } else {
                releaseWakeLockSafely()
                _isCpuActiveState.value = false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed updating CPU wake lock", t)
            _isCpuActiveState.value = false
        }
    }

    private fun releaseWakeLockSafely() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed releasing wake lock", t)
        }
        _isCpuActiveState.value = false
    }

    /**
     * Draggable overlay container supporting unclamped off-screen edge parking via FLAG_LAYOUT_NO_LIMITS.
     * Passes scroll gestures inside `excludedScrollRect` cleanly to the bounded crash history grid.
     */
    private inner class DraggableOverlayLayout(
        context: Context,
        private val layoutParams: WindowManager.LayoutParams,
        private val onPositionSaved: ((Int, Int) -> Unit)? = null,
        private val onBackPressed: (() -> Unit)? = null
    ) : FrameLayout(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var downInsideScrollZone = false
        private var isDragging = false

        var excludedScrollRect: RectF? = null

        init {
            setViewTreeLifecycleOwner(this@ScreenEarnerOverlayService)
            setViewTreeViewModelStoreOwner(this@ScreenEarnerOverlayService)
            setViewTreeSavedStateRegistryOwner(this@ScreenEarnerOverlayService)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (onBackPressed != null &&
                event.keyCode == KeyEvent.KEYCODE_BACK &&
                event.action == KeyEvent.ACTION_UP
            ) {
                onBackPressed.invoke()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = ev.rawX
                    initialTouchY = ev.rawY
                    isDragging = false
                    val headerThresholdPx = 55f * context.resources.displayMetrics.density
                    downInsideScrollZone = ev.y > headerThresholdPx
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (downInsideScrollZone) return false
                    val dx = kotlin.math.abs(ev.rawX - initialTouchX)
                    val dy = kotlin.math.abs(ev.rawY - initialTouchY)
                    if (dx > touchSlop || dy > touchSlop) {
                        isDragging = true
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    downInsideScrollZone = false
                }
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (isDragging || kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                        isDragging = true
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        try {
                            windowManager?.updateViewLayout(this, layoutParams)
                        } catch (_: Throwable) {
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        onPositionSaved?.invoke(layoutParams.x, layoutParams.y)
                    }
                    isDragging = false
                    downInsideScrollZone = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    private fun overlayLayoutType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Predict Manager Floating Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs the floating live game observer bubble and background telemetry service."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, ScreenEarnerOverlayService::class.java).apply {
            action = "ACTION_STOP_MONITORING"
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PREDICT MANAGER — MONITORING ACTIVE")
            .setContentText("Observing live crash game history ribbon & background windows.")
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Monitoring", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun setupFloatingBubbleOverlay() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bubbleDp = prefs.getFloat(PREF_BUBBLE_SIZE_DP, 58f).coerceIn(44f, 80f)
        val bubbleOpacity = prefs.getFloat(PREF_BUBBLE_OPACITY, 0.95f).coerceIn(0.3f, 1.0f)

        val containerDp = (bubbleDp + 16f).toInt()
        val bubbleSizePx = (containerDp * resources.displayMetrics.density).toInt()

        bubbleParams = WindowManager.LayoutParams(
            bubbleSizePx,
            bubbleSizePx,
            overlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (isKeepAwakeEngaged) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
        }

        val container = FrameLayout(this).apply {
            setViewTreeLifecycleOwner(this@ScreenEarnerOverlayService)
            setViewTreeViewModelStoreOwner(this@ScreenEarnerOverlayService)
            setViewTreeSavedStateRegistryOwner(this@ScreenEarnerOverlayService)
        }

        val engine = AutomationController.engine
        val automationFlow = engine?.automationState ?: MutableStateFlow(AutomationState.IDLE)

        val composeView = ComposeView(this).apply {
            setContent {
                AutoPredictorTheme(darkTheme = false) {
                    val automationState by automationFlow.collectAsState()
                    FloatingMonitorBubbleComposable(
                        coreSizeDp = bubbleDp,
                        opacity = bubbleOpacity,
                        isRunningActive = automationState == AutomationState.RUNNING
                    )
                }
            }
        }

        container.addView(composeView)
        bubbleView = container

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoved = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams?.x ?: 0
                    initialY = bubbleParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val diffX = kotlin.math.abs(event.rawX - initialTouchX)
                    val diffY = kotlin.math.abs(event.rawY - initialTouchY)
                    if (diffX > touchSlop || diffY > touchSlop) {
                        isMoved = true
                        // Unclamped FLAG_LAYOUT_NO_LIMITS drag
                        bubbleParams?.x = initialX + (event.rawX - initialTouchX).toInt()
                        bubbleParams?.y = initialY + (event.rawY - initialTouchY).toInt()
                        bubbleParams?.let { params ->
                            try {
                                windowManager?.updateViewLayout(bubbleView, params)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed updating bubble position", e)
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isMoved) {
                        toggleCompactControlPanel()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(bubbleView, bubbleParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding bubble view", e)
        }
    }

    private fun setupSessionStatsHudOverlay() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hudOpacity = prefs.getFloat(PREF_BUBBLE_OPACITY, 0.95f).coerceIn(0.45f, 1.0f)
        val sessionMgr = SessionStateManager(this)
        val defaultX = (20 * resources.displayMetrics.density).toInt()
        val defaultY = (36 * resources.displayMetrics.density).toInt()
        val (savedX, savedY) = sessionMgr.loadTopPillPosition(defaultX, defaultY)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (isKeepAwakeEngaged) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }
        sessionHudParams = params

        val container = FrameLayout(this).apply {
            setViewTreeLifecycleOwner(this@ScreenEarnerOverlayService)
            setViewTreeViewModelStoreOwner(this@ScreenEarnerOverlayService)
            setViewTreeSavedStateRegistryOwner(this@ScreenEarnerOverlayService)
        }

        val engine = AutomationController.engine
        val statsFlow = engine?.sessionStats ?: MutableStateFlow(SessionStats())
        val automationFlow = engine?.automationState ?: MutableStateFlow(AutomationState.IDLE)
        val gameStateFlow = engine?.gameDetection?.gameState ?: MutableStateFlow(GameState())
        val sessionCrashRecordsFlow = AutomationController.activeSessionCrashRecords

        val composeView = ComposeView(this).apply {
            setContent {
                AutoPredictorTheme(darkTheme = false) {
                    val stats by statsFlow.collectAsState()
                    val autoState by automationFlow.collectAsState()
                    val gs by gameStateFlow.collectAsState()
                    val sessionCrashes by sessionCrashRecordsFlow.collectAsState()
                    val latestCrashVal = sessionCrashes.firstOrNull()?.multiplier ?: gs.latestCrashMultiplier
                    FloatingSessionStatsHudComposable(
                        stats = stats,
                        observedRounds = sessionCrashes.size,
                        automationState = autoState,
                        opacity = hudOpacity,
                        gameName = if (gs.isDetected) gs.displayedGameName else "Predict Manager",
                        liveMultiplier = gs.currentMultiplier,
                        liveDetectionLost = gs.liveDetectionLost,
                        latestCrashMultiplier = latestCrashVal,
                        latestRoundId = gs.latestRoundId
                    )
                }
            }
        }
        container.addView(composeView)
        sessionHudView = container

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragged = false

        container.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val diffX = kotlin.math.abs(event.rawX - initialTouchX)
                    val diffY = kotlin.math.abs(event.rawY - initialTouchY)
                    if (diffX > touchSlop || diffY > touchSlop) {
                        isDragged = true
                        // Unclamped drag so user can park pill freely anywhere
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager?.updateViewLayout(v, params)
                        } catch (_: Throwable) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragged) {
                        sessionMgr.saveTopPillPosition(params.x, params.y)
                    } else {
                        toggleCompactControlPanel()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(container, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding session HUD view", e)
        }
    }

    private fun toggleCompactControlPanel() {
        if (isPanelVisible) {
            removeCompactControlPanel()
        } else {
            showCompactControlPanel()
        }
    }

    /**
     * Resizable, repositionable Compact/Expanded Predict Manager Live Monitor floating card.
     * Hiding the panel keeps foreground monitoring active; provides quick navigation to
     * Strategies, History, Hourly, and Settings without interrupting background capture.
     */
    private fun showCompactControlPanel() {
        if (isPanelVisible) return

        val sessionMgr = SessionStateManager(this)
        val bx = bubbleParams?.x ?: 24
        val by = bubbleParams?.y ?: 240
        val (savedPanelX, savedPanelY) = sessionMgr.loadPanelPosition(bx + 48, (by - 20).coerceAtLeast(32))
        val (initialWidthDp, initialHeightDp) = sessionMgr.loadPanelSize(defaultWidthDp = 280, defaultHeightDp = 360)
        val initialCompact = sessionMgr.isPanelCompactMode()

        val basePanelFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (isKeepAwakeEngaged) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayLayoutType(),
            basePanelFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedPanelX
            y = savedPanelY
        }
        panelParams = params

        val container = DraggableOverlayLayout(
            context = this,
            layoutParams = params,
            onPositionSaved = { px, py -> sessionMgr.savePanelPosition(px, py) }
        )

        val engine = AutomationController.engine
        val repo = AutomationController.repository
        val gameStateFlow = engine?.gameDetection?.gameState ?: MutableStateFlow(GameState())
        val automationStateFlow = engine?.automationState ?: MutableStateFlow(AutomationState.IDLE)
        val tickerFlow = engine?.agentStatusTicker ?: MutableStateFlow("Scanning for crash game...")
        val standbyFlow = engine?.isStandbyOutsideSchedule ?: MutableStateFlow(false)
        val sessionCrashRecordsFlow = AutomationController.activeSessionCrashRecords
        val density = resources.displayMetrics.density

        val composeView = ComposeView(this).apply {
            setContent {
                AutoPredictorTheme(darkTheme = false) {
                    val gameState by gameStateFlow.collectAsState()
                    val automationState by automationStateFlow.collectAsState()
                    val agentTicker by tickerFlow.collectAsState()
                    val isStandby by standbyFlow.collectAsState()
                    val activeConfig by AutomationController.latestConfig.collectAsState()
                    val agentExplanation by AutomationController.smartAgentExplanation.collectAsState()
                    val allCrashRecords by sessionCrashRecordsFlow.collectAsState()
                    val selectedGameId by AutomationController.selectedGameId.collectAsState()
                    val bannerMsg by statusBannerMessage.collectAsState()
                    val isScreenHeld by isScreenHeldState.collectAsState()
                    val awakeError by awakeErrorState.collectAsState()
                    val isAlarmRinging by AlarmAudioController.isAlarmRinging.collectAsState()
                    val activeAlarmTitle by AlarmAudioController.activeAlarmTitle.collectAsState()
                    var showExitConfirmation by remember { mutableStateOf(false) }
                    var panelWidthDp by remember { mutableStateOf(initialWidthDp) }
                    var panelHeightDp by remember { mutableStateOf(initialHeightDp) }
                    var isCompact by remember { mutableStateOf(initialCompact) }

                    val scopedCrashes = remember(allCrashRecords, gameState.isDetected, gameState.gameId, selectedGameId) {
                        val targetId = if (gameState.isDetected) gameState.gameId else selectedGameId
                        if (targetId == "ALL") allCrashRecords else allCrashRecords.filter { it.gameId == targetId }
                    }
                    val isRunningMonitor = automationState == AutomationState.RUNNING

                    Card(
                        modifier = Modifier
                            .width(panelWidthDp.dp)
                            .heightIn(max = if (isCompact) 240.dp else panelHeightDp.dp)
                            .shadow(14.dp, RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .testTag("overlay_monitor_panel"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Header bar with drag grip, Compact/Expand toggle & Close (hide panel only)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DragIndicator,
                                        contentDescription = "Drag Utility Panel",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(
                                                if (isRunningMonitor) ActionStartEmerald else Color(0xFF94A3B8),
                                                CircleShape
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "PREDICT MANAGER MONITOR",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 10.sp,
                                        letterSpacing = 0.4.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            isCompact = !isCompact
                                            sessionMgr.savePanelCompactMode(isCompact)
                                        },
                                        modifier = Modifier
                                            .size(26.dp)
                                            .testTag("overlay_compact_toggle_btn")
                                    ) {
                                        Icon(
                                            imageVector = if (isCompact) Icons.Default.Fullscreen else Icons.Default.FullscreenExit,
                                            contentDescription = if (isCompact) "Expand Monitor Panel" else "Compact Monitor Panel",
                                            tint = PrimaryCobalt,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { removeCompactControlPanel() },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Hide Panel",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            if (isAlarmRinging) {
                                Surface(
                                    color = Color(0xFFFEE2E2),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, ActionStopRed),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("overlay_active_alarm_banner")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = activeAlarmTitle ?: "ALARM RINGING",
                                            color = ActionStopRed,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Button(
                                            onClick = { AlarmAudioController.stopAlarm(this@ScreenEarnerOverlayService) },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = ActionStopRed,
                                                contentColor = Color.White
                                            ),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier
                                                .height(30.dp)
                                                .testTag("overlay_stop_alarm_btn")
                                        ) {
                                            Text("STOP ALARM", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                                        }
                                    }
                                }
                            }

                            // Above-Grid Live Detection Header
                            LiveCrashHeaderCard(
                                gameState = gameState,
                                automationState = automationState
                            )

                            if (!isCompact) {
                                // Bounded Current-Session Crash History Grid
                                BoundedCrashHistoryGrid(
                                    crashRecords = scopedCrashes,
                                    gridRows = activeConfig.gridRows,
                                    gridColumns = activeConfig.gridColumns,
                                    compactMode = true,
                                    modifier = Modifier.onGloballyPositioned { coords ->
                                        val b = coords.boundsInWindow()
                                        container.excludedScrollRect = RectF(b.left, b.top, b.right, b.bottom)
                                    }
                                )

                                // Truthful Awake + Schedule controls
                                Surface(
                                    color = if (isStandby) Color(0xFFFEF3C7) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = if (isStandby) agentTicker else agentExplanation.liveStatusLine,
                                            color = if (isStandby) Color(0xFF92400E) else MaterialTheme.colorScheme.onSurface,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            // Req 5: NEVER display ON unless verified hardware lock/flag is active
                                            val awakeLabel = when {
                                                awakeError != null -> "Awake Error"
                                                isScreenHeld -> "Awake: ON"
                                                else -> "Awake: OFF"
                                            }
                                            OverlayPillChip(
                                                label = awakeLabel,
                                                selected = isScreenHeld,
                                                activeColor = ActionStartEmerald,
                                                onClick = {
                                                    val updated = activeConfig.copy(keepScreenAwake = !activeConfig.keepScreenAwake)
                                                    serviceScope.launch {
                                                        runCatching { repo?.saveStrategyConfig(updated) }
                                                    }
                                                },
                                                modifier = Modifier.weight(1f)
                                            )
                                            val schedLabel = if (activeConfig.scheduleEnabled) {
                                                OperatingScheduleEvaluator.formatWindow(
                                                    activeConfig.scheduleStartHour,
                                                    activeConfig.scheduleStartMinute,
                                                    activeConfig.scheduleEndHour,
                                                    activeConfig.scheduleEndMinute
                                                )
                                            } else {
                                                "Sched: 24/7"
                                            }
                                            OverlayPillChip(
                                                label = schedLabel,
                                                selected = activeConfig.scheduleEnabled,
                                                activeColor = PrimaryCobalt,
                                                onClick = {
                                                    val updated = activeConfig.copy(scheduleEnabled = !activeConfig.scheduleEnabled)
                                                    serviceScope.launch {
                                                        runCatching { repo?.saveStrategyConfig(updated) }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            } else {
                                container.excludedScrollRect = null
                            }

                            if (!bannerMsg.isNullOrBlank()) {
                                Surface(
                                    color = Color(0xFFFEE2E2),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = bannerMsg ?: "",
                                        color = ActionStopRed,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(6.dp)
                                    )
                                }
                            }

                            if (showExitConfirmation) {
                                Surface(
                                    color = Color(0xFFFEF2F2),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, ActionStopRed.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("overlay_exit_confirm_box")
                                ) {
                                    Column(
                                        modifier = Modifier.padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "Exit Predict Manager and stop monitoring?",
                                            color = Color(0xFF7F1D1D),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            OutlinedButton(
                                                onClick = { showExitConfirmation = false },
                                                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 8.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(34.dp)
                                                    .testTag("overlay_exit_cancel_btn")
                                            ) {
                                                Text("Cancel", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                            Button(
                                                onClick = { performFullApplicationExit() },
                                                colors = ButtonDefaults.buttonColors(containerColor = ActionStopRed),
                                                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 8.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(34.dp)
                                                    .testTag("overlay_exit_confirm_btn")
                                            ) {
                                                Text("Exit", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }

                            // Quick in-game access shortcuts (monitoring stays running when jumping between screens)
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OverlayMenuButton(
                                        label = if (isRunningMonitor) "PAUSE" else "MONITOR",
                                        icon = if (isRunningMonitor) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        color = if (isRunningMonitor) ActionStopRed else ActionStartEmerald,
                                        onClick = { handleOverlayStartStop() },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("overlay_pause_btn")
                                    )
                                    OverlayMenuButton(
                                        label = "STRATEGIES",
                                        icon = Icons.Default.Tune,
                                        color = PrimaryCobalt,
                                        onClick = {
                                            removeCompactControlPanel()
                                            openMainActivity(MainActivity.TAB_STRATEGY)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OverlayMenuButton(
                                        label = "HISTORY",
                                        icon = Icons.Default.History,
                                        color = Color(0xFF0284C7),
                                        onClick = {
                                            removeCompactControlPanel()
                                            openMainActivity(MainActivity.TAB_CRASH_HISTORY)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    OverlayMenuButton(
                                        label = "SETTINGS",
                                        icon = Icons.Default.Settings,
                                        color = Color(0xFF475569),
                                        onClick = {
                                            removeCompactControlPanel()
                                            showSettingsOverlayPanel()
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("overlay_open_settings_btn")
                                    )
                                }

                                if (!isCompact) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OverlayMenuButton(
                                            label = "PATTERNS",
                                            icon = Icons.Default.Analytics,
                                            color = PrimaryCobalt,
                                            onClick = {
                                                removeCompactControlPanel()
                                                openMainActivity(MainActivity.TAB_PATTERNS)
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                        OverlayMenuButton(
                                            label = "HOURLY",
                                            icon = Icons.Default.Schedule,
                                            color = Color(0xFF0D9488),
                                            onClick = {
                                                removeCompactControlPanel()
                                                openMainActivity(MainActivity.TAB_HOURLY_ANALYSIS)
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OverlayMenuButton(
                                        label = "HIDE",
                                        icon = Icons.Default.Close,
                                        color = Color(0xFF64748B),
                                        onClick = { removeCompactControlPanel() },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("overlay_close_btn")
                                    )
                                    OverlayMenuButton(
                                        label = "EXIT",
                                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                                        color = Color(0xFF991B1B),
                                        onClick = {
                                            if (isRunningMonitor) {
                                                showExitConfirmation = true
                                            } else {
                                                performFullApplicationExit()
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("overlay_exit_btn")
                                    )
                                    // Bottom-right drag resize handle (Req 4)
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier
                                            .size(34.dp)
                                            .pointerInput(Unit) {
                                                detectDragGestures(
                                                    onDragEnd = {
                                                        sessionMgr.savePanelSize(panelWidthDp, panelHeightDp)
                                                    }
                                                ) { change, dragAmount ->
                                                    change.consume()
                                                    val dW = (dragAmount.x / density).roundToInt()
                                                    val dH = (dragAmount.y / density).roundToInt()
                                                    panelWidthDp = (panelWidthDp + dW).coerceIn(220, 420)
                                                    panelHeightDp = (panelHeightDp + dH).coerceIn(180, 560)
                                                }
                                            }
                                            .testTag("overlay_resize_handle")
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.OpenInFull,
                                                contentDescription = "Drag to Resize Panel",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        container.addView(composeView)
        panelView = container

        try {
            windowManager?.addView(panelView, params)
            isPanelVisible = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding compact control panel", e)
        }
    }

    private fun handleOverlayStartStop() {
        val engine = AutomationController.engine ?: return
        if (engine.automationState.value == AutomationState.RUNNING) {
            AutomationController.pauseMonitoringSession()
            statusBannerMessage.value = null
            triggerHapticVibration()
            return
        }

        val result = AutomationController.startMonitoringSession(
            isAccessibilityConnected = ScreenEarnerAccessibilityService.isServiceConnected()
        )
        triggerHapticVibration()
        if (!result.started) {
            statusBannerMessage.value = result.message
            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
        } else {
            statusBannerMessage.value = null
            Toast.makeText(this, "Predict Manager Live Monitor ACTIVE", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performFullApplicationExit() {
        try {
            AutomationController.stopMonitoringSession()
            BatteryOptimizationWorker.cancel(applicationContext)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancelAll()
        } catch (t: Throwable) {
            Log.e(TAG, "Error stopping monitoring state during exit", t)
        }

        _isScreenHeldState.value = false
        AutomationController.engine?.gameDetection?.updateScreenAwakeState(false, false)
        removeAllOverlayViews()
        releaseWakeLockSafely()
        isRunning = false

        runCatching { stopService(ScreenCaptureProjectionService.createStopIntent(applicationContext)) }
        runCatching { stopService(Intent(applicationContext, FloatingBubbleService::class.java)) }
        runCatching { stopService(Intent(applicationContext, FloatingOverlayService::class.java)) }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }

        runCatching {
            val exitActivityIntent = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_EXIT_APP
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(exitActivityIntent)
        }

        stopSelf()
    }

    private fun showSettingsOverlayPanel() {
        if (isSettingsOverlayVisible) return

        val density = resources.displayMetrics.density
        val screenWidthDp = resources.displayMetrics.widthPixels / density
        val screenHeightDp = resources.displayMetrics.heightPixels / density
        val cardWidthDp = minOf(screenWidthDp * 0.92f, 410f).coerceAtLeast(280f)
        val cardMaxHeightDp = minOf(screenHeightDp * 0.74f, 620f).coerceAtLeast(320f)

        val settingsFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            (if (isKeepAwakeEngaged) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayLayoutType(),
            settingsFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = (44 * density).toInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        settingsOverlayParams = params

        val container = DraggableOverlayLayout(
            context = this,
            layoutParams = params,
            onBackPressed = { removeSettingsOverlayPanel() }
        )

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val initialOpacity = prefs.getFloat(PREF_BUBBLE_OPACITY, 0.95f).coerceIn(0.3f, 1.0f)
        val initialSizeDp = prefs.getFloat(PREF_BUBBLE_SIZE_DP, 58f).coerceIn(44f, 80f)
        val initialHaptics = prefs.getBoolean(PREF_HAPTICS_ENABLED, true)

        val engine = AutomationController.engine
        val repo = AutomationController.repository
        val automationStateFlow = engine?.automationState ?: MutableStateFlow(AutomationState.IDLE)
        val standbyFlow = engine?.isStandbyOutsideSchedule ?: MutableStateFlow(false)

        val composeView = ComposeView(this).apply {
            setContent {
                AutoPredictorTheme(darkTheme = false) {
                    val activeConfig by AutomationController.latestConfig.collectAsState()
                    val activeNotif by AutomationController.latestNotificationSettings.collectAsState()
                    val automationState by automationStateFlow.collectAsState()
                    val isStandby by standbyFlow.collectAsState()
                    val isScreenHeld by isScreenHeldState.collectAsState()
                    val isCpuHeld by isCpuActiveState.collectAsState()
                    val awakeError by awakeErrorState.collectAsState()
                    val isAlarmRinging by AlarmAudioController.isAlarmRinging.collectAsState()
                    val activeAlarmTitle by AlarmAudioController.activeAlarmTitle.collectAsState()

                    OverlaySettingsPanelComposable(
                        strategyConfig = activeConfig,
                        notificationSettings = activeNotif,
                        isStandbyOutsideSchedule = isStandby,
                        isScreenHeld = isScreenHeld,
                        isCpuAwakeHeld = isCpuHeld,
                        awakeError = awakeError,
                        isMonitoringRunning = automationState == AutomationState.RUNNING,
                        isAlarmRinging = isAlarmRinging,
                        activeAlarmTitle = activeAlarmTitle,
                        initialBubbleOpacity = initialOpacity,
                        initialBubbleSizeDp = initialSizeDp,
                        initialHapticsEnabled = initialHaptics,
                        onStrategyConfigChanged = { updated ->
                            serviceScope.launch {
                                runCatching { repo?.saveStrategyConfig(updated) }
                            }
                        },
                        onNotificationSettingsChanged = { updatedNotif ->
                            serviceScope.launch {
                                runCatching { repo?.saveNotificationSettings(updatedNotif) }
                            }
                        },
                        onSaveOverlaySettings = { updatedConfig, updatedNotif, newOpacity, newBubbleSizeDp, newHaptics ->
                            prefs.edit()
                                .putFloat(PREF_BUBBLE_OPACITY, newOpacity)
                                .putFloat(PREF_BUBBLE_SIZE_DP, newBubbleSizeDp)
                                .putBoolean(PREF_HAPTICS_ENABLED, newHaptics)
                                .apply()
                            serviceScope.launch {
                                runCatching { repo?.saveNotificationSettings(updatedNotif) }
                                runCatching { repo?.saveStrategyConfig(updatedConfig) }
                            }
                            refreshFloatingOverlayAppearance()
                            runCatching {
                                Toast.makeText(
                                    this@ScreenEarnerOverlayService,
                                    "Overlay settings saved",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            removeSettingsOverlayPanel()
                        },
                        onResetSessionStats = {
                            engine?.resetSessionStats()
                            runCatching {
                                Toast.makeText(
                                    this@ScreenEarnerOverlayService,
                                    "Session stats reset",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onStopAlarm = { AlarmAudioController.stopAlarm(this@ScreenEarnerOverlayService) },
                        onSnoozeAlarm = { AlarmAudioController.snoozeAlarm(5) },
                        onTestAlarm = { notif ->
                            AlarmAudioController.testSignalAlarm(this@ScreenEarnerOverlayService, notif)
                        },
                        onBackToMonitorPanel = {
                            removeSettingsOverlayPanel()
                            showCompactControlPanel()
                        },
                        onExpandFullScreen = {
                            removeSettingsOverlayPanel()
                            openMainActivity(MainActivity.TAB_SETTINGS)
                        },
                        onClose = { removeSettingsOverlayPanel() },
                        modifier = Modifier
                            .width(cardWidthDp.dp)
                            .heightIn(max = cardMaxHeightDp.dp)
                    )
                }
            }
        }

        container.addView(composeView)
        settingsOverlayView = container

        try {
            windowManager?.addView(container, params)
            isSettingsOverlayVisible = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding overlay settings panel", e)
            settingsOverlayView = null
            settingsOverlayParams = null
            isSettingsOverlayVisible = false
        }
    }

    private fun removeSettingsOverlayPanel() {
        if (!isSettingsOverlayVisible && settingsOverlayView == null) return
        settingsOverlayView?.let { settingsView ->
            runCatching { windowManager?.removeViewImmediate(settingsView) }
                .onFailure { runCatching { windowManager?.removeView(settingsView) } }
        }
        settingsOverlayView = null
        settingsOverlayParams = null
        isSettingsOverlayVisible = false
    }

    private fun refreshFloatingOverlayAppearance() {
        val savedBubbleX = bubbleParams?.x
        val savedBubbleY = bubbleParams?.y
        bubbleView?.let { bubble ->
            runCatching { windowManager?.removeViewImmediate(bubble) }
                .onFailure { runCatching { windowManager?.removeView(bubble) } }
            bubbleView = null
            bubbleParams = null
        }
        setupFloatingBubbleOverlay()
        if (savedBubbleX != null && savedBubbleY != null) {
            bubbleParams?.let { params ->
                params.x = savedBubbleX
                params.y = savedBubbleY
                bubbleView?.let { view ->
                    runCatching { windowManager?.updateViewLayout(view, params) }
                }
            }
        }

        sessionHudView?.let { hud ->
            runCatching { windowManager?.removeViewImmediate(hud) }
                .onFailure { runCatching { windowManager?.removeView(hud) } }
            sessionHudView = null
            sessionHudParams = null
        }
        setupSessionStatsHudOverlay()
    }

    private fun removeAllOverlayViews() {
        removeCompactControlPanel()
        removeSettingsOverlayPanel()
        sessionHudView?.let { hud ->
            runCatching { windowManager?.removeViewImmediate(hud) }
                .onFailure { runCatching { windowManager?.removeView(hud) } }
            sessionHudView = null
            sessionHudParams = null
        }
        bubbleView?.let { bubble ->
            runCatching { windowManager?.removeViewImmediate(bubble) }
                .onFailure { runCatching { windowManager?.removeView(bubble) } }
            bubbleView = null
            bubbleParams = null
        }
    }

    private fun removeCompactControlPanel() {
        if (!isPanelVisible && panelView == null) return
        panelView?.let { panel ->
            runCatching { windowManager?.removeViewImmediate(panel) }
                .onFailure { runCatching { windowManager?.removeView(panel) } }
        }
        panelView = null
        panelParams = null
        statusBannerMessage.value = null
        isPanelVisible = false
    }

    private fun openMainActivity(tabIndex: Int = MainActivity.TAB_HOME) {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_TAB, tabIndex)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed opening MainActivity", e)
            Toast.makeText(this, "Unable to open screen", Toast.LENGTH_SHORT).show()
        }
    }

    private fun triggerHapticVibration() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PREF_HAPTICS_ENABLED, true)) return

            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(40)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Haptic vibration failed", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AccessibilityController.gestureOverlayPassThroughListener = null
        _isScreenHeldState.value = false
        AutomationController.engine?.gameDetection?.updateScreenAwakeState(false, false)
        releaseWakeLockSafely()
        serviceScope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        isRunning = false
        removeAllOverlayViews()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }
}

/**
 * Compact Light Material 3 observation-only top floating status pill.
 * Strictly renders ONLY `LAST CRASH: <val or —>` and `ROUND: <roundId or Unknown>`
 * (plus limit-halt indicator if halted by configured safety lock). Never renders live moving multiplier.
 */
@Composable
fun FloatingSessionStatsHudComposable(
    stats: SessionStats,
    observedRounds: Int,
    automationState: AutomationState,
    opacity: Float = 0.94f,
    gameName: String = "Predict Manager",
    liveMultiplier: Double = 0.0,
    liveDetectionLost: Boolean = false,
    latestCrashMultiplier: Double? = null,
    latestRoundId: String? = null
) {
    val currency by com.example.data.engine.CurrencyManager.activeCurrency.collectAsState()
    val isHaltedByLimit = !stats.limitHaltReason.isNullOrBlank()
    val borderColor = when {
        isHaltedByLimit || liveDetectionLost -> ActionStopRed
        automationState == AutomationState.RUNNING -> ActionStartEmerald
        automationState == AutomationState.PAUSED -> ActionCashOutAmber
        else -> PrimaryCobalt.copy(alpha = 0.7f)
    }
    val statusDotColor = when {
        isHaltedByLimit || liveDetectionLost -> ActionStopRed
        automationState == AutomationState.RUNNING -> ActionStartEmerald
        automationState == AutomationState.PAUSED -> ActionCashOutAmber
        else -> PrimaryCobalt
    }

    val lastCrashLabel = latestCrashMultiplier
        ?.takeIf { it.isFinite() && it >= 1.0 }
        ?.let { "LAST CRASH: ${String.format(Locale.US, "%.2fx", it)}" }
        ?: "LAST CRASH: —"
    val roundLabel = latestRoundId
        ?.takeIf { it.isNotBlank() }
        ?.let { "ROUND: $it" }
        ?: "ROUND: Unknown"
    val currencyDisplay = "Rounds: $observedRounds • ${currency.symbol} ${String.format(Locale.US, "%.2f", stats.totalProfit + 1000.0)}"

    Surface(
        color = Color(0xF5FFFFFF),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .alpha(opacity)
            .shadow(6.dp, RoundedCornerShape(14.dp))
            .border(1.2.dp, borderColor, RoundedCornerShape(14.dp))
            .testTag("top_status_pill")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(statusDotColor, CircleShape)
                )
                Text(
                    text = lastCrashLabel,
                    color = if (liveDetectionLost) ActionStopRed else PrimaryCobalt,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("top_pill_last_crash")
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(11.dp)
                        .background(Color(0xFFCBD5E1))
                )
                Text(
                    text = roundLabel,
                    color = Color(0xFF334155),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("top_pill_round")
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(11.dp)
                        .background(Color(0xFFCBD5E1))
                )
                Text(
                    text = currencyDisplay,
                    color = Color(0xFF047857),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("top_pill_currency")
                )
            }

            if (isHaltedByLimit) {
                Text(
                    text = "PAUSED: ${stats.limitHaltReason}",
                    color = ActionStopRed,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun OverlayPillChip(
    label: String,
    selected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (selected) activeColor else Color(0xFFE2E8F0),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color(0xFF334155),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun OverlayMenuButton(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 6.dp),
        modifier = modifier.height(34.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(13.dp),
                tint = Color.White
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun FloatingMonitorBubbleComposable(
    coreSizeDp: Float = 58f,
    opacity: Float = 0.95f,
    isRunningActive: Boolean = false
) {
    val containerDp = (coreSizeDp + 16f).dp
    Box(
        modifier = Modifier
            .size(containerDp)
            .alpha(opacity)
            .testTag("floating_monitor_bubble"),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(coreSizeDp.dp)
                .shadow(10.dp, CircleShape)
                .background(PrimaryCobalt, CircleShape)
                .border(
                    width = if (isRunningActive) 3.dp else 2.dp,
                    color = if (isRunningActive) ActionStartEmerald else Color.White,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "PM",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 19.sp
            )
        }

        if (isRunningActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(12.dp)
                    .background(ActionStartEmerald, CircleShape)
                    .border(1.5.dp, Color.White, CircleShape)
            )
        }
    }
}
