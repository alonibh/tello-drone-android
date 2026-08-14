package com.alonibh.tellodrone.vision

import com.alonibh.tellodrone.tello.AnalysisFrameMetadata
import com.alonibh.tellodrone.tello.AnalysisPixelRepresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonDetectionMapperTest {
    @Test fun `maps detector pixels to normalized person result metadata`() {
        val result = PersonDetectionMapper.map(
            listOf(raw("person", .82f, 32f, 24f, 160f, 120f)),
            metadata(),
        ).single()

        assertEquals(.1f, result.boundingBox.left, 0.0001f)
        assertEquals(.1f, result.boundingBox.top, 0.0001f)
        assertEquals(.5f, result.boundingBox.right, 0.0001f)
        assertEquals(.5f, result.boundingBox.bottom, 0.0001f)
        assertEquals(.82f, result.confidence, 0f)
        assertEquals(41L, result.frameSequence)
        assertEquals(900L, result.sourceTimestampNanos)
    }

    @Test fun `filters app owned category and confidence and supports multiple people`() {
        val raw = listOf(
            raw("dog", .99f, 0f, 0f, 10f, 10f),
            raw("person", .49f, 0f, 0f, 10f, 10f),
            raw("person", .50f, 1f, 2f, 30f, 40f),
            raw("person", .91f, 100f, 20f, 200f, 220f),
        )

        val results = PersonDetectionMapper.map(raw, metadata())

        assertEquals(2, results.size)
        assertEquals(listOf(.91f, .50f), results.map { it.confidence })
    }

    @Test fun `applies configurable minConfidence threshold`() {
        val raw = listOf(
            raw("person", .52f, 1f, 2f, 30f, 40f),
            raw("person", .64f, 50f, 2f, 80f, 40f),
            raw("person", .75f, 100f, 20f, 200f, 220f),
            raw("person", .88f, 210f, 20f, 300f, 220f),
        )

        val atDefault = PersonDetectionMapper.map(raw, metadata(), 0.50f)
        assertEquals(4, atDefault.size)

        val at65 = PersonDetectionMapper.map(raw, metadata(), 0.65f)
        assertEquals(listOf(.88f, .75f), at65.map { it.confidence })

        val at80 = PersonDetectionMapper.map(raw, metadata(), 0.80f)
        assertEquals(listOf(.88f), at80.map { it.confidence })
    }

    @Test fun `clamps partially out of range boxes and rejects malformed boxes`() {
        val raw = listOf(
            raw("person", .8f, -10f, -20f, 330f, 250f),
            raw("person", .8f, 20f, 10f, 20f, 50f),
            raw("person", .8f, Float.NaN, 0f, 10f, 10f),
            raw("person", .8f, 400f, 10f, 500f, 50f),
        )

        val result = PersonDetectionMapper.map(raw, metadata()).single()

        assertEquals(0f, result.boundingBox.left, 0f)
        assertEquals(0f, result.boundingBox.top, 0f)
        assertEquals(1f, result.boundingBox.right, 0f)
        assertEquals(1f, result.boundingBox.bottom, 0f)
        assertTrue(result.confidence in 0f..1f)
    }

    @Test fun `returns at most five people`() {
        val raw = (0 until 8).map { index ->
            raw("person", .8f, (index * 30).toFloat(), 0f, index * 30 + 10f, 20f)
        }

        assertEquals(5, PersonDetectionMapper.map(raw, metadata()).size)
    }

    @Test fun `near identical duplicate boxes retain the stronger detection and frame identity`() {
        val results = PersonDetectionMapper.map(
            listOf(
                raw("person", .72f, 64f, 36f, 176f, 212f),
                raw("person", .91f, 66f, 38f, 178f, 214f),
            ),
            metadata(),
        )

        assertEquals(1, results.size)
        assertEquals(.91f, results.single().confidence, 0f)
        assertEquals(41L, results.single().frameSequence)
        assertEquals(900L, results.single().sourceTimestampNanos)
    }

    @Test fun `physical broad and narrow overlapping boxes with shifted centers are deduplicated`() {
        val results = PersonDetectionMapper.map(listOf(
            raw("person", .91f, 64f, 28f, 184f, 220f),
            raw("person", .72f, 86f, 40f, 166f, 212f),
        ), metadata())
        assertEquals(1, results.size)
        assertEquals(.91f, results.single().confidence, 0f)
    }

    @Test fun `nested high overlap same person box is suppressed`() {
        val results = PersonDetectionMapper.map(
            listOf(
                raw("person", .88f, 64f, 36f, 176f, 212f),
                raw("person", .70f, 70f, 44f, 170f, 204f),
            ),
            metadata(),
        )

        assertEquals(1, results.size)
        assertEquals(.88f, results.single().confidence, 0f)
    }

    @Test fun `confidence tie keeps deterministic geometry order`() {
        val results = PersonDetectionMapper.map(
            listOf(
                raw("person", .80f, 66f, 38f, 178f, 214f),
                raw("person", .80f, 64f, 36f, 176f, 212f),
            ),
            metadata(),
        )

        assertEquals(1, results.size)
        assertEquals(.2f, results.single().boundingBox.left, .0001f)
    }

    @Test fun `distinct side by side partial overlap and materially different people are retained`() {
        val sideBySide = PersonDetectionMapper.map(
            listOf(
                raw("person", .90f, 20f, 30f, 100f, 210f),
                raw("person", .80f, 120f, 30f, 200f, 210f), // side by side
            ),
            metadata(),
        )
        val partialOverlap = PersonDetectionMapper.map(
            listOf(
                raw("person", .90f, 20f, 30f, 100f, 210f),
                raw("person", .75f, 80f, 35f, 160f, 215f), // partial overlap, separated center
            ),
            metadata(),
        )
        val substantiallyDifferent = PersonDetectionMapper.map(
            listOf(
                raw("person", .70f, 64f, 36f, 176f, 212f),
                raw("person", .69f, 66f, 40f, 122f, 126f), // high overlap but substantially smaller
            ),
            metadata(),
        )

        assertEquals(2, sideBySide.size)
        assertEquals(2, partialOverlap.size)
        assertEquals(2, substantiallyDifferent.size)
    }

    @Test fun `duplicate geometry predicate exposes conservative normalized metrics`() {
        val first = com.alonibh.tellodrone.domain.NormalizedBoundingBox(.2f, .2f, .6f, .8f)
        val duplicate = com.alonibh.tellodrone.domain.NormalizedBoundingBox(.21f, .21f, .61f, .81f)
        val separate = com.alonibh.tellodrone.domain.NormalizedBoundingBox(.5f, .2f, .9f, .8f)

        assertTrue(PersonDetectionDeduplicator.centerDistance(first, duplicate) <= .08f)
        assertTrue(PersonDetectionDeduplicator.intersectionOverSmaller(first, duplicate) >= .75f)
        assertTrue(PersonDetectionDeduplicator.areaRatio(duplicate, first) in .60f..1.67f)
        assertTrue(!PersonDetectionDeduplicator.areSamePhysicalObject(first, separate))
    }

    private fun metadata() = AnalysisFrameMetadata(
        width = 320,
        height = 240,
        captureTimestampNanos = 900L,
        pixelRepresentation = AnalysisPixelRepresentation.ARGB_8888_BITMAP,
        sequence = 41L,
    )

    private fun raw(category: String, confidence: Float, left: Float, top: Float, right: Float, bottom: Float) =
        RawObjectDetection(category, confidence, left, top, right, bottom)
}
