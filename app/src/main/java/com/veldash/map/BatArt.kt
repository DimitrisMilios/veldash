package com.veldash.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.veldash.R

/**
 * Raster art built from the drawable-nodpi PNGs (sources in /art): the batmobile for each view
 * mode and the bat-logo destination pin. Built once, cached for the process.
 *
 * Every bitmap carries the display density, so an ImageView (wrap_content) and MapLibre
 * (addImage reads Bitmap.density as the pixel ratio) both draw it at its dp size.
 */
object BatArt {

    private var car3d: Bitmap? = null
    private var car2d: Bitmap? = null
    private var pin: Bitmap? = null
    private var logo: Bitmap? = null

    /**
     * The batmobile for the view mode: rear chase render in 3D, overhead render in 2D. The car
     * is near-black, so it gets a soft yellow halo to stand off the dark map.
     */
    fun car(context: Context, view3d: Boolean): Bitmap {
        (if (view3d) car3d else car2d)?.let { return it }
        val src = scaled(context, if (view3d) R.drawable.car_3d else R.drawable.car_2d, if (view3d) CAR_3D_DP else CAR_2D_DP)
        val bmp = withHalo(context, src)
        if (view3d) car3d = bmp else car2d = bmp
        return bmp
    }

    /** Destination pin: the bat logo on a short spike. Anchor it at the bottom (the spike tip). */
    fun pin(context: Context): Bitmap = pin ?: buildPin(context).also { pin = it }

    /** The bat logo alone, [LOGO_ICON_DP] wide: list-row icon for search results. */
    fun logoIcon(context: Context): Bitmap = logo ?: scaled(context, R.drawable.bat_logo, LOGO_ICON_DP).also { logo = it }

    private fun buildPin(context: Context): Bitmap {
        val d = context.resources.displayMetrics.density
        val logo = scaled(context, R.drawable.bat_logo, PIN_DP)
        val spikeTop = logo.height - 6f * d
        val tipY = logo.height + PIN_SPIKE_DP * d
        val out = Bitmap.createBitmap(logo.width, (tipY + 2f * d).toInt(), Bitmap.Config.ARGB_8888)
        out.density = context.resources.displayMetrics.densityDpi
        val c = Canvas(out)
        val cx = logo.width / 2f
        val spike = Path().apply {
            moveTo(cx - 7f * d, spikeTop)
            lineTo(cx + 7f * d, spikeTop)
            lineTo(cx, tipY)
            close()
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = BLACK
        c.drawPath(spike, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * d
        p.strokeJoin = Paint.Join.ROUND
        p.color = YELLOW
        c.drawPath(spike, p)
        c.drawBitmap(logo, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        logo.recycle()
        return out
    }

    /** Outer blur of the alpha mask in yellow, car on top. Software canvas, so the blur filter works. */
    private fun withHalo(context: Context, src: Bitmap): Bitmap {
        val radius = HALO_DP * context.resources.displayMetrics.density
        val offset = IntArray(2)
        val blur = Paint().apply { maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.OUTER) }
        val halo = src.extractAlpha(blur, offset)
        val out = Bitmap.createBitmap(halo.width, halo.height, Bitmap.Config.ARGB_8888)
        out.density = context.resources.displayMetrics.densityDpi
        val c = Canvas(out)
        c.drawBitmap(halo, 0f, 0f, Paint().apply { color = HALO })
        c.drawBitmap(src, -offset[0].toFloat(), -offset[1].toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        halo.recycle()
        src.recycle()
        return out
    }

    /**
     * Decode at native size and scale to [widthDp]. Halves first while more than 2x too big:
     * a single bilinear step from 3x samples too sparsely and the fins shimmer.
     */
    private fun scaled(context: Context, resId: Int, widthDp: Float): Bitmap {
        val dm = context.resources.displayMetrics
        var bmp = BitmapFactory.decodeResource(context.resources, resId, BitmapFactory.Options().apply { inScaled = false })
        val w = (widthDp * dm.density + 0.5f).toInt().coerceAtLeast(1)
        val h = (bmp.height.toLong() * w / bmp.width).toInt().coerceAtLeast(1)
        while (bmp.width >= w * 2) bmp = replace(bmp, Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true))
        bmp = replace(bmp, Bitmap.createScaledBitmap(bmp, w, h, true))
        bmp.density = dm.densityDpi
        return bmp
    }

    private fun replace(old: Bitmap, new: Bitmap): Bitmap {
        if (new !== old) old.recycle()
        return new
    }

    /** Car widths; heights follow the art (3D ~1:1, 2D ~1:1.4). Halo adds [HALO_DP] around. */
    private const val CAR_3D_DP = 96f
    private const val CAR_2D_DP = 60f
    private const val HALO_DP = 7f
    private const val PIN_DP = 72f
    private const val PIN_SPIKE_DP = 14f
    private const val LOGO_ICON_DP = 32f

    private const val YELLOW = 0xFFFFE600.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val HALO = 0x99FFE600.toInt()
}
