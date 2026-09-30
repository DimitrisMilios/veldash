package com.veldash.util

import android.content.Context

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

    private companion object {
        const val KEY_MAP_PATH = "map_path"
        const val KEY_VIEW_3D = "view3d"
    }
}
