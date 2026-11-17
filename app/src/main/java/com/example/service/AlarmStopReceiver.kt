package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == AlarmAudioController.ACTION_STOP_ALARM) {
            AlarmAudioController.stopAlarm(context)
        }
    }
}
