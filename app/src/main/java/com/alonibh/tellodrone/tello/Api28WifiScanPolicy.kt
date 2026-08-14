package com.alonibh.tellodrone.tello

data class DiscoveredWifiNetwork(
    val ssid: String,
    val capabilities: String,
    val bssid: String = "",
    val level: Int = 0,
) {
    val cleanSsid: String = ssid.removeSurrounding("\"")

    val isTello: Boolean
        get() = cleanSsid.startsWith(TelloWifiNetworkManager.TELLO_SSID_PREFIX, ignoreCase = true)

    val isSecured: Boolean
        get() = isSecuredCapabilities(capabilities)

    companion object {
        fun isSecuredCapabilities(capabilities: String): Boolean {
            val upper = capabilities.uppercase()
            return upper.contains("WPA") ||
                upper.contains("WEP") ||
                upper.contains("PSK") ||
                upper.contains("EAP") ||
                upper.contains("SAE") ||
                upper.contains("OWE") ||
                upper.contains("WAPI")
        }
    }
}

sealed interface Api28ScanDecision {
    data class Connect(val ssid: String) : Api28ScanDecision
    data class AlreadyConnected(val ssid: String) : Api28ScanDecision
    data class MultipleNetworks(val ssids: List<String>, val message: String) : Api28ScanDecision
    data class SecuredNetwork(val ssid: String, val message: String) : Api28ScanDecision
    data class NotFound(val message: String) : Api28ScanDecision
    data class LocationDisabled(val message: String) : Api28ScanDecision
}

object Api28WifiScanPolicy {
    fun isTelloSsid(ssid: String?): Boolean {
        val clean = ssid?.removeSurrounding("\"")?.trim() ?: return false
        return clean.isNotBlank() &&
            clean.startsWith(TelloWifiNetworkManager.TELLO_SSID_PREFIX, ignoreCase = true) &&
            clean != "<unknown ssid>"
    }

    fun evaluate(
        scanResults: List<DiscoveredWifiNetwork>,
        currentSsid: String? = null,
        isLocationEnabled: Boolean = true,
    ): Api28ScanDecision {
        val cleanCurrentSsid = currentSsid?.removeSurrounding("\"")
        if (isTelloSsid(cleanCurrentSsid)) {
            return Api28ScanDecision.AlreadyConnected(cleanCurrentSsid!!)
        }

        if (!isLocationEnabled) {
            return Api28ScanDecision.LocationDisabled(
                "Location services must be enabled to scan for Tello Wi-Fi on Android 9.",
            )
        }

        val telloNetworks = scanResults.filter { it.isTello && it.cleanSsid.isNotBlank() }
        if (telloNetworks.isEmpty()) {
            return Api28ScanDecision.NotFound("No TELLO Wi-Fi network found in scan.")
        }

        val groupedBySsid = telloNetworks.groupBy { it.cleanSsid }
        if (groupedBySsid.size > 1) {
            val ssids = groupedBySsid.keys.sorted()
            return Api28ScanDecision.MultipleNetworks(
                ssids = ssids,
                message = "Multiple TELLO Wi-Fi networks found (${ssids.joinToString(", ")}). Manual connection required to select drone.",
            )
        }

        val (ssid, matching) = groupedBySsid.entries.first()
        if (matching.any { it.isSecured }) {
            return Api28ScanDecision.SecuredNetwork(
                ssid = ssid,
                message = "TELLO network '$ssid' is secured. Manual connection or credentials are required.",
            )
        }

        return Api28ScanDecision.Connect(ssid)
    }
}

/**
 * Ensures that on API 28, a generic Wi-Fi network callback is only accepted when
 * the active network is verified to be a TELLO-* network. Pre-existing non-TELLO
 * Wi-Fi networks (e.g. Home Wi-Fi) are ignored and do not cancel the scan/connect flow.
 */
class Api28NetworkAcceptanceGate<TNetwork>(
    private val getCurrentSsid: () -> String?,
    private val onTelloNetworkAccepted: (TNetwork) -> Unit,
    private val onTelloNetworkLost: (TNetwork) -> Unit,
) {
    var retainedNetwork: TNetwork? = null
        private set

    fun onNetworkAvailable(network: TNetwork): Boolean {
        val currentSsid = getCurrentSsid()
        if (Api28WifiScanPolicy.isTelloSsid(currentSsid)) {
            retainedNetwork = network
            onTelloNetworkAccepted(network)
            return true
        }
        return false
    }

    fun onNetworkLost(network: TNetwork): Boolean {
        if (retainedNetwork == network) {
            retainedNetwork = null
            onTelloNetworkLost(network)
            return true
        }
        return false
    }

    fun reset() {
        retainedNetwork = null
    }
}
