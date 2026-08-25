package com.alonibh.tellodrone.tello

/** Selects the connection mechanism without making an API-29 framework reference on API 28. */
enum class TelloNetworkStrategy {
    Api28ScanAndConnect,
    WifiNetworkSpecifier;

    companion object {
        fun forSdk(sdkInt: Int): TelloNetworkStrategy =
            if (sdkInt >= 29) WifiNetworkSpecifier else Api28ScanAndConnect
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
