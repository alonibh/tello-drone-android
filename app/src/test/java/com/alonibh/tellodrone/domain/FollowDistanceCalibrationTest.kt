package com.alonibh.tellodrone.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowDistanceCalibrationTest {
    @Test fun `seven newer valid samples use median visual scale and reject duplicate frames`() {
        val calibrator = FollowDistanceCalibrator(); calibrator.start(0)
        val scales = listOf(.20f, .21f, .22f, .50f, .23f, .24f, .25f)
        val reference = scales.mapIndexed { index, scale ->
            calibrator.add(index.toLong(), index.toLong(), boxForScale(scale))
        }.last()
        assertEquals(.23f, reference!!.visualScale, .0001f)

        calibrator.start(0)
        assertNull(calibrator.add(1L, 1L, boxForScale(.2f)))
        assertNull(calibrator.add(1L, 2L, boxForScale(.2f)))
    }

    @Test fun `clipped invalid and timed out calibration never produces reference`() {
        val calibrator = FollowDistanceCalibrator(); calibrator.start(0)
        assertNull(calibrator.add(1L, 1L, NormalizedBoundingBox(0f, .2f, .4f, .8f)))
        assertTrue(calibrator.timedOut(FollowDistanceCalibrator.TIMEOUT_NANOS + 1L))
    }

    @Test fun `calibrated visual scale keeps sign deadzone and yaw vertical contract`() {
        val engine = TrackingErrorEngine()
        val reference = FollowDistanceReference(.3f, 1L, 1L, 7)
        val atReference = engine.update(target(boxForScale(.3f)), true, reference)
        assertEquals(0f, atReference.forwardBackError, 0f)
        engine.reset()
        assertTrue(engine.update(target(boxForScale(.2f)), true, reference).forwardBackError > 0f)
        engine.reset()
        assertTrue(engine.update(target(boxForScale(.4f)), true, reference).forwardBackError < 0f)
        engine.reset()
        assertEquals(0f, engine.update(target(boxForScale(.31f)), true, reference).forwardBackError, 0f)
        val uncalibrated = engine.update(target(boxForScale(.2f)), true)
        assertEquals(0f, uncalibrated.forwardBackError, 0f)
        assertTrue(!uncalibrated.distanceCalibrated)
    }

    private fun boxForScale(scale: Float) = NormalizedBoundingBox(.5f - scale / 2, .5f - scale / 2, .5f + scale / 2, .5f + scale / 2)
    private fun target(box: NormalizedBoundingBox) = TrackedTarget(box, .9f, 1L, 1L)
}
