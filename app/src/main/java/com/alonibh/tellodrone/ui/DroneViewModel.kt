package com.alonibh.tellodrone.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alonibh.tellodrone.data.MockDroneController
import com.alonibh.tellodrone.domain.DroneController
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.TrackingMode
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

class DroneViewModel(private val controller: DroneController = MockDroneController()) : ViewModel() {
    val uiState: StateFlow<DroneSessionState> = controller.state.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), controller.state.value,
    )
    fun connect() = controller.connect()
    fun disconnect() = controller.disconnect()
    fun takeOff() = controller.takeOff()
    fun land() = controller.land()
    fun stopAndHover() = controller.stopAndHover()
    fun emergencyMotorKill() = controller.emergencyMotorKill()
    fun setTrackingMode(mode: TrackingMode) = controller.setTrackingMode(mode)
    fun setTargetLock(locked: Boolean) = controller.setTargetLock(locked)
    fun setManualVector(vector: ManualControlVector) = controller.setManualControlVector(vector)
    fun setSpeed(percent: Int) = controller.setSpeed(percent)
}
