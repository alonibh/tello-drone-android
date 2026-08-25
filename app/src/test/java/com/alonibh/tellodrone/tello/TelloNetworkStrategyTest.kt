package com.alonibh.tellodrone.tello

import org.junit.Assert.assertEquals
import org.junit.Test

class TelloNetworkStrategyTest {
    @Test fun api_28_uses_api28_scan_and_connect() {
        assertEquals(TelloNetworkStrategy.Api28ScanAndConnect, TelloNetworkStrategy.forSdk(28))
    }

    @Test fun api_29_and_later_use_wifi_network_specifier() {
        assertEquals(TelloNetworkStrategy.WifiNetworkSpecifier, TelloNetworkStrategy.forSdk(29))
        assertEquals(TelloNetworkStrategy.WifiNetworkSpecifier, TelloNetworkStrategy.forSdk(37))
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
