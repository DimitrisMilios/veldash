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
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import com.veldash.databinding.ActivityMainBinding
import com.veldash.location.Fix
import com.veldash.location.LocationBus
import com.veldash.location.LocationService
import com.veldash.map.BatArt
import com.veldash.map.BatStyle
import com.veldash.map.BatmobileMarker
import com.veldash.map.MapFile
import com.veldash.map.MapRepository
import com.veldash.map.MapSetup
import com.veldash.map.RouteOverlay
import com.veldash.nav.Navigator
import com.veldash.nav.SmoothMotion
import com.veldash.routing.OfflineRouter
import com.veldash.routing.Route
import com.veldash.routing.Router
import com.veldash.search.GeoUri
import com.veldash.search.Place
import com.veldash.search.PlaceStore
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
    private lateinit var favorites: PlaceStore
    private lateinit var recents: PlaceStore
    private lateinit var searchPanel: SearchPanel

    /** Destination requested before a style was loaded (geo: intent at cold start). */
    private var pendingDestination: LatLng? = null

    private var map: MapLibreMap? = null
    private var current: MapFile? = null

    private val marker = BatmobileMarker()
    private val routeOverlay = RouteOverlay(this)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var destination: LatLng? = null
    private var route: Route? = null
    private var navigator: Navigator? = null
    private var lastRerouteMs = 0L

    // ---- Frame-driven motion: the car and camera glide between 1 Hz fixes ----
    private val motion = SmoothMotion()
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }
    private var animating = false
    private var lastFrameNs = 0L
    private var lastApplyNs = 0L
    private var lastHudNs = 0L

    /** While a deliberate camera ease (recenter) plays, per-frame camera moves pause. */
    private var cameraHoldUntilMs = 0L

    // ---- 2D <-> 3D switch: tilt, top padding, zoom and the car art all blend on one curve ----
    /** 0 = flat 2D, 1 = 3D chase. Equals [blendTo] except while a switch plays. */
    private var viewBlend = 1f
    private var blendFrom = 1f
    private var blendTo = 1f
    /** Eased switch progress 0..1 (drives the zoom lerp). */
    private var switchEase = 1f
    private var switchStartMs = 0L
    private var zoomFrom = 0.0
    private var zoomTo = 0.0
    private val switching: Boolean get() = blendFrom != blendTo

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animating) return
            if (lastFrameNs != 0L) motion.step((frameTimeNanos - lastFrameNs) / 1e9)
            lastFrameNs = frameTimeNanos
            // A view switch renders every frame so the tilt is silky; normal tracking is 30 Hz.
            val blendChanged = stepViewSwitch()
            if (blendChanged || frameTimeNanos - lastApplyNs >= APPLY_INTERVAL_NS) {
                lastApplyNs = frameTimeNanos
                applyMotion(blendChanged)
            }
            if (frameTimeNanos - lastHudNs >= HUD_INTERVAL_NS) {
                lastHudNs = frameTimeNanos
                val nav = navigator
                if (nav != null && motion.onRoute) hud.updateTurnDistance(nav.distanceToNextAt(motion.distAlongM))
            }
            // Keep stepping while fixes keep coming (or a switch plays); go idle (zero CPU) otherwise.
            if (switching || LocationBus.isFresh(IDLE_AFTER_MS)) choreographer.postFrameCallback(this) else animating = false
        }
    }

    /** Camera tracks the batmobile, heading-up. Switched off by a pan gesture, on by the recenter button. */
    private var follow = true

    /** 3D chase view (pitched, car in the lower third) vs flat top-down. Persisted. */
    private var view3d = true
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
        favorites = PlaceStore(this, "favorites.json")
        recents = PlaceStore(this, "recents.json", MAX_RECENTS)
        searchPanel = SearchPanel(
            activity = this,
            b = binding,
            prefs = prefs,
            favorites = favorites,
            recents = recents,
            onPick = { goTo(it) },
            currentDestination = { destination },
            currentPosition = { LocationBus.last?.let { LatLng(it.lat, it.lon) } ?: map?.cameraPosition?.target },
        )
        favorites.load { searchPanel.refresh() }
        recents.load { searchPanel.refresh() }
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
        binding.btnEmptyLoadMap.setOnClickListener { pickMap() }
        binding.btnEndRoute.setOnClickListener { clearRoute() }
        binding.btnRecenter.setImageBitmap(BatArt.car(this, view3d = false))
        binding.imgCar2d.setImageBitmap(BatArt.car(this, view3d = false))
        binding.imgCar3d.setImageBitmap(BatArt.car(this, view3d = true))
        binding.btnRecenter.setOnClickListener { setFollow(true) }
        binding.btnSearch.setOnClickListener { if (searchPanel.isOpen) searchPanel.close() else searchPanel.open() }

        view3d = prefs.view3d
        viewBlend = if (view3d) 1f else 0f
        blendFrom = viewBlend
        blendTo = viewBlend
        updateViewModeButton()
        binding.btnViewMode.setOnClickListener {
            view3d = !view3d
            prefs.view3d = view3d
            updateViewModeButton()
            startViewSwitch()
        }

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

    private fun startAnimating() {
        if (animating) return
        animating = true
        lastFrameNs = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopAnimating() {
        animating = false
        choreographer.removeFrameCallback(frameCallback)
    }

    /**
     * Push the interpolated position to the car and, when following, the camera.
     *
     * Following: the camera is moved so the car is at a fixed screen point, and the car is drawn
     * as the screen overlay at exactly that point (heading-up, so it always points straight up).
     * A map-layer marker would lag the camera by a frame and visibly jitter.
     * Not following (or during a recenter ease): the map-layer marker is used instead.
     * [blendChanged]: a 2D/3D switch moved this frame, so tilt the camera even without a fix.
     */
    private fun applyMotion(blendChanged: Boolean = false) {
        if (!motion.hasPosition) {
            if (blendChanged) tiltInPlace()
            return
        }
        val useOverlay = follow && SystemClock.uptimeMillis() >= cameraHoldUntilMs
        if (useOverlay) {
            followCamera(motion.lat, motion.lon, motion.bearing, animate = false)
            marker.setShown(false)
            showCarOverlay(true)
        } else {
            marker.setArt(BatArt.car(this, viewBlend >= 0.5f))
            marker.update(motion.lat, motion.lon, motion.bearing)
            marker.setShown(true)
            showCarOverlay(false)
            if (blendChanged) tiltInPlace()
        }
    }

    /**
     * Start the 2D <-> 3D blend. Tilt, padding (car height on screen) and zoom glide together on
     * one ease-in-out curve driven by the frame loop, while the overhead and chase renders of the
     * car cross-fade and foreshorten. A tap mid-switch reverses smoothly from where it is.
     */
    private fun startViewSwitch() {
        blendFrom = viewBlend
        blendTo = if (view3d) 1f else 0f
        switchEase = 0f
        switchStartMs = SystemClock.uptimeMillis()
        val lat = if (motion.hasPosition) motion.lat else map?.cameraPosition?.target?.latitude ?: 0.0
        zoomTo = navZoom(lat)
        zoomFrom = map?.cameraPosition?.zoom ?: zoomTo
        cameraHoldUntilMs = 0L // the switch owns the camera now
        startAnimating()
    }

    /** Advance the switch. Returns true while it moved the blend this frame (including the last). */
    private fun stepViewSwitch(): Boolean {
        if (!switching) return false
        // A reversal mid-way only travels the remaining distance, so it takes proportionally less time.
        val span = VIEW_SWITCH_MS * Math.abs(blendTo - blendFrom).coerceAtLeast(0.35f)
        val p = ((SystemClock.uptimeMillis() - switchStartMs) / span).coerceIn(0f, 1f)
        switchEase = easeInOutCubic(p)
        viewBlend = blendFrom + (blendTo - blendFrom) * switchEase
        if (p >= 1f) {
            viewBlend = blendTo
            blendFrom = blendTo
        }
        return true
    }

    private fun easeInOutCubic(t: Float): Float =
        if (t < 0.5f) 4f * t * t * t else 1f - Math.pow(-2.0 * t + 2.0, 3.0).toFloat() / 2f

    /** Panned, or no fix yet: tilt around the current camera target only. */
    private fun tiltInPlace() {
        val m = map ?: return
        if (m.style?.isFullyLoaded != true) return
        val cam = CameraPosition.Builder(m.cameraPosition).tilt(MapSetup.MAX_PITCH * viewBlend).build()
        m.moveCamera(CameraUpdateFactory.newCameraPosition(cam))
    }

    /**
     * Follow zoom from a ground distance, not a fixed level, the way Google picks it: the map's
     * short side spans [NAV_SPAN_2D_M] of street in 2D, and 3D sits [NAV_3D_EXTRA_ZOOM] closer
     * (the tilt already shows the road far ahead). A fixed zoom looks far out on a large
     * low-density head unit: the 1080x600 @ 0.75 emulator is 1440x800 dp, 4x a phone's area.
     */
    private fun navZoom(lat: Double): Double {
        val dm = resources.displayMetrics
        val shortPx = minOf(mapView.width, mapView.height).takeIf { it > 0 } ?: minOf(dm.widthPixels, dm.heightPixels)
        val shortDp = shortPx / dm.density
        // MapLibre: 512 dp per world width at zoom 0, so metres per dp = C * cos(lat) / (512 * 2^z).
        val z2d = Math.log(EARTH_CIRCUMFERENCE_M * Math.cos(Math.toRadians(lat)) * shortDp / (512.0 * NAV_SPAN_2D_M)) / Math.log(2.0)
        val z = if (view3d) z2d + NAV_3D_EXTRA_ZOOM else z2d
        return z.coerceIn(MIN_NAV_ZOOM, MAX_NAV_ZOOM)
    }

    /** Top padding (fraction of height) for the current blend: the car sinks as the view tilts. */
    private fun padTop(): Double = PAD_TOP_2D + (PAD_TOP_3D - PAD_TOP_2D) * viewBlend

    /**
     * Both car renders sit centred on the camera target. Mid-switch they cross-fade, and each is
     * squashed vertically toward the other one's proportions, so the car reads as tilting rather
     * than being swapped.
     */
    private fun showCarOverlay(show: Boolean) {
        val b = viewBlend
        if (show) {
            // Camera target with top padding p (fraction of height) sits at y = h(1+p)/2.
            val cy = mapView.height * (1f + padTop().toFloat()) / 2f
            placeCar(binding.imgCar2d, cy, 1f - b, 1f - 0.35f * b)
            placeCar(binding.imgCar3d, cy, b, 0.8f + 0.2f * b)
        } else {
            placeCar(binding.imgCar2d, 0f, 0f, 1f)
            placeCar(binding.imgCar3d, 0f, 0f, 1f)
        }
    }

    private fun placeCar(v: ImageView, centerY: Float, alpha: Float, scaleY: Float) {
        val vis = if (alpha > 0.01f) View.VISIBLE else View.GONE
        if (v.visibility != vis) v.visibility = vis
        if (vis == View.GONE) return
        // Bitmap height, not view.height: the view is GONE (unmeasured) the first time through.
        val h = (v.drawable?.intrinsicHeight ?: 0).toFloat()
        v.y = centerY - h / 2f
        v.pivotY = h / 2f
        v.alpha = alpha
        v.scaleY = scaleY
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
        stopAnimating()
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
            searchPanel.isOpen -> searchPanel.back()
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
     * One fix per second drives the logic: HUD, off-route detection, and a new target for
     * [motion]. The marker and camera themselves are moved per frame by [frameCallback], so
     * the batmobile glides along the road and sweeps through turns instead of stepping.
     */
    override fun onFix(fix: Fix) {
        hud.showSpeed((fix.speedKmh + 0.5f).toInt())

        var state: com.veldash.nav.NavState? = null
        val nav = navigator
        if (nav != null) {
            val s = nav.update(fix.lat, fix.lon)
            when {
                s.arrived -> {
                    navigator = null
                    motion.setNavigator(null)
                    hud.showArrived()
                }
                nav.isOffRoute -> {
                    hud.showRerouting()
                    maybeReroute(fix)
                    state = s
                }
                else -> {
                    hud.showNavigation(s)
                    state = s
                }
            }
        }

        val first = !motion.hasPosition
        motion.onFix(fix.lat, fix.lon, fix.bearing, fix.speedKmh, state)
        if (first) applyMotion()
        startAnimating()
    }

    override fun onGpsAvailable(available: Boolean) {
        if (!available) hud.showSpeedUnavailable(false)
    }

    private fun setFollow(on: Boolean) {
        if (follow == on) return
        follow = on
        binding.btnRecenter.visibility = if (on) View.GONE else View.VISIBLE
        if (on) {
            LocationBus.last?.let { followCamera(it.lat, it.lon, it.bearing, animate = true, forceZoom = true) }
        }
        // Swap between overlay and map marker right away (the ease hold keeps the marker up briefly).
        applyMotion()
    }

    private fun updateViewModeButton() {
        binding.btnViewMode.setText(if (view3d) R.string.view_3d else R.string.view_2d)
    }

    /**
     * Heading-up follow camera, placed once per frame by the frame loop.
     *
     * Tilt, top padding and zoom come from [viewBlend]: 3D is pitched [MapSetup.MAX_PITCH] with
     * the car low on screen (Google-style chase view); 2D is flat with the car a little below
     * centre. Zoom comes from [navZoom]. During a switch the zoom glides between the two.
     * Otherwise the driver's pinch zoom is kept while it stays within a sane navigation range,
     * unless [forceZoom] (recenter, map load) resets it.
     */
    private fun followCamera(lat: Double, lon: Double, bearing: Float, animate: Boolean, forceZoom: Boolean = false) {
        val m = map ?: return
        if (m.style?.isFullyLoaded != true) return
        val h = mapView.height.toDouble()
        val current = m.cameraPosition.zoom
        val nav = navZoom(lat)
        val zoom = when {
            switching -> zoomFrom + (zoomTo - zoomFrom) * switchEase
            // A driver pinch is kept while it stays near street level; recenter snaps back.
            !forceZoom && current in (nav - FOLLOW_ZOOM_OUT)..(nav + FOLLOW_ZOOM_IN) -> current
            else -> nav
        }
        val cam = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .bearing(bearing.toDouble())
            .tilt(MapSetup.MAX_PITCH * viewBlend)
            .zoom(zoom)
            .padding(0.0, h * padTop(), 0.0, 0.0)
            .build()
        val update = CameraUpdateFactory.newCameraPosition(cam)
        if (animate) {
            // Deliberate transition (recenter): ease, and keep the frame loop's hands off the
            // camera until it lands.
            cameraHoldUntilMs = SystemClock.uptimeMillis() + CAMERA_EASE_MS
            m.easeCamera(update, CAMERA_EASE_MS, true)
        } else {
            m.moveCamera(update)
        }
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
        motion.setNavigator(null)
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
            navigator = Navigator(r).also { motion.setNavigator(it) }
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
        motion.setNavigator(null)
        routeOverlay.setRoute(null)
        routeOverlay.setDestination(null)
        hud.showIdle()
    }

    // ---- Map loading ----

    private fun loadSavedMap() {
        val path = prefs.mapPath
        if (path == null) {
            showStatus(getString(R.string.no_map_loaded), canLoad = true)
            return
        }
        Bg.compute({ MapFile.read(File(path)) }) { mapFile ->
            if (mapFile == null) {
                prefs.mapPath = null
                showStatus(getString(R.string.no_map_loaded), canLoad = true)
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
            binding.panelEmpty.visibility = View.GONE

            // Clamp zoom to what the file contains (+5 overzoom for vector: z14 tiles carry full
            // detail and the GPU just scales the geometry, so the close z18 chase view costs no
            // extra tiles).
            val overzoom = if (mapFile.isVector) 5.0 else 0.0
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
                fix != null && follow -> followCamera(fix.lat, fix.lon, fix.bearing, animate = false, forceZoom = true)
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

    /** Centre card over the empty map: status text, plus a load button when there is nothing to show. */
    private fun showStatus(text: String, canLoad: Boolean = false) {
        binding.txtStatus.text = text
        binding.btnEmptyLoadMap.visibility = if (canLoad) View.VISIBLE else View.GONE
        binding.panelEmpty.visibility = View.VISIBLE
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

        const val MAX_RECENTS = 30

        const val DEFAULT_ZOOM = 12.0
        /** Street-level, like Google navigation: 2D shows this much road across the short side. */
        const val NAV_SPAN_2D_M = 200.0
        const val NAV_3D_EXTRA_ZOOM = 1.0
        const val MIN_NAV_ZOOM = 15.0
        /** Vector maps allow z14 + 5 overzoom (see show()). */
        const val MAX_NAV_ZOOM = 19.0
        /** How far a driver pinch may stray from [navZoom] before follow mode resets it. */
        const val FOLLOW_ZOOM_OUT = 2.5
        const val FOLLOW_ZOOM_IN = 1.0
        const val EARTH_CIRCUMFERENCE_M = 40_075_016.7

        /** Fraction of the view height used as top padding: puts the car at 78% / 65% down. */
        const val PAD_TOP_3D = 0.56
        const val PAD_TOP_2D = 0.3

        /** Recenter ease length. */
        const val CAMERA_EASE_MS = 800
        /** Full 2D <-> 3D switch length (ease-in-out). */
        const val VIEW_SWITCH_MS = 900f
        const val STALE_TICK_MS = 2000L
        const val REROUTE_MIN_MS = 10_000L

        /** Marker + camera update rate while moving: 30 Hz is smooth and half the GPU cost of 60. */
        const val APPLY_INTERVAL_NS = 33_000_000L
        /** Turn-distance countdown refresh. */
        const val HUD_INTERVAL_NS = 250_000_000L
        /** Stop the frame loop entirely once fixes have been absent this long. */
        const val IDLE_AFTER_MS = 6_000L
    }
}
