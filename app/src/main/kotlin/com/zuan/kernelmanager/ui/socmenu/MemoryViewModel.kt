/*
 * Original code from: Rem01Gaming (origami_kernel_manager)
 * Modified and integrated by: Copyright (c) 2025 ZKM
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.zuan.kernelmanager.ui.socmenu

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zuan.kernelmanager.ui.settings.SettingsPreference
import com.zuan.kernelmanager.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.app.Application
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class MemoryViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsPreference = SettingsPreference.getInstance(application)

    // State untuk VM standard
    data class MemoryState(
        val swappiness: String = "0", val hasSwappiness: Boolean = false,
        val vfsCachePressure: String = "0", val hasVfsCachePressure: Boolean = false,
        val dirtyRatio: String = "0", val hasDirtyRatio: Boolean = false,
        val dirtyBackgroundRatio: String = "0", val hasDirtyBackgroundRatio: Boolean = false,
        val minFreeKbytes: String = "0", val hasMinFreeKbytes: Boolean = false,
        val extraFreeKbytes: String = "0", val hasExtraFreeKbytes: Boolean = false
    )

    // State untuk ZRAM
    data class ZramState(
        val sizeMb: Int = 0,
        val activeAlgo: String = "Unknown",
        val availableAlgos: List<String> = emptyList()
    )

    // State untuk I/O Device
    data class IODeviceState(
        val name: String,
        val activeScheduler: String,
        val availableSchedulers: List<String>,
        // Tunables values
        val nrRequests: String = "",
        val readAheadKb: String = "",
        val rqAffinity: String = "",
        val rotational: String = "",
        val addRandom: String = "",
        val iostats: String = ""
    )

    data class MemoryProfile(
        val name: String,
        val vmSettings: Map<String, String>,
        val zramSizeMb: Int?,
        val zramAlgo: String?,
        val ioSchedulers: Map<String, String>,
        val ioTunables: Map<String, Map<String, String>>? = null
    )

    private val _mem = MutableStateFlow(MemoryState())
    val mem: StateFlow<MemoryState> = _mem

    private val _zram = MutableStateFlow(ZramState())
    val zram: StateFlow<ZramState> = _zram

    private val _ioDevices = MutableStateFlow<List<IODeviceState>>(emptyList())
    val ioDevices: StateFlow<List<IODeviceState>> = _ioDevices

    private val _profiles = MutableStateFlow<List<MemoryProfile>>(emptyList())
    val profiles: StateFlow<List<MemoryProfile>> = _profiles

    init {
        refreshAll()
        loadProfiles()
    }

    private fun loadProfiles() {
        viewModelScope.launch {
            val json = settingsPreference.memProfilesJson.value
            _profiles.value = parseProfilesJson(json)
        }
    }

    private fun saveProfilesToPrefs() {
        val json = profilesToJson(_profiles.value)
        settingsPreference.setMemProfilesJson(json)
    }

    private fun parseProfilesJson(json: String): List<MemoryProfile> {
        val list = mutableListOf<MemoryProfile>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(MemoryProfile(
                    name = obj.getString("name"),
                    vmSettings = jsonToMap(obj.optJSONObject("vmSettings")),
                    zramSizeMb = if (obj.has("zramSizeMb") && !obj.isNull("zramSizeMb")) obj.getInt("zramSizeMb") else null,
                    zramAlgo = if (obj.has("zramAlgo") && !obj.isNull("zramAlgo")) obj.getString("zramAlgo") else null,
                    ioSchedulers = jsonToMap(obj.optJSONObject("ioSchedulers")),
                    ioTunables = if (obj.has("ioTunables")) jsonToIoTunables(obj.optJSONObject("ioTunables")) else null
                ))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    private fun profilesToJson(profiles: List<MemoryProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            val obj = JSONObject()
            obj.put("name", profile.name)
            obj.put("vmSettings", mapToJson(profile.vmSettings))
            profile.zramSizeMb?.let { obj.put("zramSizeMb", it) }
            profile.zramAlgo?.let { obj.put("zramAlgo", it) }
            obj.put("ioSchedulers", mapToJson(profile.ioSchedulers))
            profile.ioTunables?.let { obj.put("ioTunables", ioTunablesToJson(it)) }
            array.put(obj)
        }
        return array.toString()
    }

    private fun ioTunablesToJson(map: Map<String, Map<String, String>>): JSONObject {
        val root = JSONObject()
        map.forEach { (dev, tunables) ->
            val devObj = JSONObject()
            tunables.forEach { (k, v) -> devObj.put(k, v) }
            root.put(dev, devObj)
        }
        return root
    }

    private fun jsonToIoTunables(obj: JSONObject?): Map<String, Map<String, String>> {
        if (obj == null) return emptyMap()
        val result = mutableMapOf<String, Map<String, String>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val dev = keys.next()
            val devObj = obj.getJSONObject(dev)
            val tunables = mutableMapOf<String, String>()
            val tKeys = devObj.keys()
            while (tKeys.hasNext()) {
                val tk = tKeys.next()
                tunables[tk] = devObj.getString(tk)
            }
            result[dev] = tunables
        }
        return result
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

    fun refreshAll() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshVmStats()
            refreshZramStats()
            refreshIOStats()
        }
    }

    private fun refreshVmStats() {
        _mem.value = MemoryState(
            swappiness = Utils.readFile(MemoryUtils.SWAPPINESS).trim(),
            hasSwappiness = Utils.testFile(MemoryUtils.SWAPPINESS),
            vfsCachePressure = Utils.readFile(MemoryUtils.VFS_CACHE_PRESSURE).trim(),
            hasVfsCachePressure = Utils.testFile(MemoryUtils.VFS_CACHE_PRESSURE),
            dirtyRatio = Utils.readFile(MemoryUtils.DIRTY_RATIO).trim(),
            hasDirtyRatio = Utils.testFile(MemoryUtils.DIRTY_RATIO),
            dirtyBackgroundRatio = Utils.readFile(MemoryUtils.DIRTY_BACKGROUND_RATIO).trim(),
            hasDirtyBackgroundRatio = Utils.testFile(MemoryUtils.DIRTY_BACKGROUND_RATIO),
            minFreeKbytes = Utils.readFile(MemoryUtils.MIN_FREE_KBYTES).trim(),
            hasMinFreeKbytes = Utils.testFile(MemoryUtils.MIN_FREE_KBYTES),
            extraFreeKbytes = Utils.readFile(MemoryUtils.EXTRA_FREE_KBYTES).trim(),
            hasExtraFreeKbytes = Utils.testFile(MemoryUtils.EXTRA_FREE_KBYTES)
        )
    }

    private fun refreshZramStats() {
        val sizeBytes = MemoryUtils.getZramSizeBytes()
        val sizeMb = (sizeBytes / 1048576L).toInt() // Convert Byte to MB
        val (active, algos) = MemoryUtils.getZramAlgoInfo()
        
        _zram.value = ZramState(
            sizeMb = sizeMb,
            activeAlgo = active,
            availableAlgos = algos
        )
    }

    private fun refreshIOStats() {
        val devices = MemoryUtils.getBlockDevices()
        val newList = devices.map { devName ->
            val (sched, scheds) = MemoryUtils.getIOSchedulerInfo(devName)
            IODeviceState(
                name = devName,
                activeScheduler = sched,
                availableSchedulers = scheds,
                nrRequests = MemoryUtils.getIOTunable(devName, "nr_requests"),
                readAheadKb = MemoryUtils.getIOTunable(devName, "read_ahead_kb"),
                rqAffinity = MemoryUtils.getIOTunable(devName, "rq_affinity"),
                rotational = MemoryUtils.getIOTunable(devName, "rotational"),
                addRandom = MemoryUtils.getIOTunable(devName, "add_random"),
                iostats = MemoryUtils.getIOTunable(devName, "iostats")
            )
        }
        _ioDevices.value = newList
    }

    fun updateVmValue(path: String, value: String) {
        viewModelScope.launch(Dispatchers.IO) {
            Utils.writeFile(path, value)
            refreshVmStats()
        }
    }
    
    // ZRAM Logic
    fun applyZramChanges(targetSizeMb: Int, targetAlgo: String) {
        viewModelScope.launch(Dispatchers.IO) {
            MemoryUtils.swapoff()
            MemoryUtils.resetZram()
            MemoryUtils.setZramCompAlgorithm(targetAlgo)
            val sizeBytes = targetSizeMb * 1048576L
            MemoryUtils.setZramSize(sizeBytes)
            MemoryUtils.mkswap()
            MemoryUtils.swapon()
            refreshZramStats()
        }
    }

    // I/O Logic
    fun setIOScheduler(devName: String, scheduler: String) {
        viewModelScope.launch(Dispatchers.IO) {
            MemoryUtils.setIOScheduler(devName, scheduler)
            refreshIOStats()
        }
    }

    fun setIOTunable(devName: String, key: String, value: String) {
        viewModelScope.launch(Dispatchers.IO) {
            MemoryUtils.setIOTunable(devName, key, value)
            refreshIOStats()
        }
    }

    // --- Profile Actions ---

    fun saveCurrentToProfile(name: String) {
        val initialList = _profiles.value.toMutableList()
        val preIndex = initialList.indexOfFirst { it.name == name }
        if (preIndex == -1) {
            initialList.add(MemoryProfile(name, emptyMap(), null, null, emptyMap()))
            _profiles.value = initialList
        }
        viewModelScope.launch(Dispatchers.IO) {
            val vmSettings = mutableMapOf<String, String>()
            listOf(
                MemoryUtils.SWAPPINESS,
                MemoryUtils.VFS_CACHE_PRESSURE,
                MemoryUtils.DIRTY_RATIO,
                MemoryUtils.DIRTY_BACKGROUND_RATIO,
                MemoryUtils.MIN_FREE_KBYTES,
                MemoryUtils.EXTRA_FREE_KBYTES
            ).forEach { path ->
                if (Utils.testFile(path)) vmSettings[path] = Utils.readFile(path)
            }

            val sizeBytes = MemoryUtils.getZramSizeBytes()
            val zramSizeMb = if (sizeBytes > 0) (sizeBytes / 1048576L).toInt() else null
            val zramAlgo = MemoryUtils.getZramAlgoInfo().first.takeIf { it != "Unknown" }

            val ioScheds = mutableMapOf<String, String>()
            val ioTunables = mutableMapOf<String, Map<String, String>>()
            val tunableKeys = listOf("nr_requests", "read_ahead_kb", "rq_affinity", "rotational", "add_random", "iostats")
            
            MemoryUtils.getBlockDevices().forEach { dev ->
                ioScheds[dev] = MemoryUtils.getIOSchedulerInfo(dev).first
                val devTunables = mutableMapOf<String, String>()
                tunableKeys.forEach { key ->
                    val v = MemoryUtils.getIOTunable(dev, key)
                    if (v.isNotEmpty()) devTunables[key] = v
                }
                ioTunables[dev] = devTunables
            }

            val newProfile = MemoryProfile(name, vmSettings, zramSizeMb, zramAlgo, ioScheds, ioTunables)
            val newList = _profiles.value.toMutableList()
            val index = newList.indexOfFirst { it.name == name }
            if (index != -1) newList[index] = newProfile else newList.add(newProfile)
            
            _profiles.value = newList
            saveProfilesToPrefs()
            
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "Profile '$name' saved.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun applyProfile(profile: MemoryProfile, isAutoApply: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            profile.vmSettings.forEach { (path, value) -> Utils.writeFile(path, value) }
            
            val currentSizeMb = (MemoryUtils.getZramSizeBytes() / 1048576L).toInt()
            val currentAlgo = MemoryUtils.getZramAlgoInfo().first
            if (profile.zramSizeMb != null && profile.zramAlgo != null && 
                (profile.zramSizeMb != currentSizeMb || profile.zramAlgo != currentAlgo)) {
                MemoryUtils.swapoff()
                MemoryUtils.resetZram()
                MemoryUtils.setZramCompAlgorithm(profile.zramAlgo)
                MemoryUtils.setZramSize(profile.zramSizeMb * 1048576L)
                MemoryUtils.mkswap()
                MemoryUtils.swapon()
            }
            profile.ioSchedulers.forEach { (dev, sched) -> MemoryUtils.setIOScheduler(dev, sched) }
            profile.ioTunables?.forEach { (dev, tunables) ->
                tunables.forEach { (key, value) ->
                    MemoryUtils.setIOTunable(dev, key, value)
                }
            }
            
            refreshAll()
            settingsPreference.setSelectedMemProfileName(profile.name)
            
            withContext(Dispatchers.Main) {
                val message = if (isAutoApply) "Auto-applied profile '${profile.name}'" else "Profile '${profile.name}' applied."
                Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun deleteProfile(profile: MemoryProfile) {
        val newList = _profiles.value.filter { it.name != profile.name }
        _profiles.value = newList
        saveProfilesToPrefs()
        if (settingsPreference.selectedMemProfileName.value == profile.name) {
            settingsPreference.setSelectedMemProfileName(null)
        }
    }

    fun renameProfile(profile: MemoryProfile, newName: String) {
        val newList = _profiles.value.map { 
            if (it.name == profile.name) it.copy(name = newName) else it 
        }
        _profiles.value = newList
        saveProfilesToPrefs()
        if (settingsPreference.selectedMemProfileName.value == profile.name) {
            settingsPreference.setSelectedMemProfileName(newName)
        }
    }
}
