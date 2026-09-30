package com.veldash.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.veldash.R
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
 * bitmap. Updating it is a single setGeoJson() call per second.
 *
 * Lives on top of the style, so it must be re-attached every time a new style is set.
 */
class BatmobileMarker(private val context: Context) {

    private var source: GeoJsonSource? = null
    private var layer: SymbolLayer? = null
    private var visible = false

    /** Adds image, source and layer to a freshly loaded [style]. */
    fun attach(style: Style, fix: Fix?) {
        style.addImage(IMAGE, rasterise())

        val src = GeoJsonSource(SOURCE, feature(fix))
        style.addSource(src)

        val lyr = SymbolLayer(LAYER, SOURCE).withProperties(
            PropertyFactory.iconImage(IMAGE),
            PropertyFactory.iconSize(1f),
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

        source = src
        layer = lyr
        visible = fix != null
    }

    fun update(fix: Fix) = update(fix.lat, fix.lon, fix.bearing)

    /** Position the car explicitly, e.g. at the route-snapped point while navigating. */
    fun update(lat: Double, lon: Double, bearing: Float) {
        val src = source ?: return
        src.setGeoJson(feature(lat, lon, bearing))
        if (!visible) {
            layer?.setProperties(PropertyFactory.visibility(Property.VISIBLE))
            visible = true
        }
    }

    fun detach() {
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

    /** Vector drawable to ARGB bitmap, once per attach. ~32x48 px at mdpi: a few KB. */
    private fun rasterise(): Bitmap {
        val d = context.getDrawable(R.drawable.ic_batmobile)!!
        val w = d.intrinsicWidth.coerceAtLeast(1)
        val h = d.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bmp))
        return bmp
    }

    companion object {
        /** Public so other overlays can insert themselves below the car. */
        const val LAYER = "batmobile-layer"
        private const val IMAGE = "batmobile"
        private const val SOURCE = "batmobile-src"
        private const val PROP_BEARING = "bearing"
    }
}
