package com.alonibh.tellodrone.data

import com.alonibh.tellodrone.domain.ControlAuthority
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.TrackingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MockDroneControllerTest {
    @Test fun `cannot take off while disconnected`() {
        val controller = MockDroneController()
        controller.takeOff()
        assertEquals(FlightState.Grounded, controller.state.value.flight)
    }

    @Test fun `takeoff moves mock state to flying and land returns grounded`() {
        val controller = connectedController()
        controller.takeOff(); assertEquals(FlightState.Flying, controller.state.value.flight)
        controller.land(); assertEquals(FlightState.Grounded, controller.state.value.flight)
    }

    @Test fun `stop hover preserves observational detection and manual authority`() {
        val controller = detectingFlyingController()
        controller.stopAndHover()
        assertEquals(FlightState.Flying, controller.state.value.flight)
        assertEquals(ControlAuthority.Manual, controller.state.value.authority)
        assertEquals(TrackingMode.DetectOnly, controller.state.value.tracking)
        assertEquals(0f, controller.state.value.telemetry.speedMetersPerSecond)
    }

    @Test fun `emergency motor kill clears autonomous authority`() {
        val controller = detectingFlyingController()
        controller.emergencyMotorKill()
        assertEquals(FlightState.Emergency, controller.state.value.flight)
        assertEquals(ControlAuthority.Manual, controller.state.value.authority)
        assertEquals(TrackingMode.Off, controller.state.value.tracking)
    }

    @Test fun `explicit mock target selection remains dry run and follow remains unavailable`() {
        val controller = connectedController()
        controller.setTrackingMode(TrackingMode.DetectOnly)
        controller.selectTarget(controller.state.value.personDetections.first())
        controller.setTrackingMode(TrackingMode.Follow)
        assertEquals(ControlAuthority.Manual, controller.state.value.authority)
        assertEquals(TrackingMode.TargetLocked, controller.state.value.tracking)
        org.junit.Assert.assertNotNull(controller.state.value.target)
    }

    @Test fun `manual input remains authoritative without disabling detection`() {
        val controller = detectingFlyingController()
        controller.setManualControlVector(ManualControlVector(forward = 1f))
        assertEquals(TrackingMode.DetectOnly, controller.state.value.tracking)
        assertEquals(ControlAuthority.Manual, controller.state.value.authority)
    }

    @Test fun `target selection does not issue movement or autonomous authority`() {
        val controller = detectingFlyingController()
        val manual = ManualControlVector(forward = .5f, yaw = -.2f)
        controller.setManualControlVector(manual)
        controller.selectTarget(controller.state.value.personDetections.last())

        assertEquals(ControlAuthority.Manual, controller.state.value.authority)
        assertEquals(manual, controller.state.value.manualVector)
        assertEquals(TrackingMode.TargetLocked, controller.state.value.tracking)
    }

    @Test fun `disconnect clears unsafe states`() {
        val controller = detectingFlyingController()
        controller.disconnect()
        assertEquals(DroneConnectionState.Disconnected, controller.state.value.connection)
        assertEquals(FlightState.Grounded, controller.state.value.flight)
        assertEquals(TrackingMode.Off, controller.state.value.tracking)
    }

    @Test fun `confidence threshold update is applied when tracking is off`() {
        val controller = connectedController()
        controller.setDetectorConfidenceThreshold(0.70f)
        assertEquals(0.70f, controller.state.value.video.detectorConfidenceThreshold)
    }

    @Test fun `confidence threshold update is rejected when tracking is active`() {
        val controller = detectingFlyingController()
        controller.setDetectorConfidenceThreshold(0.70f)
        assertEquals(0.50f, controller.state.value.video.detectorConfidenceThreshold)
        assertEquals("Turn person detection off before changing confidence threshold", controller.state.value.lastMessage)
    }

    @Test fun `mock person detections are filtered by configured confidence threshold`() {
        val controller = connectedController()
        controller.setDetectorConfidenceThreshold(0.90f)
        controller.setTrackingMode(TrackingMode.DetectOnly)
        assertEquals(1, controller.state.value.personDetections.size)
        assertEquals(0.92f, controller.state.value.personDetections.single().confidence)
    }

    private fun connectedController() = MockDroneController(DroneSessionState(connection = DroneConnectionState.Connected))
    private fun detectingFlyingController() = connectedController().also { it.takeOff(); it.setTrackingMode(TrackingMode.DetectOnly) }
}
