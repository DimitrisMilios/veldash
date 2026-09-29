package com.veldash.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GeoUriTest {

    @Test
    fun plainCoordinates() {
        val t = GeoUri.parse("geo:37.98,23.72")!!
        assertNotNull(t.place)
        assertEquals(37.98, t.place!!.lat, 1e-9)
        assertEquals(23.72, t.place!!.lon, 1e-9)
        assertNull(t.query)
    }

    @Test
    fun coordinatesWithZoomAndAltitude() {
        val t = GeoUri.parse("geo:37.98,23.72,0?z=15")!!
        assertEquals(37.98, t.place!!.lat, 1e-9)
    }

    @Test
    fun labelledQueryCoordinates() {
        val t = GeoUri.parse("geo:0,0?q=37.98,23.72(Home%20Base)")!!
        assertEquals("Home Base", t.place!!.name)
        assertEquals(23.72, t.place!!.lon, 1e-9)
    }

    @Test
    fun freeTextQuery() {
        val t = GeoUri.parse("geo:0,0?q=Syntagma+Square")!!
        assertNull(t.place)
        assertEquals("Syntagma Square", t.query)
    }

    @Test
    fun rejectsOtherSchemesAndEmpty() {
        assertNull(GeoUri.parse("https://example.com"))
        assertNull(GeoUri.parse("geo:0,0"))
        assertNull(GeoUri.parse(null))
    }
}
