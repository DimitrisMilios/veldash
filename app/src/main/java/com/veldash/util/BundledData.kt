package com.veldash.util

import android.content.Context
import android.util.Log
import com.veldash.map.MapRepository
import com.veldash.routing.OfflineRouter
import java.io.File
import java.io.FileOutputStream

/**
 * Map tiles and routing segments shipped inside the APK, so the app works offline from the
 * first start with nothing copied over USB.
 *
 * MapLibre and BRouter both need real files on disk, so the assets are copied out once into the
 * same folders a user would copy their own files to (see README). A file is re-copied only when
 * it is missing or its size differs from the asset (an app update with newer data). Nothing
 * else is ever deleted: a user's own files next to ours stay untouched.
 *
 *   assets/maps/<name>.mbtiles            -> MapRepository.mapsDir()
 *   assets/brouter/segments/<tile>.rd5    -> OfflineRouter.segmentsDir()
 *
 * The data files are not committed to git; `tools/fetch-bundled-data.sh` produces them.
 * Blocking I/O: call from [Bg].
 */
object BundledData {

    private const val TAG = "BundledData"
    private const val MAPS_ASSET_DIR = "maps"
    private const val SEGMENTS_ASSET_DIR = "brouter/segments"

    /** The bundled map file on disk, or null when the APK carries no map. Cheap: no I/O beyond an asset listing. */
    fun bundledMap(context: Context): File? {
        val name = list(context, MAPS_ASSET_DIR).firstOrNull { it.endsWith(".mbtiles", ignoreCase = true) } ?: return null
        return File(MapRepository.mapsDir(context), name)
    }

    /** Copies out whatever is missing or stale. Returns true when at least one file was written. */
    fun install(context: Context): Boolean {
        var changed = false
        for (name in list(context, MAPS_ASSET_DIR)) {
            if (!name.endsWith(".mbtiles", ignoreCase = true)) continue
            changed = copyIfStale(context, "$MAPS_ASSET_DIR/$name", File(MapRepository.mapsDir(context), name)) || changed
        }
        for (name in list(context, SEGMENTS_ASSET_DIR)) {
            if (!name.endsWith(".rd5", ignoreCase = true)) continue
            changed = copyIfStale(context, "$SEGMENTS_ASSET_DIR/$name", File(OfflineRouter.segmentsDir(context), name)) || changed
        }
        return changed
    }

    private fun list(context: Context, dir: String): Array<String> = try {
        context.assets.list(dir) ?: emptyArray()
    } catch (e: Exception) {
        emptyArray()
    }

    /**
     * Streams the asset to [target] via a temp file, renamed into place at the end, so a crash
     * or a full disk mid-copy never leaves a truncated map that MapLibre would then try to open.
     */
    private fun copyIfStale(context: Context, assetPath: String, target: File): Boolean {
        val size = assetSize(context, assetPath)
        if (target.exists() && (size < 0 || target.length() == size)) return false
        val tmp = File(target.parentFile, target.name + ".part")
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.fd.sync()
                }
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw java.io.IOException("rename failed: $target")
            }
            Log.i(TAG, "Installed $assetPath -> $target (${target.length() / 1_048_576} MB)")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Could not install $assetPath", e)
            tmp.delete()
            return false
        }
    }

    /** Uncompressed asset size, or -1 when the asset is compressed (size unknown without reading it). */
    private fun assetSize(context: Context, assetPath: String): Long = try {
        context.assets.openFd(assetPath).use { it.length }
    } catch (e: Exception) {
        -1L
    }
}
