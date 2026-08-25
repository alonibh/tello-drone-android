package com.alonibh.tellodrone.ui

import com.alonibh.tellodrone.domain.ManualControlVector
import kotlin.math.sqrt

/** Normalized stick position: positive vertical means up/forward/ascend. */
internal data class JoystickVector(val horizontal: Float = 0f, val vertical: Float = 0f)

internal fun normalizedJoystickVector(horizontal: Float, vertical: Float, deadZone: Float = .12f): JoystickVector {
    val magnitude = sqrt(horizontal * horizontal + vertical * vertical)
    if (magnitude <= deadZone) return JoystickVector()
    val scale = if (magnitude > 1f) 1f / magnitude else 1f
    return JoystickVector(horizontal * scale, vertical * scale)
}

/** Mode-2 mapping: left stick is altitude/yaw; right stick is lateral/forward. */
internal fun manualVectorFromSticks(left: JoystickVector, right: JoystickVector) = ManualControlVector(
    lateral = right.horizontal,
    forward = right.vertical,
    vertical = left.vertical,
    yaw = left.horizontal,
)
// SPDX-License-Identifier: AGPL-3.0-only
