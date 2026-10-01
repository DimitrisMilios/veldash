package com.veldash.util

import android.content.Context
import com.veldash.search.Place
import com.veldash.search.PlaceStore

/** Thin SharedPreferences wrapper. One file, a handful of keys. */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("veldash", Context.MODE_PRIVATE)

    /** Absolute path of the last selected .mbtiles file, or null. */
    var mapPath: String?
        get() = sp.getString(KEY_MAP_PATH, null)
        set(value) = sp.edit().putString(KEY_MAP_PATH, value).apply()

    /** Chase camera (3D, behind the car) vs top-down 2D. */
    var view3d: Boolean
        get() = sp.getBoolean(KEY_VIEW_3D, true)
        set(value) = sp.edit().putBoolean(KEY_VIEW_3D, value).apply()

    /** Home and Work shortcuts (search panel). Null until the driver sets them. */
    var home: Place?
        get() = place(KEY_HOME)
        set(value) = setPlace(KEY_HOME, value)

    var work: Place?
        get() = place(KEY_WORK)
        set(value) = setPlace(KEY_WORK, value)

    private fun place(key: String): Place? = try {
        sp.getString(key, null)?.let { PlaceStore.fromJson(it).firstOrNull() }
    } catch (e: Exception) {
        null
    }

    private fun setPlace(key: String, p: Place?) {
        val e = sp.edit()
        if (p == null) e.remove(key) else e.putString(key, PlaceStore.toJson(listOf(p)))
        e.apply()
    }

    private companion object {
        const val KEY_MAP_PATH = "map_path"
        const val KEY_VIEW_3D = "view3d"
        const val KEY_HOME = "home"
        const val KEY_WORK = "work"
    }
}
