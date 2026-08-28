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

    @Test fun `normal sustained offsets produce smooth bounded yaw-only commands`() {
        val right = ProductionYawController()
        val rightYaw = (1L..5L).map { frame ->
            right.command(errors(.20f, frame), commandTime(frame)).safetyFilteredYawRc
        }
        val left = ProductionYawController()
        val leftYaw = (1L..5L).map { frame ->
            left.command(errors(-.20f, frame), commandTime(frame)).safetyFilteredYawRc
        }

        // error 0.20 -> requested = round(0.20 * 70) = 14
        // step 4 per frame: 4, 8, 12, 14, 14
        assertEquals(listOf(4, 8, 12, 14, 14), rightYaw)
        assertEquals(listOf(-4, -8, -12, -14, -14), leftYaw)
        (rightYaw + leftYaw).forEach { assertTrue(abs(it) <= ProductionYawController.ABSOLUTE_YAW_RC_CAP) }
        val command = right.command(errors(.20f, 6L), commandTime(6L)).command
        assertEquals(0, command.lateral)
        assertEquals(0, command.forward)
        assertEquals(0, command.vertical)
    }

    @Test fun `centered target produces zero`() {
        val outcome = ProductionYawController().command(errors(0f, 1L), commandTime(1L))
        assertEquals(0, outcome.safetyFilteredYawRc)
        assertEquals(YawControlSuppressionReason.NONE, outcome.suppressionReason)
    }

    @Test fun `perception older than Teclast budget produces zero`() {
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
        assertEquals(4, controller.command(errors(.08f, 1L), commandTime(1L)).safetyFilteredYawRc)

        val rejected = controller.command(errors(.30f, 2L), commandTime(2L))
        val firstStable = controller.command(errors(.29f, 3L), commandTime(3L))
        val secondStable = controller.command(errors(.28f, 4L), commandTime(4L))

        assertEquals(0, rejected.safetyFilteredYawRc)
        assertEquals(YawControlSuppressionReason.TARGET_JUMP_REJECTED, rejected.suppressionReason)
        assertEquals(YawControlSuppressionReason.STABLE_RESUME, firstStable.suppressionReason)
        assertEquals(0, firstStable.safetyFilteredYawRc)
        assertEquals(4, secondStable.safetyFilteredYawRc)
    }

    @Test fun `large error sign flip brakes for one frame then normal fresh measurement resumes without 2-frame penalty`() {
        val controller = ProductionYawController()
        controller.command(errors(.12f, 1L), commandTime(1L))
        controller.command(errors(.12f, 2L), commandTime(2L))

        val crossing = controller.command(errors(-.05f, 3L), commandTime(3L))

        assertEquals(0, crossing.safetyFilteredYawRc)
        assertTrue(crossing.requestedYawRc <= 0)
        assertEquals(YawControlSuppressionReason.CENTER_CROSSING_BRAKE, crossing.suppressionReason)

        // Frame 4: normal fresh measurement across center can resume from zero immediately using slew limiting
        val resumed = controller.command(errors(-.08f, 4L), commandTime(4L))
        assertEquals(YawControlSuppressionReason.NONE, resumed.suppressionReason)
        assertEquals(-4, resumed.safetyFilteredYawRc)
    }

    @Test fun `yaw output cannot jump beyond configured slew limit and never exceeds cap 16`() {
        val controller = ProductionYawController()
        val outputs = (1L..6L).map { controller.command(errors(.40f, it), commandTime(it)).safetyFilteredYawRc }
        outputs.zipWithNext().forEach { (previous, next) ->
            assertTrue(abs(next - previous) <= ProductionYawController.MAXIMUM_YAW_RC_STEP)
        }
        assertEquals(ProductionYawController.ABSOLUTE_YAW_RC_CAP, outputs.last())
        assertEquals(16, outputs.last())
    }

    @Test fun `temporary missing stops immediately and two plausible frames are required to resume`() {
        val gate = YawFollowGate()
        assertEquals(4, gate.arm(healthy(frame = 1L)).yawRc)

        val missing = gate.evaluate(
            healthy(
                frame = 2L,
                association = TargetAssociationState.TemporarilyMissing,
                fresh = false,
            ),
        )
        val first = gate.evaluate(healthy(frame = 3L))
        val second = gate.evaluate(healthy(frame = 4L))

        assertEquals(YawFollowState.ARMED_WAITING, missing.state)
        assertEquals(0, missing.yawRc)
        assertEquals(YawFollowState.ACTIVE, first.state)
        assertEquals(0, first.yawRc)
        assertEquals(YawControlSuppressionReason.STABLE_RESUME, first.control?.suppressionReason)
        assertEquals(4, second.yawRc)
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
            healthy(manualNeutral = false),
            healthy(hoverActive = true),
            healthy(flight = FlightState.Landing),
            healthy(flight = FlightState.Emergency),
            healthy(telemetryFresh = false),
            healthy(video = VideoAvailability.Error),
            healthy(detector = PersonDetectionState.Error),
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

    @Test fun `observable 92 and 134 second box sequences fail closed on discontinuity`() {
        val incident92 = ProductionYawController()
        incident92.command(errors(.08f, 1L), commandTime(1L))
        val event92 = incident92.command(errors(.28f, 2L), commandTime(2L))

        val incident134 = ProductionYawController()
        incident134.command(errors(.15f, 1L), commandTime(1L))
        incident134.command(errors(.18f, 2L), commandTime(2L))
        val event134 = incident134.command(errors(-.12f, 3L), commandTime(3L))

        assertEquals(YawControlSuppressionReason.TARGET_JUMP_REJECTED, event92.suppressionReason)
        assertEquals(0, event92.safetyFilteredYawRc)
        assertTrue(
            event134.suppressionReason in setOf(
                YawControlSuppressionReason.TARGET_JUMP_REJECTED,
                YawControlSuppressionReason.CENTER_CROSSING_BRAKE,
            ),
        )
        assertEquals(0, event134.safetyFilteredYawRc)
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
        connection = connection,
        flight = flight,
        telemetryFresh = telemetryFresh,
        video = video,
        detector = detector,
        targetPresent = targetPresent,
        association = association,
        errors = errors(.20f, frame, fresh),
        manualInputNeutral = manualNeutral,
        hoverActive = hoverActive,
        commandTimestampNanos = commandTime(frame),
    )

    private fun errors(yaw: Float, frame: Long, fresh: Boolean = true) = TrackingErrors(
        rawYawError = yaw,
        yawError = yaw,
        targetPresent = true,
        targetFresh = fresh,
        targetCenterX = .5f + yaw,
        measurementFrameSequence = frame,
        measurementSourceTimestampNanos = sourceTime(frame),
    )

    private fun sourceTime(frame: Long) = 1_000_000_000L + (frame - 1L) * 100L * NANOS_PER_MILLISECOND
    private fun commandTime(frame: Long) = sourceTime(frame) + 20L * NANOS_PER_MILLISECOND

    companion object { private const val NANOS_PER_MILLISECOND = 1_000_000L }
}
// SPDX-License-Identifier: AGPL-3.0-only
