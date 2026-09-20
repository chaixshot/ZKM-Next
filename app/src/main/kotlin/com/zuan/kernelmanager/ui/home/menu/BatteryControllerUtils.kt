/*
 * Copyright (c) 2025 ZKM
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.zuan.kernelmanager.ui.home.menu

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.topjohnwu.superuser.Shell
import java.io.File
import kotlin.math.abs

data class BatteryInfo(
    val level: Int,
    val scale: Int,
    val percentage: Int,
    val status: String,
    val health: String,
    val temperature: Float,
    val voltage: Int,
    val currentNow: Int,
    val currentAverage: Int,
    val technology: String,
    val isCharging: Boolean,
    val chargingType: String,
    val cycleCount: Int
)

data class ChargingStats(
    val maxCurrent: Int,
    val maxVoltage: Int,
    val chargingPower: Float,
    val estimatedTimeRemaining: Long
)

object BatteryControllerUtils {

    // --- CONSTANTS ---
    private const val BATTERY_PATH = "/sys/class/power_supply/battery"
    private const val USB_PATH = "/sys/class/power_supply/usb"
    const val THERMAL_SCONFIG = "/sys/class/thermal/thermal_message/sconfig"
    const val BYPASS_CHARGING_PATH = "/sys/class/power_supply/battery/input_suspend"

    // --- SCRIPTS ---
    private const val SCRIPT_DISABLE_CHARGING = """
        echo "1" > /sys/class/power_supply/battery/batt_slate_mode
        echo "1" > /sys/class/power_supply/battery/battery_input_suspend
        echo "1" > /sys/class/power_supply/battery/input_suspend
        echo "1" > /sys/class/power_supply/main/input_suspend
        echo "1" > /sys/class/power_supply/battery/bd_trickle_cnt
        echo "0" > /sys/class/power_supply/battery/device/Charging_Enable
        echo "0" > /sys/class/power_supply/battery/charging_enabled
        echo "0" > /sys/class/power_supply/main/charging_enabled
        echo "1" > /sys/class/power_supply/battery/op_disable_charge
        echo "1" > /sys/class/power_supply/battery/store_mode
        echo "1" > /sys/class/power_supply/battery/test_mode
        echo "1" > /sys/class/power_supply/battery/battery_ext/smart_charging_interruption
        echo "0" > /sys/class/power_supply/battery/siop_level
        echo "0" > /sys/class/power_supply/battery/battery_charging_enabled
        echo "0" > /sys/class/power_supply/battery/mmi_charging_enable
        echo "1" > /sys/class/power_supply/battery/stop_charging_enable
        echo "0" > /sys/class/hw_power/charger/charge_data/enable_charger
        echo "1" > /sys/class/qcom-battery/input_suspend
        echo "1" > /sys/devices/platform/charger/tran_aichg_disable_charger
        echo "1" > /sys/devices/platform/charger/bypass_charger
        echo "0" > /sys/devices/platform/huawei_charger/enable_charger
        echo "1" > /sys/devices/platform/lge-unified-nodes/charging_completed
        echo "0" > /sys/devices/platform/lge-unified-nodes/charging_enable
        echo "1" > /sys/devices/platform/mt-battery/disable_charger
        echo "1" > /sys/devices/platform/soc/soc:google,charger/charge_disable
        echo "1" > /sys/kernel/debug/google_charger/chg_suspend
        echo "1" > /sys/kernel/debug/google_charger/input_suspend
        echo "on" > /sys/kernel/nubia_charge/charger_bypass
        echo "0 1" > /proc/mtk_battery_cmd/current_cmd
        echo "0" > /sys/class/power_supply/battery/constant_charge_current_max
    """

    private const val SCRIPT_ENABLE_CHARGING = """
        echo "0" > /sys/class/power_supply/battery/batt_slate_mode
        echo "0" > /sys/class/power_supply/battery/battery_input_suspend
        echo "0" > /sys/class/power_supply/battery/input_suspend
        echo "0" > /sys/class/power_supply/main/input_suspend
        echo "0" > /sys/class/power_supply/battery/bd_trickle_cnt
        echo "1" > /sys/class/power_supply/battery/device/Charging_Enable
        echo "1" > /sys/class/power_supply/battery/charging_enabled
        echo "1" > /sys/class/power_supply/main/charging_enabled
        echo "0" > /sys/class/power_supply/battery/op_disable_charge
        echo "0" > /sys/class/power_supply/battery/store_mode
        echo "2" > /sys/class/power_supply/battery/test_mode
        echo "0" > /sys/class/power_supply/battery/battery_ext/smart_charging_interruption
        echo "100" > /sys/class/power_supply/battery/siop_level
        echo "1" > /sys/class/power_supply/battery/battery_charging_enabled
        echo "1" > /sys/class/power_supply/battery/mmi_charging_enable
        echo "0" > /sys/class/power_supply/battery/stop_charging_enable
        echo "1" > /sys/class/hw_power/charger/charge_data/enable_charger
        echo "0" > /sys/class/qcom-battery/input_suspend
        echo "0" > /sys/devices/platform/charger/tran_aichg_disable_charger
        echo "0" > /sys/devices/platform/charger/bypass_charger
        echo "1" > /sys/devices/platform/huawei_charger/enable_charger
        echo "0" > /sys/devices/platform/lge-unified-nodes/charging_completed
        echo "1" > /sys/devices/platform/lge-unified-nodes/charging_enable
        echo "0" > /sys/devices/platform/mt-battery/disable_charger
        echo "0" > /sys/devices/platform/soc/soc:google,charger/charge_disable
        echo "0" > /sys/kernel/debug/google_charger/chg_suspend
        echo "0" > /sys/kernel/debug/google_charger/input_suspend
        echo "off" > /sys/kernel/nubia_charge/charger_bypass
        echo "0 0" > /proc/mtk_battery_cmd/current_cmd
    """

    fun getBatteryInfo(context: Context): BatteryInfo {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        
        return if (batteryStatus != null) {
            val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percentage = (level * 100 / scale)
            
            val status = when (batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "Full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
                else -> "Unknown"
            }
            
            val health = when (batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failed"
                BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                else -> "Unknown"
            }
            
            val temp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
            val voltage = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            val technology = batteryStatus.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown"
            
            val isCharging = status == "Charging" || status == "Full"
            
            val chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val chargingType = when (chargePlug) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC Adapter"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> "None"
            }
            
            val cycleCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                batteryStatus.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, 0)
            } else {
                readSysFile("$BATTERY_PATH/cycle_count")?.toIntOrNull() ?: 0
            }
            
            // Logic mA yang lebih akurat dan aman (dijadikan absolut agar angkanya cantik di UI)
            val currentNowRaw = getBatteryCurrentNow()
            val currentNow = abs(currentNowRaw) 
            
            // FIX BUG -2147483mA: Filter nilai error Int.MIN_VALUE dari sensor Android
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            var currentAverageRaw = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            
            val currentAverage = if (currentAverageRaw == Int.MIN_VALUE || currentAverageRaw == Int.MIN_VALUE / 1000) {
                // Jika Android API gagal membaca Average, fallback baca dari kernel
                val kernelAvg = readSysFile("$BATTERY_PATH/current_avg")?.toIntOrNull()
                if (kernelAvg != null) abs(kernelAvg / 1000) else currentNow // Jika gagal total, samakan dengan currentNow
            } else {
                abs(currentAverageRaw / 1000)
            }
            
            BatteryInfo(
                level = level,
                scale = scale,
                percentage = percentage,
                status = status,
                health = health,
                temperature = temp,
                voltage = voltage, // Ini dalam mV
                currentNow = currentNow,
                currentAverage = currentAverage,
                technology = technology,
                isCharging = isCharging,
                chargingType = chargingType,
                cycleCount = cycleCount
            )
        } else {
            BatteryInfo(0, 0, 0, "Unknown", "Unknown", 0f, 0, 0, 0, "Unknown", false, "None", 0)
        }
    }

    private fun getBatteryCurrentNow(): Int {
        val paths = listOf(
            "$BATTERY_PATH/current_now",
            "/sys/class/power_supply/bms/current_now"
        )
        for (path in paths) {
            val result = readSysFile(path)?.trim()?.toIntOrNull()
            if (result != null && result != 0) {
                 return result / 1000 // Convert uA to mA
            }
        }
        return 0
    }

    fun getChargingStats(): ChargingStats {
        val maxCurrent = getChargingSpeed() * 1000
        val maxVoltage = readSysFile("$USB_PATH/voltage_max")?.toIntOrNull() ?: 0
        
        val currentNow = abs(getBatteryCurrentNow()) // dalam mA
        val voltageNowRaw = readSysFile("$BATTERY_PATH/voltage_now")?.toIntOrNull() ?: 0
        
        // FIX BUG DAYA (WATT): Normalisasi uV vs mV ke Volt
        val voltageVolts = if (voltageNowRaw > 100_000) {
            voltageNowRaw / 1_000_000f // uV to Volts
        } else {
            voltageNowRaw / 1000f // mV to Volts
        }
        val currentAmps = currentNow / 1000f // mA to Amps
        
        val power = if (currentNow != 0 && voltageVolts > 0) {
            currentAmps * voltageVolts
        } else 0f
        
        return ChargingStats(
            maxCurrent = maxCurrent / 1000,
            maxVoltage = maxVoltage / 1000,
            chargingPower = power,
            estimatedTimeRemaining = 0L
        )
    }

    // --- FITUR UTAMA ---

    private val chargingSpeedPaths = listOf(
        "/sys/class/power_supply/battery/constant_charge_current_max",
        "/sys/class/power_supply/main/constant_charge_current_max",
        "/sys/class/power_supply/usb/current_max",
        "/sys/class/power_supply/usb/hw_current_max",
        "/sys/class/power_supply/usb/pd_current_max",
        "/sys/class/power_supply/main/current_max",
        "/sys/class/power_supply/battery/current_max",
        "/sys/class/qcom-battery/restricted_current",
        "/sys/class/power_supply/pc_port/current_max",
        "/sys/class/power_supply/battery/input_current_limit",
        "/sys/class/power_supply/usb/input_current_limit"
    )

    fun setChargingSpeed(mA: Int): Boolean {
        val uA = mA * 1000
        var success = false
        for (path in chargingSpeedPaths) {
            if (Shell.cmd("test -e $path").exec().isSuccess) {
                if (Shell.cmd("echo $uA > $path").exec().isSuccess) {
                    success = true
                }
            }
        }
        return success
    }

    fun getChargingSpeed(): Int {
        for (path in chargingSpeedPaths) {
            val v = readSysFile(path)?.toIntOrNull()
            if (v != null && v > 0) {
                return v / 1000
            }
        }
        return 2000 // Default fallback
    }

    fun setChargingEnabled(enabled: Boolean): Boolean {
        val script = if (enabled) SCRIPT_ENABLE_CHARGING else SCRIPT_DISABLE_CHARGING
        Shell.cmd(script).exec()
        return true 
    }

    fun getChargingEnabledStatus(): Boolean {
        val chargingEnabledNodes = listOf(
            "/sys/class/power_supply/battery/charging_enabled",
            "/sys/class/power_supply/main/charging_enabled",
            "/sys/class/power_supply/battery/device/Charging_Enable"
        )
        for (node in chargingEnabledNodes) {
            val v = readSysFile(node)
            if (v == "0") return false
            if (v == "1") return true
        }

        val inputSuspendNodes = listOf(
            "/sys/class/power_supply/battery/input_suspend",
            "/sys/class/power_supply/battery/battery_input_suspend",
            "/sys/class/power_supply/main/input_suspend",
            "/sys/class/qcom-battery/input_suspend"
        )
        for (node in inputSuspendNodes) {
            val v = readSysFile(node)
            if (v == "1") return false
            if (v == "0") return true
        }
        
        return true
    }

    fun isUsbPowerConnected(): Boolean {
        // We check for "present" or "online" in any power supply that isn't the battery.
        // On many devices, "present" stays 1 even if charging is suspended, as long as the cable is there.
        val psPath = "/sys/class/power_supply"
        val nodes = runCatching { Shell.cmd("ls $psPath").exec().out }.getOrElse { emptyList() }
        
        for (node in nodes) {
            if (node == "battery" || node == "bms" || node == "main") {
                // Main is often a virtual node that follows battery/usb, so we check it carefully or skip
                continue 
            }
            
            val present = readSysFile("$psPath/$node/present")
            val online = readSysFile("$psPath/$node/online")
            
            if (present == "1" || online == "1") return true
        }

        // Fallback for some Xiaomi devices: check the "main" or "usb" node specifically if not found above
        if (readSysFile("$psPath/usb/present") == "1") return true
        if (readSysFile("$psPath/ac/present") == "1") return true
        
        return false
    }

    fun setChargingLimit(percentage: Int): Boolean {
        val paths = listOf(
            "$BATTERY_PATH/charge_control_limit",
            "$BATTERY_PATH/charge_stop_threshold",
            "$BATTERY_PATH/charge_limit",
            "/sys/devices/platform/charger/charging_limit",
            "/sys/devices/platform/soc/soc:google,charger/charge_stop_threshold",
            "/sys/class/hw_power/charger/charge_data/charge_limit"
        )
        for (path in paths) {
            if (Shell.cmd("test -e $path").exec().isSuccess) {
                val result = Shell.cmd("echo $percentage > $path").exec()
                if (result.isSuccess) return true
            }
        }
        return false
    }

    fun getChargingLimit(): Int {
        val paths = listOf(
            "$BATTERY_PATH/charge_control_limit",
            "$BATTERY_PATH/charge_stop_threshold",
            "$BATTERY_PATH/charge_limit",
            "/sys/devices/platform/charger/charging_limit",
            "/sys/devices/platform/soc/soc:google,charger/charge_stop_threshold",
            "/sys/class/hw_power/charger/charge_data/charge_limit"
        )
        for (path in paths) {
            val value = readSysFile(path)
            if (value != null && value.toIntOrNull() != null) {
                return value.toInt()
            }
        }
        return 100
    }

    fun isChargingLimitSupported(): Boolean {
        val paths = listOf(
            "$BATTERY_PATH/charge_control_limit",
            "$BATTERY_PATH/charge_stop_threshold",
            "$BATTERY_PATH/charge_limit",
            "/sys/devices/platform/charger/charging_limit",
            "/sys/devices/platform/soc/soc:google,charger/charge_stop_threshold",
            "/sys/class/hw_power/charger/charge_data/charge_limit"
        )
        return paths.any { Shell.cmd("test -e $it").exec().isSuccess }
    }

    fun setFastCharge(enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"
        val paths = listOf(
            "/sys/kernel/fast_charge/force_fast_charge",
            "/sys/class/power_supply/battery/fastchg",
            "/sys/class/power_supply/usb/fastchg"
        )
        for (path in paths) {
            if (File(path).exists()) {
                val result = Shell.cmd("echo $value > $path").exec()
                if (result.isSuccess) return true
            }
        }
        return false
    }
    
    fun setBypassCharging(enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"
        val result = Shell.cmd("echo $value > $BYPASS_CHARGING_PATH").exec()
        return result.isSuccess
    }
    
    fun isBypassSupported(): Boolean {
        return File(BYPASS_CHARGING_PATH).exists()
    }

    fun isFastChargeSupported(): Boolean {
        val paths = listOf(
            "/sys/kernel/fast_charge/force_fast_charge",
            "/sys/class/power_supply/battery/fastchg",
            "/sys/class/power_supply/usb/fastchg"
        )
        return paths.any { File(it).exists() }
    }

    fun getFastChargeStatus(): Boolean {
        val paths = listOf(
            "/sys/kernel/fast_charge/force_fast_charge",
            "/sys/class/power_supply/battery/fastchg",
            "/sys/class/power_supply/usb/fastchg"
        )
        for (path in paths) {
            val value = readSysFile(path)
            if (value == "1") return true
        }
        return false
    }
    
    fun getBypassStatus(): Boolean {
        return readSysFile(BYPASS_CHARGING_PATH) == "1"
    }

    fun setBatterySaver(enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"
        val result = Shell.cmd("settings put global low_power $value").exec()
        return result.isSuccess
    }

    fun isBatterySaverEnabled(): Boolean {
        val result = Shell.cmd("settings get global low_power").exec()
        return result.isSuccess && result.out.joinToString("").trim() == "1"
    }

    // --- THERMAL UTILS ---
    fun hasThermalSconfig(): Boolean = File(THERMAL_SCONFIG).exists()

    fun getThermalSconfig(): String = readSysFile(THERMAL_SCONFIG) ?: "0"

    fun setThermalSconfig(value: String): Boolean {
        Shell.cmd("chmod 644 $THERMAL_SCONFIG").exec()
        val res = Shell.cmd("echo $value > $THERMAL_SCONFIG").exec()
        Shell.cmd("chmod 444 $THERMAL_SCONFIG").exec()
        return res.isSuccess
    }

    // --- SMART CHARGE CHECK ---
    fun isSmartChargeSupported(): Boolean {
        val testPaths = listOf(
            "/sys/class/power_supply/battery/charging_enabled",
            "/sys/class/power_supply/battery/battery_charging_enabled",
            "/sys/class/power_supply/battery/input_suspend",
            "/sys/class/power_supply/battery/battery_input_suspend",
            "/sys/class/power_supply/main/charging_enabled",
            "/sys/class/power_supply/main/input_suspend",
            "/sys/class/qcom-battery/input_suspend",
            "/sys/class/power_supply/battery/batt_slate_mode",
            "/sys/devices/platform/charger/bypass_charger",
            "/sys/class/power_supply/battery/device/Charging_Enable",
            "/sys/class/hw_power/charger/charge_data/enable_charger"
        )
        return testPaths.any { path ->
            Shell.cmd("test -e $path").exec().isSuccess
        }
    }

    // --- HELPERS ---
    private fun normalizeCapacity(valueStr: String?): Double {
        val value = valueStr?.trim()?.toDoubleOrNull() ?: 0.0
        return if (value > 100_000) value / 1000.0 else value
    }

    fun getBatteryCapacity(): Int {
        val capacity = readSysFile("$BATTERY_PATH/charge_full")
        val designCapacity = readSysFile("$BATTERY_PATH/charge_full_design")
        
        return if (capacity != null && designCapacity != null) {
            val cap = normalizeCapacity(capacity)
            val design = normalizeCapacity(designCapacity)
            if (design > 0) ((cap / design) * 100).toInt() else 0
        } else 100
    }

    private fun readSysFile(path: String): String? {
        return try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                file.readText().trim()
            } else {
                val result = Shell.cmd("cat $path 2>/dev/null").exec()
                if (result.isSuccess && result.out.isNotEmpty()) result.out[0].trim() else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
