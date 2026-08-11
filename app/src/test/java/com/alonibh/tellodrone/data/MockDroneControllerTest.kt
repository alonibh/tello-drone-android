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

    @Test fun `target lock and follow remain unavailable`() {
        val controller = connectedController()
        controller.setTrackingMode(TrackingMode.DetectOnly)
        controller.setTargetLock(true)
        controller.setTrackingMode(TrackingMode.Follow)
        assertEquals(ControlAuthority.Manual, controller.state.value.authority)
        assertEquals(TrackingMode.DetectOnly, controller.state.value.tracking)
        assertNull(controller.state.value.target)
    }

    @Test fun `manual input remains authoritative without disabling detection`() {
        val controller = detectingFlyingController()
        controller.setManualControlVector(ManualControlVector(forward = 1f))
        assertEquals(TrackingMode.DetectOnly, controller.state.value.tracking)
        assertEquals(ControlAuthority.Manual, controller.state.value.authority)
    }

    @Test fun `disconnect clears unsafe states`() {
        val controller = detectingFlyingController()
        controller.disconnect()
        assertEquals(DroneConnectionState.Disconnected, controller.state.value.connection)
        assertEquals(FlightState.Grounded, controller.state.value.flight)
        assertEquals(TrackingMode.Off, controller.state.value.tracking)
    }

    private fun connectedController() = MockDroneController(DroneSessionState(connection = DroneConnectionState.Connected))
    private fun detectingFlyingController() = connectedController().also { it.takeOff(); it.setTrackingMode(TrackingMode.DetectOnly) }
}
