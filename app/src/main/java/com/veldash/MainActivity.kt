package com.veldash

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentCallbacks2
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.veldash.databinding.ActivityMainBinding
import com.veldash.location.Fix
import com.veldash.location.LocationBus
import com.veldash.location.LocationService
import com.veldash.map.BatStyle
import com.veldash.map.BatmobileMarker
import com.veldash.map.MapFile
import com.veldash.map.MapRepository
import com.veldash.map.MapSetup
import com.veldash.util.Bg
import com.veldash.util.Prefs
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.maps.renderer.MapRenderer
import java.io.File

/**
 * Hosts the MapView. Plain android.app.Activity: no AppCompat, no Fragment, no ViewModel.
 *
 * The Activity is never recreated (see configChanges in the manifest), so the MapView and its
 * GL context live exactly as long as the process foreground session.
 */
class MainActivity : Activity(), LocationBus.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mapView: MapView
    private lateinit var prefs: Prefs

    private var map: MapLibreMap? = null
    private var current: MapFile? = null

    private val marker = BatmobileMarker(this)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Camera tracks the batmobile, heading-up. Switched off by a pan gesture, on by the recenter button. */
    private var follow = true
    private var askedLocation = false

    /** Every 2 s: if no fix arrived recently, grey out the speed readout. */
    private val staleTick = object : Runnable {
        override fun run() {
            if (!LocationBus.isFresh()) showSpeedUnavailable()
            mainHandler.postDelayed(this, STALE_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Native renderer is loaded here, on first use, not in Application.onCreate.
        MapLibre.getInstance(this)
        MapSetup.capDiskCache(this)

        mapView = MapView(this, MapSetup.options(this)).apply {
            // Render a frame only when something changed. No wasted GPU cycles while idle.
            setRenderingRefreshMode(MapRenderer.RenderingRefreshMode.WHEN_DIRTY)
        }
        binding.mapContainer.addView(
            mapView,
            0,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            MapSetup.tune(m)
            // A drag (not a pinch) means the driver wants to look around: stop following.
            m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: MoveGestureDetector) = setFollow(false)
                override fun onMove(detector: MoveGestureDetector) = Unit
                override fun onMoveEnd(detector: MoveGestureDetector) = Unit
            })
            loadSavedMap()
        }

        binding.btnLoadMap.setOnClickListener { pickMap() }
        binding.btnRecenter.setOnClickListener { setFollow(true) }
    }

    // ---- Lifecycle ----

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        LocationBus.add(this)
        LocationBus.last?.let { onFix(it) }
        mainHandler.post(staleTick)
        ensureLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mainHandler.removeCallbacks(staleTick)
        LocationBus.remove(this)
        mapView.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        // stopWithTask in the manifest also covers swipe-away; this covers a clean finish.
        if (isFinishing) LocationService.stop(this)
        marker.detach()
        mapView.onDestroy()
        map = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    /** MapLibre drops its in-memory tile cache on onLowMemory(); forward the milder trim signals too. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            mapView.onLowMemory()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    /** Immersive sticky: reclaim the nav-bar strip on head units that have one. */
    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    // ---- Location ----

    private fun ensureLocationPermission() {
        if (LocationService.hasLocationPermission(this)) {
            LocationService.start(this)
            return
        }
        if (askedLocation || Build.VERSION.SDK_INT < 23) return
        askedLocation = true
        val perms = ArrayList<String>(3)
        perms += Manifest.permission.ACCESS_FINE_LOCATION
        perms += Manifest.permission.ACCESS_COARSE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), REQ_LOCATION)
    }

    override fun onFix(fix: Fix) {
        marker.update(fix)
        showSpeed(fix)
        if (follow) followCamera(fix, animate = true)
    }

    override fun onGpsAvailable(available: Boolean) {
        if (!available) binding.txtSpeed.setText(R.string.gps_off)
    }

    private fun showSpeed(fix: Fix) {
        binding.txtSpeed.text = getString(R.string.speed_kmh, (fix.speedKmh + 0.5f).toInt())
        binding.txtSpeed.setTextColor(colorOf(R.color.bat_yellow))
    }

    private fun showSpeedUnavailable() {
        binding.txtSpeed.setText(if (LocationBus.gpsAvailable) R.string.gps_searching else R.string.gps_off)
        binding.txtSpeed.setTextColor(colorOf(R.color.bat_text_dim))
    }

    /** Context.getColor(int) is API 23+; this works on 21 without pulling in ContextCompat. */
    @Suppress("DEPRECATION")
    private fun colorOf(id: Int): Int = resources.getColor(id)

    private fun setFollow(on: Boolean) {
        if (follow == on) return
        follow = on
        binding.btnRecenter.visibility = if (on) View.GONE else View.VISIBLE
        if (on) LocationBus.last?.let { followCamera(it, animate = true) }
    }

    /**
     * Heading-up chase camera. One linear ease per fix, lasting exactly the fix interval, so
     * consecutive eases chain into continuous motion instead of a stop-start stutter.
     */
    private fun followCamera(fix: Fix, animate: Boolean) {
        val m = map ?: return
        if (m.style?.isFullyLoaded != true) return
        val zoom = if (m.cameraPosition.zoom < MIN_FOLLOW_ZOOM) NAV_ZOOM else m.cameraPosition.zoom
        val pos = CameraPosition.Builder()
            .target(LatLng(fix.lat, fix.lon))
            .bearing(fix.bearing.toDouble())
            .zoom(zoom)
            .build()
        val update = CameraUpdateFactory.newCameraPosition(pos)
        if (animate) m.easeCamera(update, FOLLOW_EASE_MS, false) else m.moveCamera(update)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_LOCATION -> {
                val i = permissions.indexOf(Manifest.permission.ACCESS_FINE_LOCATION)
                if (i >= 0 && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    LocationService.start(this)
                }
            }
            // Granted or not, the app-private maps dir is always scannable.
            REQ_STORAGE -> scanAndPick()
        }
    }

    // ---- Map loading ----

    private fun loadSavedMap() {
        val path = prefs.mapPath
        if (path == null) {
            showStatus(getString(R.string.no_map_loaded))
            return
        }
        Bg.compute({ MapFile.read(File(path)) }) { mapFile ->
            if (mapFile == null) {
                prefs.mapPath = null
                showStatus(getString(R.string.no_map_loaded))
            } else {
                show(mapFile)
            }
        }
    }

    private fun show(mapFile: MapFile) {
        val m = map ?: return
        current = mapFile
        prefs.mapPath = mapFile.file.absolutePath
        showStatus(mapFile.name)
        marker.detach()

        // Style JSON is built on the main thread: it is string concatenation, sub-millisecond.
        val json = BatStyle.build(this, mapFile)
        m.setStyle(Style.Builder().fromJson(json)) { style ->
            binding.txtStatus.visibility = View.GONE

            // Clamp zoom to what the file contains (+2 overzoom for vector, which scales geometry
            // instead of loading tiles that do not exist).
            val overzoom = if (mapFile.isVector) 2.0 else 0.0
            m.setMinZoomPreference(mapFile.minZoom.toDouble())
            m.setMaxZoomPreference(mapFile.maxZoom + overzoom)

            val fix = LocationBus.last
            marker.attach(style, fix)

            // Camera: batmobile if we have a fix, else the map's own centre or bounds. No animation.
            val center = mapFile.center
            val bounds = mapFile.bounds
            when {
                fix != null && follow -> followCamera(fix, animate = false)
                center != null -> m.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(center, mapFile.centerZoom ?: DEFAULT_ZOOM),
                )
                bounds != null -> m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0))
            }
        }
    }

    private fun showStatus(text: String) {
        binding.txtStatus.text = text
        binding.txtStatus.visibility = View.VISIBLE
    }

    // ---- Map picker ----

    private fun pickMap() {
        if (MapRepository.needsStoragePermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        scanAndPick()
    }

    private fun scanAndPick() {
        Bg.compute({ MapRepository.scan(this) }) { maps ->
            if (isFinishing || isDestroyed) return@compute
            if (maps.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.pick_map)
                    .setMessage(getString(R.string.no_maps_found, MapRepository.mapsDir(this).absolutePath))
                    .setPositiveButton(R.string.ok, null)
                    .show()
                return@compute
            }
            val names = Array(maps.size) { i ->
                val f = maps[i]
                "${f.name}  (${f.format}, z${f.minZoom}-${f.maxZoom})"
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.pick_map)
                .setItems(names) { _, which -> show(maps[which]) }
                .show()
        }
    }

    private companion object {
        const val REQ_STORAGE = 1
        const val REQ_LOCATION = 2

        const val DEFAULT_ZOOM = 12.0
        const val NAV_ZOOM = 16.0
        const val MIN_FOLLOW_ZOOM = 13.0

        /** Matches the GPS interval so eases chain seamlessly. */
        const val FOLLOW_EASE_MS = 1000
        const val STALE_TICK_MS = 2000L
    }
}
