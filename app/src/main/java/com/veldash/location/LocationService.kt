package com.veldash.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import com.veldash.MainActivity
import com.veldash.R

/**
 * Foreground service that owns the one LocationManager subscription in the app.
 *
 * - Platform LocationManager only: no Play Services, no fused-location library.
 * - GPS_PROVIDER at a fixed 1000 ms interval, 0 m distance filter (steady cadence for the HUD).
 * - NETWORK_PROVIDER (Wi-Fi / cell) subscribed alongside: it is the only thing that produces a
 *   position indoors or in the first minutes of a cold start. Its fixes are used only while GPS
 *   has not delivered one recently, so the HUD never flips between the two on the road.
 * - Seeded from the freshest last-known position of any provider.
 * - Delivers on the main looper straight into [LocationBus]; no thread hop, no allocation churn.
 * - Foreground so GPS keeps running when the driver switches to a music app mid-route.
 */
class LocationService : Service(), LocationListener {

    private lateinit var lm: LocationManager
    private val providers = ArrayList<String>(2)
    private var subscribed = false

    /** elapsedRealtime of the last GPS fix that was published; 0 = none yet. */
    private var lastGpsMs = 0L

    private var prev: Location? = null
    private var stickyBearing = 0f

    /** Low-pass state for speed derived from movement; -1 = none yet. */
    private var derivedKmh = -1f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !hasLocationPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        subscribe()
        return START_STICKY
    }

    override fun onDestroy() {
        if (subscribed) {
            try {
                lm.removeUpdates(this)
            } catch (e: SecurityException) {
                // Permission revoked while running; nothing to release.
            }
            subscribed = false
        }
        super.onDestroy()
    }

    // ---- Subscription ----

    private fun subscribe() {
        if (subscribed) return
        val all = lm.allProviders
        providers.clear()
        if (LocationManager.GPS_PROVIDER in all) providers.add(LocationManager.GPS_PROVIDER)
        if (LocationManager.NETWORK_PROVIDER in all) providers.add(LocationManager.NETWORK_PROVIDER)
        if (providers.isEmpty()) {
            LocationBus.setGpsAvailable(false)
            return
        }
        try {
            var anyEnabled = false
            var seed: Location? = null
            for (p in providers) {
                lm.requestLocationUpdates(p, INTERVAL_MS, 0f, this, Looper.getMainLooper())
                if (lm.isProviderEnabled(p)) anyEnabled = true
                // Freshest last-known position across providers; a 2-day-old GPS fix must not
                // beat a Wi-Fi position from a minute ago.
                val lk = lm.getLastKnownLocation(p)
                if (lk != null && (seed == null || lk.elapsedRealtimeNanos > seed.elapsedRealtimeNanos)) seed = lk
            }
            subscribed = true
            LocationBus.setGpsAvailable(anyEnabled)
            // Seed so the batmobile appears before the first live fix.
            // Its elapsedRealtimeNanos carries the true age, so LocationBus.isFresh() stays honest.
            seed?.let { onLocationChanged(it) }
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun anyProviderEnabled(): Boolean {
        for (p in providers) if (lm.isProviderEnabled(p)) return true
        return false
    }

    // ---- LocationListener ----

    override fun onLocationChanged(location: Location) {
        val nowMs = location.elapsedRealtimeNanos / 1_000_000L
        if (location.provider == LocationManager.GPS_PROVIDER) {
            lastGpsMs = nowMs
        } else if (lastGpsMs != 0L && nowMs - lastGpsMs < GPS_HOLD_MS) {
            // GPS is live: a coarse Wi-Fi position would only yank the car off the road.
            return
        }
        val last = prev

        // Movement derived from consecutive positions. Used whenever the chip reports nothing,
        // or reports an exact 0.0 speed/bearing flagged as valid while the position is clearly
        // moving (the Android emulator does this; so do some cheap head-unit GPS modules).
        var dtS = 0f
        var distM = 0f
        if (last != null) {
            dtS = (location.elapsedRealtimeNanos - last.elapsedRealtimeNanos) / 1e9f
            if (dtS > 0.2f) distM = last.distanceTo(location)
        }
        // Movement is trusted only when the displacement clearly exceeds the position noise:
        // at least MIN_MOVE_M and a fraction of the worse accuracy radius of the two fixes.
        // Wi-Fi / cell fixes wander tens of metres while standing still, so they never derive
        // speed or heading at all; the car then holds its last heading instead of spinning.
        val coarse = location.provider != LocationManager.GPS_PROVIDER
        val noiseM = maxOf(MIN_MOVE_M, ACCURACY_MOVE_FRACTION * maxOf(accuracyOf(location), accuracyOf(last)))
        val moved = !coarse && dtS > 0.2f && distM >= noiseM

        var speedKmh = when {
            location.hasSpeed() && location.speed > 0f -> location.speed * MPS_TO_KMH
            // Derived speed: only over a decent interval (two fixes 0.3 s apart would read 3x
            // too fast), and low-pass filtered so the readout does not flicker.
            moved && dtS >= MIN_DERIVE_DT_S -> {
                val raw = distM / dtS * MPS_TO_KMH
                val filtered = if (derivedKmh < 0f) raw else derivedKmh + (raw - derivedKmh) * DERIVED_ALPHA
                derivedKmh = filtered
                filtered
            }
            moved -> if (derivedKmh >= 0f) derivedKmh else 0f
            else -> {
                derivedKmh = -1f
                0f
            }
        }
        // Reject implausible jumps (multipath, cold-start relocation) from the derived speed.
        if (!location.hasSpeed() && speedKmh > MAX_DERIVED_KMH) speedKmh = 0f

        var bearing = when {
            location.hasBearing() && location.bearing != 0f -> location.bearing
            moved && distM >= MIN_BEARING_DIST_M -> last!!.bearingTo(location)
            location.hasBearing() -> location.bearing // genuine due-north
            else -> Float.NaN
        }

        // GPS bearing is noise below walking pace: keep the last good heading.
        if (!bearing.isNaN() && speedKmh >= MIN_BEARING_SPEED_KMH) {
            stickyBearing = if (bearing < 0f) bearing + 360f else bearing
        }

        prev = location
        LocationBus.publish(
            Fix(
                lat = location.latitude,
                lon = location.longitude,
                bearing = stickyBearing,
                speedKmh = speedKmh,
                accuracyM = if (location.hasAccuracy()) location.accuracy else -1f,
                elapsedMs = location.elapsedRealtimeNanos / 1_000_000L,
            ),
        )
    }

    // The three methods below are default (no-op) on API 29+ but ABSTRACT on API 21-28.
    // Omitting them compiles fine against SDK 35 and then throws AbstractMethodError on an
    // old head unit the moment the provider toggles. Keep them.

    override fun onProviderEnabled(provider: String) {
        if (provider in providers) LocationBus.setGpsAvailable(true)
    }

    override fun onProviderDisabled(provider: String) {
        if (provider in providers) LocationBus.setGpsAvailable(anyProviderEnabled())
    }

    @Deprecated("Abstract on API < 29, must be implemented")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    // ---- Foreground notification ----

    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val piFlags = if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), piFlags)

        val b = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notif_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        setShowBadge(false)
                        enableVibration(false)
                        setSound(null, null)
                    },
                )
            }
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        }

        return b
            .setSmallIcon(R.drawable.ic_bat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_gps))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    companion object {
        private const val INTERVAL_MS = 1000L

        /** Network fixes are dropped while a GPS fix is younger than this. */
        private const val GPS_HOLD_MS = 10_000L
        private const val MPS_TO_KMH = 3.6f
        private const val MIN_MOVE_M = 1.5f
        /** A displacement under this fraction of the reported accuracy is treated as noise. */
        private const val ACCURACY_MOVE_FRACTION = 0.5f
        private const val MIN_BEARING_DIST_M = 3f
        private const val MIN_BEARING_SPEED_KMH = 4f
        private const val MAX_DERIVED_KMH = 250f
        private const val MIN_DERIVE_DT_S = 0.6f
        private const val DERIVED_ALPHA = 0.5f

        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "nav"
        private const val ACTION_STOP = "com.veldash.STOP"

        private fun accuracyOf(l: Location?): Float = if (l != null && l.hasAccuracy()) l.accuracy else 0f

        fun hasLocationPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 23) return true
            return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

        /** Starts (or re-pokes) the service. Caller must hold ACCESS_FINE_LOCATION. */
        fun start(context: Context) {
            val i = Intent(context, LocationService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationService::class.java))
        }
    }
}
