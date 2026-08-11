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

    @Test fun `short landscape takes precedence over every width class`() {
        assertEquals(WindowLayout.CompactHeight, windowLayout(640.dp, 360.dp))
        assertEquals(WindowLayout.CompactHeight, windowLayout(1200.dp, 479.dp))
    }

    @Test fun `tablet and medium layouts remain unchanged`() {
        assertEquals(WindowLayout.Expanded, windowLayout(1280.dp, 800.dp))
        assertEquals(WindowLayout.Medium, windowLayout(700.dp, 600.dp))
    }

    @Test fun `compact height routes dashboard controls and tracking to their dedicated content`() {
        assertEquals(CompactHeightContent.Dashboard, compactHeightContent("Dashboard"))
        assertEquals(CompactHeightContent.Controls, compactHeightContent("Controls"))
        assertEquals(CompactHeightContent.Tracking, compactHeightContent("Tracking"))
    }
}
