package com.veldash.search

/** A named point: search result, favorite, or parsed coordinate. */
class Place(
    val name: String,
    /** Secondary line (address, region). May be empty. */
    val detail: String,
    val lat: Double,
    val lon: Double,
) {
    /** Two places within ~5 m are the same spot for favorite matching. */
    fun sameSpot(lat: Double, lon: Double): Boolean =
        Math.abs(this.lat - lat) < 5e-5 && Math.abs(this.lon - lon) < 5e-5
}
