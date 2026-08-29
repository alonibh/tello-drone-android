package com.alonibh.tellodrone.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Dry-run normalized tracking errors. Positive yaw means target-right; positive vertical means target-above. */
data class TrackingErrors(
    val yawError: Float = 0f,
    val verticalError: Float = 0f,
    /** Positive means the target is smaller than desired; no flight command is implied. */
    val forwardBackError: Float = 0f,
    val targetPresent: Boolean = false,
    val targetFresh: Boolean = false,
    val distanceCalibrated: Boolean = false,
    /** Center offset before either deadzone or smoothing. Positive means target-right. */
    val rawYawError: Float = 0f,
    val targetCenterX: Float? = null,
    val measurementFrameSequence: Long? = null,
    val measurementSourceTimestampNanos: Long? = null,
)

/**
 * Stateful only for EMA smoothing, but free of Android, detector, controller, and RC concerns.
 * Call [reset] for an explicit selection change or loss before processing the next target.
 */
class TrackingErrorEngine {
    private var previous = TrackingErrors()
    private var seeded = false

    fun update(target: TrackedTarget?, targetFresh: Boolean, distanceReference: FollowDistanceReference? = null): TrackingErrors {
        if (target == null) {
            reset()
            return TrackingErrors()
        }
        if (!targetFresh) return previous.copy(targetPresent = true, targetFresh = false)

        val box = target.boundingBox
        val centerX = (box.left + box.right) / 2f
        val rawYaw = centerX - .5f
        val controlYaw = deadzone(rawYaw, X_DEADZONE)
        val rawVertical = deadzone(.5f - (box.top + box.bottom) / 2f, Y_DEADZONE)
        val rawDistance = distanceReference?.let { reference ->
            val scale = sqrt(area(box))
            deadzone(((reference.visualScale - scale) / reference.visualScale).coerceIn(-1f, 1f), DISTANCE_DEADZONE)
        } ?: 0f
        previous = TrackingErrors(
            rawYawError = rawYaw,
            yawError = if (seeded) {
                val alpha = if (abs(rawYaw) >= RESPONSIVE_YAW_ERROR) FAR_YAW_EMA_ALPHA else NEAR_YAW_EMA_ALPHA
                ema(previous.yawError, controlYaw, alpha)
            } else controlYaw,
            verticalError = if (seeded) ema(previous.verticalError, rawVertical, VERTICAL_DISTANCE_EMA_ALPHA) else rawVertical,
            forwardBackError = if (seeded && distanceReference != null) {
                ema(previous.forwardBackError, rawDistance, VERTICAL_DISTANCE_EMA_ALPHA)
            } else {
                rawDistance
            },
            targetPresent = true,
            targetFresh = true,
            distanceCalibrated = distanceReference != null,
            targetCenterX = centerX,
            measurementFrameSequence = target.lastSeenFrameSequence,
            measurementSourceTimestampNanos = target.lastSeenSourceTimestampNanos,
        )
        seeded = true
        return previous
    }

    fun reset() { previous = TrackingErrors(); seeded = false }
    fun resetDistance() { previous = previous.copy(forwardBackError = 0f, distanceCalibrated = false) }

    private fun ema(previous: Float, raw: Float, alpha: Float) = alpha * raw + (1f - alpha) * previous
    private fun deadzone(value: Float, deadzone: Float) = if (abs(value) <= deadzone) 0f else value
    private fun area(box: NormalizedBoundingBox) = max(0f, box.right - box.left) * max(0f, box.bottom - box.top)

    companion object {
        /** Derived from 15 / 960 and 15 / 720, respectively; no pixels enter this engine. */
        const val X_DEADZONE = 15f / 960f
        const val Y_DEADZONE = 15f / 720f
        /** Relative visual-scale jitter tolerance (7%); no pixel or meter calibration is implied. */
        const val DISTANCE_DEADZONE = .07f
        const val RESPONSIVE_YAW_ERROR = .08f
        const val FAR_YAW_EMA_ALPHA = .90f
        const val NEAR_YAW_EMA_ALPHA = .45f
        const val VERTICAL_DISTANCE_EMA_ALPHA = .4f
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
