package com.rosergenbg.logo

import android.content.Context

/**
 * Process-wide owner of the real-time audio objects.
 * This keeps one DAF engine alive across Activity recreation/backgrounding.
 */
object DafRuntime {
    @Volatile
    private var initialized = false

    lateinit var routeManager: AudioRouteManager
        private set

    lateinit var engine: DafAudioEngine
        private set

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            routeManager = AudioRouteManager(context.applicationContext)
            engine = DafAudioEngine(routeManager)
            initialized = true
        }
    }
}
