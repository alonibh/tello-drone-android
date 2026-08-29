package com.alonibh.tellodrone.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalWindowPolicyTest {
    @Test fun `portrait window uses the safety fallback`() {
        assertTrue(isPortraitOperationalWindow(600.dp, 900.dp))
    }

    @Test fun `landscape and square windows retain the operational dashboard`() {
        assertFalse(isPortraitOperationalWindow(1280.dp, 800.dp))
        assertFalse(isPortraitOperationalWindow(800.dp, 800.dp))
    }

    @Test fun `joystick diameter stays responsive and bounded`() {
        assertEquals(126f, joystickDiameter(640.dp, 360.dp).value, 0f)
        assertEquals(230.4f, joystickDiameter(1280.dp, 800.dp).value, .01f)
        assertEquals(240f, joystickDiameter(1920.dp, 1200.dp).value, 0f)
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
