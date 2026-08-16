package com.alonibh.tellodrone.ui

import com.alonibh.tellodrone.domain.ControllerMode
import com.alonibh.tellodrone.domain.SimulatorScenarioAction

internal const val SIMULATOR_MODE_LABEL = "SIMULATOR"
internal const val START_SIMULATOR_LABEL = "START SIMULATOR"
internal const val STOP_SIMULATOR_LABEL = "STOP SIMULATOR"
internal const val SIMULATOR_BANNER_TEXT = "SIMULATOR • NO PHYSICAL DRONE"
internal const val SYNTHETIC_DETECTION_LABEL = "SYNTHETIC DETECTION"
internal const val SIMULATED_YAW_FOLLOW_LABEL = "SIMULATED YAW FOLLOW"

internal fun controllerModeLabel(mode: ControllerMode): String = when (mode) {
    ControllerMode.Real -> "REAL"
    ControllerMode.Mock -> SIMULATOR_MODE_LABEL
}

internal val simulatorScenarioLabels = mapOf(
    SimulatorScenarioAction.MovePersonLeft to "MOVE PERSON LEFT",
    SimulatorScenarioAction.MovePersonRight to "MOVE PERSON RIGHT",
    SimulatorScenarioAction.CenterPerson to "CENTER PERSON",
    SimulatorScenarioAction.TogglePersonVisibility to "HIDE/SHOW PERSON",
    SimulatorScenarioAction.Reset to "RESET SCENARIO",
)
