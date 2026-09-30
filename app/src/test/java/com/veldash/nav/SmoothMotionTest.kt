package com.veldash.nav

import com.veldash.routing.Maneuver
import com.veldash.routing.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothMotionTest {

    /** North along lon 23.7 for 1 km, then a right-angle turn east for 1 km. */
    private fun lRoute(): Route {
        val lats = DoubleArray(21)
        val lons = DoubleArray(21)
        for (i in 0..10) { lats[i] = 38.0 + i * 0.0009; lons[i] = 23.7 }
        for (i in 1..10) { lats[10 + i] = lats[10]; lons[10 + i] = 23.7 + i * 0.0009 / Math.cos(Math.toRadians(38.0)) }
        val m = listOf(
            Maneuver(Maneuver.Type.DEPART, lats[0], lons[0], 0, 1000.0, null, 0),
            Maneuver(Maneuver.Type.TURN_RIGHT, lats[10], lons[10], 10, 1000.0, null, 0),
            Maneuver(Maneuver.Type.ARRIVE, lats[20], lons[20], 20, 0.0, null, 0),
        )
        return Route(lats, lons, 2000.0, 200.0, m, Route.Source.OSRM)
    }

    @Test
    fun followsTheRouteSmoothlyAtConstantSpeed() {
        val nav = Navigator(lRoute())
        val motion = SmoothMotion()
        motion.setNavigator(nav)

        // Feed a 10 m/s drive: a fix every second, 30 frames in between.
        var maxJumpM = 0.0
        var prevLat = Double.NaN
        var prevLon = Double.NaN
        val mPerDegLon = 111_320.0 * Math.cos(Math.toRadians(38.0))
        for (sec in 0..60) {
            val d = sec * 10.0
            val p = Navigator.RoutePoint()
            nav.positionAt(d, p)
            val s = nav.update(p.lat, p.lon)
            motion.onFix(p.lat, p.lon, p.bearing, 36f, s)
            repeat(30) {
                motion.step(1.0 / 30)
                if (!prevLat.isNaN()) {
                    val dx = (motion.lon - prevLon) * mPerDegLon
                    val dy = (motion.lat - prevLat) * 111_320.0
                    maxJumpM = maxOf(maxJumpM, Math.sqrt(dx * dx + dy * dy))
                }
                prevLat = motion.lat
                prevLon = motion.lon
            }
        }
        assertTrue(motion.onRoute)
        // 10 m/s at 30 fps = 0.33 m per frame; allow catch-up headroom but no visible jumps.
        assertTrue("max per-frame move $maxJumpM m", maxJumpM < 1.0)
        // After 60 s at 10 m/s the drawn car is within a few metres of 600 m along.
        assertEquals(600.0, motion.distAlongM, 15.0)
    }

    @Test
    fun headingSweepsThroughTheCorner() {
        val nav = Navigator(lRoute())
        val motion = SmoothMotion()
        motion.setNavigator(nav)
        val p = Navigator.RoutePoint()

        // Approach the corner heading north.
        for (sec in 0..99) {
            nav.positionAt(sec * 10.0, p)
            motion.onFix(p.lat, p.lon, p.bearing, 36f, nav.update(p.lat, p.lon))
            repeat(30) { motion.step(1.0 / 30) }
        }
        assertEquals(0f, motion.bearing, 3f)

        // Continue past the corner: heading must move towards east without wrapping the wrong way.
        var maxDeltaPerFrame = 0f
        var prev = motion.bearing
        for (sec in 100..106) {
            nav.positionAt(sec * 10.0, p)
            motion.onFix(p.lat, p.lon, p.bearing, 36f, nav.update(p.lat, p.lon))
            repeat(30) {
                motion.step(1.0 / 30)
                val d = Math.abs(SmoothMotion.lerpAngle(prev, motion.bearing, 1f) - prev).let { if (it > 180) 360 - it else it }
                maxDeltaPerFrame = maxOf(maxDeltaPerFrame, d)
                prev = motion.bearing
            }
        }
        assertEquals(90f, motion.bearing, 5f)
        assertTrue("turned $maxDeltaPerFrame deg in one frame", maxDeltaPerFrame < 15f)
    }

    @Test
    fun lerpAngleTakesTheShortWay() {
        assertEquals(355f, SmoothMotion.lerpAngle(350f, 10f, 0.25f), 1e-3f)
        assertEquals(5f, SmoothMotion.lerpAngle(10f, 350f, 0.25f), 1e-3f)
        assertEquals(90f, SmoothMotion.lerpAngle(0f, 180f, 0.5f), 1e-3f)
    }

    @Test
    fun freeModeChasesAndDeadReckons() {
        val motion = SmoothMotion()
        motion.onFix(38.0, 23.7, 90f, 36f, null) // moving east at 10 m/s, no route
        val startLon = motion.lon
        repeat(30) { motion.step(1.0 / 30) }
        val mPerDegLon = 111_320.0 * Math.cos(Math.toRadians(38.0))
        val movedM = (motion.lon - startLon) * mPerDegLon
        // Chasing a target that itself advances 10 m: we should have moved a good part of it, eastwards.
        assertTrue("moved $movedM m", movedM in 5.0..10.0)
        assertEquals(38.0, motion.lat, 1e-6)
    }
}
