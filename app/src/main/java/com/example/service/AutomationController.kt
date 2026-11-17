package com.example.service

import android.content.Context
import android.util.Log
import com.example.data.database.ClearHistoryScope
import com.example.data.database.ScreenEarnerDatabase
import com.example.data.database.ScreenEarnerRepository
import com.example.data.engine.AutomationEngine
import com.example.data.engine.CurrencyManager
import com.example.data.model.CollectionSource
import com.example.data.engine.ConfirmedCrashEmission
import com.example.data.engine.ExtractedHistoryItem
import com.example.data.engine.GameDetection
import com.example.data.engine.HourlyAnalysisEngine
import com.example.data.engine.LiveCrashPipeline
import com.example.data.engine.MultiWindowAnalysisBundle
import com.example.data.engine.PatternDetectionEngine
import com.example.data.engine.RibbonHistoryCollector
import com.example.data.engine.RiskManager
import com.example.data.engine.SmartAgentExplanation
import com.example.data.engine.SmartAnalysisAgent
import com.example.data.engine.StartAutomationResult
import com.example.data.engine.StatisticalAnalysisEngine
import com.example.data.engine.StatisticalReport
import com.example.data.engine.StrategyEngine
import com.example.data.model.AlarmProfileEntity
import com.example.data.model.CalendarHourUtils
import com.example.data.model.ColorClassifier
import com.example.data.model.CrashRecord
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.FloatingShortcut
import com.example.data.model.GameState
import com.example.data.model.HistoryCollectionStatus
import com.example.data.model.MonitoringPipelineStatus
import com.example.data.model.MonitoringSessionEntity
import com.example.data.model.MonitoringStatus
import com.example.data.model.MultiplierRecord
import com.example.data.model.NotificationSettings
import com.example.data.model.PatternOccurrenceRecord
import com.example.data.model.RiskLimits
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyNotificationType
import com.example.data.model.TimestampSource
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * AutomationController - Singleton managing the single authoritative coroutine-serialized
 * live monitoring pipeline, session lifecycle & persisted baselines, Room deduplicated crash
 * persistence, statistical/hourly analysis, Smart Analysis Agent, clock-hour rollover, and alerts.
 */
object AutomationController {
    private const val TAG = "AutomationController"

    const val PREFS_NAME = SessionStateManager.PREFS_NAME
    const val KEY_ACTIVE_SESSION_ID = SessionStateManager.KEY_ACTIVE_SESSION_ID
    const val KEY_MONITORING_DESIRED_STATE = SessionStateManager.KEY_MONITORING_DESIRED_STATE

    var repository: ScreenEarnerRepository? = null
    var engine: AutomationEngine? = null
    var appContextRef: Context? = null
        private set
    private var sessionStateManager: SessionStateManager? = null

    val patternDetectionEngine = PatternDetectionEngine()
    var notificationManager: StrategyNotificationManager? = null
        private set

    private val safeExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is kotlinx.coroutines.CancellationException) {
            Log.w(TAG, "Background coroutine exception: ${throwable.message}", throwable)
        }
    }

    private val controllerScope = CoroutineScope(Dispatchers.Default + SupervisorJob() + safeExceptionHandler)
    private val evaluationMutex = Mutex()
    private val recordingMutex = Mutex()
    private val hourRolloverMutex = Mutex()
    private var loopsParentJob: Job? = null
    private var lastLoopsRepo: ScreenEarnerRepository? = null
    private var observerJob: Job? = null
    private var tickerJob: Job? = null

    private val _latestConfig = MutableStateFlow(StrategyConfig())
    val latestConfig: StateFlow<StrategyConfig> = _latestConfig.asStateFlow()

    private val _latestLimits = MutableStateFlow(RiskLimits())
    val latestLimits: StateFlow<RiskLimits> = _latestLimits.asStateFlow()

    private val _latestShortcuts = MutableStateFlow<List<FloatingShortcut>>(emptyList())
    val latestShortcuts: StateFlow<List<FloatingShortcut>> = _latestShortcuts.asStateFlow()

    private val _latestCustomStrategies = MutableStateFlow<List<CustomPatternStrategy>>(emptyList())
    val latestCustomStrategies: StateFlow<List<CustomPatternStrategy>> = _latestCustomStrategies.asStateFlow()

    private val _latestNotificationSettings = MutableStateFlow(NotificationSettings())
    val latestNotificationSettings: StateFlow<NotificationSettings> = _latestNotificationSettings.asStateFlow()

    private val _alarmProfiles = MutableStateFlow<List<AlarmProfileEntity>>(emptyList())
    val alarmProfiles: StateFlow<List<AlarmProfileEntity>> = _alarmProfiles.asStateFlow()

    private val _monitoringSessions = MutableStateFlow<List<MonitoringSessionEntity>>(emptyList())
    val monitoringSessions: StateFlow<List<MonitoringSessionEntity>> = _monitoringSessions.asStateFlow()

    private val _selectedGameId = MutableStateFlow("unknown")
    val selectedGameId: StateFlow<String> = _selectedGameId.asStateFlow()

    private val _activeSessionId = MutableStateFlow("")
    val activeSessionId: StateFlow<String> = _activeSessionId.asStateFlow()

    private val _monitoringStatus = MutableStateFlow(MonitoringStatus.STOPPED)
    val monitoringStatus: StateFlow<MonitoringStatus> = _monitoringStatus.asStateFlow()

    private val _sessionCrashesRecorded = MutableStateFlow(0)
    val sessionCrashesRecorded: StateFlow<Int> = _sessionCrashesRecorded.asStateFlow()

    private val _activeSessionCrashRecords = MutableStateFlow<List<CrashRecord>>(emptyList())
    val activeSessionCrashRecords: StateFlow<List<CrashRecord>> = _activeSessionCrashRecords.asStateFlow()
    val sessionCrashRecords: StateFlow<List<CrashRecord>> = _activeSessionCrashRecords.asStateFlow()

    val liveCrashPipeline: LiveCrashPipeline by lazy {
        LiveCrashPipeline(scope = controllerScope) { emission ->
            handleConfirmedLivePipelineEmission(emission)
        }
    }

    private val _statisticalReport = MutableStateFlow(
        StatisticalAnalysisEngine.analyzeCompletedCrashes(emptyList())
    )
    val statisticalReport: StateFlow<StatisticalReport> = _statisticalReport.asStateFlow()

    private val _multiWindowBundle = MutableStateFlow(
        HourlyAnalysisEngine.buildMultiWindowBundle(emptyList())
    )
    val multiWindowBundle: StateFlow<MultiWindowAnalysisBundle> = _multiWindowBundle.asStateFlow()

    private val _smartAgentExplanation = MutableStateFlow(
        SmartAnalysisAgent.generateExplanation(
            gameState = GameState(),
            strategies = emptyList(),
            report = StatisticalAnalysisEngine.analyzeCompletedCrashes(emptyList()),
            hourlyBundle = HourlyAnalysisEngine.buildMultiWindowBundle(emptyList()),
            occurrences = emptyList(),
            config = StrategyConfig()
        )
    )
    val smartAgentExplanation: StateFlow<SmartAgentExplanation> = _smartAgentExplanation.asStateFlow()

    @Volatile
    private var cachedCrashRecords: List<CrashRecord> = emptyList()

    @Volatile
    private var cachedPatternOccurrences: List<PatternOccurrenceRecord> = emptyList()

    @Volatile
    private var baselineSnapshotCache: List<PersistedRibbonBaselineItem>? = null

    @Volatile
    private var baselineGameIdCache: String = ""

    @Volatile
    var bypassMonitoringGuardForTest: Boolean = false

    @Volatile
    private var previousExpandedPanel: Boolean = false

    @Volatile
    private var inMemorySeqCounter: Long = 0L

    @Volatile
    private var lastAutoProfileId: String? = null

    @Volatile
    private var lastDetectedGameIdForNotice: String? = null

    @Volatile
    private var lastHistoryUnavailableNoticeMs: Long = 0L

    @Volatile
    private var wasAccessibilityDisconnectedNoticed: Boolean = false

    @Volatile
    private var lastObservedPhaseForExecution: String = ""

    @Volatile
    private var activeTrackedHourStartMs: Long = CalendarHourUtils.hourStartFor(System.currentTimeMillis())

    fun ensureInitialized(context: Context) {
        val appContext = context.applicationContext
        if (appContextRef !== appContext || sessionStateManager == null) {
            appContextRef = appContext
            sessionStateManager = SessionStateManager(appContext)
        }
        if (repository == null) {
            val dao = ScreenEarnerDatabase.getDatabase(appContext).dao()
            val repo = ScreenEarnerRepository(dao)
            notificationManager = StrategyNotificationManager(appContext, repo)
            initialize(repo, appContext)
            BatteryOptimizationWorker.schedule(appContext)
        } else if (notificationManager == null) {
            notificationManager = StrategyNotificationManager(appContext, repository!!)
        }
    }

    private fun attachEngineCallbacks(targetEngine: AutomationEngine) {
        targetEngine.onExecutionNotification = { type, title, message, occurrenceKey, _ ->
            controllerScope.launch {
                notificationManager?.dispatchSystemNotice(
                    strategyName = "Predict Manager Automation",
                    type = type,
                    title = title,
                    message = message,
                    occurrenceKey = occurrenceKey,
                    settings = _latestNotificationSettings.value,
                    nowMs = System.currentTimeMillis(),
                    gameId = _selectedGameId.value.takeIf { it.isNotBlank() && it != "unknown" }
                )
            }
        }
    }

    fun initialize(repo: ScreenEarnerRepository, context: Context? = null) {
        if (context != null) {
            val appContext = context.applicationContext
            if (appContextRef !== appContext || sessionStateManager == null) {
                appContextRef = appContext
                sessionStateManager = SessionStateManager(appContext)
            }
        }
        if (notificationManager == null || repository !== repo) {
            notificationManager = StrategyNotificationManager(appContextRef, repo)
        }
        if (engine == null || repository !== repo) {
            repository = repo
            val newEngine = AutomationEngine(
                repo,
                StrategyEngine(),
                RiskManager(),
                GameDetection(),
                controllerScope
            )
            attachEngineCallbacks(newEngine)
            engine = newEngine
            startBackgroundLoops(repo)
            if (sessionStateManager?.isIntendedMonitoringActive() == true) {
                controllerScope.launch {
                    restorePersistedSessionIfIntended()
                }
            }
        } else {
            engine?.let { attachEngineCallbacks(it) }
        }
    }

    fun getSessionStateManager(): SessionStateManager? {
        val ctx = appContextRef ?: return sessionStateManager
        if (sessionStateManager == null) {
            sessionStateManager = SessionStateManager(ctx)
        }
        return sessionStateManager
    }

    fun resetTestState() {
        cachedCrashRecords = emptyList()
        cachedPatternOccurrences = emptyList()
        baselineSnapshotCache = null
        baselineGameIdCache = ""
        previousExpandedPanel = false
        inMemorySeqCounter = 0L
        wasAccessibilityDisconnectedNoticed = false
        lastObservedPhaseForExecution = ""
        _selectedGameId.value = "unknown"
        _activeSessionId.value = ""
        _monitoringStatus.value = MonitoringStatus.STOPPED
        _sessionCrashesRecorded.value = 0
        _activeSessionCrashRecords.value = emptyList()
        liveCrashPipeline.resetForStoppedSession()
    }

    fun cancelTestBackgroundLoops() {
        val job = loopsParentJob
        val obs = observerJob
        val tick = tickerJob
        loopsParentJob = null
        observerJob = null
        tickerJob = null
        lastLoopsRepo = null
        runBlocking {
            withTimeoutOrNull(2_000L) {
                obs?.cancel()
                tick?.cancel()
                job?.cancelAndJoin()
            }
        }
    }

    private fun nextConfirmedSequence(): Long {
        val mgr = sessionStateManager
        return mgr?.nextConfirmedSequence() ?: (++inMemorySeqCounter)
    }

    private fun saveBaseline(gameId: String, items: List<PersistedRibbonBaselineItem>) {
        baselineSnapshotCache = items
        baselineGameIdCache = gameId
        sessionStateManager?.saveBaselineSnapshot(gameId, items, captured = true)
    }

    private fun loadBaseline(gameId: String): List<PersistedRibbonBaselineItem>? {
        val inMem = baselineSnapshotCache
        if (!inMem.isNullOrEmpty() && (baselineGameIdCache.isBlank() || baselineGameIdCache == gameId)) {
            return inMem
        }
        val persisted = sessionStateManager?.loadBaselineSnapshot(gameId)
        if (!persisted.isNullOrEmpty()) {
            baselineSnapshotCache = persisted
            baselineGameIdCache = gameId
        }
        return persisted
    }

    fun startMonitoringSession(
        isAccessibilityConnected: Boolean = ScreenEarnerAccessibilityService.isServiceConnected(),
        nowMs: Long = System.currentTimeMillis()
    ): StartAutomationResult {
        val currentEngine = engine ?: return StartAutomationResult(false, "Engine not initialized")
        val cfg = _latestConfig.value
        val lim = _latestLimits.value
        val check = currentEngine.verifyReadyForMonitoring(isAccessibilityConnected, lim)
        if (!check.started) {
            if (!isAccessibilityConnected) {
                _monitoringStatus.value = MonitoringStatus.PERMISSION_REQUIRED
                currentEngine.gameDetection.updatePipelineStatus(
                    MonitoringPipelineStatus.ACCESSIBILITY_DISCONNECTED,
                    check.message,
                    sessionId = _activeSessionId.value,
                    sessionCrashCount = _activeSessionCrashRecords.value.size
                )
            }
            return check
        }

        // If resuming from PAUSED within an active session, preserve session ID, baseline, and crashes!
        if (currentEngine.automationState.value == com.example.data.engine.AutomationState.PAUSED &&
            _activeSessionId.value.isNotBlank()
        ) {
            sessionStateManager?.setIntendedMonitoringActive(true)
            currentEngine.resumeAutomation(cfg, lim)
            val currentCount = _activeSessionCrashRecords.value.size
            val nextStatus = if (!currentEngine.gameDetection.gameState.value.isDetected) {
                MonitoringPipelineStatus.WAITING_FOR_GAME
            } else if (!baselineSnapshotCache.isNullOrEmpty()) {
                if (currentCount > 0) MonitoringPipelineStatus.MONITORING_LIVE
                else MonitoringPipelineStatus.BASELINE_CAPTURED_WAITING_FOR_NEW_CRASH
            } else {
                MonitoringPipelineStatus.GAME_DETECTED_WAITING_FOR_HISTORY
            }
            _monitoringStatus.value = MonitoringStatus.MONITORING
            _sessionCrashesRecorded.value = currentCount
            currentEngine.gameDetection.updatePipelineStatus(
                pipelineStatus = nextStatus,
                reason = "Monitoring resumed cleanly — session preserved ($currentCount crashes)",
                sessionId = _activeSessionId.value,
                sessionCrashCount = currentCount
            )
            recomputeAnalytics(nowMs)
            return StartAutomationResult(true, "Live monitoring resumed.")
        }

        // Start a brand-new monitoring session at crash count = 0 (never importing old DB records)
        val activeGame = _selectedGameId.value.ifBlank { "unknown" }
        val newSessionId = sessionStateManager?.startFreshSession(nowMs, activeGame)
            ?: "sess_${nowMs}_${UUID.randomUUID().toString().take(8)}"
        sessionStateManager?.setIntendedMonitoringActive(true)
        baselineSnapshotCache = null
        baselineGameIdCache = ""
        previousExpandedPanel = false
        _activeSessionId.value = newSessionId
        _activeSessionCrashRecords.value = emptyList()
        _sessionCrashesRecorded.value = 0
        _monitoringStatus.value = MonitoringStatus.MONITORING
        liveCrashPipeline.configureSession(newSessionId)
        currentEngine.gameDetection.resetLiveSessionTelemetry(sessionId = newSessionId)
        val result = currentEngine.startMonitoringWithValidation(cfg, lim, isAccessibilityConnected)
        val initialStatus = if (currentEngine.gameDetection.gameState.value.isDetected) {
            MonitoringPipelineStatus.GAME_DETECTED_WAITING_FOR_HISTORY
        } else {
            MonitoringPipelineStatus.WAITING_FOR_GAME
        }
        currentEngine.gameDetection.updatePipelineStatus(
            pipelineStatus = initialStatus,
            reason = "New session started ($newSessionId) — awaiting game history baseline",
            sessionId = newSessionId,
            sessionCrashCount = 0,
            latestRoundId = null
        )
        val gs = currentEngine.gameDetection.gameState.value
        controllerScope.launch {
            runCatching {
                repository?.upsertMonitoringSession(
                    MonitoringSessionEntity(
                        sessionId = newSessionId,
                        gameId = gs.gameId.ifBlank { "unknown" },
                        gameName = gs.displayedGameName.ifBlank { "Unknown" },
                        startedAtMs = nowMs,
                        confirmedCrashCount = 0,
                        isActive = true
                    )
                )
            }
        }
        recomputeAnalytics(nowMs)
        return result
    }

    fun startNewOrResumeMonitoring(context: Context): String {
        ensureInitialized(context)
        val currentEngine = engine
        val existingSid = _activeSessionId.value
        val isPaused = currentEngine?.automationState?.value == com.example.data.engine.AutomationState.PAUSED ||
            _monitoringStatus.value == MonitoringStatus.PAUSED
        if (isPaused && existingSid.isNotBlank()) {
            val res = startMonitoringSession(isAccessibilityConnected = true)
            if (!res.started) {
                _monitoringStatus.value = MonitoringStatus.MONITORING
                sessionStateManager?.setDesiredMonitoringStatus(MonitoringStatus.MONITORING)
            }
            return _activeSessionId.value
        }
        val res = startMonitoringSession(isAccessibilityConnected = true)
        if (!res.started) {
            val sid = sessionStateManager?.startFreshSession(System.currentTimeMillis(), _selectedGameId.value)
                ?: "sess_${System.currentTimeMillis()}"
            _activeSessionId.value = sid
            _activeSessionCrashRecords.value = emptyList()
            _sessionCrashesRecorded.value = 0
            _monitoringStatus.value = MonitoringStatus.MONITORING
            sessionStateManager?.setDesiredMonitoringStatus(MonitoringStatus.MONITORING)
            liveCrashPipeline.configureSession(sid)
        }
        return _activeSessionId.value
    }

    fun pauseMonitoringSession() {
        val currentEngine = engine ?: return
        sessionStateManager?.setDesiredMonitoringStatus(MonitoringStatus.PAUSED)
        currentEngine.pauseAutomation()
        val count = _activeSessionCrashRecords.value.size
        _monitoringStatus.value = MonitoringStatus.PAUSED
        _sessionCrashesRecorded.value = count
        currentEngine.gameDetection.updatePipelineStatus(
            pipelineStatus = MonitoringPipelineStatus.PAUSED,
            reason = "Monitoring paused — session data preserved ($count crashes)",
            sessionId = _activeSessionId.value,
            sessionCrashCount = count
        )
        recomputeAnalytics()
    }

    fun pauseMonitoring(context: Context) {
        ensureInitialized(context)
        pauseMonitoringSession()
    }

    fun stopMonitoringSession() {
        val currentEngine = engine ?: return
        sessionStateManager?.setIntendedMonitoringActive(false)
        currentEngine.quitAutomation()
        liveCrashPipeline.resetForStoppedSession()
        val count = _activeSessionCrashRecords.value.size
        _monitoringStatus.value = MonitoringStatus.STOPPED
        _sessionCrashesRecorded.value = count
        currentEngine.gameDetection.updatePipelineStatus(
            pipelineStatus = MonitoringPipelineStatus.STOPPED,
            reason = "Monitoring stopped",
            sessionId = _activeSessionId.value,
            sessionCrashCount = count
        )
        controllerScope.launch {
            runCatching { repository?.closeActiveMonitoringSessions(System.currentTimeMillis()) }
        }
        recomputeAnalytics()
    }

    suspend fun endSessionAndClearSessionData() {
        val repo = repository
        val currentEngine = engine
        recordingMutex.withLock {
            val sid = _activeSessionId.value.ifBlank { sessionStateManager?.getActiveSessionId().orEmpty() }
            if (repo != null && sid.isNotBlank()) {
                runCatching { repo.clearCrashRecordsForSession(sid) }
                runCatching { repo.clearMultiplierRecordsForSession(sid) }
                runCatching { repo.closeActiveMonitoringSessions(System.currentTimeMillis()) }
            }
            sessionStateManager?.clearSessionRuntimeState()
            baselineSnapshotCache = null
            baselineGameIdCache = ""
            previousExpandedPanel = false
            liveCrashPipeline.resetForStoppedSession()
            _activeSessionId.value = ""
            _monitoringStatus.value = MonitoringStatus.STOPPED
            _sessionCrashesRecorded.value = 0
            _activeSessionCrashRecords.value = emptyList()
            currentEngine?.quitAutomation()
            currentEngine?.resetSessionStats()
            currentEngine?.gameDetection?.resetLiveSessionTelemetry(sessionId = "")
            currentEngine?.gameDetection?.updatePipelineStatus(
                pipelineStatus = MonitoringPipelineStatus.STOPPED,
                reason = "Session ended and current session data cleared",
                sessionId = "",
                sessionCrashCount = 0,
                latestRoundId = null
            )
            cachedCrashRecords = repo?.let { runCatching { it.getCrashRecordsSinceAsc(0L) }.getOrNull() } ?: emptyList()
            refreshActiveSessionCrashList()
            recomputeAnalytics()
        }
    }

    fun endSessionAndClearData(context: Context) {
        ensureInitialized(context)
        runBlocking {
            endSessionAndClearSessionData()
        }
    }

    fun requestEndSessionAndClearSessionData() {
        controllerScope.launch {
            endSessionAndClearSessionData()
        }
    }

    fun processDetectionFrame(detection: GameDetectionState) {
        val nowMs = detection.updatedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val currentEngine = engine
        val status = _monitoringStatus.value
        controllerScope.launch {
            currentEngine?.gameDetection?.observeLiveUiSnapshot(detection, nowMs)
            liveCrashPipeline.onDetectionFrame(
                detection = detection,
                monitoringStatus = status,
                lowThreshold = _latestConfig.value.lowThreshold,
                bigThreshold = _latestConfig.value.bigThreshold,
                nowMs = nowMs
            )
            val isA11yConnected = ScreenEarnerAccessibilityService.isServiceConnected()
            if (status == MonitoringStatus.MONITORING &&
                isA11yConnected &&
                detection.isGameDetected &&
                currentEngine != null &&
                currentEngine.automationState.value == com.example.data.engine.AutomationState.RUNNING
            ) {
                val cfg = _latestConfig.value
                val effectiveMult = if (detection.gamePhase == "IN_FLIGHT") {
                    detection.detectedMultiplier ?: 0.0
                } else {
                    detection.detectedMultiplier
                        ?: currentEngine.gameDetection.gameState.value.currentMultiplier
                }
                currentEngine.handlePhaseUpdate(
                    phase = detection.gamePhase,
                    liveMultiplier = effectiveMult,
                    config = cfg,
                    limits = _latestLimits.value,
                    currentTimeMs = nowMs,
                    siteButtonState = detection.uiTargets.siteButtonState,
                    observationOnly = !cfg.isAutoBetEnabled && !cfg.isAutoCashOutEnabled
                )
            }
        }
    }

    private suspend fun handleConfirmedLivePipelineEmission(emission: ConfirmedCrashEmission) {
        val repo = repository
        val currentEngine = engine
        val cfg = _latestConfig.value
        recordingMutex.withLock {
            val crashRecord = emission.crashRecord
            val multRecord = emission.multiplierRecord
            val sid = crashRecord.sessionId.ifBlank { _activeSessionId.value }
            val inserted = if (repo != null) {
                repo.insertConfirmedCrashIfAbsent(crashRecord, multRecord)
            } else {
                true
            }
            if (!inserted) return@withLock

            if (crashRecord.gameId.isNotBlank() && _selectedGameId.value != crashRecord.gameId) {
                _selectedGameId.value = crashRecord.gameId
            }

            val updatedSessionList = if (repo != null && sid.isNotBlank()) {
                repo.getCrashRecordsForSessionOnce(sid)
            } else {
                (_activeSessionCrashRecords.value + crashRecord)
            }.sortedWith(compareByDescending<CrashRecord> { it.crashTimestamp }.thenByDescending { it.id })

            val count = updatedSessionList.size
            _activeSessionCrashRecords.value = updatedSessionList
            _sessionCrashesRecorded.value = count
            if (repo != null) {
                cachedCrashRecords = runCatching { repo.getCrashRecordsSinceAsc(0L) }.getOrDefault(updatedSessionList)
                runCatching {
                    repo.upsertMonitoringSession(
                        MonitoringSessionEntity(
                            sessionId = sid,
                            gameId = crashRecord.gameId,
                            gameName = crashRecord.gameName,
                            startedAtMs = sessionStateManager?.getSessionStartedMs()?.takeIf { it > 0L } ?: crashRecord.crashTimestamp,
                            endedAtMs = null,
                            confirmedCrashCount = count,
                            isActive = true
                        )
                    )
                }
            } else {
                cachedCrashRecords = (cachedCrashRecords + crashRecord)
            }

            currentEngine?.setObservedCrashCount(count)
            currentEngine?.gameDetection?.recordConfirmedLiveCrash(crashRecord, count)
            currentEngine?.recordAuthoritativeCompletedRound(
                multiplier = crashRecord.multiplier,
                config = cfg,
                sessionCrashCount = count,
                currentTimeMs = crashRecord.crashTimestamp,
                limits = _latestLimits.value,
                platformName = multRecord.platform.ifBlank { crashRecord.gameName }
            )
            currentEngine?.gameDetection?.updatePipelineStatus(
                pipelineStatus = MonitoringPipelineStatus.MONITORING_LIVE,
                reason = "Recorded confirmed crash (${String.format(java.util.Locale.US, "%.2fx", crashRecord.multiplier)})",
                sessionId = sid,
                sessionCrashCount = count,
                latestRoundId = crashRecord.roundIdentifier
            )

            controllerScope.launch {
                evaluatePatternStrategies(repo)
            }
            recomputeAnalytics(crashRecord.crashTimestamp)
        }
    }

    suspend fun restorePersistedSessionIfIntended() {
        val mgr = sessionStateManager ?: return
        val repo = repository ?: return
        val currentEngine = engine ?: return
        val savedSid = mgr.getActiveSessionId()
        if (savedSid.isBlank()) return

        recordingMutex.withLock {
            _activeSessionId.value = savedSid
            val sessionCrashes = runCatching { repo.getCrashRecordsForSessionOnce(savedSid) }.getOrDefault(emptyList())
            cachedCrashRecords = runCatching { repo.getCrashRecordsSinceAsc(0L) }.getOrDefault(sessionCrashes)
            _activeSessionCrashRecords.value = sessionCrashes.sortedWith(
                compareByDescending<CrashRecord> { it.crashTimestamp }.thenByDescending { it.id }
            )
            currentEngine.gameDetection.restoreSessionSnapshot(savedSid, sessionCrashes)
            val baselineGame = mgr.getBaselineGameId().ifBlank { null }
            val loadedBaseline = mgr.loadBaselineSnapshot(baselineGame)
            if (!loadedBaseline.isNullOrEmpty()) {
                baselineSnapshotCache = loadedBaseline
                baselineGameIdCache = baselineGame.orEmpty()
            }

            if (mgr.isIntendedMonitoringActive()) {
                currentEngine.restoreMonitoringRunningPreservingSession(
                    config = _latestConfig.value,
                    limits = _latestLimits.value,
                    confirmedCrashCount = sessionCrashes.size
                )
                val restoredStatus = when {
                    !ScreenEarnerAccessibilityService.isServiceConnected() -> MonitoringPipelineStatus.ACCESSIBILITY_DISCONNECTED
                    !loadedBaseline.isNullOrEmpty() && sessionCrashes.isNotEmpty() -> MonitoringPipelineStatus.MONITORING_LIVE
                    !loadedBaseline.isNullOrEmpty() -> MonitoringPipelineStatus.BASELINE_CAPTURED_WAITING_FOR_NEW_CRASH
                    else -> MonitoringPipelineStatus.WAITING_FOR_GAME
                }
                _monitoringStatus.value = MonitoringStatus.MONITORING
                _sessionCrashesRecorded.value = sessionCrashes.size
                liveCrashPipeline.configureSession(
                    sessionId = savedSid,
                    initialSequence = sessionCrashes.size,
                    existingDedupKeys = sessionCrashes.map { it.dedupKey }
                )
                currentEngine.gameDetection.updatePipelineStatus(
                    pipelineStatus = restoredStatus,
                    reason = "Restored active session cleanly (${sessionCrashes.size} recorded crashes; baseline preserved)",
                    sessionId = savedSid,
                    sessionCrashCount = sessionCrashes.size
                )
            } else {
                currentEngine.setObservedCrashCount(sessionCrashes.size)
                val isPaused = currentEngine.automationState.value == com.example.data.engine.AutomationState.PAUSED ||
                    mgr.getDesiredMonitoringStatus() == MonitoringStatus.PAUSED
                _monitoringStatus.value = if (isPaused) MonitoringStatus.PAUSED else MonitoringStatus.STOPPED
                _sessionCrashesRecorded.value = sessionCrashes.size
                liveCrashPipeline.configureSession(
                    sessionId = savedSid,
                    initialSequence = sessionCrashes.size,
                    existingDedupKeys = sessionCrashes.map { it.dedupKey }
                )
                currentEngine.gameDetection.updatePipelineStatus(
                    pipelineStatus = if (isPaused) {
                        MonitoringPipelineStatus.PAUSED
                    } else {
                        MonitoringPipelineStatus.STOPPED
                    },
                    reason = "Restored saved session (${sessionCrashes.size} crashes)",
                    sessionId = savedSid,
                    sessionCrashCount = sessionCrashes.size
                )
            }
            recomputeAnalytics()
        }
    }

    private fun refreshActiveSessionCrashList() {
        val sid = _activeSessionId.value
        val activeList = if (sid.isNotBlank()) {
            cachedCrashRecords.filter { it.sessionId == sid }
        } else {
            emptyList()
        }
        _activeSessionCrashRecords.value = activeList
        _sessionCrashesRecorded.value = activeList.size
        engine?.setObservedCrashCount(activeList.size)
    }

    fun setSelectedGameId(gameId: String) {
        if (gameId.isNotBlank() && _selectedGameId.value != gameId) {
            _selectedGameId.value = gameId
            recomputeAnalytics()
        }
    }

    suspend fun clearHistoryByScope(scope: ClearHistoryScope, gameId: String = _selectedGameId.value) {
        val repo = repository ?: return
        val sid = _activeSessionId.value
        recordingMutex.withLock {
            repo.clearHistoryByScope(scope, sid, gameId)
            when (scope) {
                ClearHistoryScope.CURRENT_SESSION -> {
                    baselineSnapshotCache = null
                    baselineGameIdCache = ""
                    if (gameId.isNotBlank()) sessionStateManager?.clearBaselineForGame(gameId)
                    _activeSessionCrashRecords.value = emptyList()
                    _sessionCrashesRecorded.value = 0
                    engine?.resetSessionStats()
                    engine?.gameDetection?.resetLiveSessionTelemetry(sessionId = sid)
                }
                ClearHistoryScope.SELECTED_GAME_ARCHIVED -> {
                    // Active session stays running, archived records for selected game are deleted
                }
                ClearHistoryScope.ALL_HISTORY -> {
                    baselineSnapshotCache = null
                    baselineGameIdCache = ""
                    sessionStateManager?.clearBaselineForGame(gameId)
                    _activeSessionCrashRecords.value = emptyList()
                    _sessionCrashesRecorded.value = 0
                    cachedPatternOccurrences = emptyList()
                    engine?.resetSessionStats()
                    engine?.gameDetection?.resetLiveSessionTelemetry(sessionId = sid)
                }
            }
            cachedCrashRecords = runCatching { repo.getCrashRecordsSinceAsc(0L) }.getOrDefault(emptyList())
            refreshActiveSessionCrashList()
            recomputeAnalytics()
        }
    }

    suspend fun countClearScopeRecords(scope: ClearHistoryScope, gameId: String = _selectedGameId.value): Int {
        val repo = repository ?: return 0
        return repo.countClearScopeRecords(scope, _activeSessionId.value, gameId)
    }

    suspend fun saveAlarmProfile(profile: AlarmProfileEntity) {
        repository?.upsertAlarmProfile(profile)
    }

    private fun recomputeAnalytics(nowMs: Long = System.currentTimeMillis()) {
        controllerScope.launch(Dispatchers.Default) {
            try {
                val cfg = _latestConfig.value
                val gameId = _selectedGameId.value
                val sessionScopedRecords = if (gameId == "ALL") {
                    _activeSessionCrashRecords.value
                } else {
                    _activeSessionCrashRecords.value.filter { it.gameId == gameId }
                }
                val historyScopedRecords = if (gameId == "ALL") {
                    cachedCrashRecords
                } else {
                    cachedCrashRecords.filter { it.gameId == gameId }
                }
                val strategies = _latestCustomStrategies.value
                val occurrences = if (gameId == "ALL") {
                    cachedPatternOccurrences
                } else {
                    cachedPatternOccurrences.filter { it.gameId == gameId }
                }
                val currentGameState = engine?.gameDetection?.gameState?.value ?: GameState()
                val scopedGameName = when {
                    gameId == "ALL" -> "All Games"
                    gameId == "unknown" -> "Unknown"
                    else -> historyScopedRecords.lastOrNull()?.gameName?.takeIf { it.isNotBlank() }
                        ?: com.example.data.engine.GameAdapterRegistry.allAdapters()
                            .firstOrNull { it.gameId == gameId }?.displayedGameName
                        ?: currentGameState.displayedGameName.takeIf { currentGameState.gameId == gameId && it.isNotBlank() }
                        ?: "Unknown"
                }

                val report = StatisticalAnalysisEngine.analyzeCompletedCrashes(
                    records = sessionScopedRecords,
                    config = cfg,
                    minBigGapMinutes = 5,
                    maxBigGapMinutes = 15,
                    gameId = gameId,
                    gameName = scopedGameName
                )
                val bundle = HourlyAnalysisEngine.buildMultiWindowBundle(
                    allRecords = historyScopedRecords,
                    config = cfg,
                    minBigIntervalMinutes = 5,
                    maxBigIntervalMinutes = 15,
                    gameId = gameId,
                    gameName = scopedGameName,
                    nowMs = nowMs
                )
                val agent = SmartAnalysisAgent.generateExplanation(
                    gameState = currentGameState,
                    strategies = strategies,
                    report = report,
                    hourlyBundle = bundle,
                    occurrences = occurrences,
                    config = cfg,
                    nowMs = nowMs
                )
                _statisticalReport.value = report
                _multiWindowBundle.value = bundle
                _smartAgentExplanation.value = agent
            } catch (t: Throwable) {
                Log.e(TAG, "Analytics computation error", t)
            }
        }
    }

    private fun startBackgroundLoops(repo: ScreenEarnerRepository) {
        if (lastLoopsRepo === repo && observerJob != null && observerJob?.isActive == true) return
        loopsParentJob?.cancel()
        observerJob?.cancel()
        tickerJob?.cancel()
        val parent = SupervisorJob()
        loopsParentJob = parent
        lastLoopsRepo = repo
        val loopScope = CoroutineScope(Dispatchers.Default + parent + safeExceptionHandler)

        loopScope.launch {
            try {
                repo.seedDefaultsIfEmpty()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "seedDefaultsIfEmpty aborted: ${t.message}")
            }
        }

        loopScope.launch {
            repo.strategyConfig
                .catch { e -> Log.w(TAG, "strategyConfig loop stopped: ${e.message}") }
                .collect { cfg ->
                    if (cfg != null) {
                        _latestConfig.value = cfg
                        engine?.updateCachedConfig(cfg)
                        engine?.strategyEngine?.bindStrategyConfig(cfg)
                        engine?.gameDetection?.gameState?.value?.let { gs ->
                            CurrencyManager.updateFromConfigAndGameState(cfg, gs)
                        }
                        recomputeAnalytics()
                    }
                }
        }

        loopScope.launch {
            repo.riskLimits
                .catch { e -> Log.w(TAG, "riskLimits loop stopped: ${e.message}") }
                .collect { lim ->
                    if (lim != null) {
                        _latestLimits.value = lim
                        engine?.updateCachedLimits(lim)
                    }
                }
        }

        loopScope.launch {
            repo.customStrategies
                .catch { e -> Log.w(TAG, "customStrategies loop stopped: ${e.message}") }
                .collect { strategies ->
                    _latestCustomStrategies.value = strategies
                    evaluatePatternStrategies(repo)
                    recomputeAnalytics()
                }
        }

        loopScope.launch {
            repo.notificationSettings
                .catch { e -> Log.w(TAG, "notificationSettings loop stopped: ${e.message}") }
                .collect { settings ->
                    if (settings != null) {
                        _latestNotificationSettings.value = settings
                        appContextRef?.let { ctx ->
                            AndroidSystemNotificationDispatcher.ensureAllChannels(ctx, settings)
                        }
                    }
                }
        }

        loopScope.launch {
            AccessibilityController.diagnosticMessage
                .catch { e -> Log.w(TAG, "diagnosticMessage loop stopped: ${e.message}") }
                .collect { msg ->
                    if (!msg.isNullOrBlank()) {
                        engine?.publishStatusTicker(msg)
                    }
                }
        }

        loopScope.launch {
            repo.crashRecords
                .catch { e -> Log.w(TAG, "crashRecords loop stopped: ${e.message}") }
                .collect { crashes ->
                    cachedCrashRecords = crashes
                    refreshActiveSessionCrashList()
                    recomputeAnalytics()
                    evaluatePatternStrategies(repo)
                }
        }

        loopScope.launch {
            repo.patternOccurrences
                .catch { e -> Log.w(TAG, "patternOccurrences loop stopped: ${e.message}") }
                .collect { occurrences ->
                    cachedPatternOccurrences = occurrences
                    recomputeAnalytics()
                }
        }

        loopScope.launch {
            repo.multiplierRecords
                .catch { e -> Log.w(TAG, "multiplierRecords loop stopped: ${e.message}") }
                .collect { records ->
                    val currentEngine = engine ?: return@collect
                    currentEngine.syncSessionWithCrashHistory(records, _latestLimits.value)
                    val activeSid = _activeSessionId.value
                    val sidRecords = if (activeSid.isNotBlank()) {
                        records.filter { it.sessionId == activeSid }
                    } else {
                        emptyList()
                    }
                    val cutoff30m = System.currentTimeMillis() - RibbonHistoryCollector.ROLLING_WINDOW_MS
                    val windowRecords = sidRecords.filter { it.crashTimestamp >= cutoff30m }
                    currentEngine.gameDetection.refreshHistoryMetadataFromRecords(windowRecords)
                }
        }

        loopScope.launch {
            repo.alarmProfiles
                .catch { e -> Log.w(TAG, "alarmProfiles loop stopped: ${e.message}") }
                .collect { profiles ->
                    _alarmProfiles.value = profiles
                }
        }

        loopScope.launch {
            repo.monitoringSessions
                .catch { e -> Log.w(TAG, "monitoringSessions loop stopped: ${e.message}") }
                .collect { sessions ->
                    _monitoringSessions.value = sessions
                }
        }

        loopScope.launch {
            repo.shortcuts
                .catch { e -> Log.w(TAG, "shortcuts loop stopped: ${e.message}") }
                .collect { list ->
                    _latestShortcuts.value = list
                    engine?.updateCalibratedShortcuts(list)
                }
        }

        observerJob = loopScope.launch {
            var isFirstEmission = true
            ScreenEarnerAccessibilityService.detectionState
                .catch { e -> Log.w(TAG, "detectionState loop stopped: ${e.message}") }
                .collect { detection ->
                    if (isFirstEmission) {
                        isFirstEmission = false
                        if (!ScreenEarnerAccessibilityService.isServiceConnected() && !detection.isGameDetected) {
                            return@collect
                        }
                    }
                    processDetectionEvent(
                        detection = detection,
                        isAccessibilityConnected = ScreenEarnerAccessibilityService.isServiceConnected(),
                        nowMs = detection.updatedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
                    )
                }
        }

        tickerJob = loopScope.launch {
            var tickCounter = 0L
            while (isActive) {
                delay(1000L)
                tickCounter++
                val now = System.currentTimeMillis()
                try {
                    val currentEngine = engine
                    currentEngine?.tickTimers(_latestConfig.value, now)
                    currentEngine?.gameDetection?.checkBalanceStaleness(now)
                    currentEngine?.gameDetection?.updateAccessibilityConnected(
                        ScreenEarnerAccessibilityService.isServiceConnected()
                    )
                    checkClockHourRollover(repo, now)
                    if (tickCounter % 5L == 0L) {
                        evaluateTimeSchedulesOnTick(repo, now)
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "Ticker loop iteration aborted: ${t.message}")
                }
            }
        }
    }

    private suspend fun evaluateTimeSchedulesOnTick(repo: ScreenEarnerRepository, nowMs: Long) {
        if (!bypassMonitoringGuardForTest && engine != null) {
            val isRunningActive = _monitoringStatus.value == MonitoringStatus.MONITORING
            val isGameDetected = engine?.gameDetection?.gameState?.value?.isDetected == true
            if (!isRunningActive || !isGameDetected) return
        }
        val strategies = _latestCustomStrategies.value
        if (strategies.none { it.isEnabled }) return
        val crashes = cachedCrashRecords
        val cfg = _latestConfig.value
        val settings = _latestNotificationSettings.value
        val mgr = notificationManager
        evaluationMutex.withLock {
            val batch = patternDetectionEngine.evaluateTimeSchedulesTick(
                strategies = strategies,
                crashRecords = crashes,
                bigThreshold = cfg.bigThreshold,
                nowMs = nowMs
            )
            if (batch.events.isNotEmpty() || batch.newOccurrences.isNotEmpty()) {
                val occIds = mutableMapOf<String, Long>()
                batch.newOccurrences.forEach { occ ->
                    val id = repo.insertPatternOccurrence(occ)
                    if (id > 0L) occIds[occ.occurrenceKey] = id
                }
                val orig = strategies.associateBy { it.id }
                batch.updatedStrategies.forEach { upd ->
                    if (orig[upd.id] != null && orig[upd.id] != upd) {
                        repo.updateCustomStrategy(upd)
                    }
                }
                batch.events.forEach { ev ->
                    mgr?.dispatchPatternAlert(
                        event = ev,
                        settings = settings,
                        nowMs = nowMs,
                        gameId = ev.strategy.targetGameId.takeIf { it != "ALL" },
                        occurrenceRecordId = occIds[ev.occurrenceKey]
                    )
                    if (ev.notificationType == StrategyNotificationType.STRATEGY_REACHED) {
                        activateCustomPatternBetSession(ev.strategy.name, cfg, nowMs)
                    }
                }
            }
        }
    }

    private fun activateCustomPatternBetSession(strategyName: String, config: StrategyConfig, nowMs: Long) {
        val currentEngine = engine ?: return
        if (!config.isAutoBetEnabled) return
        if (_monitoringStatus.value != MonitoringStatus.MONITORING) return
        if (currentEngine.automationState.value != com.example.data.engine.AutomationState.RUNNING) return
        val activated = currentEngine.strategyEngine.activateExternalPatternTrigger(
            strategyName = strategyName,
            config = config,
            currentTimeMs = nowMs
        )
        if (activated) {
            currentEngine.gameDetection.updateStrategyTelemetry(
                currentEngine.strategyEngine.getState(),
                "Pattern Triggered: $strategyName"
            )
            currentEngine.notifyStrategyWaitingToBet(strategyName)
        }
    }

    /**
     * Single authoritative, coroutine-serialized pipeline for incoming Accessibility detection snapshots.
     * - Captures baseline first (0 DB inserts).
     * - Diffs subsequent snapshots to persist ONLY genuinely new completed crashes for the active session.
     * - Updates pipelineStatus across all 8 truthful states.
     */
    suspend fun processDetectionEvent(
        detection: GameDetectionState,
        isAccessibilityConnected: Boolean = ScreenEarnerAccessibilityService.isServiceConnected(),
        nowMs: Long = detection.updatedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
    ) {
        val currentEngine = engine ?: return
        val repo = repository ?: return
        recordingMutex.withLock {
            try {
                val cfg = _latestConfig.value
                val autoState = currentEngine.automationState.value

                val targetProfileId = GameDetection.resolveProfileIdForPlatform(detection.platformName)
                if (targetProfileId != null && targetProfileId != lastAutoProfileId) {
                    lastAutoProfileId = targetProfileId
                    currentEngine.updateActivePlatform(detection.platformName)
                    controllerScope.launch {
                        runCatching { repo.setActiveProfile(targetProfileId) }
                    }
                } else if (detection.isGameDetected) {
                    currentEngine.updateActivePlatform(detection.platformName)
                }

                if (detection.isGameDetected && detection.gameId.isNotBlank()) {
                    if (_selectedGameId.value != detection.gameId) {
                        _selectedGameId.value = detection.gameId
                    }
                    if (lastDetectedGameIdForNotice != detection.gameId) {
                        lastDetectedGameIdForNotice = detection.gameId
                        val settings = _latestNotificationSettings.value
                        controllerScope.launch {
                            notificationManager?.dispatchSystemNotice(
                                strategyName = "${detection.displayedGameName} Monitor",
                                type = StrategyNotificationType.GAME_DETECTED,
                                title = "Game Detected: ${detection.displayedGameName}",
                                message = "Monitoring ${detection.displayedGameName} on ${detection.platformName}.",
                                occurrenceKey = "game_${detection.gameId}_${detection.foregroundPackage}",
                                settings = settings,
                                gameId = detection.gameId
                            )
                        }
                    }
                }

                // Update live UI telemetry without writing or simulating any DB crash records
                val (sawRoundTransition, missingHistoryElapsed) =
                    currentEngine.gameDetection.observeLiveUiSnapshot(detection, nowMs)

                val sid = _activeSessionId.value
                val existingSessionCrashes = if (sid.isNotBlank()) {
                    repo.getCrashRecordsForSessionOnce(sid)
                } else {
                    emptyList()
                }
                var sessionCrashCount = existingSessionCrashes.size

                if (!isAccessibilityConnected) {
                    _monitoringStatus.value = MonitoringStatus.PERMISSION_REQUIRED
                    currentEngine.gameDetection.updatePipelineStatus(
                        pipelineStatus = MonitoringPipelineStatus.ACCESSIBILITY_DISCONNECTED,
                        reason = "Accessibility Service is disconnected — enable Predict Manager Live Monitor in Android Settings",
                        sessionId = sid,
                        sessionCrashCount = sessionCrashCount
                    )
                    if (autoState == com.example.data.engine.AutomationState.RUNNING && !wasAccessibilityDisconnectedNoticed) {
                        wasAccessibilityDisconnectedNoticed = true
                        val settings = _latestNotificationSettings.value
                        controllerScope.launch {
                            notificationManager?.dispatchSystemNotice(
                                strategyName = "Predict Manager Automation",
                                type = StrategyNotificationType.MONITORING_ERROR,
                                title = "Automation Paused — Accessibility Off",
                                message = "Predict Manager Accessibility Service disconnected while automation was running.",
                                occurrenceKey = "a11y_disc_${nowMs / 60_000L}",
                                settings = settings,
                                nowMs = nowMs
                            )
                        }
                    }
                    CurrencyManager.updateFromConfigAndGameState(cfg, currentEngine.gameDetection.gameState.value)
                    return@withLock
                }
                wasAccessibilityDisconnectedNoticed = false

                if (detection.gamePhase == "CRASHED" && lastObservedPhaseForExecution != "CRASHED") {
                    currentEngine.resetBetFailureEpisode()
                }
                lastObservedPhaseForExecution = detection.gamePhase

                if (autoState == com.example.data.engine.AutomationState.PAUSED) {
                    _monitoringStatus.value = MonitoringStatus.PAUSED
                    currentEngine.gameDetection.updatePipelineStatus(
                        pipelineStatus = MonitoringPipelineStatus.PAUSED,
                        reason = "Monitoring paused — $sessionCrashCount crash(es) preserved in current session",
                        sessionId = sid,
                        sessionCrashCount = sessionCrashCount
                    )
                    CurrencyManager.updateFromConfigAndGameState(cfg, currentEngine.gameDetection.gameState.value)
                    return@withLock
                }

                if (autoState == com.example.data.engine.AutomationState.IDLE ||
                    autoState == com.example.data.engine.AutomationState.EMERGENCY_STOP
                ) {
                    _monitoringStatus.value = MonitoringStatus.STOPPED
                    currentEngine.gameDetection.updatePipelineStatus(
                        pipelineStatus = MonitoringPipelineStatus.STOPPED,
                        reason = "Monitoring stopped",
                        sessionId = sid,
                        sessionCrashCount = sessionCrashCount
                    )
                    CurrencyManager.updateFromConfigAndGameState(cfg, currentEngine.gameDetection.gameState.value)
                    return@withLock
                }

                _monitoringStatus.value = MonitoringStatus.MONITORING

                if (!detection.isGameDetected) {
                    currentEngine.gameDetection.updatePipelineStatus(
                        pipelineStatus = MonitoringPipelineStatus.WAITING_FOR_GAME,
                        reason = "Waiting for crash game window",
                        sessionId = sid,
                        sessionCrashCount = sessionCrashCount
                    )
                    currentEngine.gameDetection.updateHistoryStatus(
                        status = HistoryCollectionStatus.HISTORY_NOT_STARTED,
                        reason = "Waiting for active crash game window.",
                        nowMs = nowMs
                    )
                    CurrencyManager.updateFromConfigAndGameState(cfg, currentEngine.gameDetection.gameState.value)
                    currentEngine.updateLiveDiagnostics(detection.uiTargets, currentEngine.gameDetection.gameState.value, cfg)
                    return@withLock
                }

                // Active RUNNING + Game Detected -> Resolve Session ID and Ribbon History
                val effectiveSid = sid.ifBlank {
                    val created = sessionStateManager?.ensureActiveSession(nowMs)
                        ?: "sess_${nowMs}_${UUID.randomUUID().toString().take(8)}"
                    _activeSessionId.value = created
                    created
                }
                val targets = detection.uiTargets
                val ribbonItems = if (targets.ribbonItems.isNotEmpty()) {
                    targets.ribbonItems
                } else {
                    targets.ribbonMultipliers.mapIndexed { idx, value ->
                        ExtractedHistoryItem(
                            multiplier = value,
                            orderIndex = idx,
                            source = CollectionSource.VISIBLE_RIBBON,
                            displayText = "${RibbonHistoryCollector.formatMultKey(value)}x"
                        )
                    }
                }

                if (ribbonItems.isEmpty()) {
                    val notReadable = missingHistoryElapsed >= 4_000L || detection.liveDetectionLost
                    currentEngine.gameDetection.updatePipelineStatus(
                        pipelineStatus = if (notReadable) {
                            MonitoringPipelineStatus.HISTORY_NOT_READABLE
                        } else {
                            MonitoringPipelineStatus.GAME_DETECTED_WAITING_FOR_HISTORY
                        },
                        reason = if (notReadable) {
                            "Game detected (${detection.displayedGameName}) — Round History ribbon not readable in Accessibility tree"
                        } else {
                            "Game detected (${detection.displayedGameName}) — waiting for Round History ribbon"
                        },
                        sessionId = effectiveSid,
                        sessionCrashCount = sessionCrashCount
                    )
                    currentEngine.gameDetection.updateHistoryStatus(
                        status = HistoryCollectionStatus.HISTORY_UNAVAILABLE,
                        reason = "No visible ribbon multipliers readable in game interface.",
                        nowMs = nowMs
                    )
                    val liveMultNoRibbon = if (detection.gamePhase == "IN_FLIGHT") {
                        detection.detectedMultiplier ?: 0.0
                    } else {
                        detection.detectedMultiplier
                            ?: currentEngine.gameDetection.gameState.value.currentMultiplier
                    }
                    currentEngine.handlePhaseUpdate(
                        phase = detection.gamePhase,
                        liveMultiplier = liveMultNoRibbon,
                        config = cfg,
                        limits = _latestLimits.value,
                        currentTimeMs = nowMs,
                        siteButtonState = targets.siteButtonState,
                        observationOnly = !cfg.isAutoBetEnabled && !cfg.isAutoCashOutEnabled
                    )
                    CurrencyManager.updateFromConfigAndGameState(cfg, currentEngine.gameDetection.gameState.value)
                    currentEngine.updateLiveDiagnostics(targets, currentEngine.gameDetection.gameState.value, cfg)
                    return@withLock
                }

                val gameId = detection.gameId.ifBlank { "unknown" }
                val resolvedPlatform = GameDetection.resolvePlatformFromSignals(
                    packageName = detection.foregroundPackage,
                    detectedUrl = targets.detectedUrl,
                    pageHints = targets.pageTextHints
                ) ?: detection.platformName.ifBlank { "Unknown" }
                val gameName = detection.displayedGameName.ifBlank { resolvedPlatform.ifBlank { "Unknown" } }

                val prevBaseline = loadBaseline(gameId)
                val alignment = RibbonHistoryCollector.alignAuthoritativeLiveSnapshot(
                    previousBaseline = prevBaseline,
                    currentItems = ribbonItems,
                    previousExpanded = previousExpandedPanel,
                    currentExpanded = targets.historyPanelExpanded,
                    sawRoundCompletionTransition = sawRoundTransition
                )
                previousExpandedPanel = targets.historyPanelExpanded

                if (alignment.isBaselineCaptureOnly) {
                    val updatedBase = alignment.updatedBaseline ?: RibbonHistoryCollector.toBaselineEntries(ribbonItems)
                    saveBaseline(gameId, updatedBase)
                    val statusChoice = if (sessionCrashCount > 0) {
                        MonitoringPipelineStatus.MONITORING_LIVE
                    } else {
                        MonitoringPipelineStatus.BASELINE_CAPTURED_WAITING_FOR_NEW_CRASH
                    }
                    currentEngine.gameDetection.updatePipelineStatus(
                        pipelineStatus = statusChoice,
                        reason = alignment.rejectedReason ?: "Baseline captured (${ribbonItems.size} items) — waiting for new completed crash",
                        sessionId = effectiveSid,
                        sessionCrashCount = sessionCrashCount
                    )
                    currentEngine.gameDetection.updateHistoryStatus(
                        status = if (targets.historyPanelExpanded) HistoryCollectionStatus.COLLECTING_VISIBLE_HISTORY else HistoryCollectionStatus.HISTORY_PARTIAL,
                        reason = "Baseline captured (${ribbonItems.size} visible); 0 historical rows imported.",
                        nowMs = nowMs
                    )
                } else if (alignment.newCompletedItems.isNotEmpty() && !currentEngine.isStandbyOutsideSchedule.value) {
                    var insertedAny = false
                    val orderedOldestToNewest = alignment.newCompletedItems.asReversed()
                    orderedOldestToNewest.forEachIndexed { revIdx, item ->
                        val itemTs = item.gameTimestampMs ?: (nowMs + revIdx)
                        val cleanRoundId = item.roundId?.takeIf { it.isNotBlank() }
                        val multKey = RibbonHistoryCollector.formatMultKey(item.multiplier)
                        val dedupKey = when {
                            cleanRoundId != null -> "${effectiveSid}_${gameId}_rid_${cleanRoundId}"
                            item.gameTimestampMs != null -> "${effectiveSid}_${gameId}_gt_${item.gameTimestampMs}_${multKey}"
                            else -> {
                                val seq = nextConfirmedSequence()
                                "${effectiveSid}_${gameId}_seq_${seq}_${multKey}"
                            }
                        }
                        val fallbackCat = ColorClassifier.fallbackCategoryForMultiplier(
                            item.multiplier,
                            cfg.lowThreshold,
                            cfg.bigThreshold
                        )
                        val cat = item.colorCategory ?: fallbackCat
                        val hex = item.detectedColorHex ?: cat.defaultHex
                        val multRecord = MultiplierRecord(
                            sessionId = effectiveSid,
                            gameId = gameId,
                            roundId = cleanRoundId,
                            multiplier = item.multiplier,
                            gameTimestampMs = item.gameTimestampMs,
                            observationTimestampMs = itemTs,
                            crashTimestamp = itemTs,
                            platform = resolvedPlatform,
                            timestampSource = if (item.gameTimestampMs != null) TimestampSource.GAME_PROVIDED else TimestampSource.LOCAL_OBSERVATION,
                            collectionSource = CollectionSource.LIVE_CRASH,
                            collectionStatus = if (targets.historyPanelExpanded) HistoryCollectionStatus.COLLECTING_VISIBLE_HISTORY else HistoryCollectionStatus.HISTORY_PARTIAL,
                            dedupKey = dedupKey,
                            sequenceIndex = item.orderIndex,
                            hasGapBefore = false
                        )
                        val crashRecord = CrashRecord(
                            sessionId = effectiveSid,
                            gameId = gameId,
                            gameName = gameName,
                            multiplier = item.multiplier,
                            detectedColorHex = hex,
                            colorCategory = cat,
                            isPixelColorVerified = item.isPixelColorVerified,
                            crashTimestamp = itemTs,
                            hourStartTimestamp = CalendarHourUtils.hourStartFor(itemTs),
                            sourcePackage = detection.foregroundPackage,
                            roundIdentifier = cleanRoundId,
                            confidence = 1.0f,
                            manuallyCorrected = false,
                            collectionSource = CollectionSource.LIVE_CRASH,
                            dedupKey = dedupKey
                        )

                        val inserted = repo.insertConfirmedCrashIfAbsent(crashRecord, multRecord)
                        if (inserted) {
                            insertedAny = true
                            sessionCrashCount += 1
                            sessionStateManager?.setLastConfirmedRoundKey(dedupKey)
                            currentEngine.gameDetection.recordConfirmedLiveCrash(crashRecord, sessionCrashCount)
                            currentEngine.recordAuthoritativeCompletedRound(
                                multiplier = crashRecord.multiplier,
                                config = cfg,
                                sessionCrashCount = sessionCrashCount,
                                currentTimeMs = crashRecord.crashTimestamp,
                                limits = _latestLimits.value,
                                platformName = resolvedPlatform
                            )
                        }
                    }

                    val updatedBase = alignment.updatedBaseline ?: RibbonHistoryCollector.toBaselineEntries(ribbonItems)
                    saveBaseline(gameId, updatedBase)

                    if (insertedAny) {
                        val updatedList = repo.getCrashRecordsForSessionOnce(effectiveSid)
                        sessionCrashCount = updatedList.size
                        _activeSessionCrashRecords.value = updatedList.sortedWith(
                            compareByDescending<CrashRecord> { it.crashTimestamp }.thenByDescending { it.id }
                        )
                        cachedCrashRecords = runCatching { repo.getCrashRecordsSinceAsc(0L) }.getOrDefault(updatedList)
                        currentEngine.setObservedCrashCount(sessionCrashCount)
                        controllerScope.launch {
                            evaluatePatternStrategies(repo)
                        }
                        recomputeAnalytics(nowMs)
                    }

                    currentEngine.gameDetection.updatePipelineStatus(
                        pipelineStatus = if (sessionCrashCount > 0) MonitoringPipelineStatus.MONITORING_LIVE else MonitoringPipelineStatus.BASELINE_CAPTURED_WAITING_FOR_NEW_CRASH,
                        reason = if (insertedAny) {
                            "Recorded new completed crash (${_activeSessionCrashRecords.value.firstOrNull()?.multiplier?.let { String.format(java.util.Locale.US, "%.2fx", it) } ?: "—"}) — $sessionCrashCount total in session"
                        } else {
                            "Waiting for next completed round"
                        },
                        sessionId = effectiveSid,
                        sessionCrashCount = sessionCrashCount
                    )
                    currentEngine.gameDetection.updateHistoryStatus(
                        status = if (targets.historyPanelExpanded) HistoryCollectionStatus.COLLECTING_VISIBLE_HISTORY else HistoryCollectionStatus.HISTORY_PARTIAL,
                        reason = "Live monitoring active ($sessionCrashCount crash(es) recorded in current session)",
                        nowMs = nowMs
                    )
                } else {
                    if (alignment.updatedBaseline != null && !alignment.isScrollOrExpandRejected && !alignment.isUnchanged) {
                        saveBaseline(gameId, alignment.updatedBaseline)
                    }
                    val statusChoice = if (sessionCrashCount > 0) {
                        MonitoringPipelineStatus.MONITORING_LIVE
                    } else {
                        MonitoringPipelineStatus.BASELINE_CAPTURED_WAITING_FOR_NEW_CRASH
                    }
                    currentEngine.gameDetection.updatePipelineStatus(
                        pipelineStatus = statusChoice,
                        reason = alignment.rejectedReason ?: "Monitoring live — waiting for next crash",
                        sessionId = effectiveSid,
                        sessionCrashCount = sessionCrashCount
                    )
                }

                // Drive live phase execution (BETTING_OPEN stake/bet placement + IN_FLIGHT cash-out)
                val liveMult = if (detection.gamePhase == "IN_FLIGHT") {
                    detection.detectedMultiplier ?: 0.0
                } else {
                    detection.detectedMultiplier
                        ?: currentEngine.gameDetection.gameState.value.currentMultiplier
                }
                currentEngine.handlePhaseUpdate(
                    phase = detection.gamePhase,
                    liveMultiplier = liveMult,
                    config = cfg,
                    limits = _latestLimits.value,
                    currentTimeMs = nowMs,
                    siteButtonState = targets.siteButtonState,
                    observationOnly = !cfg.isAutoBetEnabled && !cfg.isAutoCashOutEnabled
                )

                CurrencyManager.updateFromConfigAndGameState(cfg, currentEngine.gameDetection.gameState.value)
                currentEngine.updateLiveDiagnostics(
                    targets = targets,
                    gameState = currentEngine.gameDetection.gameState.value,
                    config = cfg
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Error processing accessibility detection event", t)
            }
        }
    }

    private suspend fun checkClockHourRollover(repo: ScreenEarnerRepository, nowMs: Long) {
        val currentHourStart = CalendarHourUtils.hourStartFor(nowMs)
        if (currentHourStart <= activeTrackedHourStartMs) return

        hourRolloverMutex.withLock {
            val recheckHour = CalendarHourUtils.hourStartFor(nowMs)
            val closingHourStart = activeTrackedHourStartMs
            if (recheckHour <= closingHourStart) return
            activeTrackedHourStartMs = recheckHour

            try {
                val cfg = _latestConfig.value
                val allCrashes = cachedCrashRecords
                val transition = HourlyAnalysisEngine.finalizeClosingHourSummaries(
                    allRecords = allCrashes,
                    closingHourStartMs = closingHourStart,
                    newHourStartMs = recheckHour,
                    config = cfg,
                    finalizedAtMs = nowMs
                )
                val summaries = transition.savedSummaries
                summaries.forEach { repo.upsertHourlySummary(it) }

                // Reset hourly occurrence alert markers for the new hour while preserving rolling 30m records
                val strategies = repo.getCustomStrategiesOnce()
                strategies.forEach { strat ->
                    if (strat.currentlyApproaching || strat.currentlyReached ||
                        strat.lastTriggeredOccurrenceKey != null || strat.lastApproachedOccurrenceKey != null
                    ) {
                        repo.updateCustomStrategy(
                            strat.copy(
                                currentlyApproaching = false,
                                currentlyReached = false,
                                lastTriggeredOccurrenceKey = null,
                                lastApproachedOccurrenceKey = null
                            )
                        )
                    }
                }

                val activeGameId = _selectedGameId.value
                val activeGameName = summaries.firstOrNull { it.gameId == activeGameId }?.gameName
                    ?: engine?.gameDetection?.gameState?.value?.displayedGameName?.takeIf { it.isNotBlank() }
                    ?: "Unknown"
                val closedLabel = CalendarHourUtils.formatHourWindow(closingHourStart)
                val newLabel = CalendarHourUtils.formatHourWindow(recheckHour)
                val roundCount = summaries.sumOf { it.totalRounds }
                val summaryMsg = "Completed $closedLabel ($roundCount rounds recorded across monitored games). New hourly session $newLabel started; 30-minute rolling stats retained."

                notificationManager?.dispatchHourlyBoundaryNotice(
                    gameId = activeGameId,
                    gameName = activeGameName,
                    closedHourLabel = closedLabel,
                    newHourLabel = newLabel,
                    summaryLine = summaryMsg,
                    settings = _latestNotificationSettings.value,
                    nowMs = nowMs
                )
                recomputeAnalytics(nowMs)
            } catch (t: Throwable) {
                Log.e(TAG, "Error handling clock-hour boundary rollover", t)
            }
        }
    }

    suspend fun evaluatePatternStrategies(repo: ScreenEarnerRepository? = repository) {
        val activeRepo = repo ?: return
        if (!bypassMonitoringGuardForTest && engine != null) {
            val isRunningActive = _monitoringStatus.value == MonitoringStatus.MONITORING
            val isGameDetected = engine?.gameDetection?.gameState?.value?.isDetected == true
            if (!isRunningActive || !isGameDetected) return
        }
        evaluationMutex.withLock {
            try {
                val strategies = activeRepo.getCustomStrategiesOnce()
                val enabled = strategies.filter { it.isEnabled }
                if (enabled.isEmpty()) return

                val nowMs = System.currentTimeMillis()
                val cfg = _latestConfig.value
                val mgr = notificationManager
                val settings = activeRepo.getNotificationSettingsOnce()

                // 1. Evaluate Strategy A / B / C against completed CrashRecord history
                val crashCandidates = cachedCrashRecords.ifEmpty {
                    activeRepo.getCrashRecordsSinceAsc(nowMs - 24 * 60 * 60_000L)
                }
                if (crashCandidates.isNotEmpty()) {
                    val crashBatch = patternDetectionEngine.evaluateStrategiesOnCrashRecords(
                        strategies = strategies,
                        crashRecords = crashCandidates,
                        lowThreshold = cfg.lowThreshold,
                        bigThreshold = cfg.bigThreshold,
                        nowMs = nowMs
                    )
                    val insertedOccIds = mutableMapOf<String, Long>()
                    crashBatch.newOccurrences.forEach { occ ->
                        val rowId = activeRepo.insertPatternOccurrence(occ)
                        if (rowId > 0L) {
                            insertedOccIds[occ.occurrenceKey] = rowId
                        }
                    }
                    val origById = strategies.associateBy { it.id }
                    crashBatch.updatedStrategies.forEach { updated ->
                        val original = origById[updated.id]
                        if (original != null && updated != original) {
                            activeRepo.updateCustomStrategy(updated)
                        }
                    }
                    crashBatch.events.forEach { event ->
                        val occId = insertedOccIds[event.occurrenceKey]
                        mgr?.dispatchPatternAlert(
                            event = event,
                            settings = settings,
                            nowMs = nowMs,
                            occurrenceRecordId = occId
                        )
                        if (event.notificationType == StrategyNotificationType.STRATEGY_REACHED) {
                            activateCustomPatternBetSession(event.strategy.name, cfg, nowMs)
                        }
                    }
                }

                // 2. Evaluate CUSTOM_CONDITION strategies against 30m MultiplierRecord chronological stream
                val customOnly = strategies.filter {
                    it.isEnabled && it.strategyMode == com.example.data.model.StrategyMode.CUSTOM_CONDITION
                }
                if (customOnly.isNotEmpty()) {
                    val chronological = activeRepo.getRecentChronologicalRecords(
                        windowMinutes = RibbonHistoryCollector.ROLLING_WINDOW_MINUTES,
                        nowMs = nowMs
                    )
                    if (chronological.isNotEmpty()) {
                        val batch = patternDetectionEngine.evaluateAllStrategies(
                            strategies = customOnly,
                            records = chronological,
                            nowMs = nowMs
                        )
                        val originalById = customOnly.associateBy { it.id }
                        batch.updatedStrategies.forEach { updated ->
                            val original = originalById[updated.id]
                            if (original != null && updated != original) {
                                activeRepo.updateCustomStrategy(updated)
                            }
                        }
                        batch.events.forEach { event ->
                            mgr?.dispatchPatternAlert(event, settings, nowMs)
                            if (event.notificationType == StrategyNotificationType.STRATEGY_REACHED) {
                                activateCustomPatternBetSession(event.strategy.name, cfg, nowMs)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Pattern strategy evaluation failed", t)
            }
        }
    }

    fun triggerManualHistoryCollection(context: Context) {
        ensureInitialized(context)
        val repo = repository ?: return
        val currentEngine = engine ?: return
        val a11y = ScreenEarnerAccessibilityService.instance
        val nowMs = System.currentTimeMillis()

        if (a11y == null) {
            currentEngine.gameDetection.updateHistoryStatus(
                status = HistoryCollectionStatus.HISTORY_UNAVAILABLE,
                reason = "Accessibility Service is not connected; cannot read game ribbon.",
                nowMs = nowMs
            )
            controllerScope.launch {
                maybeDispatchHistoryUnavailableNotice(
                    repo = repo,
                    reason = "Accessibility Service is disconnected. Enable it to collect 30-minute ribbon history.",
                    nowMs = nowMs
                )
            }
            return
        }

        val gs = currentEngine.gameDetection.gameState.value
        val platform = gs.platformName
            .takeIf { it.isNotBlank() && it != "Waiting for Game..." }
            ?: "Chrome Crash Game"

        a11y.collectAccessibleRibbonHistory(
            onStatusUpdate = { status, reason ->
                controllerScope.launch {
                    val recordsIn30m = repo.getRecentChronologicalRecords(
                        windowMinutes = RibbonHistoryCollector.ROLLING_WINDOW_MINUTES,
                        nowMs = System.currentTimeMillis()
                    )
                    currentEngine.gameDetection.updateHistoryStatus(
                        status = status,
                        reason = reason,
                        recordsInWindow = recordsIn30m
                    )
                    if (status == HistoryCollectionStatus.HISTORY_UNAVAILABLE ||
                        status == HistoryCollectionStatus.HISTORY_ERROR
                    ) {
                        maybeDispatchHistoryUnavailableNotice(repo, reason)
                    }
                }
            },
            onBatchExtracted = { items, isExhausted, covers30Min, source ->
                controllerScope.launch {
                    val cfg = _latestConfig.value
                    val activeGameId = gs.gameId.ifBlank { _selectedGameId.value.ifBlank { "unknown" } }
                    val activeGameName = gs.displayedGameName.ifBlank { "Unknown" }
                    val result = currentEngine.gameDetection.ribbonHistoryCollector.processExpandedHistoryBatch(
                        items = items,
                        platformName = platform,
                        gameId = activeGameId,
                        isScrolledExhausted = isExhausted,
                        coversFull30Minutes = covers30Min,
                        source = source
                    )
                    if (result.newRecordsToPersist.isNotEmpty()) {
                        val activeSid = _activeSessionId.value.ifBlank {
                            sessionStateManager?.ensureActiveSession(nowMs)
                                ?: "sess_${nowMs}_${UUID.randomUUID().toString().take(8)}"
                        }.also { _activeSessionId.value = it }

                        result.newRecordsToPersist.forEachIndexed { idx, rec ->
                            val srcItem = items.getOrNull(idx)
                            val fallbackCat = ColorClassifier.fallbackCategoryForMultiplier(
                                rec.multiplier,
                                cfg.lowThreshold,
                                cfg.bigThreshold
                            )
                            val cat = srcItem?.colorCategory ?: fallbackCat
                            val hex = srcItem?.detectedColorHex ?: cat.defaultHex
                            val scopedKey = "${activeSid}_${rec.dedupKey}"
                            val crashRow = CrashRecord(
                                sessionId = activeSid,
                                gameId = activeGameId,
                                gameName = activeGameName,
                                multiplier = rec.multiplier,
                                detectedColorHex = hex,
                                colorCategory = cat,
                                isPixelColorVerified = srcItem?.isPixelColorVerified == true,
                                crashTimestamp = rec.crashTimestamp,
                                hourStartTimestamp = CalendarHourUtils.hourStartFor(rec.crashTimestamp),
                                sourcePackage = gs.sourcePackage,
                                roundIdentifier = rec.roundId,
                                confidence = 1.0f,
                                manuallyCorrected = false,
                                collectionSource = rec.collectionSource,
                                dedupKey = scopedKey
                            )
                            repo.insertConfirmedCrashIfAbsent(crashRow, rec.copy(sessionId = activeSid, dedupKey = scopedKey))
                        }
                    }
                    val recordsIn30m = repo.getRecentChronologicalRecords(
                        windowMinutes = RibbonHistoryCollector.ROLLING_WINDOW_MINUTES
                    )
                    currentEngine.gameDetection.updateHistoryStatus(
                        status = result.status,
                        reason = result.reason,
                        recordsInWindow = recordsIn30m
                    )
                    evaluatePatternStrategies(repo)
                    recomputeAnalytics()
                }
            }
        )
    }

    private suspend fun maybeDispatchHistoryUnavailableNotice(
        repo: ScreenEarnerRepository,
        reason: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val enabledStrategies = repo.getCustomStrategiesOnce().filter { it.isEnabled }
        if (enabledStrategies.isEmpty()) return
        if (nowMs - lastHistoryUnavailableNoticeMs < 5 * 60_000L) return
        lastHistoryUnavailableNoticeMs = nowMs

        val settings = repo.getNotificationSettingsOnce()
        notificationManager?.dispatchSystemNotice(
            strategyName = enabledStrategies.first().name,
            type = StrategyNotificationType.HISTORY_UNAVAILABLE,
            title = "History Collection Unavailable",
            message = reason,
            occurrenceKey = "hist_unavail_${nowMs / (5 * 60_000L)}",
            settings = settings,
            nowMs = nowMs
        )
    }

    fun retryBalanceDetection() {
        engine?.gameDetection?.retryBalanceDetection()
    }
}
