package com.alonibh.tellodrone.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YawTargetEstimatorTest {
    @Test fun `consistent source-time motion predicts only bounded perception delay`() {
        val estimator = YawTargetEstimator()
        estimator.update(.50f, 1_000_000_000L, 0L)
        val estimate = estimator.update(.58f, 1_100_000_000L, 200_000_000L)
        assertEquals(YawTargetEstimator.MAXIMUM_PREDICTION_HORIZON_MILLIS, estimate.predictionHorizonMillis)
        assertTrue(estimate.estimatedCenterX > estimate.measuredCenterX)
        assertTrue(estimate.estimatedCenterX - estimate.measuredCenterX <= YawTargetEstimator.MAXIMUM_PREDICTED_OFFSET)
        assertTrue(estimate.velocityPerSecond <= YawTargetEstimator.MAXIMUM_VELOCITY_PER_SECOND)
        assertEquals(TargetPredictionMode.NORMAL, estimate.predictionMode)
    }

    @Test fun `high drone yaw rate suppresses velocity prediction completely`() {
        val estimator = YawTargetEstimator()
        estimator.update(.50f, 1_000_000_000L, 0L)
        // Drone spinning at 80 deg/s (above 70 deg/s full suppression threshold)
        val estimate = estimator.update(
            centerX = .65f,
            sourceTimestampNanos = 1_100_000_000L,
            perceptionAgeNanos = 150_000_000L,
            physicalYawRateDegreesPerSecond = 80f,
        )
        assertEquals(0.65f, estimate.estimatedCenterX)
        assertEquals(0f, estimate.appliedPredictedOffset)
        assertEquals(TargetPredictionMode.CLAMPED_FOR_YAW_RATE, estimate.predictionMode)
    }

    @Test fun `moderate drone yaw rate attenuates velocity prediction`() {
        val estimator = YawTargetEstimator()
        estimator.update(.50f, 1_000_000_000L, 0L)

        // Moderate drone rate: 47.5 deg/s (halfway between 25 and 70 -> attenuation factor = 0.5)
        val estimate = estimator.update(
            centerX = .60f,
            sourceTimestampNanos = 1_100_000_000L,
            perceptionAgeNanos = 150_000_000L,
            physicalYawRateDegreesPerSecond = 47.5f,
        )
        assertEquals(TargetPredictionMode.CLAMPED_FOR_YAW_RATE, estimate.predictionMode)
        assertTrue(estimate.appliedPredictedOffset > 0f)
        assertTrue(estimate.appliedPredictedOffset < estimate.rawPredictedOffset)
    }

    @Test fun `settling phase disables velocity prediction`() {
        val estimator = YawTargetEstimator()
        estimator.update(.50f, 1_000_000_000L, 0L)
        val estimate = estimator.update(
            centerX = .60f,
            sourceTimestampNanos = 1_100_000_000L,
            perceptionAgeNanos = 150_000_000L,
            isSettling = true,
        )
        assertEquals(0.60f, estimate.estimatedCenterX)
        assertEquals(0f, estimate.appliedPredictedOffset)
        assertEquals(TargetPredictionMode.DISABLED_SETTLING, estimate.predictionMode)
    }

    @Test fun `stationary bbox jitter decays velocity rather than accumulating prediction`() {
        val estimator = YawTargetEstimator()
        estimator.update(.50f, 1_000_000_000L, 0L)
        estimator.update(.58f, 1_100_000_000L, 40_000_000L)
        var estimate = estimator.update(.585f, 1_200_000_000L, 40_000_000L)
        repeat(5) { index ->
            estimate = estimator.update(
                if (index % 2 == 0) .581f else .586f,
                1_300_000_000L + index * 100_000_000L,
                40_000_000L,
            )
        }
        assertTrue(kotlin.math.abs(estimate.velocityPerSecond) < .01f)
    }

    @Test fun `prediction brakes at center and never extrapolates into reversal`() {
        val estimator = YawTargetEstimator()
        estimator.update(.65f, 1_000_000_000L, 0L)
        val estimate = estimator.update(.53f, 1_100_000_000L, 120_000_000L)
        assertEquals(.5f, estimate.estimatedCenterX)
    }

    @Test fun `reset removes velocity history across identity uncertainty`() {
        val estimator = YawTargetEstimator()
        estimator.update(.40f, 1_000_000_000L, 0L)
        estimator.update(.50f, 1_100_000_000L, 100_000_000L)
        estimator.reset()
        val freshIdentity = estimator.update(.60f, 2_000_000_000L, 100_000_000L)
        assertEquals(0f, freshIdentity.velocityPerSecond)
        assertEquals(.60f, freshIdentity.estimatedCenterX)
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
