package com.alonibh.tellodrone.data

import android.content.Context
import android.content.Intent
import android.view.Surface
import androidx.core.content.ContextCompat
import com.alonibh.tellodrone.domain.ControlAuthority
import com.alonibh.tellodrone.domain.ControllerMode
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneController
import com.alonibh.tellodrone.domain.DetectorBackendPreference
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.NetworkSelectionState
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.TrackingMode
import com.alonibh.tellodrone.service.TelloDroneService
import com.alonibh.tellodrone.service.TelloServiceGateway
import kotlinx.coroutines.flow.StateFlow

/** Thin UI adapter; the foreground service owns every physical-session resource. */
class RealDroneController(context: Context) : DroneController {
    private val applicationContext = context.applicationContext
    override val state: StateFlow<com.alonibh.tellodrone.domain.DroneSessionState> = TelloSessionStore.state

    override fun connect() {
        val missing = TelloPermissionPolicy.missingPermissions(applicationContext)
        if (missing.isNotEmpty()) {
            TelloSessionStore.update {
                it.copy(
                    controllerMode = ControllerMode.Real,
                    connection = DroneConnectionState.AwaitingPermission,
                    networkSelection = NetworkSelectionState.PermissionRequired,
                    lastMessage = "Nearby Wi-Fi and local-network access are required only to connect to Tello",
                )
            }
            return
        }
        val intent = Intent(applicationContext, TelloDroneService::class.java).setAction(TelloDroneService.ACTION_CONNECT)
        ContextCompat.startForegroundService(applicationContext, intent)
    }

    override fun onNetworkPermissionsResult(granted: Boolean) {
        if (granted && TelloPermissionPolicy.missingPermissions(applicationContext).isEmpty()) connect()
        else TelloSessionStore.update {
            it.copy(
                connection = DroneConnectionState.Error,
                networkSelection = NetworkSelectionState.PermissionDenied,
                tracking = TrackingMode.Off,
                authority = ControlAuthority.Manual,
                personDetections = emptyList(),
                target = null,
                hoverActive = false,
                lastMessage = "Tello network permission denied; no drone connection was attempted",
            )
        }
    }

    override fun disconnect() {
        if (TelloServiceGateway.isAvailable()) TelloServiceGateway.disconnect()
        else TelloSessionStore.update {
            it.copy(
                connection = DroneConnectionState.Disconnected,
                networkSelection = NetworkSelectionState.Idle,
                telemetry = it.telemetry.copy(isFresh = false),
                video = com.alonibh.tellodrone.domain.VideoState(),
                tracking = TrackingMode.Off,
                authority = ControlAuthority.Manual,
                personDetections = emptyList(),
                target = null,
                manualVector = ManualControlVector(),
                hoverActive = false,
                lastMessage = "Tello session cleared",
            )
        }
    }

    override fun takeOff() { TelloServiceGateway.takeOff() }
    override fun land() { TelloServiceGateway.land() }
    override fun stopAndHover() { TelloServiceGateway.stopAndHover() }
    override fun emergencyMotorKill() { TelloServiceGateway.emergencyMotorKill() }
    override fun setManualControlVector(vector: ManualControlVector) { TelloServiceGateway.publishManualControl(vector) }
    override fun setSpeed(percent: Int) { TelloServiceGateway.setSpeed(percent.coerceIn(10, 40)) }
    override fun attachVideoSurface(surface: Surface) { TelloServiceGateway.attachVideoSurface(surface) }
    override fun detachVideoSurface(surface: Surface) { TelloServiceGateway.detachVideoSurface(surface) }

    override fun setTrackingMode(mode: TrackingMode) {
        if (mode in setOf(TrackingMode.Off, TrackingMode.DetectOnly)) {
            TelloServiceGateway.setTrackingMode(mode)
        } else outOfScope()
    }
    override fun setDetectorBackendPreference(preference: DetectorBackendPreference) {
        TelloServiceGateway.setDetectorBackendPreference(preference)
    }
    override fun runDetectorBenchmark() { TelloServiceGateway.runDetectorBenchmark() }
    override fun cancelDetectorBenchmark() { TelloServiceGateway.cancelDetectorBenchmark() }
    override fun selectTarget(detection: PersonDetection) = outOfScope()

    private fun outOfScope() = TelloSessionStore.update {
        it.copy(
            authority = ControlAuthority.Manual,
            target = null,
            lastMessage = "Target lock and autonomous control are not available in Phase 4A",
        )
    }
}
