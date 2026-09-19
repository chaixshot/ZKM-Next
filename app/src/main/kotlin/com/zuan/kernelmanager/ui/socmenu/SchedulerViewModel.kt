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
import com.zuan.kernelmanager.R
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

class SchedulerViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsPreference = SettingsPreference.getInstance(application)

    // --- State Classes ---
    data class SchedState(
        val schedAutogroup: String = "0", val hasSchedAutogroup: Boolean = false,
        val childRunsFirst: String = "0", val hasChildRunsFirst: Boolean = false,
        val cstateAware: String = "0", val hasCstateAware: Boolean = false,
        val schedStats: String = "0", val hasSchedStats: Boolean = false,
        val tunableScaling: String = "0", val hasTunableScaling: Boolean = false // [NEW]
    )

    data class BoreState(val hasBore: Boolean = false, val bore: Int = 0)
    
    data class UclampState(
        val hasUclampMax: Boolean = false, val uclampMax: String = "N/A",
        val hasUclampMin: Boolean = false, val uclampMin: String = "N/A"
    )

    // Data class for dynamic items
    data class TunableItem(
        val name: String,
        val path: String,
        val value: String
    )

    data class SchedulerProfile(
        val name: String,
        val toggles: Map<String, String>,
        val bore: Int?,
        val uclamp: Map<String, String>,
        val genericTunables: Map<String, String>
    )

    // --- State Flows ---
    private val _sched = MutableStateFlow(SchedState())
    val sched: StateFlow<SchedState> = _sched

    private val _bore = MutableStateFlow(BoreState())
    val bore: StateFlow<BoreState> = _bore

    private val _uclamp = MutableStateFlow(UclampState())
    val uclamp: StateFlow<UclampState> = _uclamp

    private val _genericTunables = MutableStateFlow<List<TunableItem>>(emptyList())
    val genericTunables: StateFlow<List<TunableItem>> = _genericTunables

    private val _profiles = MutableStateFlow<List<SchedulerProfile>>(emptyList())
    val profiles: StateFlow<List<SchedulerProfile>> = _profiles

    init {
        refreshData()
        loadProfiles()
    }

    private fun loadProfiles() {
        viewModelScope.launch {
            val json = settingsPreference.schedProfilesJson.value
            _profiles.value = parseProfilesJson(json)
        }
    }

    private fun saveProfilesToPrefs() {
        val json = profilesToJson(_profiles.value)
        settingsPreference.setSchedProfilesJson(json)
    }

    private fun parseProfilesJson(json: String): List<SchedulerProfile> {
        val list = mutableListOf<SchedulerProfile>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(SchedulerProfile(
                    name = obj.getString("name"),
                    toggles = jsonToMap(obj.optJSONObject("toggles")),
                    bore = if (obj.has("bore") && !obj.isNull("bore")) obj.getInt("bore") else null,
                    uclamp = jsonToMap(obj.optJSONObject("uclamp")),
                    genericTunables = jsonToMap(obj.optJSONObject("genericTunables"))
                ))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    private fun profilesToJson(profiles: List<SchedulerProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            val obj = JSONObject()
            obj.put("name", profile.name)
            obj.put("toggles", mapToJson(profile.toggles))
            profile.bore?.let { obj.put("bore", it) }
            obj.put("uclamp", mapToJson(profile.uclamp))
            obj.put("genericTunables", mapToJson(profile.genericTunables))
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

    fun refreshData() {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Refresh Feature Toggles
            _sched.value = SchedState(
                schedAutogroup = Utils.readFile(SchedulerUtils.SCHED_AUTO_GROUP).trim(),
                hasSchedAutogroup = Utils.testFile(SchedulerUtils.SCHED_AUTO_GROUP),
                childRunsFirst = Utils.readFile(SchedulerUtils.SCHED_CHILD_RUNS_FIRST).trim(),
                hasChildRunsFirst = Utils.testFile(SchedulerUtils.SCHED_CHILD_RUNS_FIRST),
                cstateAware = Utils.readFile(SchedulerUtils.SCHED_CSTATE_AWARE).trim(),
                hasCstateAware = Utils.testFile(SchedulerUtils.SCHED_CSTATE_AWARE),
                schedStats = Utils.readFile(SchedulerUtils.SCHED_SCHEDSTATS).trim(),
                hasSchedStats = Utils.testFile(SchedulerUtils.SCHED_SCHEDSTATS),
                tunableScaling = Utils.readFile(SchedulerUtils.SCHED_TUNABLE_SCALING).trim(),
                hasTunableScaling = Utils.testFile(SchedulerUtils.SCHED_TUNABLE_SCALING)
            )

            _bore.value = BoreState(
                hasBore = Utils.testFile(SchedulerUtils.BORE),
                bore = Utils.readFile(SchedulerUtils.BORE).trim().toIntOrNull() ?: 0
            )

            _uclamp.value = UclampState(
                hasUclampMax = Utils.testFile(SchedulerUtils.SCHED_UTIL_CLAMP_MAX),
                uclampMax = Utils.readFile(SchedulerUtils.SCHED_UTIL_CLAMP_MAX).trim(),
                hasUclampMin = Utils.testFile(SchedulerUtils.SCHED_UTIL_CLAMP_MIN),
                uclampMin = Utils.readFile(SchedulerUtils.SCHED_UTIL_CLAMP_MIN).trim()
            )

            // 2. Refresh Dynamic Tunables
            val validTunables = mutableListOf<TunableItem>()
            SchedulerUtils.GENERIC_SCHED_TUNABLES.forEach { (name, path) ->
                if (Utils.testFile(path)) { 
                    val content = Utils.readFile(path).trim()
                    if (content.isNotEmpty()) {
                         validTunables.add(TunableItem(name, path, content))
                    }
                }
            }
            _genericTunables.value = validTunables
        }
    }

    fun updateValue(path: String, value: String) {
        viewModelScope.launch(Dispatchers.IO) {
            Utils.writeFile(path, value)
            refreshData()
        }
    }

    // --- Profile Actions ---

    fun saveCurrentToProfile(name: String) {
        val initialList = _profiles.value.toMutableList()
        val preIndex = initialList.indexOfFirst { it.name == name }
        if (preIndex == -1) {
            initialList.add(SchedulerProfile(name, emptyMap(), null, emptyMap(), emptyMap()))
            _profiles.value = initialList
        }
        viewModelScope.launch(Dispatchers.IO) {
            val toggles = mutableMapOf<String, String>()
            listOf(
                SchedulerUtils.SCHED_AUTO_GROUP,
                SchedulerUtils.SCHED_CHILD_RUNS_FIRST,
                SchedulerUtils.SCHED_CSTATE_AWARE,
                SchedulerUtils.SCHED_SCHEDSTATS,
                SchedulerUtils.SCHED_TUNABLE_SCALING
            ).forEach { path ->
                if (Utils.testFile(path)) toggles[path] = Utils.readFile(path)
            }

            val bore = if (Utils.testFile(SchedulerUtils.BORE)) Utils.readFile(SchedulerUtils.BORE).toIntOrNull() else null

            val uclamp = mutableMapOf<String, String>()
            listOf(SchedulerUtils.SCHED_UTIL_CLAMP_MAX, SchedulerUtils.SCHED_UTIL_CLAMP_MIN).forEach { path ->
                if (Utils.testFile(path)) uclamp[path] = Utils.readFile(path)
            }

            val genericTunables = mutableMapOf<String, String>()
            SchedulerUtils.GENERIC_SCHED_TUNABLES.values.forEach { path ->
                if (Utils.testFile(path)) genericTunables[path] = Utils.readFile(path)
            }

            val newProfile = SchedulerProfile(name, toggles, bore, uclamp, genericTunables)
            val newList = _profiles.value.toMutableList()
            val index = newList.indexOfFirst { it.name == name }
            if (index != -1) newList[index] = newProfile else newList.add(newProfile)
            
            _profiles.value = newList
            saveProfilesToPrefs()
            
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), getApplication<Application>().getString(R.string.profile_save_success, name), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun applyProfile(profile: SchedulerProfile, isAutoApply: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            profile.toggles.forEach { (path, value) -> Utils.writeFile(path, value) }
            profile.bore?.let { Utils.writeFile(SchedulerUtils.BORE, it.toString()) }
            profile.uclamp.forEach { (path, value) -> Utils.writeFile(path, value) }
            profile.genericTunables.forEach { (path, value) -> Utils.writeFile(path, value) }
            
            refreshData()
            settingsPreference.setSelectedSchedProfileName(profile.name)
            
            withContext(Dispatchers.Main) {
                val format = if (isAutoApply) R.string.profile_auto_apply_success else R.string.profile_apply_success
                Toast.makeText(getApplication(), getApplication<Application>().getString(format, profile.name), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun deleteProfile(profile: SchedulerProfile) {
        val newList = _profiles.value.filter { it.name != profile.name }
        _profiles.value = newList
        saveProfilesToPrefs()
        if (settingsPreference.selectedSchedProfileName.value == profile.name) {
            settingsPreference.setSelectedSchedProfileName(null)
        }
    }

    fun renameProfile(profile: SchedulerProfile, newName: String) {
        val newList = _profiles.value.map { 
            if (it.name == profile.name) it.copy(name = newName) else it 
        }
        _profiles.value = newList
        saveProfilesToPrefs()
        if (settingsPreference.selectedSchedProfileName.value == profile.name) {
            settingsPreference.setSelectedSchedProfileName(newName)
        }
    }
}
