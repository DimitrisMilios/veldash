package com.veldash

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.StrictMode

/**
 * Process entry point. Deliberately tiny: no DI, no analytics, no crash SDK.
 *
 * MapLibre is NOT initialised here. It is initialised lazily in MainActivity right before
 * the MapView is inflated, so a background start (e.g. the foreground GPS service restarting
 * after a process kill) never pays for loading the native renderer.
 */
class VeldashApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            // Debug only: catch main-thread disk/network access and leaked objects early.
            // Stripped from release by R8 (BuildConfig.DEBUG is a compile-time constant).
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build()
            )
        }
    }

    /**
     * Memory pressure hook. Individual components (MapView, route cache) register their own
     * ComponentCallbacks2, so this base implementation only needs to forward.
     * Kept explicit so it is obvious where system trim signals enter the app.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            // Components react to the same callback; nothing to do centrally yet.
        }
    }
}
