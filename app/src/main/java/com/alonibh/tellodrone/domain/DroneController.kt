package com.alonibh.tellodrone.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * UI-facing contract. A future connected-device service will own the real session and implement
 * this boundary; Compose and the ViewModel never own a socket or a flight loop.
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
}
