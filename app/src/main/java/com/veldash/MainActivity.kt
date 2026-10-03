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
import com.veldash.databinding.ActivityMainBinding
import com.veldash.location.Fix
import com.veldash.location.LocationBus
import com.veldash.location.LocationService
import android.graphics.Bitmap
import com.veldash.map.BatStyle
import com.veldash.map.CarSprites
import com.veldash.map.BatmobileMarker
import com.veldash.map.MapFile
import com.veldash.map.MapRepository
import com.veldash.map.MapSetup
import com.veldash.map.RouteOverlay
import com.veldash.nav.FollowGate
import com.veldash.nav.Navigator
import com.veldash.nav.SmoothMotion
import com.veldash.routing.OfflineRouter
import com.veldash.routing.Route
import com.veldash.routing.Router
import com.veldash.search.GeoUri
import com.veldash.search.Place
import com.veldash.search.PlaceStore
import com.veldash.ui.Hud
import com.veldash.ui.PlaceIcons
import com.veldash.ui.SearchPanel
import com.veldash.util.Bg
import com.veldash.util.BundledData
import com.veldash.util.Prefs
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
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

    /** Name of the current destination for the banner (a searched place); null for a dropped pin. */
    private var destinationName: String? = null
    /** Drawable of the logo on the destination pin (a saved place wearing one), 0 for the bat logo. */
    private var destinationBadge = 0

    private var map: MapLibreMap? = null
    private var current: MapFile? = null

    private val marker = BatmobileMarker()
    /** The batmobile, pre-rendered from its 3D model: one frame per tilt / relative heading. */
    private val sprites by lazy { CarSprites(this) }
    private val pick = CarSprites.Pick()
    private var shownCar: Bitmap? = null
    private var shownBlend: Bitmap? = null

    /**
     * Camera heading. Follows the car's heading with a short lag ([CAMERA_BEARING_TAU_S]), so
     * in a corner the car turns a little on screen and the 3D renders show its flank, without
     * the long swing that made it look like it was sliding across the road.
     */
    private var camBearing = 0f
    private var camBearingSet = false
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

    /**
     * Decides whether a touch gesture frees the camera (a real drag) or keeps following (a
     * pinch or quick zoom). While it is busy the frame loop leaves the camera to the finger.
     */
    private val followGate by lazy { FollowGate(breakPx = DRAG_BREAK_DP * resources.displayMetrics.density) }

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
            if (lastFrameNs != 0L) {
                val dt = (frameTimeNanos - lastFrameNs) / 1e9
                motion.step(dt)
                stepCameraBearing(dt)
            }
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
            currentDestinationName = { destinationName },
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
            // A pinch zoom keeps following (the chase view just zooms); only a real one-finger
            // drag past a small threshold frees the camera. Two-finger drift during a pinch is
            // ignored, and the frame loop leaves the camera alone while the pinch is in progress.
            m.addOnScaleListener(object : MapLibreMap.OnScaleListener {
                override fun onScaleBegin(detector: StandardScaleGestureDetector) = followGate.onScaleBegin()
                override fun onScale(detector: StandardScaleGestureDetector) = Unit
                override fun onScaleEnd(detector: StandardScaleGestureDetector) = followGate.onScaleEnd()
            })
            m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: MoveGestureDetector) = followGate.onMoveBegin(m.cameraPosition.zoom)
                override fun onMove(detector: MoveGestureDetector) =
                    followGate.onMove(detector.lastDistanceX, detector.lastDistanceY, detector.pointersCount)
                override fun onMoveEnd(detector: MoveGestureDetector) {
                    if (followGate.onMoveEnd(m.cameraPosition.zoom)) setFollow(false)
                }
            })
            // Long-press anywhere = "take me there".
            m.addOnMapLongClickListener { p ->
                destinationName = null
                destinationBadge = badgeAt(p.latitude, p.longitude, "")
                setDestination(p)
                true
            }
            installBundledDataThenLoad()
        }

        // Destination pill (opens search), MAPS, and the two round map buttons.
        binding.btnDestination.setOnClickListener { toggleSearch() }
        binding.btnEndRoute.setOnClickListener { clearRoute() }
        binding.btnLoadMap.setOnClickListener { pickMap() }
        binding.btnEmptyLoadMap.setOnClickListener { pickMap() }
        binding.btnRecenter.setOnClickListener { recenter() }
        updateRecenterButton()

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

    /** The destination pill: open the dropdown, or close it if it is already up. */
    private fun toggleSearch() {
        if (searchPanel.isOpen) searchPanel.close() else searchPanel.open()
    }

    /**
     * The Batman button. Panned away: follow again. Already following: snap the zoom and
     * heading back to the navigation view (undoes a pinch).
     */
    private fun recenter() {
        if (!follow) {
            setFollow(true)
            return
        }
        if (motion.hasPosition) {
            snapCameraBearing()
            followCamera(motion.lat, motion.lon, camBearing, animate = true, forceZoom = true)
        } else {
            LocationBus.last?.let { followCamera(it.lat, it.lon, it.bearing, animate = true, forceZoom = true) }
        }
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

    private fun goTo(p: Place) {
        destinationName = p.name
        destinationBadge = badgeAt(p.lat, p.lon, p.icon)
        setDestination(LatLng(p.lat, p.lon))
    }

    /**
     * Logo for the pin at (lat, lon): the place's own [icon], else that of a saved place at the
     * same spot (a search result or dropped pin on Home or a favorite), else 0 for the bat logo.
     */
    private fun badgeAt(lat: Double, lon: Double, icon: String): Int {
        val id = icon.ifEmpty {
            favorites.find(lat, lon)?.icon
                ?: prefs.home?.takeIf { it.sameSpot(lat, lon) }?.icon
                ?: prefs.work?.takeIf { it.sameSpot(lat, lon) }?.icon
                ?: ""
        }
        return PlaceIcons.drawable(id)
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
        if (follow) {
            // During a touch gesture or a recenter ease the camera is someone else's; the car
            // stays put as the overlay and catches up the moment the gesture ends.
            if (!followGate.busy && SystemClock.uptimeMillis() >= cameraHoldUntilMs) {
                followCamera(motion.lat, motion.lon, camBearing, animate = false)
            }
            marker.setShown(false)
            showCarOverlay(true)
        } else {
            // Panned (free camera): the map-layer marker at the car's position.
            // 3D: the chase render for the car's heading relative to the camera (the orbit
            // frames ARE the camera looking at the car from that side), drawn upright. The
            // leftover few degrees are a screen rotation, as in follow mode.
            // 2D (and the first degrees of the switch): the top-down render laid flat on the
            // road and turned to the heading, so the map projects it like a decal.
            val tilt = MapSetup.MAX_PITCH.toFloat() * viewBlend
            if (tilt >= FLAT_MARKER_TILT_DEG) {
                val mapBearing = map?.cameraPosition?.bearing?.toFloat() ?: camBearing
                sprites.pick(tilt, CarSprites.normalize(motion.bearing - mapBearing), pick)
                pick.bitmap?.let { marker.setArt(it, chase = true) }
                marker.update(motion.lat, motion.lon, pick.rotation)
            } else {
                marker.setArt(sprites.topDown(), chase = false)
                marker.update(motion.lat, motion.lon, motion.bearing)
            }
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
     * The batmobile overlay, centred on the camera target: the render for the current tilt and
     * the car's heading relative to the (slightly lagging) camera, plus the small leftover
     * rotation and the between-frames scale. The neighbouring render is drawn on top with a
     * proximity alpha, so the car morphs through bends and through the tilt instead of popping.
     * The size follows the zoom on a damped curve ([CAR_ZOOM_EXPONENT] of the map's own 2^dz):
     * zooming out shrinks the car with the streets but keeps it readable, zooming in grows it a
     * little instead of filling the screen. Clamped to [CAR_SCALE_MIN]..[CAR_SCALE_MAX].
     */
    private fun showCarOverlay(show: Boolean) {
        val v = binding.imgCar
        val v2 = binding.imgCarBlend
        // No map on screen yet (first start, installing): a car floating on black looks broken.
        if (!show || map?.style?.isFullyLoaded != true) {
            if (v.visibility != View.GONE) v.visibility = View.GONE
            if (v2.visibility != View.GONE) v2.visibility = View.GONE
            return
        }
        // Heading relative to the camera as actually drawn (the map's bearing, which differs
        // from camBearing during a two-finger rotate or a recenter ease). In follow mode the
        // camera lags the car by well under a second, so a large angle is GPS noise, not a
        // turn: cap it so the car never shows its flank or nose while the map says "ahead".
        val mapBearing = map?.cameraPosition?.bearing?.toFloat() ?: camBearing
        val rel = CarSprites.normalize(motion.bearing - mapBearing).coerceIn(-MAX_REL_YAW_DEG, MAX_REL_YAW_DEG)
        sprites.pick(MapSetup.MAX_PITCH.toFloat() * viewBlend, rel, pick)
        val bmp = pick.bitmap ?: return
        if (bmp !== shownCar) {
            shownCar = bmp
            v.setImageBitmap(bmp)
        }
        val zoom = map?.cameraPosition?.zoom ?: navZoom(motion.lat)
        val zoomScale = Math.pow(2.0, (zoom - navZoom(motion.lat)) * CAR_ZOOM_EXPONENT)
            .toFloat().coerceIn(CAR_SCALE_MIN, CAR_SCALE_MAX)
        val scale = pick.scale * zoomScale
        // Camera target with top padding p (fraction of height) sits at y = h(1+p)/2.
        val cx = mapView.width / 2f
        val cy = mapView.height * (1f + padTop().toFloat()) / 2f
        placeCar(v, bmp, cx, cy, pick.rotation, scale)
        if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE

        val blend = pick.blend
        if (blend != null && pick.blendAlpha > 0.02f) {
            if (blend !== shownBlend) {
                shownBlend = blend
                v2.setImageBitmap(blend)
            }
            placeCar(v2, blend, cx, cy, pick.rotation, scale)
            v2.alpha = pick.blendAlpha
            if (v2.visibility != View.VISIBLE) v2.visibility = View.VISIBLE
        } else if (v2.visibility != View.GONE) {
            v2.visibility = View.GONE
        }
    }

    /** Centre a car bitmap view on (cx, cy) with the given screen rotation and scale. */
    private fun placeCar(v: android.widget.ImageView, bmp: Bitmap, cx: Float, cy: Float, rotation: Float, scale: Float) {
        // Bitmap size, not view size: the view is unmeasured the first time it is shown.
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        v.x = cx - w / 2f
        v.y = cy - h / 2f
        v.pivotX = w / 2f
        v.pivotY = h / 2f
        v.rotation = rotation
        v.scaleX = scale
        v.scaleY = scale
    }

    /** Low-pass the camera heading toward the car's: the lag that lets the car swing in turns. */
    private fun stepCameraBearing(dt: Double) {
        if (!motion.hasPosition) return
        if (!camBearingSet) {
            snapCameraBearing()
            return
        }
        val k = (1.0 - Math.exp(-dt / CAMERA_BEARING_TAU_S)).toFloat()
        camBearing = SmoothMotion.lerpAngle(camBearing, motion.bearing, k)
    }

    private fun snapCameraBearing() {
        camBearing = motion.bearing
        camBearingSet = true
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
        updateRecenterButton()
        if (on) {
            if (motion.hasPosition) {
                snapCameraBearing()
                followCamera(motion.lat, motion.lon, camBearing, animate = true, forceZoom = true)
            } else {
                LocationBus.last?.let { followCamera(it.lat, it.lon, it.bearing, animate = true, forceZoom = true) }
            }
        }
        // Swap between overlay and map marker right away (the ease hold keeps the marker up briefly).
        applyMotion()
    }

    /** 3D / 2D button: the glyph is the current mode, lit yellow while 3D is on. */
    private fun updateViewModeButton() {
        binding.txtViewMode.setText(if (view3d) R.string.view_3d else R.string.view_2d)
        binding.btnViewMode.isActivated = view3d
    }

    /**
     * The Batman button: quiet glass while the camera follows the car; once the driver has
     * panned away the whole disc turns yellow (selected), the one thing to find at a glance.
     */
    private fun updateRecenterButton() {
        binding.btnRecenter.isActivated = follow
        binding.btnRecenter.isSelected = !follow
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
        routeOverlay.setDestination(p, destinationBadge)
        routeOverlay.setRoute(null)
        hud.showDestination(destinationName ?: getString(R.string.dropped_pin))

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
        destinationName = null
        destinationBadge = 0
        route = null
        navigator = null
        motion.setNavigator(null)
        routeOverlay.setRoute(null)
        routeOverlay.setDestination(null)
        hud.showIdle()
    }

    // ---- Map loading ----

    /**
     * First start (and after an app update with new data): copy the bundled map and routing
     * segments out of the APK, then open the map. The bundled map is the default until the
     * driver picks another file; a user-chosen map is never overridden.
     */
    private fun installBundledDataThenLoad() {
        showStatus(getString(R.string.installing_map))
        Bg.compute({
            BundledData.install(this)
            BundledData.bundledMap(this)
        }) { bundled ->
            if (isFinishing || isDestroyed) return@compute
            if (prefs.mapPath == null && bundled != null && bundled.exists()) prefs.mapPath = bundled.absolutePath
            loadSavedMap()
        }
    }

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
        const val FOLLOW_ZOOM_OUT = 3.5
        /** Covers a double-tap zoom (+1) with margin so it is not snapped back on the next fix. */
        const val FOLLOW_ZOOM_IN = 1.5

        /** One-finger drag that frees the camera; smaller moves and pinch drift are ignored. */
        const val DRAG_BREAK_DP = 24f
        const val EARTH_CIRCUMFERENCE_M = 40_075_016.7

        /**
         * Fraction of the view height used as top padding: puts the car at 78% / 65% down,
         * between the trip island and the round buttons in the bottom corners.
         */
        const val PAD_TOP_3D = 0.56
        const val PAD_TOP_2D = 0.3

        /** Recenter ease length. */
        const val CAMERA_EASE_MS = 800
        /**
         * Camera heading lag behind the car's (s). Short: enough for the car to turn a little
         * on screen and show its flank in a bend, not so long that it seems to slide sideways
         * across the road (0.9 did).
         */
        const val CAMERA_BEARING_TAU_S = 0.5

        /** Follow-mode car size relative to the navigation zoom: shrinks when zoomed out. */
        /**
         * 1.0 would be true-to-the-map scaling; 0.5 keeps the car readable at every zoom, the way
         * a navigation app's own car barely changes size (matches [BatmobileMarker]'s size curve).
         */
        const val CAR_ZOOM_EXPONENT = 0.5
        const val CAR_SCALE_MIN = 0.6f
        const val CAR_SCALE_MAX = 1.15f
        /** Largest car-vs-camera heading the chase sprite depicts; more than this is GPS noise. */
        const val MAX_REL_YAW_DEG = 45f
        /** Free-camera marker: below this tilt the flat top-down decal, above it the chase render. */
        const val FLAT_MARKER_TILT_DEG = 10f

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
