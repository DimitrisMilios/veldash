package com.veldash.map

import android.database.sqlite.SQLiteDatabase
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import java.io.File

/**
 * What we know about one .mbtiles file, read from its `metadata` table.
 * Everything here is optional in the MBTiles spec except name and format, so treat nulls as normal.
 */
class MapFile(
    val file: File,
    val name: String,
    /** "pbf" for vector tiles, "png"/"jpg"/"webp" for raster. */
    val format: String,
    val minZoom: Int,
    val maxZoom: Int,
    val bounds: LatLngBounds?,
    val center: LatLng?,
    val centerZoom: Double?,
) {
    val isVector: Boolean get() = format == "pbf"

    /** MapLibre source URL. The triple slash matters: scheme + absolute path. */
    val sourceUrl: String get() = "mbtiles://" + file.absolutePath

    companion object {

        /**
         * Opens the SQLite file read-only, reads the metadata table, closes it.
         * Costs one page read per row; call from the background thread anyway.
         * Returns null if the file is not a readable MBTiles database.
         */
        fun read(file: File): MapFile? {
            if (!file.isFile || !file.canRead()) return null
            val meta = HashMap<String, String>(16)
            try {
                SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                ).use { db ->
                    db.rawQuery("SELECT name, value FROM metadata", null).use { c ->
                        while (c.moveToNext()) {
                            val k = c.getString(0) ?: continue
                            meta[k] = c.getString(1) ?: ""
                        }
                    }
                }
            } catch (e: Exception) {
                return null
            }

            val format = meta["format"]?.lowercase() ?: return null

            return MapFile(
                file = file,
                name = meta["name"]?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
                format = format,
                minZoom = meta["minzoom"]?.toIntOrNull() ?: 0,
                maxZoom = meta["maxzoom"]?.toIntOrNull() ?: 14,
                bounds = parseBounds(meta["bounds"]),
                center = parseCenter(meta["center"]),
                centerZoom = parseCenterZoom(meta["center"]),
            )
        }

        /** MBTiles bounds: "left,bottom,right,top" in degrees. */
        private fun parseBounds(s: String?): LatLngBounds? {
            val p = s?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() } ?: return null
            if (p.size != 4) return null
            return try {
                LatLngBounds.from(p[3], p[2], p[1], p[0]) // north, east, south, west
            } catch (e: Exception) {
                null
            }
        }

        /** MBTiles center: "lon,lat,zoom". */
        private fun parseCenter(s: String?): LatLng? {
            val p = s?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() } ?: return null
            if (p.size < 2) return null
            return LatLng(p[1], p[0])
        }

        private fun parseCenterZoom(s: String?): Double? {
            val p = s?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() } ?: return null
            return p.getOrNull(2)
        }
    }
}
