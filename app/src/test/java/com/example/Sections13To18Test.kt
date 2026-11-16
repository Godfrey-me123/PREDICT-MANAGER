package com.example

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.ScreenEarnerDatabase
import com.example.data.database.ScreenEarnerRepository
import com.example.data.engine.PatternDetectionEngine
import com.example.data.engine.RibbonHistoryCollector
import com.example.data.engine.ExtractedHistoryItem
import com.example.data.model.CollectionSource
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.HistoryCollectionStatus
import com.example.data.model.MultiplierRecord
import com.example.data.model.NotificationDeliveryStatus
import com.example.data.model.NotificationInteractionStatus
import com.example.data.model.NotificationPriorityLevel
import com.example.data.model.NotificationSettings
import com.example.data.model.PatternComparator
import com.example.data.model.PatternCondition
import com.example.data.model.PatternLogicOperator
import com.example.data.model.RepeatNotificationPolicy
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyNotificationType
import com.example.data.model.TimestampSource
import com.example.service.AndroidSystemNotificationDispatcher
import com.example.service.StrategyNotificationManager
import com.example.service.SystemNotificationDispatcher
import com.example.ui.ScreenEarnerViewModel
import com.example.ui.screens.StrategyScreen
import com.example.ui.theme.ScreenEarnerTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Sections13To18Test {

    @get:Rule
    val composeRule = createComposeRule()

    // =========================================================================
    // SECTION 13: Save & Close must close ONLY the editor panel, NEVER exit app
    // =========================================================================

    @Test
    fun `Save and Close in ViewModel returns to previousTab or Home without exiting app`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ScreenEarnerViewModel(app)

        // Open Strategy editor from Logs tab -> closing editor must return to Logs tab
        vm.selectTab(MainActivity.TAB_LOGS)
        vm.selectTab(MainActivity.TAB_STRATEGY)
        assertEquals(MainActivity.TAB_STRATEGY, vm.selectedTab.value)
        vm.closeEditorScreen()
        assertEquals(MainActivity.TAB_LOGS, vm.selectedTab.value)

        // Open Settings directly from Home -> closing editor must return to Home
        vm.selectTab(MainActivity.TAB_HOME)
        vm.selectTab(MainActivity.TAB_SETTINGS)
        vm.closeEditorScreen()
        assertEquals(MainActivity.TAB_HOME, vm.selectedTab.value)
    }

    @Test
    fun `Save and Close button on StrategyScreen invokes callback once with double-tap debounce`() {
        var saveCount = 0
        composeRule.setContent {
            ScreenEarnerTheme {
                StrategyScreen(
                    currentConfig = StrategyConfig(),
                    customStrategies = emptyList(),
                    onSaveConfig = { saveCount++ }
                )
            }
        }

        // Empty state card renders with zero hardcoded strategies
        composeRule.onNodeWithTag("custom_strategies_empty_state").assertIsDisplayed()

        // Tap SAVE & CLOSE twice rapidly -> debounced to 1 save call
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("save_strategy_btn"))
        composeRule.onNodeWithTag("save_strategy_btn").performClick()
        composeRule.onNodeWithTag("save_strategy_btn").performClick()
        composeRule.waitForIdle()

        assertEquals(1, saveCount)
    }

    // =========================================================================
    // SECTION 14: Complete Game Ribbon History Collection & Strict Deduplication
    // =========================================================================

    @Test
    fun `RibbonHistoryCollector preserves duplicate multipliers across distinct rounds and ignores unchanged snapshot`() {
        val collector = RibbonHistoryCollector()
        val t0 = 1_700_000_000_000L

        // Snapshot 1: [1.50x, 2.20x, 1.50x] -> preserves both distinct 1.50x rounds
        val firstSync = collector.processVisibleRibbonSnapshot(
            items = listOf(
                ExtractedHistoryItem(multiplier = 1.50, orderIndex = 0),
                ExtractedHistoryItem(multiplier = 2.20, orderIndex = 1),
                ExtractedHistoryItem(multiplier = 1.50, orderIndex = 2)
            ),
            platformName = "BetPawa Aviator",
            isPanelExpanded = false,
            hasExpandableControl = true,
            nowMs = t0
        )
        assertEquals(3, firstSync.newRecordsToPersist.size)
        assertEquals(listOf(1.50, 2.20, 1.50), firstSync.newRecordsToPersist.map { it.multiplier })
        assertTrue(firstSync.newRecordsToPersist.all { it.timestampSource == TimestampSource.LOCAL_OBSERVATION })
        assertEquals(3, firstSync.newRecordsToPersist.map { it.dedupKey }.toSet().size)

        // Exact repeated snapshot -> 0 new records persisted
        val repeatSync = collector.processVisibleRibbonSnapshot(
            items = listOf(
                ExtractedHistoryItem(multiplier = 1.50, orderIndex = 0),
                ExtractedHistoryItem(multiplier = 2.20, orderIndex = 1),
                ExtractedHistoryItem(multiplier = 1.50, orderIndex = 2)
            ),
            platformName = "BetPawa Aviator",
            isPanelExpanded = false,
            hasExpandableControl = true,
            nowMs = t0 + 1_000L
        )
        assertTrue(repeatSync.isRepeatedSnapshot)
        assertEquals(0, repeatSync.newRecordsToPersist.size)

        // Snapshot shifts right because ANOTHER 1.50x crashed! Must capture the new head 1.50x
        val shiftedSync = collector.processVisibleRibbonSnapshot(
            items = listOf(
                ExtractedHistoryItem(multiplier = 1.50, orderIndex = 0),
                ExtractedHistoryItem(multiplier = 1.50, orderIndex = 1),
                ExtractedHistoryItem(multiplier = 2.20, orderIndex = 2),
                ExtractedHistoryItem(multiplier = 1.50, orderIndex = 3)
            ),
            platformName = "BetPawa Aviator",
            isPanelExpanded = false,
            hasExpandableControl = true,
            nowMs = t0 + 10_000L
        )
        assertFalse(shiftedSync.isRepeatedSnapshot)
        assertEquals(listOf(1.50), shiftedSync.newRecordsToPersist.map { it.multiplier })
    }

    @Test
    fun `RibbonHistoryCollector marks GAME_PROVIDED only when actual game timestamp exists and marks disjoint gaps`() {
        val collector = RibbonHistoryCollector()
        val t0 = 1_700_000_000_000L

        val initial = collector.processVisibleRibbonSnapshot(
            items = listOf(
                ExtractedHistoryItem(multiplier = 3.10, gameTimestampMs = t0 - 15_000L, roundId = "r101", orderIndex = 0),
                ExtractedHistoryItem(multiplier = 1.25, gameTimestampMs = null, roundId = "r100", orderIndex = 1)
            ),
            platformName = "SportyBet Aviator",
            isPanelExpanded = true,
            hasExpandableControl = false,
            nowMs = t0
        )
        assertEquals(TimestampSource.GAME_PROVIDED, initial.newRecordsToPersist[0].timestampSource)
        assertEquals(TimestampSource.LOCAL_OBSERVATION, initial.newRecordsToPersist[1].timestampSource)

        // Completely disjoint ribbon refresh marks hasGapBefore = true cleanly without inventing skipped rounds
        val disjoint = collector.processVisibleRibbonSnapshot(
            items = listOf(
                ExtractedHistoryItem(multiplier = 9.80, roundId = "r205", orderIndex = 0),
                ExtractedHistoryItem(multiplier = 4.40, roundId = "r204", orderIndex = 1)
            ),
            platformName = "SportyBet Aviator",
            isPanelExpanded = true,
            hasExpandableControl = false,
            nowMs = t0 + 120_000L
        )
        assertEquals(2, disjoint.newRecordsToPersist.size)
        assertTrue(disjoint.newRecordsToPersist.last().hasGapBefore)
        assertFalse(disjoint.newRecordsToPersist.first().hasGapBefore)
    }

    // =========================================================================
    // SECTION 15 & 18: User-Defined Pattern Engine Sample Sequence Test
    // =========================================================================

    @Test
    fun `pattern engine triggers approaching at 7 of 8, reached at 8 of 8, deduplicates, and invalidates on 2_45x break`() {
        val engine = PatternDetectionEngine()
        val baseTime = 1_700_000_000_000L

        // Sample rule from Section 18: "8 consecutive < 2.00x"
        val strategy = CustomPatternStrategy(
            id = 10L,
            name = "8 Consecutive Under 2x",
            isEnabled = true,
            logicOperator = PatternLogicOperator.AND,
            approachingEnabled = true,
            notificationLevel = NotificationPriorityLevel.URGENT,
            repeatPolicy = RepeatNotificationPolicy.ONCE_PER_OCCURRENCE
        ).withConditions(
            listOf(
                PatternCondition(
                    comparator = PatternComparator.LESS_THAN,
                    threshold = 2.00,
                    consecutiveRounds = 8
                )
            )
        )

        val sampleMultipliers = listOf(1.22, 1.55, 1.31, 1.80, 1.10, 1.45, 1.72, 1.90)
        val records = sampleMultipliers.mapIndexed { idx, mult ->
            MultiplierRecord(
                id = idx.toLong() + 1L,
                multiplier = mult,
                crashTimestamp = baseTime + idx * 10_000L,
                dedupKey = "sample_round_$idx",
                sequenceIndex = idx
            )
        }

        // Feed rounds 1..6 -> no approaching, no reached yet
        val batchAfter6 = engine.evaluateAllStrategies(listOf(strategy), records.take(6), nowMs = baseTime + 60_000L)
        assertTrue("Rounds 1..6 should emit zero events", batchAfter6.events.isEmpty())

        // Feed round 7 (1.72x) -> emits STRATEGY_APPROACHING ("7 of 8")
        val batchAfter7 = engine.evaluateAllStrategies(batchAfter6.updatedStrategies, records.take(7), nowMs = baseTime + 70_000L)
        assertEquals(1, batchAfter7.events.size)
        assertEquals(StrategyNotificationType.STRATEGY_APPROACHING, batchAfter7.events.first().notificationType)
        assertTrue(batchAfter7.events.first().message.contains("7 of 8"))
        assertFalse(batchAfter7.events.first().message.contains("guaranteed", ignoreCase = true))

        // Feed round 8 (1.90x) -> emits STRATEGY_REACHED with neutral non-predictive wording
        val batchAfter8 = engine.evaluateAllStrategies(batchAfter7.updatedStrategies, records.take(8), nowMs = baseTime + 80_000L)
        assertEquals(1, batchAfter8.events.size)
        val reachedEvent = batchAfter8.events.first()
        assertEquals(StrategyNotificationType.STRATEGY_REACHED, reachedEvent.notificationType)
        assertTrue(reachedEvent.message.startsWith("PATTERN DETECTED:"))
        assertEquals(1.90, reachedEvent.triggeringMultiplier!!, 0.001)

        // Re-evaluating identical 8 records must produce 0 duplicate notifications
        val duplicateCheck = engine.evaluateAllStrategies(batchAfter8.updatedStrategies, records.take(8), nowMs = baseTime + 85_000L)
        assertTrue("Re-evaluation must not double-count or resend", duplicateCheck.events.isEmpty())

        // Round 9 breaks streak at 2.45x -> emits STRATEGY_INVALIDATED and resets active/approaching state
        val breakingRecord = MultiplierRecord(
            id = 9L,
            multiplier = 2.45,
            crashTimestamp = baseTime + 90_000L,
            dedupKey = "sample_round_8",
            sequenceIndex = 8
        )
        val batchAfterBreak = engine.evaluateAllStrategies(
            duplicateCheck.updatedStrategies,
            records + breakingRecord,
            nowMs = baseTime + 90_000L
        )
        assertEquals(1, batchAfterBreak.events.size)
        assertEquals(StrategyNotificationType.STRATEGY_INVALIDATED, batchAfterBreak.events.first().notificationType)
        assertFalse(batchAfterBreak.updatedStrategies.first().currentlyReached)
        assertFalse(batchAfterBreak.updatedStrategies.first().currentlyApproaching)
    }

    @Test
    fun `pattern engine supports OR logic, BETWEEN range, time window, and exact sequence`() {
        val engine = PatternDetectionEngine()
        val now = 1_700_000_600_000L

        val seqStrategy = CustomPatternStrategy(
            id = 21L,
            name = "Exact Low Pair OR Between High",
            isEnabled = true,
            logicOperator = PatternLogicOperator.OR,
            approachingEnabled = false
        ).withConditions(
            listOf(
                PatternCondition(sequenceMultipliers = listOf(1.20, 1.50)),
                PatternCondition(
                    comparator = PatternComparator.BETWEEN,
                    threshold = 50.0,
                    upperThreshold = 100.0,
                    consecutiveRounds = 1,
                    timeWindowMinutes = 5
                )
            )
        )

        val records = listOf(
            MultiplierRecord(multiplier = 1.20, crashTimestamp = now - 60_000L, dedupKey = "seq_0"),
            MultiplierRecord(multiplier = 1.50, crashTimestamp = now - 30_000L, dedupKey = "seq_1")
        )
        val batch = engine.evaluateAllStrategies(listOf(seqStrategy), records, nowMs = now)
        assertEquals(1, batch.events.size)
        assertEquals(StrategyNotificationType.STRATEGY_REACHED, batch.events.first().notificationType)
    }

    // =========================================================================
    // SECTION 16 & 18: Room Persistence + Notification Delivery/Suppression
    // =========================================================================

    @Test
    fun `room database v9 starts with zero hardcoded custom strategies and persists all entities cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ScreenEarnerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = ScreenEarnerRepository(db.dao())
            repo.seedDefaultsIfEmpty()

            // Zero hardcoded sample custom strategies must exist
            val initialStrategies = repo.getCustomStrategiesOnce()
            assertTrue("CustomPatternStrategy table must start empty", initialStrategies.isEmpty())

            // NotificationSettings starts with defaults enabled
            val settings = repo.getNotificationSettingsOnce()
            assertTrue(settings.masterEnabled)

            // Insert and query chronological records within 30m window vs older than 30m
            val now = 1_700_002_000_000L
            repo.addMultiplierRecords(
                listOf(
                    MultiplierRecord(
                        multiplier = 5.50,
                        crashTimestamp = now - 45 * 60_000L,
                        dedupKey = "old_45m"
                    ),
                    MultiplierRecord(
                        multiplier = 1.40,
                        crashTimestamp = now - 10 * 60_000L,
                        dedupKey = "recent_10m"
                    ),
                    MultiplierRecord(
                        multiplier = 1.40,
                        crashTimestamp = now - 5 * 60_000L,
                        dedupKey = "recent_5m"
                    )
                )
            )
            val recent = repo.getRecentChronologicalRecords(windowMinutes = 30, nowMs = now)
            assertEquals(2, recent.size)
            assertEquals("recent_10m", recent[0].dedupKey)
            assertEquals("recent_5m", recent[1].dedupKey)
        } finally {
            db.close()
        }
    }

    @Test
    fun `notification manager enforces master, type, strategy, cooldown, quiet hours, permission, and logs delivery`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ScreenEarnerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = ScreenEarnerRepository(db.dao())
            val strat = CustomPatternStrategy(
                id = 55L,
                name = "Test Strategy",
                isEnabled = true,
                notificationEnabled = true,
                repeatCooldownMinutes = 5
            ).withConditions(listOf(PatternCondition(PatternComparator.LESS_THAN, 2.0, consecutiveRounds = 2)))
            repo.insertCustomStrategy(strat)

            val engine = PatternDetectionEngine()
            val now = 1_700_002_000_000L
            val recs = listOf(
                MultiplierRecord(multiplier = 1.20, crashTimestamp = now - 20_000L, dedupKey = "n_1"),
                MultiplierRecord(multiplier = 1.30, crashTimestamp = now - 10_000L, dedupKey = "n_2")
            )
            val batch = engine.evaluateAllStrategies(listOf(strat), recs, nowMs = now)
            assertTrue(batch.events.any { it.notificationType == StrategyNotificationType.STRATEGY_APPROACHING })
            val event = batch.events.first { it.notificationType == StrategyNotificationType.STRATEGY_REACHED }

            val fakeGrantedDispatcher = object : SystemNotificationDispatcher {
                var postedCount = 0
                override fun hasNotificationPermission(context: Context): Boolean = true
                override fun postSystemNotification(
                    context: Context,
                    notificationId: Int,
                    title: String,
                    message: String,
                    priorityLevel: NotificationPriorityLevel,
                    soundEnabled: Boolean,
                    vibrationEnabled: Boolean,
                    historyRecordId: Long
                ): Boolean {
                    postedCount++
                    return true
                }
            }

            val mgr = StrategyNotificationManager(context, repo, fakeGrantedDispatcher)

            // 1. Master off -> suppressed
            val rMasterOff = mgr.dispatchPatternAlert(event, NotificationSettings(masterEnabled = false), now)
            assertEquals(NotificationDeliveryStatus.SUPPRESSED_MASTER_OFF, rMasterOff.deliveryStatus)
            assertEquals(0, fakeGrantedDispatcher.postedCount)

            // 2. Type off -> suppressed
            val rTypeOff = mgr.dispatchPatternAlert(event, NotificationSettings(reachedEnabled = false), now)
            assertEquals(NotificationDeliveryStatus.SUPPRESSED_TYPE_OFF, rTypeOff.deliveryStatus)

            // 3. Per-strategy off -> suppressed
            val stratOffEvent = event.copy(strategy = strat.copy(notificationEnabled = false))
            val rStratOff = mgr.dispatchPatternAlert(stratOffEvent, NotificationSettings(), now)
            assertEquals(NotificationDeliveryStatus.SUPPRESSED_STRATEGY_OFF, rStratOff.deliveryStatus)

            // 4. Quiet hours (all day 00:00 -> 23:59 around now) -> suppressed
            val rQuiet = mgr.dispatchPatternAlert(
                event,
                NotificationSettings(quietHoursEnabled = true, quietStartHour = 0, quietStartMinute = 0, quietEndHour = 23, quietEndMinute = 59),
                now
            )
            assertEquals(NotificationDeliveryStatus.SUPPRESSED_QUIET_HOURS, rQuiet.deliveryStatus)

            // 5. Permission denied -> logged as PERMISSION_DENIED
            val deniedMgr = StrategyNotificationManager(
                context,
                repo,
                object : SystemNotificationDispatcher {
                    override fun hasNotificationPermission(context: Context): Boolean = false
                    override fun postSystemNotification(
                        context: Context,
                        notificationId: Int,
                        title: String,
                        message: String,
                        priorityLevel: NotificationPriorityLevel,
                        soundEnabled: Boolean,
                        vibrationEnabled: Boolean,
                        historyRecordId: Long
                    ): Boolean = false
                }
            )
            val rDenied = deniedMgr.dispatchPatternAlert(event, NotificationSettings(), now)
            assertEquals(NotificationDeliveryStatus.PERMISSION_DENIED, rDenied.deliveryStatus)

            // 6. Valid + granted -> DELIVERED + second occurrence inside cooldown -> SUPPRESSED_COOLDOWN
            val rDelivered = mgr.dispatchPatternAlert(event, NotificationSettings(repeatIntervalMinutes = 10), now)
            assertEquals(NotificationDeliveryStatus.DELIVERED, rDelivered.deliveryStatus)
            assertEquals(1, fakeGrantedDispatcher.postedCount)

            val newOccurrenceInsideCooldown = event.copy(occurrenceKey = "different_occ_inside_cooldown")
            val rCooldown = mgr.dispatchPatternAlert(
                newOccurrenceInsideCooldown,
                NotificationSettings(repeatIntervalMinutes = 10),
                now + 60_000L
            )
            assertEquals(NotificationDeliveryStatus.SUPPRESSED_COOLDOWN, rCooldown.deliveryStatus)

            // 7. Marking opened updates interactionStatus
            repo.updateNotificationInteraction(rDelivered.id, NotificationInteractionStatus.OPENED_BY_USER)
            val savedHistory = repo.notificationHistory.first()
            val openedItem = savedHistory.firstOrNull { it.id == rDelivered.id }
            assertNotNull(openedItem)
            assertEquals(NotificationInteractionStatus.OPENED_BY_USER, openedItem!!.interactionStatus)

            // 8. Real AndroidSystemNotificationDispatcher posts onto Android NotificationManager when permitted
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val posted = AndroidSystemNotificationDispatcher.postSystemNotification(
                context = context,
                notificationId = 9001,
                title = "Pattern Detected: Test",
                message = "PATTERN DETECTED: 8 of 8",
                priorityLevel = NotificationPriorityLevel.HIGH,
                soundEnabled = true,
                vibrationEnabled = true,
                historyRecordId = rDelivered.id
            )
            assertTrue(posted)
            val shadowNm = Shadows.shadowOf(nm)
            assertTrue(shadowNm.allNotifications.any { it.extras.getString("android.title")?.contains("Pattern Detected") == true })
        } finally {
            db.close()
        }
    }
}
