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

    @Test fun `selected A remains matched when B is nearby but separate`() {
        val selectedA = TargetSelection.select(
            detection(box = box(.30f, .20f, .50f, .80f)),
        )
        val movedA = detection(box = box(.31f, .20f, .51f, .80f), frame = 2L, timestamp = 2_000L)
        val nearbyB = detection(box = box(.43f, .20f, .63f, .80f), frame = 2L, timestamp = 2_000L)

        val result = engine.associate(selectedA, 2L, 2_000L, listOf(movedA, nearbyB))
            as TargetAssociationResult.Matched

        assertEquals(movedA.boundingBox, result.target.boundingBox)
        assertFalse(result.target.identityUncertain)
    }

    @Test fun `B approaching A remains distinguishable by joint continuity`() {
        val nearby = trackAWithNearbyB()
        val movedA = detection(box = box(.32f, .20f, .52f, .80f), frame = 3L, timestamp = 3_000L)
        val approachingB = detection(box = box(.40f, .20f, .60f, .80f), frame = 3L, timestamp = 3_000L)

        val result = engine.associate(nearby, 3L, 3_000L, listOf(movedA, approachingB))
            as TargetAssociationResult.Matched

        assertEquals(movedA.boundingBox, result.target.boundingBox)
        assertFalse(result.target.identityUncertain)
    }

    @Test fun `known B alone is target missing rather than a match`() {
        val nearby = trackAWithNearbyB()
        val bAlone = detection(box = box(.44f, .20f, .64f, .80f), frame = 3L, timestamp = 3_000L)

        val result = engine.associate(nearby, 3L, 3_000L, listOf(bAlone))

        assertTrue(result is TargetAssociationResult.TemporarilyMissing)
        assertEquals(nearby.boundingBox, result.target?.boundingBox)
        assertFalse(result.target?.boundingBox == bAlone.boundingBox)

        val approachingTargetBox = detection(
            box = box(.40f, .20f, .60f, .80f),
            frame = 4L,
            timestamp = 4_000L,
        )
        val stillMissing = engine.associate(result.target, 4L, 4_000L, listOf(approachingTargetBox))
        assertTrue(stillMissing is TargetAssociationResult.TemporarilyMissing)

        val insideTargetBox = detection(
            box = box(.35f, .20f, .55f, .80f),
            frame = 5L,
            timestamp = 5_000L,
        )
        val neverMatchedAsA = engine.associate(stillMissing.target, 5L, 5_000L, listOf(insideTargetBox))
        assertFalse(neverMatchedAsA is TargetAssociationResult.Matched)
    }

    @Test fun `actual crossing becomes ambiguous and never transfers to B`() {
        val approaching = approachAWithB()
        val crossingA = detection(
            box = box(.36f, .20f, .56f, .80f),
            frame = 4L,
            timestamp = 4_000L,
        )
        val crossingB = detection(
            box = box(.365f, .20f, .565f, .80f),
            frame = 4L,
            timestamp = 4_000L,
        )

        val result = engine.associate(approaching, 4L, 4_000L, listOf(crossingA, crossingB))

        assertTrue(result is TargetAssociationResult.Ambiguous)
        assertEquals(approaching.boundingBox, result.target?.boundingBox)
        assertFalse(result.target?.boundingBox == crossingB.boundingBox)
        assertTrue(result.target?.identityUncertain == true)
    }

    @Test fun `unresolved crossing becomes lost and cannot auto reacquire B`() {
        val approaching = approachAWithB()
        val crossingA = detection(box = box(.36f, .20f, .56f, .80f), frame = 4L, timestamp = 4_000L)
        val crossingB = detection(box = box(.365f, .20f, .565f, .80f), frame = 4L, timestamp = 4_000L)
        val ambiguous = engine.associate(approaching, 4L, 4_000L, listOf(crossingA, crossingB))
            as TargetAssociationResult.Ambiguous
        val lostTimestamp = approaching.lastSeenSourceTimestampNanos +
            TargetAssociationEngine.MISSING_TIMEOUT_NANOS + 1L
        val bAlone = detection(
            box = box(.38f, .20f, .58f, .80f),
            frame = 5L,
            timestamp = lostTimestamp,
        )

        val lost = engine.associate(ambiguous.target, 5L, lostTimestamp, listOf(bAlone))
        assertTrue(lost is TargetAssociationResult.Lost)
        assertNull(lost.target)

        val afterLoss = engine.associate(
            lost.target,
            6L,
            lostTimestamp + 1L,
            listOf(bAlone.copy(frameSequence = 6L, sourceTimestampNanos = lostTimestamp + 1L)),
        )
        assertTrue(afterLoss is TargetAssociationResult.Lost)
        assertNull(afterLoss.target)
    }

    @Test fun `single occluding B is ambiguous rather than matched as A`() {
        val trackedAWithSeparateB = trackAWithSeparateB()
        val occludingB = detection(
            box = box(.52f, .20f, .72f, .80f),
            frame = 3L,
            timestamp = 3_000L,
        )

        val result = engine.associate(trackedAWithSeparateB, 3L, 3_000L, listOf(occludingB))

        assertTrue(result is TargetAssociationResult.Ambiguous)
        assertEquals(trackedAWithSeparateB.boundingBox, result.target?.boundingBox)
        assertFalse(result.target?.boundingBox == occludingB.boundingBox)
    }

    @Test fun `explicit reselection after lost tracks newly selected person`() {
        val selected = TargetSelection.select(detection(timestamp = 1_000L))
        val lostTimestamp = selected.lastSeenSourceTimestampNanos +
            TargetAssociationEngine.MISSING_TIMEOUT_NANOS + 1L
        val lost = engine.associate(selected, 2L, lostTimestamp, emptyList())
        assertTrue(lost is TargetAssociationResult.Lost)

        val selectedBDetection = detection(
            box = box(.52f, .20f, .72f, .80f),
            frame = 3L,
            timestamp = lostTimestamp + 1L,
        )
        val explicitlySelectedB = TargetSelection.select(selectedBDetection)
        val movedB = detection(
            box = box(.54f, .20f, .74f, .80f),
            frame = 4L,
            timestamp = lostTimestamp + 2L,
        )
        val result = engine.associate(explicitlySelectedB, 4L, lostTimestamp + 2L, listOf(movedB))
            as TargetAssociationResult.Matched

        assertEquals(movedB.boundingBox, result.target.boundingBox)
    }

    @Test fun `normal one-person motion remains matched across frames`() {
        val selected = TargetSelection.select(
            detection(box = box(.20f, .20f, .40f, .80f)),
        )
        val first = detection(box = box(.25f, .20f, .45f, .80f), frame = 2L, timestamp = 2_000L)
        val firstMatch = engine.associate(selected, 2L, 2_000L, listOf(first))
            as TargetAssociationResult.Matched
        val second = detection(box = box(.30f, .20f, .50f, .80f), frame = 3L, timestamp = 3_000L)
        val secondMatch = engine.associate(firstMatch.target, 3L, 3_000L, listOf(second))
            as TargetAssociationResult.Matched

        assertEquals(second.boundingBox, secondMatch.target.boundingBox)
    }

    @Test fun `short disappearance is temporarily missing`() {
        val selected = TargetSelection.select(detection(timestamp = 1_000L))

        val result = engine.associate(selected, 2L, 1_000L + TargetAssociationEngine.MISSING_TIMEOUT_NANOS, emptyList())

        assertTrue(result is TargetAssociationResult.TemporarilyMissing)
        assertEquals(selected, result.target)
    }

    @Test fun `bounded prediction matches consistent motion after one missed detector frame`() {
        val moving = movingTargetWithTwoMatches()
        val missing = engine.associate(moving, 4L, 400_000_000L, emptyList())
            as TargetAssociationResult.TemporarilyMissing
        val continued = detection(
            box = box(.61f, .20f, .81f, .80f),
            frame = 5L,
            timestamp = 500_000_000L,
        )

        val result = engine.associate(missing.target, 5L, 500_000_000L, listOf(continued))
            as TargetAssociationResult.Matched

        assertEquals(continued.boundingBox, result.target.boundingBox)
    }

    @Test fun `bounded prediction rejects implausible jump then still becomes lost`() {
        val moving = movingTargetWithTwoMatches()
        val implausible = detection(
            box = box(.90f, .20f, 1.00f, .80f),
            frame = 5L,
            timestamp = 800_000_000L,
        )

        val missing = engine.associate(moving, 5L, 800_000_000L, listOf(implausible))
        assertTrue(missing is TargetAssociationResult.TemporarilyMissing)

        val lostTimestamp = moving.lastSeenSourceTimestampNanos + TargetAssociationEngine.MISSING_TIMEOUT_NANOS + 1L
        val lost = engine.associate(missing.target, 6L, lostTimestamp, listOf(implausible.copy(frameSequence = 6L, sourceTimestampNanos = lostTimestamp)))
        assertTrue(lost is TargetAssociationResult.Lost)
        assertNull(lost.target)
    }

    @Test fun `prediction ignores match history older than five hundred milliseconds`() {
        val selected = TargetSelection.select(
            detection(box = box(.10f, .20f, .50f, .80f), frame = 1L, timestamp = 1L),
        )
        val first = engine.associate(
            selected,
            2L,
            100_000_000L,
            listOf(detection(box = box(.20f, .20f, .60f, .80f), frame = 2L, timestamp = 100_000_000L)),
        ) as TargetAssociationResult.Matched
        val second = engine.associate(
            first.target,
            3L,
            700_000_000L,
            listOf(detection(box = box(.30f, .20f, .70f, .80f), frame = 3L, timestamp = 700_000_000L)),
        ) as TargetAssociationResult.Matched
        val beyondStrictGate = detection(
            box = box(.51f, .20f, .91f, .80f),
            frame = 4L,
            timestamp = 800_000_000L,
        )

        val result = engine.associate(second.target, 4L, 800_000_000L, listOf(beyondStrictGate))

        assertTrue(result is TargetAssociationResult.TemporarilyMissing)
        assertEquals(second.target, result.target)
    }

    @Test fun `two prediction-plausible people remain ambiguous`() {
        val moving = movingTargetWithTwoMatches()
        val first = detection(box = box(.59f, .20f, .79f, .80f), frame = 5L, timestamp = 500_000_000L)
        val second = detection(box = box(.61f, .20f, .81f, .80f), frame = 5L, timestamp = 500_000_000L)

        val result = engine.associate(moving, 5L, 500_000_000L, listOf(first, second))

        assertTrue(result is TargetAssociationResult.Ambiguous)
        assertEquals(2, (result as TargetAssociationResult.Ambiguous).candidateCount)
        assertEquals(moving.boundingBox, result.target.boundingBox)
        assertTrue(result.target.identityUncertain)
    }

    @Test fun `absence beyond timeout is lost`() {
        val selected = TargetSelection.select(detection(timestamp = 1_000L))

        val result = engine.associate(selected, 2L, 1_001L + TargetAssociationEngine.MISSING_TIMEOUT_NANOS, emptyList())

        assertTrue(result is TargetAssociationResult.Lost)
        assertNull(result.target)
    }

    @Test fun `lost target never auto reacquires`() {
        val selected = TargetSelection.select(detection(timestamp = 1_000L))
        val lostTimestamp = selected.lastSeenSourceTimestampNanos + TargetAssociationEngine.MISSING_TIMEOUT_NANOS + 1L
        val lost = engine.associate(selected, 2L, lostTimestamp, emptyList())
        val candidate = detection(frame = 3L, timestamp = lostTimestamp + 1L)

        val result = engine.associate(lost.target, 3L, lostTimestamp + 1L, listOf(candidate))

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

    private fun movingTargetWithTwoMatches(): TrackedTarget {
        val selected = TargetSelection.select(
            detection(box = box(.20f, .20f, .40f, .80f), frame = 1L, timestamp = 100_000_000L),
        )
        val first = engine.associate(
            selected,
            2L,
            200_000_000L,
            listOf(detection(box = box(.30f, .20f, .50f, .80f), frame = 2L, timestamp = 200_000_000L)),
        ) as TargetAssociationResult.Matched
        return (engine.associate(
            first.target,
            3L,
            300_000_000L,
            listOf(detection(box = box(.40f, .20f, .60f, .80f), frame = 3L, timestamp = 300_000_000L)),
        ) as TargetAssociationResult.Matched).target
    }

    @Test fun `diagnostics explain an ineligible jitter box without changing fail closed result`() {
        val selected = TargetSelection.select(detection())
        val implausiblySmall = detection(
            box = box(.38f, .40f, .43f, .55f),
            frame = 2L,
            timestamp = 2_000L,
        )

        val evaluation = engine.evaluate(selected, 2L, 2_000L, listOf(implausiblySmall))

        assertTrue(evaluation.result is TargetAssociationResult.TemporarilyMissing)
        assertEquals(TargetAssociationDecision.TemporarilyMissing, evaluation.diagnostics.decision)
        assertEquals(0, evaluation.diagnostics.eligibleCandidateCount)
        val candidate = evaluation.diagnostics.candidates.single()
        assertFalse(candidate.eligible)
        assertTrue(candidate.strict.areaRatio < TargetAssociationEngine.MIN_AREA_RATIO)
        assertNull(evaluation.diagnostics.selectedDetectionIndex)
    }

    private fun trackAWithNearbyB(): TrackedTarget {
        val selectedA = TargetSelection.select(
            detection(box = box(.30f, .20f, .50f, .80f)),
        )
        val movedA = detection(box = box(.31f, .20f, .51f, .80f), frame = 2L, timestamp = 2_000L)
        val nearbyB = detection(box = box(.43f, .20f, .63f, .80f), frame = 2L, timestamp = 2_000L)
        return (engine.associate(selectedA, 2L, 2_000L, listOf(movedA, nearbyB))
            as TargetAssociationResult.Matched).target
    }

    private fun approachAWithB(): TrackedTarget {
        val nearby = trackAWithNearbyB()
        val movedA = detection(box = box(.32f, .20f, .52f, .80f), frame = 3L, timestamp = 3_000L)
        val approachingB = detection(box = box(.40f, .20f, .60f, .80f), frame = 3L, timestamp = 3_000L)
        return (engine.associate(nearby, 3L, 3_000L, listOf(movedA, approachingB))
            as TargetAssociationResult.Matched).target
    }

    private fun trackAWithSeparateB(): TrackedTarget {
        val selectedA = TargetSelection.select(
            detection(box = box(.40f, .20f, .60f, .80f)),
        )
        val movedA = detection(box = box(.42f, .20f, .62f, .80f), frame = 2L, timestamp = 2_000L)
        val separateB = detection(box = box(.62f, .20f, .82f, .80f), frame = 2L, timestamp = 2_000L)
        return (engine.associate(selectedA, 2L, 2_000L, listOf(movedA, separateB))
            as TargetAssociationResult.Matched).target
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
