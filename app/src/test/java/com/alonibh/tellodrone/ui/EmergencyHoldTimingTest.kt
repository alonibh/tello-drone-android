package com.alonibh.tellodrone.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyHoldTimingTest {
    @Test fun `emergency hold uses one 900 millisecond completion boundary`() {
        assertEquals(900L, EMERGENCY_HOLD_MILLIS)
    }

    @Test fun `completion triggers once and release or cancellation resets the next hold`() {
        val completion = EmergencyHoldCompletion()
        assertTrue(completion.completeOnce())
        assertFalse(completion.completeOnce())
        completion.reset()
        assertTrue(completion.completeOnce())
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
