package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.engine.ActionType
import com.example.data.engine.StrategyEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * TaskRobotSettings - Per-button configuration
 */
data class TaskRobotSettings(
    var label: String,
    var color: Int,
    var stakeOrMultiplier: Double = 0.0
)

/**
 * FloatingBubbleService - Lightweight, non-intrusive floating windows
 */
class FloatingBubbleService : Service() {

    companion object {
        private const val TAG = "FloatingBubbleService"
        private const val PREFS_NAME = "floating_bubble_prefs"
        const val CHANNEL_ID = "FloatingBubbleChannel"
        const val NOTIFICATION_ID = 1002
    }

    private lateinit var windowManager: WindowManager
    private var mainBubbleView: View? = null
    private var expandedMenuView: View? = null

    private val independentButtons = mutableListOf<View>()
    private val buttonSettings = mutableMapOf<View, TaskRobotSettings>()
    private var isMenuExpanded = false
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            Log.w("FloatingBubbleService", "Display over other apps permission missing. Stopping service.")
            Toast.makeText(this, "Display over other apps permission required.", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createMainBubble()

        serviceScope.launch {
            StrategyEngine.actionEvent.collect { event ->
                if (event != null) {
                    val targetText = if (event.actionType == ActionType.BET) "B" else "C"
                    independentButtons.forEach { btn ->
                        if ((btn as? Button)?.text == targetText) {
                            btn.animate()
                                .scaleX(1.22f)
                                .scaleY(1.22f)
                                .setDuration(160)
                                .withEndAction {
                                    btn.animate().scaleX(1.0f).scaleY(1.0f).setDuration(160).start()
                                }
                                .start()
                        }
                    }
                    mainBubbleView?.animate()
                        ?.scaleX(1.18f)
                        ?.scaleY(1.18f)
                        ?.setDuration(150)
                        ?.withEndAction {
                            mainBubbleView?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(150)?.start()
                        }
                        ?.start()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Predict Manager Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Predict Manager Active")
            .setContentText("Non-intrusive floating monitor.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createMainBubble() {
        val bubbleSize = (56 * resources.displayMetrics.density).toInt()

        val params = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        val bubbleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.rounded_bubble_bg)
            isClickable = true
            isFocusable = true
        }

        val textView = TextView(this).apply {
            text = "PM"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        bubbleLayout.addView(textView)

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        bubbleLayout.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var startTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        startTime = System.currentTimeMillis()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                            params.x = initialX + dx.toInt()
                            params.y = initialY + dy.toInt()
                            try {
                                windowManager.updateViewLayout(v, params)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed updating main bubble layout", e)
                            }
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - startTime
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (duration < 200 && Math.abs(dx) < touchSlop && Math.abs(dy) < touchSlop) {
                            toggleExpandedMenu(params.x, params.y)
                            return true
                        }
                    }
                }
                return false
            }
        })

        mainBubbleView = bubbleLayout
        try {
            windowManager.addView(mainBubbleView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding main bubble view", e)
        }
    }

    private fun toggleExpandedMenu(bubbleX: Int, bubbleY: Int) {
        if (isMenuExpanded) {
            expandedMenuView?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed removing expanded menu", e)
                }
                expandedMenuView = null
            }
            isMenuExpanded = false
        } else {
            val menuWidth = (240 * resources.displayMetrics.density).toInt()
            val menuHeight = (280 * resources.displayMetrics.density).toInt()

            val menuParams = WindowManager.LayoutParams(
                menuWidth,
                menuHeight,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = bubbleX + (60 * resources.displayMetrics.density).toInt()
                y = bubbleY
            }

            val menuLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(android.graphics.Color.parseColor("#E4E1E9"))
                setPadding(24, 24, 24, 24)
            }

            val addBetBtn = Button(this).apply {
                text = "Add Bet"
                setBackgroundColor(android.graphics.Color.parseColor("#16A34A"))
                setTextColor(android.graphics.Color.WHITE)
                setOnClickListener {
                    createIndependentTaskButton("BET", android.graphics.Color.parseColor("#16A34A"), TaskRobotSettings("BET", android.graphics.Color.parseColor("#16A34A")))
                }
            }
            menuLayout.addView(addBetBtn)

            val addCashOutBtn = Button(this).apply {
                text = "Add Cash Out"
                setBackgroundColor(android.graphics.Color.parseColor("#F59E0B"))
                setTextColor(android.graphics.Color.WHITE)
                setOnClickListener {
                    createIndependentTaskButton("CASH OUT", android.graphics.Color.parseColor("#F59E0B"), TaskRobotSettings("CASH OUT", android.graphics.Color.parseColor("#F59E0B")))
                }
            }
            menuLayout.addView(addCashOutBtn)

            expandedMenuView = menuLayout
            try {
                windowManager.addView(expandedMenuView, menuParams)
                isMenuExpanded = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed showing expanded menu", e)
            }
        }
    }

    private fun saveButtonSettings(button: View, settings: TaskRobotSettings) {
        try {
            if (settings.label.isBlank() ||
                !settings.stakeOrMultiplier.isFinite() ||
                settings.stakeOrMultiplier < 0.0
            ) {
                Toast.makeText(
                    this,
                    "Failed to save settings, please check input",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            buttonSettings[button] = settings
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat("val_${settings.label}", settings.stakeOrMultiplier.toFloat())
                .apply()
            toggleExpandedMenu(0, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Save failed in FloatingBubbleService", e)
            Toast.makeText(
                this,
                "Failed to save settings, please check input",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun createIndependentTaskButton(label: String, color: Int, settings: TaskRobotSettings) {
        val btnSize = (48 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            btnSize,
            btnSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 200 + (independentButtons.size * 60)
            y = 400
        }

        val button = Button(this).apply {
            text = if (label == "BET") "B" else "C"
            setBackgroundColor(color)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            setOnClickListener {
                val currentSettings = buttonSettings[this] ?: settings
                saveButtonSettings(this, currentSettings)
            }
        }
        buttonSettings[button] = settings

        button.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager.updateViewLayout(v, params)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed updating button position", e)
                        }
                        return true
                    }
                }
                return false
            }
        })

        independentButtons.add(button)
        try {
            windowManager.addView(button, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding task button", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        mainBubbleView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { Log.e(TAG, "Remove bubble failed", e) }
        }
        mainBubbleView = null
        expandedMenuView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { Log.e(TAG, "Remove menu failed", e) }
        }
        expandedMenuView = null
        independentButtons.forEach {
            try { windowManager.removeView(it) } catch (e: Exception) { Log.e(TAG, "Remove button failed", e) }
        }
        independentButtons.clear()
        buttonSettings.clear()
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
