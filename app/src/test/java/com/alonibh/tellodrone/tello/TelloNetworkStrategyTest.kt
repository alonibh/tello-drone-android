package com.alonibh.tellodrone.tello

import org.junit.Assert.assertEquals
import org.junit.Test

class TelloNetworkStrategyTest {
    @Test fun api_28_uses_existing_wifi_manual_selection() {
        assertEquals(TelloNetworkStrategy.ExistingWifi, TelloNetworkStrategy.forSdk(28))
    }

    @Test fun api_29_and_later_use_wifi_network_specifier() {
        assertEquals(TelloNetworkStrategy.WifiNetworkSpecifier, TelloNetworkStrategy.forSdk(29))
        assertEquals(TelloNetworkStrategy.WifiNetworkSpecifier, TelloNetworkStrategy.forSdk(37))
    }
}
