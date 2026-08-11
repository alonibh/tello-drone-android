package com.alonibh.tellodrone.ui

import com.alonibh.tellodrone.domain.ControllerMode
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.TelemetrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeoffConfirmationGateTest {
    @Test fun `eligible real takeoff confirms exactly once`() {
        val gate = TakeoffConfirmationGate()
        val state = eligibleRealState()
        var calls = 0

        assertTrue(gate.request(state))
        assertTrue(gate.confirm(state) { calls++ })
        assertFalse(gate.confirm(state) { calls++ })

        assertEquals(1, calls)
    }

    @Test fun `ineligible transition dismisses pending confirmation`() {
        val gate = TakeoffConfirmationGate()
        assertTrue(gate.request(eligibleRealState()))
        val stale = eligibleRealState().copy(telemetry = TelemetrySnapshot(isFresh = false))

        assertFalse(gate.dismissIfIneligible(stale))
        assertFalse(gate.confirm(stale) {})
    }

    @Test fun `mock mode does not request a real aircraft confirmation`() {
        val gate = TakeoffConfirmationGate()
        val mockState = eligibleRealState().copy(controllerMode = ControllerMode.Mock)

        assertFalse(gate.request(mockState))
    }

    private fun eligibleRealState() = DroneSessionState(
        controllerMode = ControllerMode.Real,
        connection = DroneConnectionState.Connected,
        flight = FlightState.Grounded,
        telemetry = TelemetrySnapshot(isFresh = true),
    )
}
