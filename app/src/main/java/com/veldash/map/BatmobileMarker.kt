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
            // Rotate with the map, not the screen, so the car points along the road.
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconRotate(Expression.get(PROP_BEARING)),
            PropertyFactory.visibility(if (fix != null) Property.VISIBLE else Property.NONE),
        )
        style.addLayer(lyr)

        source = src
        layer = lyr
        visible = fix != null
    }

    fun update(fix: Fix) {
        val src = source ?: return
        src.setGeoJson(feature(fix))
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

    private fun feature(fix: Fix?): Feature {
        val f = if (fix != null) {
            Feature.fromGeometry(Point.fromLngLat(fix.lon, fix.lat))
        } else {
            Feature.fromGeometry(Point.fromLngLat(0.0, 0.0))
        }
        f.addNumberProperty(PROP_BEARING, fix?.bearing ?: 0f)
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

    private companion object {
        const val IMAGE = "batmobile"
        const val SOURCE = "batmobile-src"
        const val LAYER = "batmobile-layer"
        const val PROP_BEARING = "bearing"
    }
}
