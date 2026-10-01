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
 * Binds navigation state to the dashboard views in activity_main.xml:
 *  - the top banner: "WHERE TO?" when idle, "TO: <destination>" plus END ROUTE otherwise;
 *  - the next-turn card over the map: a yellow arrow, "NEXT: RIGHT TURN (700 M)", the street;
 *  - the trip readouts along the bottom: ROUTING… / NO ROUTE / ARRIVED, ETA (with the time
 *    left), DIST, SPEED and MODE (3D chase / 2D top-down / free look).
 *
 * All work here is setText/setImageResource on a handful of views, once per second.
 * Values are cached so unchanged text is not re-set (TextView.setText re-lays out).
 */
class Hud(private val context: Context, private val b: ActivityMainBinding) {

    private val timeFormat = DateFormat.getTimeFormat(context)
    private val date = Date()

    private var lastIcon = 0
    private var maneuverName = ""
    private var lastManeuverName: String? = null
    private var lastDistText: String? = null
    private var lastTurn: String? = null
    private var lastStreet: String? = null
    private var lastStatus: String? = null
    private var lastEta: String? = null
    private var lastDist: String? = null
    private var lastMode: String? = null
    private var lastDestination: String? = null
    private var lastSpeed = Int.MIN_VALUE
    /** Starts true: the readout opens on the dim "--" placeholder. */
    private var speedDim = true
    private var lastSpeedPlaceholder = R.string.speed_none
    /** True while the turn card shows a live distance that the frame loop may count down. */
    private var countdown = false

    // ---- Speed ----

    fun showSpeed(kmh: Int) {
        if (kmh != lastSpeed || speedDim) {
            lastSpeed = kmh
            b.txtSpeed.text = context.getString(R.string.speed_kmh, kmh)
            if (speedDim) {
                speedDim = false
                b.txtSpeed.setTextColor(color(R.color.bat_text))
            }
        }
    }

    fun showSpeedUnavailable(gpsAvailable: Boolean) {
        val res = if (gpsAvailable) R.string.speed_none else R.string.gps_off
        if (!speedDim || res != lastSpeedPlaceholder) {
            speedDim = true
            lastSpeed = Int.MIN_VALUE
            lastSpeedPlaceholder = res
            b.txtSpeed.setText(res)
            b.txtSpeed.setTextColor(color(R.color.bat_text_dim))
        }
    }

    // ---- Banner and mode ----

    /** Top banner: "TO: name" with END ROUTE, or the WHERE TO? prompt when [name] is null. */
    fun showDestination(name: String?) {
        if (name == lastDestination) return
        lastDestination = name
        val routing = name != null
        b.txtDestLabel.setText(if (routing) R.string.label_to else R.string.where_to)
        b.txtDestName.text = name ?: ""
        b.txtDestName.visibility = if (routing) View.VISIBLE else View.GONE
        b.imgSearchHint.visibility = if (routing) View.GONE else View.VISIBLE
        b.btnEndRoute.visibility = if (routing) View.VISIBLE else View.GONE
    }

    /** MODE readout: what the camera is doing. */
    fun showMode(view3d: Boolean, follow: Boolean) {
        val s = context.getString(
            when {
                !follow -> R.string.mode_free
                view3d -> R.string.mode_3d
                else -> R.string.mode_2d
            },
        )
        if (s != lastMode) {
            lastMode = s
            b.txtMode.text = s
        }
    }

    // ---- Navigation ----

    fun showNavigation(state: NavState) {
        setCardVisible(true)
        val next = state.next
        setIcon(iconFor(next?.type))
        maneuverName = context.getString(nameFor(next?.type))
        countdown = true
        setTurn(formatTurnDistance(state.distToNextM))
        setStreet(next?.streetName ?: "")
        setStatus(null)
        date.time = System.currentTimeMillis() + (state.remainingS * 1000).toLong()
        setTrip(
            context.getString(R.string.eta_value, timeFormat.format(date), formatDuration(state.remainingS)),
            formatDistance(state.remainingM),
        )
    }

    /** Frame-loop countdown between fixes. Only touches the view when the rounded text changes. */
    fun updateTurnDistance(m: Double) {
        if (!countdown || b.cardManeuver.visibility != View.VISIBLE) return
        setTurn(formatTurnDistance(m))
    }

    fun showRerouting() {
        setCardVisible(true)
        setIcon(R.drawable.ic_turn_straight)
        countdown = false
        setTurnText(context.getString(R.string.rerouting))
        setStreet("")
    }

    fun showArrived() {
        setCardVisible(true)
        setIcon(R.drawable.bat_logo)
        countdown = false
        setTurnText(context.getString(R.string.arrived))
        setStreet("")
        setStatus(context.getString(R.string.arrived))
        setTrip(null, null)
    }

    fun showRouting() {
        setCardVisible(false)
        setStatus(context.getString(R.string.routing))
        setTrip(null, null)
    }

    fun showRouteFailed(reason: String) {
        setCardVisible(false)
        setStatus(if (reason.isBlank()) context.getString(R.string.route_failed) else context.getString(R.string.route_failed_reason, reason))
        setTrip(null, null)
    }

    fun showRouteSummary(r: Route) {
        setStatus(null)
        date.time = System.currentTimeMillis() + (r.durationS * 1000).toLong()
        setTrip(
            context.getString(R.string.eta_value, timeFormat.format(date), formatDuration(r.durationS)),
            formatDistance(r.distanceM),
        )
    }

    fun showIdle() {
        setCardVisible(false)
        countdown = false
        setStatus(null)
        setTrip(null, null)
        showDestination(null)
    }

    // ---- internals ----

    private fun setCardVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        if (b.cardManeuver.visibility != v) b.cardManeuver.visibility = v
    }

    private fun setIcon(res: Int) {
        if (res != lastIcon) {
            lastIcon = res
            b.imgManeuver.setImageResource(res)
        }
    }

    /** "NEXT: <maneuver> (<distance>)", rebuilt only when either part changes. */
    private fun setTurn(dist: String) {
        if (dist == lastDistText && maneuverName == lastManeuverName) return
        lastDistText = dist
        lastManeuverName = maneuverName
        setTurnText(context.getString(R.string.next_turn, maneuverName, dist))
    }

    private fun setTurnText(s: String) {
        if (s != lastTurn) {
            lastTurn = s
            b.txtTurn.text = s
        }
        if (!countdown) {
            // A fixed caption (REROUTING, ARRIVED): the next live distance must redraw.
            lastDistText = null
        }
    }

    private fun setStreet(s: String) {
        if (s != lastStreet) {
            lastStreet = s
            b.txtTurnStreet.text = s
            b.txtTurnStreet.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    /** The route-status readout (ROUTING…, NO ROUTE, ARRIVED); null hides it. */
    private fun setStatus(s: String?) {
        if (s == lastStatus) return
        lastStatus = s
        b.txtTripStatus.text = s ?: ""
        b.txtTripStatus.visibility = if (s == null) View.GONE else View.VISIBLE
    }

    /** ETA and DIST readouts; null hides a segment (and its divider). */
    private fun setTrip(eta: String?, dist: String?) {
        if (eta != lastEta) {
            lastEta = eta
            b.txtEta.text = eta ?: ""
            b.segEta.visibility = if (eta == null) View.GONE else View.VISIBLE
        }
        if (dist != lastDist) {
            lastDist = dist
            b.txtDist.text = dist ?: ""
            b.segDist.visibility = if (dist == null) View.GONE else View.VISIBLE
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

    private fun nameFor(t: Maneuver.Type?): Int = when (t) {
        null, Maneuver.Type.CONTINUE -> R.string.turn_continue
        Maneuver.Type.DEPART -> R.string.turn_depart
        Maneuver.Type.TURN_LEFT -> R.string.turn_left
        Maneuver.Type.TURN_RIGHT -> R.string.turn_right
        Maneuver.Type.TURN_SLIGHT_LEFT -> R.string.turn_slight_left
        Maneuver.Type.TURN_SLIGHT_RIGHT -> R.string.turn_slight_right
        Maneuver.Type.EXIT_LEFT -> R.string.turn_exit_left
        Maneuver.Type.EXIT_RIGHT -> R.string.turn_exit_right
        Maneuver.Type.TURN_SHARP_LEFT -> R.string.turn_sharp_left
        Maneuver.Type.TURN_SHARP_RIGHT -> R.string.turn_sharp_right
        Maneuver.Type.KEEP_LEFT -> R.string.turn_keep_left
        Maneuver.Type.KEEP_RIGHT -> R.string.turn_keep_right
        Maneuver.Type.UTURN -> R.string.turn_uturn
        Maneuver.Type.ROUNDABOUT -> R.string.turn_roundabout
        Maneuver.Type.ARRIVE -> R.string.turn_arrive
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
