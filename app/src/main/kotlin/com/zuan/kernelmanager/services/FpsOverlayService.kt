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
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.zuan.kernelmanager.R
import com.zuan.kernelmanager.ui.MainActivity
import android.graphics.PixelFormat
import android.os.Build
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.zuan.kernelmanager.ui.navigation.NavigationRoute
import com.zuan.kernelmanager.ui.settings.SettingsPreference
import com.zuan.kernelmanager.utils.FpsReader
import com.zuan.kernelmanager.utils.FpsRecorder
import com.zuan.kernelmanager.utils.MonitorReader
import com.zuan.kernelmanager.utils.RootPersistenceUtils
import com.zuan.kernelmanager.utils.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class FpsOverlayService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner {

    companion object {
        var isRunning = false
        const val TAG = "FpsOverlayService"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private lateinit var overlayParams: WindowManager.LayoutParams
    
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    private lateinit var settingsPreference: SettingsPreference

    // --- CONFIG VARIABLES ---
    private var styleMode by mutableStateOf(0) 
    private var androidOrientation by mutableStateOf(0) 
    private var colorHex by mutableStateOf("#00FF00")
    private var textSizeSp by mutableStateOf(14f)
    private var bgAlpha by mutableStateOf(0.5f)
    private var widthScale by mutableStateOf(1f)
    
    // Toggle Metrics
    private var showFps by mutableStateOf(true)
    private var showCpu by mutableStateOf(true)
    private var showWatts by mutableStateOf(true)
    private var showTemp by mutableStateOf(true)
    private var showRam by mutableStateOf(true)
    private var showRender by mutableStateOf(false)
    private var showGpuUsage by mutableStateOf(false)
    private var showCpuTemp by mutableStateOf(false)
    private var showCpuFreq by mutableStateOf(false)
    private var showGpuFreq by mutableStateOf(false)
    private var showGpuTemp by mutableStateOf(false)

    // Drag vars
    private var initialX = 0; private var initialY = 0; private var initialTouchX = 0f; private var initialTouchY = 0f

    override fun onCreate() {
        super.onCreate()
        ShellExecutor.init(this)
        RootPersistenceUtils.applyRootExemptions(this)
        settingsPreference = SettingsPreference.getInstance(this)
        loadInitialSettings()
        
        savedStateRegistryController.performRestore(null)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val savedX = settingsPreference.fpsPosX.value
        val savedY = settingsPreference.fpsPosY.value
        overlayParams = createLayoutParams(savedX, savedY)
        
        startForegroundNotification()
        isRunning = true
    }

    private fun loadInitialSettings() {
        styleMode = settingsPreference.fpsStyle.value
        androidOrientation = settingsPreference.fpsOrientation.value
        colorHex = settingsPreference.fpsColor.value
        textSizeSp = settingsPreference.fpsSize.value
        widthScale = settingsPreference.fpsWidthScale.value
        bgAlpha = settingsPreference.fpsAlpha.value
        
        showFps = settingsPreference.fpsShowFps.value
        showCpu = settingsPreference.fpsShowCpu.value
        showWatts = settingsPreference.fpsShowWatts.value
        showTemp = settingsPreference.fpsShowTemp.value
        showRam = settingsPreference.fpsShowRam.value
        showRender = settingsPreference.fpsShowRender.value
        showGpuUsage = settingsPreference.fpsShowGpuUsage.value
        showCpuTemp = settingsPreference.fpsShowCpuTemp.value
        showCpuFreq = settingsPreference.fpsShowCpuFreq.value
        showGpuFreq = settingsPreference.fpsShowGpuFreq.value
        showGpuTemp = settingsPreference.fpsShowGpuTemp.value
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        if (intent != null) {
            if (intent.hasExtra("STYLE")) styleMode = intent.getIntExtra("STYLE", 0)
            if (intent.hasExtra("ORIENTATION")) androidOrientation = intent.getIntExtra("ORIENTATION", 0)
            
            intent.getStringExtra("COLOR")?.let { c -> colorHex = c }
            intent.getStringExtra("POSITION")?.let { p -> resetPosition(p) }
            
            if (intent.hasExtra("SIZE")) textSizeSp = intent.getFloatExtra("SIZE", 14f)
            if (intent.hasExtra("WIDTH_SCALE")) widthScale = intent.getFloatExtra("WIDTH_SCALE", 1f)
            if (intent.hasExtra("ALPHA")) bgAlpha = intent.getFloatExtra("ALPHA", 0.5f)
            
            if (intent.hasExtra("SHOW_FPS")) showFps = intent.getBooleanExtra("SHOW_FPS", true)
            if (intent.hasExtra("SHOW_CPU")) showCpu = intent.getBooleanExtra("SHOW_CPU", true)
            if (intent.hasExtra("SHOW_WATTS")) showWatts = intent.getBooleanExtra("SHOW_WATTS", true)
            if (intent.hasExtra("SHOW_TEMP")) showTemp = intent.getBooleanExtra("SHOW_TEMP", true)
            if (intent.hasExtra("SHOW_RAM")) showRam = intent.getBooleanExtra("SHOW_RAM", true)
            if (intent.hasExtra("SHOW_RENDER")) showRender = intent.getBooleanExtra("SHOW_RENDER", false)
            if (intent.hasExtra("SHOW_GPU_USAGE")) showGpuUsage = intent.getBooleanExtra("SHOW_GPU_USAGE", false)
            if (intent.hasExtra("SHOW_CPU_TEMP")) showCpuTemp = intent.getBooleanExtra("SHOW_CPU_TEMP", false)
            if (intent.hasExtra("SHOW_CPU_FREQ")) showCpuFreq = intent.getBooleanExtra("SHOW_CPU_FREQ", false)
            if (intent.hasExtra("SHOW_GPU_FREQ")) showGpuFreq = intent.getBooleanExtra("SHOW_GPU_FREQ", false)
            if (intent.hasExtra("SHOW_GPU_TEMP")) showGpuTemp = intent.getBooleanExtra("SHOW_GPU_TEMP", false)
        } else {
            loadInitialSettings()
        }

        if (overlayView == null) {
            setupOverlay()
        } else {
            applyBoundaryConstraints()
        }
        return START_STICKY
    }

    private fun getScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            metrics.bounds.width() to metrics.bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun applyBoundaryConstraints() {
        overlayView?.post {
            overlayView?.let { v ->
                val (screenWidth, screenHeight) = getScreenSize()
                
                // Use measured size if current size is 0
                val viewWidth = v.width.coerceAtLeast(v.measuredWidth)
                val viewHeight = v.height.coerceAtLeast(v.measuredHeight)

                if (viewWidth <= 0 || viewHeight <= 0) return@let

                val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
                val safetyMargin = (16 * resources.displayMetrics.density).toInt()
                val maxY = (screenHeight - viewHeight - safetyMargin).coerceAtLeast(0)

                val oldX = overlayParams.x
                val oldY = overlayParams.y

                overlayParams.x = overlayParams.x.coerceIn(0, maxX)
                overlayParams.y = overlayParams.y.coerceIn(0, maxY)

                if (overlayParams.x != oldX || overlayParams.y != oldY) {
                    try {
                        windowManager.updateViewLayout(overlayView, overlayParams)
                        settingsPreference.setFpsPos(overlayParams.x, overlayParams.y)
                    } catch (e: Exception) {
                        Log.e(TAG, "Update Layout Error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun setupOverlay() {
        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FpsOverlayService)
            setViewTreeSavedStateRegistryOwner(this@FpsOverlayService)
            setViewTreeViewModelStoreOwner(this@FpsOverlayService)

            setContent {
                MainOverlayContent(
                    styleMode = styleMode,
                    orientation = androidOrientation,
                    colorHex = colorHex,
                    fontSize = textSizeSp,
                    bgAlpha = bgAlpha,
                    widthScale = widthScale,
                    metrics = MetricsState(
                        showFps, showCpu, showWatts, showTemp, showRam, showRender,
                        showGpuUsage, showCpuTemp, showCpuFreq, showGpuFreq, showGpuTemp
                    )
                )
            }
            
            setOnTouchListener(object : View.OnTouchListener {
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = overlayParams.x
                            initialY = overlayParams.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val (screenWidth, screenHeight) = getScreenSize()
                            
                            val viewWidth = v.width.coerceAtLeast(v.measuredWidth)
                            val viewHeight = v.height.coerceAtLeast(v.measuredHeight)
                            
                            val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
                            val safetyMargin = (16 * resources.displayMetrics.density).toInt()
                            val maxY = (screenHeight - viewHeight - safetyMargin).coerceAtLeast(0)

                            val deltaX = (event.rawX - initialTouchX).toInt()
                            val deltaY = (event.rawY - initialTouchY).toInt()
                            
                            overlayParams.x = (initialX + deltaX).coerceIn(0, maxX)
                            overlayParams.y = (initialY + deltaY).coerceIn(0, maxY)
                            
                            windowManager.updateViewLayout(overlayView, overlayParams)
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            settingsPreference.setFpsPos(overlayParams.x, overlayParams.y)
                            return true
                        }
                    }
                    return false
                }
            })
        }
        windowManager.addView(overlayView, overlayParams)
        
        // Ensure boundaries on first layout
        applyBoundaryConstraints()
    }

    private fun resetPosition(position: String) {
        overlayParams.x = 20
        overlayParams.y = 200
        if (overlayView != null) {
            windowManager.updateViewLayout(overlayView, overlayParams)
            settingsPreference.setFpsPos(overlayParams.x, overlayParams.y)
            applyBoundaryConstraints()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyBoundaryConstraints()
    }
    
    private fun createLayoutParams(xPos: Int, yPos: Int): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = xPos
        params.y = yPos
        return params
    }
    
    private fun startForegroundNotification() {
        val channelId = "fps_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "FPS Overlay", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("TARGET_ROUTE", NavigationRoute.FpsManager.route)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ZKM Overlay")
            .setContentText(getString(R.string.fps_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (isRunning) {
            val restartServiceIntent = Intent(applicationContext, FpsOverlayService::class.java).apply {
                setPackage(packageName)
            }
            val restartServicePendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    applicationContext,
                    1,
                    restartServiceIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    applicationContext,
                    1,
                    restartServiceIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val alarmService = applicationContext.getSystemService(ALARM_SERVICE) as AlarmManager
            alarmService.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        FpsRecorder.stopRecording(this)
        if (overlayView != null) { windowManager.removeView(overlayView); overlayView = null }
        store.clear()
    }
}

data class MetricsState(
    val fps: Boolean, val cpu: Boolean, val watt: Boolean, val temp: Boolean, val ram: Boolean, val showRender: Boolean,
    val gpuUsage: Boolean = false, val cpuTemp: Boolean = false, val cpuFreq: Boolean = false, val gpuFreq: Boolean = false,
    val gpuTemp: Boolean = false
)

// --- MAIN UI COMPOSER ---

@Composable
fun MainOverlayContent(
    styleMode: Int,
    orientation: Int,
    colorHex: String,
    fontSize: Float,
    bgAlpha: Float,
    widthScale: Float,
    metrics: MetricsState
) {
    val context = LocalContext.current
    val customColor = try { Color(android.graphics.Color.parseColor(colorHex)) } catch (e: Exception) { Color.Green }
    
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }

    // Data Holders
    var fpsVal by remember { mutableStateOf("0") }
    var fpsFloat by remember { mutableFloatStateOf(0f) }
    var cpuVal by remember { mutableStateOf("0%") }
    var cpuInt by remember { mutableIntStateOf(0) }
    var wattVal by remember { mutableStateOf("0.0W") }
    var wattFloat by remember { mutableFloatStateOf(0f) }
    var tempVal by remember { mutableStateOf("0°C") }
    var tempFloat by remember { mutableFloatStateOf(0f) }
    var ramVal by remember { mutableStateOf("0") }
    var ramInt by remember { mutableIntStateOf(0) }
    var gpuUsageVal by remember { mutableStateOf("0%") }
    var cpuTempFormat by remember { mutableStateOf("0°C") }
    var cpuFreqVal by remember { mutableStateOf("0MHz") }
    var gpuFreqVal by remember { mutableStateOf("0MHz") }
    var gpuTempFormat by remember { mutableStateOf("0°C") }
    var renderName by remember { mutableStateOf("FPS") }

    // Record State
    var isRec by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            while (isActive) {
                // Optimization: Don't collect data if screen is off
                if (!powerManager.isInteractive) {
                    delay(3000)
                    continue
                }

                val start = System.currentTimeMillis()
                try {
                    // Sync Record State
                    isRec = FpsRecorder.isRecording
                    isPaused = FpsRecorder.isPaused

                    // 1. Baca Sensor
                    if (metrics.fps) {
                         fpsFloat = FpsReader.getRealFps()
                         fpsVal = String.format("%.0f", fpsFloat)
                    }
                    if (metrics.cpu) {
                        cpuInt = MonitorReader.getCpuLoad()
                        cpuVal = "$cpuInt%"
                    }
                    if (metrics.watt) {
                        wattFloat = MonitorReader.getPowerWatt()
                        wattVal = String.format("%.1fW", wattFloat)
                    }
                    if (metrics.temp) {
                        tempFloat = MonitorReader.getBatteryTemp(context)
                        tempVal = String.format("%.1f°C", tempFloat)
                    }
                    if (metrics.ram) {
                        val r = MonitorReader.getRamInfo(context)
                        ramInt = r.usedMb
                        ramVal = "${r.usedMb}"
                    }
                    
                    if (metrics.gpuUsage || metrics.gpuFreq) {
                        val gpuInfo = MonitorReader.getCombinedGpuInfo()
                        if (metrics.gpuUsage) {
                            gpuUsageVal = "${gpuInfo.usage}%"
                        }
                        if (metrics.gpuFreq) {
                            gpuFreqVal = "${gpuInfo.freq}MHz"
                        }
                    }
                    
                    if (metrics.cpuTemp) {
                        cpuTempFormat = String.format("%.1f°C", MonitorReader.getCpuTemp())
                    }
                    if (metrics.cpuFreq) {
                        cpuFreqVal = "${MonitorReader.getCpuFreqAverage()}MHz"
                    }
                    if (metrics.gpuTemp) {
                        gpuTempFormat = String.format("%.1f°C", MonitorReader.getGpuTemp())
                    }
                    if (metrics.showRender) {
                        renderName = MonitorReader.getCurrentRenderer()
                    }

                    // 2. Kirim ke Recorder jika aktif
                    if (isRec) {
                        val currentPkg = MonitorReader.getForegroundPackage()
                        val gpuUsageInt = MonitorReader.getCombinedGpuInfo().usage
                        FpsRecorder.tick(context, currentPkg, fpsFloat, cpuInt, gpuUsageInt, wattFloat, tempFloat, ramInt)
                    }

                } catch (e: Exception) {}
                delay(1000 - (System.currentTimeMillis() - start).coerceAtLeast(0))
            }
        }
    }

    Box(
        modifier = Modifier
            .background(Color.Black.copy(alpha = bgAlpha), RoundedCornerShape(8.dp))
            .padding(8.dp)
            .width(IntrinsicSize.Max)
            .widthIn(min = (80 * widthScale).dp)
    ) {
        // STRUKTUR VERTIKAL: ATAS (DATA) - BAWAH (TOMBOL)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            
            // --- BAGIAN ATAS: DATA SESUAI STYLE ---
            Box(Modifier.fillMaxWidth()) {
                when (styleMode) {
                    0 -> AndroidStyleOverlay(
                        orientation, customColor, fontSize, metrics, 
                        fpsVal, cpuVal, wattVal, tempVal, ramVal,
                        gpuUsageVal, cpuTempFormat, cpuFreqVal, gpuFreqVal, gpuTempFormat,
                        renderName
                    )
                    1 -> PcStyleOverlay(
                        fontSize, metrics, 
                        fpsVal, cpuVal, wattVal, tempVal, ramMb = ramVal, renderLabel = renderName,
                        gpuUsage = gpuUsageVal, cpuTemp = cpuTempFormat, cpuFreq = cpuFreqVal, gpuFreq = gpuFreqVal, gpuTemp = gpuTempFormat
                    )
                    2 -> MiniMonitorOverlay(
                        fontSize, metrics, 
                        fpsVal, cpuVal, tempVal,
                        gpuUsageVal, cpuTempFormat, gpuTempFormat,
                        renderName
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f), thickness = 0.5.dp)
            Spacer(Modifier.height(4.dp))
            
            OverlayBottomControls(isRec, isPaused, context)
        }
    }
}

@Composable
fun OverlayBottomControls(isRec: Boolean, isPaused: Boolean, context: Context) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        RecordControlButton(isRec, isPaused, context)
        
        // Stop Service Button (X)
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .clickable {
                    context.stopService(Intent(context, FpsOverlayService::class.java))
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Stop Overlay",
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// --- TOMBOL RECORD (DI BAWAH) ---
@Composable
fun RecordControlButton(isRec: Boolean, isPaused: Boolean, context: Context) {
    Box(
        modifier = Modifier
            .size(24.dp) // Ukuran icon kecil pas di bawah
            .clip(CircleShape)
            .background(if (isRec) Color.White.copy(alpha = 0.1f) else Color.Transparent)
            .clickable {
                if (isRec) {
                    // STOP
                    FpsRecorder.stopRecording(context)
                } else {
                    // START
                    // Logic Fix: Jika package kosong (gagal detect), tetap start sebagai "Unknown App"
                    var pkg = MonitorReader.getForegroundPackage()
                    if (pkg.isEmpty()) pkg = "Unknown App" 
                    FpsRecorder.startRecording(pkg)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (isRec) {
            if (isPaused) {
                // Kuning (Paused/Out of game)
                Icon(Icons.Default.Pause, null, tint = Color.Yellow, modifier = Modifier.size(16.dp))
            } else {
                // Merah Kotak (Recording)
                Icon(Icons.Default.Stop, null, tint = Color.Red, modifier = Modifier.size(16.dp))
            }
        } else {
            // Putih Bulat (Standby)
            Icon(Icons.Default.FiberManualRecord, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
        }
    }
}

// --- STYLE 1: ANDROID ---
@Composable
fun AndroidStyleOverlay(
    orientation: Int, color: Color, size: Float, m: MetricsState,
    fps: String, cpu: String, watt: String, temp: String, ram: String,
    gpu: String, cpuTemp: String, cpuFreq: String, gpuFreq: String, gpuTemp: String,
    renderLabel: String
) {
    val finalFpsLabel = if (m.showRender) renderLabel else "FPS"

    if (orientation == 0) { // Vertical
        Column(horizontalAlignment = Alignment.Start) {
            if (m.fps) AndroidRow(finalFpsLabel, fps, color, size)
            if (m.cpu) AndroidRow("CPU", cpu, color, size)
            if (m.cpuFreq) AndroidRow("CFR", cpuFreq, color, size)
            if (m.cpuTemp) AndroidRow("CTP", cpuTemp, color, size)
            if (m.gpuUsage) AndroidRow("GPU", gpu, color, size)
            if (m.gpuFreq) AndroidRow("GFR", gpuFreq, color, size)
            if (m.gpuTemp) AndroidRow("GTP", gpuTemp, color, size)
            if (m.ram) AndroidRow("RAM", "$ram MB", color, size)
            if (m.watt) AndroidRow("PWR", watt, color, size)
            if (m.temp) AndroidRow("TMP", temp, color, size)
        }
    } else { // Horizontal
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (m.fps) AndroidRow(finalFpsLabel, fps, color, size)
            if (m.cpu) AndroidRow("CPU", cpu, color, size)
            if (m.gpuUsage) AndroidRow("GPU", gpu, color, size)
            if (m.gpuTemp) AndroidRow("GTP", gpuTemp, color, size)
            if (m.ram) AndroidRow("RAM", ram, color, size)
        }
    }
}

@Composable
fun AndroidRow(label: String, value: String, color: Color, size: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "$label ", color = Color.LightGray, fontSize = (size * 0.7f).sp)
        Text(text = value, color = color, fontSize = size.sp, fontWeight = FontWeight.Bold)
    }
}

// --- STYLE 2: PC MODE ---
@Composable
fun PcStyleOverlay(
    size: Float, m: MetricsState,
    fps: String, cpu: String, watt: String, temp: String, ramMb: String,
    renderLabel: String,
    gpuUsage: String, cpuTemp: String, cpuFreq: String, gpuFreq: String, gpuTemp: String
) {
    val font = FontFamily.Monospace
    val green = Color(0xFF00FF00)
    val blue = Color(0xFF00BFFF)
    val orange = Color(0xFFFF8C00)
    val white = Color.White
    
    val finalFpsLabel = if (m.showRender) renderLabel else "FPS"

    Column {
        if (m.gpuUsage || m.gpuFreq || m.gpuTemp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("GPU", color = green, fontSize = size.sp, fontFamily = font, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(16.dp))
                val text = buildString {
                    if (m.gpuUsage) append(gpuUsage)
                    if (m.gpuFreq) {
                        if (isNotEmpty()) append(" ")
                        append(gpuFreq)
                    }
                    if (m.gpuTemp) {
                        if (isNotEmpty()) append(" ")
                        append(gpuTemp)
                    }
                }
                Text(text, color = orange, fontSize = size.sp, fontFamily = font, fontWeight = FontWeight.Bold)
            }
        }
        if (m.cpu || m.cpuFreq || m.cpuTemp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("CPU", color = blue, fontSize = size.sp, fontFamily = font, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(16.dp))
                val text = buildString {
                    if (m.cpu) append(cpu)
                    if (m.cpuFreq) {
                        if (isNotEmpty()) append(" ")
                        append(cpuFreq)
                    }
                    if (m.cpuTemp) {
                        if (isNotEmpty()) append(" ")
                        append(cpuTemp)
                    }
                }
                Text(text, color = orange, fontSize = size.sp, fontFamily = font, fontWeight = FontWeight.Bold)
            }
        }
        if (m.ram) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("MEM", color = green, fontSize = size.sp, fontFamily = font, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(16.dp))
                Text("$ramMb MB", color = orange, fontSize = size.sp, fontFamily = font, fontWeight = FontWeight.Bold)
            }
        }
        if (m.watt || m.temp) {
             Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("BAT", color = blue, fontSize = size.sp, fontFamily = font, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(16.dp))
                val text = if(m.temp && m.watt) "$temp $watt" else if(m.temp) temp else watt
                Text(text, color = orange, fontSize = size.sp, fontFamily = font, fontWeight = FontWeight.Bold)
            }
        }
        if (m.fps) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(finalFpsLabel, color = Color(0xFFECA3A3), fontSize = size.sp, fontFamily = font)
                Spacer(Modifier.width(16.dp))
                Text(fps, color = white, fontSize = size.sp, fontFamily = font, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// --- STYLE 3: MINI MONITOR ---
@Composable
fun MiniMonitorOverlay(
    size: Float, m: MetricsState,
    fps: String, cpu: String, temp: String,
    gpuUsage: String, cpuTemp: String, gpuTemp: String,
    renderLabel: String
) {
    val finalFpsLabel = if (m.showRender) renderLabel else "FPS"
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (m.fps) {
            Text(fps, color = Color.White, fontSize = (size * 1.2f).sp, fontWeight = FontWeight.ExtraBold)
            Text(finalFpsLabel, color = Color.Gray, fontSize = (size * 0.6f).sp)
        }
        if (m.cpu || m.temp || m.gpuUsage || m.cpuTemp || m.gpuTemp) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                if (m.cpu) Text(cpu, color = Color(0xFF00BFFF), fontSize = (size * 0.8f).sp, modifier = Modifier.padding(end=4.dp))
                if (m.gpuUsage) Text(gpuUsage, color = Color(0xFF00FF00), fontSize = (size * 0.8f).sp, modifier = Modifier.padding(end=4.dp))
                if (m.cpuTemp) Text(cpuTemp, color = Color(0xFFFF8C00), fontSize = (size * 0.8f).sp, modifier = Modifier.padding(end=4.dp))
                if (m.gpuTemp) Text(gpuTemp, color = Color(0xFF32CD32), fontSize = (size * 0.8f).sp, modifier = Modifier.padding(end=4.dp))
                if (m.temp) Text(temp, color = Color(0xFFFF4500), fontSize = (size * 0.8f).sp)
            }
        }
    }
}
