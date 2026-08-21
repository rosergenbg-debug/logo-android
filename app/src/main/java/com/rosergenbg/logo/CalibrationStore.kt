package com.rosergenbg.logo

import android.content.Context

class CalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences("logo_calibration", Context.MODE_PRIVATE)

    fun save(routeLabel: String, latencyMs: Int) {
        prefs.edit().putInt(routeLabel, latencyMs.coerceAtLeast(0)).apply()
    }

    fun load(routeLabel: String): Int? {
        if (!prefs.contains(routeLabel)) return null
        return prefs.getInt(routeLabel, -1).takeIf { it >= 0 }
    }

    fun clear(routeLabel: String) {
        prefs.edit().remove(routeLabel).apply()
    }
}
