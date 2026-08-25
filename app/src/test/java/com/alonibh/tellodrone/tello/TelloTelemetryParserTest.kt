package com.alonibh.tellodrone.tello

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelloTelemetryParserTest {
    @Test fun `parses supported Tello state and derives speed`() {
        val sample = TelloTelemetryParser.parse(
            "pitch:0;roll:1;yaw:2;vgx:30;vgy:40;vgz:0;templ:60;temph:64;h:125;bat:77;time:19;",
            receivedAt = Instant.parse("2026-08-10T00:00:00Z"),
            receivedAtMonotonicMillis = 500,
        )!!

        assertEquals(77, sample.batteryPercent)
        assertEquals(1.25f, sample.heightMeters)
        assertEquals(0.5f, sample.speedMetersPerSecond)
        assertEquals(62f, sample.temperatureCelsius)
        assertEquals(19, sample.flightTimeSeconds)
        assertEquals(500, sample.receivedAtMonotonicMillis)
    }

    @Test fun `does not fabricate absent or malformed telemetry`() {
        val sample = TelloTelemetryParser.parse("bat:nope;h:20;", receivedAtMonotonicMillis = 1)!!
        assertNull(sample.batteryPercent)
        assertNull(sample.speedMetersPerSecond)
        assertEquals(.2f, sample.heightMeters)
        assertNull(TelloTelemetryParser.parse("not-state", receivedAtMonotonicMillis = 1))
    }

    @Test fun `rejects unrecognized packets and preserves invalid fields as unknown`() {
        assertNull(TelloTelemetryParser.parse("unexpected:1;", receivedAtMonotonicMillis = 1))

        val sample = TelloTelemetryParser.parse(
            "h:-1;time:-4;templ:NaN;temph:Infinity;bat:101;",
            receivedAtMonotonicMillis = 1,
        )!!
        assertNull(sample.heightMeters)
        assertNull(sample.flightTimeSeconds)
        assertNull(sample.temperatureCelsius)
        assertNull(sample.batteryPercent)
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
