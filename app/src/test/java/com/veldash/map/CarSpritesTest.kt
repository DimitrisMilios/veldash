package com.veldash.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarSpritesTest {

    private val c = CarSprites.Choice()

    @Test
    fun flatViewRotatesTheTopDownFrame() {
        CarSprites.choose(0f, 30f, c)
        assertEquals(0, c.tiltIndex)
        assertEquals(0, c.yawStep)
        assertEquals(30f, c.rotation, 1e-4f)
    }

    @Test
    fun midSwitchPicksNearestTiltFrame() {
        CarSprites.choose(23f, -10f, c)
        assertEquals(5, c.tiltIndex) // 23 / 5 = 4.6 -> 5
        assertEquals(0, c.yawStep)
        assertEquals(-10f, c.rotation, 1e-4f)
    }

    @Test
    fun fullTiltRightTurnUsesOrbitFrame() {
        CarSprites.choose(55f, 30f, c)
        assertEquals(2, c.yawStep)
        assertFalse(c.mirror)
        assertEquals(0f, c.rotation, 1e-4f)
    }

    @Test
    fun fullTiltLeftTurnMirrorsAndKeepsRemainder() {
        CarSprites.choose(55f, -40f, c)
        assertEquals(3, c.yawStep) // 40 / 15 = 2.67 -> 3
        assertTrue(c.mirror)
        assertEquals(5f, c.rotation, 1e-4f) // -40 - (-45)
    }

    @Test
    fun smallHeadingAtFullTiltIsJustRotation() {
        CarSprites.choose(55f, 5f, c)
        assertEquals(0, c.yawStep)
        assertEquals(5f, c.rotation, 1e-4f)
    }

    @Test
    fun orbitStepsAreCapped() {
        CarSprites.choose(55f, 179f, c)
        assertEquals(CarSprites.YAW_STEPS - 1, c.yawStep)
    }

    @Test
    fun normalizeWrapsIntoHalfOpenRange() {
        assertEquals(-90f, CarSprites.normalize(270f), 1e-4f)
        assertEquals(180f, CarSprites.normalize(-180f), 1e-4f)
        assertEquals(180f, CarSprites.normalize(540f), 1e-4f)
        assertEquals(0f, CarSprites.normalize(720f), 1e-4f)
    }
}
