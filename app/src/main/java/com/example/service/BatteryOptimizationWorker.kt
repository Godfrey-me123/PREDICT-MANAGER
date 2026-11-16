package com.example.service

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class BatteryOptimizationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    companion object {
        private const val TAG = "BatteryOptWorker"
        private const val WORK_NAME = "ScreenEarnerBatteryOptimizationPeriodicWork"

        fun schedule(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val workRequest = PeriodicWorkRequestBuilder<BatteryOptimizationWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
                Log.i(TAG, "Battery optimization and service health periodic worker scheduled successfully.")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to schedule BatteryOptimizationWorker", t)
            }
        }

        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
                Log.i(TAG, "Battery optimization worker cancelled cleanly.")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed cancelling BatteryOptimizationWorker", t)
            }
        }
    }

    override fun doWork(): Result {
        return try {
            val context = applicationContext
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isIgnoring = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            } else {
                true
            }

            Log.i(TAG, "Battery optimization check: isIgnoringBatteryOptimizations = $isIgnoring")

            val a11yConnected = ScreenEarnerAccessibilityService.isServiceConnected()
            Log.i(TAG, "Service health check in background work: AccessibilityService connected = $a11yConnected")

            // Ensure background telemetry is healthy
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Error executing BatteryOptimizationWorker", t)
            Result.retry()
        }
    }
}
