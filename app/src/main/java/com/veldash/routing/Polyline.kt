package com.veldash.routing

/** Google encoded polyline decoder (precision 5 for OSRM default, 6 for polyline6). */
object Polyline {

    class Coords(val lats: DoubleArray, val lons: DoubleArray)

    fun decode(encoded: String, precision: Int): Coords {
        val scale = when (precision) {
            5 -> 1e5
            6 -> 1e6
            else -> Math.pow(10.0, precision.toDouble())
        }
        // Upper bound on point count: every point needs at least 2 chars.
        var lats = DoubleArray(encoded.length / 2 + 1)
        var lons = DoubleArray(lats.size)
        var n = 0
        var index = 0
        var lat = 0
        var lon = 0
        val len = encoded.length

        while (index < len) {
            var shift = 0
            var result = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < len)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < len)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            if (n == lats.size) {
                lats = lats.copyOf(n * 2)
                lons = lons.copyOf(n * 2)
            }
            lats[n] = lat / scale
            lons[n] = lon / scale
            n++
        }
        return Coords(lats.copyOf(n), lons.copyOf(n))
    }
}
