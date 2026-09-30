package com.veldash.nav

/**
 * Turns 1 Hz GPS fixes into a continuous, frame-rate position and heading for the car marker
 * and the chase camera. Pure Kotlin, no Android imports.
 *
 * Two regimes:
 *
 *  ON ROUTE  The car travels along the route polyline at a display speed. Each fix measures the
 *            error between where we are drawn and where GPS says we are (both as metres along
 *            the route) and folds that error into the display speed so it is closed over the
 *            next second. The car therefore never leaves the road, sweeps through corners
 *            following the geometry, and never stutters when a fix is late or early.
 *
 *  FREE      No route, off-route, or (nearly) stationary. The drawn position chases a target
 *            that dead-reckons forward from the last fix at the fix speed and heading, with an
 *            exponential approach so corrections are gentle.
 *
 * Heading is always smoothed towards its target with a short time constant: corners turn the
 * car over ~0.4 s instead of snapping, and GPS heading noise is filtered out.
 */
class SmoothMotion {

    var lat = 0.0
        private set
    var lon = 0.0
        private set
    var bearing = 0f
        private set
    var hasPosition = false
        private set

    /** True while travelling along the route polyline. */
    var onRoute = false
        private set

    /** Metres along the route of the drawn position (valid when [onRoute]). */
    var distAlongM = 0.0
        private set

    private var navigator: Navigator? = null
    private var speedMps = 0.0

    // Free-mode target (dead-reckoned).
    private var targetLat = 0.0
    private var targetLon = 0.0
    private var freeSpeedMps = 0.0
    private var freeBearing = 0f

    private var bearingTarget = 0f
    private val rp = Navigator.RoutePoint()
    private var mPerDegLon = 111_320.0

    /** Call whenever the active route changes (new route, reroute, cleared). */
    fun setNavigator(nav: Navigator?) {
        navigator = nav
        onRoute = false
    }

    /**
     * @param state the Navigator result for this fix, or null when there is no route.
     */
    fun onFix(fixLat: Double, fixLon: Double, fixBearing: Float, fixSpeedKmh: Float, state: NavState?) {
        val v = fixSpeedKmh / 3.6
        if (!hasPosition) {
            lat = fixLat
            lon = fixLon
            bearing = fixBearing
            bearingTarget = fixBearing
            hasPosition = true
            mPerDegLon = 111_320.0 * Math.cos(Math.toRadians(fixLat))
        }

        val nav = navigator
        if (nav != null && state != null && state.onRoute && v >= MOVING_MPS) {
            if (!onRoute || Math.abs(state.distAlongM - distAlongM) > RESYNC_M) {
                // First on-route fix, or a jump (reroute, GPS glitch): don't animate across it.
                distAlongM = state.distAlongM
                nav.positionAt(distAlongM, rp)
                lat = rp.lat
                lon = rp.lon
            }
            onRoute = true
            val err = state.distAlongM - distAlongM
            speedMps = (v + err / CATCHUP_S).coerceIn(0.0, v * 1.5 + 2.0)
        } else {
            onRoute = false
            targetLat = fixLat
            targetLon = fixLon
            freeSpeedMps = v
            if (v >= MOVING_MPS) {
                freeBearing = fixBearing
                bearingTarget = fixBearing
            }
            // A big jump (first fix after a gap, teleport) is snapped, not chased.
            if (distanceM(lat, lon, fixLat, fixLon) > RESYNC_M) {
                lat = fixLat
                lon = fixLon
            }
        }
    }

    /** Advance by [dtS] seconds. Call once per display frame. */
    fun step(dtS: Double) {
        if (!hasPosition || dtS <= 0.0) return
        val dt = dtS.coerceAtMost(MAX_STEP_S)

        val nav = navigator
        if (onRoute && nav != null) {
            distAlongM = (distAlongM + speedMps * dt).coerceAtMost(nav.totalM)
            nav.positionAt(distAlongM, rp)
            lat = rp.lat
            lon = rp.lon
            if (speedMps >= MOVING_MPS) bearingTarget = rp.bearing
        } else {
            if (freeSpeedMps >= MOVING_MPS) {
                // Dead-reckon the target forward so the car keeps rolling between fixes.
                val stepM = freeSpeedMps * dt
                val rad = Math.toRadians(freeBearing.toDouble())
                targetLat += stepM * Math.cos(rad) / M_PER_DEG_LAT
                targetLon += stepM * Math.sin(rad) / mPerDegLon
            }
            val k = 1.0 - Math.exp(-dt / TAU_POS_S)
            lat += (targetLat - lat) * k
            lon += (targetLon - lon) * k
        }

        val kb = (1.0 - Math.exp(-dt / TAU_BEARING_S)).toFloat()
        bearing = lerpAngle(bearing, bearingTarget, kb)
    }

    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dx = (lon2 - lon1) * mPerDegLon
        val dy = (lat2 - lat1) * M_PER_DEG_LAT
        return Math.sqrt(dx * dx + dy * dy)
    }

    companion object {
        /** Below this the car is "stopped": no dead reckoning, heading frozen. */
        const val MOVING_MPS = 1.0

        /** Seconds over which a position error is closed. ~ the fix interval. */
        const val CATCHUP_S = 1.0

        /** Errors beyond this are snapped rather than animated. */
        const val RESYNC_M = 30.0

        const val TAU_POS_S = 0.45
        const val TAU_BEARING_S = 0.4
        const val MAX_STEP_S = 0.25
        private const val M_PER_DEG_LAT = 111_320.0

        /** Shortest-way angular interpolation, result in [0, 360). */
        fun lerpAngle(from: Float, to: Float, k: Float): Float {
            var d = (to - from) % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            var r = (from + d * k) % 360f
            if (r < 0f) r += 360f
            return r
        }
    }
}
