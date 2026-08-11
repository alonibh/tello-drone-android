package com.alonibh.tellodrone.domain

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** UI-friendly status for the intentionally observational target association path. */
enum class TargetAssociationState { None, Selected, Matched, TemporarilyMissing, Lost, Ambiguous }

/**
 * Result of associating one newer detector frame with an explicitly selected target. `Ignored`
 * means the frame cannot change state because its sequence or source timestamp is not newer.
 */
sealed interface TargetAssociationResult {
    val target: TrackedTarget?

    data class Matched(override val target: TrackedTarget) : TargetAssociationResult
    data class TemporarilyMissing(override val target: TrackedTarget) : TargetAssociationResult
    data class Lost(override val target: TrackedTarget? = null) : TargetAssociationResult
    data class Ambiguous(override val target: TrackedTarget, val candidateCount: Int) : TargetAssociationResult
    data class Ignored(override val target: TrackedTarget) : TargetAssociationResult
}

/**
 * Conservative, backend-independent association for an explicitly selected target. It never
 * selects or reacquires a target; callers must preserve `Lost` until another explicit selection.
 */
class TargetAssociationEngine {
    fun associate(
        selectedTarget: TrackedTarget?,
        frameSequence: Long,
        sourceTimestampNanos: Long,
        detections: List<PersonDetection>,
    ): TargetAssociationResult {
        val target = selectedTarget ?: return TargetAssociationResult.Lost()
        if (frameSequence <= target.lastSeenFrameSequence ||
            sourceTimestampNanos <= target.lastSeenSourceTimestampNanos
        ) return TargetAssociationResult.Ignored(target)

        val candidates = detections
            .asSequence()
            .filter { it.frameSequence == frameSequence && it.sourceTimestampNanos == sourceTimestampNanos }
            .map { detection -> Candidate(detection, metrics(target.boundingBox, detection.boundingBox)) }
            .filter { it.isEligible }
            .sortedBy { it.score }
            .toList()

        if (candidates.isEmpty()) return missingOrLost(target, sourceTimestampNanos)
        val best = candidates.first()
        if (candidates.size > 1 && candidates[1].score - best.score <= AMBIGUITY_SCORE_MARGIN) {
            return TargetAssociationResult.Ambiguous(target, candidates.size)
        }
        return TargetAssociationResult.Matched(
            target.copy(
                boundingBox = best.detection.boundingBox,
                confidence = best.detection.confidence,
                lastSeenFrameSequence = frameSequence,
                lastSeenSourceTimestampNanos = sourceTimestampNanos,
            ),
        )
    }

    private fun missingOrLost(target: TrackedTarget, timestampNanos: Long): TargetAssociationResult =
        if (timestampNanos - target.lastSeenSourceTimestampNanos > MISSING_TIMEOUT_NANOS) {
            TargetAssociationResult.Lost()
        } else {
            TargetAssociationResult.TemporarilyMissing(target)
        }

    private data class Candidate(val detection: PersonDetection, val metrics: Metrics) {
        val isEligible: Boolean = metrics.centerDisplacement <= MAX_CENTER_DISPLACEMENT &&
            metrics.iou >= MIN_IOU &&
            metrics.areaRatio in MIN_AREA_RATIO..MAX_AREA_RATIO
        val score: Float =
            CENTER_WEIGHT * (metrics.centerDisplacement / MAX_CENTER_DISPLACEMENT) +
                IOU_WEIGHT * (1f - metrics.iou) +
                SIZE_WEIGHT * abs(1f - metrics.areaRatio)
    }

    private data class Metrics(val centerDisplacement: Float, val iou: Float, val areaRatio: Float)

    private fun metrics(previous: NormalizedBoundingBox, next: NormalizedBoundingBox): Metrics {
        val previousArea = area(previous)
        val nextArea = area(next)
        val centerDisplacement = hypot(centerX(next) - centerX(previous), centerY(next) - centerY(previous))
        val intersection = max(0f, min(previous.right, next.right) - max(previous.left, next.left)) *
            max(0f, min(previous.bottom, next.bottom) - max(previous.top, next.top))
        val union = previousArea + nextArea - intersection
        return Metrics(
            centerDisplacement = centerDisplacement,
            iou = if (union > 0f) intersection / union else 0f,
            areaRatio = if (previousArea > 0f) nextArea / previousArea else 0f,
        )
    }

    private fun area(box: NormalizedBoundingBox) = max(0f, box.right - box.left) * max(0f, box.bottom - box.top)
    private fun centerX(box: NormalizedBoundingBox) = (box.left + box.right) / 2f
    private fun centerY(box: NormalizedBoundingBox) = (box.top + box.bottom) / 2f

    companion object {
        /** Source-monotonic grace period before a missing selected person becomes Lost. */
        const val MISSING_TIMEOUT_NANOS = 1_000_000_000L
        /** Maximum normalized center movement allowed in one association step. */
        const val MAX_CENTER_DISPLACEMENT = 0.20f
        /** Minimum box overlap; all three geometry checks must pass. */
        const val MIN_IOU = 0.05f
        /** Permitted next/previous target-area ratio. */
        const val MIN_AREA_RATIO = 0.50f
        const val MAX_AREA_RATIO = 2.00f
        /** Close scores are ambiguous rather than a reason to switch people. */
        const val AMBIGUITY_SCORE_MARGIN = 0.08f
        private const val CENTER_WEIGHT = 0.50f
        private const val IOU_WEIGHT = 0.35f
        private const val SIZE_WEIGHT = 0.15f
    }
}
