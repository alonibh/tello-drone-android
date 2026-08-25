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
        assertNull(calibrator.add(2L, 2L, boxForScale(.2f)))
        assertNull(calibrator.add(1L, 3L, boxForScale(.2f)))
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

    @Test fun `fresh stable target with edge-clipped box can start calibration`() {
        val clippedBox = NormalizedBoundingBox(0.01f, .2f, .4f, .8f)
        val state = DroneSessionState(
            connection = DroneConnectionState.Connected,
            video = VideoState(availability = VideoAvailability.Streaming, personDetectionState = PersonDetectionState.Detecting),
            target = target(clippedBox),
            targetAssociationState = TargetAssociationState.Matched,
            trackingErrors = TrackingErrors(0f, 0f, 0f, targetFresh = true, distanceCalibrated = false),
        )
        assertEquals(FollowDistanceEligibilityReason.READY, FollowDistanceEligibility.evaluate(state))
    }

    @Test fun `clipped frames still do not count as calibration samples`() {
        val calibrator = FollowDistanceCalibrator()
        calibrator.start(0L)
        val clippedBox = NormalizedBoundingBox(0.01f, .2f, .4f, .8f)
        assertNull(calibrator.add(1L, 1L, clippedBox))
        assertEquals(0, calibrator.sampleCount)

        val validBox = NormalizedBoundingBox(.2f, .2f, .6f, .6f)
        assertNull(calibrator.add(2L, 2L, validBox))
        assertEquals(1, calibrator.sampleCount)

        assertNull(calibrator.add(3L, 3L, clippedBox))
        assertEquals(1, calibrator.sampleCount)
    }

    @Test fun `unstable or missing target still cannot start calibration`() {
        val baseReady = DroneSessionState(
            connection = DroneConnectionState.Connected,
            video = VideoState(availability = VideoAvailability.Streaming, personDetectionState = PersonDetectionState.Detecting),
            target = target(boxForScale(.3f)),
            targetAssociationState = TargetAssociationState.Matched,
            trackingErrors = TrackingErrors(0f, 0f, 0f, targetFresh = true, distanceCalibrated = false),
        )

        assertEquals(
            FollowDistanceEligibilityReason.SELECT_A_PERSON,
            FollowDistanceEligibility.evaluate(baseReady.copy(target = null)),
        )
        assertEquals(
            FollowDistanceEligibilityReason.TARGET_NOT_STABLE,
            FollowDistanceEligibility.evaluate(baseReady.copy(targetAssociationState = TargetAssociationState.TemporarilyMissing)),
        )
        assertEquals(
            FollowDistanceEligibilityReason.TARGET_NOT_STABLE,
            FollowDistanceEligibility.evaluate(baseReady.copy(targetAssociationState = TargetAssociationState.Lost)),
        )
        assertEquals(
            FollowDistanceEligibilityReason.TARGET_NOT_STABLE,
            FollowDistanceEligibility.evaluate(baseReady.copy(targetAssociationState = TargetAssociationState.Ambiguous)),
        )
        assertEquals(
            FollowDistanceEligibilityReason.TARGET_NOT_STABLE,
            FollowDistanceEligibility.evaluate(baseReady.copy(trackingErrors = TrackingErrors(0f, 0f, 0f, targetFresh = false, distanceCalibrated = false))),
        )
        assertEquals(
            FollowDistanceEligibilityReason.SELECT_A_PERSON,
            FollowDistanceEligibility.evaluate(baseReady.copy(connection = DroneConnectionState.Disconnected)),
        )
        assertEquals(
            FollowDistanceEligibilityReason.SELECT_A_PERSON,
            FollowDistanceEligibility.evaluate(baseReady.copy(video = VideoState(availability = VideoAvailability.Unavailable, personDetectionState = PersonDetectionState.Detecting))),
        )
        assertEquals(
            FollowDistanceEligibilityReason.SELECT_A_PERSON,
            FollowDistanceEligibility.evaluate(baseReady.copy(video = VideoState(availability = VideoAvailability.Streaming, personDetectionState = PersonDetectionState.Off))),
        )
        assertEquals(
            FollowDistanceEligibilityReason.CALIBRATING,
            FollowDistanceEligibility.evaluate(baseReady.copy(followDistanceCalibrationState = FollowDistanceCalibrationState.Calibrating)),
        )
    }

    private fun boxForScale(scale: Float) = NormalizedBoundingBox(.5f - scale / 2, .5f - scale / 2, .5f + scale / 2, .5f + scale / 2)
    private fun target(box: NormalizedBoundingBox) = TrackedTarget(box, .9f, 1L, 1L)
}
// SPDX-License-Identifier: AGPL-3.0-only
