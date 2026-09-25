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
import android.os.SystemClock
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File
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
    private var lastRendererPkg: String? = null
    private var lastRendererResult: String = "FPS"

    fun getCurrentRenderer(): String {
        try {
            val pkgName = getForegroundPackage()
            if (pkgName.isEmpty()) return "FPS"
            
            // Optimization: If package is same as last time, reuse result for 5 seconds
            if (pkgName == lastRendererPkg && System.currentTimeMillis() % 5000 != 0L) {
                return lastRendererResult
            }
            
            val pids = ShellExecutor.executeWithResult("pgrep -f $pkgName").split("\n").filter { it.isNotBlank() }

            for (pid in pids) {
                if (pid.trim().isEmpty()) continue

                val maps = ShellExecutor.executeWithResult("cat /proc/${pid.trim()}/maps")
                if (maps.contains("libvulkan.so") || maps.contains("vulkan.adreno.so") || maps.contains("libvulkan_")) {
                    lastRendererPkg = pkgName
                    lastRendererResult = "VULKAN"
                    return "VULKAN"
                }
                if (maps.contains("libGLESv3") || maps.contains("libGLESv2") || maps.contains("libGLESv1")) {
                    lastRendererPkg = pkgName
                    lastRendererResult = "OPENGL"
                    return "OPENGL"
                }
            }
            
            lastRendererPkg = pkgName
            lastRendererResult = "FPS"
        } catch (e: Exception) {
            Log.e(TAG, "Renderer Error: ${e.message}")
        }
        return lastRendererResult
    }

    fun getCpuLoad(): Int {
        if (!ensureShell()) return 0
        return try {
            val statOutput = Shell.cmd("cat /proc/stat").exec().out
            if (statOutput.isEmpty()) return 0
            val firstLine = statOutput[0]
            val parts = firstLine.trim().split(Pattern.compile("\\s+"))
            if (parts.size < 5) return 0
            
            // parts[0] is "cpu"
            val user = parts[1].toLongOrNull() ?: 0L
            val nice = parts[2].toLongOrNull() ?: 0L
            val system = parts[3].toLongOrNull() ?: 0L
            val idle = parts[4].toLongOrNull() ?: 0L
            val iowait = parts.getOrNull(5)?.toLongOrNull() ?: 0L
            val irq = parts.getOrNull(6)?.toLongOrNull() ?: 0L
            val softirq = parts.getOrNull(7)?.toLongOrNull() ?: 0L
            
            val total = user + nice + system + idle + iowait + irq + softirq
            
            if (lastTotal == 0L) { 
                lastTotal = total
                lastIdle = idle
                return 0 
            }
            
            val diffTotal = total - lastTotal
            val diffIdle = idle - lastIdle
            
            lastTotal = total
            lastIdle = idle
            
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

    private var cachedGpuUsagePath: String? = null
    private var cachedMtkGpuIdle = false
    private var lastGpuClockStatsBusyUs: Long = 0L
    private var lastGpuClockStatsTimeMs: Long = 0L
    private var lastGpuActiveCycles: Long = 0L
    private var lastGpuTotalCycles: Long = 0L

    private fun readAdrenoGpuClockStats(): Int? {
        val raw = ShellExecutor.executeWithResult("cat /sys/class/kgsl/kgsl-3d0/gpu_clock_stats 2>/dev/null").trim()
        if (raw.isEmpty()) return null

        val firstVal = raw.split("\\s+".toRegex()).firstOrNull() ?: return null
        val curBusyUs = firstVal.toLongOrNull() ?: return null
        val nowMs = SystemClock.elapsedRealtime()

        if (lastGpuClockStatsTimeMs > 0L && nowMs > lastGpuClockStatsTimeMs && curBusyUs >= lastGpuClockStatsBusyUs) {
            val deltaBusyUs = curBusyUs - lastGpuClockStatsBusyUs
            val deltaTimeMs = nowMs - lastGpuClockStatsTimeMs
            val deltaTimeUs = deltaTimeMs * 1000L

            if (deltaTimeUs > 0L) {
                val load = ((deltaBusyUs * 100L) / deltaTimeUs).toInt().coerceIn(0, 100)
                
                lastGpuClockStatsBusyUs = curBusyUs
                lastGpuClockStatsTimeMs = nowMs
                return load
            }
        }

        lastGpuClockStatsBusyUs = curBusyUs
        lastGpuClockStatsTimeMs = nowMs
        return 0
    }

    private fun readAdrenoGpuBusy(): Int? {
        val raw = ShellExecutor.executeWithResult("cat /sys/class/kgsl/kgsl-3d0/gpubusy /sys/kernel/debug/kgsl/kgsl-3d0/gpubusy 2>/dev/null | head -n 1").trim()
        if (raw.isEmpty()) return null

        val parts = raw.split("\\s+".toRegex())
        if (parts.size < 2) return null

        val curActive = parts[0].removePrefix("0x").toLongOrNull(16) ?: parts[0].toLongOrNull() ?: 0L
        val curTotal = parts[1].removePrefix("0x").toLongOrNull(16) ?: parts[1].toLongOrNull() ?: 0L

        if (curTotal <= 0L) {
            lastGpuActiveCycles = 0L
            lastGpuTotalCycles = 0L
            return 0
        }

        if (lastGpuTotalCycles <= 0L || curTotal < lastGpuTotalCycles) {
            lastGpuActiveCycles = curActive
            lastGpuTotalCycles = curTotal
            return 0
        }

        val deltaActive = (curActive - lastGpuActiveCycles).coerceAtLeast(0L)
        val deltaTotal = curTotal - lastGpuTotalCycles

        lastGpuActiveCycles = curActive
        lastGpuTotalCycles = curTotal

        if (deltaTotal > 0L) {
            return ((deltaActive * 100L) / deltaTotal).toInt().coerceIn(0, 100)
        }

        return 0
    }

    fun getGpuUsage(): Int {
        try {
            // 1. Try Adreno clock stats normalized multi-pipe delta (Primary source for Adreno 6xx/7xx / Mi Pad 6 / HyperOS)
            val adrenoClockStats = readAdrenoGpuClockStats()
            if (adrenoClockStats != null) {
                return adrenoClockStats
            }

            // 2. Try Adreno gpubusy interval delta
            val adrenoBusy = readAdrenoGpuBusy()
            if (adrenoBusy != null) {
                return adrenoBusy
            }

            val kgslFiles = ShellExecutor.executeWithResult("ls /sys/class/kgsl/kgsl-3d0/ 2>/dev/null").trim()
            Log.d("GPU_DEBUG", "Available KGSL nodes: $kgslFiles")

            // 2. Check cached direct working path if valid
            cachedGpuUsagePath?.let { path ->
                val raw = ShellExecutor.executeWithResult("cat $path 2>/dev/null").trim()
                if (raw.isNotEmpty() && raw != "-1") {
                    val firstPart = raw.split("@")[0].split(" ").first().replace("%", "").trim()
                    val usage = firstPart.toIntOrNull() ?: -1
                    if (usage in 0..100) {
                        return if (cachedMtkGpuIdle) (100 - usage).coerceIn(0, 100) else usage
                    }
                }
                cachedGpuUsagePath = null
                cachedMtkGpuIdle = false
            }

            // 3. Direct percentage nodes (EXCLUDING fake static config nodes like /sys/class/kgsl/kgsl-3d0/usage or /sys/class/devfreq/.../load)
            val directGpuPaths = listOf(
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                "/sys/class/kgsl/kgsl-3d0/gpu_load",
                "/sys/module/ged/parameters/gpu_loading",
                "/sys/class/misc/mali0/device/utilization",
                "/sys/devices/platform/soc/soc:qcom,kgsl-3d0/kgsl/kgsl-3d0/gpu_busy_percentage"
            )

            for (path in directGpuPaths) {
                val raw = ShellExecutor.executeWithResult("cat $path 2>/dev/null").trim()
                if (raw.isNotEmpty()) {
                    val value = raw.split("@")[0].split(" ").first().replace("%", "").trim().toIntOrNull() ?: -1
                    if (value in 0..100) {
                        cachedGpuUsagePath = path
                        return value
                    }
                }
            }

            // 4. MediaTek gpu_idle fallback
            val mtkIdlePath = "/sys/module/ged/parameters/gpu_idle"
            val mtkIdleRaw = ShellExecutor.executeWithResult("cat $mtkIdlePath 2>/dev/null").trim().toIntOrNull() ?: -1
            if (mtkIdleRaw in 0..100) {
                cachedGpuUsagePath = mtkIdlePath
                cachedMtkGpuIdle = true
                return (100 - mtkIdleRaw).coerceIn(0, 100)
            }

            // 5. Alternative kernel nodes
            val lastResort = listOf("/sys/kernel/gpu/gpu_busy", "/proc/mali/utilization", "/sys/module/mali_kbase/parameters/gpu_utilization")
            for (path in lastResort) {
                val usage = ShellExecutor.executeWithResult("cat $path 2>/dev/null").trim().toIntOrNull() ?: -1
                if (usage in 0..100) {
                    cachedGpuUsagePath = path
                    return usage
                }
            }

        } catch (e: Exception) {
            Log.e("GPU_DEBUG", "Error in getGpuUsage: ${e.message}")
        }
        return 0
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

    private var cachedCpuFreqPaths: List<String>? = null

    fun getCpuFreqAverage(): Int {
        return try {
            val paths = if (cachedCpuFreqPaths != null) {
                cachedCpuFreqPaths!!
            } else {
                val output = ShellExecutor.executeWithResult("ls /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq 2>/dev/null")
                val found = output.split("\n").filter { it.isNotBlank() }
                if (found.isNotEmpty()) cachedCpuFreqPaths = found
                found
            }
            
            if (paths.isEmpty()) return 0
            
            val freqsOutput = ShellExecutor.executeWithResult("cat ${paths.joinToString(" ")} 2>/dev/null")
            val freqs = freqsOutput.split("\n").mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }
            
            if (freqs.isEmpty()) return 0
            (freqs.average() / 1000).toInt()
        } catch (e: Exception) { 0 }
    }

    private var cachedGpuFreqPath: String? = null

    fun getGpuFreq(): Int {
        return try {
            // 1. Check cached path
            cachedGpuFreqPath?.let { path ->
                val freqRaw = ShellExecutor.executeWithResult("cat $path 2>/dev/null").trim().split(" ").first().toLongOrNull() ?: 0L
                if (freqRaw > 0) return normalizeFreq(freqRaw)
                cachedGpuFreqPath = null
            }

            // 2. Snapdragon/Adreno Standard
            val gpuPaths = listOf(
                "/sys/class/kgsl/kgsl-3d0/gpuclk",
                "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
                "/sys/class/devfreq/*gpu*/cur_freq",
                "/sys/class/devfreq/*.mali/cur_freq",
                "/sys/class/devfreq/*.kgsl-3d0/cur_freq",
                "/sys/kernel/ged/hal/current_freqency"
            )

            for (path in gpuPaths) {
                val freqRaw = ShellExecutor.executeWithResult("cat $path 2>/dev/null | head -n 1").trim().split(" ").first().toLongOrNull() ?: 0L
                if (freqRaw > 0) {
                    cachedGpuFreqPath = path
                    return normalizeFreq(freqRaw)
                }
            }
            0
        } catch (e: Exception) { 0 }
    }

    private fun normalizeFreq(freqRaw: Long): Int {
        return when {
            freqRaw > 1000000000 -> (freqRaw / 1000000).toInt()
            freqRaw > 1000000 -> (freqRaw / 1000000).toInt()
            freqRaw > 100000 -> (freqRaw / 1000).toInt()
            freqRaw > 5000 -> (freqRaw / 1000).toInt()
            else -> freqRaw.toInt()
        }
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

    data class GpuInfo(val usage: Int, val freq: Int)
    fun getCombinedGpuInfo(): GpuInfo {
        return try {
            GpuInfo(getGpuUsage(), getGpuFreq())
        } catch (e: Exception) {
            GpuInfo(0, 0)
        }
    }
}
