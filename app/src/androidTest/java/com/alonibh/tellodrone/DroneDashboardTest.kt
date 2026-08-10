package com.alonibh.tellodrone

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alonibh.tellodrone.data.MockDroneController
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.ui.DroneDashboard
import com.alonibh.tellodrone.ui.DroneViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class DroneDashboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun disconnected_dashboard_gates_takeoff_and_renders_safety_controls() {
        val controller = MockDroneController(DroneSessionState(connection = DroneConnectionState.Disconnected))
        val initialState = controller.state.value
        val viewModel = DroneViewModel(controller)
        compose.setContent { MaterialTheme { DroneDashboard(initialState, viewModel) } }
        compose.onNodeWithTag("take_off").assertIsNotEnabled()
        compose.onNodeWithTag("emergency_motor_kill").assertExists()
        compose.onNodeWithTag("stop_hover").assertExists()
        compose.onNodeWithText("TELLO DRONE").assertExists()
    }

    @Test fun adaptive_windows_keep_critical_and_emergency_controls_distinct() {
        val controller = MockDroneController(DroneSessionState(connection = DroneConnectionState.Connected))
        val viewModel = DroneViewModel(controller)
        val dashboardState = controller.state.value
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(420.dp, 900.dp)) { DroneDashboard(dashboardState, viewModel) }
            }
        }
        compose.onNodeWithTag("layout_compact").assertExists()
        compose.onNodeWithTag("stop_hover").assertExists()
        compose.onNodeWithTag("emergency_motor_kill").assertExists()
    }

    @Test fun adaptive_layout_uses_medium_expanded_and_compact_height_breakpoints() {
        val controller = MockDroneController(DroneSessionState(connection = DroneConnectionState.Connected))
        val viewModel = DroneViewModel(controller)
        val dashboardState = controller.state.value
        compose.setContent { MaterialTheme { Box(Modifier.size(700.dp, 800.dp)) { DroneDashboard(dashboardState, viewModel) } } }
        compose.onNodeWithTag("layout_medium").assertExists()
        compose.setContent { MaterialTheme { Box(Modifier.size(900.dp, 360.dp)) { DroneDashboard(dashboardState, viewModel) } } }
        compose.onNodeWithTag("layout_expanded").assertExists()
        compose.setContent { MaterialTheme { Box(Modifier.size(800.dp, 360.dp)) { DroneDashboard(dashboardState, viewModel) } } }
        compose.onNodeWithTag("layout_compact_height").assertExists()
        assertEquals(dashboardState, controller.state.value)
    }
}
