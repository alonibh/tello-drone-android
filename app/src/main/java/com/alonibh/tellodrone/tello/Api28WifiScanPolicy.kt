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
    fun evaluate(
        scanResults: List<DiscoveredWifiNetwork>,
        currentSsid: String? = null,
        isLocationEnabled: Boolean = true,
    ): Api28ScanDecision {
        val cleanCurrentSsid = currentSsid?.removeSurrounding("\"")
        if (cleanCurrentSsid != null &&
            cleanCurrentSsid.startsWith(TelloWifiNetworkManager.TELLO_SSID_PREFIX, ignoreCase = true) &&
            cleanCurrentSsid != "<unknown ssid>"
        ) {
            return Api28ScanDecision.AlreadyConnected(cleanCurrentSsid)
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
