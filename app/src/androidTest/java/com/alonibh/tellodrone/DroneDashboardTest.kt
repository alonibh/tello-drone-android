package com.alonibh.tellodrone

import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.NormalizedBoundingBox
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.domain.TelemetrySnapshot
import com.alonibh.tellodrone.domain.TrackingMode
import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.VideoState
import com.alonibh.tellodrone.ui.DroneDashboard
import com.alonibh.tellodrone.ui.DroneDashboardActions
import com.alonibh.tellodrone.ui.NoOpDroneDashboardActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DroneDashboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun unified_landscape_renders_every_primary_control_without_navigation() {
        render(DroneSessionState(connection = DroneConnectionState.Disconnected))

        compose.onNodeWithTag("unified_flight_screen").assertExists()
        compose.onNodeWithTag("left_joystick").assertExists()
        compose.onNodeWithTag("right_joystick").assertExists()
        compose.onNodeWithTag("take_off").assertExists().assertIsNotEnabled()
        compose.onNodeWithTag("land").assertExists().assertIsNotEnabled()
        compose.onNodeWithTag("stop_hover").assertExists()
        compose.onNodeWithTag("emergency_motor_kill").assertExists()
        compose.onNodeWithTag("telemetry_overlay").assertExists()
        compose.onNodeWithTag("tracking_overlay").assertExists()
        compose.onNodeWithTag("operational_navigation").assertDoesNotExist()
        compose.onNodeWithText("Select Target").assertDoesNotExist()
    }

    @Test fun joysticks_are_symmetric_about_the_tablet_edges() {
        render(DroneSessionState(connection = DroneConnectionState.Connected))

        val root = compose.onNodeWithTag("unified_flight_screen").fetchSemanticsNode().boundsInRoot
        val left = compose.onNodeWithTag("left_joystick").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag("right_joystick").fetchSemanticsNode().boundsInRoot

        assertEquals(left.width, right.width, 1f)
        assertEquals(left.center.x - root.left, root.right - right.center.x, 1f)
        assertEquals(left.center.y, right.center.y, 1f)
    }

    @Test fun speed_segments_send_requested_real_control_state() {
        val actions = RecordingActions()
        render(DroneSessionState(speedPercent = 65), actions)

        compose.onNodeWithTag("speed_slow").performClick()
        assertEquals(30, actions.selectedSpeed)
        compose.onNodeWithTag("speed_medium").performClick()
        assertEquals(65, actions.selectedSpeed)
        compose.onNodeWithTag("speed_fast").performClick()
        assertEquals(100, actions.selectedSpeed)
    }

    @Test fun connected_status_is_passive_while_disconnected_state_is_an_explicit_action() {
        val connectedActions = RecordingActions()
        render(DroneSessionState(connection = DroneConnectionState.Connected), connectedActions)
        val connected = compose.onNodeWithTag("connection_status").fetchSemanticsNode()
        assertFalse(connected.config.contains(SemanticsActions.OnClick))
        assertEquals(0, connectedActions.disconnectRequests)

        val disconnectedActions = RecordingActions()
        render(DroneSessionState(connection = DroneConnectionState.Disconnected), disconnectedActions)
        compose.onNodeWithTag("connection_action").performClick()
        assertEquals(1, disconnectedActions.connectRequests)
        assertEquals(0, disconnectedActions.disconnectRequests)
    }

    @Test fun select_target_state_uses_instruction_only_and_compact_actions_keep_safe_touch_targets() {
        render(
            DroneSessionState(
                connection = DroneConnectionState.Connected,
                flight = FlightState.Flying,
                telemetry = TelemetrySnapshot(isFresh = true),
                tracking = TrackingMode.DetectOnly,
                video = VideoState(
                    availability = VideoAvailability.Streaming,
                    analysisLatestSequence = 1L,
                    personDetectionState = PersonDetectionState.Detecting,
                ),
            ),
        )

        compose.onNodeWithText("Tap a detected person to select").assertExists()
        compose.onNodeWithTag("tracking_primary_action").assertDoesNotExist()
        compose.onNodeWithText("Tap a person").assertDoesNotExist()
        assertTrue(compose.onNodeWithTag("stop_detection").fetchSemanticsNode().boundsInRoot.height >= 44f)
        assertTrue(compose.onNodeWithTag("speed_slow").fetchSemanticsNode().boundsInRoot.height >= 40f)
    }

    @Test fun tapping_a_current_detection_selects_that_target_directly() {
        val detection = PersonDetection(
            boundingBox = NormalizedBoundingBox(.4f, .2f, .6f, .75f),
            confidence = .91f,
            frameSequence = 7L,
            sourceTimestampNanos = 9L,
        )
        val actions = RecordingActions()
        render(
            DroneSessionState(
                connection = DroneConnectionState.Connected,
                tracking = TrackingMode.DetectOnly,
                video = VideoState(
                    availability = VideoAvailability.Streaming,
                    analysisLatestSequence = 7L,
                    personDetectionState = PersonDetectionState.Detecting,
                    processedDetectorFrameSequence = 7L,
                    processedDetectorSourceTimestampNanos = 9L,
                ),
                personDetections = listOf(detection),
            ),
            actions,
        )

        compose.onNodeWithTag("person_detection_0").performClick()
        assertEquals(detection, actions.selectedTarget)
        compose.onNodeWithText("Select Target").assertDoesNotExist()
    }

    @Test fun one_aspect_fit_video_surface_is_attached_for_the_unified_screen() {
        val actions = RecordingActions()
        render(
            DroneSessionState(
                connection = DroneConnectionState.Connected,
                video = VideoState(availability = VideoAvailability.Streaming),
            ),
            actions,
        )
        compose.waitUntil(5_000) { actions.attached.size == 1 }

        compose.onNodeWithTag("aspect_fit_video").assertExists()
        assertEquals(1, actions.attached.size)
        assertTrue(actions.detached.isEmpty())
    }

    @Test fun portrait_airborne_fallback_keeps_safety_actions() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(420.dp, 900.dp)) {
                    DroneDashboard(
                        DroneSessionState(
                            connection = DroneConnectionState.Connected,
                            flight = FlightState.Flying,
                            telemetry = TelemetrySnapshot(isFresh = true),
                        ),
                        NoOpDroneDashboardActions,
                    )
                }
            }
        }

        compose.onNodeWithTag("portrait_safety_fallback").assertExists()
        compose.onNodeWithTag("stop_hover").assertExists()
        compose.onNodeWithTag("land").assertExists()
        compose.onNodeWithTag("emergency_motor_kill").assertExists()
    }

    private fun render(state: DroneSessionState, actions: DroneDashboardActions = NoOpDroneDashboardActions) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(1280.dp, 800.dp)) { DroneDashboard(state, actions) }
            }
        }
    }

    private class RecordingActions : DroneDashboardActions by NoOpDroneDashboardActions {
        val attached = mutableListOf<Surface>()
        val detached = mutableListOf<Surface>()
        var selectedSpeed: Int? = null
        var selectedTarget: PersonDetection? = null
        var connectRequests = 0
        var disconnectRequests = 0

        override fun connect() {
            connectRequests++
        }

        override fun disconnect() {
            disconnectRequests++
        }

        override fun setSpeed(percent: Int) {
            selectedSpeed = percent
        }

        override fun selectTarget(detection: PersonDetection) {
            selectedTarget = detection
        }

        override fun attachVideoSurface(surface: Surface) {
            attached += surface
        }

        override fun detachVideoSurface(surface: Surface) {
            detached += surface
        }
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
