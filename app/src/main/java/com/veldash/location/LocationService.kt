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
 * - Falls back to NETWORK_PROVIDER on units without a GPS chip.
 * - Delivers on the main looper straight into [LocationBus]; no thread hop, no allocation churn.
 * - Foreground so GPS keeps running when the driver switches to a music app mid-route.
 */
class LocationService : Service(), LocationListener {

    private lateinit var lm: LocationManager
    private var provider: String? = null
    private var subscribed = false

    private var prev: Location? = null
    private var stickyBearing = 0f

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
        val providers = lm.allProviders
        val p = when {
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (p == null) {
            LocationBus.setGpsAvailable(false)
            return
        }
        provider = p
        try {
            lm.requestLocationUpdates(p, INTERVAL_MS, 0f, this, Looper.getMainLooper())
            subscribed = true
            LocationBus.setGpsAvailable(lm.isProviderEnabled(p))
            // Seed with the last known position so the batmobile appears before the first live fix.
            // Its elapsedRealtimeNanos carries the true age, so LocationBus.isFresh() stays honest.
            lm.getLastKnownLocation(p)?.let { onLocationChanged(it) }
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    // ---- LocationListener ----

    override fun onLocationChanged(location: Location) {
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
        val moved = dtS > 0.2f && distM >= MIN_MOVE_M

        var speedKmh = when {
            location.hasSpeed() && location.speed > 0f -> location.speed * MPS_TO_KMH
            moved -> distM / dtS * MPS_TO_KMH
            else -> 0f
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
        if (provider == this.provider) LocationBus.setGpsAvailable(true)
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == this.provider) LocationBus.setGpsAvailable(false)
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
        private const val MPS_TO_KMH = 3.6f
        private const val MIN_MOVE_M = 1.5f
        private const val MIN_BEARING_DIST_M = 3f
        private const val MIN_BEARING_SPEED_KMH = 4f
        private const val MAX_DERIVED_KMH = 250f

        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "nav"
        private const val ACTION_STOP = "com.veldash.STOP"

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
