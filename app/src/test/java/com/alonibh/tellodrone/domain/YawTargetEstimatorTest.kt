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
