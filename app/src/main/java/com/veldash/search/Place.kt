package com.veldash.search

/** A named point: search result, favorite, or parsed coordinate. */
class Place(
    val name: String,
    /** Secondary line (address, region). May be empty. */
    val detail: String,
    val lat: Double,
    val lon: Double,
    /**
     * Logo shown for this place in the search rows and on its destination pin: one of the ids
     * in `PlaceIcons`, or empty for the default, the bat logo. Only saved places carry one.
     */
    val icon: String = "",
) {
    /** Two places within ~5 m are the same spot for favorite matching. */
    fun sameSpot(lat: Double, lon: Double): Boolean =
        Math.abs(this.lat - lat) < 5e-5 && Math.abs(this.lon - lon) < 5e-5

    /** The same spot under a new name, address line or icon. */
    fun with(name: String = this.name, detail: String = this.detail, icon: String = this.icon): Place =
        Place(name, detail, lat, lon, icon)

    /** One-line address: "name, detail", or whichever of the two is set. */
    fun address(): String = when {
        name.isEmpty() -> detail
        detail.isEmpty() -> name
        else -> "$name, $detail"
    }
}
