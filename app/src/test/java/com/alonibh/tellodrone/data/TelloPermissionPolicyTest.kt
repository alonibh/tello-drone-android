package com.alonibh.tellodrone.data

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TelloPermissionPolicyTest {
    @Test fun api_28_requests_only_required_coarse_location_permission() {
        assertArrayEquals(
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
            TelloPermissionPolicy.requiredRuntimePermissions(28),
        )
    }

    @Test fun api_29_to_32_keep_specifier_location_permissions() {
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
// SPDX-License-Identifier: AGPL-3.0-only
