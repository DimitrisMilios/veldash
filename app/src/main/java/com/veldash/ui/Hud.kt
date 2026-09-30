package com.veldash.ui

import android.content.Context
import android.content.res.ColorStateList
import android.text.format.DateFormat
import android.view.View
import com.veldash.R
import com.veldash.databinding.ActivityMainBinding
import com.veldash.nav.NavState
import com.veldash.routing.Maneuver
import com.veldash.routing.Route
import java.util.Date

/**
 * Binds navigation state to the HUD views in activity_main.xml: the yellow next-turn card
 * (top-left), the speed badge (top-right) and, bottom-left, either the "Where to?" pill (idle)
 * or the trip card with ETA and the end-route button (routing / navigating).
 *
 * All work here is setText/setImageResource on a handful of views, once per second.
 * Values are cached so unchanged text is not re-set (TextView.setText re-lays out).
 */
class Hud(private val context: Context, private val b: ActivityMainBinding) {

    private val timeFormat = DateFormat.getTimeFormat(context)
    private val date = Date()
    private val arrowTint = ColorStateList.valueOf(color(R.color.bat_black))

    private var lastIcon = 0
    private var lastTurnText: String? = null
    private var lastStreet: String? = null
    private var lastTripMain: String? = null
    private var lastTripSub: String? = null
    private var lastSpeed = Int.MIN_VALUE
    /** Starts true: the badge opens on the dim "GPS" placeholder with the unit hidden. */
    private var speedDim = true

    // ---- Speed ----

    fun showSpeed(kmh: Int) {
        if (kmh != lastSpeed || speedDim) {
            lastSpeed = kmh
            b.txtSpeed.text = context.getString(R.string.speed_kmh, kmh)
            if (speedDim) {
                speedDim = false
                b.txtSpeed.setTextColor(color(R.color.bat_yellow))
                b.txtSpeedUnit.visibility = View.VISIBLE
            }
        }
    }

    fun showSpeedUnavailable(gpsAvailable: Boolean) {
        if (!speedDim) {
            speedDim = true
            lastSpeed = Int.MIN_VALUE
            b.txtSpeed.setText(if (gpsAvailable) R.string.gps_searching else R.string.gps_off)
            b.txtSpeed.setTextColor(color(R.color.bat_text_dim))
            b.txtSpeedUnit.visibility = View.GONE
        }
    }

    // ---- Navigation ----

    fun showNavigation(state: NavState) {
        setCardVisible(true)
        setTripVisible(true)
        val next = state.next
        setIcon(iconFor(next?.type))
        setTurn(formatTurnDistance(state.distToNextM))
        setStreet(next?.streetName ?: "")
        date.time = System.currentTimeMillis() + (state.remainingS * 1000).toLong()
        setTrip(
            formatDuration(state.remainingS),
            context.getString(R.string.trip_sub, formatDistance(state.remainingM), timeFormat.format(date)),
        )
    }

    /** Frame-loop countdown between fixes. Only touches the view when the rounded text changes. */
    fun updateTurnDistance(m: Double) {
        if (b.cardManeuver.visibility != View.VISIBLE) return
        setTurn(formatTurnDistance(m))
    }

    fun showRerouting() {
        setCardVisible(true)
        setIcon(R.drawable.ic_turn_straight)
        setTurn(context.getString(R.string.rerouting))
        setStreet("")
    }

    fun showArrived() {
        setCardVisible(true)
        setTripVisible(true)
        setIcon(R.drawable.bat_logo)
        setTurn(context.getString(R.string.arrived))
        setStreet("")
        setTrip(context.getString(R.string.arrived), "")
    }

    fun showRouting() {
        setCardVisible(false)
        setTripVisible(true)
        setTrip(context.getString(R.string.routing), "")
    }

    fun showRouteFailed(reason: String) {
        setCardVisible(false)
        setTripVisible(true)
        setTrip(context.getString(R.string.route_failed), reason)
    }

    fun showRouteSummary(r: Route) {
        setTripVisible(true)
        val src = when (r.source) {
            Route.Source.MAPBOX -> R.string.src_mapbox
            Route.Source.OSRM -> R.string.src_osrm
            Route.Source.BROUTER -> R.string.src_brouter
        }
        setTrip(formatDuration(r.durationS), context.getString(R.string.route_summary_sub, formatDistance(r.distanceM), context.getString(src)))
    }

    fun showIdle() {
        setCardVisible(false)
        setTripVisible(false)
    }

    // ---- internals ----

    private fun setCardVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        if (b.cardManeuver.visibility != v) b.cardManeuver.visibility = v
    }

    /** The trip card and the search pill share the bottom-left corner: one or the other. */
    private fun setTripVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        if (b.cardTrip.visibility != v) {
            b.cardTrip.visibility = v
            b.btnSearch.visibility = if (visible) View.GONE else View.VISIBLE
        }
    }

    private fun setIcon(res: Int) {
        if (res != lastIcon) {
            lastIcon = res
            b.imgManeuver.setImageResource(res)
            // Arrows are single-colour vectors tinted black; the bat logo keeps its own colours.
            b.imgManeuver.imageTintList = if (res == R.drawable.bat_logo) null else arrowTint
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
            b.txtTurnStreet.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun setTrip(main: String, sub: String) {
        if (main != lastTripMain) {
            lastTripMain = main
            b.txtTripMain.text = main
        }
        if (sub != lastTripSub) {
            lastTripSub = sub
            b.txtTripSub.text = sub
            b.txtTripSub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
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
        Maneuver.Type.ARRIVE -> R.drawable.bat_logo
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
