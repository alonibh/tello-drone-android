package com.alonibh.tellodrone

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.alonibh.tellodrone.domain.DetectorBackend
import com.alonibh.tellodrone.domain.DetectorBackendPreference
import com.alonibh.tellodrone.domain.DetectorBenchmarkState
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.VideoState
import com.alonibh.tellodrone.ui.DroneDashboard
import com.alonibh.tellodrone.ui.NoOpDroneDashboardActions
import com.alonibh.tellodrone.vision.DetectorBenchmarkResult
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class DroneDashboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun disconnected_dashboard_gates_takeoff_and_renders_safety_controls() {
        val initialState = DroneSessionState(connection = DroneConnectionState.Disconnected)
        compose.setContent { MaterialTheme { DroneDashboard(initialState, NoOpDroneDashboardActions) } }
        compose.onNodeWithTag("take_off").assertIsNotEnabled()
        compose.onNodeWithTag("emergency_motor_kill").assertExists()
        compose.onNodeWithTag("stop_hover").assertExists()
        compose.onNodeWithText("TELLO DRONE").assertExists()
    }

    @Test fun adaptive_windows_keep_critical_and_emergency_controls_distinct() {
        val dashboardState = DroneSessionState(connection = DroneConnectionState.Connected)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(420.dp, 900.dp)) { DroneDashboard(dashboardState, NoOpDroneDashboardActions) }
            }
        }
        compose.onNodeWithTag("layout_compact").assertExists()
        compose.onNodeWithTag("stop_hover").assertExists()
        compose.onNodeWithTag("emergency_motor_kill").assertExists()
    }

    @Test fun adaptive_layout_uses_medium_expanded_and_compact_height_breakpoints() {
        val dashboardState = DroneSessionState(connection = DroneConnectionState.Connected)
        compose.setContent { MaterialTheme { Box(Modifier.size(700.dp, 800.dp)) { DroneDashboard(dashboardState, NoOpDroneDashboardActions) } } }
        compose.onNodeWithTag("layout_medium").assertExists()
        compose.setContent { MaterialTheme { Box(Modifier.size(900.dp, 360.dp)) { DroneDashboard(dashboardState, NoOpDroneDashboardActions) } } }
        compose.onNodeWithTag("layout_expanded").assertExists()
        compose.setContent { MaterialTheme { Box(Modifier.size(800.dp, 360.dp)) { DroneDashboard(dashboardState, NoOpDroneDashboardActions) } } }
        compose.onNodeWithTag("layout_compact_height").assertExists()
    }

    @Test fun expanded_tracking_pane_scrolls_to_completed_benchmark_report() {
        val dashboardState = DroneSessionState(
            connection = DroneConnectionState.Connected,
            video = VideoState(
                availability = VideoAvailability.Streaming,
                analysisLatestSequence = 1L,
                detectorBenchmarkState = DetectorBenchmarkState.Complete,
                detectorBenchmarkResult = DetectorBenchmarkResult(
                    manufacturer = "Teclast",
                    model = "Tablet",
                    androidVersion = "Android",
                    sdkLevel = 35,
                    supportedAbis = listOf("arm64-v8a"),
                    availableProcessors = 8,
                    requestedBackend = DetectorBackendPreference.Cpu,
                    actualBackend = DetectorBackend.Cpu,
                    fellBackFromGpu = false,
                    detectorModel = "MobileNet",
                    startupMillis = 12L,
                    durationMillis = 30_000L,
                    completedInferences = 100,
                    steadyStateInferences = 97,
                    inferenceMinMillis = 8L,
                    inferenceP50Millis = 10L,
                    inferenceP95Millis = 14L,
                    inferenceMaxMillis = 20L,
                    detectorFps = 50f,
                    previewFps = 30f,
                    analysisFrameFps = 30f,
                ),
            ),
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, 800.dp)) {
                    DroneDashboard(dashboardState, NoOpDroneDashboardActions)
                }
            }
        }

        compose.onNodeWithText("Tracking").performClick()
        compose.onNodeWithText("ADVANCED DETECTOR").performClick()
        compose.onNodeWithTag("expanded_tracking_scroll").performTouchInput {
            swipeUp()
            swipeUp()
        }
        compose.onNodeWithText("COPY REPORT").assertIsDisplayed()
    }
}
