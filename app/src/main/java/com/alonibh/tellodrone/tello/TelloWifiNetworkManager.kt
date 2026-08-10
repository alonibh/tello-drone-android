package com.alonibh.tellodrone.tello

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

class TelloWifiNetworkManager(context: Context) {
    interface Listener {
        fun onAvailable(network: Network)
        fun onManualSelectionRequired(message: String)
        fun onUnavailable(message: String)
        fun onLost(network: Network)
    }

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var retainedNetwork: Network? = null

    fun request(listener: Listener) {
        cancel()
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
            if (Build.VERSION.SDK_INT >= 29) {
                Api29TelloNetworkRequest.request(connectivityManager, networkCallback)
            } else {
                requestExistingWifi(networkCallback, listener)
            }
        } catch (security: SecurityException) {
            callback = null
            listener.onUnavailable("Wi-Fi permission missing or revoked: ${security.message ?: "access denied"}")
        } catch (error: RuntimeException) {
            callback = null
            listener.onUnavailable("Could not request Tello Wi-Fi: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /** Android 9 cannot select an SSID; watch actual Wi-Fi Networks and validate via the SDK handshake. */
    private fun requestExistingWifi(networkCallback: ConnectivityManager.NetworkCallback, listener: Listener) {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        val existingWifi = connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        if (existingWifi == null) {
            listener.onManualSelectionRequired(
                "Connect to the TELLO Wi-Fi network in Android Wi-Fi settings, then return to the app.",
            )
        } else {
            retainedNetwork = existingWifi
            listener.onAvailable(existingWifi)
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
