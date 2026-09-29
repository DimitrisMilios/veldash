package com.veldash.nav

import com.veldash.routing.Maneuver
import com.veldash.routing.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {

    /** Straight north along lon 23.7, from lat 38.000 to 38.011 in 0.001 deg steps: 12 points, ~1224 m. */
    private fun straightRoute(): Route {
        val n = 12
        val lats = DoubleArray(n) { 38.0 + it * 0.001 }
        val lons = DoubleArray(n) { 23.7 }
        val maneuvers = listOf(
            Maneuver(Maneuver.Type.DEPART, lats[0], lons[0], 0, 556.0, null, 0),
            Maneuver(Maneuver.Type.TURN_RIGHT, lats[5], lons[5], 5, 668.0, "Main St", 0),
            Maneuver(Maneuver.Type.ARRIVE, lats[11], lons[11], 11, 0.0, null, 0),
        )
        return Route(lats, lons, 1224.0, 120.0, maneuvers, Route.Source.OSRM)
    }

    @Test
    fun progressAndNextManeuver() {
        val nav = Navigator(straightRoute())
        val s = nav.update(38.002, 23.7)

        assertTrue(s.onRoute)
        assertFalse(s.arrived)
        assertEquals(222.6, s.distAlongM, 3.0)
        assertNotNull(s.next)
        assertEquals(Maneuver.Type.TURN_RIGHT, s.next!!.type)
        assertEquals(334.0, s.distToNextM, 3.0)
        assertEquals(nav.totalM - s.distAlongM, s.remainingM, 0.01)
        assertEquals(0f, s.roadBearing, 0.5f)
    }

    @Test
    fun passingAManeuverAdvancesToTheNext() {
        val nav = Navigator(straightRoute())
        nav.update(38.002, 23.7)
        val s = nav.update(38.0062, 23.7) // 22 m past the turn at 38.005
        assertEquals(Maneuver.Type.ARRIVE, s.next!!.type)
    }

    @Test
    fun snapsLateralJitterOntoTheLine() {
        val nav = Navigator(straightRoute())
        // ~9 m east of the line
        val s = nav.update(38.003, 23.7 + 9.0 / (111_320.0 * Math.cos(Math.toRadians(38.0))))
        assertTrue(s.onRoute)
        assertEquals(9.0, s.crossTrackM, 0.5)
        assertEquals(23.7, s.snappedLon, 1e-6)
        assertEquals(38.003, s.snappedLat, 1e-5)
    }

    @Test
    fun offRouteNeedsConsecutiveFixes() {
        val nav = Navigator(straightRoute())
        val farEast = 23.7 + 150.0 / (111_320.0 * Math.cos(Math.toRadians(38.0)))
        nav.update(38.003, farEast)
        assertFalse(nav.isOffRoute)
        nav.update(38.003, farEast)
        assertFalse(nav.isOffRoute)
        val s = nav.update(38.003, farEast)
        assertTrue(nav.isOffRoute)
        assertFalse(s.onRoute)
        // Raw position is reported when off-route.
        assertEquals(farEast, s.snappedLon, 1e-9)

        // One good fix resets the counter.
        nav.update(38.004, 23.7)
        assertFalse(nav.isOffRoute)
    }

    @Test
    fun arrivesNearTheEnd() {
        val nav = Navigator(straightRoute())
        nav.update(38.009, 23.7)
        val s = nav.update(38.0109, 23.7) // ~11 m short of the end
        assertTrue(s.arrived)
        assertNull(s.next)
        assertTrue(s.remainingM < Navigator.ARRIVE_M)
    }
}
