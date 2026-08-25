package com.alonibh.tellodrone.tello

fun interface MonotonicClock {
    fun nowMillis(): Long
}

object SystemMonotonicClock : MonotonicClock {
    override fun nowMillis(): Long = android.os.SystemClock.elapsedRealtime()
}
// SPDX-License-Identifier: AGPL-3.0-only
