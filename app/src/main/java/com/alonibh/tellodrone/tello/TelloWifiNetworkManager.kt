package com.alonibh.tellodrone.tello

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

class TelloWifiNetworkManager(private val context: Context) {
    interface Listener {
        fun onAvailable(network: Network)
        fun onManualSelectionRequired(message: String)
        fun onUnavailable(message: String)
        fun onLost(network: Network)
    }

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var scanReceiver: BroadcastReceiver? = null
    private var scanTimeoutRunnable: Runnable? = null
    private var connectionTimeoutRunnable: Runnable? = null
    private var retainedNetwork: Network? = null

    fun request(listener: Listener) {
        cancel()
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cancelTimeouts()
                unregisterScanReceiver()
                retainedNetwork = network
                listener.onAvailable(network)
            }

            override fun onUnavailable() {
                cancelTimeouts()
                unregisterScanReceiver()
                listener.onUnavailable("Tello Wi-Fi selection was cancelled or timed out")
            }

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
                requestApi28(networkCallback, listener)
            }
        } catch (security: SecurityException) {
            cancel()
            listener.onUnavailable("Wi-Fi permission missing or revoked: ${security.message ?: "access denied"}")
        } catch (error: RuntimeException) {
            cancel()
            listener.onUnavailable("Could not request Tello Wi-Fi: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /**
     * On Android 9 (API 28), scan and connect to a single visible open TELLO-* Wi-Fi network,
     * or reuse the connection if already connected.
     */
    @Suppress("DEPRECATION")
    private fun requestApi28(networkCallback: ConnectivityManager.NetworkCallback, listener: Listener) {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)

        val currentSsid = wifiManager?.connectionInfo?.ssid?.removeSurrounding("\"")
        val isAlreadyTello = currentSsid != null &&
            currentSsid.startsWith(TELLO_SSID_PREFIX, ignoreCase = true) &&
            currentSsid != "<unknown ssid>"

        if (isAlreadyTello) {
            val existingWifi = connectivityManager.allNetworks.firstOrNull { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (existingWifi != null) {
                retainedNetwork = existingWifi
                listener.onAvailable(existingWifi)
                return
            }
            listener.onManualSelectionRequired("Connected to $currentSsid; waiting for network...")
            startConnectionTimeout(listener)
            return
        }

        val isLocationEnabled = locationManager?.isLocationEnabled == true
        if (!isLocationEnabled) {
            cancel()
            listener.onUnavailable("Location services must be enabled to scan for Tello Wi-Fi on Android 9.")
            return
        }

        registerScanReceiver(listener)
        startScanTimeout(listener)

        val scanStarted = wifiManager?.startScan() == true
        if (!scanStarted) {
            // startScan may be throttled; evaluate available cached scan results immediately
            handleScanResults(listener, isFallback = true)
        }
    }

    private fun registerScanReceiver(listener: Listener) {
        unregisterScanReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    handleScanResults(listener, isFallback = false)
                }
            }
        }
        scanReceiver = receiver
        context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
    }

    @Suppress("DEPRECATION")
    private fun handleScanResults(listener: Listener, isFallback: Boolean) {
        val rawResults = try {
            wifiManager?.scanResults ?: emptyList()
        } catch (security: SecurityException) {
            cancel()
            listener.onUnavailable("Wi-Fi scan permission denied: ${security.message ?: "access denied"}")
            return
        }

        if (rawResults.isEmpty() && !isFallback) return

        val discovered = rawResults.map {
            DiscoveredWifiNetwork(
                ssid = it.SSID ?: "",
                capabilities = it.capabilities ?: "",
                bssid = it.BSSID ?: "",
                level = it.level,
            )
        }

        val currentSsid = wifiManager?.connectionInfo?.ssid?.removeSurrounding("\"")
        val isLocationEnabled = locationManager?.isLocationEnabled == true

        val decision = Api28WifiScanPolicy.evaluate(
            scanResults = discovered,
            currentSsid = currentSsid,
            isLocationEnabled = isLocationEnabled,
        )

        when (decision) {
            is Api28ScanDecision.AlreadyConnected -> {
                unregisterScanReceiver()
                val existingWifi = connectivityManager.allNetworks.firstOrNull { network ->
                    connectivityManager.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }
                if (existingWifi != null) {
                    retainedNetwork = existingWifi
                    listener.onAvailable(existingWifi)
                } else {
                    startConnectionTimeout(listener)
                }
            }
            is Api28ScanDecision.Connect -> {
                unregisterScanReceiver()
                connectToOpenWifi(decision.ssid, listener)
            }
            is Api28ScanDecision.MultipleNetworks -> {
                cancel()
                listener.onUnavailable(decision.message)
            }
            is Api28ScanDecision.SecuredNetwork -> {
                cancel()
                listener.onUnavailable(decision.message)
            }
            is Api28ScanDecision.LocationDisabled -> {
                cancel()
                listener.onUnavailable(decision.message)
            }
            is Api28ScanDecision.NotFound -> {
                if (isFallback) {
                    cancel()
                    listener.onUnavailable(decision.message)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun connectToOpenWifi(ssid: String, listener: Listener) {
        listener.onManualSelectionRequired("Connecting to $ssid...")
        startConnectionTimeout(listener)

        val wifiConfig = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
        }

        val existingId = try {
            wifiManager?.configuredNetworks?.firstOrNull {
                it.SSID?.removeSurrounding("\"") == ssid
            }?.networkId
        } catch (_: SecurityException) {
            null
        }

        val netId = if (existingId != null && existingId != -1) {
            wifiManager?.updateNetwork(wifiConfig)?.takeIf { it != -1 } ?: existingId
        } else {
            wifiManager?.addNetwork(wifiConfig) ?: -1
        }

        if (netId == -1) {
            cancel()
            listener.onUnavailable("Could not configure Wi-Fi network for $ssid")
            return
        }

        wifiManager?.disconnect()
        val enabled = wifiManager?.enableNetwork(netId, true) == true
        wifiManager?.reconnect()
        if (!enabled) {
            cancel()
            listener.onUnavailable("Could not enable Wi-Fi connection to $ssid")
        }
    }

    private fun startScanTimeout(listener: Listener) {
        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            handleScanResults(listener, isFallback = true)
        }
        scanTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, SCAN_TIMEOUT_MILLIS)
    }

    private fun startConnectionTimeout(listener: Listener) {
        connectionTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            cancel()
            listener.onUnavailable("Tello Wi-Fi connection timed out")
        }
        connectionTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, NETWORK_REQUEST_TIMEOUT_MILLIS.toLong())
    }

    private fun cancelTimeouts() {
        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        connectionTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        connectionTimeoutRunnable = null
    }

    private fun unregisterScanReceiver() {
        scanReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // Receiver not registered
            }
        }
        scanReceiver = null
    }

    fun cancel() {
        cancelTimeouts()
        unregisterScanReceiver()
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
        const val SCAN_TIMEOUT_MILLIS = 15_000L
        const val NETWORK_REQUEST_TIMEOUT_MILLIS = 45_000
    }
}
