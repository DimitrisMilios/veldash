package com.veldash.nav

import com.veldash.routing.Maneuver
import com.veldash.routing.Route

/** Snapshot of where we are on the route, produced once per GPS fix. */
class NavState(
    val onRoute: Boolean,
    val arrived: Boolean,
    /** Position projected onto the route when on it, raw position otherwise. */
    val snappedLat: Double,
    val snappedLon: Double,
    /** Bearing of the route segment under us, degrees. */
    val roadBearing: Float,
    val distAlongM: Double,
    val remainingM: Double,
    val remainingS: Double,
    /** Next instruction, null once arrived. */
    val next: Maneuver?,
    val distToNextM: Double,
    val crossTrackM: Double,
)

/**
 * Pure route-following logic. No Android imports, so it runs in plain JVM tests.
 *
 * Per fix it does a windowed nearest-segment search around the last position (a few hundred
 * segments, microseconds), projects the fix onto that segment and derives everything else
 * from a precomputed cumulative-distance table. Distances use a local equirectangular
 * approximation, accurate to well under 1 % at regional scale.
 */
class Navigator(val route: Route) {

    private val n = route.pointCount
    private val cum = DoubleArray(n)
    private val mPerDegLat = 111_320.0
    private val mPerDegLon: Double

    /** Maneuvers in track order (engines already sort them; this is defensive). */
    private val maneuvers: List<Maneuver> = route.maneuvers.sortedBy { it.pointIndex }

    private var lastSeg = 0
    private var nextIdx = 0
    private var offCount = 0
    private var arrived = false

    /** Average speed for ETA, from the engine's own estimate. */
    private val avgMps: Double

    init {
        require(n >= 2) { "route needs at least 2 points" }
        val midLat = (route.lats[0] + route.lats[n - 1]) / 2.0
        mPerDegLon = mPerDegLat * Math.cos(Math.toRadians(midLat))
        var acc = 0.0
        for (i in 1 until n) {
            acc += dist(route.lats[i - 1], route.lons[i - 1], route.lats[i], route.lons[i])
            cum[i] = acc
        }
        avgMps = if (route.durationS > 0) route.distanceM / route.durationS else DEFAULT_MPS
    }

    val totalM: Double get() = cum[n - 1]

    /** Mutable output holder so the per-frame caller allocates nothing. */
    class RoutePoint(var lat: Double = 0.0, var lon: Double = 0.0, var bearing: Float = 0f)

    /**
     * Position and segment bearing at [distAlongM] metres from the start, clamped to the route.
     * Binary search over the cumulative table: O(log n), allocation-free.
     */
    fun positionAt(distAlongM: Double, out: RoutePoint) {
        val d = distAlongM.coerceIn(0.0, totalM)
        var lo = 0
        var hi = n - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cum[mid] <= d) lo = mid else hi = mid - 1
        }
        val seg = lo
        val segLen = cum[seg + 1] - cum[seg]
        val t = if (segLen > 0.0) ((d - cum[seg]) / segLen).coerceIn(0.0, 1.0) else 0.0
        out.lat = route.lats[seg] + (route.lats[seg + 1] - route.lats[seg]) * t
        out.lon = route.lons[seg] + (route.lons[seg + 1] - route.lons[seg]) * t
        out.bearing = bearing(route.lats[seg], route.lons[seg], route.lats[seg + 1], route.lons[seg + 1])
    }

    /** Metres from [distAlongM] to the current next maneuver (as chosen by the last [update]). */
    fun distanceToNextAt(distAlongM: Double): Double {
        if (arrived) return 0.0
        val next = maneuvers.getOrNull(nextIdx) ?: return 0.0
        return (cum[next.pointIndex] - distAlongM).coerceAtLeast(0.0)
    }

    /** True after [OFF_ROUTE_FIXES] consecutive fixes farther than [OFF_ROUTE_M] from the line. */
    val isOffRoute: Boolean get() = offCount >= OFF_ROUTE_FIXES

    fun update(lat: Double, lon: Double): NavState {
        // ---- 1. nearest segment, windowed, then full scan if that looks off-route ----
        var best = nearestSegment(lat, lon, (lastSeg - BACK_TOLERANCE).coerceAtLeast(0), (lastSeg + WINDOW).coerceAtMost(n - 2))
        if (best.dist > OFF_ROUTE_M) {
            val full = nearestSegment(lat, lon, 0, n - 2)
            if (full.dist <= OFF_ROUTE_M) best = full
        }
        val seg = best.seg
        val onRoute = best.dist <= OFF_ROUTE_M
        if (onRoute) {
            offCount = 0
            lastSeg = seg
        } else {
            offCount++
        }

        // ---- 2. progress along the route ----
        val segLen = cum[seg + 1] - cum[seg]
        val distAlong = cum[seg] + best.t * segLen
        val remaining = (totalM - distAlong).coerceAtLeast(0.0)

        if (!arrived && onRoute && remaining <= ARRIVE_M) arrived = true

        // ---- 3. next maneuver: skip those we have passed by more than a few metres ----
        while (nextIdx < maneuvers.size - 1 && cum[maneuvers[nextIdx].pointIndex] < distAlong - MANEUVER_PASSED_M) {
            nextIdx++
        }
        val next = if (arrived) null else maneuvers.getOrNull(nextIdx)
        val distToNext = if (next != null) (cum[next.pointIndex] - distAlong).coerceAtLeast(0.0) else 0.0

        val bearing = bearing(route.lats[seg], route.lons[seg], route.lats[seg + 1], route.lons[seg + 1])

        return NavState(
            onRoute = onRoute,
            arrived = arrived,
            snappedLat = if (onRoute) best.lat else lat,
            snappedLon = if (onRoute) best.lon else lon,
            roadBearing = bearing,
            distAlongM = distAlong,
            remainingM = remaining,
            remainingS = remaining / avgMps,
            next = next,
            distToNextM = distToNext,
            crossTrackM = best.dist,
        )
    }

    // ---- geometry ----

    private class Proj(var seg: Int, var t: Double, var lat: Double, var lon: Double, var dist: Double)

    private fun nearestSegment(lat: Double, lon: Double, from: Int, to: Int): Proj {
        val out = Proj(from, 0.0, route.lats[from], route.lons[from], Double.MAX_VALUE)
        // Work in local metres relative to the query point: px = 0, py = 0.
        for (i in from..to) {
            val ax = (route.lons[i] - lon) * mPerDegLon
            val ay = (route.lats[i] - lat) * mPerDegLat
            val bx = (route.lons[i + 1] - lon) * mPerDegLon
            val by = (route.lats[i + 1] - lat) * mPerDegLat
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val qx = ax + t * dx
            val qy = ay + t * dy
            val d2 = qx * qx + qy * qy
            if (d2 < out.dist) {
                out.dist = d2
                out.seg = i
                out.t = t
                out.lon = lon + qx / mPerDegLon
                out.lat = lat + qy / mPerDegLat
            }
        }
        out.dist = Math.sqrt(out.dist)
        return out
    }

    private fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dx = (lon2 - lon1) * mPerDegLon
        val dy = (lat2 - lat1) * mPerDegLat
        return Math.sqrt(dx * dx + dy * dy)
    }

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dx = (lon2 - lon1) * mPerDegLon
        val dy = (lat2 - lat1) * mPerDegLat
        var b = Math.toDegrees(Math.atan2(dx, dy))
        if (b < 0) b += 360.0
        return b.toFloat()
    }

    companion object {
        const val OFF_ROUTE_M = 40.0
        const val OFF_ROUTE_FIXES = 3
        const val ARRIVE_M = 25.0
        private const val MANEUVER_PASSED_M = 10.0
        private const val WINDOW = 300
        private const val BACK_TOLERANCE = 3
        private const val DEFAULT_MPS = 13.9 // 50 km/h
    }
}
