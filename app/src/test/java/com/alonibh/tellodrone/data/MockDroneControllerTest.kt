@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.alonibh.tellodrone.data

import com.alonibh.tellodrone.domain.ControlAuthority
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.SimulatorScenarioAction
import com.alonibh.tellodrone.domain.TrackingMode
import com.alonibh.tellodrone.domain.YawFollowState
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockDroneControllerTest {
    @Test fun `start creates connected grounded simulator and stop cleans it up`() = runTest {
        val controller = controller()
        controller.connect()
        runCurrent()
        assertEquals(DroneConnectionState.Connected, controller.state.value.connection)
        assertEquals(FlightState.Grounded, controller.state.value.flight)
        assertTrue(controller.state.value.telemetry.isFresh)

        controller.disconnect()
        runCurrent()
        assertEquals(DroneConnectionState.Disconnected, controller.state.value.connection)
        assertEquals(FlightState.Grounded, controller.state.value.flight)
        assertEquals(TrackingMode.Off, controller.state.value.tracking)
    }

    @Test fun `takeoff and landing use acknowledgement then simulated telemetry`() = runTest {
        val controller = connectedController()
        controller.takeOff()
        runCurrent()
        assertEquals(FlightState.TakingOff, controller.state.value.flight)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(FlightState.Flying, controller.state.value.flight)

        controller.land()
        runCurrent()
        assertEquals(FlightState.Landing, controller.state.value.flight)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(FlightState.Grounded, controller.state.value.flight)
    }

    @Test fun `synthetic selection and production yaw follow use final transport diagnostics`() = runTest {
        val controller = flyingController()
        controller.setTrackingMode(TrackingMode.DetectOnly)
        advanceTimeBy(100L)
        runCurrent()
        val detection = controller.state.value.personDetections.single()
        controller.selectTarget(detection)
        advanceTimeBy(100L)
        runCurrent()
        controller.setYawFollowArmed(true)
        controller.applySimulatorScenario(SimulatorScenarioAction.MovePersonRight)
        advanceTimeBy(700L)
        runCurrent()

        assertEquals(YawFollowState.ACTIVE, controller.state.value.yawFollowDecision.state)
        assertTrue(requireNotNull(controller.state.value.simulatorDiagnostics).yawRc > 0)
        assertEquals(ControlAuthority.Autonomous, controller.state.value.authority)
    }

    @Test fun `stop hover selects exact zero and requires rearm`() = runTest {
        val controller = flyingController()
        controller.setManualControlVector(ManualControlVector())
        controller.setManualControlVector(ManualControlVector(forward = 1f))
        advanceTimeBy(50L)
        runCurrent()
        assertTrue(requireNotNull(controller.state.value.simulatorDiagnostics).forwardRc > 0)

        controller.stopAndHover()
        runCurrent()
        val diagnostics = requireNotNull(controller.state.value.simulatorDiagnostics)
        assertEquals(0, diagnostics.lateralRc)
        assertEquals(0, diagnostics.forwardRc)
        assertEquals(0, diagnostics.verticalRc)
        assertEquals(0, diagnostics.yawRc)
        assertTrue(controller.state.value.hoverActive)
    }

    @Test fun `reset replaces runtime with grounded centred visible zero state and old state cannot return`() = runTest {
        val controller = flyingController()
        controller.applySimulatorScenario(SimulatorScenarioAction.MovePersonRight)
        advanceTimeBy(300L)
        runCurrent()
        controller.applySimulatorScenario(SimulatorScenarioAction.Reset)
        runCurrent()

        assertEquals(DroneConnectionState.Connected, controller.state.value.connection)
        assertEquals(FlightState.Grounded, controller.state.value.flight)
        val reset = requireNotNull(controller.state.value.simulatorDiagnostics)
        assertEquals(.5f, reset.personHorizontalPosition, .001f)
        assertTrue(reset.personVisible)
        assertEquals(0, reset.yawRc)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(FlightState.Grounded, controller.state.value.flight)
        assertEquals(.5f, requireNotNull(controller.state.value.simulatorDiagnostics).personHorizontalPosition, .001f)
    }

    @Test fun `hide clears current synthetic result`() = runTest {
        val controller = connectedController()
        controller.setTrackingMode(TrackingMode.DetectOnly)
        advanceTimeBy(100L)
        runCurrent()
        assertNotNull(controller.state.value.personDetections.singleOrNull())
        controller.applySimulatorScenario(SimulatorScenarioAction.TogglePersonVisibility)
        advanceTimeBy(100L)
        runCurrent()
        assertTrue(controller.state.value.personDetections.isEmpty())
    }

    private fun kotlinx.coroutines.test.TestScope.controller() =
        MockDroneController(parentScope = backgroundScope)

    private fun kotlinx.coroutines.test.TestScope.connectedController() = controller().also {
        it.connect()
        runCurrent()
    }

    private fun kotlinx.coroutines.test.TestScope.flyingController() = connectedController().also {
        it.takeOff()
        advanceTimeBy(200L)
        runCurrent()
    }
}
