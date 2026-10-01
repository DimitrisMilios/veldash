package com.veldash.nav

/**
 * Decides whether a touch gesture on the map should free the camera from follow mode.
 * Pure logic, fed by the map's move/scale callbacks, unit-tested without MapLibre.
 *
 * Rules:
 *  - While any gesture is in progress ([busy]) the follow camera stays off the map so it does
 *    not fight the finger.
 *  - The decision is made when the finger lifts: a gesture that changed the zoom (a pinch, or a
 *    one-finger double-tap-and-drag quick zoom) keeps following; only a genuine one-finger drag
 *    whose accumulated distance passed [breakPx] frees the camera. Taps and jitter keep it.
 *  - Two-finger movement (pinch drift) never counts towards the drag distance.
 */
class FollowGate(private val breakPx: Float, private val zoomEps: Double = 0.02) {

    private var scaling = false
    private var moving = false
    private var dragPx = 0f
    private var zoomAtBegin = 0.0

    /** True while a scale or move gesture is in progress. */
    val busy: Boolean get() = scaling || moving

    fun onScaleBegin() {
        scaling = true
    }

    fun onScaleEnd() {
        scaling = false
        dragPx = 0f
    }

    fun onMoveBegin(zoom: Double) {
        moving = true
        dragPx = 0f
        zoomAtBegin = zoom
    }

    fun onMove(dx: Float, dy: Float, pointers: Int) {
        if (!scaling && pointers == 1) dragPx += Math.abs(dx) + Math.abs(dy)
    }

    /** Finger lifted. Returns true when the camera should be freed. */
    fun onMoveEnd(zoom: Double): Boolean {
        moving = false
        val zoomed = Math.abs(zoom - zoomAtBegin) > zoomEps
        val free = !zoomed && dragPx > breakPx
        dragPx = 0f
        return free
    }
}
