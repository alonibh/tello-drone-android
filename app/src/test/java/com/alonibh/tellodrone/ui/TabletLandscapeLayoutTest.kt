package com.alonibh.tellodrone.ui

import androidx.compose.ui.unit.dp
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.domain.RcSpeedMode
import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.VideoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletLandscapeLayoutTest {

    @Test
    fun `tablet 1280x800 uses landscape control geometry`() {
        assertFalse(isPortraitOperationalWindow(1280.dp, 800.dp))
        assertEquals(230.4f, joystickDiameter(1280.dp, 800.dp).value, 0.01f)
    }

    @Test
    fun `speed presets expose requested percentages and conservative RC mapping`() {
        assertEquals(listOf(30, 65, 100), RcSpeedMode.entries.map { it.percent })
        assertEquals(listOf(12, 26, 40), RcSpeedMode.entries.map { it.rcMagnitude })
        assertEquals(RcSpeedMode.Slow, RcSpeedMode.fromPercent(30))
        assertEquals(RcSpeedMode.Medium, RcSpeedMode.fromPercent(65))
        assertEquals(RcSpeedMode.Fast, RcSpeedMode.fromPercent(100))
    }

    @Test
    fun `manualVectorFromSticks isolates left yaw-vertical and right roll-pitch`() {
        val leftStick = JoystickVector(horizontal = 0.5f, vertical = 0.8f)
        val rightStick = JoystickVector(horizontal = -0.4f, vertical = -0.6f)
        val vector = manualVectorFromSticks(leftStick, rightStick)

        assertEquals(0.5f, vector.yaw, 0.001f)
        assertEquals(0.8f, vector.vertical, 0.001f)
        assertEquals(-0.4f, vector.lateral, 0.001f)
        assertEquals(-0.6f, vector.forward, 0.001f)
    }

    @Test
    fun `zero sticks produce zero manual control vector`() {
        val vector = manualVectorFromSticks(JoystickVector(), JoystickVector())
        assertEquals(ManualControlVector(), vector)
    }

    @Test
    fun `grounded flight state can not trigger emergency kill`() {
        val groundedState = DroneSessionState(
            connection = DroneConnectionState.Connected,
            flight = FlightState.Grounded,
        )
        val flyingState = DroneSessionState(
            connection = DroneConnectionState.Connected,
            flight = FlightState.Flying,
        )
        val takingOffState = DroneSessionState(
            connection = DroneConnectionState.Connected,
            flight = FlightState.TakingOff,
        )
        val disconnectedState = DroneSessionState(
            connection = DroneConnectionState.Disconnected,
            flight = FlightState.Flying,
        )

        fun DroneSessionState.canEmergencyCheck() = connection == DroneConnectionState.Connected &&
            flight in setOf(FlightState.TakingOff, FlightState.Flying, FlightState.Landing, FlightState.Unknown)

        assertFalse(groundedState.canEmergencyCheck())
        assertTrue(flyingState.canEmergencyCheck())
        assertTrue(takingOffState.canEmergencyCheck())
        assertFalse(disconnectedState.canEmergencyCheck())
    }

    @Test
    fun `tracking ui presentation reflects detection and target states`() {
        val state = DroneSessionState(
            connection = DroneConnectionState.Connected,
            flight = FlightState.Flying,
            video = VideoState(
                availability = VideoAvailability.Streaming,
                personDetectionState = PersonDetectionState.Detecting,
            ),
        )
        val presentation = state.trackingUiPresentation()
        assertEquals("On", presentation.detection.value)
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
