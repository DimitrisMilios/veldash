package com.veldash.map

import android.graphics.Bitmap
import com.veldash.location.Fix
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

/**
 * The batmobile: our own-position marker while the camera is free (panned away).
 *
 * Deliberately NOT MapLibre's LocationComponent, which adds four layers, a pulsing animation,
 * a compass engine and a stack of classes. This is one GeoJSON source, one symbol layer and one
 * bitmap. Two modes, switched with [setArt]:
 *  - chase (3D view): the [CarSprites] render for the car's heading relative to the camera,
 *    drawn upright to the screen with a small leftover rotation, like the follow-mode overlay.
 *  - flat (2D view): the top-down render laid on the road (pitch and rotation aligned to the
 *    map) and turned to the heading, so the map projects it like a decal.
 * In follow mode the car is a screen overlay instead. Updating it is one setGeoJson().
 *
 * Lives on top of the style, so it must be re-attached every time a new style is set.
 */
class BatmobileMarker {

    private var style: Style? = null
    private var art: Bitmap? = null
    private var chase = false
    private var source: GeoJsonSource? = null
    private var layer: SymbolLayer? = null
    private var visible = false

    /** Adds image, source and layer to a freshly loaded [style]. */
    fun attach(style: Style, fix: Fix?) {
        art?.let { style.addImage(IMAGE, it) }

        val src = GeoJsonSource(SOURCE, feature(fix))
        style.addSource(src)

        val lyr = SymbolLayer(LAYER, SOURCE).withProperties(
            PropertyFactory.iconImage(IMAGE),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconRotate(Expression.get(PROP_ROTATE)),
            PropertyFactory.visibility(if (fix != null) Property.VISIBLE else Property.NONE),
        )
        applyMode(lyr)
        style.addLayer(lyr)

        this.style = style
        source = src
        layer = lyr
        visible = fix != null
    }

    /**
     * Car image and mode. [chase]: the bitmap is a chase render to draw upright (rotation = the
     * small leftover screen rotation); otherwise the top-down render, laid flat on the road
     * (rotation = heading). Re-adding an image under the same name replaces it in place.
     */
    fun setArt(bmp: Bitmap, chase: Boolean) {
        if (chase != this.chase) {
            this.chase = chase
            layer?.let { applyMode(it) }
        }
        if (bmp === art) return
        art = bmp
        style?.addImage(IMAGE, bmp)
    }

    /**
     * Alignment and size for the mode. Size follows the zoom gently (half the map's rate) and
     * stays readable: never a speck zoomed out, never filling the screen zoomed in. The flat
     * decal gets a little extra because foreshortening on a tilted map makes it look smaller.
     */
    private fun applyMode(lyr: SymbolLayer) {
        if (chase) {
            lyr.setProperties(
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconSize(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(13, 0.6f), Expression.stop(15, 0.7f),
                        Expression.stop(17, 1.0f), Expression.stop(18, 1.15f),
                    ),
                ),
            )
        } else {
            lyr.setProperties(
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconSize(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(13, 0.75f), Expression.stop(15, 0.9f),
                        Expression.stop(17, 1.25f), Expression.stop(18, 1.45f),
                    ),
                ),
            )
        }
    }

    fun update(fix: Fix) = update(fix.lat, fix.lon, fix.bearing)

    /**
     * Position the car; [rotate] is the heading (flat) or the leftover screen rotation (chase).
     * Called up to 30 times a second while moving, so the GeoJSON is written as a string
     * straight into a reused StringBuilder: no Feature/Point objects, no Gson.
     */
    fun update(lat: Double, lon: Double, rotate: Float) {
        val src = source ?: return
        val sb = json
        sb.setLength(0)
        sb.append("{\"type\":\"Feature\",\"properties\":{\"").append(PROP_ROTATE).append("\":")
            .append(rotate).append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            .append(lon).append(',').append(lat).append("]}}")
        src.setGeoJson(sb.toString())
        if (!visible) {
            layer?.setProperties(PropertyFactory.visibility(Property.VISIBLE))
            visible = true
        }
    }

    private val json = StringBuilder(160)

    /** Hide/show the map-layer car (hidden while the screen overlay is used in follow mode). */
    fun setShown(shown: Boolean) {
        if (shown == visible) return
        val lyr = layer ?: return
        lyr.setProperties(PropertyFactory.visibility(if (shown) Property.VISIBLE else Property.NONE))
        visible = shown
    }

    fun detach() {
        style = null
        source = null
        layer = null
        visible = false
    }

    private fun feature(fix: Fix?): Feature =
        if (fix != null) feature(fix.lat, fix.lon, if (chase) 0f else fix.bearing) else feature(0.0, 0.0, 0f)

    private fun feature(lat: Double, lon: Double, rotate: Float): Feature {
        val f = Feature.fromGeometry(Point.fromLngLat(lon, lat))
        f.addNumberProperty(PROP_ROTATE, rotate)
        return f
    }


    companion object {
        /** Public so other overlays can insert themselves below the car. */
        const val LAYER = "batmobile-layer"
        private const val IMAGE = "batmobile"
        private const val SOURCE = "batmobile-src"
        private const val PROP_ROTATE = "rotate"
    }
}
