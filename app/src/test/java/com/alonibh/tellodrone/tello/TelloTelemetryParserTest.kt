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
        assertEquals(2, sample.yawDegrees)
        assertEquals(500, sample.receivedAtMonotonicMillis)
    }

    @Test fun `calculates shortest angular difference handling degree wraparound`() {
        assertEquals(2f, shortestAngularDifferenceDegrees(179f, -179f), 0.001f)
        assertEquals(-2f, shortestAngularDifferenceDegrees(-179f, 179f), 0.001f)
        assertEquals(10f, shortestAngularDifferenceDegrees(0f, 10f), 0.001f)
        assertEquals(-10f, shortestAngularDifferenceDegrees(10f, 0f), 0.001f)
        assertEquals(-20f, shortestAngularDifferenceDegrees(-170f, 170f), 0.001f)
        assertEquals(20f, shortestAngularDifferenceDegrees(170f, -170f), 0.001f)
    }

    @Test fun `derives yaw rate from consecutive samples and rejects stale gaps`() {
        val rate1 = calculateYawRateDegreesPerSecond(
            previousYawDegrees = 178,
            currentYawDegrees = 179,
            previousTimestampMillis = 1000L,
            currentTimestampMillis = 1100L,
        )
        assertEquals(10f, rate1!!, 0.01f)

        // Wraparound from 179 to -179 across 100ms
        val rateWrap = calculateYawRateDegreesPerSecond(
            previousYawDegrees = 179,
            currentYawDegrees = -179,
            previousTimestampMillis = 1100L,
            currentTimestampMillis = 1200L,
        )
        assertEquals(20f, rateWrap!!, 0.01f)

        // Stale gap > 1000ms returns null
        val rateStale = calculateYawRateDegreesPerSecond(
            previousYawDegrees = 10,
            currentYawDegrees = 20,
            previousTimestampMillis = 1000L,
            currentTimestampMillis = 2100L,
        )
        assertNull(rateStale)

        // Non-positive gap returns null
        val rateInvalidTime = calculateYawRateDegreesPerSecond(
            previousYawDegrees = 10,
            currentYawDegrees = 20,
            previousTimestampMillis = 1000L,
            currentTimestampMillis = 1000L,
        )
        assertNull(rateInvalidTime)
    }

    @Test fun `does not fabricate absent or malformed telemetry`() {
        val sample = TelloTelemetryParser.parse("bat:nope;h:20;", receivedAtMonotonicMillis = 1)!!
        assertNull(sample.batteryPercent)
        assertNull(sample.speedMetersPerSecond)
        assertEquals(.2f, sample.heightMeters)
        assertNull(TelloTelemetryParser.parse("not-state", receivedAtMonotonicMillis = 1))
    }

    @Test fun `derives total translational speed from every real velocity component`() {
        fun speed(vgx: Int, vgy: Int, vgz: Int) = TelloTelemetryParser.parse(
            "vgx:$vgx;vgy:$vgy;vgz:$vgz;",
            receivedAtMonotonicMillis = 1,
        )!!.speedMetersPerSecond

        assertEquals(1f, speed(100, 0, 0))
        assertEquals(1f, speed(0, 100, 0))
        assertEquals(1f, speed(0, 0, 100))
        assertEquals(1.5f, speed(100, 100, 50))
        assertEquals(0f, speed(0, 0, 0))
        assertEquals(1.3f, speed(-30, -40, -120))
    }

    @Test fun `speed remains unavailable unless all velocity components are present`() {
        assertNull(TelloTelemetryParser.parse("vgx:100;vgy:0;", receivedAtMonotonicMillis = 1)!!.speedMetersPerSecond)
        assertNull(totalTranslationalSpeedMetersPerSecond(100, null, 0))
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
