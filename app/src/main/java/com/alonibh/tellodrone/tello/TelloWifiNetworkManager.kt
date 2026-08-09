package com.alonibh.tellodrone.tello

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher

class TelloWifiNetworkManager(context: Context) {
    interface Listener {
        fun onAvailable(network: Network)
        fun onUnavailable(message: String)
        fun onLost(network: Network)
    }

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var retainedNetwork: Network? = null

    fun request(listener: Listener) {
        cancel()
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(TELLO_SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                retainedNetwork = network
                listener.onAvailable(network)
            }

            override fun onUnavailable() = listener.onUnavailable("Tello Wi-Fi selection was cancelled or timed out")

            override fun onLost(network: Network) {
                if (retainedNetwork == network) {
                    retainedNetwork = null
                    listener.onLost(network)
                }
            }
        }
        callback = networkCallback
        try {
            connectivityManager.requestNetwork(request, networkCallback, NETWORK_REQUEST_TIMEOUT_MILLIS)
        } catch (security: SecurityException) {
            callback = null
            listener.onUnavailable("Wi-Fi permission missing or revoked: ${security.message ?: "access denied"}")
        } catch (error: RuntimeException) {
            callback = null
            listener.onUnavailable("Could not request Tello Wi-Fi: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun cancel() {
        callback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: IllegalArgumentException) {
                // Already unregistered by the platform.
            }
        }
        callback = null
        retainedNetwork = null
    }

    companion object {
        const val TELLO_SSID_PREFIX = "TELLO-"
        const val NETWORK_REQUEST_TIMEOUT_MILLIS = 45_000
    }
}
