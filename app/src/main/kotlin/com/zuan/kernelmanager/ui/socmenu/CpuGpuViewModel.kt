/*
 * Copyright (c) 2025 ZKM
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.zuan.kernelmanager.ui.socmenu

import android.app.Application
import android.widget.Toast
import com.zuan.kernelmanager.R
import androidx.lifecycle.AndroidViewModel
import com.topjohnwu.superuser.Shell
import androidx.lifecycle.viewModelScope
import com.zuan.kernelmanager.ui.settings.SettingsPreference
import com.zuan.kernelmanager.utils.GenericGpuUtils
import com.zuan.kernelmanager.utils.Utils
import com.zuan.kernelmanager.utils.AdrenoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class CpuGpuViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsPreference = SettingsPreference.getInstance(application)

    // Data Classes
    data class CPUState(
        val name: String, val policyPath: String, val minFreq: String, val maxFreq: String,
        val currentFreq: String, val gov: String, val availableFreq: List<String>, 
        val availableGov: List<String>, val isPrime: Boolean = false,
        // List core yang tergabung di cluster ini beserta statusnya
        val associatedCores: List<CoreStatus> = emptyList()
    ) 

    data class GPUState(
        val type: CpuGpuUtils.GpuType, 
        val currentFreq: String,
        val usage: String
    ) { 
        companion object { val EMPTY = GPUState(CpuGpuUtils.GpuType.UNKNOWN, "N/A", "0") } 
    }

    data class CpuClusterSetting(
        val policyPath: String,
        val minFreq: String,
        val maxFreq: String,
        val governor: String,
        val tunables: Map<String, String>? = null
    )

    data class GpuSetting(
        val type: String,
        val currentFreq: String,
        val minFreq: String? = null,
        val maxFreq: String? = null,
        val governor: String? = null,
        val throttling: String? = null,
        val adrenoBoost: String? = null,
        // Advanced Adreno Settings
        val idlerActive: String? = null,
        val idlerIdleWait: String? = null,
        val idlerDownDiff: String? = null,
        val idlerWorkload: String? = null,
        val simpleGpuActive: String? = null,
        val simpleLaziness: String? = null,
        val simpleRampThreshold: String? = null,
        val forceNoNap: String? = null,
        val forceClkOn: String? = null,
        val forceBusOn: String? = null,
        val busSplit: String? = null,
        val defaultPwrlevel: String? = null,
        val maxPwrlevel: String? = null,
        val thermalPwrlevel: String? = null
    )

    data class CpuGpuProfile(
        val name: String,
        val cpuSettings: List<CpuClusterSetting>,
        val gpuSetting: GpuSetting? = null,
        val cpusets: Map<String, String>? = null
    )

    // Flows
    private val _socName = MutableStateFlow("Processor")
    val socName: StateFlow<String> = _socName

    private val _clusterStates = MutableStateFlow<List<CPUState>>(emptyList())
    val clusterStates: StateFlow<List<CPUState>> = _clusterStates
    
    private val _gpuState = MutableStateFlow(GPUState.EMPTY)
    val gpuState: StateFlow<GPUState> = _gpuState

    private val _govTunables = MutableStateFlow<List<GovTunable>>(emptyList())
    val govTunables: StateFlow<List<GovTunable>> = _govTunables

    // Cpuset Flow
    private val _cpusetList = MutableStateFlow<List<CpusetData>>(emptyList())
    val cpusetList: StateFlow<List<CpusetData>> = _cpusetList

    private val _profiles = MutableStateFlow<List<CpuGpuProfile>>(emptyList())
    val profiles: StateFlow<List<CpuGpuProfile>> = _profiles

    private var job: Job? = null

    init {
        loadStaticInfo()
        loadProfiles()
        startJob()
    }

    private fun loadProfiles() {
        viewModelScope.launch {
            val json = settingsPreference.cpuGpuProfilesJson.value
            _profiles.value = parseProfilesJson(json)
        }
    }

    private fun saveProfilesToPrefs() {
        val json = profilesToJson(_profiles.value)
        settingsPreference.setCpuGpuProfilesJson(json)
    }

    private fun parseProfilesJson(json: String): List<CpuGpuProfile> {
        val list = mutableListOf<CpuGpuProfile>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.getString("name")
                
                // CPU
                val cpuArray = obj.getJSONArray("cpuSettings")
                val cpuSettings = mutableListOf<CpuClusterSetting>()
                for (j in 0 until cpuArray.length()) {
                    val cpuObj = cpuArray.getJSONObject(j)
                    val tunables = if (cpuObj.has("tunables")) jsonToMap(cpuObj.getJSONObject("tunables")) else null
                    cpuSettings.add(CpuClusterSetting(
                        cpuObj.getString("policyPath"),
                        cpuObj.getString("minFreq"),
                        cpuObj.getString("maxFreq"),
                        cpuObj.getString("governor"),
                        tunables
                    ))
                }
                
                // GPU
                val gpuSetting = if (obj.has("gpuSetting") && !obj.isNull("gpuSetting")) {
                    val gpuObj = obj.getJSONObject("gpuSetting")
                    GpuSetting(
                        type = gpuObj.getString("type"),
                        currentFreq = gpuObj.getString("currentFreq"),
                        minFreq = if (gpuObj.has("minFreq")) gpuObj.getString("minFreq") else null,
                        maxFreq = if (gpuObj.has("maxFreq")) gpuObj.getString("maxFreq") else null,
                        governor = if (gpuObj.has("governor")) gpuObj.getString("governor") else null,
                        throttling = if (gpuObj.has("throttling")) gpuObj.getString("throttling") else null,
                        adrenoBoost = if (gpuObj.has("adrenoBoost")) gpuObj.getString("adrenoBoost") else null,
                        idlerActive = if (gpuObj.has("idlerActive")) gpuObj.getString("idlerActive") else null,
                        idlerIdleWait = if (gpuObj.has("idlerIdleWait")) gpuObj.getString("idlerIdleWait") else null,
                        idlerDownDiff = if (gpuObj.has("idlerDownDiff")) gpuObj.getString("idlerDownDiff") else null,
                        idlerWorkload = if (gpuObj.has("idlerWorkload")) gpuObj.getString("idlerWorkload") else null,
                        simpleGpuActive = if (gpuObj.has("simpleGpuActive")) gpuObj.getString("simpleGpuActive") else null,
                        simpleLaziness = if (gpuObj.has("simpleLaziness")) gpuObj.getString("simpleLaziness") else null,
                        simpleRampThreshold = if (gpuObj.has("simpleRampThreshold")) gpuObj.getString("simpleRampThreshold") else null,
                        forceNoNap = if (gpuObj.has("forceNoNap")) gpuObj.getString("forceNoNap") else null,
                        forceClkOn = if (gpuObj.has("forceClkOn")) gpuObj.getString("forceClkOn") else null,
                        forceBusOn = if (gpuObj.has("forceBusOn")) gpuObj.getString("forceBusOn") else null,
                        busSplit = if (gpuObj.has("busSplit")) gpuObj.getString("busSplit") else null,
                        defaultPwrlevel = if (gpuObj.has("defaultPwrlevel")) gpuObj.getString("defaultPwrlevel") else null,
                        maxPwrlevel = if (gpuObj.has("maxPwrlevel")) gpuObj.getString("maxPwrlevel") else null,
                        thermalPwrlevel = if (gpuObj.has("thermalPwrlevel")) gpuObj.getString("thermalPwrlevel") else null
                    )
                } else null

                list.add(CpuGpuProfile(name, cpuSettings, gpuSetting, if (obj.has("cpusets")) jsonToMap(obj.optJSONObject("cpusets")) else null))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    private fun profilesToJson(profiles: List<CpuGpuProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            val obj = JSONObject()
            obj.put("name", profile.name)
            
            // CPU
            val cpuArray = JSONArray()
            profile.cpuSettings.forEach { cpu ->
                val cpuObj = JSONObject()
                cpuObj.put("policyPath", cpu.policyPath)
                cpuObj.put("minFreq", cpu.minFreq)
                cpuObj.put("maxFreq", cpu.maxFreq)
                cpuObj.put("governor", cpu.governor)
                cpu.tunables?.let { cpuObj.put("tunables", mapToJson(it)) }
                cpuArray.put(cpuObj)
            }
            obj.put("cpuSettings", cpuArray)
            
            // GPU
            profile.gpuSetting?.let { gpu ->
                val gpuObj = JSONObject()
                gpuObj.put("type", gpu.type)
                gpuObj.put("currentFreq", gpu.currentFreq)
                gpu.minFreq?.let { gpuObj.put("minFreq", it) }
                gpu.maxFreq?.let { gpuObj.put("maxFreq", it) }
                gpu.governor?.let { gpuObj.put("governor", it) }
                gpu.throttling?.let { gpuObj.put("throttling", it) }
                gpu.adrenoBoost?.let { gpuObj.put("adrenoBoost", it) }
                gpu.idlerActive?.let { gpuObj.put("idlerActive", it) }
                gpu.idlerIdleWait?.let { gpuObj.put("idlerIdleWait", it) }
                gpu.idlerDownDiff?.let { gpuObj.put("idlerDownDiff", it) }
                gpu.idlerWorkload?.let { gpuObj.put("idlerWorkload", it) }
                gpu.simpleGpuActive?.let { gpuObj.put("simpleGpuActive", it) }
                gpu.simpleLaziness?.let { gpuObj.put("simpleLaziness", it) }
                gpu.simpleRampThreshold?.let { gpuObj.put("simpleRampThreshold", it) }
                gpu.forceNoNap?.let { gpuObj.put("forceNoNap", it) }
                gpu.forceClkOn?.let { gpuObj.put("forceClkOn", it) }
                gpu.forceBusOn?.let { gpuObj.put("forceBusOn", it) }
                gpu.busSplit?.let { gpuObj.put("busSplit", it) }
                gpu.defaultPwrlevel?.let { gpuObj.put("defaultPwrlevel", it) }
                gpu.maxPwrlevel?.let { gpuObj.put("maxPwrlevel", it) }
                gpu.thermalPwrlevel?.let { gpuObj.put("thermalPwrlevel", it) }
                obj.put("gpuSetting", gpuObj)
            }
            
            profile.cpusets?.let { obj.put("cpusets", mapToJson(it)) }

            array.put(obj)
        }
        return array.toString()
    }

    private fun mapToJson(map: Map<String, String>): JSONObject {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj
    }

    private fun jsonToMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = obj.getString(key)
        }
        return map
    }

    private fun loadStaticInfo() {
        viewModelScope.launch {
            _socName.value = CpuGpuUtils.getCpuInfo()
        }
    }

    fun startJob() {
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            settingsPreference.pollingInterval.collect { interval ->
                while (true) {
                    loadDynamicCPUData()
                    loadGPUData()
                    loadCpusetData()
                    delay(interval)
                }
            }
        }
    }

    fun stopJob() { job?.cancel(); job = null }

    // --- Loaders ---
    private suspend fun loadDynamicCPUData() {
        val policies = CpuGpuUtils.getCpuPolicies() 
        if (policies.isEmpty()) return

        val rawData = policies.map { path ->
            val maxFreq = CpuGpuUtils.readFreq(path, "scaling_max_freq").toIntOrNull() ?: 0
            Triple(path, maxFreq, CpuGpuUtils.readAvailableFreq(path))
        }

        val sortedByFreq = rawData.sortedBy { it.second } 
        val minMaxFreq = sortedByFreq.first().second
        val maxMaxFreq = sortedByFreq.last().second

        val newStates = rawData.mapIndexed { index, (path, maxFreq, availFreq) ->
            val name = when {
                rawData.size == 1 -> "CPU Cluster" 
                maxFreq == minMaxFreq -> "Little Cluster"
                maxFreq == maxMaxFreq -> if (rawData.size > 2) "Prime Cluster" else "Big Cluster"
                else -> "Big Cluster" 
            }
            val isPrime = name.contains("Prime") || (name.contains("Big") && rawData.size == 2 && index == 1)
            
            // Load status per-core (Online/Offline)
            val affectedCpus = CpuGpuUtils.getAffectedCpus(path)
            val coreStatuses = affectedCpus.map { id ->
                CoreStatus(id, CpuGpuUtils.getCoreStatus(id))
            }
            
            CPUState(
                name = name, policyPath = path,
                minFreq = CpuGpuUtils.readFreq(path, "scaling_min_freq"),
                maxFreq = CpuGpuUtils.readFreq(path, "scaling_max_freq"),
                currentFreq = CpuGpuUtils.readFreq(path, "scaling_cur_freq"),
                gov = CpuGpuUtils.readGovernor(path),
                availableFreq = availFreq,
                availableGov = CpuGpuUtils.readAvailableGov(path),
                isPrime = isPrime,
                associatedCores = coreStatuses
            )
        }.sortedBy { it.policyPath }
        _clusterStates.value = newStates
    }

    private suspend fun loadGPUData() {
        val type = CpuGpuUtils.getGpuType()
        val usage = CpuGpuUtils.getGpuUsage() 
        
        if (type == CpuGpuUtils.GpuType.ADRENO) {
            val freq = CpuGpuUtils.readFreqGPU(CpuGpuUtils.CURRENT_FREQ_GPU)
            _gpuState.value = GPUState(type, freq, usage)
        } else {
            _gpuState.value = GPUState(type, "Dynamic", usage)
        }
    }

    private suspend fun loadCpusetData() {
        val data = CpuGpuUtils.getCpusetInfo()
        _cpusetList.value = data
    }

    // --- Actions ---
    
    fun updateFreq(target: String, selectedFreq: String, policyPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = if (target == "min") "scaling_min_freq" else "scaling_max_freq"
            CpuGpuUtils.writeFreq(policyPath, file, selectedFreq)
            saveIndividualCpuSetting(policyPath, target, selectedFreq)
            loadDynamicCPUData() 
        }
    }

    fun updateGov(selectedGov: String, policyPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            CpuGpuUtils.writeGov(policyPath, selectedGov)
            saveIndividualCpuSetting(policyPath, "governor", selectedGov)
            loadDynamicCPUData()
            loadGovTunables(policyPath, selectedGov)
        }
    }

    private fun saveIndividualCpuSetting(policyPath: String, key: String, value: String) {
        try {
            val json = settingsPreference.individualCpuSettingsJson.value
            val root = JSONObject(json)
            val clusterObj = if (root.has(policyPath)) root.getJSONObject(policyPath) else JSONObject()
            clusterObj.put(key, value)
            root.put(policyPath, clusterObj)
            settingsPreference.setIndividualCpuSettingsJson(root.toString())
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun loadGovTunables(policyPath: String, governor: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val tunables = CpuGpuUtils.getGovernorTunables(policyPath, governor)
            _govTunables.value = tunables
        }
    }

    fun applyTunable(tunable: GovTunable, newValue: String) {
        viewModelScope.launch(Dispatchers.IO) {
            CpuGpuUtils.writeTunable(tunable.path, newValue)
            val currentList = _govTunables.value.map { 
                if (it.path == tunable.path) it.copy(value = newValue) else it 
            }
            _govTunables.value = currentList
        }
    }

    // Toggle Core Action
    fun toggleCore(coreId: Int, currentStatus: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (coreId == 0) return@launch // Core 0 is sacred
            
            // Safety check: jika ingin mematikan, pastikan sisa core > 1
            if (currentStatus) {
                val activeCount = CpuGpuUtils.getTotalOnlineCores()
                if (activeCount <= 1) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "Safety Halt: Cannot disable all cores.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
            }

            CpuGpuUtils.setCoreOnline(coreId, !currentStatus)
            loadDynamicCPUData()
        }
    }

    // Update Cpuset Action
    fun updateCpuset(cpusetPath: String, selectedCores: List<Int>) {
        viewModelScope.launch(Dispatchers.IO) {
            if (selectedCores.isEmpty()) {
                 withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Error: Cpuset must have at least 1 core.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            CpuGpuUtils.applyCpuset(cpusetPath, selectedCores)
            loadCpusetData()
        }
    }

    // --- Profile Actions ---

    fun saveCurrentToProfile(name: String) {
        // Pre-insert an entry with the name immediately on the UI thread for instant UX feedback
        val initialList = _profiles.value.toMutableList()
        val preIndex = initialList.indexOfFirst { it.name == name }
        if (preIndex == -1) {
            initialList.add(CpuGpuProfile(name, emptyList(), null))
            _profiles.value = initialList
        }

        viewModelScope.launch(Dispatchers.IO) {
            val currentCpu = _clusterStates.value.map { cluster ->
                val tunables = CpuGpuUtils.getGovernorTunables(cluster.policyPath, cluster.gov)
                    .associate { it.name to it.value }
                CpuClusterSetting(cluster.policyPath, cluster.minFreq, cluster.maxFreq, cluster.gov, tunables)
            }
            val currentGpu = if (_gpuState.value.type != CpuGpuUtils.GpuType.UNKNOWN) {
                val type = _gpuState.value.type
                if (type == CpuGpuUtils.GpuType.ADRENO) {
                    GpuSetting(
                        type = type.name,
                        currentFreq = _gpuState.value.currentFreq,
                        minFreq = AdrenoUtils.readFreqGPU("/sys/class/kgsl/kgsl-3d0/min_clock_mhz"),
                        maxFreq = AdrenoUtils.readFreqGPU("/sys/class/kgsl/kgsl-3d0/max_clock_mhz"),
                        governor = Utils.readFile("/sys/class/kgsl/kgsl-3d0/devfreq/governor"),
                        throttling = Utils.readFile("/sys/class/kgsl/kgsl-3d0/throttling"),
                        adrenoBoost = Utils.readFile("/sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost"),
                        idlerActive = Utils.readFile(AdrenoUtils.IDLER_ACTIVE),
                        idlerIdleWait = Utils.readFile(AdrenoUtils.IDLER_IDLEWAIT),
                        idlerDownDiff = Utils.readFile(AdrenoUtils.IDLER_DOWNDIFF),
                        idlerWorkload = Utils.readFile(AdrenoUtils.IDLER_WORKLOAD),
                        simpleGpuActive = Utils.readFile(AdrenoUtils.SIMPLE_GPU_ACTIVATE),
                        simpleLaziness = Utils.readFile(AdrenoUtils.SIMPLE_GPU_LAZINESS),
                        simpleRampThreshold = Utils.readFile(AdrenoUtils.SIMPLE_RAMP_THRESHOLD),
                        forceNoNap = Utils.readFile("${AdrenoUtils.KGSL_3D0_DIR}/force_no_nap"),
                        forceClkOn = Utils.readFile("${AdrenoUtils.KGSL_3D0_DIR}/force_clk_on"),
                        forceBusOn = Utils.readFile("${AdrenoUtils.KGSL_3D0_DIR}/force_bus_on"),
                        busSplit = Utils.readFile("${AdrenoUtils.KGSL_3D0_DIR}/bus_split"),
                        defaultPwrlevel = Utils.readFile("${AdrenoUtils.KGSL_3D0_DIR}/default_pwrlevel"),
                        maxPwrlevel = Utils.readFile("${AdrenoUtils.KGSL_3D0_DIR}/max_pwrlevel"),
                        thermalPwrlevel = Utils.readFile("${AdrenoUtils.KGSL_3D0_DIR}/thermal_pwrlevel")
                    )
                } else if (type == CpuGpuUtils.GpuType.GENERIC_DEVFREQ) {
                    val path = GenericGpuUtils.getGpuPath()
                    GpuSetting(
                        type = type.name,
                        currentFreq = _gpuState.value.currentFreq,
                        minFreq = path?.let { GenericGpuUtils.getMinFreq(it) },
                        maxFreq = path?.let { GenericGpuUtils.getMaxFreq(it) },
                        governor = path?.let { GenericGpuUtils.getGov(it) }
                    )
                } else {
                    GpuSetting(type.name, _gpuState.value.currentFreq)
                }
            } else null
            
            val cpusets = _cpusetList.value.associate { it.key to it.value }
            
            val newProfile = CpuGpuProfile(name, currentCpu, currentGpu, cpusets)
            val newList = _profiles.value.toMutableList()
            // Check if exists, replace or add
            val index = newList.indexOfFirst { it.name == name }
            if (index != -1) newList[index] = newProfile else newList.add(newProfile)
            
            _profiles.value = newList
            saveProfilesToPrefs()
            
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), getApplication<Application>().getString(R.string.profile_save_success, name), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun applyProfile(profile: CpuGpuProfile, isAutoApply: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            // CPU
            profile.cpuSettings.forEach { cpu ->
                CpuGpuUtils.writeFreq(cpu.policyPath, "scaling_min_freq", cpu.minFreq)
                CpuGpuUtils.writeFreq(cpu.policyPath, "scaling_max_freq", cpu.maxFreq)
                CpuGpuUtils.writeGov(cpu.policyPath, cpu.governor)
                cpu.tunables?.forEach { (name, value) ->
                    val path = "${cpu.policyPath}/${cpu.governor}/$name"
                    CpuGpuUtils.writeTunable(path, value)
                }
            }
            
            // GPU
            profile.gpuSetting?.let { gpu ->
                if (gpu.type == CpuGpuUtils.GpuType.ADRENO.name) {
                    if (!gpu.minFreq.isNullOrEmpty()) {
                        AdrenoUtils.writeFreqGPU("/sys/class/kgsl/kgsl-3d0/min_clock_mhz", gpu.minFreq)
                    }
                    if (!gpu.maxFreq.isNullOrEmpty()) {
                        AdrenoUtils.writeFreqGPU("/sys/class/kgsl/kgsl-3d0/max_clock_mhz", gpu.maxFreq)
                    }
                    if (!gpu.governor.isNullOrEmpty()) {
                        AdrenoUtils.writeData("/sys/class/kgsl/kgsl-3d0/devfreq/governor", gpu.governor)
                    }
                    if (!gpu.throttling.isNullOrEmpty()) {
                        AdrenoUtils.writeData("/sys/class/kgsl/kgsl-3d0/throttling", gpu.throttling)
                    }
                    if (!gpu.adrenoBoost.isNullOrEmpty()) {
                        AdrenoUtils.writeData(AdrenoUtils.ADRENO_BOOST, gpu.adrenoBoost)
                    }
                    
                    // Advanced Adreno Settings
                    if (!gpu.idlerActive.isNullOrEmpty()) AdrenoUtils.writeData(AdrenoUtils.IDLER_ACTIVE, gpu.idlerActive)
                    if (!gpu.idlerIdleWait.isNullOrEmpty()) AdrenoUtils.writeData(AdrenoUtils.IDLER_IDLEWAIT, gpu.idlerIdleWait)
                    if (!gpu.idlerDownDiff.isNullOrEmpty()) AdrenoUtils.writeData(AdrenoUtils.IDLER_DOWNDIFF, gpu.idlerDownDiff)
                    if (!gpu.idlerWorkload.isNullOrEmpty()) AdrenoUtils.writeData(AdrenoUtils.IDLER_WORKLOAD, gpu.idlerWorkload)
                    
                    if (!gpu.simpleGpuActive.isNullOrEmpty()) AdrenoUtils.writeData(AdrenoUtils.SIMPLE_GPU_ACTIVATE, gpu.simpleGpuActive)
                    if (!gpu.simpleLaziness.isNullOrEmpty()) AdrenoUtils.writeData(AdrenoUtils.SIMPLE_GPU_LAZINESS, gpu.simpleLaziness)
                    if (!gpu.simpleRampThreshold.isNullOrEmpty()) AdrenoUtils.writeData(AdrenoUtils.SIMPLE_RAMP_THRESHOLD, gpu.simpleRampThreshold)
                    
                    if (!gpu.forceNoNap.isNullOrEmpty()) AdrenoUtils.writeData("${AdrenoUtils.KGSL_3D0_DIR}/force_no_nap", gpu.forceNoNap)
                    if (!gpu.forceClkOn.isNullOrEmpty()) AdrenoUtils.writeData("${AdrenoUtils.KGSL_3D0_DIR}/force_clk_on", gpu.forceClkOn)
                    if (!gpu.forceBusOn.isNullOrEmpty()) AdrenoUtils.writeData("${AdrenoUtils.KGSL_3D0_DIR}/force_bus_on", gpu.forceBusOn)
                    if (!gpu.busSplit.isNullOrEmpty()) AdrenoUtils.writeData("${AdrenoUtils.KGSL_3D0_DIR}/bus_split", gpu.busSplit)
                    
                    if (!gpu.defaultPwrlevel.isNullOrEmpty()) AdrenoUtils.writeData("${AdrenoUtils.KGSL_3D0_DIR}/default_pwrlevel", gpu.defaultPwrlevel)
                    if (!gpu.maxPwrlevel.isNullOrEmpty()) AdrenoUtils.writeData("${AdrenoUtils.KGSL_3D0_DIR}/max_pwrlevel", gpu.maxPwrlevel)
                    if (!gpu.thermalPwrlevel.isNullOrEmpty()) AdrenoUtils.writeData("${AdrenoUtils.KGSL_3D0_DIR}/thermal_pwrlevel", gpu.thermalPwrlevel)

                    AdrenoUtils.writeFreqGPU("/sys/class/kgsl/kgsl-3d0/gpuclk", gpu.currentFreq)
                } else if (gpu.type == CpuGpuUtils.GpuType.GENERIC_DEVFREQ.name) {
                    GenericGpuUtils.getGpuPath()?.let { path ->
                        if (!gpu.minFreq.isNullOrEmpty()) GenericGpuUtils.setFreq(path, "min", gpu.minFreq)
                        if (!gpu.maxFreq.isNullOrEmpty()) GenericGpuUtils.setFreq(path, "max", gpu.maxFreq)
                        if (!gpu.governor.isNullOrEmpty()) GenericGpuUtils.setGov(path, gpu.governor)
                        GenericGpuUtils.setFreq(path, "max", gpu.currentFreq)
                    }
                }
            }
            
            profile.cpusets?.forEach { (key, value) ->
                val path = "/dev/cpuset/$key/cpus"
                if (Utils.testFile(path)) Shell.cmd("echo \"$value\" > $path").exec()
            }
            
            loadDynamicCPUData()
            loadGPUData()
            loadCpusetData()
            
            settingsPreference.setSelectedCpuProfileName(profile.name)
            
            withContext(Dispatchers.Main) {
                val format = if (isAutoApply) R.string.profile_auto_apply_success else R.string.profile_apply_success
                Toast.makeText(getApplication(), getApplication<Application>().getString(format, profile.name), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun deleteProfile(profile: CpuGpuProfile) {
        val newList = _profiles.value.filter { it.name != profile.name }
        _profiles.value = newList
        saveProfilesToPrefs()
        
        // Clear selected profile if it was deleted
        if (settingsPreference.selectedCpuProfileName.value == profile.name) {
            settingsPreference.setSelectedCpuProfileName(null)
        }
    }

    fun renameProfile(profile: CpuGpuProfile, newName: String) {
        val newList = _profiles.value.map { 
            if (it.name == profile.name) it.copy(name = newName) else it 
        }
        _profiles.value = newList
        saveProfilesToPrefs()
        
        // Update selected profile name if it was renamed
        if (settingsPreference.selectedCpuProfileName.value == profile.name) {
            settingsPreference.setSelectedCpuProfileName(newName)
        }
    }
}
