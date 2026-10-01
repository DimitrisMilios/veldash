package com.veldash.map

import android.content.Context
import com.veldash.routing.Route
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Route polyline + destination pin (the bat logo, [BatArt.pin]). Two sources, two layers, inserted below the
 * batmobile so the car always draws on top of the line.
 *
 * Geometry is handed to MapLibre as a GeoJSON string built directly from the primitive arrays.
 * Going through Feature/Point objects would allocate one object per vertex and then serialise
 * them with Gson anyway; the string path skips both.
 */
class RouteOverlay(private val context: Context) {

    private var routeSource: GeoJsonSource? = null
    private var destSource: GeoJsonSource? = null

    /** Adds sources/layers to a freshly loaded style, below [aboveLayerId]. */
    fun attach(style: Style, aboveLayerId: String, route: Route?, destination: LatLng?) {
        style.addImage(IMAGE_DEST, BatArt.pin(context))

        val rs = GeoJsonSource(SRC_ROUTE, route?.let(::lineJson) ?: EMPTY)
        style.addSource(rs)
        // Dark casing under the bright line: keeps the route readable over yellow motorways.
        style.addLayerBelow(
            LineLayer(LAYER_ROUTE_CASING, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(CASING_COLOR),
                PropertyFactory.lineWidth(routeWidth(CASING_EXTRA)),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            ),
            aboveLayerId,
        )
        style.addLayerBelow(
            LineLayer(LAYER_ROUTE, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(ROUTE_COLOR),
                PropertyFactory.lineWidth(routeWidth(0f)),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            ),
            aboveLayerId,
        )

        val ds = GeoJsonSource(SRC_DEST, destination?.let(::pointJson) ?: EMPTY)
        style.addSource(ds)
        style.addLayerBelow(
            SymbolLayer(LAYER_DEST, SRC_DEST).withProperties(
                PropertyFactory.iconImage(IMAGE_DEST),
                PropertyFactory.iconSize(1f),
                // The spike tip is the destination point.
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            ),
            aboveLayerId,
        )

        routeSource = rs
        destSource = ds
    }

    /**
     * Route width grows with zoom, like Google: a thin thread across the city, a band nearly as
     * wide as the road in the close chase view. [extra] widens the casing by a constant.
     */
    private fun routeWidth(extra: Float): Expression = Expression.interpolate(
        Expression.exponential(1.5f), Expression.zoom(),
        Expression.stop(10, 3f + extra),
        Expression.stop(14, 6f + extra),
        Expression.stop(17, 14f + extra),
        Expression.stop(19, 26f + extra),
    )

    fun setRoute(route: Route?) {
        routeSource?.setGeoJson(route?.let(::lineJson) ?: EMPTY)
    }

    fun setDestination(p: LatLng?) {
        destSource?.setGeoJson(p?.let(::pointJson) ?: EMPTY)
    }

    fun detach() {
        routeSource = null
        destSource = null
    }

    private fun lineJson(r: Route): String {
        val sb = StringBuilder(r.pointCount * 22 + 64)
        sb.append("{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
        for (i in 0 until r.pointCount) {
            if (i > 0) sb.append(',')
            sb.append('[').append(round6(r.lons[i])).append(',').append(round6(r.lats[i])).append(']')
        }
        sb.append("]}}")
        return sb.toString()
    }

    private fun pointJson(p: LatLng): String =
        "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Point\",\"coordinates\":[" +
            round6(p.longitude) + "," + round6(p.latitude) + "]}}"

    /** 6 decimals = 11 cm. Shorter strings, and no scientific notation from Double.toString. */
    private fun round6(v: Double): String = (Math.round(v * 1e6) / 1e6).toString()


    private companion object {
        const val SRC_ROUTE = "route-src"
        const val LAYER_ROUTE = "route-line"
        const val LAYER_ROUTE_CASING = "route-casing"
        const val SRC_DEST = "dest-src"
        const val LAYER_DEST = "dest-bat"
        const val IMAGE_DEST = "bat-signal"

        const val ROUTE_COLOR = "#FFE600"
        const val CASING_COLOR = "#000000"
        const val CASING_EXTRA = 3f

        const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"
    }
}
