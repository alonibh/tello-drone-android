package com.alonibh.tellodrone.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YawResponseSafetyMonitorTest {

    @Test
    fun `real flight incident sequence triggers sustained mismatch then catastrophic runaway anomaly`() {
        val monitor = YawResponseSafetyMonitor()

        // 1. Commanded small negative RC (counter-clockwise)
        monitor.recordSentRc(sentAtMillis = 65000L, yawRc = -9)
        monitor.recordSentRc(sentAtMillis = 65100L, yawRc = -17)
        monitor.recordSentRc(sentAtMillis = 65200L, yawRc = -25)

        // 2. First opposing physical telemetry sample: yaw rate jumps positive (+89 deg/s)
        val eval1 = monitor.evaluate(
            sample = TelemetryYawSample(
                yawDegrees = 45,
                rawYawRateDegreesPerSecond = 89f,
                filteredYawRateDegreesPerSecond = 89f,
                receivedAtMillis = 65250L,
            ),
            flightState = FlightState.Flying,
            yawFollowState = YawFollowState.ACTIVE,
        )
        // 1st sample above 50 deg/s is suspect
        assertEquals(YawResponseSafetyStatus.MISMATCH_SUSPECT, eval1.status)

        // 3. Second opposing physical telemetry sample (+70 deg/s) -> sustained mismatch latches!
        val eval2 = monitor.evaluate(
            sample = TelemetryYawSample(
                yawDegrees = 52,
                rawYawRateDegreesPerSecond = 70f,
                filteredYawRateDegreesPerSecond = 70f,
                receivedAtMillis = 65350L,
            ),
            flightState = FlightState.Flying,
            yawFollowState = YawFollowState.ACTIVE,
        )
        assertEquals(YawResponseSafetyStatus.ANOMALY_LATCHED, eval2.status)
        assertEquals(YawResponseAnomalyReason.SUSTAINED_DIRECTION_MISMATCH, eval2.anomalyReason)
        assertNotNull(eval2.anomalyDurationMillis)
    }

    @Test
    fun `catastrophic yaw rate above 140 dps latches immediately on single sample`() {
        val monitor = YawResponseSafetyMonitor()
        monitor.recordSentRc(sentAtMillis = 1000L, yawRc = -10)

        val eval = monitor.evaluate(
            sample = TelemetryYawSample(
                yawDegrees = 100,
                rawYawRateDegreesPerSecond = 160f,
                filteredYawRateDegreesPerSecond = 160f,
                receivedAtMillis = 1100L,
            ),
            flightState = FlightState.Flying,
            yawFollowState = YawFollowState.ACTIVE,
        )
        assertEquals(YawResponseSafetyStatus.ANOMALY_LATCHED, eval.status)
        assertEquals(YawResponseAnomalyReason.CATASTROPHIC_YAW_RATE, eval.anomalyReason)
    }

    @Test
    fun `normal rotation matching commanded direction does not trigger false anomaly`() {
        val monitor = YawResponseSafetyMonitor()

        // Commanded positive yaw +28
        monitor.recordSentRc(sentAtMillis = 1000L, yawRc = 28)
        monitor.recordSentRc(sentAtMillis = 1100L, yawRc = 28)

        // Drone physical yaw rate +90 deg/s (matches direction)
        val eval1 = monitor.evaluate(
            sample = TelemetryYawSample(
                yawDegrees = 10,
                rawYawRateDegreesPerSecond = 90f,
                filteredYawRateDegreesPerSecond = 90f,
                receivedAtMillis = 1150L,
            ),
            flightState = FlightState.Flying,
            yawFollowState = YawFollowState.ACTIVE,
        )
        assertEquals(YawResponseSafetyStatus.NORMAL, eval1.status)
        assertNull(eval1.anomalyReason)

        val eval2 = monitor.evaluate(
            sample = TelemetryYawSample(
                yawDegrees = 19,
                rawYawRateDegreesPerSecond = 95f,
                filteredYawRateDegreesPerSecond = 95f,
                receivedAtMillis = 1250L,
            ),
            flightState = FlightState.Flying,
            yawFollowState = YawFollowState.ACTIVE,
        )
        assertEquals(YawResponseSafetyStatus.NORMAL, eval2.status)
    }

    @Test
    fun `zero runaway triggers when uncommanded rotation continues past braking grace`() {
        val monitor = YawResponseSafetyMonitor()

        // Commanded zero
        monitor.recordSentRc(sentAtMillis = 1000L, yawRc = 0)

        // Sample during braking grace (200ms < 220ms grace)
        val evalGrace = monitor.evaluate(
            sample = TelemetryYawSample(
                yawDegrees = 20,
                rawYawRateDegreesPerSecond = 60f,
                filteredYawRateDegreesPerSecond = 60f,
                receivedAtMillis = 1200L,
            ),
            flightState = FlightState.Flying,
            yawFollowState = YawFollowState.ACTIVE,
        )
        // Normal during active braking grace
        assertEquals(YawResponseSafetyStatus.NORMAL, evalGrace.status)

        // Sample 1 after braking grace (300ms > 220ms) -> suspect
        val eval1 = monitor.evaluate(
            sample = TelemetryYawSample(
                yawDegrees = 26,
                rawYawRateDegreesPerSecond = 60f,
                filteredYawRateDegreesPerSecond = 60f,
                receivedAtMillis = 1300L,
            ),
            flightState = FlightState.Flying,
            yawFollowState = YawFollowState.ACTIVE,
        )
        assertEquals(YawResponseSafetyStatus.MISMATCH_SUSPECT, eval1.status)

        // Sample 2 after braking grace (400ms > 220ms) -> latches ZERO_RUNAWAY
        val eval2 = monitor.evaluate(
            sample = TelemetryYawSample(
                yawDegrees = 32,
                rawYawRateDegreesPerSecond = 55f,
                filteredYawRateDegreesPerSecond = 55f,
                receivedAtMillis = 1400L,
            ),
            flightState = FlightState.Flying,
            yawFollowState = YawFollowState.ACTIVE,
        )
        assertEquals(YawResponseSafetyStatus.ANOMALY_LATCHED, eval2.status)
        assertEquals(YawResponseAnomalyReason.ZERO_RUNAWAY, eval2.anomalyReason)
    }

    @Test
    fun `sensor noise and small opposing rate below threshold stay normal`() {
        val monitor = YawResponseSafetyMonitor()

        // Commanded -8
        monitor.recordSentRc(sentAtMillis = 1000L, yawRc = -8)

        // Noise sample +15 deg/s (e.g. 1 deg quantization step over 100ms is 10 deg/s)
        val eval = monitor.evaluate(
            sample = TelemetryYawSample(
                yawDegrees = 5,
                rawYawRateDegreesPerSecond = 15f,
                filteredYawRateDegreesPerSecond = 15f,
                receivedAtMillis = 1100L,
            ),
            flightState = FlightState.Flying,
            yawFollowState = YawFollowState.ACTIVE,
        )
        assertEquals(YawResponseSafetyStatus.NORMAL, eval.status)
    }

    @Test
    fun `latched anomaly stays latched until reset`() {
        val monitor = YawResponseSafetyMonitor()
        monitor.recordSentRc(sentAtMillis = 1000L, yawRc = 0)

        // Trigger runaway
        monitor.evaluate(
            sample = TelemetryYawSample(yawDegrees = 10, rawYawRateDegreesPerSecond = 150f, filteredYawRateDegreesPerSecond = 150f, receivedAtMillis = 1100L),
            flightState = FlightState.Flying,
            yawFollowState = YawFollowState.ACTIVE,
        )

        // Subsequent zero/settled sample remains latched
        val eval = monitor.evaluate(
            sample = TelemetryYawSample(yawDegrees = 10, rawYawRateDegreesPerSecond = 0f, filteredYawRateDegreesPerSecond = 0f, receivedAtMillis = 1200L),
            flightState = FlightState.Flying,
            yawFollowState = YawFollowState.ACTIVE,
        )
        assertEquals(YawResponseSafetyStatus.ANOMALY_LATCHED, eval.status)

        // Reset clears latch
        monitor.reset()
        val evalAfterReset = monitor.evaluate(
            sample = TelemetryYawSample(yawDegrees = 10, rawYawRateDegreesPerSecond = 0f, filteredYawRateDegreesPerSecond = 0f, receivedAtMillis = 1300L),
            flightState = FlightState.Flying,
            yawFollowState = YawFollowState.ACTIVE,
        )
        assertEquals(YawResponseSafetyStatus.NORMAL, evalAfterReset.status)
    }
}
