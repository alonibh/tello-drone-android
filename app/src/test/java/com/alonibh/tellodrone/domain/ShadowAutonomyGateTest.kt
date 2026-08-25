package com.alonibh.tellodrone.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowAutonomyGateTest {
    @Test fun `invalid pid config is rejected`() {
        try { PidConfig(Float.NaN, 0f, 0f, -1f, 1f, -1f, 1f); throw AssertionError() } catch (_: IllegalArgumentException) {}
        try { PidConfig(0f, 0f, 0f, 1f, -1f, -1f, 1f); throw AssertionError() } catch (_: IllegalArgumentException) {}
    }
    @Test fun `healthy explicit arm becomes eligible`() {
        val decision = ShadowAutonomyGate().evaluate(healthy(arm = true))
        assertTrue(decision.eligible); assertEquals(ShadowAutonomyState.Eligible, decision.state)
    }
    @Test fun `manual override latches rearm until new arm`() {
        val gate = ShadowAutonomyGate(); gate.evaluate(healthy(arm = true))
        val override = gate.evaluate(healthy(neutral = false))
        assertEquals(ShadowAutonomyState.RequiresRearm, override.state)
        assertFalse(gate.evaluate(healthy()).eligible)
        assertTrue(gate.evaluate(healthy(arm = true)).eligible)
    }
    @Test fun `hover ambiguity lost telemetry video detector landing and emergency latch`() {
        listOf(
            healthy(hover = true), healthy(association = TargetAssociationState.Ambiguous), healthy(association = TargetAssociationState.Lost),
            healthy(telemetry = false), healthy(video = VideoAvailability.Error), healthy(detector = PersonDetectionState.Error),
            healthy(flight = FlightState.Landing), healthy(flight = FlightState.Emergency),
        ).forEach { bad ->
            val gate = ShadowAutonomyGate(); gate.evaluate(healthy(arm = true)); assertTrue(gate.evaluate(bad).requiresExplicitRearm); assertFalse(gate.evaluate(healthy()).eligible)
        }
    }
    @Test fun `missing stale planner and invalid inputs never eligible`() {
        val replay = ShadowAutonomyReplay().replay(listOf(healthy(arm = true), healthy(association = TargetAssociationState.TemporarilyMissing), healthy(intent = false), healthy(fresh = false)))
        assertTrue(replay.first().eligible); replay.drop(1).forEach { assertFalse(it.eligible) }
    }
    private fun healthy(arm: Boolean = false, neutral: Boolean = true, hover: Boolean = false, association: TargetAssociationState = TargetAssociationState.Matched, telemetry: Boolean = true, video: VideoAvailability = VideoAvailability.Streaming, detector: PersonDetectionState = PersonDetectionState.Detecting, flight: FlightState = FlightState.Flying, fresh: Boolean = true, intent: Boolean = true) = ShadowAutonomyInput(DroneConnectionState.Connected, flight, telemetry, video, detector, true, association, TrackingErrors(targetPresent = true, targetFresh = fresh), DryRunControlIntent(actionable = intent, reason = DryRunControlReason.TARGET_MATCHED), neutral, hover, armRequested = arm)
}
// SPDX-License-Identifier: AGPL-3.0-only
