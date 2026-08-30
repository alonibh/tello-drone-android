package com.alonibh.tellodrone.domain

import com.alonibh.tellodrone.tello.calculateYawRateDegreesPerSecond
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
        val intervalMillis = 220L // Slower than 200ms command hold
        val first = controller.command(errors(.40f, 1L, intervalMillis = intervalMillis), commandTime(1L, intervalMillis))
        assertEquals(8, first.safetyFilteredYawRc)

        // Between frames, command hold expires physically
        val expired1 = controller.command(errors(.40f, 1L, intervalMillis = intervalMillis), commandTime(1L, intervalMillis) + 205L * NANOS_PER_MILLISECOND)
        assertEquals(0, expired1.safetyFilteredYawRc)
        assertEquals(YawControlSuppressionReason.NONZERO_COMMAND_HOLD_EXPIRED, expired1.suppressionReason)

        // Next fresh frame at 220ms advances to 16
        val second = controller.command(errors(.40f, 2L, intervalMillis = intervalMillis), commandTime(2L, intervalMillis))
        assertEquals(16, second.safetyFilteredYawRc)

        // Expired again before frame 3
        val expired2 = controller.command(errors(.40f, 2L, intervalMillis = intervalMillis), commandTime(2L, intervalMillis) + 205L * NANOS_PER_MILLISECOND)
        assertEquals(0, expired2.safetyFilteredYawRc)

        // Next fresh frame at 440ms advances to 24
        val third = controller.command(errors(.40f, 3L, intervalMillis = intervalMillis), commandTime(3L, intervalMillis))
        assertEquals(24, third.safetyFilteredYawRc)

        // Next fresh frame at 660ms advances to 28 (cap)
        val fourth = controller.command(errors(.40f, 4L, intervalMillis = intervalMillis), commandTime(4L, intervalMillis))
        assertEquals(28, fourth.safetyFilteredYawRc)
    }

    @Test fun `next fresh same-direction frame resumes controller progression after hold expiry`() {
        val controller = ProductionYawController()
        val intervalMillis = 220L
        val first = controller.command(errors(.30f, 1L, intervalMillis = intervalMillis), commandTime(1L, intervalMillis))
        assertEquals(8, first.safetyFilteredYawRc)

        val expired = controller.command(errors(.30f, 1L, intervalMillis = intervalMillis), commandTime(1L, intervalMillis) + 205L * NANOS_PER_MILLISECOND)
        assertEquals(0, expired.safetyFilteredYawRc)
        assertEquals(YawControlSuppressionReason.NONZERO_COMMAND_HOLD_EXPIRED, expired.suppressionReason)

        val second = controller.command(errors(.30f, 2L, intervalMillis = intervalMillis), commandTime(2L, intervalMillis))
        assertEquals(16, second.safetyFilteredYawRc)
    }

    @Test fun `centered frame overrides preserved state immediately after hold expiry`() {
        val controller = ProductionYawController()
        val intervalMillis = 220L
        val first = controller.command(errors(.15f, 1L, intervalMillis = intervalMillis), commandTime(1L, intervalMillis))
        assertEquals(8, first.safetyFilteredYawRc)

        val expired = controller.command(errors(.15f, 1L, intervalMillis = intervalMillis), commandTime(1L, intervalMillis) + 205L * NANOS_PER_MILLISECOND)
        assertEquals(0, expired.safetyFilteredYawRc)

        val centered = controller.command(errors(.02f, 2L, intervalMillis = intervalMillis), commandTime(2L, intervalMillis))
        assertEquals(0, centered.safetyFilteredYawRc)
        assertEquals(0, centered.requestedYawRc)
        assertEquals(YawControlSuppressionReason.NONE, centered.suppressionReason)
    }

    @Test fun `direction crossing after hold expiry brakes to zero before reversing`() {
        val controller = ProductionYawController()
        val intervalMillis = 220L
        val first = controller.command(errors(.12f, 1L, intervalMillis = intervalMillis), commandTime(1L, intervalMillis))
        assertEquals(8, first.safetyFilteredYawRc)

        val expired = controller.command(errors(.12f, 1L, intervalMillis = intervalMillis), commandTime(1L, intervalMillis) + 205L * NANOS_PER_MILLISECOND)
        assertEquals(0, expired.safetyFilteredYawRc)

        // Crossing to opposite side within anti-jump gate (.12 to -.05 is delta .17 <= .18) enters SETTLING with zero output
        val crossing = controller.command(errors(-.05f, 2L, intervalMillis = intervalMillis), commandTime(2L, intervalMillis))
        assertEquals(0, crossing.safetyFilteredYawRc)
        assertEquals(YawControlSuppressionReason.CENTER_CROSSING_BRAKE, crossing.suppressionReason)
        assertEquals(YawControllerPhase.SETTLING, crossing.phase)

        // Settling measurement 1 (dt = 175ms < 200ms fallback)
        val settling = controller.command(errors(-.07f, 3L, intervalMillis = intervalMillis), commandTime(3L, intervalMillis))
        assertEquals(0, settling.safetyFilteredYawRc)

        // Subsequent fresh frame at dt = 350ms >= 200ms and count >= 2 begins reverse rotation
        val reversed = controller.command(errors(-.09f, 4L, intervalMillis = intervalMillis), commandTime(4L, intervalMillis))
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
        assertEquals(0, crossing.safetyFilteredYawRc)
        assertEquals(YawControlSuppressionReason.CENTER_CROSSING_BRAKE, crossing.suppressionReason)
        assertEquals(YawControllerPhase.SETTLING, crossing.phase)

        val settling1 = controller.command(errors(-.07f, 4L), commandTime(4L))
        assertEquals(0, settling1.safetyFilteredYawRc)

        val resumed = controller.command(errors(-.09f, 5L), commandTime(5L))
        assertTrue(resumed.safetyFilteredYawRc < 0)
        assertTrue(abs(resumed.safetyFilteredYawRc) <= ProductionYawController.MAXIMUM_ACCELERATION_STEP)
    }

    @Test fun `test 1 physical inertia simulation crossing with high yaw rate stays in settling with zero yaw`() {
        val controller = ProductionYawController()
        // Active rotation command to the right
        val cmd1 = controller.command(errors(.30f, 1L), commandTime(1L), telemetryYawRateDegreesPerSecond = 15f)
        assertEquals(8, cmd1.safetyFilteredYawRc)
        assertEquals(YawControllerPhase.CORRECTING, cmd1.phase)

        val cmd2 = controller.command(errors(.25f, 2L), commandTime(2L), telemetryYawRateDegreesPerSecond = 18f)
        assertEquals(16, cmd2.safetyFilteredYawRc)
        assertEquals(YawControllerPhase.CORRECTING, cmd2.phase)

        // Center reached / crossing with high yaw rate (delta .25 -> -.04 is delta .29 > .18, so step via .10 then -.04)
        val cmd3 = controller.command(errors(.10f, 3L), commandTime(3L), telemetryYawRateDegreesPerSecond = 18f)
        assertTrue(cmd3.safetyFilteredYawRc > 0)
        assertEquals(YawControllerPhase.CORRECTING, cmd3.phase)

        val cmd4 = controller.command(errors(-.04f, 4L), commandTime(4L), telemetryYawRateDegreesPerSecond = 16f)
        assertEquals(0, cmd4.safetyFilteredYawRc)
        assertEquals(YawControllerPhase.SETTLING, cmd4.phase)
        assertEquals(YawControlSuppressionReason.CENTER_CROSSING_BRAKE, cmd4.suppressionReason)

        // Subsequent frames while aircraft is still spinning fast (> 8 deg/s) must NEVER output reverse yaw
        val cmd5 = controller.command(errors(-.06f, 5L), commandTime(5L), telemetryYawRateDegreesPerSecond = 14f)
        assertEquals(0, cmd5.safetyFilteredYawRc)
        assertEquals(YawControllerPhase.SETTLING, cmd5.phase)

        val cmd6 = controller.command(errors(-.08f, 6L), commandTime(6L), telemetryYawRateDegreesPerSecond = 10f)
        assertEquals(0, cmd6.safetyFilteredYawRc)
        assertEquals(YawControllerPhase.SETTLING, cmd6.phase)
    }

    @Test fun `test 2 reversal only begins after physical yaw rate settles for 2 consecutive samples`() {
        val controller = ProductionYawController()
        controller.command(errors(.25f, 1L), commandTime(1L), telemetryYawRateDegreesPerSecond = 12f)
        controller.command(errors(.18f, 2L), commandTime(2L), telemetryYawRateDegreesPerSecond = 15f)
        controller.command(errors(.08f, 3L), commandTime(3L), telemetryYawRateDegreesPerSecond = 15f)

        // Cross center -> SETTLING (.08 -> -.05 is delta .13 <= .18)
        val crossing = controller.command(errors(-.05f, 4L), commandTime(4L), telemetryYawRateDegreesPerSecond = 12f)
        assertEquals(0, crossing.safetyFilteredYawRc)
        assertEquals(YawControllerPhase.SETTLING, crossing.phase)

        // First sample with low rate (4 deg/s <= 8 deg/s) - only 1 sample, not yet settled
        controller.observeTelemetry(0, 4f, 1_000L)
        val sample1 = controller.command(errors(-.07f, 5L), commandTime(5L), telemetryYawRateDegreesPerSecond = 4f)
        assertEquals(0, sample1.safetyFilteredYawRc)
        assertEquals(YawControllerPhase.SETTLING, sample1.phase)

        // Second consecutive sample with low rate (2 deg/s <= 8 deg/s) -> now settled, begins smooth reversal
        controller.observeTelemetry(0, 2f, 1_100L)
        val sample2 = controller.command(errors(-.08f, 6L), commandTime(6L), telemetryYawRateDegreesPerSecond = 2f)
        assertTrue(sample2.safetyFilteredYawRc < 0)
        assertTrue(abs(sample2.safetyFilteredYawRc) <= ProductionYawController.MAXIMUM_ACCELERATION_STEP)
        assertEquals(YawControllerPhase.CORRECTING, sample2.phase)
    }

    @Test fun `test 3 stationary centered person with detector jitter remains in hold at zero yaw`() {
        val controller = ProductionYawController()
        // CenterX: 0.49, 0.51, 0.485, 0.515, 0.50 (errors: -0.01, +0.01, -0.015, +0.015, 0.0)
        val jitterErrors = listOf(-0.01f, 0.01f, -0.015f, 0.015f, 0.0f)
        jitterErrors.forEachIndexed { index, err ->
            val frame = index + 1L
            val outcome = controller.command(
                errors(err, frame),
                commandTime(frame),
                telemetryYawRateDegreesPerSecond = 0.5f,
            )
            assertEquals(0, outcome.safetyFilteredYawRc)
            assertEquals(0, outcome.requestedYawRc)
            assertEquals(YawControllerPhase.HOLD, outcome.phase)
        }
    }

    @Test fun `test 4 person moving across frame when drone is stationary settles and follows`() {
        val controller = ProductionYawController()
        // Person is on left (-0.08) with stationary drone
        val cmd1 = controller.command(errors(-.08f, 1L), commandTime(1L), telemetryYawRateDegreesPerSecond = 0f)
        assertTrue(cmd1.safetyFilteredYawRc < 0)
        assertTrue(abs(cmd1.safetyFilteredYawRc) <= ProductionYawController.MAXIMUM_ACCELERATION_STEP)
        assertEquals(YawControllerPhase.CORRECTING, cmd1.phase)

        // Person moves to right (+0.06) (delta -.08 -> .06 is .14 <= .18) -> enters SETTLING
        val crossing = controller.command(errors(.06f, 2L), commandTime(2L), telemetryYawRateDegreesPerSecond = 0f)
        assertEquals(0, crossing.safetyFilteredYawRc)
        assertEquals(YawControllerPhase.SETTLING, crossing.phase)

        // Settling sample 1 with yaw rate = 0 (count = 1 < 2)
        controller.observeTelemetry(0, 0f, 1_000L)
        val settling1 = controller.command(errors(.07f, 3L), commandTime(3L), telemetryYawRateDegreesPerSecond = 0f)
        assertEquals(0, settling1.safetyFilteredYawRc)
        assertEquals(YawControllerPhase.SETTLING, settling1.phase)

        // Settling sample 2 with yaw rate = 0 (count = 2 >= 2) -> transitions to CORRECTING and follows
        controller.observeTelemetry(0, 0f, 1_100L)
        val followed = controller.command(errors(.08f, 4L), commandTime(4L), telemetryYawRateDegreesPerSecond = 0f)
        assertTrue(followed.safetyFilteredYawRc > 0)
        assertTrue(followed.safetyFilteredYawRc <= ProductionYawController.MAXIMUM_ACCELERATION_STEP)
        assertEquals(YawControllerPhase.CORRECTING, followed.phase)
    }

    @Test fun `test 5 yaw wraparound 178 to 179 to minus 179 to minus 178 produces continuous rate`() {
        val rate1 = calculateYawRateDegreesPerSecond(178, 179, 1000L, 1100L)
        val rate2 = calculateYawRateDegreesPerSecond(179, -179, 1100L, 1200L)
        val rate3 = calculateYawRateDegreesPerSecond(-179, -178, 1200L, 1300L)
        assertEquals(10f, rate1!!, 0.01f)
        assertEquals(20f, rate2!!, 0.01f)
        assertEquals(10f, rate3!!, 0.01f)
        assertTrue(rate2 > 0f)
        assertTrue(rate2 < 50f)
    }

    @Test fun `test 6 missing yaw telemetry fallback requires 200ms and 2 measurements`() {
        val controller = ProductionYawController()
        controller.command(errors(.20f, 1L), commandTime(1L))
        controller.command(errors(.12f, 2L), commandTime(2L))

        // Center crossing with telemetry yaw rate = null (.12 -> -.04 is delta .16 <= .18)
        val crossing = controller.command(errors(-.04f, 3L), commandTime(3L))
        assertEquals(0, crossing.safetyFilteredYawRc)
        assertEquals(YawControllerPhase.SETTLING, crossing.phase)

        // Frame 4 at 100ms later (only 1 measurement, 100ms < 200ms)
        val settling = controller.command(errors(-.06f, 4L), commandTime(4L))
        assertEquals(0, settling.safetyFilteredYawRc)
        assertEquals(YawControllerPhase.SETTLING, settling.phase)

        // Frame 5 at 200ms later (2 measurements, 200ms >= 200ms)
        val resumed = controller.command(errors(-.08f, 5L), commandTime(5L))
        assertTrue(resumed.safetyFilteredYawRc < 0)
        assertTrue(abs(resumed.safetyFilteredYawRc) <= ProductionYawController.MAXIMUM_ACCELERATION_STEP)
        assertEquals(YawControllerPhase.CORRECTING, resumed.phase)
    }

    @Test fun `test 7 safety invariants disarm or latch requires rearm and reset controller`() {
        val unsafe = listOf(
            healthy(manualNeutral = false), healthy(hoverActive = true), healthy(flight = FlightState.Landing),
            healthy(flight = FlightState.Emergency), healthy(telemetryFresh = false),
            healthy(video = VideoAvailability.Error), healthy(detector = PersonDetectionState.Error),
            healthy(connection = DroneConnectionState.Error),
            healthy(association = TargetAssociationState.Lost),
            healthy(association = TargetAssociationState.Ambiguous),
        )
        unsafe.forEach { input ->
            val gate = YawFollowGate()
            gate.arm(healthy())
            val decision = gate.evaluate(input)
            assertEquals(YawFollowState.REQUIRES_REARM, decision.state)
            assertEquals(0, decision.yawRc)
        }
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

    @Test fun `hard perception age 450ms expiration and true stale gap settling`() {
        val controller = ProductionYawController()
        val first = controller.command(errors(.15f, 1L), commandTime(1L))
        assertEquals(8, first.safetyFilteredYawRc)

        // Gap of 460ms exceeds 450ms hard limit
        val stale = controller.command(errors(.15f, 1L), commandTime(1L) + 460L * NANOS_PER_MILLISECOND)
        assertEquals(0, stale.safetyFilteredYawRc)
        assertEquals(YawControlSuppressionReason.STALE_PERCEPTION, stale.suppressionReason)

        // When perception resumes with opposite direction within anti-jump gate (.15 to -.02 is delta .17 <= .18), settling is enforced
        val opposite = controller.command(errors(-.02f, 2L), commandTime(1L) + 500L * NANOS_PER_MILLISECOND)
        assertEquals(0, opposite.safetyFilteredYawRc)
        assertEquals(YawControlSuppressionReason.CENTER_CROSSING_BRAKE, opposite.suppressionReason)
        assertEquals(YawControllerPhase.SETTLING, opposite.phase)
    }

    @Test fun `gate observeTelemetry does not reprocess perception or reset controller`() {
        val gate = YawFollowGate()
        val initialInput = healthy(frame = 1L)
        gate.arm(initialInput)
        val firstDecision = gate.processFreshPerception(initialInput)
        assertEquals(8, firstDecision.yawRc)
        assertEquals(YawFollowState.ACTIVE, firstDecision.state)

        // Telemetry observation updates settling state without recomputing command
        gate.observeTelemetry(10, 2f, 1_000L)
        val safetyCheck = gate.evaluateSafetyGate(initialInput)
        assertEquals(YawFollowState.ACTIVE, safetyCheck.state)
    }

    @Test fun `duplicate telemetry sample cannot settle through repeated detector frames`() {
        val controller = ProductionYawController()
        controller.command(errors(.12f, 1L), commandTime(1L), telemetryYawRateDegreesPerSecond = 12f)
        val crossing = controller.command(errors(-.05f, 2L), commandTime(2L), telemetryYawRateDegreesPerSecond = 2f)
        assertEquals(YawControllerPhase.SETTLING, crossing.phase)

        controller.observeTelemetry(0, 2f, 2_000L)
        repeat(8) { index ->
            controller.observeTelemetry(0, 2f, 2_000L)
            val frame = index + 3L
            val held = controller.command(errors(-.06f, frame), commandTime(frame), telemetryYawRateDegreesPerSecond = 2f)
            assertEquals(0, held.safetyFilteredYawRc)
            assertEquals(YawControllerPhase.SETTLING, held.phase)
        }
        assertEquals(1, controller.settledTelemetrySampleCount())

        controller.observeTelemetry(0, 2f, 2_100L)
        assertEquals(2, controller.settledTelemetrySampleCount())
        val reversed = controller.command(errors(-.08f, 11L), commandTime(11L), telemetryYawRateDegreesPerSecond = 2f)
        assertTrue(reversed.safetyFilteredYawRc < 0)
    }

    @Test fun `stale gap opposite yaw waits for distinct physical settling samples`() {
        val controller = ProductionYawController()
        controller.command(errors(.15f, 1L), commandTime(1L), telemetryYawRateDegreesPerSecond = 14f)
        controller.command(
            errors(.15f, 1L),
            commandTime(1L) + 460L * NANOS_PER_MILLISECOND,
            telemetryYawRateDegreesPerSecond = 2f,
        )
        val opposite = controller.command(
            errors(-.02f, 2L),
            commandTime(1L) + 500L * NANOS_PER_MILLISECOND,
            telemetryYawRateDegreesPerSecond = 2f,
        )
        assertEquals(0, opposite.safetyFilteredYawRc)

        controller.observeTelemetry(0, 2f, 3_000L)
        assertEquals(1, controller.settledTelemetrySampleCount())
        val oneSample = controller.command(errors(-.08f, 7L), commandTime(7L), telemetryYawRateDegreesPerSecond = 2f)
        assertEquals(0, oneSample.safetyFilteredYawRc)
        assertEquals(1, controller.settledTelemetrySampleCount())

        controller.observeTelemetry(0, 2f, 3_100L)
        assertEquals(2, controller.settledTelemetrySampleCount())
        val reversed = controller.command(errors(-.08f, 8L), commandTime(8L), telemetryYawRateDegreesPerSecond = 2f)
        assertTrue(reversed.safetyFilteredYawRc < 0)
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
