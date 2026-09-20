/*
 * Copyright (c) 2025 ZKM
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.zuan.kernelmanager.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.edit
import com.zuan.kernelmanager.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// [UPDATE] Enums untuk Navigasi
enum class NavStyle { LIQUID_CAPSULE, CLASSIC_MATERIAL, MODERN_TABS }
enum class NavLabelState { ALWAYS_SHOW, ONLY_SELECTED, ALWAYS_HIDE }

// [UPDATE] Enums untuk Weather Effects
enum class WeatherEffect { NONE, FOG, RAIN, SNOW, SUN_RAYS }

// [UPDATE] UiConfig sekarang punya properti isVideo dan weather effects
 data class UiConfig(
    val bgType: BgType = BgType.SYSTEM,
    val solidColor: Int = 0xFF1A1A1A.toInt(),
    val expressiveThemeId: Int = 0,
    val customImageUri: String? = null,
    val isBgBlur: Boolean = false,
    val bgContrast: Float = 0f,
    val cardDarkness: Float = 0.15f,
    val isHazeEnabled: Boolean = true,
    val isVideo: Boolean = false,
    // [BARU] Weather Effects
    val weatherEffect: WeatherEffect = WeatherEffect.NONE,
    val weatherIntensity: Float = 0.5f,
    // [BARU] Background adjustments
    val bgSaturation: Float = 1f,
    val blurStrength: Float = 20f,
    // [BARU] Custom Colors
    val isCustomColor: Boolean = false,
    val customPrimaryColor: Int = Color(0xFF4A6595).toArgb(),
    val customSecondaryColor: Int = Color(0xFF586275).toArgb(),
    val customTertiaryColor: Int = Color(0xFF5E4B8B).toArgb()
)

// Enum Background
enum class BgType { SYSTEM, PRESET_SOLID, EXPRESSIVE, GALLERY }

// Data Class Warna
 data class AppThemeColor(
    val name: String,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color
)

// [UPDATE] Expanded color palette - 16 warna + dynamic
val availableColors = listOf(
    // Original colors
    AppThemeColor("Blue", Color(0xFF4A6595), Color(0xFF586275), Color(0xFF5E4B8B)),
    AppThemeColor("Green", Color(0xFF556B2F), Color(0xFF4F6A55), Color(0xFF356A4F)),
    AppThemeColor("Purple", Color(0xFF7B52AB), Color(0xFF685675), Color(0xFF8B4C70)),
    AppThemeColor("Orange", Color(0xFFB36B36), Color(0xFF8B6B55), Color(0xFF9C551F)),
    AppThemeColor("Pink", Color(0xFFBC4C73), Color(0xFF9C4C66), Color(0xFF8B4C66)),
    AppThemeColor("Gray", Color(0xFF6B6B6B), Color(0xFF555555), Color(0xFF3E3E3E)),
    AppThemeColor("Yellow", Color(0xFF9C8B1F), Color(0xFF7B6B1F), Color(0xFF6B551F)),
    // [BARU] Additional colors
    AppThemeColor("Light Green", Color(0xFF7CB342), Color(0xFF689F38), Color(0xFF558B2F)),
    AppThemeColor("Dark Green", Color(0xFF1B5E20), Color(0xFF2E7D32), Color(0xFF388E3C)),
    AppThemeColor("Teal", Color(0xFF00897B), Color(0xFF00796B), Color(0xFF00695C)),
    AppThemeColor("Cyan", Color(0xFF00ACC1), Color(0xFF0097A7), Color(0xFF00838F)),
    AppThemeColor("Light Blue", Color(0xFF29B6F6), Color(0xFF03A9F4), Color(0xFF0288D1)),
    AppThemeColor("Indigo", Color(0xFF5C6BC0), Color(0xFF3F51B5), Color(0xFF3949AB)),
    AppThemeColor("Deep Purple", Color(0xFF7E57C2), Color(0xFF673AB7), Color(0xFF5E35B1)),
    AppThemeColor("Magenta", Color(0xFFD81B60), Color(0xFFC2185B), Color(0xFFAD1457)),
    AppThemeColor("Rose", Color(0xFFF06292), Color(0xFFEC407A), Color(0xFFE91E63)),
    AppThemeColor("Coral", Color(0xFFFF7043), Color(0xFFFF5722), Color(0xFFF4511E)),
    AppThemeColor("Amber", Color(0xFFFFB300), Color(0xFFFFA000), Color(0xFFFF8F00)),
    AppThemeColor("Lime", Color(0xFFAFB42B), Color(0xFF9E9D24), Color(0xFF827717)),
    AppThemeColor("Mint", Color(0xFF26A69A), Color(0xFF4DB6AC), Color(0xFF80CBC4)),
    AppThemeColor("Navy", Color(0xFF1A237E), Color(0xFF283593), Color(0xFF303F9F)),
    AppThemeColor("Slate", Color(0xFF455A64), Color(0xFF546E7A), Color(0xFF607D8B)),
    AppThemeColor("Charcoal", Color(0xFF37474F), Color(0xFF455A64), Color(0xFF263238)),
    AppThemeColor("Burgundy", Color(0xFF880E4F), Color(0xFFAD1457), Color(0xFFC2185B)),
    AppThemeColor("Peach", Color(0xFFFFAB91), Color(0xFFFF8A65), Color(0xFFFF7043))
)

class SettingsPreference(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)

    // --- STATES ---
    private val _themeMode = MutableStateFlow(getThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _themeColorName = MutableStateFlow(getThemeColorName())
    val themeColorName: StateFlow<String> = _themeColorName.asStateFlow()

    private val _isDynamicColor = MutableStateFlow(getDynamicColorEnabled())
    val isDynamicColor: StateFlow<Boolean> = _isDynamicColor.asStateFlow()

    private val _pollingInterval = MutableStateFlow(getPollingInterval())
    val pollingInterval: StateFlow<Long> = _pollingInterval.asStateFlow()

    private val _currentLanguageCode = MutableStateFlow(getLanguageCode())
    val currentLanguageCode: StateFlow<String> = _currentLanguageCode.asStateFlow()

    private val _appDpi = MutableStateFlow(getAppDpi())
    val appDpi: StateFlow<Int> = _appDpi.asStateFlow()

    // --- VISUAL STATES ---
    private val _isCustomBackground = MutableStateFlow(getCustomBackgroundEnabled())
    val isCustomBackground: StateFlow<Boolean> = _isCustomBackground.asStateFlow()

    private val _backgroundImageUri = MutableStateFlow(getBackgroundImageUri())
    val backgroundImageUri: StateFlow<String?> = _backgroundImageUri.asStateFlow()

    // [BARU] State Video Flag
    private val _isVideoWallpaper = MutableStateFlow(getIsVideoWallpaper())
    val isVideoWallpaper: StateFlow<Boolean> = _isVideoWallpaper.asStateFlow()

    private val _navBarTransparency = MutableStateFlow(getNavBarTransparency())
    val navBarTransparency: StateFlow<Float> = _navBarTransparency.asStateFlow()

    private val _cardDarkness = MutableStateFlow(getCardDarkness())
    val cardDarkness: StateFlow<Float> = _cardDarkness.asStateFlow()

    private val _isBgBlur = MutableStateFlow(getBgBlurEnabled())
    val isBgBlur: StateFlow<Boolean> = _isBgBlur.asStateFlow()

    private val _bgContrast = MutableStateFlow(getBgContrast())
    val bgContrast: StateFlow<Float> = _bgContrast.asStateFlow()
    
    private val _isHazeEnabled = MutableStateFlow(getHazeEnabled())
    val isHazeEnabled: StateFlow<Boolean> = _isHazeEnabled.asStateFlow()

    private val _bgType = MutableStateFlow(getBgType())
    val bgType: StateFlow<BgType> = _bgType.asStateFlow()

    private val _solidColor = MutableStateFlow(getSolidColor())
    val solidColor: StateFlow<Int> = _solidColor.asStateFlow()

    private val _expressiveThemeId = MutableStateFlow(getExpressiveThemeId())
    val expressiveThemeId: StateFlow<Int> = _expressiveThemeId.asStateFlow()

    // --- NAVIGATION STATES ---
    private val _navStyle = MutableStateFlow(getNavStyle())
    val navStyle: StateFlow<NavStyle> = _navStyle.asStateFlow()

    private val _navLabelState = MutableStateFlow(getNavLabelState())
    val navLabelState: StateFlow<NavLabelState> = _navLabelState.asStateFlow()

    // [BARU] WEATHER EFFECT STATES ---
    private val _weatherEffect = MutableStateFlow(getWeatherEffect())
    val weatherEffect: StateFlow<WeatherEffect> = _weatherEffect.asStateFlow()

    private val _weatherIntensity = MutableStateFlow(getWeatherIntensity())
    val weatherIntensity: StateFlow<Float> = _weatherIntensity.asStateFlow()

    // [BARU] BACKGROUND ADJUSTMENT STATES ---
    private val _bgSaturation = MutableStateFlow(getBgSaturation())
    val bgSaturation: StateFlow<Float> = _bgSaturation.asStateFlow()

    private val _blurStrength = MutableStateFlow(getBlurStrength())
    val blurStrength: StateFlow<Float> = _blurStrength.asStateFlow()

    // [BARU] CUSTOM COLOR STATES ---
    private val _isCustomColor = MutableStateFlow(getIsCustomColor())
    val isCustomColor: StateFlow<Boolean> = _isCustomColor.asStateFlow()

    private val _customPrimaryColor = MutableStateFlow(getCustomPrimaryColor())
    val customPrimaryColor: StateFlow<Int> = _customPrimaryColor.asStateFlow()

    private val _customSecondaryColor = MutableStateFlow(getCustomSecondaryColor())
    val customSecondaryColor: StateFlow<Int> = _customSecondaryColor.asStateFlow()

    private val _customTertiaryColor = MutableStateFlow(getCustomTertiaryColor())
    val customTertiaryColor: StateFlow<Int> = _customTertiaryColor.asStateFlow()

    // [BARU] INTRO STATES ---
    private val _hasCompletedIntro = MutableStateFlow(getHasCompletedIntro())
    val hasCompletedIntro: StateFlow<Boolean> = _hasCompletedIntro.asStateFlow()
    
    private val _acceptedTerms = MutableStateFlow(getAcceptedTerms())
    val acceptedTerms: StateFlow<Boolean> = _acceptedTerms.asStateFlow()
    
    private val _acceptedRisk = MutableStateFlow(getAcceptedRisk())
    val acceptedRisk: StateFlow<Boolean> = _acceptedRisk.asStateFlow()

    private val _cpuGpuProfilesJson = MutableStateFlow(getCpuGpuProfilesJson())
    val cpuGpuProfilesJson: StateFlow<String> = _cpuGpuProfilesJson.asStateFlow()

    private val _selectedCpuProfileName = MutableStateFlow(getSelectedCpuProfileName())
    val selectedCpuProfileName: StateFlow<String?> = _selectedCpuProfileName.asStateFlow()

    private val _schedProfilesJson = MutableStateFlow(getSchedProfilesJson())
    val schedProfilesJson: StateFlow<String> = _schedProfilesJson.asStateFlow()

    private val _selectedSchedProfileName = MutableStateFlow(getSelectedSchedProfileName())
    val selectedSchedProfileName: StateFlow<String?> = _selectedSchedProfileName.asStateFlow()

    private val _memProfilesJson = MutableStateFlow(getMemProfilesJson())
    val memProfilesJson: StateFlow<String> = _memProfilesJson.asStateFlow()

    private val _selectedMemProfileName = MutableStateFlow(getSelectedMemProfileName())
    val selectedMemProfileName: StateFlow<String?> = _selectedMemProfileName.asStateFlow()

    private val _netProfilesJson = MutableStateFlow(getNetProfilesJson())
    val netProfilesJson: StateFlow<String> = _netProfilesJson.asStateFlow()

    private val _selectedNetProfileName = MutableStateFlow(getSelectedNetProfileName())
    val selectedNetProfileName: StateFlow<String?> = _selectedNetProfileName.asStateFlow()


    private val _individualCpuSettingsJson = MutableStateFlow(getIndividualCpuSettingsJson())
    val individualCpuSettingsJson: StateFlow<String> = _individualCpuSettingsJson.asStateFlow()

    private val _batteryMonitorEnabled = MutableStateFlow(getBatteryMonitorEnabled())
    val batteryMonitorEnabled: StateFlow<Boolean> = _batteryMonitorEnabled.asStateFlow()

    private val _smartCutoffEnabled = MutableStateFlow(getSmartCutoffEnabled())
    val smartCutoffEnabled: StateFlow<Boolean> = _smartCutoffEnabled.asStateFlow()

    private val _smartCutoffLimit = MutableStateFlow(getSmartCutoffLimit())
    val smartCutoffLimit: StateFlow<Float> = _smartCutoffLimit.asStateFlow()

    private val _chargingLimit = MutableStateFlow(getChargingLimit())
    val chargingLimit: StateFlow<Int> = _chargingLimit.asStateFlow()

    private val _fastChargeEnabled = MutableStateFlow(getFastChargeEnabled())
    val fastChargeEnabled: StateFlow<Boolean> = _fastChargeEnabled.asStateFlow()

    private val _bypassChargingEnabled = MutableStateFlow(getBypassChargingEnabled())
    val bypassChargingEnabled: StateFlow<Boolean> = _bypassChargingEnabled.asStateFlow()

    private val _chargingSpeed = MutableStateFlow(getChargingSpeed())
    val chargingSpeed: StateFlow<Int> = _chargingSpeed.asStateFlow()

    private val _batterySaverEnabled = MutableStateFlow(getBatterySaverEnabled())
    val batterySaverEnabled: StateFlow<Boolean> = _batterySaverEnabled.asStateFlow()

    private val _thermalSconfig = MutableStateFlow(getThermalSconfig())
    val thermalSconfig: StateFlow<String> = _thermalSconfig.asStateFlow()

    private val _applyOnBoot = MutableStateFlow(getApplyOnBoot())
    val applyOnBoot: StateFlow<Boolean> = _applyOnBoot.asStateFlow()

    private val _isAlternateIcon = MutableStateFlow(getAlternateIconEnabled())
    val isAlternateIcon: StateFlow<Boolean> = _isAlternateIcon.asStateFlow()

    // --- FPS OVERLAY STATES ---
    private val _fpsStyle = MutableStateFlow(getFpsStyle())
    val fpsStyle: StateFlow<Int> = _fpsStyle.asStateFlow()

    private val _fpsOrientation = MutableStateFlow(getFpsOrientation())
    val fpsOrientation: StateFlow<Int> = _fpsOrientation.asStateFlow()

    private val _fpsColor = MutableStateFlow(getFpsColor())
    val fpsColor: StateFlow<String> = _fpsColor.asStateFlow()

    private val _fpsSize = MutableStateFlow(getFpsSize())
    val fpsSize: StateFlow<Float> = _fpsSize.asStateFlow()

    private val _fpsWidthScale = MutableStateFlow(getFpsWidthScale())
    val fpsWidthScale: StateFlow<Float> = _fpsWidthScale.asStateFlow()

    private val _fpsAlpha = MutableStateFlow(getFpsAlpha())
    val fpsAlpha: StateFlow<Float> = _fpsAlpha.asStateFlow()

    private val _fpsShowFps = MutableStateFlow(getFpsShowFps())
    val fpsShowFps: StateFlow<Boolean> = _fpsShowFps.asStateFlow()

    private val _fpsShowCpu = MutableStateFlow(getFpsShowCpu())
    val fpsShowCpu: StateFlow<Boolean> = _fpsShowCpu.asStateFlow()

    private val _fpsShowWatts = MutableStateFlow(getFpsShowWatts())
    val fpsShowWatts: StateFlow<Boolean> = _fpsShowWatts.asStateFlow()

    private val _fpsShowTemp = MutableStateFlow(getFpsShowTemp())
    val fpsShowTemp: StateFlow<Boolean> = _fpsShowTemp.asStateFlow()

    private val _fpsShowRam = MutableStateFlow(getFpsShowRam())
    val fpsShowRam: StateFlow<Boolean> = _fpsShowRam.asStateFlow()

    private val _fpsShowRender = MutableStateFlow(getFpsShowRender())
    val fpsShowRender: StateFlow<Boolean> = _fpsShowRender.asStateFlow()

    private val _fpsShowGpuUsage = MutableStateFlow(getFpsShowGpuUsage())
    val fpsShowGpuUsage: StateFlow<Boolean> = _fpsShowGpuUsage.asStateFlow()

    private val _fpsShowCpuTemp = MutableStateFlow(getFpsShowCpuTemp())
    val fpsShowCpuTemp: StateFlow<Boolean> = _fpsShowCpuTemp.asStateFlow()

    private val _fpsShowCpuFreq = MutableStateFlow(getFpsShowCpuFreq())
    val fpsShowCpuFreq: StateFlow<Boolean> = _fpsShowCpuFreq.asStateFlow()

    private val _fpsShowGpuFreq = MutableStateFlow(getFpsShowGpuFreq())
    val fpsShowGpuFreq: StateFlow<Boolean> = _fpsShowGpuFreq.asStateFlow()

    private val _fpsShowGpuTemp = MutableStateFlow(getFpsShowGpuTemp())
    val fpsShowGpuTemp: StateFlow<Boolean> = _fpsShowGpuTemp.asStateFlow()

    private val _fpsPosX = MutableStateFlow(getFpsPosX())
    val fpsPosX: StateFlow<Int> = _fpsPosX.asStateFlow()

    private val _fpsPosY = MutableStateFlow(getFpsPosY())
    val fpsPosY: StateFlow<Int> = _fpsPosY.asStateFlow()

    companion object {
        private const val THEME_KEY = "theme_mode"
        private const val THEME_COLOR_KEY = "theme_color_name"
        private const val DYNAMIC_COLOR_KEY = "dynamic_color_enabled"
        private const val POLLING_INTERVAL_KEY = "soc_polling_interval"
        private const val LANGUAGE_KEY = "app_language_code"
        private const val DPI_KEY = "app_custom_dpi"
        
        private const val CUSTOM_BG_KEY = "custom_bg_enabled"
        private const val BG_URI_KEY = "custom_bg_uri"
        private const val IS_VIDEO_KEY = "is_video_wallpaper_flag"
        
        private const val NAVBAR_TRANS_KEY = "navbar_transparency"
        private const val CARD_DARKNESS_KEY = "card_darkness"
        
        private const val BG_BLUR_KEY = "bg_blur_enabled"
        private const val BG_CONTRAST_KEY = "bg_contrast_value"
        private const val HAZE_ENABLED_KEY = "haze_ui_enabled"

        private const val BG_TYPE_KEY = "bg_type_selection"
        private const val SOLID_COLOR_KEY = "bg_solid_color_value"
        private const val EXPRESSIVE_ID_KEY = "bg_expressive_theme_id"

        private const val NAV_STYLE_KEY = "nav_style_selection"
        private const val NAV_LABEL_KEY = "nav_label_behavior"

        // [BARU] Weather Effect Keys
        private const val WEATHER_EFFECT_KEY = "weather_effect_type"
        private const val WEATHER_INTENSITY_KEY = "weather_effect_intensity"

        // [BARU] Background Adjustment Keys
        private const val BG_SATURATION_KEY = "bg_saturation_value"
        private const val BLUR_STRENGTH_KEY = "blur_strength_value"

        // [BARU] Custom Color Keys
        private const val IS_CUSTOM_COLOR_KEY = "is_custom_color_enabled"
        private const val CUSTOM_PRIMARY_KEY = "custom_primary_color"
        private const val CUSTOM_SECONDARY_KEY = "custom_secondary_color"
        private const val CUSTOM_TERTIARY_KEY = "custom_tertiary_color"

        // [BARU] Intro Keys
        private const val HAS_COMPLETED_INTRO_KEY = "has_completed_intro"
        private const val ACCEPTED_TERMS_KEY = "accepted_terms"
        private const val ACCEPTED_RISK_KEY = "accepted_risk"
        
        private const val CPU_GPU_PROFILES_KEY = "cpu_gpu_profiles_json"
        private const val SELECTED_CPU_PROFILE_NAME_KEY = "selected_cpu_profile_name"
        private const val SCHED_PROFILES_KEY = "sched_profiles_json"
        private const val SELECTED_SCHED_PROFILE_NAME_KEY = "selected_sched_profile_name"
        private const val MEM_PROFILES_KEY = "mem_profiles_json"
        private const val SELECTED_MEM_PROFILE_NAME_KEY = "selected_mem_profile_name"
        private const val NET_PROFILES_KEY = "net_profiles_json"
        private const val SELECTED_NET_PROFILE_NAME_KEY = "selected_net_profile_name"
        
        private const val INDIVIDUAL_CPU_SETTINGS_KEY = "individual_cpu_settings_json"

        private const val BATTERY_MONITOR_KEY = "battery_monitor_enabled"
        private const val SMART_CUTOFF_KEY = "smart_cutoff_enabled"
        private const val SMART_CUTOFF_LIMIT_KEY = "smart_cutoff_limit_val"
        private const val CHARGING_LIMIT_KEY = "battery_charging_limit_val"
        private const val FAST_CHARGE_KEY = "fast_charge_enabled_state"
        private const val BYPASS_CHARGING_KEY = "bypass_charging_enabled_state"
        private const val CHARGING_SPEED_KEY = "charging_speed_val"
        private const val BATTERY_SAVER_KEY = "battery_saver_enabled_state"
        private const val THERMAL_SCONFIG_KEY = "thermal_sconfig_val"
        private const val APPLY_ON_BOOT_KEY = "apply_on_boot_master"
        private const val ALT_ICON_KEY = "alternate_app_icon_enabled"

        // [BARU] FPS Overlay Keys
        private const val FPS_STYLE_KEY = "fps_overlay_style"
        private const val FPS_ORIENTATION_KEY = "fps_overlay_orientation"
        private const val FPS_COLOR_KEY = "fps_overlay_color"
        private const val FPS_SIZE_KEY = "fps_overlay_size"
        private const val FPS_WIDTH_SCALE_KEY = "fps_overlay_width_scale"
        private const val FPS_ALPHA_KEY = "fps_overlay_alpha"
        private const val FPS_SHOW_FPS_KEY = "fps_show_fps"
        private const val FPS_SHOW_CPU_KEY = "fps_show_cpu"
        private const val FPS_SHOW_WATTS_KEY = "fps_show_watts"
        private const val FPS_SHOW_TEMP_KEY = "fps_show_temp"
        private const val FPS_SHOW_RAM_KEY = "fps_show_ram"
        private const val FPS_SHOW_RENDER_KEY = "fps_show_render"
        private const val FPS_SHOW_GPU_USAGE_KEY = "fps_show_gpu_usage"
        private const val FPS_SHOW_CPU_TEMP_KEY = "fps_show_cpu_temp"
        private const val FPS_SHOW_CPU_FREQ_KEY = "fps_show_cpu_freq"
        private const val FPS_SHOW_GPU_FREQ_KEY = "fps_show_gpu_freq"
        private const val FPS_SHOW_GPU_TEMP_KEY = "fps_show_gpu_temp"
        private const val FPS_POS_X_KEY = "fps_overlay_pos_x"
        private const val FPS_POS_Y_KEY = "fps_overlay_pos_y"

        private const val DEFAULT_POLLING_INTERVAL = 3000L
        private const val DEFAULT_DPI = 0
        
        @Volatile
        private var INSTANCE: SettingsPreference? = null

        fun getInstance(context: Context): SettingsPreference {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsPreference(context).also { INSTANCE = it }
            }
        }
    }

    // --- Getters/Setters ---
    fun setThemeMode(mode: ThemeMode) { prefs.edit { putString(THEME_KEY, mode.name) }; _themeMode.value = mode }
    private fun getThemeMode(): ThemeMode { return try { ThemeMode.valueOf(prefs.getString(THEME_KEY, ThemeMode.SYSTEM_DEFAULT.name)!!) } catch (e: Exception) { ThemeMode.SYSTEM_DEFAULT } }

    fun setThemeColor(name: String) { prefs.edit { putString(THEME_COLOR_KEY, name) }; _themeColorName.value = name }
    private fun getThemeColorName(): String = prefs.getString(THEME_COLOR_KEY, "Green") ?: "Green"

    fun setDynamicColorEnabled(enabled: Boolean) { prefs.edit { putBoolean(DYNAMIC_COLOR_KEY, enabled) }; _isDynamicColor.value = enabled }
    private fun getDynamicColorEnabled(): Boolean = prefs.getBoolean(DYNAMIC_COLOR_KEY, false)

    fun setPollingInterval(interval: Long) { prefs.edit { putLong(POLLING_INTERVAL_KEY, interval) }; _pollingInterval.value = interval }
    private fun getPollingInterval(): Long = prefs.getLong(POLLING_INTERVAL_KEY, DEFAULT_POLLING_INTERVAL)

    fun setLanguageCode(code: String) { prefs.edit { putString(LANGUAGE_KEY, code) }; _currentLanguageCode.value = code }
    private fun getLanguageCode(): String = prefs.getString(LANGUAGE_KEY, "system") ?: "system"

    fun setAppDpi(dpi: Int) { prefs.edit { putInt(DPI_KEY, dpi) }; _appDpi.value = dpi }
    private fun getAppDpi(): Int = prefs.getInt(DPI_KEY, DEFAULT_DPI)

    fun setCustomBackgroundEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(CUSTOM_BG_KEY, enabled) }
        _isCustomBackground.value = enabled
    }
    private fun getCustomBackgroundEnabled(): Boolean = prefs.getBoolean(CUSTOM_BG_KEY, false)

    // [UPDATE] Set Video Flag
    fun setIsVideoWallpaper(isVideo: Boolean) {
        prefs.edit { putBoolean(IS_VIDEO_KEY, isVideo) }
        _isVideoWallpaper.value = isVideo
    }
    private fun getIsVideoWallpaper(): Boolean = prefs.getBoolean(IS_VIDEO_KEY, false)

    fun setBackgroundImageUri(uri: String?) {
        prefs.edit { putString(BG_URI_KEY, uri) }
        _backgroundImageUri.value = uri
        // Reset flag jika URI dihapus
        if (uri == null) setIsVideoWallpaper(false)
    }
    private fun getBackgroundImageUri(): String? = prefs.getString(BG_URI_KEY, null)

    fun setNavBarTransparency(value: Float) { prefs.edit { putFloat(NAVBAR_TRANS_KEY, value) }; _navBarTransparency.value = value }
    private fun getNavBarTransparency(): Float = prefs.toFloat(NAVBAR_TRANS_KEY, 0.5f)

    fun setCardDarkness(value: Float) { prefs.edit { putFloat(CARD_DARKNESS_KEY, value) }; _cardDarkness.value = value }
    private fun getCardDarkness(): Float = prefs.toFloat(CARD_DARKNESS_KEY, 0.5f)

    fun setBgBlurEnabled(enabled: Boolean) { prefs.edit { putBoolean(BG_BLUR_KEY, enabled) }; _isBgBlur.value = enabled }
    private fun getBgBlurEnabled(): Boolean = prefs.getBoolean(BG_BLUR_KEY, false)

    fun setBgContrast(value: Float) { prefs.edit { putFloat(BG_CONTRAST_KEY, value) }; _bgContrast.value = value }
    private fun getBgContrast(): Float = prefs.toFloat(BG_CONTRAST_KEY, 0f)

    fun setHazeEnabled(enabled: Boolean) { prefs.edit { putBoolean(HAZE_ENABLED_KEY, enabled) }; _isHazeEnabled.value = enabled }
    private fun getHazeEnabled(): Boolean = prefs.getBoolean(HAZE_ENABLED_KEY, true)

    fun setBgType(type: BgType) {
        prefs.edit { putString(BG_TYPE_KEY, type.name) }
        _bgType.value = type
        setCustomBackgroundEnabled(type == BgType.GALLERY)
    }
    private fun getBgType(): BgType {
        return try { BgType.valueOf(prefs.getString(BG_TYPE_KEY, BgType.SYSTEM.name)!!) } catch (e: Exception) { BgType.SYSTEM }
    }

    fun setSolidColor(color: Int) { prefs.edit { putInt(SOLID_COLOR_KEY, color) }; _solidColor.value = color }
    private fun getSolidColor(): Int = prefs.getInt(SOLID_COLOR_KEY, Color(0xFF1A1A1A).toArgb())

    fun setExpressiveThemeId(id: Int) { prefs.edit { putInt(EXPRESSIVE_ID_KEY, id) }; _expressiveThemeId.value = id }
    private fun getExpressiveThemeId(): Int = prefs.getInt(EXPRESSIVE_ID_KEY, 0)

    fun setNavStyle(style: NavStyle) { prefs.edit { putString(NAV_STYLE_KEY, style.name) }; _navStyle.value = style }
    private fun getNavStyle(): NavStyle {
        return try { NavStyle.valueOf(prefs.getString(NAV_STYLE_KEY, NavStyle.LIQUID_CAPSULE.name)!!) } catch (e: Exception) { NavStyle.LIQUID_CAPSULE }
    }

    fun setNavLabelState(state: NavLabelState) { prefs.edit { putString(NAV_LABEL_KEY, state.name) }; _navLabelState.value = state }
    private fun getNavLabelState(): NavLabelState {
        return try { NavLabelState.valueOf(prefs.getString(NAV_LABEL_KEY, NavLabelState.ALWAYS_SHOW.name)!!) } catch (e: Exception) { NavLabelState.ALWAYS_SHOW }
    }

    // [BARU] Weather Effect Getters/Setters
    fun setWeatherEffect(effect: WeatherEffect) { prefs.edit { putString(WEATHER_EFFECT_KEY, effect.name) }; _weatherEffect.value = effect }
    private fun getWeatherEffect(): WeatherEffect {
        return try { WeatherEffect.valueOf(prefs.getString(WEATHER_EFFECT_KEY, WeatherEffect.NONE.name)!!) } catch (e: Exception) { WeatherEffect.NONE }
    }

    fun setWeatherIntensity(intensity: Float) { prefs.edit { putFloat(WEATHER_INTENSITY_KEY, intensity) }; _weatherIntensity.value = intensity }
    private fun getWeatherIntensity(): Float = prefs.toFloat(WEATHER_INTENSITY_KEY, 0.5f)

    // [BARU] Background Adjustment Getters/Setters
    fun setBgSaturation(saturation: Float) { prefs.edit { putFloat(BG_SATURATION_KEY, saturation) }; _bgSaturation.value = saturation }
    private fun getBgSaturation(): Float = prefs.toFloat(BG_SATURATION_KEY, 1f)

    fun setBlurStrength(strength: Float) { prefs.edit { putFloat(BLUR_STRENGTH_KEY, strength) }; _blurStrength.value = strength }
    private fun getBlurStrength(): Float = prefs.toFloat(BLUR_STRENGTH_KEY, 20f)

    // [BARU] Custom Color Getters/Setters
    fun setIsCustomColor(enabled: Boolean) { prefs.edit { putBoolean(IS_CUSTOM_COLOR_KEY, enabled) }; _isCustomColor.value = enabled }
    private fun getIsCustomColor(): Boolean = prefs.getBoolean(IS_CUSTOM_COLOR_KEY, false)

    fun setCustomPrimaryColor(color: Int) { prefs.edit { putInt(CUSTOM_PRIMARY_KEY, color) }; _customPrimaryColor.value = color }
    private fun getCustomPrimaryColor(): Int = prefs.getInt(CUSTOM_PRIMARY_KEY, Color(0xFF4A6595).toArgb())

    fun setCustomSecondaryColor(color: Int) { prefs.edit { putInt(CUSTOM_SECONDARY_KEY, color) }; _customSecondaryColor.value = color }
    private fun getCustomSecondaryColor(): Int = prefs.getInt(CUSTOM_SECONDARY_KEY, Color(0xFF586275).toArgb())

    fun setCustomTertiaryColor(color: Int) { prefs.edit { putInt(CUSTOM_TERTIARY_KEY, color) }; _customTertiaryColor.value = color }
    private fun getCustomTertiaryColor(): Int = prefs.getInt(CUSTOM_TERTIARY_KEY, Color(0xFF5E4B8B).toArgb())

    // [BARU] Intro Getters/Setters
    fun setHasCompletedIntro(completed: Boolean) { 
        prefs.edit { putBoolean(HAS_COMPLETED_INTRO_KEY, completed) } 
        _hasCompletedIntro.value = completed 
    }
    private fun getHasCompletedIntro(): Boolean = prefs.getBoolean(HAS_COMPLETED_INTRO_KEY, false)

    fun setAcceptedTerms(accepted: Boolean) { 
        prefs.edit { putBoolean(ACCEPTED_TERMS_KEY, accepted) } 
        _acceptedTerms.value = accepted 
    }
    private fun getAcceptedTerms(): Boolean = prefs.getBoolean(ACCEPTED_TERMS_KEY, false)

    fun setAcceptedRisk(accepted: Boolean) { 
        prefs.edit { putBoolean(ACCEPTED_RISK_KEY, accepted) } 
        _acceptedRisk.value = accepted 
    }
    private fun getAcceptedRisk(): Boolean = prefs.getBoolean(ACCEPTED_RISK_KEY, false)

    fun setCpuGpuProfilesJson(json: String) {
        prefs.edit { putString(CPU_GPU_PROFILES_KEY, json) }
        _cpuGpuProfilesJson.value = json
    }
    private fun getCpuGpuProfilesJson(): String = prefs.getString(CPU_GPU_PROFILES_KEY, "[]") ?: "[]"

    fun setSelectedCpuProfileName(name: String?) {
        prefs.edit { putString(SELECTED_CPU_PROFILE_NAME_KEY, name) }
        _selectedCpuProfileName.value = name
    }
    private fun getSelectedCpuProfileName(): String? = prefs.getString(SELECTED_CPU_PROFILE_NAME_KEY, null)

    fun setSchedProfilesJson(json: String) {
        prefs.edit { putString(SCHED_PROFILES_KEY, json) }
        _schedProfilesJson.value = json
    }
    private fun getSchedProfilesJson(): String = prefs.getString(SCHED_PROFILES_KEY, "[]") ?: "[]"

    fun setSelectedSchedProfileName(name: String?) {
        prefs.edit { putString(SELECTED_SCHED_PROFILE_NAME_KEY, name) }
        _selectedSchedProfileName.value = name
    }
    private fun getSelectedSchedProfileName(): String? = prefs.getString(SELECTED_SCHED_PROFILE_NAME_KEY, null)

    fun setMemProfilesJson(json: String) {
        prefs.edit { putString(MEM_PROFILES_KEY, json) }
        _memProfilesJson.value = json
    }
    private fun getMemProfilesJson(): String = prefs.getString(MEM_PROFILES_KEY, "[]") ?: "[]"

    fun setSelectedMemProfileName(name: String?) {
        prefs.edit { putString(SELECTED_MEM_PROFILE_NAME_KEY, name) }
        _selectedMemProfileName.value = name
    }
    private fun getSelectedMemProfileName(): String? = prefs.getString(SELECTED_MEM_PROFILE_NAME_KEY, null)

    fun setNetProfilesJson(json: String) {
        prefs.edit { putString(NET_PROFILES_KEY, json) }
        _netProfilesJson.value = json
    }
    private fun getNetProfilesJson(): String = prefs.getString(NET_PROFILES_KEY, "[]") ?: "[]"

    fun setSelectedNetProfileName(name: String?) {
        prefs.edit { putString(SELECTED_NET_PROFILE_NAME_KEY, name) }
        _selectedNetProfileName.value = name
    }
    private fun getSelectedNetProfileName(): String? = prefs.getString(SELECTED_NET_PROFILE_NAME_KEY, null)



    fun setIndividualCpuSettingsJson(json: String) {
        prefs.edit { putString(INDIVIDUAL_CPU_SETTINGS_KEY, json) }
        _individualCpuSettingsJson.value = json
    }
    private fun getIndividualCpuSettingsJson(): String = prefs.getString(INDIVIDUAL_CPU_SETTINGS_KEY, "{}") ?: "{}"

    fun setBatteryMonitorEnabled(enabled: Boolean) { prefs.edit { putBoolean(BATTERY_MONITOR_KEY, enabled) }; _batteryMonitorEnabled.value = enabled }
    private fun getBatteryMonitorEnabled(): Boolean = prefs.getBoolean(BATTERY_MONITOR_KEY, false)

    fun setSmartCutoffEnabled(enabled: Boolean) { prefs.edit { putBoolean(SMART_CUTOFF_KEY, enabled) }; _smartCutoffEnabled.value = enabled }
    private fun getSmartCutoffEnabled(): Boolean = prefs.getBoolean(SMART_CUTOFF_KEY, false)

    fun setSmartCutoffLimit(limit: Float) { prefs.edit { putFloat(SMART_CUTOFF_LIMIT_KEY, limit) }; _smartCutoffLimit.value = limit }
    private fun getSmartCutoffLimit(): Float = prefs.toFloat(SMART_CUTOFF_LIMIT_KEY, 80f)

    fun setChargingLimit(limit: Int) { prefs.edit { putInt(CHARGING_LIMIT_KEY, limit) }; _chargingLimit.value = limit }
    private fun getChargingLimit(): Int = prefs.getInt(CHARGING_LIMIT_KEY, 100)

    fun setFastChargeEnabled(enabled: Boolean) { prefs.edit { putBoolean(FAST_CHARGE_KEY, enabled) }; _fastChargeEnabled.value = enabled }
    private fun getFastChargeEnabled(): Boolean = prefs.getBoolean(FAST_CHARGE_KEY, false)

    fun setBypassChargingEnabled(enabled: Boolean) { prefs.edit { putBoolean(BYPASS_CHARGING_KEY, enabled) }; _bypassChargingEnabled.value = enabled }
    private fun getBypassChargingEnabled(): Boolean = prefs.getBoolean(BYPASS_CHARGING_KEY, false)

    fun setChargingSpeed(speed: Int) { prefs.edit { putInt(CHARGING_SPEED_KEY, speed) }; _chargingSpeed.value = speed }
    private fun getChargingSpeed(): Int = prefs.getInt(CHARGING_SPEED_KEY, 2000)

    fun setBatterySaverEnabled(enabled: Boolean) { prefs.edit { putBoolean(BATTERY_SAVER_KEY, enabled) }; _batterySaverEnabled.value = enabled }
    private fun getBatterySaverEnabled(): Boolean = prefs.getBoolean(BATTERY_SAVER_KEY, false)

    fun setThermalSconfig(value: String) { prefs.edit { putString(THERMAL_SCONFIG_KEY, value) }; _thermalSconfig.value = value }
    private fun getThermalSconfig(): String = prefs.getString(THERMAL_SCONFIG_KEY, "0") ?: "0"

    fun setApplyOnBoot(enabled: Boolean) { prefs.edit { putBoolean(APPLY_ON_BOOT_KEY, enabled) }; _applyOnBoot.value = enabled }
    private fun getApplyOnBoot(): Boolean = prefs.getBoolean(APPLY_ON_BOOT_KEY, true)

    fun setAlternateIconEnabled(context: Context, enabled: Boolean) {
        prefs.edit { putBoolean(ALT_ICON_KEY, enabled) }
        _isAlternateIcon.value = enabled
        toggleAppIcon(context, enabled)
    }
    private fun getAlternateIconEnabled(): Boolean = prefs.getBoolean(ALT_ICON_KEY, false)

    private fun toggleAppIcon(context: Context, useAlt: Boolean) {
        val pm = context.packageManager
        val pkgName = context.packageName
        
        val defaultAlias = "$pkgName.MainActivityAlias"
        val altAlias = "$pkgName.MainActivityAltAlias"
        
        try {
            if (useAlt) {
                pm.setComponentEnabledSetting(
                    ComponentName(pkgName, altAlias),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    ComponentName(pkgName, defaultAlias),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } else {
                pm.setComponentEnabledSetting(
                    ComponentName(pkgName, defaultAlias),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    ComponentName(pkgName, altAlias),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    // --- FPS OVERLAY GETTERS/SETTERS ---
    fun setFpsStyle(style: Int) { prefs.edit { putInt(FPS_STYLE_KEY, style) }; _fpsStyle.value = style }
    private fun getFpsStyle(): Int = prefs.getInt(FPS_STYLE_KEY, 0)

    fun setFpsOrientation(orientation: Int) { prefs.edit { putInt(FPS_ORIENTATION_KEY, orientation) }; _fpsOrientation.value = orientation }
    private fun getFpsOrientation(): Int = prefs.getInt(FPS_ORIENTATION_KEY, 0)

    fun setFpsColor(color: String) { prefs.edit { putString(FPS_COLOR_KEY, color) }; _fpsColor.value = color }
    private fun getFpsColor(): String = prefs.getString(FPS_COLOR_KEY, "#00FF00") ?: "#00FF00"

    fun setFpsSize(size: Float) { prefs.edit { putFloat(FPS_SIZE_KEY, size) }; _fpsSize.value = size }
    private fun getFpsSize(): Float = prefs.getFloat(FPS_SIZE_KEY, 14f)

    fun setFpsWidthScale(scale: Float) { prefs.edit { putFloat(FPS_WIDTH_SCALE_KEY, scale) }; _fpsWidthScale.value = scale }
    private fun getFpsWidthScale(): Float = prefs.getFloat(FPS_WIDTH_SCALE_KEY, 1f)

    fun setFpsAlpha(alpha: Float) { prefs.edit { putFloat(FPS_ALPHA_KEY, alpha) }; _fpsAlpha.value = alpha }
    private fun getFpsAlpha(): Float = prefs.getFloat(FPS_ALPHA_KEY, 0.5f)

    fun setFpsShowFps(show: Boolean) { prefs.edit { putBoolean(FPS_SHOW_FPS_KEY, show) }; _fpsShowFps.value = show }
    private fun getFpsShowFps(): Boolean = prefs.getBoolean(FPS_SHOW_FPS_KEY, true)

    fun setFpsShowCpu(show: Boolean) { prefs.edit { putBoolean(FPS_SHOW_CPU_KEY, show) }; _fpsShowCpu.value = show }
    private fun getFpsShowCpu(): Boolean = prefs.getBoolean(FPS_SHOW_CPU_KEY, true)

    fun setFpsShowWatts(show: Boolean) { prefs.edit { putBoolean(FPS_SHOW_WATTS_KEY, show) }; _fpsShowWatts.value = show }
    private fun getFpsShowWatts(): Boolean = prefs.getBoolean(FPS_SHOW_WATTS_KEY, true)

    fun setFpsShowTemp(show: Boolean) { prefs.edit { putBoolean(FPS_SHOW_TEMP_KEY, show) }; _fpsShowTemp.value = show }
    private fun getFpsShowTemp(): Boolean = prefs.getBoolean(FPS_SHOW_TEMP_KEY, true)

    fun setFpsShowRam(show: Boolean) { prefs.edit { putBoolean(FPS_SHOW_RAM_KEY, show) }; _fpsShowRam.value = show }
    private fun getFpsShowRam(): Boolean = prefs.getBoolean(FPS_SHOW_RAM_KEY, true)

    fun setFpsShowRender(show: Boolean) { prefs.edit { putBoolean(FPS_SHOW_RENDER_KEY, show) }; _fpsShowRender.value = show }
    private fun getFpsShowRender(): Boolean = prefs.getBoolean(FPS_SHOW_RENDER_KEY, false)

    fun setFpsShowGpuUsage(show: Boolean) { prefs.edit { putBoolean(FPS_SHOW_GPU_USAGE_KEY, show) }; _fpsShowGpuUsage.value = show }
    private fun getFpsShowGpuUsage(): Boolean = prefs.getBoolean(FPS_SHOW_GPU_USAGE_KEY, false)

    fun setFpsShowCpuTemp(show: Boolean) { prefs.edit { putBoolean(FPS_SHOW_CPU_TEMP_KEY, show) }; _fpsShowCpuTemp.value = show }
    private fun getFpsShowCpuTemp(): Boolean = prefs.getBoolean(FPS_SHOW_CPU_TEMP_KEY, false)

    fun setFpsShowCpuFreq(show: Boolean) { prefs.edit { putBoolean(FPS_SHOW_CPU_FREQ_KEY, show) }; _fpsShowCpuFreq.value = show }
    private fun getFpsShowCpuFreq(): Boolean = prefs.getBoolean(FPS_SHOW_CPU_FREQ_KEY, false)

    fun setFpsShowGpuFreq(show: Boolean) { prefs.edit { putBoolean(FPS_SHOW_GPU_FREQ_KEY, show) }; _fpsShowGpuFreq.value = show }
    private fun getFpsShowGpuFreq(): Boolean = prefs.getBoolean(FPS_SHOW_GPU_FREQ_KEY, false)

    fun setFpsShowGpuTemp(show: Boolean) { prefs.edit { putBoolean(FPS_SHOW_GPU_TEMP_KEY, show) }; _fpsShowGpuTemp.value = show }
    private fun getFpsShowGpuTemp(): Boolean = prefs.getBoolean(FPS_SHOW_GPU_TEMP_KEY, false)

    fun setFpsPos(x: Int, y: Int) {
        prefs.edit { 
            putInt(FPS_POS_X_KEY, x)
            putInt(FPS_POS_Y_KEY, y)
        }
        _fpsPosX.value = x
        _fpsPosY.value = y
    }
    private fun getFpsPosX(): Int = prefs.getInt(FPS_POS_X_KEY, 20)
    private fun getFpsPosY(): Int = prefs.getInt(FPS_POS_Y_KEY, 100)
}

private fun SharedPreferences.toFloat(key: String, defValue: Float): Float = try { this.getFloat(key, defValue) } catch (e: Exception) { defValue }
