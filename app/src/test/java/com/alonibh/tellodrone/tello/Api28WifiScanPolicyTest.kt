package com.alonibh.tellodrone.tello

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Api28WifiScanPolicyTest {

    @Test
    fun home_wifi_callback_first_is_ignored_then_tello_connection_later_is_accepted() {
        var currentSsid: String? = "Home-WiFi"
        var acceptedNetwork: String? = null
        var lostNetwork: String? = null

        val gate = Api28NetworkAcceptanceGate<String>(
            getCurrentSsid = { currentSsid },
            onTelloNetworkAccepted = { acceptedNetwork = it },
            onTelloNetworkLost = { lostNetwork = it },
        )

        // 1. Initial onAvailable callback for Home Wi-Fi must be ignored
        val homeAccepted = gate.onNetworkAvailable("network-home-wifi")
        assertFalse("Home Wi-Fi must not be accepted", homeAccepted)
        assertNull("No network should be accepted yet", acceptedNetwork)
        assertNull("Gate should not retain Home Wi-Fi", gate.retainedNetwork)

        // 2. Home Wi-Fi lost while switching networks must not trigger Tello lost callback
        val homeLost = gate.onNetworkLost("network-home-wifi")
        assertFalse("Home Wi-Fi lost must not trigger Tello lost callback", homeLost)
        assertNull(lostNetwork)

        // 3. Device associates and connects to Tello Wi-Fi
        currentSsid = "TELLO-998877"
        val telloAccepted = gate.onNetworkAvailable("network-tello")
        assertTrue("TELLO network must be accepted", telloAccepted)
        assertEquals("network-tello", acceptedNetwork)
        assertEquals("network-tello", gate.retainedNetwork)

        // 4. TELLO network lost triggers lost callback
        val telloLost = gate.onNetworkLost("network-tello")
        assertTrue("TELLO network lost must trigger lost callback", telloLost)
        assertEquals("network-tello", lostNetwork)
        assertNull(gate.retainedNetwork)
    }

    @Test
    fun is_tello_ssid_validation() {
        assertTrue(Api28WifiScanPolicy.isTelloSsid("TELLO-123456"))
        assertTrue(Api28WifiScanPolicy.isTelloSsid("\"TELLO-123456\""))
        assertFalse(Api28WifiScanPolicy.isTelloSsid("Home-WiFi"))
        assertFalse(Api28WifiScanPolicy.isTelloSsid("\"Home-WiFi\""))
        assertFalse(Api28WifiScanPolicy.isTelloSsid("<unknown ssid>"))
        assertFalse(Api28WifiScanPolicy.isTelloSsid(null))
        assertFalse(Api28WifiScanPolicy.isTelloSsid(""))
    }

    @Test
    fun already_connected_to_tello_reuses_unquoted_network() {
        val decision = Api28WifiScanPolicy.evaluate(
            scanResults = emptyList(),
            currentSsid = "TELLO-98A1B2",
            isLocationEnabled = true,
        )
        assertEquals(Api28ScanDecision.AlreadyConnected("TELLO-98A1B2"), decision)
    }

    @Test
    fun already_connected_to_tello_reuses_quoted_network() {
        val decision = Api28WifiScanPolicy.evaluate(
            scanResults = emptyList(),
            currentSsid = "\"TELLO-FE31A2\"",
            isLocationEnabled = true,
        )
        assertEquals(Api28ScanDecision.AlreadyConnected("TELLO-FE31A2"), decision)
    }

    @Test
    fun already_connected_ignores_unknown_or_non_tello_ssid() {
        val decisionUnknown = Api28WifiScanPolicy.evaluate(
            scanResults = listOf(DiscoveredWifiNetwork("TELLO-112233", "[ESS]")),
            currentSsid = "<unknown ssid>",
            isLocationEnabled = true,
        )
        assertEquals(Api28ScanDecision.Connect("TELLO-112233"), decisionUnknown)

        val decisionOther = Api28WifiScanPolicy.evaluate(
            scanResults = listOf(DiscoveredWifiNetwork("TELLO-112233", "[ESS]")),
            currentSsid = "\"Home-Network\"",
            isLocationEnabled = true,
        )
        assertEquals(Api28ScanDecision.Connect("TELLO-112233"), decisionOther)
    }

    @Test
    fun location_services_disabled_reports_location_required() {
        val decision = Api28WifiScanPolicy.evaluate(
            scanResults = listOf(DiscoveredWifiNetwork("TELLO-112233", "[ESS]")),
            currentSsid = null,
            isLocationEnabled = false,
        )
        assertTrue(decision is Api28ScanDecision.LocationDisabled)
        val locationDisabled = decision as Api28ScanDecision.LocationDisabled
        assertTrue(locationDisabled.message.contains("Location services must be enabled", ignoreCase = true))
    }

    @Test
    fun empty_scan_results_reports_not_found() {
        val decision = Api28WifiScanPolicy.evaluate(
            scanResults = emptyList(),
            currentSsid = null,
            isLocationEnabled = true,
        )
        assertTrue(decision is Api28ScanDecision.NotFound)
    }

    @Test
    fun non_tello_networks_only_reports_not_found() {
        val decision = Api28WifiScanPolicy.evaluate(
            scanResults = listOf(
                DiscoveredWifiNetwork("Home-WiFi", "[WPA2-PSK-CCMP][ESS]"),
                DiscoveredWifiNetwork("Guest-Access", "[ESS]"),
                DiscoveredWifiNetwork("Drone-Other", "[ESS]"),
            ),
            currentSsid = null,
            isLocationEnabled = true,
        )
        assertTrue(decision is Api28ScanDecision.NotFound)
    }

    @Test
    fun single_visible_open_tello_network_connects() {
        val decision = Api28WifiScanPolicy.evaluate(
            scanResults = listOf(
                DiscoveredWifiNetwork("TELLO-60A2B1", "[ESS]"),
                DiscoveredWifiNetwork("Home-WiFi", "[WPA2-PSK-CCMP][ESS]"),
            ),
            currentSsid = null,
            isLocationEnabled = true,
        )
        assertEquals(Api28ScanDecision.Connect("TELLO-60A2B1"), decision)
    }

    @Test
    fun single_tello_network_with_multiple_bssids_deduplicates_and_connects() {
        val decision = Api28WifiScanPolicy.evaluate(
            scanResults = listOf(
                DiscoveredWifiNetwork("TELLO-60A2B1", "[ESS]", bssid = "00:11:22:33:44:55", level = -60),
                DiscoveredWifiNetwork("TELLO-60A2B1", "[ESS]", bssid = "00:11:22:33:44:66", level = -70),
            ),
            currentSsid = null,
            isLocationEnabled = true,
        )
        assertEquals(Api28ScanDecision.Connect("TELLO-60A2B1"), decision)
    }

    @Test
    fun multiple_distinct_tello_networks_fails_without_guessing() {
        val decision = Api28WifiScanPolicy.evaluate(
            scanResults = listOf(
                DiscoveredWifiNetwork("TELLO-111111", "[ESS]"),
                DiscoveredWifiNetwork("TELLO-222222", "[ESS]"),
            ),
            currentSsid = null,
            isLocationEnabled = true,
        )
        assertTrue(decision is Api28ScanDecision.MultipleNetworks)
        val multiple = decision as Api28ScanDecision.MultipleNetworks
        assertEquals(listOf("TELLO-111111", "TELLO-222222"), multiple.ssids)
        assertTrue(multiple.message.contains("TELLO-111111"))
        assertTrue(multiple.message.contains("TELLO-222222"))
    }

    @Test
    fun secured_tello_wpa2_fails_with_credentials_required_message() {
        val decision = Api28WifiScanPolicy.evaluate(
            scanResults = listOf(
                DiscoveredWifiNetwork("TELLO-SECURE", "[WPA2-PSK-CCMP][ESS]"),
            ),
            currentSsid = null,
            isLocationEnabled = true,
        )
        assertTrue(decision is Api28ScanDecision.SecuredNetwork)
        val secured = decision as Api28ScanDecision.SecuredNetwork
        assertEquals("TELLO-SECURE", secured.ssid)
        assertTrue(secured.message.contains("secured", ignoreCase = true))
        assertTrue(secured.message.contains("manual", ignoreCase = true) || secured.message.contains("credentials", ignoreCase = true))
    }

    @Test
    fun secured_tello_wep_fails_with_credentials_required_message() {
        val decision = Api28WifiScanPolicy.evaluate(
            scanResults = listOf(
                DiscoveredWifiNetwork("TELLO-OLDWEP", "[WEP][ESS]"),
            ),
            currentSsid = null,
            isLocationEnabled = true,
        )
        assertTrue(decision is Api28ScanDecision.SecuredNetwork)
        val secured = decision as Api28ScanDecision.SecuredNetwork
        assertEquals("TELLO-OLDWEP", secured.ssid)
        assertTrue(secured.message.contains("secured", ignoreCase = true))
    }

    @Test
    fun single_tello_with_mixed_open_and_secured_entries_is_treated_as_secured() {
        val decision = Api28WifiScanPolicy.evaluate(
            scanResults = listOf(
                DiscoveredWifiNetwork("TELLO-MIXED", "[ESS]"),
                DiscoveredWifiNetwork("TELLO-MIXED", "[WPA-PSK-CCMP][ESS]"),
            ),
            currentSsid = null,
            isLocationEnabled = true,
        )
        assertTrue(decision is Api28ScanDecision.SecuredNetwork)
        val secured = decision as Api28ScanDecision.SecuredNetwork
        assertEquals("TELLO-MIXED", secured.ssid)
    }
}
