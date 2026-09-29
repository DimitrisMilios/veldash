package com.veldash.routing

import org.json.JSONObject

/**
 * Parses an OSRM v5 route response. The Mapbox Directions API v5 emits the same schema,
 * so one parser covers both online back ends. Uses org.json from the platform: no Gson, no Moshi.
 */
object OsrmParser {

    fun parse(json: String, source: Route.Source, polylinePrecision: Int): Route {
        val root = JSONObject(json)
        val code = root.optString("code", "")
        if (code != "Ok") {
            throw RoutingException(root.optString("message", code.ifEmpty { "Bad response" }))
        }
        val routes = root.optJSONArray("routes")
        if (routes == null || routes.length() == 0) throw RoutingException("No route found")
        val r = routes.getJSONObject(0)

        val geom = r.optString("geometry", "")
        if (geom.isEmpty()) throw RoutingException("Route has no geometry")
        val coords = Polyline.decode(geom, polylinePrecision)

        val maneuvers = ArrayList<Maneuver>(32)
        val legs = r.optJSONArray("legs")
        var searchFrom = 0
        if (legs != null) {
            for (li in 0 until legs.length()) {
                val steps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
                for (si in 0 until steps.length()) {
                    val step = steps.getJSONObject(si)
                    val m = step.optJSONObject("maneuver") ?: continue
                    val type = mapType(m.optString("type"), m.optString("modifier")) ?: continue
                    val loc = m.optJSONArray("location") ?: continue
                    val lon = loc.getDouble(0)
                    val lat = loc.getDouble(1)
                    val idx = nearestIndex(coords, lat, lon, searchFrom)
                    searchFrom = idx
                    maneuvers += Maneuver(
                        type = type,
                        lat = lat,
                        lon = lon,
                        pointIndex = idx,
                        distanceToNextM = step.optDouble("distance", 0.0),
                        streetName = step.optString("name", "").ifEmpty { null },
                        exit = m.optInt("exit", 0),
                    )
                }
            }
        }

        return Route(
            lats = coords.lats,
            lons = coords.lons,
            distanceM = r.optDouble("distance", 0.0),
            durationS = r.optDouble("duration", 0.0),
            maneuvers = maneuvers,
            source = source,
        )
    }

    /** OSRM (type, modifier) to our enum. Null = not worth announcing. */
    private fun mapType(type: String, modifier: String): Maneuver.Type? = when (type) {
        "depart" -> Maneuver.Type.DEPART
        "arrive" -> Maneuver.Type.ARRIVE
        "roundabout", "rotary", "roundabout turn" -> Maneuver.Type.ROUNDABOUT
        "off ramp" -> if (modifier.contains("left")) Maneuver.Type.EXIT_LEFT else Maneuver.Type.EXIT_RIGHT
        "fork", "merge", "on ramp" -> when {
            modifier.contains("left") -> Maneuver.Type.KEEP_LEFT
            modifier.contains("right") -> Maneuver.Type.KEEP_RIGHT
            else -> null
        }
        "turn", "end of road", "continue", "new name" -> fromModifier(modifier)
        else -> null
    }

    private fun fromModifier(modifier: String): Maneuver.Type? = when (modifier) {
        "uturn" -> Maneuver.Type.UTURN
        "sharp left" -> Maneuver.Type.TURN_SHARP_LEFT
        "left" -> Maneuver.Type.TURN_LEFT
        "slight left" -> Maneuver.Type.TURN_SLIGHT_LEFT
        "sharp right" -> Maneuver.Type.TURN_SHARP_RIGHT
        "right" -> Maneuver.Type.TURN_RIGHT
        "slight right" -> Maneuver.Type.TURN_SLIGHT_RIGHT
        else -> null // "straight" on continue / new name: no instruction needed
    }

    /** Index of the geometry point closest to (lat, lon), scanning forward from [from]. */
    private fun nearestIndex(c: Polyline.Coords, lat: Double, lon: Double, from: Int): Int {
        var best = from
        var bestD = Double.MAX_VALUE
        val cosLat = Math.cos(Math.toRadians(lat))
        for (i in from until c.lats.size) {
            val dLat = c.lats[i] - lat
            val dLon = (c.lons[i] - lon) * cosLat
            val d = dLat * dLat + dLon * dLon
            if (d < bestD) {
                bestD = d
                best = i
                if (d == 0.0) break
            }
        }
        return best
    }
}
