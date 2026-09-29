package com.veldash.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the parser over a real OSRM response (Athens centre to Piraeus) captured as a fixture. */
class OsrmParserTest {

    private fun fixture(): String =
        javaClass.classLoader!!.getResourceAsStream("osrm_sample.json")!!.bufferedReader().readText()

    @Test
    fun parsesRealResponse() {
        val r = OsrmParser.parse(fixture(), Route.Source.OSRM, polylinePrecision = 6)

        assertEquals(Route.Source.OSRM, r.source)
        assertTrue("expected a dense polyline, got ${r.pointCount}", r.pointCount > 20)
        assertEquals(r.pointCount, r.lons.size)
        assertTrue("distance ${r.distanceM}", r.distanceM in 5_000.0..25_000.0)
        assertTrue("duration ${r.durationS}", r.durationS in 300.0..7_200.0)

        // Every vertex inside a generous Attica box.
        for (i in 0 until r.pointCount) {
            assertTrue("lat[$i]=${r.lats[i]}", r.lats[i] in 37.8..38.2)
            assertTrue("lon[$i]=${r.lons[i]}", r.lons[i] in 23.5..23.9)
        }
    }

    @Test
    fun maneuversAreOrderedAndBracketed() {
        val r = OsrmParser.parse(fixture(), Route.Source.OSRM, polylinePrecision = 6)
        val m = r.maneuvers

        assertTrue("need at least depart + arrive", m.size >= 2)
        assertEquals(Maneuver.Type.DEPART, m.first().type)
        assertEquals(Maneuver.Type.ARRIVE, m.last().type)
        assertEquals(0, m.first().pointIndex)
        assertEquals(r.pointCount - 1, m.last().pointIndex)

        var prev = -1
        for (x in m) {
            assertTrue("pointIndex must not go backwards", x.pointIndex >= prev)
            assertTrue(x.pointIndex in 0 until r.pointCount)
            assertTrue(x.distanceToNextM >= 0.0)
            prev = x.pointIndex
        }
    }

    @Test(expected = RoutingException::class)
    fun rejectsNonOkCode() {
        OsrmParser.parse("""{"code":"NoRoute","message":"Impossible route."}""", Route.Source.OSRM, 6)
    }
}
