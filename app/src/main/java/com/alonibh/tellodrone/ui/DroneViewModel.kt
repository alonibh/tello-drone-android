package com.alonibh.tellodrone.ui

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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

class DroneViewModel(private val controller: DroneController) : ViewModel(), DroneDashboardActions {
    val uiState: StateFlow<DroneSessionState> = controller.state.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        controller.state.value,
    )
    override fun connect() = controller.connect()
    override fun disconnect() = controller.disconnect()
    override fun takeOff() = controller.takeOff()
    override fun land() = controller.land()
    override fun stopAndHover() = controller.stopAndHover()
    override fun emergencyMotorKill() = controller.emergencyMotorKill()
    override fun setTrackingMode(mode: TrackingMode) = controller.setTrackingMode(mode)
    override fun setDetectorModel(model: DetectorModel) =
        controller.setDetectorModel(model)
    override fun setDetectorBackendPreference(preference: DetectorBackendPreference) =
        controller.setDetectorBackendPreference(preference)
    override fun setDetectorConfidenceThreshold(threshold: Float) =
        controller.setDetectorConfidenceThreshold(threshold)
    override fun runDetectorBenchmark() = controller.runDetectorBenchmark()
    override fun cancelDetectorBenchmark() = controller.cancelDetectorBenchmark()
    override fun selectTarget(detection: PersonDetection) = controller.selectTarget(detection)
    fun setCurrentFollowDistance() = controller.setCurrentFollowDistance()
    fun setShadowAutonomyArmed(armed: Boolean) = controller.setShadowAutonomyArmed(armed)
    override fun setYawFollowArmed(armed: Boolean) = controller.setYawFollowArmed(armed)
    override fun setManualVector(vector: ManualControlVector) = controller.setManualControlVector(vector)
    override fun setSpeed(percent: Int) = controller.setSpeed(percent)
    override fun attachVideoSurface(surface: Surface) = controller.attachVideoSurface(surface)
    override fun detachVideoSurface(surface: Surface) = controller.detachVideoSurface(surface)
    fun onNetworkPermissionsResult(granted: Boolean) = controller.onNetworkPermissionsResult(granted)

    class Factory(private val controller: DroneController) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DroneViewModel(controller) as T
    }
}
