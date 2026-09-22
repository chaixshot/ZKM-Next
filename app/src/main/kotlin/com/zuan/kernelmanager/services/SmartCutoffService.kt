/*
 * Copyright (c) 2025 ZKM
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.zuan.kernelmanager.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.zuan.kernelmanager.R
import com.zuan.kernelmanager.ui.home.menu.BatteryControllerUtils
import com.zuan.kernelmanager.utils.RootPersistenceUtils
import kotlinx.coroutines.*

class SmartCutoffService : Service() {

    companion object {
        const val CHANNEL_ID = "SmartCutoffChannel"
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        const val ACTION_UPDATE_LIMIT = "UPDATE_LIMIT"
        const val EXTRA_LIMIT = "limit_threshold"
    }

    private var limitThreshold: Int = 80
    private var isCutOffActive = false
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            // Re-check state on battery change or power connection change
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context?.registerReceiver(null, intentFilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1

            handleBatteryLogic(level)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RootPersistenceUtils.applyRootExemptions(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_LIMIT -> {
                limitThreshold = intent.getIntExtra(EXTRA_LIMIT, 80)
                updateNotification("Limit updated to $limitThreshold%")
                checkBatteryStateNow()
            }
            else -> {
                limitThreshold = intent.getIntExtra(EXTRA_LIMIT, 80)
            }
        }

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Cut-off Active")
            .setContentText("Target: $limitThreshold%")
            .setSmallIcon(R.drawable.ic_battery_android_frame_shield)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        startForeground(1, notification)

        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) { }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(batteryReceiver, filter)

        return START_REDELIVER_INTENT
    }

    private fun checkBatteryStateNow() {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        intent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            handleBatteryLogic(level)
        }
    }

    private fun handleBatteryLogic(level: Int) {
        // Check physical connection via low-level nodes
        val isPhysicallyConnected = BatteryControllerUtils.isUsbPowerConnected()

        if (!isPhysicallyConnected) {
            // IF NO CABLE: Re-enable charging immediately and reset cutoff state
            if (isCutOffActive) {
                BatteryControllerUtils.setChargingEnabled(true)
                isCutOffActive = false
                updateNotification("Charger unplugged. Ready for next session.")
                Log.d("SmartCutoff", "Physical unplug detected. State reset.")
            }
            return
        }

        // IF CABLE IS CONNECTED:

        // 1. Cut-off: Stop charging indefinitely once target is hit
        if (level >= limitThreshold && !isCutOffActive) {
            BatteryControllerUtils.setChargingEnabled(false)
            isCutOffActive = true
            playTargetReachedNotify(level)
            Log.d("SmartCutoff", "Target reached. Charging killed.")
        }

        // Note: No "Resume" or periodic checks here.
        // This prevents the sound spam as setChargingEnabled(true) is never called while plugged.
    }

    private fun playTargetReachedNotify(level: Int) {
        val title = getString(R.string.battery_target_reached_title)
        val message = getString(R.string.battery_target_reached_msg, level)
        
        // Play Sound
        try {
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(applicationContext, notificationUri)
            r.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Show Popup Notification
        val manager = getSystemService(NotificationManager::class.java)
        
        // Create high importance channel for popup if on O+
        val alertChannelId = "SmartCutoffAlert"
        val channelName = getString(R.string.battery_smart_cutoff)
        val channel = NotificationChannel(alertChannelId, channelName, NotificationManager.IMPORTANCE_HIGH).apply {
            description = getString(R.string.battery_target_reached_title)
            enableLights(true)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)

        val popupNotification = NotificationCompat.Builder(this, alertChannelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_battery_android_frame_shield)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        manager.notify(2, popupNotification)

        // Update Foreground Notification
        updateNotification(message)
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Cut-off Active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_battery_android_frame_shield)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, notification)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val restartServiceIntent = Intent(applicationContext, SmartCutoffService::class.java).apply {
            setPackage(packageName)
        }
        val restartServicePendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                applicationContext, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                applicationContext, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val alarmService = applicationContext.getSystemService(ALARM_SERVICE) as AlarmManager
        alarmService.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1000,
            restartServicePendingIntent
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) { }
        BatteryControllerUtils.setChargingEnabled(true)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Smart Cut-off Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
