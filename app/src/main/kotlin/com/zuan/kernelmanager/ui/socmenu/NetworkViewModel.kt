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

class NetworkViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsPreference = SettingsPreference.getInstance(application)

    data class NetworkState(
        val tcpCongestion: String = "N/A", val availableTcp: List<String> = emptyList(),
        val syncookies: String = "0", val hasSyncookies: Boolean = false,
        val reuse: String = "0", val hasReuse: Boolean = false,
        val fastOpen: String = "0", val hasFastOpen: Boolean = false,
        val sack: String = "0", val hasSack: Boolean = false,
        val ecn: String = "0", val hasEcn: Boolean = false,
        val maxSynBacklog: String = "0", val hasMaxSynBacklog: Boolean = false,
        val printk: String = "N/A"
    )

    data class NetworkProfile(
        val name: String,
        val tcpCongestion: String?,
        val toggles: Map<String, String>,
        val printk: String?
    )

    private val _net = MutableStateFlow(NetworkState())
    val net: StateFlow<NetworkState> = _net

    private val _profiles = MutableStateFlow<List<NetworkProfile>>(emptyList())
    val profiles: StateFlow<List<NetworkProfile>> = _profiles

    init {
        refreshData()
        loadProfiles()
    }

    private fun loadProfiles() {
        viewModelScope.launch {
            val json = settingsPreference.netProfilesJson.value
            _profiles.value = parseProfilesJson(json)
        }
    }

    private fun saveProfilesToPrefs() {
        val json = profilesToJson(_profiles.value)
        settingsPreference.setNetProfilesJson(json)
    }

    private fun parseProfilesJson(json: String): List<NetworkProfile> {
        val list = mutableListOf<NetworkProfile>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(NetworkProfile(
                    name = obj.getString("name"),
                    tcpCongestion = if (obj.has("tcpCongestion") && !obj.isNull("tcpCongestion")) obj.getString("tcpCongestion") else null,
                    toggles = jsonToMap(obj.optJSONObject("toggles")),
                    printk = if (obj.has("printk") && !obj.isNull("printk")) obj.getString("printk") else null
                ))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    private fun profilesToJson(profiles: List<NetworkProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            val obj = JSONObject()
            obj.put("name", profile.name)
            profile.tcpCongestion?.let { obj.put("tcpCongestion", it) }
            obj.put("toggles", mapToJson(profile.toggles))
            profile.printk?.let { obj.put("printk", it) }
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
            _net.value = NetworkState(
                tcpCongestion = NetworkUtils.getTcpCongestion(),
                availableTcp = NetworkUtils.getAvailableTcpCongestion(),
                syncookies = Utils.readFile(NetworkUtils.TCP_SYNCOOKIES).trim(),
                hasSyncookies = Utils.testFile(NetworkUtils.TCP_SYNCOOKIES),
                reuse = Utils.readFile(NetworkUtils.TCP_REUSE).trim(),
                hasReuse = Utils.testFile(NetworkUtils.TCP_REUSE),
                fastOpen = Utils.readFile(NetworkUtils.TCP_FASTOPEN).trim(),
                hasFastOpen = Utils.testFile(NetworkUtils.TCP_FASTOPEN),
                sack = Utils.readFile(NetworkUtils.TCP_SACK).trim(),
                hasSack = Utils.testFile(NetworkUtils.TCP_SACK),
                ecn = Utils.readFile(NetworkUtils.TCP_ECN).trim(),
                hasEcn = Utils.testFile(NetworkUtils.TCP_ECN),
                maxSynBacklog = Utils.readFile(NetworkUtils.TCP_MAX_SYN_BACKLOG).trim(),
                hasMaxSynBacklog = Utils.testFile(NetworkUtils.TCP_MAX_SYN_BACKLOG),
                printk = Utils.readFile(NetworkUtils.PRINTK)
            )
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
            initialList.add(NetworkProfile(name, null, emptyMap(), null))
            _profiles.value = initialList
        }
        viewModelScope.launch(Dispatchers.IO) {
            val toggles = mutableMapOf<String, String>()
            listOf(
                NetworkUtils.TCP_SYNCOOKIES,
                NetworkUtils.TCP_REUSE,
                NetworkUtils.TCP_FASTOPEN,
                NetworkUtils.TCP_SACK,
                NetworkUtils.TCP_ECN,
                NetworkUtils.TCP_MAX_SYN_BACKLOG
            ).forEach { path ->
                if (Utils.testFile(path)) toggles[path] = Utils.readFile(path)
            }

            val newProfile = NetworkProfile(
                name = name,
                tcpCongestion = NetworkUtils.getTcpCongestion().takeIf { it.isNotEmpty() },
                toggles = toggles,
                printk = Utils.readFile(NetworkUtils.PRINTK).takeIf { it.isNotEmpty() }
            )
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

    fun applyProfile(profile: NetworkProfile, isAutoApply: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            profile.tcpCongestion?.let { Utils.writeFile(NetworkUtils.TCP_CONG, it) }
            profile.toggles.forEach { (path, value) -> Utils.writeFile(path, value) }
            profile.printk?.let { Utils.writeFile(NetworkUtils.PRINTK, it) }
            
            refreshData()
            settingsPreference.setSelectedNetProfileName(profile.name)
            
            withContext(Dispatchers.Main) {
                val format = if (isAutoApply) R.string.profile_auto_apply_success else R.string.profile_apply_success
                Toast.makeText(getApplication(), getApplication<Application>().getString(format, profile.name), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun deleteProfile(profile: NetworkProfile) {
        val newList = _profiles.value.filter { it.name != profile.name }
        _profiles.value = newList
        saveProfilesToPrefs()
        if (settingsPreference.selectedNetProfileName.value == profile.name) {
            settingsPreference.setSelectedNetProfileName(null)
        }
    }

    fun renameProfile(profile: NetworkProfile, newName: String) {
        val newList = _profiles.value.map { 
            if (it.name == profile.name) it.copy(name = newName) else it 
        }
        _profiles.value = newList
        saveProfilesToPrefs()
        if (settingsPreference.selectedNetProfileName.value == profile.name) {
            settingsPreference.setSelectedNetProfileName(newName)
        }
    }
}
