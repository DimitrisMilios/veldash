package com.veldash

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.veldash.map.RouteOverlay
import com.veldash.nav.Navigator
import com.veldash.routing.OfflineRouter
import com.veldash.routing.Route
import com.veldash.routing.Router
import com.veldash.search.Favorites
import com.veldash.search.GeoUri
import com.veldash.search.Place
import com.veldash.ui.Hud
import com.veldash.ui.SearchPanel
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
    private lateinit var hud: Hud
    private lateinit var favorites: Favorites
    private lateinit var searchPanel: SearchPanel

    /** Destination requested before a style was loaded (geo: intent at cold start). */
    private var pendingDestination: LatLng? = null

    private var map: MapLibreMap? = null
    private var current: MapFile? = null

    private val marker = BatmobileMarker(this)
    private val routeOverlay = RouteOverlay(this)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var destination: LatLng? = null
    private var route: Route? = null
    private var navigator: Navigator? = null
    private var lastRerouteMs = 0L

    /** Camera tracks the batmobile, heading-up. Switched off by a pan gesture, on by the recenter button. */
    private var follow = true
    private var askedLocation = false

    /** Every 2 s: if no fix arrived recently, grey out the speed readout. */
    private val staleTick = object : Runnable {
        override fun run() {
            if (!LocationBus.isFresh()) hud.showSpeedUnavailable(LocationBus.gpsAvailable)
            mainHandler.postDelayed(this, STALE_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hud = Hud(this, binding)
        favorites = Favorites(this)
        searchPanel = SearchPanel(
            activity = this,
            b = binding,
            favorites = favorites,
            onPick = { goTo(it) },
            currentDestination = { destination },
            currentPosition = { LocationBus.last?.let { LatLng(it.lat, it.lon) } ?: map?.cameraPosition?.target },
        )
        favorites.load { searchPanel.refresh() }
        // Create the data folders up front so they exist for users copying files in over USB/MTP.
        Bg.execute {
            MapRepository.mapsDir(this)
            OfflineRouter.segmentsDir(this)
        }

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
            // Long-press anywhere = "take me there".
            m.addOnMapLongClickListener { p ->
                setDestination(p)
                true
            }
            loadSavedMap()
        }

        binding.btnLoadMap.setOnClickListener { pickMap() }
        binding.btnRecenter.setOnClickListener { setFollow(true) }
        binding.btnSearch.setOnClickListener { if (searchPanel.isOpen) searchPanel.close() else searchPanel.open() }

        handleGeoIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleGeoIntent(intent)
    }

    /** geo: URIs from other apps: coordinates go straight to routing, free text opens search. */
    private fun handleGeoIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val t = GeoUri.parse(intent.dataString) ?: return
        val p = t.place
        if (p != null) goTo(p) else searchPanel.open(t.query)
    }

    private fun goTo(p: Place) = setDestination(LatLng(p.lat, p.lon))

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
        routeOverlay.detach()
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

    /** Back closes search, then clears the route, then leaves the app. */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            searchPanel.isOpen -> searchPanel.close()
            destination != null -> clearRoute()
            else -> super.onBackPressed()
        }
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

    /**
     * One fix per second drives everything: HUD, marker, camera, off-route detection.
     * While on a route the car and camera use the snapped position and the road bearing,
     * which keeps the batmobile glued to the line instead of wandering with GPS noise.
     */
    override fun onFix(fix: Fix) {
        hud.showSpeed((fix.speedKmh + 0.5f).toInt())

        var lat = fix.lat
        var lon = fix.lon
        var bearing = fix.bearing

        val nav = navigator
        if (nav != null) {
            val s = nav.update(fix.lat, fix.lon)
            when {
                s.arrived -> {
                    navigator = null
                    hud.showArrived()
                }
                nav.isOffRoute -> {
                    hud.showRerouting()
                    maybeReroute(fix)
                }
                else -> {
                    hud.showNavigation(s)
                    if (s.onRoute && fix.speedKmh >= SNAP_MIN_KMH) {
                        lat = s.snappedLat
                        lon = s.snappedLon
                        bearing = s.roadBearing
                    }
                }
            }
        }

        marker.update(lat, lon, bearing)
        if (follow) followCamera(lat, lon, bearing, animate = true)
    }

    override fun onGpsAvailable(available: Boolean) {
        if (!available) hud.showSpeedUnavailable(false)
    }

    private fun setFollow(on: Boolean) {
        if (follow == on) return
        follow = on
        binding.btnRecenter.visibility = if (on) View.GONE else View.VISIBLE
        if (on) LocationBus.last?.let { followCamera(it.lat, it.lon, it.bearing, animate = true) }
    }

    /**
     * Heading-up chase camera. One linear ease per fix, lasting exactly the fix interval, so
     * consecutive eases chain into continuous motion instead of a stop-start stutter.
     */
    private fun followCamera(lat: Double, lon: Double, bearing: Float, animate: Boolean) {
        val m = map ?: return
        if (m.style?.isFullyLoaded != true) return
        val zoom = if (m.cameraPosition.zoom < MIN_FOLLOW_ZOOM) NAV_ZOOM else m.cameraPosition.zoom
        val pos = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .bearing(bearing.toDouble())
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

    // ---- Routing ----

    private fun setDestination(p: LatLng) {
        val m = map
        if (m == null || m.style?.isFullyLoaded != true) {
            // No map yet: remember it and apply once a style is up.
            pendingDestination = p
            return
        }
        destination = p
        route = null
        navigator = null
        routeOverlay.setDestination(p)
        routeOverlay.setRoute(null)

        // Start point: live fix if we have one, else the camera target (handy on an emulator without GPS).
        val fix = LocationBus.last
        val fromLat = fix?.lat ?: m.cameraPosition.target?.latitude ?: return
        val fromLon = fix?.lon ?: m.cameraPosition.target?.longitude ?: return
        requestRoute(fromLat, fromLon, p)
    }

    /** Off-route: recompute from where we are, at most once per [REROUTE_MIN_MS]. */
    private fun maybeReroute(fix: Fix) {
        val dest = destination ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRerouteMs < REROUTE_MIN_MS) return
        lastRerouteMs = now
        requestRoute(fix.lat, fix.lon, dest)
    }

    private fun requestRoute(fromLat: Double, fromLon: Double, dest: LatLng) {
        if (navigator == null) hud.showRouting()
        Router.request(this, fromLat, fromLon, dest.latitude, dest.longitude) { outcome ->
            if (destination != dest) return@request // superseded
            val r = outcome.route
            if (r == null) {
                if (navigator == null) hud.showRouteFailed(outcome.error ?: "?")
                return@request
            }
            route = r
            navigator = Navigator(r)
            routeOverlay.setRoute(r)
            hud.showRouteSummary(r)
            // Drive the HUD immediately rather than waiting for the next fix.
            LocationBus.last?.let { onFix(it) }
        }
    }

    private fun clearRoute() {
        destination = null
        route = null
        navigator = null
        routeOverlay.setRoute(null)
        routeOverlay.setDestination(null)
        hud.showIdle()
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
        routeOverlay.detach()

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
            // Overlays live in the style: re-add them (with current route) on every style load.
            routeOverlay.attach(style, BatmobileMarker.LAYER, route, destination)

            // Camera: batmobile if we have a fix, else the map's own centre or bounds. No animation.
            val center = mapFile.center
            val bounds = mapFile.bounds
            when {
                fix != null && follow -> followCamera(fix.lat, fix.lon, fix.bearing, animate = false)
                center != null -> m.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(center, mapFile.centerZoom ?: DEFAULT_ZOOM),
                )
                bounds != null -> m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0))
            }

            pendingDestination?.let {
                pendingDestination = null
                setDestination(it)
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

        /** Below this the car sits on the raw fix; snapping a parked car looks wrong. */
        const val SNAP_MIN_KMH = 3f
        const val REROUTE_MIN_MS = 10_000L
    }
}
