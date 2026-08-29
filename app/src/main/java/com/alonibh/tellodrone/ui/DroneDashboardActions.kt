package com.alonibh.tellodrone.ui

import android.view.Surface
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.TargetSelectionPoint
import com.alonibh.tellodrone.domain.TrackingMode

/** Narrow UI action sink; it never represents a drone connection, video stream, or flight state. */
interface DroneDashboardActions {
    fun connect()
    fun disconnect()
    fun takeOff()
    fun land()
    fun stopAndHover()
    fun emergencyMotorKill()
    fun setTrackingMode(mode: TrackingMode)
    fun selectTargetAt(normalizedX: Float, normalizedY: Float, displayedFrameSequence: Long? = null)
    fun selectTargetAt(point: TargetSelectionPoint, displayedFrameSequence: Long? = null) =
        selectTargetAt(point.normalizedX, point.normalizedY, displayedFrameSequence)
    fun setYawFollowArmed(armed: Boolean)
    fun exportVisionTrace(destinationUri: String)
    fun setManualVector(vector: ManualControlVector)
    fun setSpeed(percent: Int)
    fun attachVideoSurface(surface: Surface)
    fun detachVideoSurface(surface: Surface)
}

object NoOpDroneDashboardActions : DroneDashboardActions {
    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun takeOff() = Unit
    override fun land() = Unit
    override fun stopAndHover() = Unit
    override fun emergencyMotorKill() = Unit
    override fun setTrackingMode(mode: TrackingMode) = Unit
    override fun selectTargetAt(normalizedX: Float, normalizedY: Float, displayedFrameSequence: Long?) = Unit
    override fun setYawFollowArmed(armed: Boolean) = Unit
    override fun exportVisionTrace(destinationUri: String) = Unit
    override fun setManualVector(vector: ManualControlVector) = Unit
    override fun setSpeed(percent: Int) = Unit
    override fun attachVideoSurface(surface: Surface) = Unit
    override fun detachVideoSurface(surface: Surface) = Unit
}
// SPDX-License-Identifier: AGPL-3.0-only
