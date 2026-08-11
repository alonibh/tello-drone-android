package com.alonibh.tellodrone.domain

import kotlin.math.abs
import kotlin.math.max

/** Dry-run normalized tracking errors. Positive yaw means target-right; positive vertical means target-above. */
data class TrackingErrors(
    val yawError: Float = 0f,
    val verticalError: Float = 0f,
    /** Positive means the target is smaller than desired; no flight command is implied. */
    val forwardBackError: Float = 0f,
    val targetPresent: Boolean = false,
    val targetFresh: Boolean = false,
)

/**
 * Stateful only for EMA smoothing, but free of Android, detector, controller, and RC concerns.
 * Call [reset] for an explicit selection change or loss before processing the next target.
 */
class TrackingErrorEngine {
    private var previous = TrackingErrors()
    private var seeded = false

    fun update(target: TrackedTarget?, targetFresh: Boolean): TrackingErrors {
        if (target == null) {
            reset()
            return TrackingErrors()
        }
        if (!targetFresh) return previous.copy(targetPresent = true, targetFresh = false)

        val box = target.boundingBox
        val rawYaw = deadzone((box.left + box.right) / 2f - .5f, X_DEADZONE)
        val rawVertical = deadzone(.5f - (box.top + box.bottom) / 2f, Y_DEADZONE)
        val rawArea = deadzone(DESIRED_TARGET_AREA_RATIO - area(box), AREA_DEADZONE_RATIO)
        previous = TrackingErrors(
            yawError = if (seeded) ema(previous.yawError, rawYaw) else rawYaw,
            verticalError = if (seeded) ema(previous.verticalError, rawVertical) else rawVertical,
            forwardBackError = if (seeded) ema(previous.forwardBackError, rawArea) else rawArea,
            targetPresent = true,
            targetFresh = true,
        )
        seeded = true
        return previous
    }

    fun reset() { previous = TrackingErrors(); seeded = false }

    private fun ema(previous: Float, raw: Float) = EMA_ALPHA * raw + (1f - EMA_ALPHA) * previous
    private fun deadzone(value: Float, deadzone: Float) = if (abs(value) <= deadzone) 0f else value
    private fun area(box: NormalizedBoundingBox) = max(0f, box.right - box.left) * max(0f, box.bottom - box.top)

    companion object {
        /** Derived from 15 / 960 and 15 / 720, respectively; no pixels enter this engine. */
        const val X_DEADZONE = 15f / 960f
        const val Y_DEADZONE = 15f / 720f
        /** Derived from 45,000 / (960 * 720). */
        const val DESIRED_TARGET_AREA_RATIO = 45_000f / (960f * 720f)
        /** Derived from 2,000 / (960 * 720). */
        const val AREA_DEADZONE_RATIO = 2_000f / (960f * 720f)
        const val EMA_ALPHA = .4f
    }
}
