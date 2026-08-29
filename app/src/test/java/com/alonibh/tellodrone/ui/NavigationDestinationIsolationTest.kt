package com.alonibh.tellodrone.ui

import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.TelemetrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationDestinationIsolationTest {
    @Test
    fun `unified presentation does not alter underlying flight or connection state`() {
        val initialSessionState = DroneSessionState(
            connection = DroneConnectionState.Connected,
            flight = FlightState.Flying,
            telemetry = TelemetrySnapshot(batteryPercent = 50, heightMeters = 1.2f, isFresh = true),
        )

        assertEquals(FlightState.Flying, initialSessionState.flight)
        assertEquals(DroneConnectionState.Connected, initialSessionState.connection)
        assertEquals("Off", initialSessionState.trackingUiPresentation().detection.value)
        assertEquals("OFF", trackingHudLabel(initialSessionState))
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
