package com.veldash.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceIconsTest {

    @Test
    fun everyIdHasItsOwnDrawable() {
        val drawables = PlaceIcons.ALL.map(PlaceIcons::drawable)
        assertTrue(drawables.all { it != 0 })
        assertEquals(drawables.size, drawables.toSet().size)
    }

    @Test
    fun unknownOrEmptyIdMeansTheDefaultGlyph() {
        assertEquals(0, PlaceIcons.drawable(""))
        assertEquals(0, PlaceIcons.drawable("penguin"))
        assertNotEquals(PlaceIcons.label(""), PlaceIcons.label(PlaceIcons.JOKER))
    }
}
