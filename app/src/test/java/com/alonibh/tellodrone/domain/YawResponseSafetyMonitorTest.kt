package com.alonibh.tellodrone.domain

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertFalse(eval1.isJustLatched)

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
        assertTrue(eval2.isJustLatched)
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
        assertTrue(eval.isJustLatched)
        assertEquals(YawResponseAnomalyReason.CATASTROPHIC_YAW_RATE, eval.anomalyReason)
    }

    @Test
    fun `catastrophic raw spike latches even when filtered rate is still low`() {
        val monitor = YawResponseSafetyMonitor()
        monitor.recordSentRc(sentAtMillis = 1000L, yawRc = 8)

        // Raw physical rate spike of 200 deg/s (while median filter is still reporting 20 deg/s)
        val eval = monitor.evaluate(
            sample = TelemetryYawSample(
                yawDegrees = 50,
                rawYawRateDegreesPerSecond = 200f,
                filteredYawRateDegreesPerSecond = 20f,
                receivedAtMillis = 1100L,
            ),
            flightState = FlightState.Flying,
            yawFollowState = YawFollowState.ACTIVE,
        )
        assertEquals(YawResponseSafetyStatus.ANOMALY_LATCHED, eval.status)
        assertTrue(eval.isJustLatched)
        assertEquals(YawResponseAnomalyReason.CATASTROPHIC_YAW_RATE, eval.anomalyReason)
    }

    @Test
    fun `catastrophic same-direction runaway latches when raw rate exceeds 140 dps`() {
        val monitor = YawResponseSafetyMonitor()
        // Commanded mild positive yaw +8
        monitor.recordSentRc(sentAtMillis = 1000L, yawRc = 8)

        // Drone physically accelerates to +180 deg/s in same direction
        val eval = monitor.evaluate(
            sample = TelemetryYawSample(
                yawDegrees = 80,
                rawYawRateDegreesPerSecond = 180f,
                filteredYawRateDegreesPerSecond = 180f,
                receivedAtMillis = 1100L,
            ),
            flightState = FlightState.Flying,
            yawFollowState = YawFollowState.ACTIVE,
        )
        assertEquals(YawResponseSafetyStatus.ANOMALY_LATCHED, eval.status)
        assertTrue(eval.isJustLatched)
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
    fun `latched anomaly tracks settled condition and enables safe re-arm only when settled`() {
        val monitor = YawResponseSafetyMonitor()
        monitor.recordSentRc(sentAtMillis = 1000L, yawRc = 0)

        // Trigger anomaly latch
        val evalLatch = monitor.evaluate(
            sample = TelemetryYawSample(yawDegrees = 10, rawYawRateDegreesPerSecond = 150f, filteredYawRateDegreesPerSecond = 150f, receivedAtMillis = 1100L),
            flightState = FlightState.Flying,
            yawFollowState = YawFollowState.ACTIVE,
        )
        assertTrue(evalLatch.isJustLatched)
        assertTrue(monitor.isLatched())
        assertFalse(monitor.isRearmReady())

        // User attempts rearm while aircraft is still spinning (+40 deg/s) -> rejected!
        val evalRotating = monitor.evaluate(
            sample = TelemetryYawSample(yawDegrees = 20, rawYawRateDegreesPerSecond = 40f, filteredYawRateDegreesPerSecond = 40f, receivedAtMillis = 1200L),
            flightState = FlightState.Flying,
            yawFollowState = YawFollowState.ACTIVE,
        )
        assertFalse(evalRotating.rearmReady)
        assertFalse(monitor.tryAcknowledgeAndResetForRearm())
        assertTrue(monitor.isLatched())

        // Consecutive settled samples: <= 8.0 deg/s
        monitor.evaluate(
            sample = TelemetryYawSample(yawDegrees = 25, rawYawRateDegreesPerSecond = 5f, filteredYawRateDegreesPerSecond = 5f, receivedAtMillis = 1300L),
            flightState = FlightState.Flying,
            yawFollowState = YawFollowState.ACTIVE,
        )
        assertFalse(monitor.isRearmReady())

        monitor.evaluate(
            sample = TelemetryYawSample(yawDegrees = 25, rawYawRateDegreesPerSecond = 4f, filteredYawRateDegreesPerSecond = 4f, receivedAtMillis = 1400L),
            flightState = FlightState.Flying,
            yawFollowState = YawFollowState.ACTIVE,
        )
        assertFalse(monitor.isRearmReady())

        val evalSettled = monitor.evaluate(
            sample = TelemetryYawSample(yawDegrees = 25, rawYawRateDegreesPerSecond = 3f, filteredYawRateDegreesPerSecond = 3f, receivedAtMillis = 1500L),
            flightState = FlightState.Flying,
            yawFollowState = YawFollowState.ACTIVE,
        )
        assertTrue(evalSettled.rearmReady)
        assertTrue(monitor.isRearmReady())

        // Now rearm succeeds and cleanly resets monitor
        assertTrue(monitor.tryAcknowledgeAndResetForRearm())
        assertFalse(monitor.isLatched())
        assertFalse(monitor.isRearmReady())
    }

    @Test
    fun `concurrent multi-threaded execution is fully thread safe`() {
        val monitor = YawResponseSafetyMonitor()
        val executor = Executors.newFixedThreadPool(4)
        val errorCount = AtomicInteger(0)

        val iterations = 500
        val rcTask = Runnable {
            for (i in 0 until iterations) {
                try {
                    val rc = if (i % 2 == 0) 16 else -16
                    monitor.recordSentRc(sentAtMillis = 1000L + i * 20L, yawRc = rc)
                } catch (t: Throwable) {
                    errorCount.incrementAndGet()
                }
            }
        }

        val telemetryTask = Runnable {
            for (i in 0 until iterations) {
                try {
                    monitor.evaluate(
                        sample = TelemetryYawSample(
                            yawDegrees = (i * 2) % 360,
                            rawYawRateDegreesPerSecond = 30f,
                            filteredYawRateDegreesPerSecond = 30f,
                            receivedAtMillis = 1000L + i * 20L,
                        ),
                        flightState = FlightState.Flying,
                        yawFollowState = YawFollowState.ACTIVE,
                    )
                } catch (t: Throwable) {
                    errorCount.incrementAndGet()
                }
            }
        }

        val rearmTask = Runnable {
            for (i in 0 until iterations) {
                try {
                    monitor.isLatched()
                    monitor.isRearmReady()
                    if (i % 50 == 0) monitor.tryAcknowledgeAndResetForRearm()
                } catch (t: Throwable) {
                    errorCount.incrementAndGet()
                }
            }
        }

        executor.submit(rcTask)
        executor.submit(telemetryTask)
        executor.submit(rearmTask)
        executor.submit(rcTask)

        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(0, errorCount.get())
    }
}
