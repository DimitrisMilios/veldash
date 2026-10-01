package com.veldash.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.LruCache

/**
 * The batmobile, pre-rendered from its 3D model by tools/render-car.py into assets/car/.
 * No 3D engine runs on the head unit: each frame we pick the render that matches the camera
 * tilt and the car's heading relative to the camera, so the car really tilts during the 2D/3D
 * switch and really turns in corners, for the cost of one small bitmap.
 *
 *  - Tilt frames (yaw 0, tilt 0..55 every 5 degrees): the 2D view, and the 2D <-> 3D switch.
 *    Any heading is a screen rotation of these (exact at tilt 0, close enough mid-switch).
 *  - Yaw frames (tilt 55, camera orbiting 0..180 every 15 degrees to the car's right): the 3D
 *    view. The car's other side is the same frame mirrored; the 0..7.5 degree remainder is a
 *    small screen rotation.
 *
 * Between two neighbouring frames the picker also returns the other frame with a blend weight,
 * so the drawer can cross-fade: the car morphs smoothly through a bend and through the tilt
 * instead of popping from one render to the next.
 *
 * Every frame is scaled for its tilt (the car grows as the view tilts, like a chase camera
 * closing in), given the yellow halo, and cropped symmetrically around the car's ground point,
 * so the anchor is always the bitmap centre. Tilt frames are decoded once (a few hundred KB);
 * yaw frames on demand into a small LRU.
 */
class CarSprites(private val context: Context) {

    /** Result of [pick]: the bitmap plus the screen rotation and scale to draw it with. */
    class Pick {
        var bitmap: Bitmap? = null
        /** Neighbouring frame to draw on top with [blendAlpha] (0..0.5), or null. */
        var blend: Bitmap? = null
        var blendAlpha = 0f
        /** Clockwise screen rotation, degrees, about the bitmap centre. */
        var rotation = 0f
        /** Extra scale on top of the bitmap's own size (smooths the size between tilt frames). */
        var scale = 1f
    }

    /** Which render to draw, and how: the pure-math half of [pick] (unit-tested). */
    class Choice {
        /** Tilt frame index (tilt = index * [TILT_STEP_DEG]); ignored when [yawStep] > 0. */
        var tiltIndex = 0
        /** Full-tilt orbit frame (yaw = step * [YAW_STEP_DEG]); 0 = use the tilt frame. */
        var yawStep = 0
        /** Show the orbit frame mirrored (the car's left side). */
        var mirror = false
        /** Leftover clockwise screen rotation, degrees. */
        var rotation = 0f
    }

    private val choice = Choice()
    private val density = context.resources.displayMetrics.density
    private val tiltFrames = arrayOfNulls<Bitmap>(TILT_STEPS)
    private val yawFrames = object : LruCache<Int, Bitmap>(YAW_CACHE) {}

    /** Tilt frame [index] (tilt = index * 5 degrees), decoded on first use and kept. */
    fun tiltFrame(index: Int): Bitmap =
        tiltFrames[index] ?: load(index * TILT_STEP_DEG, 0, mirror = false).also { tiltFrames[index] = it }

    /** Top-down frame, nose up: the recenter button icon and the 2D marker. */
    fun topDown(): Bitmap = tiltFrame(0)

    /** Full-tilt orbit frame [step] (0 = straight behind, which is the last tilt frame). */
    private fun yawFrame(step: Int, mirror: Boolean): Bitmap {
        if (step == 0) return tiltFrame(TILT_STEPS - 1)
        val key = if (mirror) -step else step
        return yawFrames.get(key) ?: load(FULL_TILT_DEG, step * YAW_STEP_DEG, mirror).also { yawFrames.put(key, it) }
    }

    /**
     * Frame for camera tilt [tiltDeg] (0 = straight down) and the car's heading relative to the
     * camera, [relYawDeg] (clockwise positive: the car is turning right on screen).
     */
    fun pick(tiltDeg: Float, relYawDeg: Float, out: Pick) {
        val c = choice
        choose(tiltDeg, relYawDeg, c)
        out.blend = null
        out.blendAlpha = 0f
        out.rotation = c.rotation

        val rel = normalize(relYawDeg)
        val fullTilt = c.tiltIndex == TILT_STEPS - 1
        if (fullTilt && Math.abs(rel) > 0.01f) {
            // Orbit frames bracketing |rel|: nearest drawn solid, the other faded in by proximity.
            val mirror = rel < 0
            val pos = (Math.abs(rel) / YAW_STEP_DEG).coerceAtMost((YAW_STEPS - 1).toFloat())
            val a = pos.toInt().coerceAtMost(YAW_STEPS - 1)
            val t = pos - a
            val b = (a + 1).coerceAtMost(YAW_STEPS - 1)
            val near = if (t < 0.5f) a else b
            val far = if (t < 0.5f) b else a
            out.bitmap = yawFrame(near, mirror)
            if (far != near) {
                out.blend = yawFrame(far, mirror)
                out.blendAlpha = if (t < 0.5f) t else 1f - t
            }
            // Never screen-rotate a pitched render: spinning it about the screen axis lifts the
            // nose, since a real turn happens in the ground plane. The orbit frames already
            // depict that turn; the cross-fade covers the angles in between.
            out.rotation = 0f
            out.scale = 1f
        } else {
            // Tilt frames bracketing the tilt (2D view and the 2D <-> 3D switch).
            val ti = (tiltDeg / TILT_STEP_DEG).coerceIn(0f, (TILT_STEPS - 1).toFloat())
            val a = ti.toInt().coerceAtMost(TILT_STEPS - 1)
            val t = ti - a
            val b = (a + 1).coerceAtMost(TILT_STEPS - 1)
            val near = if (t < 0.5f) a else b
            val far = if (t < 0.5f) b else a
            out.bitmap = tiltFrame(near)
            if (far != near) {
                out.blend = tiltFrame(far)
                out.blendAlpha = if (t < 0.5f) t else 1f - t
            }
            out.scale = displayScale(tiltDeg) / displayScale(near * TILT_STEP_DEG.toFloat())
        }
    }

    // ---- decoding ----

    private fun load(tilt: Int, yaw: Int, mirror: Boolean): Bitmap {
        val name = "car/car_p%02d_y%03d.webp".format(tilt, yaw)
        val src = context.assets.open(name).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false })
        } ?: error("missing $name")

        // Scale the whole (square, car-centred) render to this tilt's on-screen size.
        val target = (src.width * displayScale(tilt.toFloat()) * density / SRC_PX_PER_M).toInt().coerceAtLeast(8)
        val m = Matrix()
        m.setScale((if (mirror) -1f else 1f) * target / src.width, target.toFloat() / src.height)
        if (mirror) m.postTranslate(target.toFloat(), 0f)
        val scaled = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        Canvas(scaled).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()

        val haloed = withHalo(scaled)
        scaled.recycle()
        val out = cropAroundCentre(haloed)
        if (out !== haloed) haloed.recycle()
        out.density = context.resources.displayMetrics.densityDpi
        return out
    }

    /** Yellow outer glow so the black car reads on the dark map. Keeps the bitmap size and centre. */
    private fun withHalo(src: Bitmap): Bitmap {
        val radius = HALO_DP * density
        val offset = IntArray(2)
        val halo = src.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.OUTER) }, offset)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(halo, offset[0].toFloat(), offset[1].toFloat(), Paint().apply { color = HALO })
        c.drawBitmap(src, 0f, 0f, null)
        halo.recycle()
        return out
    }

    /**
     * Trim transparent margins, symmetrically about the centre (the car's ground point), so the
     * bitmap stays anchored at its centre for the ImageView and for MapLibre alike.
     */
    private fun cropAroundCentre(b: Bitmap): Bitmap {
        val w = b.width
        val h = b.height
        val row = IntArray(w)
        var minX = w
        var maxX = -1
        var minY = h
        var maxY = -1
        for (y in 0 until h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if ((row[x] ushr 24) > ALPHA_CUTOFF) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    maxY = y
                }
            }
        }
        if (maxX < 0) return b
        val cx = w / 2
        val cy = h / 2
        val hw = maxOf(cx - minX, maxX - cx) + 1
        val hh = maxOf(cy - minY, maxY - cy) + 1
        val left = (cx - hw).coerceAtLeast(0)
        val top = (cy - hh).coerceAtLeast(0)
        return Bitmap.createBitmap(b, left, top, (2 * hw).coerceAtMost(w - left), (2 * hh).coerceAtMost(h - top))
    }

    /** On-screen dp per metre of car: [DP_PER_M_2D] flat, growing to [DP_PER_M_3D] at full tilt. */
    private fun displayScale(tiltDeg: Float): Float {
        val b = (tiltDeg / FULL_TILT_DEG).coerceIn(0f, 1f)
        return DP_PER_M_2D + (DP_PER_M_3D - DP_PER_M_2D) * b
    }

    companion object {
        /**
         * Below full tilt: the tilt frame nearest [tiltDeg], screen-rotated by the whole relative
         * heading. At full tilt and more than half a step off-axis: the nearest orbit frame.
         * The car turned right on screen shows its right side, which is the camera orbited to
         * its right (unmirrored); turned left is the same frame mirrored. The remainder (under
         * half a step) is a small screen rotation.
         */
        fun choose(tiltDeg: Float, relYawDeg: Float, out: Choice) {
            val rel = normalize(relYawDeg)
            val index = Math.round(tiltDeg / TILT_STEP_DEG).coerceIn(0, TILT_STEPS - 1)
            out.tiltIndex = index
            if (index == TILT_STEPS - 1 && Math.abs(rel) >= YAW_STEP_DEG / 2f) {
                val step = Math.round(Math.abs(rel) / YAW_STEP_DEG).coerceAtMost(YAW_STEPS - 1)
                out.yawStep = step
                out.mirror = rel < 0
                out.rotation = rel - (if (out.mirror) -1 else 1) * step * YAW_STEP_DEG
            } else {
                out.yawStep = 0
                out.mirror = false
                out.rotation = rel
            }
        }

        /** Degrees into (-180, 180]. */
        fun normalize(deg: Float): Float {
            var d = deg % 360f
            if (d > 180f) d -= 360f
            if (d <= -180f) d += 360f
            return d
        }

        /** Must match tools/render-car.py: TILTS, YAWS, frame size and ortho scale. */
        const val TILT_STEP_DEG = 5
        const val TILT_STEPS = 12          // 0..55
        const val FULL_TILT_DEG = 55
        const val YAW_STEP_DEG = 15
        const val YAW_STEPS = 13           // 0..180
        /** 480 px across 4.6 m x 1.12. */
        const val SRC_PX_PER_M = 480f / (4.6f * 1.12f)

        /** Car size on screen: ~125 dp long top-down, a big chase-view car at full tilt. */
        private const val DP_PER_M_2D = 27f
        private const val DP_PER_M_3D = 52f

        private const val YAW_CACHE = 8
        private const val HALO_DP = 5f
        private const val HALO = 0x80FFE600.toInt()
        private const val ALPHA_CUTOFF = 6
    }
}
