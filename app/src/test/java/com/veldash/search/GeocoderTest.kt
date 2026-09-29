package com.veldash.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeocoderTest {

    @Test
    fun parsesDecimalCoordinates() {
        val p = Geocoder.parseCoordinates("37.9838, 23.7275")
        assertNotNull(p)
        assertEquals(37.9838, p!!.lat, 1e-9)
        assertEquals(23.7275, p.lon, 1e-9)

        val q = Geocoder.parseCoordinates(" -33.86 151.21 ")
        assertNotNull(q)
        assertEquals(-33.86, q!!.lat, 1e-9)
        assertEquals(151.21, q.lon, 1e-9)
    }

    @Test
    fun rejectsTextAndOutOfRange() {
        assertNull(Geocoder.parseCoordinates("Syntagma Square"))
        assertNull(Geocoder.parseCoordinates("95, 10"))
        assertNull(Geocoder.parseCoordinates("10, 190"))
        assertNull(Geocoder.parseCoordinates(""))
    }

    @Test
    fun parsesRealNominatimResponse() {
        val json = javaClass.classLoader!!.getResourceAsStream("nominatim_sample.json")!!.bufferedReader().readText()
        val places = Geocoder.parseNominatim(json)
        assertTrue("expected results", places.isNotEmpty())
        for (p in places) {
            assertTrue(p.name.isNotBlank())
            assertTrue("lat ${p.lat}", p.lat in 37.5..38.5)
            assertTrue("lon ${p.lon}", p.lon in 23.0..24.5)
            // detail must not repeat the name as its prefix
            assertTrue(!p.detail.startsWith(p.name))
        }
    }

    @Test
    fun parsesMapboxShape() {
        val json = """
            {"features":[{"text":"Syntagma Square","place_name":"Syntagma Square, Athens, Greece","center":[23.7348,37.9755]}]}
        """.trimIndent()
        val places = Geocoder.parseMapbox(json)
        assertEquals(1, places.size)
        assertEquals("Syntagma Square", places[0].name)
        assertEquals("Athens, Greece", places[0].detail)
        assertEquals(37.9755, places[0].lat, 1e-9)
        assertEquals(23.7348, places[0].lon, 1e-9)
    }
}
