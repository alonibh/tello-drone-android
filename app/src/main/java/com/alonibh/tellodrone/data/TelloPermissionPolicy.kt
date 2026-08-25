package com.alonibh.tellodrone.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object TelloPermissionPolicy {
    fun requiredRuntimePermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> = buildList {
        when {
            sdkInt >= 33 -> add(Manifest.permission.NEARBY_WIFI_DEVICES)
            sdkInt >= 29 -> {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            sdkInt == 28 -> add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (sdkInt >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }.toTypedArray()

    fun missingPermissions(context: Context): List<String> = requiredRuntimePermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
