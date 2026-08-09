package com.alonibh.tellodrone

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.material3.MaterialTheme
import com.alonibh.tellodrone.data.MockDroneController
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.ui.DroneDashboard
import com.alonibh.tellodrone.ui.DroneViewModel
import org.junit.Rule
import org.junit.Test

class DroneDashboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun disconnected_dashboard_gates_takeoff_and_renders_safety_controls() {
        val controller = MockDroneController(DroneSessionState(connection = DroneConnectionState.Disconnected))
        compose.setContent { MaterialTheme { DroneDashboard(controller.state.value, DroneViewModel(controller)) } }
        compose.onNodeWithTag("take_off").assertIsNotEnabled()
        compose.onNodeWithTag("emergency_motor_kill").assertExists()
        compose.onNodeWithTag("stop_hover").assertExists()
        compose.onNodeWithText("TELLO DRONE").assertExists()
    }
}
