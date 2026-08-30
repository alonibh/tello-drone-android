@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.alonibh.tellodrone.tello

import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.ProductionYawController
import com.alonibh.tellodrone.domain.RcSpeedMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RcControlLoopTest {
    @Test fun `manual axes use full SDK range and remain clamped`() = runTest {
        val clock = FakeClock(1_000)
        val loop = RcControlLoop(backgroundScope, {}, clock)
        loop.setEnabled(true)
        loop.setHealthy(true)
        loop.publish(ManualControlVector(lateral = 3f, forward = -2f, vertical = .5f, yaw = -.5f), RcSpeedMode.Fast.rcMagnitude)

        assertEquals(RcVector(100, -100, 50, -50), loop.currentVector())
    }

    @Test fun `every manual channel and sign scales at each pilot speed mode`() = runTest {
        val loop = RcControlLoop(backgroundScope, {}, FakeClock(1_000))
        loop.setEnabled(true)
        loop.setHealthy(true)

        RcSpeedMode.entries.forEach { mode ->
            val magnitude = mode.rcMagnitude
            loop.publish(ManualControlVector(1f, -1f, 1f, -1f), magnitude)
            assertEquals(RcVector(magnitude, -magnitude, magnitude, -magnitude), loop.currentVector())
            loop.publish(ManualControlVector(-1f, 1f, -1f, 1f), magnitude)
            assertEquals(RcVector(-magnitude, magnitude, -magnitude, magnitude), loop.currentVector())
        }
    }

    @Test fun `fast half stick is proportional and no manual channel exceeds SDK range`() = runTest {
        val loop = RcControlLoop(backgroundScope, {}, FakeClock(1_000))
        loop.setEnabled(true)
        loop.setHealthy(true)

        loop.publish(ManualControlVector(.5f, -.5f, .5f, -.5f), RcSpeedMode.Fast.rcMagnitude)
        assertEquals(RcVector(50, -50, 50, -50), loop.currentVector())
        loop.publish(ManualControlVector(Float.MAX_VALUE, -Float.MAX_VALUE, 2f, -2f), Int.MAX_VALUE)
        assertEquals(RcVector(100, -100, 100, -100), loop.currentVector())
    }

    @Test fun `stale desired input becomes zero movement`() = runTest {
        val clock = FakeClock(1_000)
        val loop = RcControlLoop(backgroundScope, {}, clock, inputTtlMillis = 250)
        loop.setEnabled(true)
        loop.setHealthy(true)
        loop.publish(ManualControlVector(forward = 1f), 20)
        assertEquals(RcVector(forward = 20), loop.currentVector())

        clock.value = 1_251
        assertEquals(RcVector.Zero, loop.currentVector())
    }

    @Test fun `desired input expires at the TTL boundary`() = runTest {
        val clock = FakeClock(1_000)
        val loop = RcControlLoop(backgroundScope, {}, clock, inputTtlMillis = 250)
        loop.setEnabled(true)
        loop.setHealthy(true)
        loop.publish(ManualControlVector(forward = 1f), 20)

        clock.value = 1_250
        assertEquals(RcVector.Zero, loop.currentVector())
    }

    @Test fun `joystick release publishes immediate zero`() = runTest {
        val sent = mutableListOf<RcVector>()
        val clock = FakeClock(1_000)
        val loop = RcControlLoop(backgroundScope, { sent += it }, clock)
        loop.setEnabled(true)
        loop.setHealthy(true)
        loop.publish(ManualControlVector(yaw = 1f), 20)
        loop.sendCycle()
        loop.publish(ManualControlVector(), 20)
        loop.sendCycle()

        assertEquals(RcVector(yaw = 20), sent.first())
        assertEquals(RcVector.Zero, sent.last())
    }

    @Test fun `safety zero cannot be overtaken by a prepared nonzero send`() = runTest {
        val clock = FakeClock(1_000)
        val sent = mutableListOf<RcVector>()
        val firstSendStarted = CompletableDeferred<Unit>()
        val releaseFirstSend = CompletableDeferred<Unit>()
        val loop = RcControlLoop(backgroundScope, { vector ->
            sent += vector
            if (vector != RcVector.Zero && !firstSendStarted.isCompleted) {
                firstSendStarted.complete(Unit)
                releaseFirstSend.await()
            }
        }, clock)
        loop.setEnabled(true)
        loop.setHealthy(true)
        loop.publish(ManualControlVector(forward = 1f), 20)

        val cycle = async { loop.sendCycle() }
        firstSendStarted.await()
        val stop = async { loop.clearAndSendZero() }
        runCurrent()
        assertEquals(listOf(RcVector(forward = 20)), sent)

        releaseFirstSend.complete(Unit)
        cycle.await()
        stop.await()
        assertEquals(listOf(RcVector(forward = 20), RcVector.Zero), sent)
    }

    @Test fun `manual preemption invalidates autonomous publisher generation`() = runTest {
        val clock = FakeClock(1_000)
        val loop = RcControlLoop(backgroundScope, {}, clock)
        loop.setEnabled(true)
        loop.setHealthy(true)
        val generation = loop.beginAutonomousYaw()
        loop.publishAutonomousYaw(12, generation)
        assertEquals(RcVector(yaw = 12), loop.currentVector())

        loop.publish(ManualControlVector(forward = .5f), 20)
        loop.publishAutonomousYaw(-20, generation)

        assertEquals(RcVector(forward = 10), loop.currentVector())
    }

    @Test fun `autonomous yaw is capped with all other axes zero`() = runTest {
        val loop = RcControlLoop(backgroundScope, {}, FakeClock(1_000))
        loop.setEnabled(true)
        loop.setHealthy(true)
        val generation = loop.beginAutonomousYaw()

        loop.publishAutonomousYaw(99, generation)
        assertEquals(RcVector(yaw = ProductionYawController.ABSOLUTE_YAW_RC_CAP), loop.currentVector())

        loop.publishAutonomousYaw(-99, generation)
        assertEquals(RcVector(yaw = -ProductionYawController.ABSOLUTE_YAW_RC_CAP), loop.currentVector())
    }

    @Test fun `autonomous perception validity expires independently before RC TTL`() = runTest {
        val clock = FakeClock(1_000)
        val sent = mutableListOf<RcVector>()
        val publications = mutableListOf<RcPublication>()
        val loop = RcControlLoop(
            backgroundScope,
            { sent += it },
            clock,
            inputTtlMillis = 250,
            onRcSent = { publications += it },
        )
        loop.setEnabled(true)
        loop.setHealthy(true)
        val generation = loop.beginAutonomousYaw()
        loop.publishAutonomousYaw(9, generation, validForMillis = 75)

        clock.value = 1_074
        assertEquals(RcVector(yaw = 9), loop.currentVector())
        clock.value = 1_075
        assertEquals(RcVector.Zero, loop.currentVector())
        loop.sendCycle()
        assertEquals(RcVector.Zero, sent.single())
        assertEquals(RcSendSuppressionReason.PERCEPTION_AGE_EXPIRED, publications.single().suppressionReason)
    }

    @Test fun `autonomous command hold expiry is separately identified and send timestamps are ordered`() = runTest {
        val clock = FakeClock(1_000)
        var traceNow = 10_000L
        val publications = mutableListOf<RcPublication>()
        val loop = RcControlLoop(
            scope = backgroundScope,
            sender = { traceNow += 10L },
            clock = clock,
            inputTtlMillis = 250,
            traceClockNanos = { traceNow += 10L; traceNow },
            onRcSent = { publications += it },
        )
        loop.setEnabled(true)
        loop.setHealthy(true)
        val generation = loop.beginAutonomousYaw()
        loop.publishAutonomousYaw(
            yawRc = 8,
            generation = generation,
            validForMillis = 110L,
            validityExpiryReason = RcSendSuppressionReason.AUTONOMOUS_COMMAND_HOLD_EXPIRED,
        )
        clock.value = 1_110L

        loop.sendCycle()

        val publication = publications.single()
        assertEquals(RcVector.Zero, publication.actualVector)
        assertEquals(RcSendSuppressionReason.AUTONOMOUS_COMMAND_HOLD_EXPIRED, publication.suppressionReason)
        assertTrue(publication.desiredPublishedAtNanos <= publication.sendStartedAtNanos)
        assertTrue(publication.sendStartedAtNanos <= publication.sentAtNanos)
    }

    @Test fun `every physically published autonomous vector is structurally yaw only`() = runTest {
        val publications = mutableListOf<RcPublication>()
        val loop = RcControlLoop(
            scope = backgroundScope,
            sender = {},
            clock = FakeClock(1_000),
            onRcSent = { publications += it },
        )
        loop.setEnabled(true)
        loop.setHealthy(true)
        val generation = loop.beginAutonomousYaw()

        loop.publishAutonomousYaw(12, generation)
        loop.sendCycle()

        val sent = publications.single()
        assertEquals(RcInputKind.AUTONOMOUS_YAW, sent.inputKind)
        assertEquals(0, sent.actualVector.lateral)
        assertEquals(0, sent.actualVector.forward)
        assertEquals(0, sent.actualVector.vertical)
        assertEquals(12, sent.actualVector.yaw)
    }

    @Test fun `delayed safety zero cannot overwrite newer manual command`() = runTest {
        val sent = mutableListOf<RcVector>()
        val clock = FakeClock(1_000)
        val loop = RcControlLoop(backgroundScope, { sent += it }, clock)
        loop.setEnabled(true)
        loop.setHealthy(true)
        val generation = loop.beginAutonomousYaw()
        loop.publishAutonomousYaw(8, generation)
        val preemption = loop.preemptAutonomy()

        loop.publish(ManualControlVector(yaw = 1f), 20)
        loop.sendZeroIfCurrent(preemption)
        loop.sendCycle()

        assertEquals(listOf(RcVector(yaw = 20)), sent)
    }

    @Test fun `enableForNewFlight atomically resets desired vector to safety zero and starts fresh epoch`() = runTest {
        val sent = mutableListOf<RcVector>()
        val publications = mutableListOf<RcPublication>()
        val clock = FakeClock(1_000)
        val loop = RcControlLoop(
            scope = backgroundScope,
            sender = { sent += it },
            clock = clock,
            onRcSent = { publications += it },
        )
        // Simulate previous flight with manual input
        loop.setEnabled(true)
        loop.setHealthy(true)
        loop.publish(ManualControlVector(yaw = 1f), 30)
        assertEquals(RcVector(yaw = 30), loop.currentVector())

        // Arm for new flight
        val newEpoch = loop.enableForNewFlight()
        assertTrue(newEpoch > 0L)
        assertEquals(RcVector.Zero, loop.currentVector())

        loop.sendCycle()
        assertEquals(listOf(RcVector.Zero), sent)
        val pub = publications.single()
        assertEquals(RcInputKind.SAFETY_ZERO, pub.inputKind)
        assertEquals(RcVector.Zero, pub.actualVector)
        assertEquals(newEpoch, pub.flightEpoch)
    }

    @Test fun `stale flight epoch command cannot produce non-zero output`() = runTest {
        val publications = mutableListOf<RcPublication>()
        val clock = FakeClock(1_000)
        val loop = RcControlLoop(
            scope = backgroundScope,
            sender = {},
            clock = clock,
            onRcSent = { publications += it },
        )
        loop.enableForNewFlight()
        loop.publish(ManualControlVector(forward = 1f), 50)
        assertEquals(RcVector(forward = 50), loop.currentVector())

        // New flight epoch arrives without new manual input
        val epoch2 = loop.enableForNewFlight()
        assertTrue(epoch2 > 1L)
        assertEquals(RcVector.Zero, loop.currentVector())

        loop.sendCycle()
        val pub = publications.last()
        assertEquals(RcVector.Zero, pub.actualVector)
        assertEquals(RcInputKind.SAFETY_ZERO, pub.inputKind)
    }

    @Test fun `authority validator suppressing autonomous yaw enforces zero actual vector`() = runTest {
        val physicallySent = mutableListOf<RcVector>()
        val publications = mutableListOf<RcPublication>()
        val clock = FakeClock(1_000)
        var allowAutonomous = true
        val loop = RcControlLoop(
            scope = backgroundScope,
            sender = { physicallySent += it },
            clock = clock,
            onRcSent = { publications += it },
            authorityValidator = { kind, _ ->
                if (kind == RcInputKind.AUTONOMOUS_YAW && !allowAutonomous) {
                    RcSendSuppressionReason.TRACKING_INACTIVE
                } else null
            },
        )
        loop.enableForNewFlight()
        val gen = loop.beginAutonomousYaw()
        loop.publishAutonomousYaw(15, gen)
        assertEquals(RcVector(yaw = 15), loop.currentVector())

        // Perception/session safety changes before ordinary yaw-gate reconciliation can publish zero.
        allowAutonomous = false

        loop.sendCycle()
        val pub = publications.single()
        assertEquals(listOf(RcVector.Zero), physicallySent)
        assertEquals(RcVector.Zero, pub.actualVector)
        assertEquals(RcSendSuppressionReason.TRACKING_INACTIVE, pub.suppressionReason)
    }

    @Test fun `send cycle in-flight blocks anomaly commit until completion then anomaly commits zero`() = runTest {
        val physicallySent = mutableListOf<RcVector>()
        val publications = mutableListOf<RcPublication>()
        val clock = FakeClock(1_000)
        var traceNowNanos = 10_000_000L
        val monitor = com.alonibh.tellodrone.domain.YawResponseSafetyMonitor()
        val loop = RcControlLoop(
            scope = backgroundScope,
            sender = { physicallySent += it },
            clock = clock,
            traceClockNanos = { traceNowNanos },
            onRcSent = { publications += it },
            authorityValidator = { kind, _ ->
                if (kind == RcInputKind.AUTONOMOUS_YAW && monitor.isLatched()) {
                    RcSendSuppressionReason.YAW_RESPONSE_ANOMALY
                } else null
            },
        )
        loop.enableForNewFlight()
        val gen = loop.beginAutonomousYaw()
        loop.publishAutonomousYaw(-24, gen)

        val beforeSenderReached = CompletableDeferred<Unit>()
        val releaseSender = CompletableDeferred<Unit>()
        loop.beforeSenderHook = { vector ->
            if (vector.yaw == -24) {
                beforeSenderReached.complete(Unit)
                releaseSender.await()
            }
        }

        val sendCycleJob = async { loop.sendCycle() }
        beforeSenderReached.await()

        var anomalyCommitted = false
        val anomalyFenceJob = async {
            loop.fenceAndCommitAnomaly { startedAtNanos ->
                traceNowNanos = 20_000_000L
                monitor.commitPhysicalLatch(
                    anomalyReason = com.alonibh.tellodrone.domain.YawResponseAnomalyReason.SUSTAINED_DIRECTION_MISMATCH,
                    reason = "Sustained mismatch",
                    dominantRc = -24,
                    timestampMillis = 1000L,
                    committedAtNanos = traceNowNanos,
                )
                anomalyCommitted = true
                traceNowNanos
            }
        }
        runCurrent()
        org.junit.Assert.assertFalse(anomalyCommitted)
        org.junit.Assert.assertFalse(monitor.isLatched())

        traceNowNanos = 15_000_000L
        releaseSender.complete(Unit)
        sendCycleJob.await()

        val fenceResult = anomalyFenceJob.await()
        assertTrue(anomalyCommitted)
        assertTrue(monitor.isLatched())
        assertEquals(20_000_000L, fenceResult.committedAtNanos)

        assertEquals(listOf(RcVector(yaw = -24), RcVector.Zero), physicallySent)
        val firstPub = publications[0]
        assertEquals(RcVector(yaw = -24), firstPub.actualVector)
        assertTrue(firstPub.sentAtNanos < fenceResult.committedAtNanos)

        val gen2 = loop.beginAutonomousYaw()
        loop.publishAutonomousYaw(-24, gen2)
        loop.sendCycle()
        assertEquals(listOf(RcVector(yaw = -24), RcVector.Zero, RcVector.Zero), physicallySent)
        assertEquals(RcVector.Zero, publications.last().actualVector)
        assertEquals(RcSendSuppressionReason.YAW_RESPONSE_ANOMALY, publications.last().suppressionReason)
    }

    @Test fun `anomaly fence commits before sendCycle forcing subsequent autonomous send to zero`() = runTest {
        val physicallySent = mutableListOf<RcVector>()
        val publications = mutableListOf<RcPublication>()
        val clock = FakeClock(1_000)
        var traceNowNanos = 10_000_000L
        val monitor = com.alonibh.tellodrone.domain.YawResponseSafetyMonitor()
        val loop = RcControlLoop(
            scope = backgroundScope,
            sender = { physicallySent += it },
            clock = clock,
            traceClockNanos = { traceNowNanos },
            onRcSent = { publications += it },
            authorityValidator = { kind, _ ->
                if (kind == RcInputKind.AUTONOMOUS_YAW && monitor.isLatched()) {
                    RcSendSuppressionReason.YAW_RESPONSE_ANOMALY
                } else null
            },
        )
        loop.enableForNewFlight()
        val gen = loop.beginAutonomousYaw()
        loop.publishAutonomousYaw(-24, gen)

        traceNowNanos = 15_000_000L
        val fenceResult = loop.fenceAndCommitAnomaly { startedAtNanos ->
            traceNowNanos = 16_000_000L
            monitor.commitPhysicalLatch(
                anomalyReason = com.alonibh.tellodrone.domain.YawResponseAnomalyReason.SUSTAINED_DIRECTION_MISMATCH,
                reason = "Sustained mismatch",
                dominantRc = -24,
                timestampMillis = 1000L,
                committedAtNanos = traceNowNanos,
            )
            traceNowNanos
        }
        assertTrue(monitor.isLatched())
        assertEquals(listOf(RcVector.Zero), physicallySent)

        val gen2 = loop.beginAutonomousYaw()
        loop.publishAutonomousYaw(-24, gen2)
        traceNowNanos = 20_000_000L
        loop.sendCycle()

        assertEquals(listOf(RcVector.Zero, RcVector.Zero), physicallySent)
        val secondPub = publications.last()
        assertEquals(RcVector.Zero, secondPub.actualVector)
        assertEquals(RcSendSuppressionReason.YAW_RESPONSE_ANOMALY, secondPub.suppressionReason)
        assertTrue(secondPub.sendStartedAtNanos > fenceResult.committedAtNanos)
    }

    internal class FakeClock(var value: Long) : MonotonicClock { override fun nowMillis() = value }
}
// SPDX-License-Identifier: AGPL-3.0-only
