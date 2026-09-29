package com.veldash.search

import android.content.Context
import com.veldash.util.Bg
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Offline favorites: a JSON array in the app's private files dir. Loaded once on the
 * background thread, then held in memory; every change writes a snapshot asynchronously.
 * All public methods are main-thread only.
 */
class Favorites(context: Context) {

    private val file = File(context.filesDir, "favorites.json")
    private val items = ArrayList<Place>()

    @Volatile
    var loaded = false
        private set

    fun load(onLoaded: () -> Unit) {
        Bg.compute({ readFile() }) { list ->
            items.clear()
            items.addAll(list)
            loaded = true
            onLoaded()
        }
    }

    fun all(): List<Place> = ArrayList(items)

    fun isFavorite(lat: Double, lon: Double): Boolean = items.any { it.sameSpot(lat, lon) }

    fun find(lat: Double, lon: Double): Place? = items.firstOrNull { it.sameSpot(lat, lon) }

    fun add(p: Place) {
        items.removeAll { it.sameSpot(p.lat, p.lon) }
        items.add(0, p)
        save()
    }

    fun remove(p: Place) {
        if (items.removeAll { it.sameSpot(p.lat, p.lon) }) save()
    }

    /** Case-insensitive substring match on name and detail. Empty query returns everything. */
    fun matching(query: String): List<Place> {
        val q = query.trim()
        if (q.isEmpty()) return all()
        return items.filter { it.name.contains(q, ignoreCase = true) || it.detail.contains(q, ignoreCase = true) }
    }

    // ---- persistence ----

    private fun save() {
        val snapshot = toJson(items)
        Bg.execute {
            try {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(snapshot)
                if (!tmp.renameTo(file)) {
                    file.writeText(snapshot)
                    tmp.delete()
                }
            } catch (e: Exception) {
                // Storage full or read-only: favorites stay in memory for this session.
            }
        }
    }

    private fun readFile(): List<Place> {
        if (!file.isFile) return emptyList()
        return try {
            fromJson(file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        fun toJson(list: List<Place>): String {
            val arr = JSONArray()
            for (p in list) {
                arr.put(
                    JSONObject()
                        .put("name", p.name)
                        .put("detail", p.detail)
                        .put("lat", p.lat)
                        .put("lon", p.lon),
                )
            }
            return arr.toString()
        }

        fun fromJson(json: String): List<Place> {
            val arr = JSONArray(json)
            val out = ArrayList<Place>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Place(
                    o.optString("name", "?"),
                    o.optString("detail", ""),
                    o.getDouble("lat"),
                    o.getDouble("lon"),
                )
            }
            return out
        }
    }
}
