package com.alonibh.tellodrone.data

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TelloPermissionPolicyTest {
    @Test fun api_28_to_32_require_location_permissions_for_wifi_scanning() {
        assertArrayEquals(
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            TelloPermissionPolicy.requiredRuntimePermissions(28),
        )
        assertArrayEquals(
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            TelloPermissionPolicy.requiredRuntimePermissions(29),
        )
        assertArrayEquals(
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            TelloPermissionPolicy.requiredRuntimePermissions(32),
        )
    }

    @Test fun api_33_and_api_37_use_modern_permissions() {
        assertArrayEquals(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), TelloPermissionPolicy.requiredRuntimePermissions(33))
        assertArrayEquals(
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_LOCAL_NETWORK),
            TelloPermissionPolicy.requiredRuntimePermissions(37),
        )
    }
}
