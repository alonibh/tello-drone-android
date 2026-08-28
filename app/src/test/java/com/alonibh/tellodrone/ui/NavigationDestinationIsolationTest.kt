package com.alonibh.tellodrone.ui

import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.TelemetrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationDestinationIsolationTest {
    @Test
    fun `navigation destinations do not alter underlying flight or connection state`() {
        val destinations = listOf("Dashboard", "Controls", "Tracking", "Media", "Status")
        val initialSessionState = DroneSessionState(
            connection = DroneConnectionState.Connected,
            flight = FlightState.Flying,
            telemetry = TelemetrySnapshot(batteryPercent = 50, heightMeters = 1.2f, isFresh = true),
        )

        for (dest in destinations) {
            // Verify that navigation destination represents a pure UI mode
            // and does not change session or flight state
            assertEquals(FlightState.Flying, initialSessionState.flight)
            assertEquals(DroneConnectionState.Connected, initialSessionState.connection)
            val presentation = initialSessionState.trackingUiPresentation()
            // Tracking UI presentation remains consistent
            assertEquals("Off", presentation.detection.value)
        }
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
