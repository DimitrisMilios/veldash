package com.veldash.map

import android.content.Context
import android.graphics.Color
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.offline.OfflineManager

/**
 * Every MapLibre knob that affects RAM, GPU load or frame pacing, in one place.
 * All methods below were verified against the 11.8.8 AAR.
 */
object MapSetup {

    /** Disk ambient cache (SQLite) cap. Only populated by network tiles; local mbtiles bypass it. */
    private const val AMBIENT_CACHE_BYTES = 20L * 1024 * 1024

    /**
     * How many zoom levels of parent tiles to prefetch. Default is 4. Each extra level costs a few
     * extra tiles in memory; 2 still gives a low-res placeholder during zoom so nothing flashes blank.
     */
    private const val PREFETCH_ZOOM_DELTA = 0

    /** Chase-camera pitch. Beyond ~55 the horizon pulls in many far tiles per frame. */
    const val MAX_PITCH = 50.0

    /** Options for the programmatic MapView constructor. */
    fun options(context: Context): MapLibreMapOptions =
        MapLibreMapOptions.createFromAttributes(context)
            // SurfaceView, not TextureView. TextureView costs an extra GPU copy per frame.
            .textureMode(false)
            .translucentTextureSurface(false)
            .foregroundLoadColor(Color.BLACK)
            // Pitch is capped, not banned: the 3D chase view uses MAX_PITCH; no 3D buildings exist
            // in the style, so this is only a perspective transform, not extra geometry.
            .minPitchPreference(0.0)
            .maxPitchPreference(MAX_PITCH)
            .tiltGesturesEnabled(false)
            .rotateGesturesEnabled(false)
            .scrollGesturesEnabled(true)
            .zoomGesturesEnabled(true)
            .doubleTapGesturesEnabled(true)
            .quickZoomGesturesEnabled(true)
            // No overlay widgets: each one is an extra View + drawable in the hierarchy.
            .compassEnabled(false)
            .logoEnabled(false)
            .attributionEnabled(false)
            .setPrefetchesTiles(true)
            .setPrefetchZoomDelta(PREFETCH_ZOOM_DELTA)
            // Single source, so cross-source label collision checks are wasted work.
            .crossSourceCollisions(false)
            .apply { renderSurfaceOnTop(false) }

    /** Post-ready tuning that only exists on the MapLibreMap instance. */
    fun tune(map: MapLibreMap) {
        // Keep the in-memory tile cache: it holds parent/child tiles so pan-back and zoom are instant.
        // MapLibre sizes it from the viewport; there is no byte cap API, so RAM is controlled through
        // style layer count (BatStyle), prefetch delta (above) and onLowMemory() (MainActivity).
        map.setTileCacheEnabled(false)
        map.setDebugActive(false)
        map.setMinPitchPreference(0.0)
        map.setMaxPitchPreference(MAX_PITCH)
        map.uiSettings.apply {
            isCompassEnabled = false
            isLogoEnabled = false
            isAttributionEnabled = false
            isTiltGesturesEnabled = false
            isRotateGesturesEnabled = false
            isDisableRotateWhenScaling = true
            isIncreaseRotateThresholdWhenScaling = true
        }
    }

    /** Cap the on-disk ambient cache. Async; fire once per process. */
    fun capDiskCache(context: Context) {
        OfflineManager.getInstance(context).setMaximumAmbientCacheSize(
            AMBIENT_CACHE_BYTES,
            object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = Unit
                override fun onError(message: String) = Unit
            },
        )
    }
}
