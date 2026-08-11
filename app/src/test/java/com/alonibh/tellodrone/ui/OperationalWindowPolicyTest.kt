package com.alonibh.tellodrone.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
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
}
