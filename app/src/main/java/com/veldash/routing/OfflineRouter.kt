package com.veldash.routing

import android.content.Context
import btools.router.BRouterBridge
import java.io.File
import java.io.FileOutputStream

/**
 * Offline routing via embedded BRouter. Fully on-device, no network.
 *
 * Data layout (external app dir, so users can copy files in without root):
 *   <external>/Android/data/com.veldash/files/brouter/segments/  user-supplied .rd5 map segments
 *   <external>/Android/data/com.veldash/files/brouter/profiles/         copied from APK assets on first use
 *
 * Synchronous: call from the background thread only. A long route can take several seconds
 * on a low-end SoC; the engine and its node cache are garbage after the call returns.
 */
object OfflineRouter {

    private const val PROFILE = "car-vario.brf"
    private const val LOOKUPS = "lookups.dat"
    private const val ASSET_DIR = "brouter"

    /** Wall-clock cap for one search. */
    private const val MAX_RUN_MS = 60_000L

    /** BRouter node-cache budget. Transient, released after each route. */
    private const val MEMORY_MB = 32

    fun dataDir(context: Context): File {
        val dir = context.getExternalFilesDir(ASSET_DIR) ?: File(context.filesDir, ASSET_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun segmentsDir(context: Context): File = File(dataDir(context), "segments").also { if (!it.exists()) it.mkdirs() }

    fun hasSegments(context: Context): Boolean =
        segmentsDir(context).listFiles()?.any { it.name.endsWith(".rd5", ignoreCase = true) } == true

    fun hasProfileAssets(context: Context): Boolean = try {
        val names = context.assets.list(ASSET_DIR) ?: emptyArray()
        PROFILE in names && LOOKUPS in names
    } catch (e: Exception) {
        false
    }

    /** True when both a profile and at least one segment file are present. */
    fun isAvailable(context: Context): Boolean = hasProfileAssets(context) && hasSegments(context)

    @Throws(RoutingException::class)
    fun route(context: Context, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route {
        if (!hasProfileAssets(context)) throw RoutingException("Offline routing profile missing from app")
        if (!hasSegments(context)) throw RoutingException("No offline routing data (.rd5) installed")

        val profile = ensureProfiles(context)
        val res = BRouterBridge.route(
            segmentsDir(context), profile,
            fromLat, fromLon, toLat, toLon,
            MAX_RUN_MS, MEMORY_MB,
        )
        res.error?.let { throw RoutingException(it) }

        val n = res.hintIndex.size
        val maneuvers = ArrayList<Maneuver>(n + 2)
        for (i in 0 until n) {
            val type = mapCmd(res.hintCmd[i]) ?: continue
            val idx = res.hintIndex[i].coerceIn(0, res.lats.size - 1)
            maneuvers += Maneuver(
                type = type,
                lat = res.lats[idx],
                lon = res.lons[idx],
                pointIndex = idx,
                distanceToNextM = res.hintDistToNext[i],
                streetName = null,
                exit = res.hintExit[i],
            )
        }
        // BRouter emits no arrival hint; add one so the HUD has a terminal instruction.
        if (maneuvers.lastOrNull()?.type != Maneuver.Type.ARRIVE) {
            val last = res.lats.size - 1
            maneuvers += Maneuver(Maneuver.Type.ARRIVE, res.lats[last], res.lons[last], last, 0.0, null, 0)
        }

        return Route(
            lats = res.lats,
            lons = res.lons,
            distanceM = res.distanceM.toDouble(),
            durationS = res.seconds.toDouble(),
            maneuvers = maneuvers,
            source = Route.Source.BROUTER,
        )
    }

    /** BRouter VoiceHint command ints (see VoiceHint.java) to our enum. */
    private fun mapCmd(cmd: Int): Maneuver.Type? = when (cmd) {
        1 -> Maneuver.Type.CONTINUE          // C
        2 -> Maneuver.Type.TURN_LEFT         // TL
        3 -> Maneuver.Type.TURN_SLIGHT_LEFT  // TSLL
        4 -> Maneuver.Type.TURN_SHARP_LEFT   // TSHL
        5 -> Maneuver.Type.TURN_RIGHT        // TR
        6 -> Maneuver.Type.TURN_SLIGHT_RIGHT // TSLR
        7 -> Maneuver.Type.TURN_SHARP_RIGHT  // TSHR
        8 -> Maneuver.Type.KEEP_LEFT         // KL
        9 -> Maneuver.Type.KEEP_RIGHT        // KR
        10, 11, 15 -> Maneuver.Type.UTURN    // TLU, TRU, TU
        13, 14 -> Maneuver.Type.ROUNDABOUT   // RNDB, RNLB
        17 -> Maneuver.Type.EXIT_LEFT        // EL
        18 -> Maneuver.Type.EXIT_RIGHT       // ER
        100 -> Maneuver.Type.ARRIVE          // END
        else -> null                         // OFFR, BL: nothing to announce
    }

    /**
     * Copies the profile and lookup table out of the APK once. BRouter needs real files and
     * finds lookups.dat next to the profile. Re-copies if the asset size changed (app update).
     */
    private fun ensureProfiles(context: Context): File {
        val dir = File(dataDir(context), "profiles")
        if (!dir.exists()) dir.mkdirs()
        val profile = File(dir, PROFILE)
        copyAsset(context, "$ASSET_DIR/$PROFILE", profile)
        copyAsset(context, "$ASSET_DIR/$LOOKUPS", File(dir, LOOKUPS))
        return profile
    }

    private fun copyAsset(context: Context, assetPath: String, target: File) {
        val am = context.assets
        val assetSize = try {
            am.openFd(assetPath).use { it.length }
        } catch (e: Exception) {
            -1L // compressed asset: size unknown, fall back to "copy if missing"
        }
        if (target.exists() && (assetSize < 0 || target.length() == assetSize)) return
        am.open(assetPath).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output, 16 * 1024) }
        }
    }
}
