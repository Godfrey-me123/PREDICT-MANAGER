package com.example.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.database.ScreenEarnerRepository
import com.example.data.engine.PatternAlertEvent
import com.example.data.model.AlarmProfileEntity
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.NotificationDeliveryStatus
import com.example.data.model.NotificationHistoryRecord
import com.example.data.model.NotificationInteractionStatus
import com.example.data.model.NotificationPriorityLevel
import com.example.data.model.NotificationSettings
import com.example.data.model.MonitoringStatus
import com.example.data.model.StrategyNotificationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

interface SystemNotificationDispatcher {
    fun hasNotificationPermission(context: Context): Boolean
    fun isDoNotDisturbBlocking(context: Context, priorityLevel: NotificationPriorityLevel): Boolean = false
    fun postSystemNotification(
        context: Context,
        notificationId: Int,
        title: String,
        message: String,
        priorityLevel: NotificationPriorityLevel,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean,
        historyRecordId: Long
    ): Boolean

    fun postChannelNotification(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        message: String,
        priorityLevel: NotificationPriorityLevel,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean,
        historyRecordId: Long,
        targetTab: Int = MainActivity.TAB_STRATEGY,
        targetGameId: String? = null
    ): Boolean = postSystemNotification(
        context = context,
        notificationId = notificationId,
        title = title,
        message = message,
        priorityLevel = priorityLevel,
        soundEnabled = soundEnabled,
        vibrationEnabled = vibrationEnabled,
        historyRecordId = historyRecordId
    )
}

/**
 * Properly managed audio & vibration controller for active signal alarms and settings tests.
 * Respects system Do Not Disturb, audio focus / volume, and configured max duration;
 * stops and releases MediaPlayer resources immediately upon Stop, Snooze, or timeout.
 */
object AlarmAudioController {
    private const val TAG = "AlarmAudioController"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _isAlarmRinging = MutableStateFlow(false)
    val isAlarmRinging: StateFlow<Boolean> = _isAlarmRinging.asStateFlow()

    private val _activeAlarmTitle = MutableStateFlow<String?>(null)
    val activeAlarmTitle: StateFlow<String?> = _activeAlarmTitle.asStateFlow()

    private val _snoozedUntilMs = MutableStateFlow(0L)
    val snoozedUntilMs: StateFlow<Long> = _snoozedUntilMs.asStateFlow()

    private val _lastAudioStatus = MutableStateFlow<String?>(null)
    val lastAudioStatus: StateFlow<String?> = _lastAudioStatus.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var autoStopJob: Job? = null
    private var activeVibrator: Vibrator? = null
    @Volatile
    private var lastAppContext: Context? = null

    fun vibrationTimingsFor(patternKey: String): LongArray = when (patternKey.uppercase()) {
        "SHORT_PULSE" -> longArrayOf(0L, 120L, 100L, 120L)
        "DISTINCTIVE" -> longArrayOf(0L, 350L, 150L, 350L, 150L, 600L)
        "LONG_CONTINUOUS" -> longArrayOf(0L, 800L, 250L, 800L)
        "NONE" -> longArrayOf(0L)
        else -> longArrayOf(0L, 220L, 120L, 220L)
    }

    /**
     * Resolves a user-selected Android sound URI ONLY.
     * Never silently auto-assigns a sound when customUriString is empty.
     */
    fun resolveSoundUri(customUriString: String, isAlarm: Boolean): Uri? {
        if (customUriString.isBlank()) return null
        return runCatching { Uri.parse(customUriString.trim()) }.getOrNull()
    }

    fun previewSoundUri(context: Context, uriString: String, asAlarmStream: Boolean = true) {
        val uri = resolveSoundUri(uriString, isAlarm = asAlarmStream)
        if (uri == null) {
            _lastAudioStatus.value = "No sound selected — tap Choose Sound to pick one."
            return
        }
        stopAlarm()
        runCatching {
            val usage = if (asAlarmStream) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context.applicationContext, uri)
                isLooping = false
                setOnCompletionListener { mp ->
                    runCatching { mp.release() }
                    if (mediaPlayer === mp) mediaPlayer = null
                }
                prepare()
                start()
            }
            mediaPlayer = player
            _lastAudioStatus.value = "Previewing selected sound."
        }.onFailure {
            _lastAudioStatus.value = "Unable to play selected sound URI."
        }
    }

    fun testAlarmProfile(context: Context, profile: AlarmProfileEntity) {
        val settings = NotificationSettings(
            highPriorityAlarmEnabled = true,
            alarmSoundUri = profile.soundUri,
            alarmVibrationPattern = if (profile.vibrationEnabled) profile.vibrationPattern else "NONE",
            alarmRepeated = profile.repeatEnabled,
            alarmMaxDurationMinutes = 1
        )
        clearSnooze()
        if (profile.soundUri.isBlank() && !profile.vibrationEnabled) {
            _lastAudioStatus.value = "Profile '${profile.category().displayTitle}' has no sound or vibration selected."
            return
        }
        startAlarm(context, settings, "Test: ${profile.category().displayTitle}")
    }

    fun isAlarmSnoozed(nowMs: Long = System.currentTimeMillis()): Boolean =
        _snoozedUntilMs.value > nowMs

    const val ALARM_NOTIFICATION_ID = 9999
    const val ACTION_STOP_ALARM = "com.example.action.STOP_ALARM"

    fun startAlarm(
        context: Context?,
        settings: NotificationSettings,
        title: String = "Signal Detected — Pattern Match",
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (!settings.highPriorityAlarmEnabled) {
            _lastAudioStatus.value = "Signal alarm disabled in profile."
            return
        }
        if (isAlarmSnoozed(nowMs)) {
            _lastAudioStatus.value = "Signal alarm snoozed."
            return
        }
        if (context == null) {
            _isAlarmRinging.value = true
            _activeAlarmTitle.value = title
            return
        }
        if (AndroidSystemNotificationDispatcher.isDoNotDisturbBlocking(context, NotificationPriorityLevel.URGENT)) {
            _lastAudioStatus.value = "Do Not Disturb active on device; alarm audio suppressed."
            return
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val alarmVol = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 1
        if (alarmVol <= 0) {
            _lastAudioStatus.value = "Device alarm volume is muted (0); triggering vibration only."
        }

        lastAppContext = context.applicationContext
        stopAlarm(context)
        _isAlarmRinging.value = true
        _activeAlarmTitle.value = title

        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AndroidSystemNotificationDispatcher.ensureAllChannels(context, settings)
            }
            val stopIntent = Intent(context, AlarmStopReceiver::class.java).apply {
                action = ACTION_STOP_ALARM
            }
            val stopActionPendingIntent = PendingIntent.getBroadcast(
                context,
                2002,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val stopTapPendingIntent = PendingIntent.getBroadcast(
                context,
                2003,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val stopDeletePendingIntent = PendingIntent.getBroadcast(
                context,
                2004,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val channelId = AndroidSystemNotificationDispatcher.resolveVersionedChannelId(
                AndroidSystemNotificationDispatcher.CHANNEL_ALARM, settings
            )
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setContentTitle(title)
                .setContentText("Alarm ringing — tap STOP ALARM or swipe away to silence.")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(true)
                .setSilent(true)
                .setContentIntent(stopTapPendingIntent)
                .setDeleteIntent(stopDeletePendingIntent)
                .addAction(R.mipmap.ic_launcher_round, "STOP ALARM", stopActionPendingIntent)

            nm?.notify(ALARM_NOTIFICATION_ID, builder.build())
        }

        val uri = resolveSoundUri(settings.alarmSoundUri, isAlarm = true)
        if (uri != null && alarmVol > 0) {
            runCatching {
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(context.applicationContext, uri)
                    isLooping = settings.alarmRepeated
                    setOnCompletionListener {
                        if (!settings.alarmRepeated) {
                            stopAlarm(context)
                        }
                    }
                    setOnErrorListener { _, _, _ ->
                        stopAlarm(context)
                        true
                    }
                    prepare()
                    start()
                }
                mediaPlayer = player
            }.onFailure {
                Log.w(TAG, "Alarm MediaPlayer failed; keeping UI alarm state", it)
                _lastAudioStatus.value = "Unable to play alarm URI on this device."
            }
        }

        if (settings.alarmVibrationPattern != "NONE") {
            triggerVibration(context, settings.alarmVibrationPattern, repeat = settings.alarmRepeated)
        }

        val maxDurationMs = settings.alarmMaxDurationMinutes.coerceIn(1, 30) * 60_000L
        autoStopJob?.cancel()
        autoStopJob = scope.launch {
            delay(maxDurationMs)
            stopAlarm(context)
        }
    }

    fun stopAlarm(context: Context? = null) {
        autoStopJob?.cancel()
        autoStopJob = null
        runCatching {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
        }
        mediaPlayer = null
        runCatching { activeVibrator?.cancel() }
        activeVibrator = null
        _isAlarmRinging.value = false
        _activeAlarmTitle.value = null

        val ctx = context ?: lastAppContext ?: com.example.service.ScreenEarnerAccessibilityService.instance?.applicationContext
        if (ctx != null) {
            runCatching {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(ALARM_NOTIFICATION_ID)
            }
        }
    }

    fun snoozeAlarm(snoozeMinutes: Int = 5, nowMs: Long = System.currentTimeMillis()) {
        stopAlarm()
        val mins = snoozeMinutes.coerceIn(1, 30)
        _snoozedUntilMs.value = nowMs + mins * 60_000L
        _lastAudioStatus.value = "Signal alarm snoozed for ${mins}m."
    }

    fun clearSnooze() {
        _snoozedUntilMs.value = 0L
    }

    fun testStandardSound(context: Context, settings: NotificationSettings) {
        if (AndroidSystemNotificationDispatcher.isDoNotDisturbBlocking(context, NotificationPriorityLevel.STANDARD)) {
            _lastAudioStatus.value = "Do Not Disturb active; sound suppressed by Android."
            return
        }
        val uri = resolveSoundUri(settings.standardSoundUri, isAlarm = false)
        if (uri == null) {
            _lastAudioStatus.value = "No sound selected for Standard Alerts."
            return
        }
        stopAlarm()
        runCatching {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context.applicationContext, uri)
                isLooping = false
                setOnCompletionListener { mp ->
                    runCatching { mp.release() }
                    if (mediaPlayer === mp) mediaPlayer = null
                }
                prepare()
                start()
            }
            mediaPlayer = player
            _lastAudioStatus.value = "Playing standard alert test sound."
        }.onFailure {
            _lastAudioStatus.value = "Could not play standard alert sound."
        }
    }

    fun testVibration(context: Context, patternKey: String) {
        triggerVibration(context, patternKey, repeat = false)
        _lastAudioStatus.value = "Triggered $patternKey vibration test."
    }

    fun testSignalAlarm(context: Context, settings: NotificationSettings) {
        clearSnooze()
        startAlarm(context, settings.copy(highPriorityAlarmEnabled = true, alarmMaxDurationMinutes = 1), "Test Signal Alarm (Pattern Match)")
    }

    @Suppress("DEPRECATION")
    private fun triggerVibration(context: Context, patternKey: String, repeat: Boolean) {
        if (patternKey == "NONE") return
        val timings = vibrationTimingsFor(patternKey)
        if (timings.size <= 1) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        activeVibrator = vibrator
        runCatching {
            val repeatIndex = if (repeat) 0 else -1
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, repeatIndex))
            } else {
                vibrator.vibrate(timings, repeatIndex)
            }
        }
    }

    fun openChannelSettings(context: Context, channelId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }.onFailure {
                openAppNotificationSettings(context)
            }
        } else {
            openAppNotificationSettings(context)
        }
    }

    fun openAppNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}

object AndroidSystemNotificationDispatcher : SystemNotificationDispatcher {
    const val CHANNEL_STANDARD = "monitoring_standard"
    const val CHANNEL_ALARM = "monitoring_alarm"
    const val CHANNEL_SERVICE = "monitoring_service"
    const val CHANNEL_HOURLY = "ap_hourly_analysis"

    // Compatibility constants kept for existing callers/tests
    const val STRATEGY_CHANNEL_ID = CHANNEL_ALARM
    const val CHANNEL_CRITICAL_PATTERNS = CHANNEL_ALARM
    const val CHANNEL_GAME_DETECTION = CHANNEL_STANDARD
    const val CHANNEL_MONITORING_ERRORS = CHANNEL_SERVICE
    const val CHANNEL_HOURLY_ANALYSIS = CHANNEL_HOURLY

    fun resolveVersionedChannelId(baseId: String, settings: NotificationSettings): String {
        val v = settings.channelConfigVersion.coerceAtLeast(1)
        return if (v <= 1 || baseId == CHANNEL_SERVICE || baseId == CHANNEL_HOURLY) baseId else "${baseId}_v$v"
    }

    override fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    override fun isDoNotDisturbBlocking(context: Context, priorityLevel: NotificationPriorityLevel): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        return when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> true
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> priorityLevel != NotificationPriorityLevel.URGENT
            else -> false
        }
    }

    fun resolveChannelId(
        type: StrategyNotificationType,
        settings: NotificationSettings = NotificationSettings()
    ): String {
        val base = when (type) {
            StrategyNotificationType.STRATEGY_REACHED,
            StrategyNotificationType.STRATEGY_APPROACHING -> CHANNEL_ALARM
            StrategyNotificationType.HOURLY_SUMMARY,
            StrategyNotificationType.NEW_HOUR_STARTED -> CHANNEL_HOURLY
            StrategyNotificationType.GAME_DETECTED,
            StrategyNotificationType.AUTOMATION_ACTION -> CHANNEL_STANDARD
            StrategyNotificationType.HISTORY_UNAVAILABLE,
            StrategyNotificationType.MONITORING_ERROR,
            StrategyNotificationType.STRATEGY_PAUSED,
            StrategyNotificationType.STRATEGY_INVALIDATED -> CHANNEL_SERVICE
        }
        return resolveVersionedChannelId(base, settings)
    }

    fun resolveTargetTab(type: StrategyNotificationType): Int = when (type) {
        StrategyNotificationType.HOURLY_SUMMARY,
        StrategyNotificationType.NEW_HOUR_STARTED -> MainActivity.TAB_HOURLY_ANALYSIS
        StrategyNotificationType.GAME_DETECTED,
        StrategyNotificationType.HISTORY_UNAVAILABLE,
        StrategyNotificationType.MONITORING_ERROR,
        StrategyNotificationType.AUTOMATION_ACTION -> MainActivity.TAB_LIVE_MONITOR
        StrategyNotificationType.STRATEGY_REACHED,
        StrategyNotificationType.STRATEGY_APPROACHING,
        StrategyNotificationType.STRATEGY_INVALIDATED,
        StrategyNotificationType.STRATEGY_PAUSED -> MainActivity.TAB_PATTERNS
    }

    fun ensureStrategyChannel(context: Context, settings: NotificationSettings) {
        ensureAllChannels(context, settings)
    }

    fun ensureAllChannels(context: Context, settings: NotificationSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val alarmAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val alarmUri = AlarmAudioController.resolveSoundUri(settings.alarmSoundUri, isAlarm = true)

        val notifAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val standardUri = AlarmAudioController.resolveSoundUri(settings.standardSoundUri, isAlarm = false)

        val standardChannelId = resolveVersionedChannelId(CHANNEL_STANDARD, settings)
        val alarmChannelId = resolveVersionedChannelId(CHANNEL_ALARM, settings)

        val standard = NotificationChannel(
            standardChannelId,
            "Standard Alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Standard notifications for game detection, monitoring updates, and resumption."
            val vibEnabled = settings.vibrationEnabled && settings.standardVibrationPattern != "NONE"
            enableVibration(vibEnabled)
            if (vibEnabled) {
                vibrationPattern = AlarmAudioController.vibrationTimingsFor(settings.standardVibrationPattern)
            }
            if (!settings.soundEnabled || !settings.standardAlertEnabled) {
                setSound(null, null)
            } else {
                setSound(standardUri, notifAttrs)
            }
        }

        val alarm = NotificationChannel(
            alarmChannelId,
            "Pattern Match Signal Alarm",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Distinctive alarm notifications when a configured pattern or condition matches."
            val vibEnabled = settings.alarmVibrationPattern != "NONE"
            enableVibration(vibEnabled)
            if (vibEnabled) {
                vibrationPattern = AlarmAudioController.vibrationTimingsFor(settings.alarmVibrationPattern)
            }
            setSound(alarmUri, alarmAttrs)
            enableLights(true)
        }

        val service = NotificationChannel(
            CHANNEL_SERVICE,
            "Persistent Monitoring Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification ensuring overlay and background monitoring remain active."
        }

        val hourly = NotificationChannel(
            CHANNEL_HOURLY,
            "Hourly Analysis",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Hour transition summaries and statistical digests."
            enableVibration(false)
        }

        nm.createNotificationChannels(listOf(standard, alarm, service, hourly))
    }

    override fun postSystemNotification(
        context: Context,
        notificationId: Int,
        title: String,
        message: String,
        priorityLevel: NotificationPriorityLevel,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean,
        historyRecordId: Long
    ): Boolean = postChannelNotification(
        context = context,
        channelId = CHANNEL_CRITICAL_PATTERNS,
        notificationId = notificationId,
        title = title,
        message = message,
        priorityLevel = priorityLevel,
        soundEnabled = soundEnabled,
        vibrationEnabled = vibrationEnabled,
        historyRecordId = historyRecordId
    )

    override fun postChannelNotification(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        message: String,
        priorityLevel: NotificationPriorityLevel,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean,
        historyRecordId: Long,
        targetTab: Int,
        targetGameId: String?
    ): Boolean {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(channelId) == null) {
                ensureAllChannels(
                    context,
                    NotificationSettings(soundEnabled = soundEnabled, vibrationEnabled = vibrationEnabled)
                )
            }

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_TAB, targetTab)
                putExtra(MainActivity.EXTRA_NOTIFICATION_HISTORY_ID, historyRecordId)
                if (!targetGameId.isNullOrBlank()) {
                    putExtra(MainActivity.EXTRA_GAME_ID, targetGameId)
                }
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val compatPriority = when (priorityLevel) {
                NotificationPriorityLevel.URGENT -> NotificationCompat.PRIORITY_MAX
                NotificationPriorityLevel.HIGH -> NotificationCompat.PRIORITY_HIGH
                NotificationPriorityLevel.STANDARD -> NotificationCompat.PRIORITY_DEFAULT
            }

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(compatPriority)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            val isAlarmChannel = channelId.startsWith(CHANNEL_ALARM)
            if (!soundEnabled || !isAlarmChannel) {
                builder.setSilent(!soundEnabled)
            }
            if (vibrationEnabled && isAlarmChannel) {
                builder.setVibrate(longArrayOf(0L, 250L, 120L, 250L))
            }
            if (isAlarmChannel) {
                val stopIntent = Intent(context, AlarmStopReceiver::class.java).apply {
                    action = AlarmAudioController.ACTION_STOP_ALARM
                }
                val stopPi = PendingIntent.getBroadcast(
                    context,
                    2005,
                    stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(R.mipmap.ic_launcher_round, "STOP ALARM", stopPi)
            }

            nm.notify(notificationId, builder.build())
            true
        } catch (t: Throwable) {
            Log.e("StrategyNotifMgr", "Failed posting Android notification", t)
            false
        }
    }
}

/**
 * Central Android System Notification Center controller.
 * Routes alerts to Pattern Match Signal Alarm, Standard Alerts, Hourly Analysis, or Persistent Service;
 * respects Android 13+ POST_NOTIFICATIONS, Do Not Disturb, quiet hours, cooldowns, and deduplication.
 */
class StrategyNotificationManager(
    private val context: Context?,
    private val repository: ScreenEarnerRepository,
    private val dispatcher: SystemNotificationDispatcher = AndroidSystemNotificationDispatcher
) {
    private val lastDeliveredOccurrences = ConcurrentHashMap<String, Long>()
    private val lastStrategyDispatchTimes = ConcurrentHashMap<Long, Long>()
    private val strategyHourDispatchCounts = ConcurrentHashMap<Pair<Long, Long>, Int>()
    private val notificationIdSeq = AtomicInteger(2100)

    companion object {
        fun isWithinQuietHours(settings: NotificationSettings, nowMs: Long): Boolean {
            if (!settings.quietHoursEnabled) return false
            val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
            val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val startMinutes = settings.quietStartHour.coerceIn(0, 23) * 60 + settings.quietStartMinute.coerceIn(0, 59)
            val endMinutes = settings.quietEndHour.coerceIn(0, 23) * 60 + settings.quietEndMinute.coerceIn(0, 59)

            return if (startMinutes == endMinutes) {
                false
            } else if (startMinutes < endMinutes) {
                currentMinutes in startMinutes until endMinutes
            } else {
                currentMinutes >= startMinutes || currentMinutes < endMinutes
            }
        }
    }

    fun evaluateSuppressionReason(
        event: PatternAlertEvent,
        settings: NotificationSettings,
        nowMs: Long
    ): NotificationDeliveryStatus? {
        if (!settings.masterEnabled) {
            return NotificationDeliveryStatus.SUPPRESSED_MASTER_OFF
        }
        val isSignalAlarmEvent = event.notificationType == StrategyNotificationType.STRATEGY_REACHED ||
            event.notificationType == StrategyNotificationType.STRATEGY_APPROACHING
        if (isSignalAlarmEvent && !settings.highPriorityAlarmEnabled && !settings.standardAlertEnabled) {
            return NotificationDeliveryStatus.SUPPRESSED_TYPE_OFF
        }
        if (!isSignalAlarmEvent &&
            event.notificationType == StrategyNotificationType.GAME_DETECTED &&
            !settings.standardAlertEnabled
        ) {
            return NotificationDeliveryStatus.SUPPRESSED_TYPE_OFF
        }

        val typeEnabled = when (event.notificationType) {
            StrategyNotificationType.STRATEGY_APPROACHING -> settings.approachingEnabled
            StrategyNotificationType.STRATEGY_REACHED -> settings.reachedEnabled
            StrategyNotificationType.STRATEGY_INVALIDATED -> settings.invalidatedEnabled
            StrategyNotificationType.HISTORY_UNAVAILABLE ->
                settings.historyUnavailableEnabled && settings.failureAlertsEnabled
            StrategyNotificationType.MONITORING_ERROR ->
                (settings.monitoringErrorsEnabled || settings.historyUnavailableEnabled) && settings.failureAlertsEnabled
            StrategyNotificationType.STRATEGY_PAUSED -> settings.pausedEnabled
            StrategyNotificationType.HOURLY_SUMMARY,
            StrategyNotificationType.NEW_HOUR_STARTED -> settings.hourlySummaryEnabled
            StrategyNotificationType.GAME_DETECTED -> settings.gameDetectionEnabled
            StrategyNotificationType.AUTOMATION_ACTION ->
                settings.standardAlertEnabled && settings.executionAlertsEnabled
        }
        if (!typeEnabled) {
            return NotificationDeliveryStatus.SUPPRESSED_TYPE_OFF
        }
        if (event.strategy.id > 0L && !event.strategy.notificationEnabled) {
            return NotificationDeliveryStatus.SUPPRESSED_STRATEGY_OFF
        }
        if (isWithinQuietHours(settings, nowMs)) {
            return NotificationDeliveryStatus.SUPPRESSED_QUIET_HOURS
        }
        if (context != null && dispatcher.isDoNotDisturbBlocking(context, event.strategy.notificationLevel)) {
            return NotificationDeliveryStatus.SUPPRESSED_QUIET_HOURS
        }
        if (lastDeliveredOccurrences.containsKey(event.occurrenceKey)) {
            return NotificationDeliveryStatus.SUPPRESSED_DUPLICATE
        }
        val effectiveStrategyKey = if (event.strategy.id > 0L) {
            event.strategy.id
        } else {
            event.strategy.name.hashCode().toLong()
        }
        val effectiveCooldownMin = maxOf(
            if (isSignalAlarmEvent && settings.alarmRepeated) settings.alarmRepeatIntervalMinutes else settings.repeatIntervalMinutes,
            event.strategy.repeatCooldownMinutes
        ).coerceAtLeast(0)
        if (event.notificationType == StrategyNotificationType.STRATEGY_REACHED && effectiveCooldownMin > 0) {
            val lastTime = lastStrategyDispatchTimes[effectiveStrategyKey] ?: 0L
            if (lastTime > 0L && (nowMs - lastTime) < effectiveCooldownMin * 60_000L) {
                return NotificationDeliveryStatus.SUPPRESSED_COOLDOWN
            }
        }
        val hourBucket = nowMs / 3_600_000L
        val repeatCap = event.strategy.repeatLimit
        if (event.notificationType == StrategyNotificationType.STRATEGY_REACHED && repeatCap > 0) {
            val sentThisHour = strategyHourDispatchCounts[effectiveStrategyKey to hourBucket] ?: 0
            if (sentThisHour >= repeatCap) {
                return NotificationDeliveryStatus.SUPPRESSED_COOLDOWN
            }
        }
        return null
    }

    suspend fun dispatchPatternAlert(
        event: PatternAlertEvent,
        settings: NotificationSettings,
        nowMs: Long = System.currentTimeMillis(),
        gameId: String? = event.strategy.targetGameId.takeIf { it != "ALL" },
        occurrenceRecordId: Long? = null
    ): NotificationHistoryRecord {
        val channelId = AndroidSystemNotificationDispatcher.resolveChannelId(event.notificationType, settings)

        val suppression = evaluateSuppressionReason(event, settings, nowMs)
        if (suppression != null) {
            val suppressedRecord = NotificationHistoryRecord(
                strategyId = event.strategy.id,
                strategyName = event.strategy.name,
                gameId = gameId.orEmpty(),
                occurrenceRecordId = occurrenceRecordId,
                channelId = channelId,
                notificationType = event.notificationType,
                title = event.title,
                message = event.message,
                triggeringRecordKey = event.triggeringRecordKey,
                triggeringMultiplier = event.triggeringMultiplier,
                timestampMs = nowMs,
                deliveryStatus = suppression,
                interactionStatus = NotificationInteractionStatus.UNREAD
            )
            val savedId = repository.addNotificationHistory(suppressedRecord)
            return suppressedRecord.copy(id = savedId)
        }

        val appCtx = context
        val hasPermission = appCtx != null && dispatcher.hasNotificationPermission(appCtx)
        if (!hasPermission) {
            val deniedRecord = NotificationHistoryRecord(
                strategyId = event.strategy.id,
                strategyName = event.strategy.name,
                gameId = gameId.orEmpty(),
                occurrenceRecordId = occurrenceRecordId,
                channelId = channelId,
                notificationType = event.notificationType,
                title = event.title,
                message = event.message,
                triggeringRecordKey = event.triggeringRecordKey,
                triggeringMultiplier = event.triggeringMultiplier,
                timestampMs = nowMs,
                deliveryStatus = NotificationDeliveryStatus.PERMISSION_DENIED,
                interactionStatus = NotificationInteractionStatus.UNREAD
            )
            val savedId = repository.addNotificationHistory(deniedRecord)
            return deniedRecord.copy(id = savedId)
        }

        val pendingRecord = NotificationHistoryRecord(
            strategyId = event.strategy.id,
            strategyName = event.strategy.name,
            gameId = gameId.orEmpty(),
            occurrenceRecordId = occurrenceRecordId,
            channelId = channelId,
            notificationType = event.notificationType,
            title = event.title,
            message = event.message,
            triggeringRecordKey = event.triggeringRecordKey,
            triggeringMultiplier = event.triggeringMultiplier,
            timestampMs = nowMs,
            deliveryStatus = NotificationDeliveryStatus.DELIVERED,
            interactionStatus = NotificationInteractionStatus.UNREAD
        )
        val recordId = repository.addNotificationHistory(pendingRecord)

        val uniqueSeed = (event.occurrenceKey.hashCode() and 0x0FFFFFFF)
        val notifId = if (uniqueSeed > 0) uniqueSeed else notificationIdSeq.incrementAndGet()
        if (dispatcher === AndroidSystemNotificationDispatcher) {
            AndroidSystemNotificationDispatcher.ensureAllChannels(appCtx, settings)
        }
        val posted = dispatcher.postChannelNotification(
            context = appCtx,
            channelId = channelId,
            notificationId = notifId,
            title = event.title,
            message = event.message,
            priorityLevel = event.strategy.notificationLevel,
            soundEnabled = settings.soundEnabled,
            vibrationEnabled = settings.vibrationEnabled,
            historyRecordId = recordId,
            targetTab = AndroidSystemNotificationDispatcher.resolveTargetTab(event.notificationType),
            targetGameId = gameId
        )

        if (posted &&
            event.notificationType == StrategyNotificationType.STRATEGY_REACHED &&
            event.strategy.useAlarmStyle &&
            settings.highPriorityAlarmEnabled &&
            dispatcher === AndroidSystemNotificationDispatcher
        ) {
            val activeProfile = runCatching {
                repository.getAlarmProfile(event.strategy.resolveAlarmProfileCategory())
            }.getOrNull()
            if (activeProfile == null || activeProfile.enabled) {
                val effectiveSettings = if (activeProfile != null) {
                    settings.copy(
                        alarmSoundUri = activeProfile.soundUri.ifBlank { settings.alarmSoundUri },
                        alarmVibrationPattern = if (event.strategy.vibrationEnabled && activeProfile.vibrationEnabled) {
                            activeProfile.vibrationPattern
                        } else {
                            "NONE"
                        },
                        alarmRepeated = activeProfile.repeatEnabled && settings.alarmRepeated
                    )
                } else {
                    settings.copy(
                        alarmVibrationPattern = if (event.strategy.vibrationEnabled) settings.alarmVibrationPattern else "NONE"
                    )
                }
                AlarmAudioController.startAlarm(
                    context = appCtx,
                    settings = effectiveSettings,
                    title = "SIGNAL DETECTED: ${event.strategy.name}",
                    nowMs = nowMs
                )
            }
        }

        val effectiveStrategyKey = if (event.strategy.id > 0L) {
            event.strategy.id
        } else {
            event.strategy.name.hashCode().toLong()
        }
        val finalStatus = if (posted) {
            lastDeliveredOccurrences[event.occurrenceKey] = nowMs
            if (event.notificationType == StrategyNotificationType.STRATEGY_REACHED) {
                lastStrategyDispatchTimes[effectiveStrategyKey] = nowMs
                val hourBucket = nowMs / 3_600_000L
                val key = effectiveStrategyKey to hourBucket
                strategyHourDispatchCounts[key] = (strategyHourDispatchCounts[key] ?: 0) + 1
            }
            NotificationDeliveryStatus.DELIVERED
        } else {
            NotificationDeliveryStatus.FAILED
        }

        val finalRecord = pendingRecord.copy(id = recordId, deliveryStatus = finalStatus)
        if (!posted) {
            repository.addNotificationHistory(finalRecord)
        }
        return finalRecord
    }

    suspend fun dispatchSystemNotice(
        strategyName: String = "Predict Manager Monitor",
        type: StrategyNotificationType,
        title: String,
        message: String,
        occurrenceKey: String,
        settings: NotificationSettings,
        nowMs: Long = System.currentTimeMillis(),
        gameId: String? = null
    ): NotificationHistoryRecord {
        val dummyStrategy = CustomPatternStrategy(id = 0L, name = strategyName)
        val event = PatternAlertEvent(
            strategy = dummyStrategy,
            notificationType = type,
            title = title,
            message = message,
            occurrenceKey = occurrenceKey,
            timestampMs = nowMs
        )
        return dispatchPatternAlert(event, settings, nowMs, gameId = gameId)
    }

    suspend fun dispatchHourlyBoundaryNotice(
        gameId: String,
        gameName: String,
        closedHourLabel: String,
        newHourLabel: String,
        summaryLine: String,
        settings: NotificationSettings,
        nowMs: Long = System.currentTimeMillis()
    ): NotificationHistoryRecord = dispatchSystemNotice(
        strategyName = "$gameName Hourly Digest",
        type = StrategyNotificationType.NEW_HOUR_STARTED,
        title = "New Hour $newHourLabel — $closedHourLabel Finalized",
        message = summaryLine,
        occurrenceKey = "hour_${gameId}_${newHourLabel}_${nowMs / 3_600_000L}",
        settings = settings,
        nowMs = nowMs,
        gameId = gameId
    )

    fun clearSessionCache() {
        lastDeliveredOccurrences.clear()
        lastStrategyDispatchTimes.clear()
        strategyHourDispatchCounts.clear()
    }
}
