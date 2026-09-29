package com.veldash.search

import java.net.URLDecoder

/**
 * Parses Android geo: URIs handed to us by other apps. Pure string code so it unit-tests on the JVM.
 *
 *   geo:37.98,23.72
 *   geo:37.98,23.72?z=15
 *   geo:0,0?q=37.98,23.72(Label)
 *   geo:0,0?q=Syntagma+Square
 */
object GeoUri {

    /** Either a resolved [place] or a free-text [query] to geocode. */
    class Target(val place: Place?, val query: String?)

    fun parse(uri: String?): Target? {
        if (uri == null || !uri.startsWith("geo:", ignoreCase = true)) return null
        val body = uri.substring(4)
        val qIdx = body.indexOf('?')
        val coordPart = if (qIdx >= 0) body.substring(0, qIdx) else body
        val query = if (qIdx >= 0) param(body.substring(qIdx + 1), "q") else null

        // 1. q=lat,lon(label) or q=lat,lon
        if (query != null) {
            val label = Regex("""\((.*)\)\s*$""").find(query)?.groupValues?.get(1)
            val coordText = query.substringBefore('(').trim()
            Geocoder.parseCoordinates(coordText)?.let { p ->
                return Target(if (label.isNullOrBlank()) p else Place(label, "", p.lat, p.lon), null)
            }
        }

        // 2. geo:lat,lon (ignore the 0,0 placeholder that means "use q")
        val coords = coordPart.split(',')
        if (coords.size >= 2) {
            val lat = coords[0].trim().toDoubleOrNull()
            val lon = coords[1].trim().toDoubleOrNull()
            if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) {
                Geocoder.parseCoordinates("$lat,$lon")?.let { return Target(it, null) }
            }
        }

        // 3. free text
        return if (!query.isNullOrBlank()) Target(null, query.trim()) else null
    }

    private fun param(query: String, key: String): String? {
        for (kv in query.split('&')) {
            val eq = kv.indexOf('=')
            val k = if (eq >= 0) kv.substring(0, eq) else kv
            if (k == key) {
                val raw = if (eq >= 0) kv.substring(eq + 1) else ""
                return try {
                    URLDecoder.decode(raw, "UTF-8")
                } catch (e: Exception) {
                    raw
                }
            }
        }
        return null
    }
}
