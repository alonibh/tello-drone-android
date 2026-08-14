@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.alonibh.tellodrone.data

import com.alonibh.tellodrone.domain.ControllerMode
import com.alonibh.tellodrone.domain.DetectorModel
import com.alonibh.tellodrone.domain.DroneController
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.TrackingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AppDroneControllerTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setCurrentFollowDistance forwards to selected underlying controller`() {
        val real = RecordingDroneController(DroneSessionState(controllerMode = ControllerMode.Real))
        val mock = RecordingDroneController(DroneSessionState(controllerMode = ControllerMode.Mock))
        val appController = AppDroneController(real, mock)

        // Real mode (default): forwards to real controller
        appController.setCurrentFollowDistance()
        assertEquals(1, real.setCurrentFollowDistanceCalls)
        assertEquals(0, mock.setCurrentFollowDistanceCalls)

        // Mock mode: forwards to mock controller
        appController.setControllerMode(ControllerMode.Mock)
        appController.setCurrentFollowDistance()
        assertEquals(1, real.setCurrentFollowDistanceCalls)
        assertEquals(1, mock.setCurrentFollowDistanceCalls)
    }

    @Test
    fun `setDetectorConfidenceThreshold forwards to selected underlying controller`() {
        val real = RecordingDroneController(DroneSessionState(controllerMode = ControllerMode.Real))
        val mock = RecordingDroneController(DroneSessionState(controllerMode = ControllerMode.Mock))
        val appController = AppDroneController(real, mock)

        // Real mode (default): forwards to real controller
        appController.setDetectorConfidenceThreshold(0.70f)
        assertEquals(0.70f, real.lastConfidenceThreshold)
        assertEquals(null, mock.lastConfidenceThreshold)

        // Mock mode: forwards to mock controller
        appController.setControllerMode(ControllerMode.Mock)
        appController.setDetectorConfidenceThreshold(0.85f)
        assertEquals(0.70f, real.lastConfidenceThreshold)
        assertEquals(0.85f, mock.lastConfidenceThreshold)
    }

    @Test
    fun `setDetectorModel forwards to selected underlying controller`() {
        val real = RecordingDroneController(DroneSessionState(controllerMode = ControllerMode.Real))
        val mock = RecordingDroneController(DroneSessionState(controllerMode = ControllerMode.Mock))
        val appController = AppDroneController(real, mock)

        // Real mode (default): forwards to real controller
        appController.setDetectorModel(DetectorModel.EfficientDetLite0)
        assertEquals(DetectorModel.EfficientDetLite0, real.lastModel)
        assertEquals(null, mock.lastModel)

        // Mock mode: forwards to mock controller
        appController.setControllerMode(ControllerMode.Mock)
        appController.setDetectorModel(DetectorModel.MobileNetV1)
        assertEquals(DetectorModel.EfficientDetLite0, real.lastModel)
        assertEquals(DetectorModel.MobileNetV1, mock.lastModel)
    }

    private class RecordingDroneController(
        initialState: DroneSessionState,
    ) : DroneController {
        private val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<DroneSessionState> = mutableState
        var setCurrentFollowDistanceCalls = 0
        var lastConfidenceThreshold: Float? = null
        var lastModel: DetectorModel? = null

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun takeOff() = Unit
        override fun land() = Unit
        override fun stopAndHover() = Unit
        override fun emergencyMotorKill() = Unit
        override fun setTrackingMode(mode: TrackingMode) = Unit
        override fun setDetectorModel(model: DetectorModel) {
            lastModel = model
        }
        override fun selectTarget(detection: PersonDetection) = Unit
        override fun setCurrentFollowDistance() {
            setCurrentFollowDistanceCalls++
        }
        override fun setDetectorConfidenceThreshold(threshold: Float) {
            lastConfidenceThreshold = threshold
        }
        override fun setManualControlVector(vector: ManualControlVector) = Unit
        override fun setSpeed(percent: Int) = Unit
    }
}
