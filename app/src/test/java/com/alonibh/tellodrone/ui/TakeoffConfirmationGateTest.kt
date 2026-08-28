package com.alonibh.tellodrone.ui

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

    @Test fun `battery threshold gates takeoff eligibility`() {
        val gate = TakeoffConfirmationGate()
        val base = eligibleRealState()

        // Battery null or below 30% is ineligible
        assertFalse(base.copy(telemetry = base.telemetry.copy(batteryPercent = null)).isTakeoffEligible())
        assertFalse(base.copy(telemetry = base.telemetry.copy(batteryPercent = 0)).isTakeoffEligible())
        assertFalse(base.copy(telemetry = base.telemetry.copy(batteryPercent = 17)).isTakeoffEligible())
        assertFalse(base.copy(telemetry = base.telemetry.copy(batteryPercent = 29)).isTakeoffEligible())

        // 30% and above is eligible
        assertTrue(base.copy(telemetry = base.telemetry.copy(batteryPercent = 30)).isTakeoffEligible())
        assertTrue(base.copy(telemetry = base.telemetry.copy(batteryPercent = 80)).isTakeoffEligible())
    }

    private fun eligibleRealState() = DroneSessionState(
        connection = DroneConnectionState.Connected,
        flight = FlightState.Grounded,
        telemetry = TelemetrySnapshot(isFresh = true, batteryPercent = 80),
    )
}
// SPDX-License-Identifier: AGPL-3.0-only
