package com.veldash.ui

import android.content.Context
import android.text.format.DateFormat
import android.view.View
import com.veldash.R
import com.veldash.databinding.ActivityMainBinding
import com.veldash.nav.NavState
import com.veldash.routing.Maneuver
import com.veldash.routing.Route
import java.util.Date

/**
 * Binds navigation state to the flat set of HUD views in activity_main.xml.
 * All work here is setText/setImageResource on a handful of views, once per second.
 * Values are cached so unchanged text is not re-set (TextView.setText re-lays out).
 */
class Hud(private val context: Context, private val b: ActivityMainBinding) {

    private val timeFormat = DateFormat.getTimeFormat(context)
    private val date = Date()

    private var lastIcon = 0
    private var lastTurnText: String? = null
    private var lastStreet: String? = null
    private var lastBottom: String? = null
    private var lastSpeed = Int.MIN_VALUE
    private var speedDim = false

    // ---- Speed ----

    fun showSpeed(kmh: Int) {
        if (kmh != lastSpeed || speedDim) {
            lastSpeed = kmh
            speedDim = false
            b.txtSpeed.text = context.getString(R.string.speed_kmh, kmh)
            b.txtSpeed.setTextColor(color(R.color.bat_yellow))
        }
    }

    fun showSpeedUnavailable(gpsAvailable: Boolean) {
        if (!speedDim) {
            speedDim = true
            lastSpeed = Int.MIN_VALUE
            b.txtSpeed.setText(if (gpsAvailable) R.string.gps_searching else R.string.gps_off)
            b.txtSpeed.setTextColor(color(R.color.bat_text_dim))
        }
    }

    // ---- Navigation panel ----

    fun showNavigation(state: NavState) {
        setPanelVisible(true)
        val next = state.next
        setIcon(iconFor(next?.type))
        setTurn(formatTurnDistance(state.distToNextM))
        setStreet(next?.streetName ?: "")
        date.time = System.currentTimeMillis() + (state.remainingS * 1000).toLong()
        setBottom(
            context.getString(
                R.string.nav_bottom,
                formatDistance(state.remainingM),
                formatDuration(state.remainingS),
                timeFormat.format(date),
            ),
        )
    }

    /** Frame-loop countdown between fixes. Only touches the view when the rounded text changes. */
    fun updateTurnDistance(m: Double) {
        if (b.hudBg.visibility != View.VISIBLE) return
        setTurn(formatTurnDistance(m))
    }

    fun showRerouting() {
        setPanelVisible(true)
        setIcon(R.drawable.ic_turn_straight)
        setTurn(context.getString(R.string.rerouting))
        setStreet("")
    }

    fun showArrived() {
        setPanelVisible(true)
        setIcon(R.drawable.ic_bat)
        setTurn(context.getString(R.string.arrived))
        setStreet("")
        setBottom("")
        b.txtRoute.visibility = View.GONE
    }

    fun showRouting() {
        setPanelVisible(false)
        b.txtRoute.visibility = View.VISIBLE
        setBottom(context.getString(R.string.routing))
    }

    fun showRouteFailed(reason: String) {
        setPanelVisible(false)
        b.txtRoute.visibility = View.VISIBLE
        setBottom(context.getString(R.string.route_failed, reason))
    }

    fun showRouteSummary(r: Route) {
        b.txtRoute.visibility = View.VISIBLE
        val src = when (r.source) {
            Route.Source.MAPBOX -> R.string.src_mapbox
            Route.Source.OSRM -> R.string.src_osrm
            Route.Source.BROUTER -> R.string.src_brouter
        }
        setBottom(context.getString(R.string.route_summary, formatDistance(r.distanceM), formatDuration(r.durationS), context.getString(src)))
    }

    fun showIdle() {
        setPanelVisible(false)
        b.txtRoute.visibility = View.GONE
        lastBottom = null
    }

    // ---- internals ----

    private fun setPanelVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        if (b.hudBg.visibility != v) {
            b.hudBg.visibility = v
            b.imgManeuver.visibility = v
            b.txtTurnDistance.visibility = v
            b.txtTurnStreet.visibility = v
            b.txtRoute.visibility = v
        }
    }

    private fun setIcon(res: Int) {
        if (res != lastIcon) {
            lastIcon = res
            b.imgManeuver.setImageResource(res)
        }
    }

    private fun setTurn(s: String) {
        if (s != lastTurnText) {
            lastTurnText = s
            b.txtTurnDistance.text = s
        }
    }

    private fun setStreet(s: String) {
        if (s != lastStreet) {
            lastStreet = s
            b.txtTurnStreet.text = s
        }
    }

    private fun setBottom(s: String) {
        if (s != lastBottom) {
            lastBottom = s
            b.txtRoute.text = s
        }
    }

    @Suppress("DEPRECATION")
    private fun color(id: Int): Int = context.resources.getColor(id)

    private fun iconFor(t: Maneuver.Type?): Int = when (t) {
        null, Maneuver.Type.DEPART, Maneuver.Type.CONTINUE -> R.drawable.ic_turn_straight
        Maneuver.Type.TURN_LEFT -> R.drawable.ic_turn_left
        Maneuver.Type.TURN_RIGHT -> R.drawable.ic_turn_right
        Maneuver.Type.TURN_SLIGHT_LEFT, Maneuver.Type.EXIT_LEFT -> R.drawable.ic_turn_slight_left
        Maneuver.Type.TURN_SLIGHT_RIGHT, Maneuver.Type.EXIT_RIGHT -> R.drawable.ic_turn_slight_right
        Maneuver.Type.TURN_SHARP_LEFT -> R.drawable.ic_turn_sharp_left
        Maneuver.Type.TURN_SHARP_RIGHT -> R.drawable.ic_turn_sharp_right
        Maneuver.Type.KEEP_LEFT -> R.drawable.ic_keep_left
        Maneuver.Type.KEEP_RIGHT -> R.drawable.ic_keep_right
        Maneuver.Type.UTURN -> R.drawable.ic_turn_uturn
        Maneuver.Type.ROUNDABOUT -> R.drawable.ic_roundabout
        Maneuver.Type.ARRIVE -> R.drawable.ic_bat
    }

    /** Coarse steps so the number does not flicker every fix: 10 m under 500 m, 50 m under 2 km. */
    private fun formatTurnDistance(m: Double): String = when {
        m < 500 -> context.getString(R.string.dist_m, ((m / 10).toInt() * 10).coerceAtLeast(0))
        m < 2000 -> context.getString(R.string.dist_m, (m / 50).toInt() * 50)
        else -> context.getString(R.string.dist_km, m / 1000.0)
    }

    fun formatDistance(m: Double): String =
        if (m >= 1000.0) context.getString(R.string.dist_km, m / 1000.0) else context.getString(R.string.dist_m, m.toInt())

    fun formatDuration(s: Double): String {
        val min = (s / 60.0 + 0.5).toInt()
        return if (min >= 60) context.getString(R.string.dur_h_min, min / 60, min % 60) else context.getString(R.string.dur_min, min)
    }
}
