package com.veldash.map

import android.animation.ValueAnimator
import android.content.Context
import android.view.animation.LinearInterpolator
import com.veldash.routing.Route
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Route polyline + destination pin (the bat logo, [BatArt.pin]), inserted below the batmobile so
 * the car always draws on top of the line.
 *
 * The pin stands upright (viewport pitch) over a pool of light and a contact dot that lie flat
 * on the road (map pitch), so in the tilted chase view the pool foreshortens with the street
 * and the pin reads as a 3D object planted in it. A new destination drops in with a bounce.
 *
 * Geometry is handed to MapLibre as a GeoJSON string built directly from the primitive arrays.
 * Going through Feature/Point objects would allocate one object per vertex and then serialise
 * them with Gson anyway; the string path skips both.
 */
class RouteOverlay(private val context: Context) {

    private var routeSource: GeoJsonSource? = null
    private var destSource: GeoJsonSource? = null
    private var pinLayer: SymbolLayer? = null
    private var glowLayer: CircleLayer? = null
    private var drop: ValueAnimator? = null
    /** Lazy: the overlay is built as an Activity field, before the Activity has resources. */
    private val density by lazy { context.resources.displayMetrics.density }

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
        // Light pool on the ground: one blurred circle, flat on the map, grows with zoom.
        val glow = CircleLayer(LAYER_DEST_GLOW, SRC_DEST).withProperties(
            PropertyFactory.circleColor(ROUTE_COLOR),
            PropertyFactory.circleOpacity(GLOW_OPACITY),
            PropertyFactory.circleBlur(1f),
            PropertyFactory.circleRadius(
                Expression.interpolate(
                    Expression.exponential(1.5f), Expression.zoom(),
                    Expression.stop(12, 10f), Expression.stop(16, 26f), Expression.stop(19, 60f),
                ),
            ),
            PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
            PropertyFactory.circlePitchScale(Property.CIRCLE_PITCH_SCALE_MAP),
        )
        style.addLayerBelow(glow, aboveLayerId)
        // Contact dot where the spike meets the road.
        style.addLayerBelow(
            CircleLayer(LAYER_DEST_DOT, SRC_DEST).withProperties(
                PropertyFactory.circleColor(ROUTE_COLOR),
                PropertyFactory.circleRadius(4f),
                PropertyFactory.circleStrokeColor(CASING_COLOR),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
            ),
            aboveLayerId,
        )
        val pin = SymbolLayer(LAYER_DEST, SRC_DEST).withProperties(
            PropertyFactory.iconImage(IMAGE_DEST),
            PropertyFactory.iconSize(1f),
            // The spike tip is the destination point.
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            // The drop animation lifts the pin straight up the screen, whatever the map bearing.
            PropertyFactory.iconTranslateAnchor(Property.ICON_TRANSLATE_ANCHOR_VIEWPORT),
        )
        style.addLayerBelow(pin, aboveLayerId)

        routeSource = rs
        destSource = ds
        pinLayer = pin
        glowLayer = glow
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
        if (p != null) dropIn() else drop?.cancel()
    }

    fun detach() {
        drop?.cancel()
        drop = null
        routeSource = null
        destSource = null
        pinLayer = null
        glowLayer = null
    }

    /**
     * The pin falls from [DROP_DP] above, lands with one small hop, and the light pool fades up
     * as it lands. ~36 frames once per destination; the map renders on demand otherwise.
     */
    private fun dropIn() {
        val pin = pinLayer ?: return
        val glow = glowLayer
        drop?.cancel()
        val lift = DROP_DP * density
        drop = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DROP_MS
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                pin.setProperties(
                    PropertyFactory.iconTranslate(arrayOf(0f, -lift * (1f - dropCurve(t)))),
                    PropertyFactory.iconOpacity((t * 4f).coerceAtMost(1f)),
                )
                glow?.setProperties(PropertyFactory.circleOpacity(GLOW_OPACITY * ((t - 0.4f) / 0.6f).coerceIn(0f, 1f)))
            }
            start()
        }
    }

    /** 0 = lifted, 1 = landed. Gravity fall to 60%, then one hop of ~12% of the lift, and settle. */
    private fun dropCurve(t: Float): Float {
        if (t < FALL_END) {
            val f = t / FALL_END
            return f * f
        }
        val s = (t - FALL_END) / (1f - FALL_END)
        return 1f - HOP * Math.sin(Math.PI * s).toFloat() * (1f - s)
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
        const val LAYER_DEST_GLOW = "dest-glow"
        const val LAYER_DEST_DOT = "dest-dot"
        const val IMAGE_DEST = "bat-signal"

        const val ROUTE_COLOR = "#FFE600"
        const val CASING_COLOR = "#000000"
        const val CASING_EXTRA = 3f

        const val GLOW_OPACITY = 0.45f
        const val DROP_DP = 90f
        const val DROP_MS = 650L
        const val FALL_END = 0.6f
        const val HOP = 0.2f

        const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"
    }
}
