package com.veldash

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Build
import android.os.StrictMode
import android.util.Log
import com.veldash.routing.Connectivity
import org.conscrypt.Conscrypt
import java.security.Security

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
        installModernTls()
        // One ConnectivityManager callback for the whole process. Drives online/offline routing choice.
        Connectivity.start(this)
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
     * Android 5-9 ship a Conscrypt that tops out at TLS 1.2, and public servers (the OSRM demo
     * router first among them) have started refusing anything below TLS 1.3. Putting the bundled
     * Conscrypt first makes every SSLSocket in the process (OkHttp, MapLibre tiles) speak TLS 1.3.
     * Android 10+ already does, so the extra provider is left out there.
     */
    private fun installModernTls() {
        if (Build.VERSION.SDK_INT >= 29) return
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        } catch (e: Throwable) {
            // Native lib missing for this ABI or failed to load: keep the platform provider.
            Log.w("VeldashApp", "Conscrypt unavailable, staying on platform TLS", e)
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
