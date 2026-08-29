package com.alonibh.tellodrone.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetAssociationEngineTest {
    private val engine = TargetAssociationEngine()

    @Test fun `selected person with safe geometry and appearance remains matched`() {
        val selected = TargetSelection.select(detection(appearance = appearanceA))
        val next = detection(box(.32f, .20f, .52f, .80f), frame = 2, timestamp = 100_000_000, appearance = appearanceA)

        val result = engine.associate(selected, 2, 100_000_000, listOf(next)) as TargetAssociationResult.Matched

        assertEquals(next.boundingBox, result.target.boundingBox)
        assertEquals(2, result.target.lastSeenFrameSequence)
    }

    @Test fun `appearance gate prevents a nearby wrong person match`() {
        val selected = TargetSelection.select(detection(appearance = appearanceA))
        val wrong = detection(box(.31f, .20f, .51f, .80f), frame = 2, timestamp = 100_000_000, appearance = appearanceB)

        val result = engine.associate(selected, 2, 100_000_000, listOf(wrong))

        assertTrue(result is TargetAssociationResult.TemporarilyMissing)
    }

    @Test fun `confidence gate is fail closed`() {
        val selected = TargetSelection.select(detection(appearance = appearanceA))
        val low = detection(frame = 2, timestamp = 100_000_000, confidence = .29f, appearance = appearanceA)
        assertTrue(engine.associate(selected, 2, 100_000_000, listOf(low)) is TargetAssociationResult.TemporarilyMissing)
    }

    @Test fun `close candidate may pass distance when overlap is below IoU gate`() {
        val selected = TargetSelection.select(detection(box(.10f, .20f, .30f, .80f), appearance = appearanceA))
        val next = detection(box(.29f, .20f, .49f, .80f), frame = 2, timestamp = 100_000_000, appearance = appearanceA)
        assertTrue(engine.associate(selected, 2, 100_000_000, listOf(next)) is TargetAssociationResult.Matched)
    }

    @Test fun `similar eligible candidates are ambiguous and never silently chosen`() {
        val selected = TargetSelection.select(detection(appearance = appearanceA))
        val first = detection(box(.29f, .20f, .49f, .80f), frame = 2, timestamp = 100_000_000, confidence = .80f, appearance = appearanceA)
        val second = detection(box(.31f, .20f, .51f, .80f), frame = 2, timestamp = 100_000_000, confidence = .80f, appearance = appearanceA)

        val result = engine.associate(selected, 2, 100_000_000, listOf(first, second))

        assertTrue(result is TargetAssociationResult.Ambiguous)
        assertSame(selected, result.target)
    }

    @Test fun `missing TTL is exactly four hundred milliseconds`() {
        val selected = TargetSelection.select(detection(timestamp = 1_000_000_000, appearance = appearanceA))
        assertTrue(engine.associate(selected, 2, 1_399_999_999, emptyList()) is TargetAssociationResult.TemporarilyMissing)
        val lost = engine.associate(selected, 2, 1_400_000_000, emptyList())
        assertTrue(lost is TargetAssociationResult.Lost)
        assertNull(lost.target)
    }

    @Test fun `lost cannot reacquire without explicit selection`() {
        val selected = TargetSelection.select(detection(timestamp = 1_000_000_000, appearance = appearanceA))
        val lost = engine.associate(selected, 2, 1_400_000_000, emptyList())
        assertTrue(lost is TargetAssociationResult.Lost)
        val visible = detection(frame = 3, timestamp = 1_500_000_000, appearance = appearanceA)
        assertTrue(engine.associate(lost.target, 3, 1_500_000_000, listOf(visible)) is TargetAssociationResult.Lost)
        val reselected = TargetSelection.select(visible)
        assertEquals(3, reselected.selectedFrameSequence)
    }

    @Test fun `older detector frame is ignored`() {
        val selected = TargetSelection.select(detection(frame = 2, timestamp = 2_000, appearance = appearanceA))
        val result = engine.associate(selected, 1, 1_000, listOf(detection(appearance = appearanceA)))
        assertTrue(result is TargetAssociationResult.Ignored)
        assertSame(selected, result.target)
    }

    @Test fun `diagnostics expose frozen appearance and geometry gates`() {
        val selected = TargetSelection.select(detection(appearance = appearanceA))
        val candidate = detection(frame = 2, timestamp = 2_000, appearance = appearanceB)
        val evaluation = engine.evaluate(selected, 2, 2_000, listOf(candidate))
        assertEquals(TargetAssociationDecision.TemporarilyMissing, evaluation.diagnostics.decision)
        assertEquals(0, evaluation.diagnostics.eligibleCandidateCount)
        assertTrue(evaluation.diagnostics.candidates.single().strict.appearanceSimilarity < TargetAssociationEngine.MIN_APPEARANCE_SIMILARITY)
        assertEquals(
            setOf(TargetAssociationRejectionReason.APPEARANCE),
            evaluation.diagnostics.candidates.single().strict.rejectionReasons,
        )
    }

    @Test fun `unique continuously visible person remains matched across irregular detector intervals`() {
        var target = TargetSelection.select(
            detection(box(.20f, .20f, .40f, .80f), timestamp = 1_000_000_000L, appearance = appearanceA),
        )
        val observations = listOf(
            detection(box(.56f, .20f, .76f, .80f), frame = 2, timestamp = 1_200_000_000L, appearance = appearanceA),
            detection(box(.69f, .19f, .90f, .81f), frame = 3, timestamp = 1_335_000_000L, appearance = appearanceA),
            detection(box(.76f, .18f, .98f, .82f), frame = 4, timestamp = 1_515_000_000L, appearance = appearanceA),
        )

        observations.forEach { observation ->
            val evaluation = engine.evaluate(
                target,
                observation.frameSequence,
                observation.sourceTimestampNanos,
                listOf(observation),
            )
            assertEquals(TargetAssociationDecision.Matched, evaluation.diagnostics.decision)
            assertTrue(evaluation.result is TargetAssociationResult.Matched)
            target = evaluation.result.target!!
        }
    }

    @Test fun `time aware continuity bonus requires one strong appearance candidate`() {
        val selected = TargetSelection.select(
            detection(box(.20f, .20f, .40f, .80f), timestamp = 1_000_000_000L, appearance = appearanceA),
        )
        val moving = detection(
            box(.58f, .20f, .78f, .80f),
            frame = 2,
            timestamp = 1_300_000_000L,
            appearance = appearanceA,
        )
        val competitor = detection(
            box(.80f, .20f, .98f, .80f),
            frame = 2,
            timestamp = 1_300_000_000L,
            appearance = appearanceB,
        )

        val unique = engine.evaluate(selected, 2, 1_300_000_000L, listOf(moving))
        val crowded = engine.evaluate(selected, 2, 1_300_000_000L, listOf(moving, competitor))

        assertTrue(unique.diagnostics.candidates.single().strict.usedTimeAwareContinuity)
        assertEquals(TargetAssociationDecision.Matched, unique.diagnostics.decision)
        assertEquals(TargetAssociationEngine.MAX_CENTER_DISPLACEMENT, crowded.diagnostics.candidates.first().strict.maximumCenterDisplacement)
        assertTrue(crowded.diagnostics.candidates.none { it.strict.usedTimeAwareContinuity })
        assertEquals(
            crowded.diagnostics.candidates.toString(),
            TargetAssociationDecision.TemporarilyMissing,
            crowded.diagnostics.decision,
        )
    }

    @Test fun `diagnostics identify every failed association gate without automatic reassignment`() {
        val selected = TargetSelection.select(detection(appearance = appearanceA))
        val rejected = detection(
            box(.75f, .20f, .79f, .30f),
            confidence = .20f,
            frame = 2,
            timestamp = 200_000_000L,
            appearance = appearanceB,
        )
        val evaluation = engine.evaluate(selected, 2, 200_000_000L, listOf(rejected))

        assertEquals(TargetAssociationDecision.TemporarilyMissing, evaluation.diagnostics.decision)
        assertEquals(
            TargetAssociationRejectionReason.entries.toSet(),
            evaluation.diagnostics.candidates.single().strict.rejectionReasons,
        )
        assertSame(selected, evaluation.result.target)
    }

    @Test fun `frozen constants match validated benchmark`() {
        assertEquals(.30f, TargetAssociationEngine.DETECTOR_CONFIDENCE)
        assertEquals(.15f, TargetAssociationEngine.MIN_IOU)
        assertEquals(.24f, TargetAssociationEngine.MAX_CENTER_DISPLACEMENT)
        assertEquals(.45f, TargetAssociationEngine.MIN_APPEARANCE_SIMILARITY)
        assertEquals(.10f, TargetAssociationEngine.AMBIGUITY_MARGIN)
        assertEquals(400_000_000L, TargetAssociationEngine.MISSING_TIMEOUT_NANOS)
    }

    private fun detection(
        box: NormalizedBoundingBox = box(.30f, .20f, .50f, .80f),
        confidence: Float = .9f,
        frame: Long = 1,
        timestamp: Long = 0,
        appearance: HsvAppearanceHistogram,
    ) = PersonDetection(box, confidence, frame, timestamp, appearance)

    private fun box(left: Float, top: Float, right: Float, bottom: Float) =
        NormalizedBoundingBox(left, top, right, bottom)

    private val appearanceA = histogram(0)
    private val appearanceB = HsvAppearanceHistogram(
        List(HsvAppearanceHistogram.BIN_COUNT) { if (it == 0) 0f else 1f },
    )

    private fun histogram(index: Int) = HsvAppearanceHistogram(
        List(HsvAppearanceHistogram.BIN_COUNT) { if (it == index) 1f else 0f },
    )
}
// SPDX-License-Identifier: AGPL-3.0-only
