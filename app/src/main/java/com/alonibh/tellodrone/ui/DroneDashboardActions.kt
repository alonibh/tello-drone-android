package com.alonibh.tellodrone.ui

import android.view.Surface
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.PersonDetection
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
    fun selectTarget(detection: PersonDetection)
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
    override fun selectTarget(detection: PersonDetection) = Unit
    override fun setYawFollowArmed(armed: Boolean) = Unit
    override fun exportVisionTrace(destinationUri: String) = Unit
    override fun setManualVector(vector: ManualControlVector) = Unit
    override fun setSpeed(percent: Int) = Unit
    override fun attachVideoSurface(surface: Surface) = Unit
    override fun detachVideoSurface(surface: Surface) = Unit
}
