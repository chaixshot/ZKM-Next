/*
 * Copyright (c) 2025 ZKM
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.zuan.kernelmanager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Build
import com.zuan.kernelmanager.service.BatteryMonitorService
import com.zuan.kernelmanager.service.SmartCutoffService
import com.zuan.kernelmanager.ui.home.menu.BatteryControllerUtils
import com.zuan.kernelmanager.ui.settings.SettingsPreference
import com.zuan.kernelmanager.ui.socmenu.CpuGpuUtils
import com.zuan.kernelmanager.ui.socmenu.MemoryUtils
import com.zuan.kernelmanager.ui.socmenu.SchedulerUtils
import com.zuan.kernelmanager.utils.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray

class BootReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Applying settings on boot...")
            val prefs = SettingsPreference.getInstance(context)
            
            scope.launch {
                applyBatterySettings(context, prefs)
                applyAllProfiles(prefs)
            }
        }
    }

    private fun applyBatterySettings(context: Context, prefs: SettingsPreference) {
        if (prefs.selectedBatteryProfileName.value != null) return 

        Log.d("BootReceiver", "Applying individual battery settings...")
        BatteryControllerUtils.setChargingLimit(prefs.chargingLimit.value)
        BatteryControllerUtils.setFastCharge(prefs.fastChargeEnabled.value)
        BatteryControllerUtils.setBypassCharging(prefs.bypassChargingEnabled.value)
        BatteryControllerUtils.setBatterySaver(prefs.batterySaverEnabled.value)
        BatteryControllerUtils.setChargingSpeed(prefs.chargingSpeed.value)
        BatteryControllerUtils.setThermalSconfig(prefs.thermalSconfig.value)

        if (prefs.smartCutoffEnabled.value) {
            val sIntent = Intent(context, SmartCutoffService::class.java).apply {
                putExtra(SmartCutoffService.EXTRA_LIMIT, prefs.smartCutoffLimit.value.toInt())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(sIntent)
            } else {
                context.startService(sIntent)
            }
        }

        if (prefs.batteryMonitorEnabled.value) {
            val mIntent = Intent(context, BatteryMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(mIntent)
            } else {
                context.startService(mIntent)
            }
        }
    }

    private suspend fun applyAllProfiles(prefs: SettingsPreference) {
        // CPU/GPU
        val cpuJson = prefs.cpuGpuProfilesJson.value
        val selectedCpu = prefs.selectedCpuProfileName.value
        if (selectedCpu != null) {
            findAndApplyCpuProfile(cpuJson, selectedCpu)
        }

        // Sched
        val schedJson = prefs.schedProfilesJson.value
        val selectedSched = prefs.selectedSchedProfileName.value
        if (selectedSched != null) {
            findAndApplySchedProfile(schedJson, selectedSched)
        }

        // Memory
        val memJson = prefs.memProfilesJson.value
        val selectedMem = prefs.selectedMemProfileName.value
        if (selectedMem != null) {
            findAndApplyMemProfile(memJson, selectedMem)
        }

        // Network
        val netJson = prefs.netProfilesJson.value
        val selectedNet = prefs.selectedNetProfileName.value
        if (selectedNet != null) {
            findAndApplyNetProfile(netJson, selectedNet)
        }
    }

    private suspend fun findAndApplyCpuProfile(json: String, name: String) {
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.getString("name") == name) {
                    val cpuArray = obj.getJSONArray("cpuSettings")
                    for (j in 0 until cpuArray.length()) {
                        val cpuObj = cpuArray.getJSONObject(j)
                        CpuGpuUtils.writeFreq(cpuObj.getString("policyPath"), "scaling_min_freq", cpuObj.getString("minFreq"))
                        CpuGpuUtils.writeFreq(cpuObj.getString("policyPath"), "scaling_max_freq", cpuObj.getString("maxFreq"))
                        CpuGpuUtils.writeGov(cpuObj.getString("policyPath"), cpuObj.getString("governor"))
                    }
                    break
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun findAndApplySchedProfile(json: String, name: String) {
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.getString("name") == name) {
                    val toggles = obj.optJSONObject("toggles")
                    toggles?.keys()?.forEach { path -> Utils.writeFile(path, toggles.getString(path)) }
                    if (obj.has("bore")) Utils.writeFile(SchedulerUtils.BORE, obj.getInt("bore").toString())
                    val uclamp = obj.optJSONObject("uclamp")
                    uclamp?.keys()?.forEach { path -> Utils.writeFile(path, uclamp.getString(path)) }
                    val tunables = obj.optJSONObject("genericTunables")
                    tunables?.keys()?.forEach { path -> Utils.writeFile(path, tunables.getString(path)) }
                    break
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun findAndApplyMemProfile(json: String, name: String) {
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.getString("name") == name) {
                    val vm = obj.optJSONObject("vmSettings")
                    vm?.keys()?.forEach { path -> Utils.writeFile(path, vm.getString(path)) }
                    if (obj.has("zramSizeMb") && obj.has("zramAlgo")) {
                        val size = obj.getLong("zramSizeMb") * 1048576L
                        val algo = obj.getString("zramAlgo")
                        MemoryUtils.swapoff()
                        MemoryUtils.resetZram()
                        MemoryUtils.setZramCompAlgorithm(algo)
                        MemoryUtils.setZramSize(size)
                        MemoryUtils.mkswap()
                        MemoryUtils.swapon()
                    }
                    val io = obj.optJSONObject("ioSchedulers")
                    io?.keys()?.forEach { dev -> MemoryUtils.setIOScheduler(dev, io.getString(dev)) }
                    break
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun findAndApplyNetProfile(json: String, name: String) {
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.getString("name") == name) {
                    if (obj.has("tcpCongestion")) Utils.writeFile("/proc/sys/net/ipv4/tcp_congestion_control", obj.getString("tcpCongestion"))
                    val toggles = obj.optJSONObject("toggles")
                    toggles?.keys()?.forEach { path -> Utils.writeFile(path, toggles.getString(path)) }
                    if (obj.has("printk")) Utils.writeFile("/proc/sys/kernel/printk", obj.getString("printk"))
                    break
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

}
