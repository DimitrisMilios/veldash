package com.veldash.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowGateTest {

    private val gate = FollowGate(breakPx = 24f)

    @Test
    fun oneFingerDragFreesTheCamera() {
        gate.onMoveBegin(17.0)
        assertTrue(gate.busy)
        repeat(10) { gate.onMove(5f, 0f, 1) }
        assertTrue(gate.onMoveEnd(17.0))
        assertFalse(gate.busy)
    }

    @Test
    fun tapOrJitterKeepsFollowing() {
        gate.onMoveBegin(17.0)
        gate.onMove(3f, 2f, 1)
        gate.onMove(2f, 1f, 1)
        assertFalse(gate.onMoveEnd(17.0))
    }

    @Test
    fun pinchKeepsFollowingEvenWithDrift() {
        gate.onMoveBegin(17.0)
        gate.onScaleBegin()
        repeat(20) { gate.onMove(8f, 8f, 2) } // two-finger drift, never counted
        gate.onScaleEnd()
        assertFalse(gate.onMoveEnd(15.5)) // zoom changed
    }

    @Test
    fun pinchDriftWithoutScaleCallbackStillIgnoredByPointerCount() {
        gate.onMoveBegin(17.0)
        repeat(20) { gate.onMove(8f, 8f, 2) }
        assertFalse(gate.onMoveEnd(17.0))
    }

    @Test
    fun quickZoomDragKeepsFollowing() {
        // Double-tap-and-drag: one pointer, lots of distance, but the zoom moved.
        gate.onMoveBegin(17.0)
        repeat(30) { gate.onMove(0f, 10f, 1) }
        assertFalse(gate.onMoveEnd(16.2))
    }

    @Test
    fun dragAfterAPinchStartsFresh() {
        gate.onMoveBegin(17.0)
        gate.onScaleBegin()
        gate.onMove(10f, 10f, 2)
        gate.onScaleEnd()
        assertFalse(gate.onMoveEnd(16.0))

        gate.onMoveBegin(16.0)
        repeat(6) { gate.onMove(5f, 0f, 1) }
        assertTrue(gate.onMoveEnd(16.0))
    }
}
