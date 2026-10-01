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
 * Binds navigation state to the islands in activity_main.xml:
 *  - the destination pill: Where to? when idle, "To <destination>" plus the end-route cross;
 *  - the next-turn card: yellow arrow, maneuver label over the big distance, the street;
 *  - the trip island: the speedometer badge, then Routing… / No route / Arrived or the time
 *    left in yellow over "distance · arrival time".
 *
 * All work here is setText/setImageResource on a handful of views, once per second.
 * Values are cached so unchanged text is not re-set (TextView.setText re-lays out).
 */
class Hud(private val context: Context, private val b: ActivityMainBinding) {

    private val timeFormat = DateFormat.getTimeFormat(context)
    private val date = Date()

    private var lastIcon = 0
    private var lastTurnLabel: String? = null
    private var lastDistText: String? = null
    private var lastStreet: String? = null
    private var lastStatus: String? = null
    private var lastTimeLeft: String? = null
    private var lastSub: String? = null
    private var lastDestination: String? = null
    private var lastSpeed = Int.MIN_VALUE
    /** Starts true: the speed badge opens on the dim "--" placeholder with the unit hidden. */
    private var speedDim = true
    private var lastSpeedPlaceholder = R.string.speed_none
    /** True while the turn card shows a live distance that the frame loop may count down. */
    private var countdown = false

    // ---- Speed ----

    fun showSpeed(kmh: Int) {
        if (kmh != lastSpeed || speedDim) {
            lastSpeed = kmh
            b.txtSpeed.text = context.getString(R.string.speed_value, kmh)
            if (speedDim) {
                speedDim = false
                b.txtSpeed.setTextColor(color(R.color.bat_text))
                b.txtSpeedUnit.visibility = View.VISIBLE
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
            b.txtSpeedUnit.visibility = View.GONE
        }
    }

    // ---- Destination pill ----

    /** "To <name>" with the end-route cross, or the Where to? prompt when [name] is null. */
    fun showDestination(name: String?) {
        if (name == lastDestination) return
        lastDestination = name
        val routing = name != null
        val idleV = if (routing) View.GONE else View.VISIBLE
        val routeV = if (routing) View.VISIBLE else View.GONE
        b.imgDestIcon.visibility = idleV
        b.txtWhereTo.visibility = idleV
        b.imgDestBat.visibility = routeV
        b.txtDestLabel.visibility = routeV
        b.txtDestName.text = name ?: ""
        b.txtDestName.visibility = routeV
        b.btnEndRoute.visibility = routeV
    }

    // ---- Navigation ----

    fun showNavigation(state: NavState) {
        setCardVisible(true)
        val next = state.next
        setIcon(iconFor(next?.type))
        countdown = true
        setTurnLabel(context.getString(nameFor(next?.type)))
        setTurnDistance(formatTurnDistance(state.distToNextM))
        setStreet(next?.streetName ?: "")
        setStatus(null)
        setTrip(state.remainingS, state.remainingM)
    }

    /** Frame-loop countdown between fixes. Only touches the view when the rounded text changes. */
    fun updateTurnDistance(m: Double) {
        if (!countdown || b.cardManeuver.visibility != View.VISIBLE) return
        setTurnDistance(formatTurnDistance(m))
    }

    fun showRerouting() {
        setCardVisible(true)
        setIcon(R.drawable.ic_turn_straight)
        countdown = false
        setTurnLabel(context.getString(R.string.rerouting))
        setTurnDistance(null)
        setStreet("")
    }

    fun showArrived() {
        setCardVisible(true)
        setIcon(R.drawable.bat_logo)
        countdown = false
        setTurnLabel(context.getString(R.string.arrived))
        setTurnDistance(null)
        setStreet("")
        setStatus(context.getString(R.string.arrived))
        clearTrip()
    }

    fun showRouting() {
        setCardVisible(false)
        setStatus(context.getString(R.string.routing))
        clearTrip()
    }

    fun showRouteFailed(reason: String) {
        setCardVisible(false)
        setStatus(if (reason.isBlank()) context.getString(R.string.route_failed) else context.getString(R.string.route_failed_reason, reason))
        clearTrip()
    }

    fun showRouteSummary(r: Route) {
        setStatus(null)
        setTrip(r.durationS, r.distanceM)
    }

    fun showIdle() {
        setCardVisible(false)
        countdown = false
        setStatus(null)
        clearTrip()
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

    private fun setTurnLabel(s: String) {
        if (s != lastTurnLabel) {
            lastTurnLabel = s
            b.txtTurnLabel.text = s
        }
    }

    /** The big distance; null hides it (Rerouting, Arrived). */
    private fun setTurnDistance(s: String?) {
        if (s == lastDistText) return
        lastDistText = s
        b.txtTurnDistance.text = s ?: ""
        b.txtTurnDistance.visibility = if (s == null) View.GONE else View.VISIBLE
    }

    private fun setStreet(s: String) {
        if (s != lastStreet) {
            lastStreet = s
            b.txtTurnStreet.text = s
            b.txtTurnStreet.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    /** The route-status reading (Routing…, No route, Arrived); null hides it. */
    private fun setStatus(s: String?) {
        if (s == lastStatus) return
        lastStatus = s
        b.txtTripStatus.text = s ?: ""
        b.txtTripStatus.visibility = if (s == null) View.GONE else View.VISIBLE
        updateTripBlock()
    }

    /** Time left (big, yellow) over "distance · arrival time". */
    private fun setTrip(remainingS: Double, remainingM: Double) {
        val timeLeft = formatDuration(remainingS)
        if (timeLeft != lastTimeLeft) {
            lastTimeLeft = timeLeft
            b.txtTimeLeft.text = timeLeft
            b.txtTimeLeft.visibility = View.VISIBLE
        }
        date.time = System.currentTimeMillis() + (remainingS * 1000).toLong()
        val sub = context.getString(R.string.trip_sub, formatDistance(remainingM), timeFormat.format(date))
        if (sub != lastSub) {
            lastSub = sub
            b.txtTripSub.text = sub
            b.txtTripSub.visibility = View.VISIBLE
        }
        updateTripBlock()
    }

    private fun clearTrip() {
        if (lastTimeLeft == null && lastSub == null) return
        lastTimeLeft = null
        lastSub = null
        b.txtTimeLeft.visibility = View.GONE
        b.txtTripSub.visibility = View.GONE
        updateTripBlock()
    }

    /** The island is just the speed badge until there is a status or a trip to show. */
    private fun updateTripBlock() {
        val v = if (lastStatus != null || lastTimeLeft != null) View.VISIBLE else View.GONE
        if (b.tripBlock.visibility != v) b.tripBlock.visibility = v
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
