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
 * The batmobile: our own-position marker.
 *
 * Deliberately NOT MapLibre's LocationComponent, which adds four layers, a pulsing animation,
 * a compass engine and a stack of classes. This is one GeoJSON source, one symbol layer and one
 * bitmap ([BatArt.car], swapped with the view mode). Updating it is a single setGeoJson() call per second.
 *
 * Lives on top of the style, so it must be re-attached every time a new style is set.
 */
class BatmobileMarker {

    private var style: Style? = null
    private var art: Bitmap? = null
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
            // Shrink with zoom so a zoomed-out map is not dominated by the car.
            PropertyFactory.iconSize(
                Expression.interpolate(
                    Expression.exponential(2f), Expression.zoom(),
                    Expression.stop(12, 0.3f), Expression.stop(15, 0.55f),
                    Expression.stop(17, 0.85f), Expression.stop(19, 1.2f),
                ),
            ),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            // Rotate with the map, not the screen, so the car points along the road...
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            // ...but draw it flat to the screen so the pitched 3D view does not squash it.
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconRotate(Expression.get(PROP_BEARING)),
            PropertyFactory.visibility(if (fix != null) Property.VISIBLE else Property.NONE),
        )
        style.addLayer(lyr)

        this.style = style
        source = src
        layer = lyr
        visible = fix != null
    }

    /** Car image for the current view mode. Re-adding an image under the same name replaces it in place. */
    fun setArt(bmp: Bitmap) {
        if (bmp === art) return
        art = bmp
        style?.addImage(IMAGE, bmp)
    }

    fun update(fix: Fix) = update(fix.lat, fix.lon, fix.bearing)

    /**
     * Position the car. Called up to 30 times a second while moving, so the GeoJSON is written
     * as a string straight into a reused StringBuilder: no Feature/Point objects, no Gson.
     */
    fun update(lat: Double, lon: Double, bearing: Float) {
        val src = source ?: return
        val sb = json
        sb.setLength(0)
        sb.append("{\"type\":\"Feature\",\"properties\":{\"").append(PROP_BEARING).append("\":")
            .append(bearing).append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
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
        if (fix != null) feature(fix.lat, fix.lon, fix.bearing) else feature(0.0, 0.0, 0f)

    private fun feature(lat: Double, lon: Double, bearing: Float): Feature {
        val f = Feature.fromGeometry(Point.fromLngLat(lon, lat))
        f.addNumberProperty(PROP_BEARING, bearing)
        return f
    }


    companion object {
        /** Public so other overlays can insert themselves below the car. */
        const val LAYER = "batmobile-layer"
        private const val IMAGE = "batmobile"
        private const val SOURCE = "batmobile-src"
        private const val PROP_BEARING = "bearing"
    }
}
