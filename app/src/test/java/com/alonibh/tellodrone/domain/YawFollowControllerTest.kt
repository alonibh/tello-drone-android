package com.alonibh.tellodrone.domain

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YawFollowControllerTest {
    @Test fun `grounded stale and targetless inputs cannot activate`() {
        assertEquals(YawFollowState.ARMED_WAITING, YawFollowGate().arm(healthy(flight = FlightState.Grounded)).state)
        assertEquals(YawFollowState.REQUIRES_REARM, YawFollowGate().arm(healthy(telemetryFresh = false)).state)
        assertEquals(YawFollowState.ARMED_WAITING, YawFollowGate().arm(healthy(targetPresent = false)).state)
    }

    @Test fun `stationary centered detector jitter remains continuously zero`() {
        val controller = ProductionYawController()
        val jitter = listOf(0f, .009f, -.011f, .006f, -.004f, .012f, -.010f, 0f)
        val outputs = jitter.mapIndexed { index, error ->
            controller.command(errors(error, index + 1L), commandTime(index + 1L)).safetyFilteredYawRc
        }
        assertTrue(outputs.all { it == 0 })
    }

    @Test fun `decisive displacement responds on first fresh measurement and accelerates faster than old ramp`() {
        val controller = ProductionYawController()
        assertEquals(0, controller.command(errors(0f, 1L), commandTime(1L)).safetyFilteredYawRc)
        val first = controller.command(errors(.16f, 2L), commandTime(2L))
        val second = controller.command(errors(.20f, 3L), commandTime(3L))
        assertEquals(ProductionYawController.MAXIMUM_ACCELERATION_STEP, first.safetyFilteredYawRc)
        assertTrue(first.safetyFilteredYawRc > 4)
        assertTrue(second.safetyFilteredYawRc > first.safetyFilteredYawRc)
        assertTrue(second.safetyFilteredYawRc <= ProductionYawController.ABSOLUTE_YAW_RC_CAP)
    }

    @Test fun `sustained offsets use scheduled gain and independent cap with yaw-only shape`() {
        val controller = ProductionYawController()
        val outputs = (1L..5L).map { controller.command(errors(.40f, it), commandTime(it)).safetyFilteredYawRc }
        assertEquals(listOf(8, 16, 24, 28, 28), outputs)
        assertEquals(28, ProductionYawController.ABSOLUTE_YAW_RC_CAP)
        val command = controller.command(errors(.40f, 6L), commandTime(6L)).command
        assertEquals(0, command.lateral)
        assertEquals(0, command.forward)
        assertEquals(0, command.vertical)
        assertEquals(28, command.yaw)
    }

    @Test fun `fresh centered measurement brakes immediately without EMA tail`() {
        val controller = ProductionYawController()
        controller.command(errors(.25f, 1L), commandTime(1L))
        controller.command(errors(.20f, 2L), commandTime(2L))
        val centered = controller.command(errors(.03f, 3L), commandTime(3L))
        assertEquals(0, centered.safetyFilteredYawRc)
        assertEquals(0, centered.requestedYawRc)
        assertEquals(YawControlSuppressionReason.NONE, centered.suppressionReason)
    }

    @Test fun `target stopping off center continues correction until actually centered`() {
        val controller = ProductionYawController()
        val outputs = (1L..4L).map { controller.command(errors(.14f, it), commandTime(it)).safetyFilteredYawRc }
        assertTrue(outputs.all { it > 0 })
        assertEquals(0, controller.command(errors(.02f, 5L), commandTime(5L)).safetyFilteredYawRc)
    }

    @Test fun `same nonzero measurement expires after short hold rather than global stale age`() {
        val controller = ProductionYawController()
        val first = controller.command(errors(.20f, 1L), commandTime(1L))
        val before = controller.command(errors(.20f, 1L), commandTime(1L) + (ProductionYawController.MAXIMUM_NONZERO_COMMAND_HOLD_MILLIS - 1L) * NANOS_PER_MILLISECOND)
        val expired = controller.command(errors(.20f, 1L), commandTime(1L) + ProductionYawController.MAXIMUM_NONZERO_COMMAND_HOLD_MILLIS * NANOS_PER_MILLISECOND)
        assertTrue(first.safetyFilteredYawRc != 0)
        assertEquals(first.safetyFilteredYawRc, before.safetyFilteredYawRc)
        assertEquals(0, expired.safetyFilteredYawRc)
        assertEquals(YawControlSuppressionReason.NONZERO_COMMAND_HOLD_EXPIRED, expired.suppressionReason)
        assertTrue(ProductionYawController.MAXIMUM_NONZERO_COMMAND_HOLD_MILLIS < ProductionYawController.MAXIMUM_PERCEPTION_AGE_MILLIS)
    }

    @Test fun `physical validity is limited by nonzero command hold`() {
        val outcome = ProductionYawController().command(errors(.20f, 1L), commandTime(1L))
        assertEquals(ProductionYawController.MAXIMUM_NONZERO_COMMAND_HOLD_MILLIS, outcome.validForMillis)
        assertTrue(outcome.validityLimitedByCommandHold)
    }

    @Test fun `detector cadence slower than command hold maintains progression 8 to 16 to 24 to 28`() {
        val controller = ProductionYawController()
        val intervalMillis = 180L // Slower than 170ms command hold
        val first = controller.command(errors(.40f, 1L, intervalMillis = intervalMillis), commandTime(1L, intervalMillis))
        assertEquals(8, first.safetyFilteredYawRc)

        // Between frames, command hold expires physically
        val expired1 = controller.command(errors(.40f, 1L, intervalMillis = intervalMillis), commandTime(1L, intervalMillis) + 175L * NANOS_PER_MILLISECOND)
        assertEquals(0, expired1.safetyFilteredYawRc)
        assertEquals(YawControlSuppressionReason.NONZERO_COMMAND_HOLD_EXPIRED, expired1.suppressionReason)

        // Next fresh frame at 180ms advances to 16
        val second = controller.command(errors(.40f, 2L, intervalMillis = intervalMillis), commandTime(2L, intervalMillis))
        assertEquals(16, second.safetyFilteredYawRc)

        // Expired again before frame 3
        val expired2 = controller.command(errors(.40f, 2L, intervalMillis = intervalMillis), commandTime(2L, intervalMillis) + 175L * NANOS_PER_MILLISECOND)
        assertEquals(0, expired2.safetyFilteredYawRc)

        // Next fresh frame at 360ms advances to 24
        val third = controller.command(errors(.40f, 3L, intervalMillis = intervalMillis), commandTime(3L, intervalMillis))
        assertEquals(24, third.safetyFilteredYawRc)

        // Next fresh frame at 540ms advances to 28 (cap)
        val fourth = controller.command(errors(.40f, 4L, intervalMillis = intervalMillis), commandTime(4L, intervalMillis))
        assertEquals(28, fourth.safetyFilteredYawRc)
    }

    @Test fun `next fresh same-direction frame resumes controller progression after hold expiry`() {
        val controller = ProductionYawController()
        val intervalMillis = 175L
        val first = controller.command(errors(.30f, 1L, intervalMillis = intervalMillis), commandTime(1L, intervalMillis))
        assertEquals(8, first.safetyFilteredYawRc)

        val expired = controller.command(errors(.30f, 1L, intervalMillis = intervalMillis), commandTime(1L, intervalMillis) + 172L * NANOS_PER_MILLISECOND)
        assertEquals(0, expired.safetyFilteredYawRc)
        assertEquals(YawControlSuppressionReason.NONZERO_COMMAND_HOLD_EXPIRED, expired.suppressionReason)

        val second = controller.command(errors(.30f, 2L, intervalMillis = intervalMillis), commandTime(2L, intervalMillis))
        assertEquals(16, second.safetyFilteredYawRc)
    }

    @Test fun `centered frame overrides preserved state immediately after hold expiry`() {
        val controller = ProductionYawController()
        val intervalMillis = 175L
        val first = controller.command(errors(.15f, 1L, intervalMillis = intervalMillis), commandTime(1L, intervalMillis))
        assertEquals(8, first.safetyFilteredYawRc)

        val expired = controller.command(errors(.15f, 1L, intervalMillis = intervalMillis), commandTime(1L, intervalMillis) + 172L * NANOS_PER_MILLISECOND)
        assertEquals(0, expired.safetyFilteredYawRc)

        val centered = controller.command(errors(.02f, 2L, intervalMillis = intervalMillis), commandTime(2L, intervalMillis))
        assertEquals(0, centered.safetyFilteredYawRc)
        assertEquals(0, centered.requestedYawRc)
        assertEquals(YawControlSuppressionReason.NONE, centered.suppressionReason)
    }

    @Test fun `direction crossing after hold expiry brakes to zero before reversing`() {
        val controller = ProductionYawController()
        val intervalMillis = 175L
        val first = controller.command(errors(.12f, 1L, intervalMillis = intervalMillis), commandTime(1L, intervalMillis))
        assertEquals(8, first.safetyFilteredYawRc)

        val expired = controller.command(errors(.12f, 1L, intervalMillis = intervalMillis), commandTime(1L, intervalMillis) + 172L * NANOS_PER_MILLISECOND)
        assertEquals(0, expired.safetyFilteredYawRc)

        // Crossing to opposite side within anti-jump gate (.12 to -.05 is delta .17 <= .18) brakes to zero
        val crossing = controller.command(errors(-.05f, 2L, intervalMillis = intervalMillis), commandTime(2L, intervalMillis))
        assertEquals(0, crossing.safetyFilteredYawRc)
        assertEquals(YawControlSuppressionReason.CENTER_CROSSING_BRAKE, crossing.suppressionReason)

        // Subsequent fresh frame begins reverse rotation
        val reversed = controller.command(errors(-.09f, 3L, intervalMillis = intervalMillis), commandTime(3L, intervalMillis))
        assertTrue(reversed.safetyFilteredYawRc < 0)
        assertTrue(abs(reversed.safetyFilteredYawRc) <= ProductionYawController.MAXIMUM_ACCELERATION_STEP)
    }

    @Test fun `safety interventions and loss still reset controller progression`() {
        val gate = YawFollowGate()
        val armed = gate.arm(healthy(frame = 1L))
        assertEquals(8, armed.yawRc)

        // Advance progression to 16
        val second = gate.evaluate(healthy(frame = 2L))
        assertEquals(16, second.yawRc)

        // Manual override intervention latches REQUIRES_REARM and resets controller
        val overridden = gate.evaluate(healthy(frame = 3L, manualNeutral = false))
        assertEquals(YawFollowState.REQUIRES_REARM, overridden.state)
        assertEquals(0, overridden.yawRc)

        // Re-arm after safety intervention restarts from 8, not 16 or 24
        val rearmed = gate.arm(healthy(frame = 4L))
        assertEquals(YawFollowState.ACTIVE, rearmed.state)
        assertEquals(8, rearmed.yawRc)
    }

    @Test fun `perception older than global budget produces zero`() {
        val timestamp = sourceTime(1L)
        val outcome = ProductionYawController().command(
            errors(.30f, 1L),
            timestamp + (ProductionYawController.MAXIMUM_PERCEPTION_AGE_MILLIS + 1L) * NANOS_PER_MILLISECOND,
        )
        assertEquals(0, outcome.safetyFilteredYawRc)
        assertEquals(YawControlSuppressionReason.STALE_PERCEPTION, outcome.suppressionReason)
        assertEquals(0L, outcome.validForMillis)
    }

    @Test fun `sudden target geometry jump brakes and requires stable measurements`() {
        val controller = ProductionYawController()
        assertTrue(controller.command(errors(.08f, 1L), commandTime(1L)).safetyFilteredYawRc > 0)
        val rejected = controller.command(errors(.30f, 2L), commandTime(2L))
        val firstStable = controller.command(errors(.29f, 3L), commandTime(3L))
        val secondStable = controller.command(errors(.28f, 4L), commandTime(4L))
        assertEquals(YawControlSuppressionReason.TARGET_JUMP_REJECTED, rejected.suppressionReason)
        assertEquals(0, rejected.safetyFilteredYawRc)
        assertEquals(YawControlSuppressionReason.STABLE_RESUME, firstStable.suppressionReason)
        assertEquals(0, firstStable.safetyFilteredYawRc)
        assertTrue(secondStable.safetyFilteredYawRc > 4)
    }

    @Test fun `center crossing brakes before a later measurement reverses`() {
        val controller = ProductionYawController()
        controller.command(errors(.12f, 1L), commandTime(1L))
        controller.command(errors(.12f, 2L), commandTime(2L))
        val crossing = controller.command(errors(-.05f, 3L), commandTime(3L))
        val resumed = controller.command(errors(-.09f, 4L), commandTime(4L))
        assertEquals(0, crossing.safetyFilteredYawRc)
        assertEquals(YawControlSuppressionReason.CENTER_CROSSING_BRAKE, crossing.suppressionReason)
        assertTrue(resumed.safetyFilteredYawRc < 0)
        assertTrue(abs(resumed.safetyFilteredYawRc) <= ProductionYawController.MAXIMUM_ACCELERATION_STEP)
    }

    @Test fun `temporary missing stops immediately and requires two plausible frames to resume`() {
        val gate = YawFollowGate()
        assertTrue(gate.arm(healthy(frame = 1L)).yawRc > 0)
        val missing = gate.evaluate(healthy(frame = 2L, association = TargetAssociationState.TemporarilyMissing, fresh = false))
        val first = gate.evaluate(healthy(frame = 3L))
        val second = gate.evaluate(healthy(frame = 4L))
        assertEquals(YawFollowState.ARMED_WAITING, missing.state)
        assertEquals(0, missing.yawRc)
        assertEquals(0, first.yawRc)
        assertEquals(YawControlSuppressionReason.STABLE_RESUME, first.control?.suppressionReason)
        assertTrue(second.yawRc > 0)
    }

    @Test fun `ambiguous and lost targets retain explicit rearm latch`() {
        listOf(TargetAssociationState.Ambiguous, TargetAssociationState.Lost).forEach { association ->
            val gate = YawFollowGate()
            assertEquals(YawFollowState.ACTIVE, gate.arm(healthy()).state)
            val latched = gate.evaluate(healthy(frame = 2L, association = association))
            assertEquals(YawFollowState.REQUIRES_REARM, latched.state)
            assertTrue(latched.requiresExplicitRearm)
            assertFalse(gate.evaluate(healthy(frame = 3L)).state == YawFollowState.ACTIVE)
            assertEquals(YawFollowState.ACTIVE, gate.arm(healthy(frame = 4L)).state)
        }
    }

    @Test fun `manual hover landing emergency and health losses retain priority`() {
        val unsafe = listOf(
            healthy(manualNeutral = false), healthy(hoverActive = true), healthy(flight = FlightState.Landing),
            healthy(flight = FlightState.Emergency), healthy(telemetryFresh = false),
            healthy(video = VideoAvailability.Error), healthy(detector = PersonDetectionState.Error),
            healthy(connection = DroneConnectionState.Error),
        )
        unsafe.forEach { input ->
            val gate = YawFollowGate()
            gate.arm(healthy())
            val decision = gate.evaluate(input)
            assertEquals(YawFollowState.REQUIRES_REARM, decision.state)
            assertEquals(0, decision.yawRc)
        }
    }

    private fun healthy(
        connection: DroneConnectionState = DroneConnectionState.Connected,
        flight: FlightState = FlightState.Flying,
        telemetryFresh: Boolean = true,
        video: VideoAvailability = VideoAvailability.Streaming,
        detector: PersonDetectionState = PersonDetectionState.Detecting,
        targetPresent: Boolean = true,
        association: TargetAssociationState = TargetAssociationState.Matched,
        manualNeutral: Boolean = true,
        hoverActive: Boolean = false,
        frame: Long = 1L,
        fresh: Boolean = true,
    ) = YawFollowInput(
        connection, flight, telemetryFresh, video, detector, targetPresent, association,
        errors(.20f, frame, fresh), manualNeutral, hoverActive, commandTime(frame),
    )

    private fun errors(
        yaw: Float,
        frame: Long,
        fresh: Boolean = true,
        intervalMillis: Long = 100L,
    ) = TrackingErrors(
        rawYawError = yaw,
        yawError = yaw,
        targetPresent = true,
        targetFresh = fresh,
        targetCenterX = .5f + yaw,
        measurementFrameSequence = frame,
        measurementSourceTimestampNanos = sourceTime(frame, intervalMillis),
    )

    private fun sourceTime(frame: Long, intervalMillis: Long = 100L) =
        1_000_000_000L + (frame - 1L) * intervalMillis * NANOS_PER_MILLISECOND
    private fun commandTime(frame: Long, intervalMillis: Long = 100L) =
        sourceTime(frame, intervalMillis) + 20L * NANOS_PER_MILLISECOND

    companion object { private const val NANOS_PER_MILLISECOND = 1_000_000L }
}
// SPDX-License-Identifier: AGPL-3.0-only
