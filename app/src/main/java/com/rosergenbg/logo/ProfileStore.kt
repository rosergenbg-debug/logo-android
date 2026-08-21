package com.rosergenbg.logo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class DafProfile(
    val name: String,
    val inputLabel: String,
    val outputLabel: String,
    val delayMs: Int,
    val volumePercent: Int
)

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("logo_profiles", Context.MODE_PRIVATE)

    fun load(): MutableList<DafProfile> {
        val raw = prefs.getString("profiles", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                DafProfile(
                    name = item.getString("name"),
                    inputLabel = item.optString("input", "Автоматически (система)"),
                    outputLabel = item.optString("output", "Автоматически (система)"),
                    delayMs = item.optInt("delay", 75).coerceIn(0, 250),
                    volumePercent = item.optInt("volume", 80).coerceIn(0, 100)
                )
            }
        }.getOrElse { mutableListOf() }
    }

    fun save(profile: DafProfile) {
        val profiles = load()
        val existing = profiles.indexOfFirst { it.name.equals(profile.name, ignoreCase = true) }
        if (existing >= 0) profiles[existing] = profile else profiles.add(profile)
        persist(profiles)
    }

    fun delete(name: String) {
        persist(load().filterNot { it.name == name })
    }

    private fun persist(profiles: List<DafProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("name", profile.name)
                    .put("input", profile.inputLabel)
                    .put("output", profile.outputLabel)
                    .put("delay", profile.delayMs)
                    .put("volume", profile.volumePercent)
            )
        }
        prefs.edit().putString("profiles", array.toString()).apply()
    }
}
