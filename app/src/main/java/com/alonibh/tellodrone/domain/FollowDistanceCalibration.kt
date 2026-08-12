package com.alonibh.tellodrone.domain

import kotlin.math.sqrt

/** Pure, bounded current-visual-distance calibration. It never estimates meters. */
class FollowDistanceCalibrator(
    private val requiredSamples: Int = REQUIRED_SAMPLES,
    private val timeoutNanos: Long = TIMEOUT_NANOS,
) {
    private val samples = mutableListOf<Pair<Long, Float>>()
    private var startedAtNanos: Long? = null
    private var lastFrameSequence: Long? = null
    private var lastSourceTimestampNanos: Long? = null
    val sampleCount: Int get() = samples.size

    fun start(nowNanos: Long) { samples.clear(); startedAtNanos = nowNanos; lastFrameSequence = null; lastSourceTimestampNanos = null }
    fun cancel() { samples.clear(); startedAtNanos = null; lastFrameSequence = null; lastSourceTimestampNanos = null }
    fun timedOut(nowNanos: Long): Boolean = startedAtNanos?.let { nowNanos - it > timeoutNanos } == true

    fun add(frameSequence: Long, timestampNanos: Long, box: NormalizedBoundingBox): FollowDistanceReference? {
        if (lastFrameSequence?.let { frameSequence <= it } == true ||
            lastSourceTimestampNanos?.let { timestampNanos <= it } == true || !isValidUnclipped(box)) return null
        lastFrameSequence = frameSequence
        lastSourceTimestampNanos = timestampNanos
        val scale = visualScale(box) ?: return null
        samples += timestampNanos to scale
        if (samples.size < requiredSamples) return null
        val median = samples.map { it.second }.sorted()[samples.size / 2]
        val last = samples.last()
        return FollowDistanceReference(median, frameSequence, last.first, samples.size).also { cancel() }
    }

    companion object {
        const val REQUIRED_SAMPLES = 7
        const val TIMEOUT_NANOS = 3_000_000_000L
        const val EDGE_MARGIN = .02f
        fun visualScale(box: NormalizedBoundingBox): Float? {
            val width = box.right - box.left; val height = box.bottom - box.top
            val area = width * height
            return if (width.isFinite() && height.isFinite() && area.isFinite() && area > 0f) sqrt(area) else null
        }
        fun isValidUnclipped(box: NormalizedBoundingBox): Boolean =
            visualScale(box) != null && box.left > EDGE_MARGIN && box.top > EDGE_MARGIN &&
                box.right < 1f - EDGE_MARGIN && box.bottom < 1f - EDGE_MARGIN
    }
}

enum class FollowDistanceEligibilityReason { READY, SELECT_A_PERSON, KEEP_PERSON_FULLY_IN_FRAME, TARGET_NOT_STABLE, CALIBRATING }

object FollowDistanceEligibility {
    fun evaluate(state: DroneSessionState): FollowDistanceEligibilityReason = when {
        state.followDistanceCalibrationState == FollowDistanceCalibrationState.Calibrating -> FollowDistanceEligibilityReason.CALIBRATING
        state.connection != DroneConnectionState.Connected || state.video.availability != VideoAvailability.Streaming ||
            state.video.personDetectionState != PersonDetectionState.Detecting || state.target == null -> FollowDistanceEligibilityReason.SELECT_A_PERSON
        state.targetAssociationState !in setOf(TargetAssociationState.Selected, TargetAssociationState.Matched) || state.trackingErrors?.targetFresh != true -> FollowDistanceEligibilityReason.TARGET_NOT_STABLE
        !FollowDistanceCalibrator.isValidUnclipped(state.target.boundingBox) -> FollowDistanceEligibilityReason.KEEP_PERSON_FULLY_IN_FRAME
        else -> FollowDistanceEligibilityReason.READY
    }
}
