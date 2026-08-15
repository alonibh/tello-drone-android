package com.alonibh.tellodrone.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetAssociationEngineTest {
    private val engine = TargetAssociationEngine()

    @Test fun `target requires explicit selection`() {
        val result = engine.associate(null, 2L, 2_000L, listOf(detection(frame = 2L, timestamp = 2_000L)))

        assertTrue(result is TargetAssociationResult.Lost)
        assertNull(result.target)
    }

    @Test fun `selected person stays matched during small movement`() {
        val selected = TargetSelection.select(detection())
        val moved = detection(box = box(.32f, .20f, .52f, .80f), confidence = .55f, frame = 2L, timestamp = 2_000L)

        val result = engine.associate(selected, 2L, 2_000L, listOf(moved)) as TargetAssociationResult.Matched

        assertEquals(moved.boundingBox, result.target.boundingBox)
        assertEquals(.55f, result.target.confidence)
    }

    @Test fun `changing confidence does not switch target`() {
        val selected = TargetSelection.select(detection())
        val samePerson = detection(box = box(.31f, .20f, .51f, .80f), confidence = .10f, frame = 2L, timestamp = 2_000L)
        val distantHighConfidence = detection(box = box(.72f, .15f, .96f, .85f), confidence = .99f, frame = 2L, timestamp = 2_000L)

        val result = engine.associate(selected, 2L, 2_000L, listOf(samePerson, distantHighConfidence)) as TargetAssociationResult.Matched

        assertEquals(samePerson.boundingBox, result.target.boundingBox)
    }

    @Test fun `larger second person does not steal lock`() {
        val selected = TargetSelection.select(detection())
        val samePerson = detection(box = box(.31f, .19f, .51f, .81f), frame = 2L, timestamp = 2_000L)
        val largerPerson = detection(box = box(.52f, .05f, .98f, .95f), confidence = .99f, frame = 2L, timestamp = 2_000L)

        val result = engine.associate(selected, 2L, 2_000L, listOf(samePerson, largerPerson)) as TargetAssociationResult.Matched

        assertEquals(samePerson.boundingBox, result.target.boundingBox)
    }

    @Test fun `two similarly plausible candidates are ambiguous`() {
        val selected = TargetSelection.select(detection())
        val first = detection(box = box(.32f, .20f, .52f, .80f), frame = 2L, timestamp = 2_000L)
        val second = detection(box = box(.33f, .20f, .53f, .80f), frame = 2L, timestamp = 2_000L)

        val result = engine.associate(selected, 2L, 2_000L, listOf(first, second))

        assertTrue(result is TargetAssociationResult.Ambiguous)
        assertEquals(selected, result.target)
    }

    @Test fun `short disappearance is temporarily missing`() {
        val selected = TargetSelection.select(detection(timestamp = 1_000L))

        val result = engine.associate(selected, 2L, 1_000L + TargetAssociationEngine.MISSING_TIMEOUT_NANOS, emptyList())

        assertTrue(result is TargetAssociationResult.TemporarilyMissing)
        assertEquals(selected, result.target)
    }

    @Test fun `absence beyond timeout is lost`() {
        val selected = TargetSelection.select(detection(timestamp = 1_000L))

        val result = engine.associate(selected, 2L, 1_001L + TargetAssociationEngine.MISSING_TIMEOUT_NANOS, emptyList())

        assertTrue(result is TargetAssociationResult.Lost)
        assertNull(result.target)
    }

    @Test fun `lost target never auto reacquires`() {
        val candidate = detection(frame = 3L, timestamp = 3_000L)

        val result = engine.associate(null, 3L, 3_000L, listOf(candidate))

        assertTrue(result is TargetAssociationResult.Lost)
        assertNull(result.target)
    }

    @Test fun `older frame is ignored`() {
        val selected = TargetSelection.select(detection(frame = 4L, timestamp = 4_000L))

        val result = engine.associate(selected, 3L, 5_000L, listOf(detection(frame = 3L, timestamp = 5_000L)))

        assertTrue(result is TargetAssociationResult.Ignored)
        assertEquals(selected, result.target)
    }

    @Test fun `normalized error signs follow target position and area`() {
        val errors = TrackingErrorEngine()
        val reference = FollowDistanceReference(.2f, 1L, 1L, 7)
        val rightAndSmall = TargetSelection.select(detection(box = box(.65f, .15f, .75f, .25f)))
        val result = errors.update(rightAndSmall, targetFresh = true, distanceReference = reference)

        assertTrue(result.yawError > 0f)
        assertTrue(result.verticalError > 0f)
        assertTrue(result.forwardBackError > 0f)
        assertTrue(result.targetPresent)
        assertTrue(result.targetFresh)

        errors.reset()
        val leftAndLarge = TargetSelection.select(detection(box = box(.05f, .70f, .55f, .95f)))
        val opposite = errors.update(leftAndLarge, targetFresh = true, distanceReference = reference)
        assertTrue(opposite.yawError < 0f)
        assertTrue(opposite.verticalError < 0f)
        assertTrue(opposite.forwardBackError < 0f)
    }

    @Test fun `yaw smoothing uses point 65 while vertical and distance remain point four`() {
        val errors = TrackingErrorEngine()
        val reference = FollowDistanceReference(.2f, 1L, 1L, 7)
        val centered = TargetSelection.select(detection(box = box(.40f, .40f, .60f, .60f)))
        val moved = TargetSelection.select(detection(box = box(.65f, .25f, .75f, .35f)))

        errors.update(centered, targetFresh = true, distanceReference = reference)
        val result = errors.update(moved, targetFresh = true, distanceReference = reference)

        assertEquals(.13f, result.yawError, .0001f)
        assertEquals(.08f, result.verticalError, .0001f)
        assertEquals(.20f, result.forwardBackError, .0001f)
    }

    @Test fun `normalized deadzones zero small center and area errors`() {
        val errors = TrackingErrorEngine()
        val target = TargetSelection.select(detection(box = box(.3725f, .3725f, .6275f, .6275f)))

        val result = errors.update(target, targetFresh = true)

        assertEquals(0f, result.yawError, 0f)
        assertEquals(0f, result.verticalError, 0f)
        assertEquals(0f, result.forwardBackError, 0f)
    }

    @Test fun `smoothing resets after loss and explicit new selection`() {
        val errors = TrackingErrorEngine()
        errors.update(TargetSelection.select(detection(box = box(.60f, .40f, .80f, .60f))), targetFresh = true)
        val lost = errors.update(null, targetFresh = false)
        val afterLoss = errors.update(TargetSelection.select(detection(box = box(.55f, .40f, .65f, .60f))), targetFresh = true)

        assertFalse(lost.targetPresent)
        assertEquals(.10f, afterLoss.yawError, .0001f)

        errors.reset()
        val afterSelection = errors.update(TargetSelection.select(detection(box = box(.55f, .40f, .65f, .60f))), targetFresh = true)
        assertEquals(.10f, afterSelection.yawError, .0001f)
    }

    private fun detection(
        box: NormalizedBoundingBox = box(.30f, .20f, .50f, .80f),
        confidence: Float = .9f,
        frame: Long = 1L,
        timestamp: Long = 1_000L,
    ) = PersonDetection(box, confidence, frame, timestamp)

    private fun box(left: Float, top: Float, right: Float, bottom: Float) =
        NormalizedBoundingBox(left, top, right, bottom)
}
