package com.alonibh.tellodrone.domain

import android.view.Surface
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
    fun setDetectorBackendPreference(preference: DetectorBackendPreference) = Unit
    fun runDetectorBenchmark() = Unit
    fun cancelDetectorBenchmark() = Unit
    /** Explicit observational selection only; implementations must never infer a target. */
    fun selectTarget(detection: PersonDetection)
    fun setCurrentFollowDistance()
    fun setShadowAutonomyArmed(armed: Boolean) = Unit
    fun setManualControlVector(vector: ManualControlVector)
    fun setSpeed(percent: Int)
    /** The UI owns only this display surface; the service owns the physical video session. */
    fun attachVideoSurface(surface: Surface) = Unit
    fun detachVideoSurface(surface: Surface) = Unit
    fun setControllerMode(mode: ControllerMode) = Unit
    fun onNetworkPermissionsResult(granted: Boolean) = Unit
}
