package com.veldash.search

import com.veldash.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Destination lookup.
 *
 *  - [parseCoordinates] works offline: "37.98, 23.72" or "37.98 23.72".
 *  - [search] is online: Mapbox Geocoding when a token is configured, else Nominatim (OSM).
 *    Nominatim's usage policy: identify the app, at most one request per second, no autocomplete.
 *    We only query on an explicit search action, never per keystroke.
 */
object Geocoder {

    private const val LIMIT = 8

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private val coordRegex = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*[,;\s]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")

    /** "lat, lon" in decimal degrees, or null. */
    fun parseCoordinates(q: String): Place? {
        val m = coordRegex.find(q) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
        return Place(String.format(Locale.US, "%.5f, %.5f", lat, lon), "", lat, lon)
    }

    /** Online search, biased towards (nearLat, nearLon) when given. Background thread only. */
    @Throws(IOException::class)
    fun search(query: String, nearLat: Double?, nearLon: Double?): List<Place> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return if (BuildConfig.MAPBOX_TOKEN.isNotEmpty()) {
            var url = "https://api.mapbox.com/geocoding/v5/mapbox.places/$q.json" +
                "?access_token=${BuildConfig.MAPBOX_TOKEN}&limit=$LIMIT"
            if (nearLat != null && nearLon != null) {
                url += String.format(Locale.US, "&proximity=%.5f,%.5f", nearLon, nearLat)
            }
            parseMapbox(get(url))
        } else {
            var url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=$LIMIT&q=$q"
            if (nearLat != null && nearLon != null) {
                // Soft bias: results inside the box rank first, outside still allowed.
                url += String.format(
                    Locale.US, "&viewbox=%.3f,%.3f,%.3f,%.3f&bounded=0",
                    nearLon - 2.0, nearLat + 2.0, nearLon + 2.0, nearLat - 2.0,
                )
            }
            parseNominatim(get(url))
        }
    }

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "veldash/${BuildConfig.VERSION_NAME} (Android head-unit navigation)")
            .header("Accept-Language", Locale.getDefault().language)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IOException("Empty body")
        }
    }

    fun parseNominatim(json: String): List<Place> {
        val arr = JSONArray(json)
        val out = ArrayList<Place>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val display = o.optString("display_name", "")
            val name = o.optString("name", "").ifEmpty { display.substringBefore(',').trim() }
            val detail = display.removePrefix(name).trimStart(',', ' ')
            out += Place(name, detail, o.getDouble("lat"), o.getDouble("lon"))
        }
        return out
    }

    fun parseMapbox(json: String): List<Place> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Place>(features.length())
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val center = f.optJSONArray("center") ?: continue
            val name = f.optString("text", "")
            val full = f.optString("place_name", "")
            val detail = full.removePrefix(name).trimStart(',', ' ')
            out += Place(name.ifEmpty { full }, detail, center.getDouble(1), center.getDouble(0))
        }
        return out
    }
}
