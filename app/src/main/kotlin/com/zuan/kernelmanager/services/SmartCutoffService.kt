/*
 * Copyright (c) 2025 ZKM
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.zuan.kernelmanager.services

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
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
    private var isExplicitStop = false
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
        serviceScope.launch(Dispatchers.IO) {
            RootPersistenceUtils.applyRootExemptions(this@SmartCutoffService)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val savedLimit = SettingsPreference.getInstance(this).smartCutoffLimit.value.toInt()
        limitThreshold = savedLimit

        if (intent != null) {
            when (intent.action) {
                ACTION_STOP_SERVICE -> {
                    isExplicitStop = true
                    stopSelf()
                    return START_NOT_STICKY
                }
                ACTION_UPDATE_LIMIT -> {
                    limitThreshold = intent.getIntExtra(EXTRA_LIMIT, savedLimit)
                    updateNotification("Limit updated to $limitThreshold%")
                    serviceScope.launch(Dispatchers.IO) { checkBatteryStateNow() }
                }
                else -> {
                    limitThreshold = intent.getIntExtra(EXTRA_LIMIT, savedLimit)
                }
            }
        }

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Cut-off Active")
            .setContentText("Target: $limitThreshold%")
            .setSmallIcon(R.drawable.ic_battery_android_frame_shield)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }

        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) { }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(batteryReceiver, filter)

        serviceScope.launch(Dispatchers.IO) { checkBatteryStateNow() }

        return START_STICKY
    }

    private fun checkBatteryStateNow() {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        intent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            handleBatteryLogic(level)
        }
    }

    private fun handleBatteryLogic(level: Int) {
        serviceScope.launch(Dispatchers.IO) {
            // Check physical connection via low-level nodes
            val isPhysicallyConnected = BatteryControllerUtils.isUsbPowerConnected()

            if (!isPhysicallyConnected) {
                // IF NO CABLE: Re-enable charging immediately and reset cutoff state
                if (isCutOffActive) {
                    BatteryControllerUtils.setChargingEnabled(true)
                    isCutOffActive = false
                    withContext(Dispatchers.Main) {
                        updateNotification("Charger unplugged. Ready for next session.")
                    }
                    Log.d("SmartCutoff", "Physical unplug detected. State reset.")
                }
                return@launch
            }

            // IF CABLE IS CONNECTED:

            // 1. Cut-off: Stop charging indefinitely once target is hit
            if (level >= limitThreshold && !isCutOffActive) {
                BatteryControllerUtils.setChargingEnabled(false)
                isCutOffActive = true
                withContext(Dispatchers.Main) {
                    playTargetReachedNotify(level)
                }
                Log.d("SmartCutoff", "Target reached. Charging killed.")
            }
        }
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
        val savedLimit = SettingsPreference.getInstance(this).smartCutoffLimit.value.toInt()
        val restartServiceIntent = Intent(applicationContext, SmartCutoffService::class.java).apply {
            setPackage(packageName)
            putExtra(EXTRA_LIMIT, savedLimit)
        }
        val restartServicePendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                applicationContext, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getService(
                applicationContext, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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
        } catch (_: Exception) { }
        if (isExplicitStop) {
            BatteryControllerUtils.setChargingEnabled(true)
        }
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
