package com.veldash.routing

import android.content.Context
import com.veldash.util.Bg
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hybrid routing facade.
 *
 *   online  -> Directions API (Mapbox or OSRM); on any failure fall through to BRouter
 *   offline -> BRouter
 *
 * Runs on the single background thread; result is delivered on the main thread.
 * Only the most recent request is delivered: a new destination cancels delivery of the old one.
 */
object Router {

    class Outcome(val route: Route?, val error: String?)

    private val seq = AtomicInteger()

    fun request(
        context: Context,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        onResult: (Outcome) -> Unit,
    ) {
        val app = context.applicationContext
        val id = seq.incrementAndGet()
        Bg.compute({ compute(app, fromLat, fromLon, toLat, toLon) }) { outcome ->
            if (id == seq.get()) onResult(outcome)
        }
    }

    private fun compute(ctx: Context, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Outcome {
        var onlineError: String? = null
        if (Connectivity.isOnline) {
            try {
                return Outcome(OnlineRouter.route(fromLat, fromLon, toLat, toLon), null)
            } catch (e: RoutingException) {
                onlineError = e.message
            }
        }
        if (OfflineRouter.isAvailable(ctx)) {
            return try {
                Outcome(OfflineRouter.route(ctx, fromLat, fromLon, toLat, toLon), null)
            } catch (e: RoutingException) {
                Outcome(null, e.message)
            }
        }
        return Outcome(null, onlineError ?: "Offline: no routing data installed")
    }
}
