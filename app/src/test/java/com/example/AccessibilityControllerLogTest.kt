package com.example

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.service.AccessibilityController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

import java.util.IdentityHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccessibilityControllerLogTest {

    private class TestHierarchyAccessibilityController(
        private val childrenMap: IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>
    ) : AccessibilityController() {
        override fun childNodesOf(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
            return childrenMap[node] ?: emptyList()
        }
    }

    @Before
    fun setUp() {
        ShadowLog.clear()
        AccessibilityController.setNodeHierarchyLoggingEnabled(true)
    }

    @After
    fun tearDown() {
        AccessibilityController.setNodeHierarchyLoggingEnabled(true)
    }

    private fun makeNode(
        className: String,
        resourceId: String? = null,
        text: String? = null,
        desc: String? = null,
        bounds: Rect = Rect(0, 0, 100, 50),
        editable: Boolean = false,
        clickable: Boolean = false,
        pkg: String = "com.betpawa.app"
    ): AccessibilityNodeInfo {
        return AccessibilityNodeInfo().apply {
            this.packageName = pkg
            this.className = className
            this.viewIdResourceName = resourceId
            this.text = text
            this.contentDescription = desc
            this.isEditable = editable
            this.isClickable = clickable
            this.isFocusable = editable || clickable
            this.isVisibleToUser = true
            @Suppress("DEPRECATION")
            setBoundsInScreen(bounds)
        }
    }

    @Test
    fun `logs full node hierarchy to Logcat with Resource IDs for stake input and Place Bet button`() {
        val root = makeNode(
            className = "android.widget.FrameLayout",
            resourceId = "com.betpawa.app:id/aviator_container",
            bounds = Rect(0, 0, 1080, 2400)
        )
        val panel = makeNode(
            className = "android.widget.LinearLayout",
            resourceId = "com.betpawa.app:id/bet_controls_panel",
            bounds = Rect(20, 1000, 1060, 1400)
        )
        val stakeEdit = makeNode(
            className = "android.widget.EditText",
            resourceId = "com.betpawa.app:id/stake_input_amount",
            text = "25",
            bounds = Rect(40, 1100, 420, 1220),
            editable = true
        )
        val placeBetBtn = makeNode(
            className = "android.widget.Button",
            resourceId = "com.betpawa.app:id/place_bet_button",
            text = "Place Bet",
            bounds = Rect(460, 1100, 1020, 1220),
            clickable = true
        )

        val treeMap = IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>().apply {
            put(root, listOf(panel))
            put(panel, listOf(stakeEdit, placeBetBtn))
        }
        val controller = TestHierarchyAccessibilityController(treeMap)

        val report = controller.logNodeHierarchyNow(
            rootNode = root,
            packageName = "com.betpawa.app",
            eventLabel = "TYPE_WINDOW_STATE_CHANGED",
            currentTimeMs = 1_700_000_000_000L
        )

        assertEquals(4, report.totalNodesVisited)
        assertFalse(report.truncated)
        assertEquals(2, report.candidates.size)
        assertEquals("STAKE_INPUT", report.candidates[0].role)
        assertEquals("com.betpawa.app:id/stake_input_amount", report.candidates[0].resourceId)
        assertEquals("PLACE_BET_BTN", report.candidates[1].role)
        assertEquals("com.betpawa.app:id/place_bet_button", report.candidates[1].resourceId)

        val logEntries = ShadowLog.getLogsForTag(AccessibilityController.NODE_HIERARCHY_TAG)
        assertTrue("Expected Logcat lines under A11yNodeTree", logEntries.isNotEmpty())
        val joinedLogs = logEntries.joinToString("\n") { it.msg }
        assertTrue(joinedLogs.contains("pkg=com.betpawa.app"))
        assertTrue(joinedLogs.contains("id=com.betpawa.app:id/stake_input_amount"))
        assertTrue(joinedLogs.contains("id=com.betpawa.app:id/place_bet_button"))
        assertTrue(joinedLogs.contains("CANDIDATE:STAKE_INPUT"))
        assertTrue(joinedLogs.contains("CANDIDATE:PLACE_BET_BTN"))
        assertTrue(AccessibilityController.lastLoggedHierarchy.value.contains("com.betpawa.app:id/place_bet_button"))
    }

    @Test
    fun `throttles duplicate hierarchy logs and emits immediately on structural or toggle changes`() {
        val root = makeNode(
            className = "android.webkit.WebView",
            resourceId = "com.sportybet.mobile:id/webview_root",
            pkg = "com.sportybet.mobile"
        )
        val betBtn = makeNode(
            className = "android.widget.Button",
            resourceId = "com.sportybet.mobile:id/btn_bet_main",
            text = "Bet",
            clickable = true,
            pkg = "com.sportybet.mobile"
        )
        val children = mutableListOf(betBtn)
        val treeMap = IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>().apply {
            put(root, children)
        }
        val controller = TestHierarchyAccessibilityController(treeMap)
        controller.setHierarchyLogThrottleMs(1000L)

        val contentEvent = AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = "com.sportybet.mobile"
        }

        // First dump succeeds
        assertTrue(controller.maybeLogNodeHierarchy(root, contentEvent, currentTimeMs = 10_000L, skipOwnPackage = false))
        // Identical structure within throttle window is skipped
        assertFalse(controller.maybeLogNodeHierarchy(root, contentEvent, currentTimeMs = 10_200L, skipOwnPackage = false))
        // Identical structure right at 1s throttle is still deduplicated until refresh interval
        assertFalse(controller.maybeLogNodeHierarchy(root, contentEvent, currentTimeMs = 11_100L, skipOwnPackage = false))

        // Structural change after throttle emits immediately
        val stakeNode = makeNode(
            className = "android.widget.EditText",
            resourceId = "com.sportybet.mobile:id/et_stake",
            text = "50",
            editable = true,
            pkg = "com.sportybet.mobile"
        )
        children.add(stakeNode)
        assertTrue(controller.maybeLogNodeHierarchy(root, contentEvent, currentTimeMs = 11_500L, skipOwnPackage = false))

        // Disabling logging suppresses emission
        AccessibilityController.setNodeHierarchyLoggingEnabled(false)
        children.clear()
        assertFalse(controller.maybeLogNodeHierarchy(root, contentEvent, currentTimeMs = 20_000L, skipOwnPackage = false))
    }

    private class ExposedTestAccessibilityController(
        private val childrenMap: IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>
    ) : AccessibilityController() {
        override fun childNodesOf(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
            return childrenMap[node] ?: emptyList()
        }

        fun scan(root: AccessibilityNodeInfo) = scanWindowTargets(root)
    }

    @Test
    fun `separates top horizontal history ribbon from live central multiplier`() {
        val root = makeNode(
            className = "android.widget.FrameLayout",
            bounds = Rect(0, 0, 1080, 2400),
            pkg = "com.android.chrome"
        )
        val urlBar = makeNode(
            className = "android.widget.EditText",
            resourceId = "com.android.chrome:id/url_bar",
            text = "https://www.betpawa.co.tz/casino/aviator",
            bounds = Rect(120, 80, 920, 160),
            editable = true,
            pkg = "com.android.chrome"
        )
        val ribbon1 = makeNode("android.widget.TextView", text = "15.74x", bounds = Rect(30, 280, 170, 330), pkg = "com.android.chrome")
        val ribbon2 = makeNode("android.widget.TextView", text = "2.00x", bounds = Rect(190, 280, 320, 330), pkg = "com.android.chrome")
        val ribbon3 = makeNode("android.widget.TextView", text = "8.14x", bounds = Rect(340, 280, 470, 330), pkg = "com.android.chrome")
        val ribbon4 = makeNode("android.widget.TextView", text = "3.29x", bounds = Rect(490, 280, 620, 330), pkg = "com.android.chrome")

        val baseChildren = mutableListOf(urlBar, ribbon1, ribbon2, ribbon3, ribbon4)
        val treeMap = IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>().apply {
            put(root, baseChildren)
        }
        val controller = ExposedTestAccessibilityController(treeMap)

        // With ONLY ribbon visible, detectedMultiplier must be null (never confused with 15.74x)
        val ribbonOnly = controller.scan(root)
        assertEquals(listOf(15.74, 2.00, 8.14, 3.29), ribbonOnly.ribbonMultipliers)
        assertEquals(null, ribbonOnly.detectedMultiplier)
        assertEquals("https://www.betpawa.co.tz/casino/aviator", ribbonOnly.detectedUrl)

        // Add large center live multiplier
        val liveCenter = makeNode(
            "android.widget.TextView",
            text = "3.42x",
            bounds = Rect(340, 740, 740, 890),
            pkg = "com.android.chrome"
        )
        baseChildren.add(liveCenter)

        val liveScan = controller.scan(root)
        assertEquals(3.42, liveScan.detectedMultiplier!!, 0.001)
        assertEquals(listOf(15.74, 2.00, 8.14, 3.29), liveScan.ribbonMultipliers)
    }

    @Test
    fun `tracks Chrome URL BetPawa vs SportyBet and real Bet Cancel CashOut FlewAway button states`() {
        val root = makeNode(
            className = "android.widget.FrameLayout",
            bounds = Rect(0, 0, 1080, 2400),
            pkg = "com.android.chrome"
        )
        val urlNode = makeNode(
            className = "android.widget.EditText",
            resourceId = "com.android.chrome:id/url_bar",
            text = "betpawa.co.zm/casino/aviator",
            bounds = Rect(120, 80, 920, 160),
            editable = true,
            pkg = "com.android.chrome"
        )
        val actionBtn = makeNode(
            className = "android.widget.Button",
            text = "Bet",
            bounds = Rect(80, 1500, 980, 1640),
            clickable = true,
            pkg = "com.android.chrome"
        )
        val liveText = makeNode(
            className = "android.widget.TextView",
            text = "Place your bet",
            bounds = Rect(260, 720, 820, 820),
            pkg = "com.android.chrome"
        )
        val treeMap = IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>().apply {
            put(root, listOf(urlNode, liveText, actionBtn))
        }
        val controller = ExposedTestAccessibilityController(treeMap)

        // 1. IDLE / READY TO BET ("Bet")
        liveText.text = ""
        val readyScan = controller.scan(root)
        assertEquals(com.example.data.model.SiteButtonState.IDLE_READY_TO_BET, readyScan.siteButtonState)
        assertEquals("BetPawa Aviator", com.example.data.engine.GameDetection.resolvePlatformFromSignals("com.android.chrome", readyScan.detectedUrl, readyScan.pageTextHints))

        // 2. BET QUEUED ("Cancel" / "Waiting for next round")
        actionBtn.text = "Cancel"
        liveText.text = "Waiting for next round"
        val queuedScan = controller.scan(root)
        assertEquals(com.example.data.model.SiteButtonState.BET_QUEUED, queuedScan.siteButtonState)
        assertTrue(queuedScan.isCancelBtnDetected)

        // 3. Switch URL to SportyBet + CASH OUT AVAILABLE ("Cash Out")
        urlNode.text = "https://www.sportybet.com/ng/m/games/aviator"
        actionBtn.text = "Cash Out"
        liveText.text = "2.15x"
        val cashOutScan = controller.scan(root)
        assertEquals(com.example.data.model.SiteButtonState.CASH_OUT_AVAILABLE, cashOutScan.siteButtonState)
        assertEquals(2.15, cashOutScan.detectedMultiplier!!, 0.001)
        assertEquals("SportyBet Aviator", com.example.data.engine.GameDetection.resolvePlatformFromSignals("com.android.chrome", cashOutScan.detectedUrl, cashOutScan.pageTextHints))

        // 4. CRASHED ("Flew Away")
        liveText.text = "Flew Away! 2.15x"
        val crashScan = controller.scan(root)
        assertEquals(com.example.data.model.SiteButtonState.CRASHED, crashScan.siteButtonState)
        assertTrue(crashScan.isFlewAwayDetected)
    }

    @Test
    fun `ignores bottom All Bets and Auto Cash Out multipliers while plane flies under target`() {
        val root = makeNode("android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400), pkg = "com.android.chrome")
        val cashOutBtn = makeNode("android.widget.Button", text = "Cash Out", bounds = Rect(60, 1520, 1020, 1680), clickable = true, pkg = "com.android.chrome")
        val autoCashPanel = makeNode("android.widget.LinearLayout", resourceId = "auto_cashout_row", bounds = Rect(60, 1720, 1020, 1820), pkg = "com.android.chrome")
        val autoCashTarget = makeNode("android.widget.TextView", text = "2.00x", bounds = Rect(700, 1730, 900, 1800), pkg = "com.android.chrome")
        val allBetsList = makeNode("android.widget.RecyclerView", resourceId = "all_bets_table", bounds = Rect(0, 1850, 1080, 2360), pkg = "com.android.chrome")
        val otherPlayerWin = makeNode("android.widget.TextView", text = "3.45x", bounds = Rect(520, 1920, 680, 1980), pkg = "com.android.chrome")
        val liveStageMult = makeNode("android.widget.TextView", text = "1.14x", bounds = Rect(360, 740, 720, 890), pkg = "com.android.chrome")

        val treeMap = IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>().apply {
            put(root, listOf(liveStageMult, cashOutBtn, autoCashPanel, allBetsList))
            put(autoCashPanel, listOf(autoCashTarget))
            put(allBetsList, listOf(otherPlayerWin))
        }
        val controller = ExposedTestAccessibilityController(treeMap)

        val scan = controller.scan(root)
        assertEquals(com.example.data.model.SiteButtonState.CASH_OUT_AVAILABLE, scan.siteButtonState)
        assertEquals(1.14, scan.detectedMultiplier!!, 0.001)

        // Even when canvas hides the live stage multiplier from a11y, bottom table values must NOT leak as detectedMultiplier
        liveStageMult.text = ""
        val canvasScan = controller.scan(root)
        assertEquals(null, canvasScan.detectedMultiplier)
    }

    @Test
    fun `keeps IDLE_READY_TO_BET when green Bet button is visible even with waiting hint and excludes BetPawa header`() {
        val root = makeNode("android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400), pkg = "com.android.chrome")
        val headerLogo = makeNode("android.widget.Button", text = "BetPawa", bounds = Rect(30, 180, 240, 250), clickable = true, pkg = "com.android.chrome")
        val waitingBanner = makeNode("android.widget.TextView", text = "Waiting for next round", bounds = Rect(240, 700, 840, 780), pkg = "com.android.chrome")
        val betButton = makeNode("android.widget.Button", text = "Bet 100 TZS", bounds = Rect(60, 1500, 1020, 1660), clickable = true, pkg = "com.android.chrome")

        val treeMap = IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>().apply {
            put(root, listOf(headerLogo, waitingBanner, betButton))
        }
        val controller = ExposedTestAccessibilityController(treeMap)

        val scan = controller.scan(root)
        assertEquals(com.example.data.model.SiteButtonState.IDLE_READY_TO_BET, scan.siteButtonState)
        assertTrue(scan.isBetBtnDetected)
        assertEquals(1580, scan.betButtonBounds?.centerY())
    }

    @Test
    fun `accessibility service safely handles null events, null root window, and invalid gesture coordinates`() {
        val service = com.example.service.ScreenEarnerAccessibilityService()
        service.onAccessibilityEvent(null)

        val fakeEvent = AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = "com.android.chrome"
        }
        // rootInActiveWindow is null on unattached service -> must not throw
        service.onAccessibilityEvent(fakeEvent)

        var invalidResult: Boolean? = null
        service.dispatchTouchGesture(Float.NaN, 100f) { ok -> invalidResult = ok }
        assertEquals(false, invalidResult)

        var negativeResult: Boolean? = null
        service.dispatchTouchGesture(-10f, 50f) { ok -> negativeResult = ok }
        assertEquals(false, negativeResult)
    }

    @Test
    fun `dispatchTouchGesture posts 80ms stroke on Main Looper without cancelling sequential gestures`() {
        val capturedGestures = mutableListOf<android.accessibilityservice.GestureDescription>()
        val controller = object : AccessibilityController() {
            override fun performPlatformDispatchGesture(
                gesture: android.accessibilityservice.GestureDescription,
                callback: GestureResultCallback,
                handler: android.os.Handler
            ): Boolean {
                capturedGestures.add(gesture)
                callback.onCompleted(gesture)
                return true
            }
        }

        val results = mutableListOf<Boolean>()
        controller.dispatchTouchGesture(420f, 1150f) { ok -> results.add(ok) }
        controller.dispatchTouchGesture(680f, 1150f) { ok -> results.add(ok) }

        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertEquals(2, capturedGestures.size)
        assertEquals(listOf(true, true), results)
        assertEquals(80L, capturedGestures[0].getStroke(0).duration)
        assertEquals(80L, capturedGestures[1].getStroke(0).duration)

        // Verify haltAllAutomationGestures cancels queued gestures before execution
        AccessibilityController.gestureOverlayPassThroughListener = {}
        capturedGestures.clear()
        val haltedResults = mutableListOf<Boolean>()
        controller.dispatchTouchGesture(300f, 900f) { ok -> haltedResults.add(ok) }
        controller.haltAllAutomationGestures("Session limit reached")
        org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(0, capturedGestures.size)
        AccessibilityController.gestureOverlayPassThroughListener = null
    }
}
