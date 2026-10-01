package com.veldash.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.veldash.R

/**
 * Raster art built from the drawable-nodpi PNGs (sources in /art): the Batman-symbol destination
 * pin and its list-icon size. Built once, cached for the process. (The batmobile itself is the
 * pre-rendered 3D model: see [CarSprites].)
 *
 * Every bitmap carries the display density, so an ImageView (wrap_content) and MapLibre
 * (addImage reads Bitmap.density as the pixel ratio) both draw it at its dp size.
 */
object BatArt {

    private var pin: Bitmap? = null
    private var logo: Bitmap? = null

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
        // Lit from the upper left: pale gold edge, deep gold far side, so the spike reads as a
        // cone rather than a flat triangle under the flat symbol.
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(
            cx - 7f * d, 0f, cx + 7f * d, 0f,
            intArrayOf(GOLD_LIGHT, YELLOW, GOLD_DARK), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawPath(spike, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * d
        p.strokeJoin = Paint.Join.ROUND
        p.color = BLACK
        c.drawPath(spike, p)
        c.drawBitmap(logo, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        logo.recycle()
        return out
    }

    /**
     * Decode at native size and scale to [widthDp]. Halves first while more than 2x too big:
     * a single bilinear step from 3x samples too sparsely and fine edges shimmer.
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

    private const val PIN_DP = 72f
    private const val PIN_SPIKE_DP = 14f
    private const val LOGO_ICON_DP = 32f

    private const val YELLOW = 0xFFFFE600.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val GOLD_LIGHT = 0xFFFFF6B0.toInt()
    private const val GOLD_DARK = 0xFF8C6E00.toInt()
}
