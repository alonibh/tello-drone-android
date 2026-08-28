package com.alonibh.tellodrone.ui

import androidx.compose.ui.unit.dp
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.VideoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletLandscapeLayoutTest {

    @Test
    fun `tablet 1280x800 selects Expanded layout`() {
        val layout = windowLayout(1280.dp, 800.dp)
        assertEquals(WindowLayout.Expanded, layout)
        assertFalse(isPortraitOperationalWindow(1280.dp, 800.dp))
    }

    @Test
    fun `operational tabs parse destination strings accurately`() {
        assertEquals(OperationalTab.Flight, OperationalTab.from("FLIGHT"))
        assertEquals(OperationalTab.Flight, OperationalTab.from("Dashboard"))
        assertEquals(OperationalTab.Flight, OperationalTab.from("Controls"))
        assertEquals(OperationalTab.Tracking, OperationalTab.from("TRACKING"))
        assertEquals(OperationalTab.Tracking, OperationalTab.from("Tracking"))
        assertEquals(OperationalTab.Status, OperationalTab.from("STATUS"))
        assertEquals(OperationalTab.Status, OperationalTab.from("Status"))
        assertEquals(OperationalTab.Flight, OperationalTab.from("Media"))
        assertEquals(OperationalTab.Flight, OperationalTab.from("Unknown"))
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
