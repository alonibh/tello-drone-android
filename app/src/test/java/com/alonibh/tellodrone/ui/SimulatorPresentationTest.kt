package com.alonibh.tellodrone.ui

import com.alonibh.tellodrone.domain.ControllerMode
import com.alonibh.tellodrone.domain.SimulatorScenarioAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorPresentationTest {
    @Test fun `mock implementation is presented only as simulator`() {
        assertEquals("REAL", controllerModeLabel(ControllerMode.Real))
        assertEquals("SIMULATOR", controllerModeLabel(ControllerMode.Mock))
        assertEquals("START SIMULATOR", START_SIMULATOR_LABEL)
        assertEquals("STOP SIMULATOR", STOP_SIMULATOR_LABEL)
        assertEquals("SIMULATOR • NO PHYSICAL DRONE", SIMULATOR_BANNER_TEXT)
        assertEquals("SYNTHETIC DETECTION", SYNTHETIC_DETECTION_LABEL)
        assertEquals("SIMULATED YAW FOLLOW", SIMULATED_YAW_FOLLOW_LABEL)
    }

    @Test fun `all required scenario actions have user facing labels`() {
        assertEquals(SimulatorScenarioAction.entries.toSet(), simulatorScenarioLabels.keys)
        assertTrue(simulatorScenarioLabels.values.containsAll(listOf(
            "MOVE PERSON LEFT",
            "MOVE PERSON RIGHT",
            "CENTER PERSON",
            "HIDE/SHOW PERSON",
            "RESET SCENARIO",
        )))
    }
}
