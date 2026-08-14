package com.alonibh.tellodrone.ui

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.alonibh.tellodrone.domain.ControllerMode
import com.alonibh.tellodrone.domain.DroneController
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.DetectorBackendPreference
import com.alonibh.tellodrone.domain.DetectorModel
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.TrackingMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class DroneViewModel(private val controller: DroneController) : ViewModel() {
    val uiState: StateFlow<DroneSessionState> = controller.state.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        controller.state.value,
    )
    fun connect() = controller.connect()
    fun disconnect() = controller.disconnect()
    fun takeOff() = controller.takeOff()
    fun land() = controller.land()
    fun stopAndHover() = controller.stopAndHover()
    fun emergencyMotorKill() = controller.emergencyMotorKill()
    fun setTrackingMode(mode: TrackingMode) = controller.setTrackingMode(mode)
    fun setDetectorModel(model: DetectorModel) =
        controller.setDetectorModel(model)
    fun setDetectorBackendPreference(preference: DetectorBackendPreference) =
        controller.setDetectorBackendPreference(preference)
    fun setDetectorConfidenceThreshold(threshold: Float) =
        controller.setDetectorConfidenceThreshold(threshold)
    fun runDetectorBenchmark() = controller.runDetectorBenchmark()
    fun cancelDetectorBenchmark() = controller.cancelDetectorBenchmark()
    fun selectTarget(detection: PersonDetection) = controller.selectTarget(detection)
    fun setCurrentFollowDistance() = controller.setCurrentFollowDistance()
    fun setShadowAutonomyArmed(armed: Boolean) = controller.setShadowAutonomyArmed(armed)
    fun setManualVector(vector: ManualControlVector) = controller.setManualControlVector(vector)
    fun setSpeed(percent: Int) = controller.setSpeed(percent)
    fun attachVideoSurface(surface: Surface) = controller.attachVideoSurface(surface)
    fun detachVideoSurface(surface: Surface) = controller.detachVideoSurface(surface)
    fun setControllerMode(mode: ControllerMode) = controller.setControllerMode(mode)
    fun onNetworkPermissionsResult(granted: Boolean) = controller.onNetworkPermissionsResult(granted)

    class Factory(private val controller: DroneController) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DroneViewModel(controller) as T
    }
}
