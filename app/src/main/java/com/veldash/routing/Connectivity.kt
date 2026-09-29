package com.veldash.routing

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

/**
 * Tracks whether a network with internet capability exists. One flag, updated by callback.
 * Started once from the Application; never unregistered (lives as long as the process).
 */
object Connectivity {

    @Volatile
    var isOnline: Boolean = false
        private set

    private var cm: ConnectivityManager? = null

    fun start(context: Context) {
        if (cm != null) return
        val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm = manager
        isOnline = query(manager)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isOnline = true
            }

            override fun onLost(network: Network) {
                // Another network may still be up (wifi dropped, mobile data remains).
                isOnline = query(manager)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                manager.registerDefaultNetworkCallback(callback)
            } else {
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                manager.registerNetworkCallback(req, callback)
            }
        } catch (e: Exception) {
            // Some ROMs throw on too many callbacks; we degrade to the initial query result.
        }
    }

    @Suppress("DEPRECATION")
    private fun query(manager: ConnectivityManager): Boolean {
        return if (Build.VERSION.SDK_INT >= 23) {
            val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            manager.activeNetworkInfo?.isConnected == true
        }
    }
}
