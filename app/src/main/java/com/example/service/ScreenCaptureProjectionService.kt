package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.R

/**
 * Optional foreground service started ONLY after explicit user consent via MediaProjection API
 * to sample real displayed multiplier badge colors from custom-rendered game canvases/ribbons.
 */
class ScreenCaptureProjectionService : Service() {

    companion object {
        private const val TAG = "ProjectionColorSvc"
        private const val CHANNEL_ID = "AP_PROJECTION_CAPTURE"
        private const val NOTIF_ID = 9402

        const val ACTION_START_CAPTURE = "com.example.action.START_PROJECTION_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.example.action.STOP_PROJECTION_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"

        fun createStartIntent(context: Context, resultCode: Int, data: Intent): Intent {
            return Intent(context, ScreenCaptureProjectionService::class.java).apply {
                action = ACTION_START_CAPTURE
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA_INTENT, data)
            }
        }

        fun createStopIntent(context: Context): Intent {
            return Intent(context, ScreenCaptureProjectionService::class.java).apply {
                action = ACTION_STOP_CAPTURE
            }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    @Volatile
    private var lastFrameAtMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_CAPTURE) {
            stopCaptureAndSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_DATA_INTENT)
        }

        if (resultCode != Activity.RESULT_OK || dataIntent == null) {
            ScreenColorSampler.setCaptureActive(false, "Screen color capture consent not granted")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            ensureNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
            startProjection(resultCode, dataIntent)
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to start MediaProjection capture service", t)
            ScreenColorSampler.setCaptureActive(false, "Color capture start failed: ${t.message}")
            stopCaptureAndSelf()
        }
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, dataIntent: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val projection = manager?.getMediaProjection(resultCode, dataIntent)
        if (projection == null) {
            ScreenColorSampler.setCaptureActive(false, "System declined MediaProjection session")
            stopSelf()
            return
        }

        stopProjectionResources()

        val dm = resources.displayMetrics
        val width = dm.widthPixels.coerceAtLeast(360)
        val height = dm.heightPixels.coerceAtLeast(640)
        val density = dm.densityDpi.coerceAtLeast(160)

        handlerThread = HandlerThread("PredictManagerColorCapture").apply { start() }
        captureHandler = Handler(handlerThread!!.looper)

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                ScreenColorSampler.setCaptureActive(false, "Screen capture stopped by system")
                stopCaptureAndSelf()
            }
        }, captureHandler)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            val now = System.currentTimeMillis()
            val img = try { r.acquireLatestImage() } catch (_: Throwable) { null } ?: return@setOnImageAvailableListener
            try {
                if (now - lastFrameAtMs < 220L) {
                    return@setOnImageAvailableListener
                }
                lastFrameAtMs = now
                val plane = img.planes.firstOrNull() ?: return@setOnImageAvailableListener
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val paddedBitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                paddedBitmap.copyPixelsFromBuffer(buffer)
                val cropped = if (paddedBitmap.width != width) {
                    Bitmap.createBitmap(paddedBitmap, 0, 0, width, height).also {
                        paddedBitmap.recycle()
                    }
                } else {
                    paddedBitmap
                }
                ScreenColorSampler.updateFrame(cropped)
            } catch (t: Throwable) {
                Log.w(TAG, "Frame sampling error: ${t.message}")
            } finally {
                runCatching { img.close() }
            }
        }, captureHandler)

        val display = projection.createVirtualDisplay(
            "PredictManagerColorSampler",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        )

        mediaProjection = projection
        imageReader = reader
        virtualDisplay = display
        ScreenColorSampler.setCaptureActive(true, null)
    }

    private fun stopProjectionResources() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        runCatching { handlerThread?.quitSafely() }
        handlerThread = null
        captureHandler = null
    }

    private fun stopCaptureAndSelf() {
        stopProjectionResources()
        ScreenColorSampler.setCaptureActive(false, null)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        stopProjectionResources()
        ScreenColorSampler.setCaptureActive(false, null)
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pixel Color Sampling",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Captures displayed multiplier colors when explicitly enabled."
            }
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Predict Manager Pixel Color Capture")
            .setContentText("Reading displayed multiplier badge colors")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
