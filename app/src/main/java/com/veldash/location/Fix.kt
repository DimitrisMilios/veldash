package com.veldash.location

/**
 * One processed GPS fix. Immutable, allocation-light (no Location object retained).
 *
 * @param bearing   Degrees clockwise from north. Sticky: when the unit is stationary the last
 *                  good bearing is carried forward so the batmobile does not spin at red lights.
 * @param elapsedMs SystemClock.elapsedRealtime() at which the fix was taken (not received), so
 *                  a stale last-known location reports its true age.
 */
class Fix(
    val lat: Double,
    val lon: Double,
    val bearing: Float,
    val speedKmh: Float,
    val accuracyM: Float,
    val elapsedMs: Long,
)
