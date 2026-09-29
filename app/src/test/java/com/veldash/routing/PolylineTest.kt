package com.veldash.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class PolylineTest {

    /** Reference vector from the Google encoded-polyline spec. */
    @Test
    fun decodesSpecSampleAtPrecision5() {
        val c = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, c.lats.size)
        assertEquals(38.5, c.lats[0], 1e-5)
        assertEquals(-120.2, c.lons[0], 1e-5)
        assertEquals(40.7, c.lats[1], 1e-5)
        assertEquals(-120.95, c.lons[1], 1e-5)
        assertEquals(43.252, c.lats[2], 1e-5)
        assertEquals(-126.453, c.lons[2], 1e-5)
    }

    @Test
    fun emptyStringGivesNoPoints() {
        val c = Polyline.decode("", 6)
        assertEquals(0, c.lats.size)
        assertEquals(0, c.lons.size)
    }
}
