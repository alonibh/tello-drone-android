package com.alonibh.tellodrone.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * UI-facing boundary. Implementations may be mock or service-backed, but Activities, Compose, and
 * ViewModels never own a physical session, UDP socket, network request, or RC loop.
 */
interface DroneController {
    val state: StateFlow<DroneSessionState>
    fun connect()
    fun disconnect()
    fun takeOff()
    fun land()
    fun stopAndHover()
    fun emergencyMotorKill()
    fun setTrackingMode(mode: TrackingMode)
    fun setTargetLock(locked: Boolean)
    fun setManualControlVector(vector: ManualControlVector)
    fun setSpeed(percent: Int)
    fun setControllerMode(mode: ControllerMode) = Unit
    fun onNetworkPermissionsResult(granted: Boolean) = Unit
}
