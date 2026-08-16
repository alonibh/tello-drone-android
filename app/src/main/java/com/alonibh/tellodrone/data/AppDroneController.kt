package com.alonibh.tellodrone.data

import android.view.Surface
import com.alonibh.tellodrone.domain.ControllerMode
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneController
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.DetectorBackendPreference
import com.alonibh.tellodrone.domain.DetectorModel
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.SimulatorScenarioAction
import com.alonibh.tellodrone.domain.TrackingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class AppDroneController(
    private val real: DroneController,
    private val mock: DroneController,
) : DroneController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(real.state.value.copy(controllerMode = ControllerMode.Real))
    override val state: StateFlow<DroneSessionState> = mutableState
    private var mode = ControllerMode.Real

    init {
        scope.launch { real.state.collect { if (mode == ControllerMode.Real) mutableState.value = it.copy(controllerMode = mode) } }
        scope.launch { mock.state.collect { if (mode == ControllerMode.Mock) mutableState.value = it.copy(controllerMode = mode) } }
    }

    override fun setControllerMode(mode: ControllerMode) {
        if (mode == this.mode) return
        if (mutableState.value.connection !in setOf(DroneConnectionState.Disconnected, DroneConnectionState.Error)) {
            mutableState.value = mutableState.value.copy(lastMessage = "Disconnect before changing controller mode")
            return
        }
        this.mode = mode
        mutableState.value = selected().state.value.copy(controllerMode = mode)
    }

    override fun applySimulatorScenario(action: SimulatorScenarioAction) {
        if (mode == ControllerMode.Mock) mock.applySimulatorScenario(action)
    }

    override fun connect() = selected().connect()
    override fun disconnect() = selected().disconnect()
    override fun takeOff() = selected().takeOff()
    override fun land() = selected().land()
    override fun stopAndHover() = selected().stopAndHover()
    override fun emergencyMotorKill() = selected().emergencyMotorKill()
    override fun setTrackingMode(mode: TrackingMode) = selected().setTrackingMode(mode)
    override fun setDetectorModel(model: DetectorModel) =
        selected().setDetectorModel(model)
    override fun setDetectorBackendPreference(preference: DetectorBackendPreference) =
        selected().setDetectorBackendPreference(preference)
    override fun setDetectorConfidenceThreshold(threshold: Float) =
        selected().setDetectorConfidenceThreshold(threshold)
    override fun runDetectorBenchmark() = selected().runDetectorBenchmark()
    override fun cancelDetectorBenchmark() = selected().cancelDetectorBenchmark()
    override fun selectTarget(detection: PersonDetection) = selected().selectTarget(detection)
    override fun setCurrentFollowDistance() = selected().setCurrentFollowDistance()
    override fun setShadowAutonomyArmed(armed: Boolean) = selected().setShadowAutonomyArmed(armed)
    override fun setYawFollowArmed(armed: Boolean) = selected().setYawFollowArmed(armed)
    override fun setManualControlVector(vector: ManualControlVector) = selected().setManualControlVector(vector)
    override fun setSpeed(percent: Int) = selected().setSpeed(percent)
    // The real adapter retains the display hand-off even when simulator mode is selected. The
    // simulator never consumes it, and the surface is ready for a later Real connection.
    override fun attachVideoSurface(surface: Surface) = real.attachVideoSurface(surface)
    override fun detachVideoSurface(surface: Surface) = real.detachVideoSurface(surface)
    override fun onNetworkPermissionsResult(granted: Boolean) = selected().onNetworkPermissionsResult(granted)

    private fun selected() = if (mode == ControllerMode.Real) real else mock
}
