package com.veldash.routing

import android.util.Log
import com.veldash.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Online routing over raw OkHttp. Mapbox Directions API when a token is configured,
 * otherwise the OSRM base URL from BuildConfig. Both answer in OSRM v5 format.
 *
 * Synchronous: call from the background thread only.
 */
object OnlineRouter {

    private const val TAG = "OnlineRouter"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val useMapbox: Boolean get() = BuildConfig.MAPBOX_TOKEN.isNotEmpty()

    @Throws(RoutingException::class)
    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route {
        val coords = String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f", fromLon, fromLat, toLon, toLat)
        val (url, source) = if (useMapbox) {
            "https://api.mapbox.com/directions/v5/mapbox/driving/$coords" +
                "?geometries=polyline6&overview=full&steps=true&access_token=${BuildConfig.MAPBOX_TOKEN}" to
                Route.Source.MAPBOX
        } else {
            "${BuildConfig.OSRM_BASE_URL.trimEnd('/')}/route/v1/driving/$coords" +
                "?geometries=polyline6&overview=full&steps=true" to
                Route.Source.OSRM
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "veldash/${BuildConfig.VERSION_NAME}")
            .get()
            .build()

        val body = try {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful && text.isEmpty()) {
                    throw RoutingException("Routing server error ${resp.code}")
                }
                text
            }
        } catch (e: IOException) {
            Log.w(TAG, "Routing request failed: $url", e)
            throw RoutingException("Network error", e)
        }

        return try {
            OsrmParser.parse(body, source, polylinePrecision = 6)
        } catch (e: RoutingException) {
            throw e
        } catch (e: Exception) {
            throw RoutingException("Bad routing response", e)
        }
    }
}
