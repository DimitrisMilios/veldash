package com.veldash.routing

/**
 * A computed route. Geometry is two parallel primitive arrays: no boxing, no per-point objects.
 * A 200 km route is ~5000 points = 80 KB. The same route as List<LatLng> would be ~200 KB plus GC churn.
 */
class Route(
    val lats: DoubleArray,
    val lons: DoubleArray,
    val distanceM: Double,
    val durationS: Double,
    val maneuvers: List<Maneuver>,
    val source: Source,
) {
    val pointCount: Int get() = lats.size

    enum class Source { MAPBOX, OSRM, BROUTER }
}

/**
 * One turn instruction, engine-agnostic.
 *
 * @param pointIndex     Index into Route.lats/lons where the maneuver happens.
 * @param distanceToNextM Metres from this maneuver to the next one (or to arrival).
 */
class Maneuver(
    val type: Type,
    val lat: Double,
    val lon: Double,
    val pointIndex: Int,
    val distanceToNextM: Double,
    val streetName: String?,
    /** Roundabout exit number, 0 when not applicable. */
    val exit: Int,
) {
    enum class Type {
        DEPART,
        CONTINUE,
        TURN_LEFT,
        TURN_SLIGHT_LEFT,
        TURN_SHARP_LEFT,
        TURN_RIGHT,
        TURN_SLIGHT_RIGHT,
        TURN_SHARP_RIGHT,
        KEEP_LEFT,
        KEEP_RIGHT,
        UTURN,
        ROUNDABOUT,
        EXIT_LEFT,
        EXIT_RIGHT,
        ARRIVE,
    }
}

/** Thrown by any engine when no route could be produced. Message is user-presentable. */
class RoutingException(message: String, cause: Throwable? = null) : Exception(message, cause)
