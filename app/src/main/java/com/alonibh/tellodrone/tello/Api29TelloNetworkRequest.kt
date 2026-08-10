package com.alonibh.tellodrone.tello

import android.net.ConnectivityManager
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher
import androidx.annotation.RequiresApi

/** Kept separate so Android 9 never loads WifiNetworkSpecifier. */
@RequiresApi(29)
internal object Api29TelloNetworkRequest {
    fun request(
        connectivityManager: ConnectivityManager,
        callback: ConnectivityManager.NetworkCallback,
    ) {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(TelloWifiNetworkManager.TELLO_SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        connectivityManager.requestNetwork(request, callback, TelloWifiNetworkManager.NETWORK_REQUEST_TIMEOUT_MILLIS)
    }
}
