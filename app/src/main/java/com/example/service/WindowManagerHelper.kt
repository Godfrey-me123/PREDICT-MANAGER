package com.example.service

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.R

class WindowManagerHelper(private val context: Context) {

    companion object {
        private const val TAG = "WindowManagerHelper"
    }

    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private var mainBubbleView: View? = null
    private var mainBubbleParams: WindowManager.LayoutParams? = null

    private var expandedMenuView: View? = null
    private var expandedMenuParams: WindowManager.LayoutParams? = null
    private var isMenuExpanded = false

    private val independentRobotViews = mutableListOf<View>()

    private val density: Float = context.resources.displayMetrics.density

    private fun dpToPx(dp: Int): Int = (dp * density).toInt()

    fun createOverlayLayoutParams(widthPx: Int, heightPx: Int, initialX: Int, initialY: Int): WindowManager.LayoutParams {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            widthPx,
            heightPx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }
    }

    fun createMainBubble(
        onSingleTap: (x: Int, y: Int) -> Unit,
        onLongPress: () -> Unit
    ) {
        if (mainBubbleView != null) return

        val sizePx = dpToPx(56)
        val params = createOverlayLayoutParams(sizePx, sizePx, 100, 200)
        mainBubbleParams = params

        val bubbleLayout = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.bg_floating_bubble)
            elevation = dpToPx(8).toFloat()
        }

        val imageView = ImageView(context).apply {
            setImageResource(R.drawable.ic_se_logo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
        }
        bubbleLayout.addView(
            imageView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoved = false
        var isLongPressed = false

        val handler = Handler(Looper.getMainLooper())
        val longPressRunnable = Runnable {
            isLongPressed = true
            triggerHapticVibration()
            onLongPress()
        }

        bubbleLayout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isMoved = false
                    isLongPressed = false
                    handler.postDelayed(longPressRunnable, 800)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
                        isMoved = true
                        handler.removeCallbacks(longPressRunnable)
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try {
                            windowManager?.updateViewLayout(mainBubbleView, params)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed updating main bubble layout", e)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (!isMoved && !isLongPressed) {
                        onSingleTap(params.x, params.y)
                    }
                    true
                }
                else -> false
            }
        }

        mainBubbleView = bubbleLayout
        try {
            windowManager?.addView(mainBubbleView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding main bubble", e)
        }
    }

    fun toggleExpandedMenu(
        bubbleX: Int,
        bubbleY: Int,
        onAddBetClicked: () -> Unit,
        onAddCashOutClicked: () -> Unit,
        onStartClicked: () -> Unit,
        onPauseClicked: () -> Unit,
        onQuitClicked: () -> Unit
    ) {
        if (isMenuExpanded) {
            closeExpandedMenu()
        } else {
            openExpandedMenu(bubbleX, bubbleY, onAddBetClicked, onAddCashOutClicked, onStartClicked, onPauseClicked, onQuitClicked)
        }
    }

    fun openExpandedMenu(
        bubbleX: Int,
        bubbleY: Int,
        onAddBetClicked: () -> Unit,
        onAddCashOutClicked: () -> Unit,
        onStartClicked: () -> Unit,
        onPauseClicked: () -> Unit,
        onQuitClicked: () -> Unit
    ) {
        if (expandedMenuView != null) return

        val widthPx = dpToPx(260)
        val params = createOverlayLayoutParams(widthPx, WindowManager.LayoutParams.WRAP_CONTENT, bubbleX + 70, bubbleY)
        expandedMenuParams = params

        val menuContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE1D2026.toInt()) // Dark MD3 Surface
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            elevation = dpToPx(12).toFloat()
        }

        val titleText = TextView(context).apply {
            text = "PREDICT MANAGER"
            setTextColor(0xFFE4E1E9.toInt())
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }
        menuContainer.addView(titleText)

        val statusText = TextView(context).apply {
            text = "1.00x | BETTING OPEN"
            setTextColor(0xFF16A34A.toInt())
            textSize = 12f
            setPadding(0, dpToPx(4), 0, dpToPx(12))
        }
        menuContainer.addView(statusText)

        fun addMenuActionButton(label: String, bgColor: Int, onClick: () -> Unit) {
            val btn = TextView(context).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(bgColor)
                setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setOnClickListener { onClick() }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dpToPx(3), 0, dpToPx(3)) }
            menuContainer.addView(btn, lp)
        }

        addMenuActionButton("ADD BET", 0xFF16A34A.toInt()) {
            onAddBetClicked()
            closeExpandedMenu()
        }
        addMenuActionButton("ADD CASH OUT", 0xFFF59E0B.toInt()) {
            onAddCashOutClicked()
            closeExpandedMenu()
        }
        addMenuActionButton("START AUTOMATION", 0xFF059669.toInt()) {
            onStartClicked()
        }
        addMenuActionButton("PAUSE / RESUME", 0xFFD97706.toInt()) {
            onPauseClicked()
        }
        addMenuActionButton("QUIT UTILITY", 0xFFDC2626.toInt()) {
            onQuitClicked()
        }

        expandedMenuView = menuContainer
        try {
            windowManager?.addView(expandedMenuView, params)
            isMenuExpanded = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding expanded menu", e)
        }
    }

    fun closeExpandedMenu() {
        expandedMenuView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed removing expanded menu", e)
            }
            expandedMenuView = null
        }
        isMenuExpanded = false
    }

    fun createIndependentRobotButton(label: String, bgColor: Int, onTaskRobotTapped: (View) -> Unit) {
        val sizePx = dpToPx(48)
        val initialX = 140 + (independentRobotViews.size * 65)
        val initialY = 400
        val params = createOverlayLayoutParams(sizePx, sizePx, initialX, initialY)

        val buttonView = TextView(context).apply {
            text = if (label.contains("BET", ignoreCase = true)) "B" else "C"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(bgColor)
            gravity = Gravity.CENTER
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            elevation = dpToPx(6).toFloat()
        }

        var initialXPos = 0
        var initialYPos = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoved = false

        buttonView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialXPos = params.x
                    initialYPos = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        isMoved = true
                        params.x = initialXPos + dx
                        params.y = initialYPos + dy
                        try {
                            windowManager?.updateViewLayout(v, params)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed updating robot button layout", e)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isMoved) {
                        onTaskRobotTapped(v)
                    }
                    true
                }
                else -> false
            }
        }

        independentRobotViews.add(buttonView)
        try {
            windowManager?.addView(buttonView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding robot button", e)
        }
    }

    private fun triggerHapticVibration() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(50)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Haptic vibration failed", e)
        }
    }

    fun removeAllOverlays() {
        closeExpandedMenu()
        mainBubbleView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { Log.e(TAG, "Failed removing main bubble", e) }
            mainBubbleView = null
        }
        independentRobotViews.forEach {
            try { windowManager?.removeView(it) } catch (e: Exception) { Log.e(TAG, "Failed removing robot button", e) }
        }
        independentRobotViews.clear()
    }
}
