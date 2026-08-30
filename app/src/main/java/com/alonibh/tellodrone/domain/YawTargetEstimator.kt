package com.alonibh.tellodrone.domain

import kotlin.math.abs

enum class TargetPredictionMode {
    NORMAL,
    CLAMPED_FOR_YAW_RATE,
    DISABLED_SETTLING,
    DISABLED_ANOMALY,
    DISABLED_RECOVERY,
}

data class YawTargetEstimate(
    val measuredCenterX: Float,
    val estimatedCenterX: Float,
    val velocityPerSecond: Float,
    val predictionHorizonMillis: Long,
    val predictionMode: TargetPredictionMode = TargetPredictionMode.NORMAL,
    val rawPredictedOffset: Float = 0f,
    val appliedPredictedOffset: Float = 0f,
)

/**
 * Small source-timestamp-aware image-space estimator with ego-motion containment. It is intentionally
 * identity-blind: callers may feed it only consecutive accepted matches for the already selected target
 * and must reset it whenever that identity is missing, ambiguous, lost, or explicitly replaced.
 */
class YawTargetEstimator(
    private val velocityAlpha: Float = VELOCITY_ALPHA,
    private val jitterDelta: Float = JITTER_DELTA,
    private val jitterVelocityDecay: Float = JITTER_VELOCITY_DECAY,
    private val maximumVelocityPerSecond: Float = MAXIMUM_VELOCITY_PER_SECOND,
    private val maximumPredictionHorizonMillis: Long = MAXIMUM_PREDICTION_HORIZON_MILLIS,
    private val maximumPredictedOffset: Float = MAXIMUM_PREDICTED_OFFSET,
    private val highYawRateThresholdDps: Float = HIGH_YAW_RATE_THRESHOLD_DPS,
    private val maxYawRateSuppressionDps: Float = MAX_YAW_RATE_SUPPRESSION_DPS,
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
        require(highYawRateThresholdDps > 0f)
        require(maxYawRateSuppressionDps > highYawRateThresholdDps)
    }

    fun update(
        centerX: Float,
        sourceTimestampNanos: Long,
        perceptionAgeNanos: Long,
        physicalYawRateDegreesPerSecond: Float? = null,
        isSettling: Boolean = false,
        isAnomaly: Boolean = false,
        isRecovery: Boolean = false,
    ): YawTargetEstimate {
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
        val rawPredictedOffset = (velocityPerSecond * horizonMillis / MILLIS_PER_SECOND)
            .coerceIn(-maximumPredictedOffset, maximumPredictedOffset)

        val (predictionMode, attenuationFactor) = when {
            isAnomaly -> TargetPredictionMode.DISABLED_ANOMALY to 0f
            isSettling -> TargetPredictionMode.DISABLED_SETTLING to 0f
            isRecovery -> TargetPredictionMode.DISABLED_RECOVERY to 0f
            physicalYawRateDegreesPerSecond != null && abs(physicalYawRateDegreesPerSecond) >= highYawRateThresholdDps -> {
                val absRate = abs(physicalYawRateDegreesPerSecond)
                val factor = (1f - (absRate - highYawRateThresholdDps) / (maxYawRateSuppressionDps - highYawRateThresholdDps))
                    .coerceIn(0f, 1f)
                if (factor < 1f) TargetPredictionMode.CLAMPED_FOR_YAW_RATE to factor else TargetPredictionMode.NORMAL to 1f
            }
            else -> TargetPredictionMode.NORMAL to 1f
        }

        val appliedPredictedOffset = rawPredictedOffset * attenuationFactor
        val effectiveHorizon = (horizonMillis * attenuationFactor).toLong()
        var estimatedCenter = (centerX + appliedPredictedOffset).coerceIn(0f, 1f)
        // Prediction may brake at center, but never extrapolates directly through it into reversal.
        if ((centerX - CENTER_X) * (estimatedCenter - CENTER_X) < 0f) estimatedCenter = CENTER_X

        return YawTargetEstimate(
            measuredCenterX = centerX,
            estimatedCenterX = estimatedCenter,
            velocityPerSecond = velocityPerSecond,
            predictionHorizonMillis = effectiveHorizon,
            predictionMode = predictionMode,
            rawPredictedOffset = rawPredictedOffset,
            appliedPredictedOffset = appliedPredictedOffset,
        )
    }

    /** Seeds the current centered/crossing measurement with zero velocity. */
    fun brake(centerX: Float, sourceTimestampNanos: Long): YawTargetEstimate {
        velocityPerSecond = 0f
        previousCenterX = centerX
        previousSourceTimestampNanos = sourceTimestampNanos
        return YawTargetEstimate(
            measuredCenterX = centerX,
            estimatedCenterX = centerX,
            velocityPerSecond = 0f,
            predictionHorizonMillis = 0L,
            predictionMode = TargetPredictionMode.DISABLED_SETTLING,
            rawPredictedOffset = 0f,
            appliedPredictedOffset = 0f,
        )
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
        const val HIGH_YAW_RATE_THRESHOLD_DPS = 25.0f
        const val MAX_YAW_RATE_SUPPRESSION_DPS = 70.0f
        private const val CENTER_X = .5f
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val MILLIS_PER_SECOND = 1_000f
        private const val MINIMUM_ESTIMATOR_INTERVAL_SECONDS = .02f
        private const val MAXIMUM_ESTIMATOR_INTERVAL_SECONDS = .35f
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
