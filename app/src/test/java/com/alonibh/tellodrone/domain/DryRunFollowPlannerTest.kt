package com.alonibh.tellodrone.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DryRunFollowPlannerTest {
    private val config = FollowPlannerConfig.LEGACY_SIMULATION

    @Test fun `first error sample seeds and subsequent sample uses ema`() {
        val engine = TrackingErrorEngine()
        val first = engine.update(target(.70f), true)
        val second = engine.update(target(.60f, frame = 2L, timestamp = 2L), true)
        assertEquals(.20f, first.yawError, .0001f)
        assertEquals(.16f, second.yawError, .0001f)
        engine.update(target(.60f, frame = 3L, timestamp = 3L), false)
        assertEquals(.136f, engine.update(target(.60f, frame = 4L, timestamp = 4L), true).yawError, .0001f)
    }

    @Test fun `pid bounds output integral and rejects invalid timing`() {
        val pid = PidController(PidConfig(1f, 1f, 0f, -.5f, .5f, -.2f, .2f))
        repeat(10) { assertEquals(.5f, pid.compute(10f, .1f)!!, 0f) }
        assertNull(pid.compute(1f, 0f))
        assertNull(pid.compute(Float.NaN, .1f))
        pid.reset()
        assertEquals(.2f, pid.compute(.1f, 1f)!!, .0001f)
    }

    @Test fun `planner preserves explicit sign contract and clamps`() {
        val planner = DryRunFollowPlanner(config)
        val right = planner.plan(errors(yaw = 4f), TargetAssociationState.Matched, .1f)
        assertTrue(right.actionable); assertTrue(right.yaw > 0f); assertEquals(.5f, right.yaw, 0f)
        planner.reset()
        assertTrue(planner.plan(errors(yaw = -1f), TargetAssociationState.Matched, .1f).yaw < 0f)
        planner.reset()
        assertTrue(planner.plan(errors(vertical = 1f), TargetAssociationState.Matched, .1f).vertical > 0f)
        planner.reset()
        assertTrue(planner.plan(errors(vertical = -1f), TargetAssociationState.Matched, .1f).vertical < 0f)
        planner.reset()
        assertTrue(planner.plan(errors(area = 1f), TargetAssociationState.Matched, .1f).forwardBack > 0f)
        planner.reset()
        assertTrue(planner.plan(errors(area = -1f), TargetAssociationState.Matched, .1f).forwardBack < 0f)
    }

    @Test fun `nonmatched stale missing ambiguous lost invalid and missing errors are always zero`() {
        val planner = DryRunFollowPlanner(config)
        listOf(TargetAssociationState.None, TargetAssociationState.TemporarilyMissing, TargetAssociationState.Ambiguous, TargetAssociationState.Lost).forEach {
            val intent = planner.plan(errors(), it, .1f)
            assertFalse(intent.actionable); assertEquals(0f, intent.yaw, 0f)
        }
        assertFalse(planner.plan(errors(fresh = false), TargetAssociationState.Matched, .1f).actionable)
        assertFalse(planner.plan(null, TargetAssociationState.Matched, .1f).actionable)
        assertFalse(planner.plan(errors(), TargetAssociationState.Matched, 0f).actionable)
    }

    @Test fun `uncalibrated matched target is distance not set with zero intent`() {
        val intent = DryRunFollowPlanner(config).plan(errors(calibrated = false), TargetAssociationState.Matched, .1f)
        assertFalse(intent.actionable); assertEquals(DryRunControlReason.DISTANCE_NOT_SET, intent.reason)
        assertEquals(0f, intent.yaw, 0f); assertEquals(0f, intent.vertical, 0f); assertEquals(0f, intent.forwardBack, 0f)
    }

    @Test fun `lost and new selection reset pid state with deterministic variable dt`() {
        val planner = DryRunFollowPlanner(config)
        planner.plan(errors(yaw = .2f), TargetAssociationState.Matched, .1f)
        planner.plan(errors(yaw = .2f), TargetAssociationState.Matched, .2f)
        val lost = planner.plan(errors(), TargetAssociationState.Lost, .1f)
        val selected = planner.plan(errors(yaw = .2f), TargetAssociationState.Selected, .3f)
        assertFalse(lost.actionable)
        assertEquals(.206f, selected.yaw, .0001f)
        assertEquals(DryRunControlReason.TARGET_SELECTED, selected.reason)
    }

    @Test fun `association replay ambiguity missing loss and older frame have no steering`() {
        val association = TargetAssociationEngine()
        val selected = TargetSelection.select(detection())
        val planner = DryRunFollowPlanner(config)
        val errors = TrackingErrorEngine()
        val matched = association.associate(selected, 2L, 2L, listOf(detection(.32f, frame = 2L, timestamp = 2L))) as TargetAssociationResult.Matched
        assertTrue(planner.plan(errors.update(matched.target, true, reference()), TargetAssociationState.Matched, .1f).actionable)
        val ambiguous = association.associate(matched.target, 3L, 3L, listOf(detection(.34f, frame = 3L, timestamp = 3L), detection(.35f, frame = 3L, timestamp = 3L)))
        assertFalse(planner.plan(errors.update(ambiguous.target, false), TargetAssociationState.Ambiguous, .1f).actionable)
        assertFalse(planner.plan(errors.update(matched.target, false), TargetAssociationState.TemporarilyMissing, .1f).actionable)
        assertFalse(planner.plan(errors.update(null, false), TargetAssociationState.Lost, .1f).actionable)
        val older = association.associate(matched.target, 2L, 4L, listOf(detection(.1f, frame = 2L, timestamp = 4L)))
        assertTrue(older is TargetAssociationResult.Ignored)
        assertFalse(planner.plan(null, TargetAssociationState.Lost, .1f).actionable)
    }

    private fun errors(yaw: Float = 0f, vertical: Float = 0f, area: Float = 0f, fresh: Boolean = true, calibrated: Boolean = true) =
        TrackingErrors(yaw, vertical, area, targetPresent = true, targetFresh = fresh, distanceCalibrated = calibrated)
    private fun reference() = FollowDistanceReference(.3f, 1L, 1L, 7)
    private fun target(centerX: Float, frame: Long = 1L, timestamp: Long = 1L) = TargetSelection.select(detection(centerX, frame, timestamp))
    private fun detection(centerX: Float = .5f, frame: Long = 1L, timestamp: Long = 1L) =
        PersonDetection(NormalizedBoundingBox(centerX - .1f, .3f, centerX + .1f, .7f), .9f, frame, timestamp)
}
