/*
 * Copyright (c) 2025 ZKM
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.zuan.kernelmanager.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlin.math.abs
import java.util.regex.Pattern

object MonitorReader {
    private const val TAG = "MonitorReader"
    private var lastTotal = 0L
    private var lastIdle = 0L
    
    private var isShellInitialized = false
    private const val SHELL_TIMEOUT = 5000L

    fun initializeShell() {
        if (isShellInitialized) return
        Thread {
            try { Shell.getShell(); isShellInitialized = true } catch (e: Exception) { isShellInitialized = false }
        }.start()
    }
    
    private fun ensureShell(): Boolean {
        if (!isShellInitialized) initializeShell()
        return isShellInitialized
    }

    // 🔥 FIX UTAMA: Menggunakan Regex untuk mencari nama paket
    fun getForegroundPackage(): String {
        return try {
            // COBA 1: dumpsys activity (Paling akurat untuk Game)
            val dump1 = ShellExecutor.executeWithResult("dumpsys activity activities | grep mResumedActivity")
            val pkg1 = parsePackageFromDump(dump1)
            if (pkg1.isNotEmpty()) return pkg1

            // COBA 2: dumpsys window (Fallback standar)
            val dump2 = ShellExecutor.executeWithResult("dumpsys window | grep mCurrentFocus")
            val pkg2 = parsePackageFromDump(dump2)
            if (pkg2.isNotEmpty()) return pkg2
            
            // COBA 3: cmd activity (Android 11+)
            val dump3 = ShellExecutor.executeWithResult("cmd activity get-top-activity")
            val pkg3 = parsePackageFromDump(dump3)
            if (pkg3.isNotEmpty()) return pkg3

            ""
        } catch (e: Exception) {
            Log.e(TAG, "Pkg Error: ${e.message}")
            ""
        }
    }

    // Fungsi Pintar: Mencari pola "com.abc.xyz/" di dalam teks sampah
    private fun parsePackageFromDump(rawOutput: String): String {
        if (rawOutput.isEmpty()) return ""
        try {
            // Pola Regex: Mencari string yang diakhiri "/" dan mengandung setidaknya satu titik "."
            // Contoh target: u0 com.mobile.legends/com.activity...
            val pattern = Pattern.compile("([a-zA-Z0-9_]+\\.[a-zA-Z0-9_\\.]+)/")
            val matcher = pattern.matcher(rawOutput)
            
            if (matcher.find()) {
                val found = matcher.group(1) ?: ""
                // Filter hasil palsu
                if (found != "com.android.systemui" && found != "com.miui.home" && found != "android") {
                    return found
                }
            }
        } catch (e: Exception) {}
        return ""
    }

    // Helper UI: Ubah Package Name -> Nama Aplikasi (Contoh: com.tencent.ig -> PUBG Mobile)
    fun getAppName(context: Context, packageName: String): String {
        if (packageName == "Unknown App" || packageName.isEmpty()) return "Unknown App"
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName // Kalau gagal, kembalikan ID-nya saja
        }
    }

    // Helper UI: Ubah Package Name -> Icon Gambar
    fun getAppIcon(context: Context, packageName: String): Drawable? {
        if (packageName == "Unknown App" || packageName.isEmpty()) return null
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            null
        }
    }
    
    // --- MONITORING LAINNYA TETAP SAMA ---
    fun getCurrentRenderer(): String {
        try {
            val pkgName = getForegroundPackage()
            if (pkgName.isNotEmpty()) {
                // Get all PIDs for this package (main process and children)
                val pids = ShellExecutor.executeWithResult("pgrep -f $pkgName").split("\n").mapNotNull { it.trim() }

                for (pid in pids) {
                    if (pid.isEmpty()) continue

                    // Check maps for Vulkan or GL libraries
                    val maps = ShellExecutor.executeWithResult("cat /proc/$pid/maps")
                    if (maps.contains("libvulkan.so") || maps.contains("vulkan.adreno.so") || maps.contains("libvulkan_")) {
                        return "VULKAN"
                    }
                    if (maps.contains("libGLESv3") || maps.contains("libGLESv2") || maps.contains("libGLESv1")) {
                        return "OPENGL"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Renderer Error: ${e.message}")
        }
        return "FPS"
    }

    fun getCpuLoad(): Int {
        if (!ensureShell()) return 0
        return try {
            val statOutput = Shell.cmd("cat /proc/stat | head -n 1").exec().out
            val firstLine = statOutput.firstOrNull() ?: return 0
            val parts = firstLine.trim().split("\\s+".toRegex())
            if (parts.size < 5) return 0
            val user = parts[1].toLongOrNull() ?: 0L
            val system = parts[3].toLongOrNull() ?: 0L
            val idle = parts[4].toLongOrNull() ?: 0L
            val total = user + system + idle + (parts[2].toLongOrNull()?:0L)
            
            if (lastTotal == 0L) { lastTotal = total; lastIdle = idle; return 0 }
            val diffTotal = total - lastTotal
            val diffIdle = idle - lastIdle
            lastTotal = total; lastIdle = idle
            
            if (diffTotal <= 0L) return 0
            ((diffTotal - diffIdle) * 100 / diffTotal).toInt().coerceIn(0, 100)
        } catch (e: Exception) { 0 }
    }
    
    fun getPowerWatt(): Float {
        if (!ensureShell()) return 0f
        return try {
            val v = ShellExecutor.executeWithResult("cat /sys/class/power_supply/battery/voltage_now").toLongOrNull() ?: 0L
            val c = ShellExecutor.executeWithResult("cat /sys/class/power_supply/battery/current_now").toLongOrNull() ?: 0L
            if (v > 0 && c != 0L) (abs(v * c).toDouble() / 1_000_000_000_000.0).toFloat() else 0f
        } catch (e: Exception) { 0f }
    }

    fun getBatteryTemp(context: Context): Float {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tempRaw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            tempRaw / 10f
        } catch (e: Exception) { 0f }
    }

    private var cachedCpuTempZoneIndex: Int = -1

    fun getCpuTemp(): Float {
        return try {
            // 1. Check cached index
            if (cachedCpuTempZoneIndex != -1) {
                val tempRaw = ShellExecutor.executeWithResult("cat /sys/class/thermal/thermal_zone$cachedCpuTempZoneIndex/temp").trim().toIntOrNull() ?: 0
                return if (abs(tempRaw) > 1000) tempRaw / 1000f else tempRaw.toFloat()
            }

            // 2. Try to find a CPU thermal zone
            val zones = listOf("cpu-thermal", "tsens_tz_sensor", "core_temp", "soc-thermal", "cpu-0-0-usr")
            var foundTemp = 0f

            for (i in 0..100) {
                val type = ShellExecutor.executeWithResult("cat /sys/class/thermal/thermal_zone$i/type 2>/dev/null").lowercase()
                if (zones.any { type.contains(it) }) {
                    val tempRaw = ShellExecutor.executeWithResult("cat /sys/class/thermal/thermal_zone$i/temp").trim().toIntOrNull() ?: 0
                    foundTemp = if (abs(tempRaw) > 1000) tempRaw / 1000f else tempRaw.toFloat()
                    if (foundTemp > 0) {
                        cachedCpuTempZoneIndex = i
                        break
                    }
                }
            }
            
            // 3. Fallback to zone0 if nothing found or found 0
            if (foundTemp == 0f) {
                val tempRaw = ShellExecutor.executeWithResult("cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null").trim().toIntOrNull() ?: 0
                foundTemp = if (abs(tempRaw) > 1000) tempRaw / 1000f else tempRaw.toFloat()
            }
            foundTemp
        } catch (e: Exception) { 0f }
    }

    fun getGpuUsage(): Int {
        return try {
            // 1. Snapdragon/Adreno Standard
            val adrenoUsage = ShellExecutor.executeWithResult("cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage").replace("%", "").trim().toIntOrNull() ?: -1
            if (adrenoUsage != -1) return adrenoUsage.coerceIn(0, 100)

            // 2. Generic Devfreq 'load' node (Many Mali/Exynos/Mediatek devices)
            // Format is often "30@500MHz" or just "30"
            val devfreqLoad = ShellExecutor.executeWithResult("cat /sys/class/devfreq/*gpu*/load /sys/class/devfreq/*.mali/load /sys/class/devfreq/*.mali/utilization 2>/dev/null | head -n 1")
            if (devfreqLoad.isNotEmpty()) {
                val usageStr = devfreqLoad.split("@")[0].trim()
                val usage = usageStr.toIntOrNull() ?: -1
                if (usage != -1) return usage.coerceIn(0, 100)
            }

            // 3. Alternative Adreno Devfreq node
            val adrenoDevfreq = ShellExecutor.executeWithResult("cat /sys/class/devfreq/*.kgsl-3d0/load 2>/dev/null | head -n 1")
            if (adrenoDevfreq.isNotEmpty()) {
                val usage = adrenoDevfreq.split("@")[0].trim().toIntOrNull() ?: -1
                if (usage != -1) return usage.coerceIn(0, 100)
            }

            // 4. Mali specific 'utilization'
            val maliUtil = ShellExecutor.executeWithResult("cat /sys/class/devfreq/*.mali/utilization 2>/dev/null | head -n 1").trim().toIntOrNull() ?: -1
            if (maliUtil != -1) return maliUtil.coerceIn(0, 100)

            // 5. Alternative kernel node
            val kernelBusy = ShellExecutor.executeWithResult("cat /sys/kernel/gpu/gpu_busy 2>/dev/null").trim().toIntOrNull() ?: -1
            if (kernelBusy != -1) return kernelBusy.coerceIn(0, 100)

            0
        } catch (e: Exception) { 0 }
    }

    private var cachedGpuTempZoneIndex: Int = -1

    fun getGpuTemp(): Float {
        return try {
            // 1. Adreno common path
            val adrenoTempRaw = ShellExecutor.executeWithResult("cat /sys/class/kgsl/kgsl-3d0/temp 2>/dev/null").trim().toIntOrNull() ?: 0
            if (adrenoTempRaw != 0) {
                return if (abs(adrenoTempRaw) > 1000) adrenoTempRaw / 1000f else adrenoTempRaw / 10f
            }

            // 2. Cached zone index
            if (cachedGpuTempZoneIndex != -1) {
                val tempRaw = ShellExecutor.executeWithResult("cat /sys/class/thermal/thermal_zone$cachedGpuTempZoneIndex/temp").trim().toIntOrNull() ?: 0
                return if (abs(tempRaw) > 1000) tempRaw / 1000f else tempRaw.toFloat()
            }

            // 3. Mali/Generic thermal zone scanning
            val zones = listOf("gpu-thermal", "gpu_temp", "mali_temp", "gpuss-0-usr")
            for (i in 0..100) {
                val type = ShellExecutor.executeWithResult("cat /sys/class/thermal/thermal_zone$i/type 2>/dev/null").lowercase()
                if (zones.any { type.contains(it) }) {
                    val tempRaw = ShellExecutor.executeWithResult("cat /sys/class/thermal/thermal_zone$i/temp").trim().toIntOrNull() ?: 0
                    val temp = if (abs(tempRaw) > 1000) tempRaw / 1000f else tempRaw.toFloat()
                    if (temp > 0) {
                        cachedGpuTempZoneIndex = i
                        return temp
                    }
                }
            }
            0f
        } catch (e: Exception) { 0f }
    }

    fun getCpuFreqAverage(): Int {
        return try {
            val output = ShellExecutor.executeWithResult("cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq")
            val lines = output.split("\n")
            val freqs = lines.mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }
            if (freqs.isEmpty()) return 0
            (freqs.average() / 1000).toInt()
        } catch (e: Exception) { 0 }
    }

    fun getGpuFreq(): Int {
        return try {
            // 1. Snapdragon/Adreno Standard
            var freqRaw = ShellExecutor.executeWithResult("cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null").trim().toLongOrNull() ?: 0L

            // 2. Devfreq cur_freq (Standard for Mali/Mediatek/Exynos and newer Snapdragon)
            if (freqRaw == 0L) {
                freqRaw = ShellExecutor.executeWithResult("cat /sys/class/devfreq/*gpu*/cur_freq /sys/class/devfreq/*.mali/cur_freq /sys/class/devfreq/*.kgsl-3d0/cur_freq 2>/dev/null | head -n 1").trim().toLongOrNull() ?: 0L
            }

            if (freqRaw == 0L) return 0

            // Normalisasi: Bisa Hz, KHz, atau MHz
            return when {
                freqRaw > 1000000 -> (freqRaw / 1000000).toInt() // Hz -> MHz
                freqRaw > 1000 -> (freqRaw / 1000).toInt() // KHz -> MHz
                else -> freqRaw.toInt() // MHz
            }
        } catch (e: Exception) { 0 }
    }
    
    data class RamInfo(val usedMb: Int, val totalMb: Int, val percent: Int)
    fun getRamInfo(context: Context): RamInfo {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            val totalMb = (memInfo.totalMem / 1048576L).toInt()
            val usedMb = ((memInfo.totalMem - memInfo.availMem) / 1048576L).toInt()
            RamInfo(usedMb, totalMb, ((usedMb.toDouble() / totalMb) * 100).toInt())
        } catch (e: Exception) { RamInfo(0, 0, 0) }
    }
    
    fun getDebugInfo(context: Context): String = "Ready"
}
