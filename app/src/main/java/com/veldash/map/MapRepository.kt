package com.veldash.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Finds .mbtiles files on the device. No file picker UI framework, no SAF copy:
 * MapLibre needs a real filesystem path so the tiles are read in place, never copied.
 *
 * Search order:
 *  1. <external>/Android/data/com.veldash/files/maps  (works on every API level, no permission)
 *  2. /sdcard/veldash and /sdcard/Download             (API <= 32 only, needs READ_EXTERNAL_STORAGE)
 */
object MapRepository {

    private const val EXT = ".mbtiles"

    /** The app-private maps dir. Always exists after this call. */
    fun mapsDir(context: Context): File {
        val dir = context.getExternalFilesDir("maps") ?: File(context.filesDir, "maps")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** True when scanning the shared /sdcard folders requires a runtime permission we do not have. */
    fun needsStoragePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT > 32) return false
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
    }

    /** Directories to scan, in priority order. */
    fun candidateDirs(context: Context): List<File> {
        val dirs = ArrayList<File>(3)
        dirs += mapsDir(context)
        if (Build.VERSION.SDK_INT <= 32 && !needsStoragePermission(context)) {
            val root = Environment.getExternalStorageDirectory()
            dirs += File(root, "veldash")
            dirs += Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }
        return dirs
    }

    /**
     * Lists every readable .mbtiles file. Reads each file's metadata table, so run on [com.veldash.util.Bg].
     * Duplicate paths (e.g. symlinked dirs) are collapsed.
     */
    fun scan(context: Context): List<MapFile> {
        val seen = HashSet<String>()
        val out = ArrayList<MapFile>()
        for (dir in candidateDirs(context)) {
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (!f.name.endsWith(EXT, ignoreCase = true)) continue
                if (!seen.add(f.absolutePath)) continue
                MapFile.read(f)?.let(out::add)
            }
        }
        out.sortBy { it.name.lowercase() }
        return out
    }
}
