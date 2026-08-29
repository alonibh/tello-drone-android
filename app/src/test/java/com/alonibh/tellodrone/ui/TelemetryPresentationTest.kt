package com.alonibh.tellodrone.ui

import com.alonibh.tellodrone.domain.TelemetrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryPresentationTest {
    @Test fun `speed presentation does not round real low motion down to fake zero`() {
        assertEquals("0.0 m/s", formatTelemetrySpeed(0f))
        assertEquals("0.04 m/s", formatTelemetrySpeed(.04f))
        assertEquals("0.10 m/s", formatTelemetrySpeed(.099f))
        assertEquals("0.5 m/s", formatTelemetrySpeed(.5f))
        assertEquals("1.5 m/s", formatTelemetrySpeed(1.5f))
    }

    @Test fun `stale or unavailable speed is not presented as zero`() {
        assertEquals("—", telemetrySpeedValue(TelemetrySnapshot(isFresh = false, speedMetersPerSecond = 1.5f)))
        assertEquals("—", telemetrySpeedValue(TelemetrySnapshot(isFresh = true, speedMetersPerSecond = null)))
        assertEquals("0.0 m/s", telemetrySpeedValue(TelemetrySnapshot(isFresh = true, speedMetersPerSecond = 0f)))
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
