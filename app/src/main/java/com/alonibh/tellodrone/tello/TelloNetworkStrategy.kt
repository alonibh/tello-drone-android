package com.alonibh.tellodrone.tello

/** Selects the connection mechanism without making an API-29 framework reference on API 28. */
enum class TelloNetworkStrategy {
    ExistingWifi,
    WifiNetworkSpecifier;

    companion object {
        fun forSdk(sdkInt: Int): TelloNetworkStrategy =
            if (sdkInt >= 29) WifiNetworkSpecifier else ExistingWifi
    }
}
