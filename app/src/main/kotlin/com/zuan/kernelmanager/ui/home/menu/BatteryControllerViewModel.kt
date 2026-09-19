/*
 * Copyright (c) 2025 ZKM
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.zuan.kernelmanager.ui.home.menu

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zuan.kernelmanager.service.BatteryMonitorService
import com.zuan.kernelmanager.service.SmartCutoffService
import com.zuan.kernelmanager.ui.settings.SettingsPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- DATA CLASS BARU UNTUK GRAFIK REAL-TIME ---
data class ChartPoint(
    val timeStamp: Long,
    val value: Int,
    val isCharging: Boolean
)

class BatteryControllerViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsPreference = SettingsPreference.getInstance(application)

    // --- DATA UTAMA ---
    private val _batteryInfo = MutableStateFlow<BatteryInfo?>(null)
    private val _chargingStats = MutableStateFlow<ChargingStats?>(null)
    private val _isLoading = MutableStateFlow(true)
    private val _isAutoRefresh = MutableStateFlow(true)
    private val _selectedTab = MutableStateFlow(0)
    
    // --- DATA TRACKING & GRAFIK M3 EXPRESSIVE ---
    private val _peakTemp = MutableStateFlow(0f)
    private val _peakChargeCurrent = MutableStateFlow(0)
    private val _peakDischargeCurrent = MutableStateFlow(0)
    private val _currentHistory = MutableStateFlow<List<ChartPoint>>(emptyList())
    
    val peakTemp: StateFlow<Float> = _peakTemp
    val peakChargeCurrent: StateFlow<Int> = _peakChargeCurrent
    val peakDischargeCurrent: StateFlow<Int> = _peakDischargeCurrent
    val currentHistory: StateFlow<List<ChartPoint>> = _currentHistory
    
    // --- FEATURE STATES ---
    private val _chargingLimit = MutableStateFlow(100)
    private val _smartCutoffLimit = MutableStateFlow(80f)
    private val _smartCutoffEnabled = MutableStateFlow(false)
    private val _monitorEnabled = MutableStateFlow(false)
    private val _isFastChargeEnabled = MutableStateFlow(false)
    private val _isFastChargeSupported = MutableStateFlow(false)
    private val _isBypassEnabled = MutableStateFlow(false)
    private val _isBypassSupported = MutableStateFlow(false)
    private val _isBatterySaverEnabled = MutableStateFlow(false)
    private val _batteryCapacity = MutableStateFlow(100)
    private val _chargingSpeed = MutableStateFlow(2000)
    private val _isChargingEnabled = MutableStateFlow(true)
    private val _thermalSconfig = MutableStateFlow("0")
    
    private var lastPhysicallyPlugged = true
    private var unpluggedTimestamp: Long = 0
    private val _hasThermalSconfig = MutableStateFlow(false)
    private val _isSmartChargeSupported = MutableStateFlow(false)
    private val _isChargingLimitSupported = MutableStateFlow(false)

    // Exposed Flows
    val batteryInfo: StateFlow<BatteryInfo?> = _batteryInfo
    val chargingStats: StateFlow<ChargingStats?> = _chargingStats
    val isLoading: StateFlow<Boolean> = _isLoading
    val selectedTab: StateFlow<Int> = _selectedTab
    val chargingLimit: StateFlow<Int> = _chargingLimit
    val smartCutoffLimit: StateFlow<Float> = _smartCutoffLimit
    val smartCutoffEnabled: StateFlow<Boolean> = _smartCutoffEnabled
    val monitorEnabled: StateFlow<Boolean> = _monitorEnabled
    val isFastChargeEnabled: StateFlow<Boolean> = _isFastChargeEnabled
    val isFastChargeSupported: StateFlow<Boolean> = _isFastChargeSupported
    val isBypassEnabled: StateFlow<Boolean> = _isBypassEnabled
    val isBypassSupported: StateFlow<Boolean> = _isBypassSupported
    val isBatterySaverEnabled: StateFlow<Boolean> = _isBatterySaverEnabled
    val batteryCapacity: StateFlow<Int> = _batteryCapacity
    val chargingSpeed: StateFlow<Int> = _chargingSpeed
    val isChargingEnabled: StateFlow<Boolean> = _isChargingEnabled
    val thermalSconfig: StateFlow<String> = _thermalSconfig
    val hasThermalSconfig: StateFlow<Boolean> = _hasThermalSconfig
    val isSmartChargeSupported: StateFlow<Boolean> = _isSmartChargeSupported
    val isChargingLimitSupported: StateFlow<Boolean> = _isChargingLimitSupported

    val chargingSpeedOptions = listOf(500, 1000, 1500, 2000, 2500, 3000, 5000)
    val chargingLimitOptions = listOf(50, 60, 70, 80, 85, 90, 95, 100)
    
    init {
        startAutoRefresh()
    }

    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (isActive) {
                if (_isAutoRefresh.value) {
                    refreshData()
                }
                delay(2000) 
            }
        }
    }

    private fun refreshData() {
        val context = getApplication<Application>().applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            val info = BatteryControllerUtils.getBatteryInfo(context)
            val stats = BatteryControllerUtils.getChargingStats()
            val isChargingEnabledVal = BatteryControllerUtils.getChargingEnabledStatus()
            
            // --- PEAK & TRACKING ---
            if (info.temperature > _peakTemp.value) _peakTemp.value = info.temperature
            if (info.isCharging) {
                if (info.currentNow > _peakChargeCurrent.value) _peakChargeCurrent.value = info.currentNow
            } else {
                if (info.currentNow > _peakDischargeCurrent.value) _peakDischargeCurrent.value = info.currentNow
            }

            val historyList = _currentHistory.value.toMutableList()
            historyList.add(ChartPoint(System.currentTimeMillis(), info.currentNow, info.isCharging))
            if (historyList.size > 18) historyList.removeAt(0)

            // --- AUTO-ENABLE CHARGING LOGIC ---
            val isCurrentlyPlugged = info.chargingType != "None"
            val currentTime = System.currentTimeMillis()

            if (!isCurrentlyPlugged) {
                if (lastPhysicallyPlugged) unpluggedTimestamp = currentTime
                
                val targetLimit = settingsPreference.smartCutoffLimit.value
                val isSafelyBelowTarget = info.level < (targetLimit - 5)
                
                if ((!settingsPreference.smartCutoffEnabled.value || isSafelyBelowTarget) && currentTime - unpluggedTimestamp > 10000 && !isChargingEnabledVal) {
                    BatteryControllerUtils.setChargingEnabled(true)
                }
            } else {
                if (!lastPhysicallyPlugged) {
                    if (!isChargingEnabledVal && !settingsPreference.smartCutoffEnabled.value) {
                        BatteryControllerUtils.setChargingEnabled(true)
                    }
                }
                unpluggedTimestamp = 0
            }
            lastPhysicallyPlugged = isCurrentlyPlugged

            withContext(Dispatchers.Main) {
                _batteryInfo.value = info
                _chargingStats.value = stats
                _isChargingEnabled.value = BatteryControllerUtils.getChargingEnabledStatus()
                _currentHistory.value = historyList
            }
        }
    }

    fun loadBatteryData(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            
            val info = BatteryControllerUtils.getBatteryInfo(context)
            val stats = BatteryControllerUtils.getChargingStats()
            val capacity = BatteryControllerUtils.getBatteryCapacity()
            
            _peakTemp.value = info.temperature
            if (info.isCharging) _peakChargeCurrent.value = info.currentNow else _peakDischargeCurrent.value = info.currentNow
            
            val fastChargeSupported = BatteryControllerUtils.isFastChargeSupported()
            val fastChargeStatus = BatteryControllerUtils.getFastChargeStatus()
            val bypassSupported = BatteryControllerUtils.isBypassSupported()
            val bypassStatus = BatteryControllerUtils.getBypassStatus()
            val batterySaver = BatteryControllerUtils.isBatterySaverEnabled()
            val smartChargeSup = BatteryControllerUtils.isSmartChargeSupported()
            val chargingLimitSup = BatteryControllerUtils.isChargingLimitSupported()
            val currentLimit = if (chargingLimitSup) BatteryControllerUtils.getChargingLimit() else 100
            val isChargingEnabledVal = BatteryControllerUtils.getChargingEnabledStatus()
            val hasThermal = BatteryControllerUtils.hasThermalSconfig()
            val thermalVal = if (hasThermal) BatteryControllerUtils.getThermalSconfig() else "0"
            
            val savedCutoff = settingsPreference.smartCutoffLimit.value
            val savedMonitor = settingsPreference.batteryMonitorEnabled.value
            
            val isCutoffRunning = isServiceRunning(context, SmartCutoffService::class.java)
            val isMonitorRunning = isServiceRunning(context, BatteryMonitorService::class.java)
            
            if (savedMonitor && !isMonitorRunning) {
                withContext(Dispatchers.Main) { toggleMonitor(context, true) }
            }

            withContext(Dispatchers.Main) {
                _batteryInfo.value = info
                _chargingStats.value = stats
                _batteryCapacity.value = capacity
                
                _isFastChargeSupported.value = fastChargeSupported
                _isFastChargeEnabled.value = fastChargeStatus
                _isBypassSupported.value = bypassSupported
                _isBypassEnabled.value = bypassStatus
                _isBatterySaverEnabled.value = batterySaver
                _isSmartChargeSupported.value = smartChargeSup
                _isChargingLimitSupported.value = chargingLimitSup
                _chargingLimit.value = currentLimit
                _isChargingEnabled.value = isChargingEnabledVal
                _hasThermalSconfig.value = hasThermal
                _thermalSconfig.value = thermalVal
                
                _smartCutoffLimit.value = savedCutoff
                _smartCutoffEnabled.value = isCutoffRunning
                _monitorEnabled.value = savedMonitor
                
                _isLoading.value = false
            }
        }
    }

    fun onTabSelected(index: Int) { _selectedTab.value = index }
    fun setChargingSpeed(mA: Int) { 
        viewModelScope.launch(Dispatchers.IO) { 
            if (BatteryControllerUtils.setChargingSpeed(mA)) {
                _chargingSpeed.value = mA 
                settingsPreference.setChargingSpeed(mA)
            }
        } 
    }
    fun setChargingLimit(percentage: Int) { 
        viewModelScope.launch(Dispatchers.IO) { 
            if (BatteryControllerUtils.setChargingLimit(percentage)) {
                _chargingLimit.value = percentage 
                settingsPreference.setChargingLimit(percentage)
            }
        } 
    }
    
    fun setSmartCutoffLimit(context: Context, limit: Float) {
        _smartCutoffLimit.value = limit
        settingsPreference.setSmartCutoffLimit(limit)
        if (_smartCutoffEnabled.value) {
            val intent = Intent(context, SmartCutoffService::class.java).apply {
                action = SmartCutoffService.ACTION_UPDATE_LIMIT
                putExtra(SmartCutoffService.EXTRA_LIMIT, limit.toInt())
            }
            context.startService(intent)
        }
    }

    fun toggleSmartCutoff(context: Context, enable: Boolean) {
        _smartCutoffEnabled.value = enable
        settingsPreference.setSmartCutoffEnabled(enable)
        val intent = Intent(context, SmartCutoffService::class.java)
        if (enable) {
            intent.putExtra(SmartCutoffService.EXTRA_LIMIT, _smartCutoffLimit.value.toInt())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        } else {
            intent.action = SmartCutoffService.ACTION_STOP_SERVICE
            context.startService(intent)
        }
    }
    
    fun toggleMonitor(context: Context, enable: Boolean) {
        _monitorEnabled.value = enable
        settingsPreference.setBatteryMonitorEnabled(enable)
        if (enable) {
            viewModelScope.launch(Dispatchers.IO) {
                delay(500) 
                withContext(Dispatchers.Main) {
                    val intent = Intent(context, BatteryMonitorService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
                }
            }
        } else {
            val intent = Intent(context, BatteryMonitorService::class.java)
            intent.action = BatteryMonitorService.ACTION_STOP
            context.startService(intent)
        }
    }

    fun toggleFastCharge(enabled: Boolean) { 
        viewModelScope.launch(Dispatchers.IO) { 
            if (BatteryControllerUtils.setFastCharge(enabled)) {
                _isFastChargeEnabled.value = enabled 
                settingsPreference.setFastChargeEnabled(enabled)
            }
        } 
    }
    fun toggleBypass(enabled: Boolean) { 
        viewModelScope.launch(Dispatchers.IO) { 
            if (BatteryControllerUtils.setBypassCharging(enabled)) {
                _isBypassEnabled.value = enabled 
                settingsPreference.setBypassChargingEnabled(enabled)
            }
        } 
    }
    fun toggleBatterySaver(enabled: Boolean) { 
        viewModelScope.launch(Dispatchers.IO) { 
            if (BatteryControllerUtils.setBatterySaver(enabled)) {
                _isBatterySaverEnabled.value = enabled 
                settingsPreference.setBatterySaverEnabled(enabled)
            }
        } 
    }
    fun toggleCharging(enabled: Boolean) { 
        viewModelScope.launch(Dispatchers.IO) { 
            if (BatteryControllerUtils.setChargingEnabled(enabled)) {
                _isChargingEnabled.value = enabled 
            }
        } 
    }
    fun updateThermalSconfig(value: String) { 
        viewModelScope.launch(Dispatchers.IO) { 
            if (BatteryControllerUtils.setThermalSconfig(value)) {
                _thermalSconfig.value = value 
                settingsPreference.setThermalSconfig(value)
            }
        } 
    }
    
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) return true
        }
        return false
    }
}
