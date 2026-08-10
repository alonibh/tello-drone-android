package com.alonibh.tellodrone.data

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TelloPermissionPolicyTest {
    @Test fun api_28_manual_wifi_fallback_requires_no_runtime_wifi_or_location_permission() {
        assertArrayEquals(emptyArray(), TelloPermissionPolicy.requiredRuntimePermissions(28))
    }

    @Test fun api_29_to_32_keep_specifier_location_permissions() {
        assertArrayEquals(
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            TelloPermissionPolicy.requiredRuntimePermissions(29),
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
