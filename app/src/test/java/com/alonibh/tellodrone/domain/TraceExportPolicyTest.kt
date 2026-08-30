package com.alonibh.tellodrone.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceExportPolicyTest {
    @Test fun `trace export is enabled only for explicitly grounded flight state`() {
        assertTrue(isTraceExportAllowed(FlightState.Grounded))
        FlightState.entries.filterNot { it == FlightState.Grounded }.forEach { flight ->
            assertFalse("Export must be blocked for $flight", isTraceExportAllowed(flight))
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
