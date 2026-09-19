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
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.topjohnwu.superuser.Shell
import com.zuan.kernelmanager.R
import com.zuan.kernelmanager.service.BatteryMonitorService
import com.zuan.kernelmanager.service.SmartCutoffService
import com.zuan.kernelmanager.ui.home.menu.BatteryControllerUtils
import com.zuan.kernelmanager.ui.settings.SettingsPreference
import com.zuan.kernelmanager.ui.socmenu.CpuGpuUtils
import com.zuan.kernelmanager.ui.socmenu.MemoryUtils
import com.zuan.kernelmanager.ui.socmenu.SchedulerUtils
import com.zuan.kernelmanager.utils.GenericGpuUtils
import com.zuan.kernelmanager.utils.Utils
import com.zuan.kernelmanager.utils.AdrenoUtils
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class BootReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            val pendingResult = goAsync()
            val prefs = SettingsPreference.getInstance(context)
            
            Log.d("BootReceiver", "Boot detected. applyOnBoot=${prefs.applyOnBoot.value}")

            if (!prefs.applyOnBoot.value) {
                pendingResult.finish()
                return
            }

            Log.d("BootReceiver", "Applying settings on boot (Async)...")
            
            scope.launch {
                try {
                    showApplyNotification(context)
                    applyBatterySettings(context, prefs)
                    applySocSettings(prefs)
                    applyAllProfiles(prefs)
                    Log.d("BootReceiver", "All settings applied successfully.")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Error applying settings on boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private fun applyBatterySettings(context: Context, prefs: SettingsPreference) {
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

    private suspend fun applySocSettings(prefs: SettingsPreference) {
        if (prefs.selectedCpuProfileName.value == null) {
            applyIndividualCpuSettings(prefs.individualCpuSettingsJson.value)
        }
        // More individual settings can be added here (GPU, Sched, etc.)
    }

    private suspend fun applyIndividualCpuSettings(json: String) {
        try {
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val policyPath = keys.next()
                val clusterObj = root.getJSONObject(policyPath)
                
                if (clusterObj.has("min")) {
                    CpuGpuUtils.writeFreq(policyPath, "scaling_min_freq", clusterObj.getString("min"))
                }
                if (clusterObj.has("max")) {
                    CpuGpuUtils.writeFreq(policyPath, "scaling_max_freq", clusterObj.getString("max"))
                }
                if (clusterObj.has("governor")) {
                    CpuGpuUtils.writeGov(policyPath, clusterObj.getString("governor"))
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun showApplyNotification(context: Context) {
        val channelId = "boot_apply_channel"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Apply on Boot", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
        
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Zuan Kernel Manager")
            .setContentText("Re-applying your performance and battery settings...")
            .setSmallIcon(R.drawable.ic_check)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
            
        manager.notify(1001, notification)
    }

    private suspend fun applyAllProfiles(prefs: SettingsPreference) {
        // CPU/GPU
        val cpuJson = prefs.cpuGpuProfilesJson.value
        val selectedCpu = prefs.selectedCpuProfileName.value
        if (selectedCpu != null) {
            findAndApplyCpuGpuProfile(cpuJson, selectedCpu)
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

    private suspend fun findAndApplyCpuGpuProfile(json: String, name: String) {
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.getString("name") == name) {
                    // CPU
                    val cpuArray = obj.getJSONArray("cpuSettings")
                    for (j in 0 until cpuArray.length()) {
                        val cpuObj = cpuArray.getJSONObject(j)
                        val policyPath = cpuObj.getString("policyPath")
                        val gov = cpuObj.getString("governor")
                        CpuGpuUtils.writeFreq(policyPath, "scaling_min_freq", cpuObj.getString("minFreq"))
                        CpuGpuUtils.writeFreq(policyPath, "scaling_max_freq", cpuObj.getString("maxFreq"))
                        CpuGpuUtils.writeGov(policyPath, gov)
                        
                        if (cpuObj.has("tunables")) {
                            val tunables = cpuObj.getJSONObject("tunables")
                            tunables.keys().forEach { key ->
                                val value = tunables.getString(key)
                                val path = "$policyPath/$gov/$key"
                                CpuGpuUtils.writeTunable(path, value)
                            }
                        }
                    }
                    
                    // GPU
                    if (obj.has("gpuSetting") && !obj.isNull("gpuSetting")) {
                        val gpuObj = obj.getJSONObject("gpuSetting")
                        val type = gpuObj.getString("type")
                        val freq = gpuObj.getString("currentFreq")
                        
                        if (type == CpuGpuUtils.GpuType.ADRENO.name) {
                            if (gpuObj.has("minFreq")) {
                                Shell.cmd("echo ${gpuObj.getString("minFreq")} > /sys/class/kgsl/kgsl-3d0/min_clock_mhz").exec()
                            }
                            if (gpuObj.has("maxFreq")) {
                                Shell.cmd("echo ${gpuObj.getString("maxFreq")} > /sys/class/kgsl/kgsl-3d0/max_clock_mhz").exec()
                            }
                            if (gpuObj.has("governor")) {
                                Shell.cmd("echo ${gpuObj.getString("governor")} > /sys/class/kgsl/kgsl-3d0/devfreq/governor").exec()
                            }
                            if (gpuObj.has("throttling")) {
                                Shell.cmd("echo ${gpuObj.getString("throttling")} > /sys/class/kgsl/kgsl-3d0/throttling").exec()
                            }
                            if (gpuObj.has("adrenoBoost")) {
                                Shell.cmd("echo ${gpuObj.getString("adrenoBoost")} > /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost").exec()
                            }

                            // Advanced KGSL settings
                            listOf("idlerActive", "idlerIdleWait", "idlerDownDiff", "idlerWorkload", 
                                   "simpleGpuActive", "simpleLaziness", "simpleRampThreshold",
                                   "forceNoNap", "forceClkOn", "forceBusOn", "busSplit",
                                   "defaultPwrlevel", "maxPwrlevel", "thermalPwrlevel").forEach { key ->
                                if (gpuObj.has(key)) {
                                    val value = gpuObj.getString(key)
                                    val sysfsPath = when(key) {
                                        "idlerActive" -> AdrenoUtils.IDLER_ACTIVE
                                        "idlerIdleWait" -> AdrenoUtils.IDLER_IDLEWAIT
                                        "idlerDownDiff" -> AdrenoUtils.IDLER_DOWNDIFF
                                        "idlerWorkload" -> AdrenoUtils.IDLER_WORKLOAD
                                        "simpleGpuActive" -> AdrenoUtils.SIMPLE_GPU_ACTIVATE
                                        "simpleLaziness" -> AdrenoUtils.SIMPLE_GPU_LAZINESS
                                        "simpleRampThreshold" -> AdrenoUtils.SIMPLE_RAMP_THRESHOLD
                                        "forceNoNap" -> "${AdrenoUtils.KGSL_3D0_DIR}/force_no_nap"
                                        "forceClkOn" -> "${AdrenoUtils.KGSL_3D0_DIR}/force_clk_on"
                                        "forceBusOn" -> "${AdrenoUtils.KGSL_3D0_DIR}/force_bus_on"
                                        "busSplit" -> "${AdrenoUtils.KGSL_3D0_DIR}/bus_split"
                                        "defaultPwrlevel" -> "${AdrenoUtils.KGSL_3D0_DIR}/default_pwrlevel"
                                        "maxPwrlevel" -> "${AdrenoUtils.KGSL_3D0_DIR}/max_pwrlevel"
                                        "thermalPwrlevel" -> "${AdrenoUtils.KGSL_3D0_DIR}/thermal_pwrlevel"
                                        else -> ""
                                    }
                                    if (sysfsPath.isNotEmpty()) Shell.cmd("echo $value > $sysfsPath").exec()
                                }
                            }

                            Shell.cmd("echo $freq > /sys/class/kgsl/kgsl-3d0/gpuclk").exec()
                        } else if (type == CpuGpuUtils.GpuType.GENERIC_DEVFREQ.name) {
                            GenericGpuUtils.getGpuPath()?.let { path ->
                                if (gpuObj.has("minFreq")) GenericGpuUtils.setFreq(path, "min", gpuObj.getString("minFreq"))
                                if (gpuObj.has("maxFreq")) GenericGpuUtils.setFreq(path, "max", gpuObj.getString("maxFreq"))
                                if (gpuObj.has("governor")) GenericGpuUtils.setGov(path, gpuObj.getString("governor"))
                                GenericGpuUtils.setFreq(path, "max", freq)
                            }
                        }
                    }

                    if (obj.has("cpusets")) {
                        val cpusets = obj.getJSONObject("cpusets")
                        cpusets.keys().forEach { key ->
                            val value = cpusets.getString(key)
                            Shell.cmd("echo \"$value\" > /dev/cpuset/$key/cpus").exec()
                        }
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
                    
                    val ioTunables = obj.optJSONObject("ioTunables")
                    ioTunables?.keys()?.forEach { dev ->
                        val devObj = ioTunables.getJSONObject(dev)
                        devObj.keys().forEach { key ->
                            MemoryUtils.setIOTunable(dev, key, devObj.getString(key))
                        }
                    }
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
