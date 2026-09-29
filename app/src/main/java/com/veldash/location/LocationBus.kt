package com.veldash.location

import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-local fan-out from [LocationService] to whoever is on screen.
 * The service and the Activity share a process, so no Binder, no broadcasts, no LiveData.
 * Everything here is called on the main thread.
 */
object LocationBus {

    interface Listener {
        fun onFix(fix: Fix)
        fun onGpsAvailable(available: Boolean)
    }

    /** Most recent fix, possibly stale. Check [isFresh] before trusting it for navigation. */
    @Volatile
    var last: Fix? = null
        private set

    /** False when the provider is switched off or the unit has no GPS hardware. */
    @Volatile
    var gpsAvailable: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun add(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun remove(listener: Listener) {
        listeners.remove(listener)
    }

    fun publish(fix: Fix) {
        last = fix
        for (l in listeners) l.onFix(fix)
    }

    fun setGpsAvailable(available: Boolean) {
        if (gpsAvailable == available) return
        gpsAvailable = available
        for (l in listeners) l.onGpsAvailable(available)
    }

    /** True when the last fix is younger than [maxAgeMs]. */
    fun isFresh(maxAgeMs: Long = FRESH_MS): Boolean {
        val f = last ?: return false
        return SystemClock.elapsedRealtime() - f.elapsedMs <= maxAgeMs
    }

    const val FRESH_MS = 5_000L
}
