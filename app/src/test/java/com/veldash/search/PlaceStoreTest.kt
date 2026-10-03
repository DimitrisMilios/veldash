package com.veldash.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceStoreTest {

    @Test
    fun jsonRoundTripKeepsTheIcon() {
        val list = listOf(
            Place("Home", "Egnatia 1, Thessaloniki", 40.6401, 22.9444, "batcave"),
            Place("Gym", "", 40.65, 22.95),
        )
        val back = PlaceStore.fromJson(PlaceStore.toJson(list))
        assertEquals(2, back.size)
        assertEquals("Home", back[0].name)
        assertEquals("Egnatia 1, Thessaloniki", back[0].detail)
        assertEquals(40.6401, back[0].lat, 1e-9)
        assertEquals(22.9444, back[0].lon, 1e-9)
        assertEquals("batcave", back[0].icon)
        assertEquals("", back[1].icon)
    }

    @Test
    fun defaultIconIsNotWritten() {
        val json = PlaceStore.toJson(listOf(Place("A", "", 1.0, 2.0)))
        assertFalse(json.contains("icon"))
    }

    @Test
    fun entriesSavedBeforeIconsStillLoad() {
        val back = PlaceStore.fromJson("""[{"name":"A","detail":"B","lat":1.5,"lon":2.5}]""")
        assertEquals(1, back.size)
        assertEquals("", back[0].icon)
        assertEquals("A, B", back[0].address())
    }

    @Test
    fun withChangesOnlyWhatIsAsked() {
        val p = Place("A", "B", 1.0, 2.0, "joker")
        val q = p.with(icon = "riddler")
        assertEquals("A", q.name)
        assertEquals("B", q.detail)
        assertEquals("riddler", q.icon)
        assertTrue(q.sameSpot(p.lat, p.lon))
        assertEquals("joker", p.with(name = "C").icon)
    }

    @Test
    fun addressFallsBackToWhicheverLineIsSet() {
        assertEquals("Only name", Place("Only name", "", 0.0, 0.0).address())
        assertEquals("40.65000, 22.95000", Place("", "40.65000, 22.95000", 0.0, 0.0).address())
        assertEquals("", Place("", "", 0.0, 0.0).address())
    }
}
