package com.veldash.ui

import com.veldash.R

/**
 * The logos a saved place can wear in the search rows and on its destination pin: five Gotham
 * badges (drawable-nodpi PNGs, 256 px, sources in /art) keyed by a short id that is stored
 * with the place ([com.veldash.search.Place.icon]). An empty or unknown id means the default,
 * the bat logo.
 */
object PlaceIcons {

    const val BATCAVE = "batcave"
    const val WAYNE = "wayne"
    const val CATWOMAN = "catwoman"
    const val JOKER = "joker"
    const val RIDDLER = "riddler"

    /** Picker order (after the bat logo, which is the empty id). */
    val ALL: List<String> = listOf(BATCAVE, WAYNE, CATWOMAN, JOKER, RIDDLER)

    /** The PNG for [id], or 0 for the default (the bat logo). */
    fun drawable(id: String): Int = when (id) {
        BATCAVE -> R.drawable.logo_batcave
        WAYNE -> R.drawable.logo_wayne
        CATWOMAN -> R.drawable.logo_catwoman
        JOKER -> R.drawable.logo_joker
        RIDDLER -> R.drawable.logo_riddler
        else -> 0
    }

    /** Accessibility name of [id]. */
    fun label(id: String): Int = when (id) {
        BATCAVE -> R.string.icon_batcave
        WAYNE -> R.string.icon_wayne
        CATWOMAN -> R.string.icon_catwoman
        JOKER -> R.string.icon_joker
        RIDDLER -> R.string.icon_riddler
        else -> R.string.icon_default
    }
}
