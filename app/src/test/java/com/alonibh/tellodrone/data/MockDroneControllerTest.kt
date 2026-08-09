package com.alonibh.tellodrone.data

import com.alonibh.tellodrone.domain.ControlAuthority
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.TrackingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun `stop hover cancels autonomous movement without motor kill`() {
        val controller = flyingLockedController()
        controller.setTrackingMode(TrackingMode.Follow)
        controller.stopAndHover()
        assertEquals(FlightState.Flying, controller.state.value.flight)
        assertEquals(ControlAuthority.Manual, controller.state.value.authority)
        assertEquals(TrackingMode.TargetLocked, controller.state.value.tracking)
        assertEquals(0f, controller.state.value.telemetry.speedMetersPerSecond)
    }

    @Test fun `emergency motor kill clears autonomous authority`() {
        val controller = flyingLockedController()
        controller.setTrackingMode(TrackingMode.Follow); controller.emergencyMotorKill()
        assertEquals(FlightState.Emergency, controller.state.value.flight)
        assertEquals(ControlAuthority.Manual, controller.state.value.authority)
        assertEquals(TrackingMode.Off, controller.state.value.tracking)
    }

    @Test fun `follow requires flying and locked target`() {
        val controller = connectedController()
        controller.setTrackingMode(TrackingMode.DetectOnly); controller.setTrackingMode(TrackingMode.Follow)
        assertFalse(controller.state.value.authority == ControlAuthority.Autonomous)
        controller.takeOff(); controller.setTargetLock(true); controller.setTrackingMode(TrackingMode.Follow)
        assertEquals(ControlAuthority.Autonomous, controller.state.value.authority)
    }

    @Test fun `manual input cancels follow and restores manual authority`() {
        val controller = flyingLockedController()
        controller.setTrackingMode(TrackingMode.Follow); controller.setManualControlVector(ManualControlVector(forward = 1f))
        assertEquals(TrackingMode.TargetLocked, controller.state.value.tracking)
        assertEquals(ControlAuthority.Manual, controller.state.value.authority)
    }

    @Test fun `disconnect clears unsafe states`() {
        val controller = flyingLockedController()
        controller.setTrackingMode(TrackingMode.Follow); controller.disconnect()
        assertEquals(DroneConnectionState.Disconnected, controller.state.value.connection)
        assertEquals(FlightState.Grounded, controller.state.value.flight)
        assertEquals(TrackingMode.Off, controller.state.value.tracking)
    }

    private fun connectedController() = MockDroneController(DroneSessionState(connection = DroneConnectionState.Connected))
    private fun flyingLockedController() = connectedController().also { it.takeOff(); it.setTrackingMode(TrackingMode.DetectOnly); it.setTargetLock(true) }
}
