package com.alonibh.tellodrone.ui

import com.alonibh.tellodrone.domain.NormalizedBoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class VideoOverlayCoordinateMapperTest {
    @Test fun `current target detection is not rendered as a yellow person`() {
        val detection = com.alonibh.tellodrone.domain.PersonDetection(NormalizedBoundingBox(.2f, .2f, .5f, .8f), .9f, 4L, 5L)
        val state = com.alonibh.tellodrone.domain.DroneSessionState(target = com.alonibh.tellodrone.domain.TargetSelection.select(detection))
        assertTrue(state.isCurrentTargetDetection(detection))
        assertFalse(state.isCurrentTargetDetection(detection.copy(boundingBox = NormalizedBoundingBox(.21f, .2f, .5f, .8f))))
    }
    @Test fun `maps normalized analysis surface box into stretched preview overlay`() {
        val result = VideoOverlayCoordinateMapper.mapFillBounds(
            NormalizedBoundingBox(.25f, .10f, .75f, .90f),
            overlayWidth = 800f,
            overlayHeight = 450f,
        )!!

        assertEquals(200f, result.left, 0f)
        assertEquals(45f, result.top, 0f)
        assertEquals(600f, result.right, 0f)
        assertEquals(405f, result.bottom, 0f)
    }

    @Test fun `clamps overlay box and rejects empty result`() {
        val clamped = VideoOverlayCoordinateMapper.mapFillBounds(
            NormalizedBoundingBox(-1f, -.5f, 2f, 1.5f),
            640f,
            360f,
        )!!
        assertEquals(OverlayPixelRect(0f, 0f, 640f, 360f), clamped)
        assertNull(VideoOverlayCoordinateMapper.mapFillBounds(NormalizedBoundingBox(.5f, 0f, .5f, 1f), 10f, 10f))
    }
}
