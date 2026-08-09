package com.alonibh.tellodrone.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object TelloPermissionPolicy {
    fun requiredRuntimePermissions(): Array<String> = buildList {
        when {
            Build.VERSION.SDK_INT >= 33 -> add(Manifest.permission.NEARBY_WIFI_DEVICES)
            else -> {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }.toTypedArray()

    fun missingPermissions(context: Context): List<String> = requiredRuntimePermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
}
