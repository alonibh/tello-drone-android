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
        assertEquals(listOf(.50f, .91f), results.map { it.confidence })
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
            raw("person", .8f, index.toFloat(), 0f, index + 10f, 20f)
        }

        assertEquals(5, PersonDetectionMapper.map(raw, metadata()).size)
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
