package com.alonibh.tellodrone.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YawFollowControllerTest {
    @Test fun `grounded stale and targetless inputs cannot activate`() {
        assertEquals(
            YawFollowState.ARMED_WAITING,
            YawFollowGate().arm(healthy(flight = FlightState.Grounded)).state,
        )
        assertEquals(
            YawFollowState.REQUIRES_REARM,
            YawFollowGate().arm(healthy(telemetryFresh = false)).state,
        )
        assertEquals(
            YawFollowState.ARMED_WAITING,
            YawFollowGate().arm(healthy(targetPresent = false)).state,
        )
    }

    @Test fun `matched fresh target produces capped physically verified Tello yaw only`() {
        val controller = ProductionYawController()
        val right = controller.command(errors(yaw = .5f))
        val left = controller.command(errors(yaw = -.5f))

        assertEquals(YawOnlyRcCommand(yaw = -12), right)
        assertEquals(YawOnlyRcCommand(yaw = 12), left)
        listOf(right, left).forEach { command ->
            assertEquals(0, command.lateral)
            assertEquals(0, command.forward)
            assertEquals(0, command.vertical)
            assertTrue(kotlin.math.abs(command.yaw) <= 12)
        }
    }

    @Test fun `five percent horizontal deadband produces zero yaw`() {
        val controller = ProductionYawController()
        assertEquals(YawOnlyRcCommand(), controller.command(errors(yaw = 0f)))
        assertEquals(YawOnlyRcCommand(), controller.command(errors(yaw = .05f)))
        assertEquals(YawOnlyRcCommand(), controller.command(errors(yaw = -.05f)))
    }

    @Test fun `temporary missing zeros and same explicit target can resume`() {
        val gate = YawFollowGate()
        assertEquals(YawFollowState.ACTIVE, gate.arm(healthy()).state)

        val missing = gate.evaluate(healthy(association = TargetAssociationState.TemporarilyMissing))
        assertEquals(YawFollowState.ARMED_WAITING, missing.state)
        assertEquals(0, missing.yawRc)

        val resumed = gate.evaluate(healthy())
        assertEquals(YawFollowState.ACTIVE, resumed.state)
        assertTrue(resumed.yawRc < 0)
    }

    @Test fun `ambiguous and lost targets require explicit rearm`() {
        listOf(TargetAssociationState.Ambiguous, TargetAssociationState.Lost).forEach { association ->
            val gate = YawFollowGate()
            assertEquals(YawFollowState.ACTIVE, gate.arm(healthy()).state)
            val latched = gate.evaluate(healthy(association = association))
            assertEquals(YawFollowState.REQUIRES_REARM, latched.state)
            assertTrue(latched.requiresExplicitRearm)
            assertFalse(gate.evaluate(healthy()).state == YawFollowState.ACTIVE)
            assertEquals(YawFollowState.ACTIVE, gate.arm(healthy()).state)
        }
    }

    @Test fun `manual hover landing emergency and health losses latch rearm`() {
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
    ) = YawFollowInput(
        connection = connection,
        flight = flight,
        telemetryFresh = telemetryFresh,
        video = video,
        detector = detector,
        targetPresent = targetPresent,
        association = association,
        errors = errors(yaw = .25f),
        manualInputNeutral = manualNeutral,
        hoverActive = hoverActive,
    )

    private fun errors(yaw: Float) = TrackingErrors(
        yawError = yaw,
        targetPresent = true,
        targetFresh = true,
        distanceCalibrated = false,
    )
}
