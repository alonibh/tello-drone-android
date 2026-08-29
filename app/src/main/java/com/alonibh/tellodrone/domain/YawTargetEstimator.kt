package com.alonibh.tellodrone.domain

import kotlin.math.abs

data class YawTargetEstimate(
    val measuredCenterX: Float,
    val estimatedCenterX: Float,
    val velocityPerSecond: Float,
    val predictionHorizonMillis: Long,
)

/**
 * Small source-timestamp-aware image-space estimator. It is intentionally identity-blind: callers
 * may feed it only consecutive accepted matches for the already selected target and must reset it
 * whenever that identity is missing, ambiguous, lost, or explicitly replaced.
 */
class YawTargetEstimator(
    private val velocityAlpha: Float = VELOCITY_ALPHA,
    private val jitterDelta: Float = JITTER_DELTA,
    private val jitterVelocityDecay: Float = JITTER_VELOCITY_DECAY,
    private val maximumVelocityPerSecond: Float = MAXIMUM_VELOCITY_PER_SECOND,
    private val maximumPredictionHorizonMillis: Long = MAXIMUM_PREDICTION_HORIZON_MILLIS,
    private val maximumPredictedOffset: Float = MAXIMUM_PREDICTED_OFFSET,
) {
    private var previousCenterX: Float? = null
    private var previousSourceTimestampNanos: Long? = null
    private var velocityPerSecond = 0f

    init {
        require(velocityAlpha in 0f..1f)
        require(jitterDelta >= 0f)
        require(jitterVelocityDecay in 0f..1f)
        require(maximumVelocityPerSecond > 0f)
        require(maximumPredictionHorizonMillis >= 0L)
        require(maximumPredictedOffset >= 0f)
    }

    fun update(centerX: Float, sourceTimestampNanos: Long, perceptionAgeNanos: Long): YawTargetEstimate {
        require(centerX.isFinite() && centerX in 0f..1f)
        require(sourceTimestampNanos >= 0L)
        val previousCenter = previousCenterX
        val previousTimestamp = previousSourceTimestampNanos
        if (previousCenter != null && previousTimestamp != null && sourceTimestampNanos > previousTimestamp) {
            val elapsedSeconds = (sourceTimestampNanos - previousTimestamp) / NANOS_PER_SECOND
            if (elapsedSeconds in MINIMUM_ESTIMATOR_INTERVAL_SECONDS..MAXIMUM_ESTIMATOR_INTERVAL_SECONDS) {
                val delta = centerX - previousCenter
                velocityPerSecond = if (abs(delta) <= jitterDelta) {
                    velocityPerSecond * jitterVelocityDecay
                } else {
                    val observed = (delta / elapsedSeconds).coerceIn(
                        -maximumVelocityPerSecond,
                        maximumVelocityPerSecond,
                    )
                    (velocityAlpha * observed + (1f - velocityAlpha) * velocityPerSecond).coerceIn(
                        -maximumVelocityPerSecond,
                        maximumVelocityPerSecond,
                    )
                }
            } else {
                velocityPerSecond = 0f
            }
        } else {
            velocityPerSecond = 0f
        }
        previousCenterX = centerX
        previousSourceTimestampNanos = sourceTimestampNanos

        val horizonMillis = (perceptionAgeNanos.coerceAtLeast(0L) / NANOS_PER_MILLISECOND)
            .coerceAtMost(maximumPredictionHorizonMillis)
        val predictedOffset = (velocityPerSecond * horizonMillis / MILLIS_PER_SECOND)
            .coerceIn(-maximumPredictedOffset, maximumPredictedOffset)
        var estimatedCenter = (centerX + predictedOffset).coerceIn(0f, 1f)
        // Prediction may brake at center, but never extrapolates directly through it into reversal.
        if ((centerX - CENTER_X) * (estimatedCenter - CENTER_X) < 0f) estimatedCenter = CENTER_X
        return YawTargetEstimate(centerX, estimatedCenter, velocityPerSecond, horizonMillis)
    }

    /** Seeds the current centered/crossing measurement with zero velocity. */
    fun brake(centerX: Float, sourceTimestampNanos: Long): YawTargetEstimate {
        velocityPerSecond = 0f
        previousCenterX = centerX
        previousSourceTimestampNanos = sourceTimestampNanos
        return YawTargetEstimate(centerX, centerX, 0f, 0L)
    }

    fun reset() {
        previousCenterX = null
        previousSourceTimestampNanos = null
        velocityPerSecond = 0f
    }

    companion object {
        const val VELOCITY_ALPHA = .65f
        const val JITTER_DELTA = .012f
        const val JITTER_VELOCITY_DECAY = .25f
        const val MAXIMUM_VELOCITY_PER_SECOND = .80f
        const val MAXIMUM_PREDICTION_HORIZON_MILLIS = 120L
        const val MAXIMUM_PREDICTED_OFFSET = .08f
        private const val CENTER_X = .5f
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val MILLIS_PER_SECOND = 1_000f
        private const val MINIMUM_ESTIMATOR_INTERVAL_SECONDS = .02f
        private const val MAXIMUM_ESTIMATOR_INTERVAL_SECONDS = .35f
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
