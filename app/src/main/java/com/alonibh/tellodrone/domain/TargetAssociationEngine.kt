package com.alonibh.tellodrone.domain

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class TargetAssociationState { None, Selected, Matched, TemporarilyMissing, Lost, Ambiguous }

sealed interface TargetAssociationResult {
    val target: TrackedTarget?
    data class Matched(override val target: TrackedTarget) : TargetAssociationResult
    data class TemporarilyMissing(override val target: TrackedTarget) : TargetAssociationResult
    data class Lost(override val target: TrackedTarget? = null) : TargetAssociationResult
    data class Ambiguous(override val target: TrackedTarget, val candidateCount: Int) : TargetAssociationResult
    data class Ignored(override val target: TrackedTarget) : TargetAssociationResult
}

enum class TargetAssociationDecision { NoTarget, Ignored, Matched, TemporarilyMissing, Lost, Ambiguous }

data class TargetAssociationMetrics(
    val centerDisplacement: Float,
    val iou: Float,
    val areaRatio: Float,
    val eligible: Boolean,
    val score: Float,
    val appearanceSimilarity: Float = 0f,
)

data class TargetCandidateDiagnostic(
    val detectionIndex: Int,
    val strict: TargetAssociationMetrics,
    val predicted: TargetAssociationMetrics? = null,
    val eligible: Boolean,
    val score: Float?,
)

data class CompetitorMatchDiagnostic(val detectionIndex: Int, val metrics: TargetAssociationMetrics)
data class CompetitorDiagnostic(
    val competitorIndex: Int,
    val boundingBox: NormalizedBoundingBox,
    val predictedBoundingBox: NormalizedBoundingBox? = null,
    val detectionMatches: List<CompetitorMatchDiagnostic> = emptyList(),
)

data class TargetAssociationDiagnostics(
    val decision: TargetAssociationDecision,
    val targetAgeNanos: Long?,
    val predictedTargetBoundingBox: NormalizedBoundingBox? = null,
    val candidates: List<TargetCandidateDiagnostic> = emptyList(),
    val competitors: List<CompetitorDiagnostic> = emptyList(),
    val selectedDetectionIndex: Int? = null,
    val eligibleCandidateCount: Int = 0,
)

data class TargetAssociationEvaluation(
    val result: TargetAssociationResult,
    val diagnostics: TargetAssociationDiagnostics,
)

/** Frozen identity-safe association validated by the corrected five-video benchmark. */
class TargetAssociationEngine {
    fun associate(
        selectedTarget: TrackedTarget?,
        frameSequence: Long,
        sourceTimestampNanos: Long,
        detections: List<PersonDetection>,
    ): TargetAssociationResult = evaluate(selectedTarget, frameSequence, sourceTimestampNanos, detections).result

    fun evaluate(
        selectedTarget: TrackedTarget?,
        frameSequence: Long,
        sourceTimestampNanos: Long,
        detections: List<PersonDetection>,
        includeDetailedDiagnostics: Boolean = true,
    ): TargetAssociationEvaluation {
        val target = selectedTarget ?: return evaluation(TargetAssociationResult.Lost(), TargetAssociationDecision.NoTarget, null)
        val age = (sourceTimestampNanos - target.lastSeenSourceTimestampNanos).coerceAtLeast(0L)
        if (frameSequence <= target.lastSeenFrameSequence || sourceTimestampNanos <= target.lastSeenSourceTimestampNanos) {
            return evaluation(TargetAssociationResult.Ignored(target), TargetAssociationDecision.Ignored, age)
        }
        val frameDetections = detections.filter {
            it.frameSequence == frameSequence && it.sourceTimestampNanos == sourceTimestampNanos
        }
        val candidates = frameDetections.mapIndexed { index, detection ->
            Candidate(index, detection, metrics(target, detection))
        }
        val eligible = candidates.filter { it.metrics.eligible }.sortedByDescending { it.metrics.score }
        fun diagnostics(decision: TargetAssociationDecision, selected: Int? = null) = TargetAssociationDiagnostics(
            decision = decision,
            targetAgeNanos = age,
            candidates = if (includeDetailedDiagnostics) candidates.map { it.diagnostic() } else emptyList(),
            selectedDetectionIndex = selected,
            eligibleCandidateCount = eligible.size,
        )
        if (eligible.isEmpty()) {
            val result = missingOrLost(target, sourceTimestampNanos)
            return TargetAssociationEvaluation(result, diagnostics(result.decision()))
        }
        val best = eligible.first()
        val runnerUp = eligible.getOrNull(1)
        if (runnerUp != null && best.metrics.score - runnerUp.metrics.score < AMBIGUITY_MARGIN) {
            val result = TargetAssociationResult.Ambiguous(target, eligible.size)
            return TargetAssociationEvaluation(result, diagnostics(TargetAssociationDecision.Ambiguous))
        }
        val result = TargetAssociationResult.Matched(
            target.copy(
                boundingBox = best.detection.boundingBox,
                confidence = best.detection.confidence,
                lastSeenFrameSequence = frameSequence,
                lastSeenSourceTimestampNanos = sourceTimestampNanos,
                previousMatchedBoundingBox = null,
                previousMatchedSourceTimestampNanos = null,
                associationMatchCount = (target.associationMatchCount + 1).coerceAtMost(2),
                competingPeople = emptyList(),
                identityUncertain = false,
                appearance = blendAppearance(target.appearance, best.detection.appearance),
            ),
        )
        return TargetAssociationEvaluation(result, diagnostics(TargetAssociationDecision.Matched, best.detectionIndex))
    }

    private fun metrics(target: TrackedTarget, detection: PersonDetection): TargetAssociationMetrics {
        val previous = target.boundingBox
        val next = detection.boundingBox
        val previousArea = area(previous)
        val nextArea = area(next)
        val distance = hypot(centerX(next) - centerX(previous), centerY(next) - centerY(previous)) / sqrt(2f)
        val intersection = max(0f, min(previous.right, next.right) - max(previous.left, next.left)) *
            max(0f, min(previous.bottom, next.bottom) - max(previous.top, next.top))
        val union = previousArea + nextArea - intersection
        val iou = if (union > 0f) intersection / union else 0f
        val areaRatio = if (previousArea > 0f) nextArea / previousArea else 0f
        val appearance = appearanceSimilarity(target.appearance, detection.appearance)
        val eligible = detection.confidence >= DETECTOR_CONFIDENCE &&
            (iou >= MIN_IOU || distance <= MAX_CENTER_DISPLACEMENT) &&
            areaRatio in MIN_AREA_RATIO..MAX_AREA_RATIO &&
            appearance >= MIN_APPEARANCE_SIMILARITY
        val distanceScore = (1f - distance / MAX_CENTER_DISPLACEMENT).coerceIn(0f, 1f)
        val score = IOU_WEIGHT * iou + DISTANCE_WEIGHT * distanceScore +
            APPEARANCE_WEIGHT * appearance + CONFIDENCE_WEIGHT * detection.confidence
        return TargetAssociationMetrics(distance, iou, areaRatio, eligible, score, appearance)
    }

    private fun appearanceSimilarity(first: HsvAppearanceHistogram?, second: HsvAppearanceHistogram?): Float {
        if (first == null || second == null || first.bins.size != second.bins.size) return 0f
        val firstMean = first.bins.average().toFloat()
        val secondMean = second.bins.average().toFloat()
        var numerator = 0f
        var firstEnergy = 0f
        var secondEnergy = 0f
        first.bins.indices.forEach { index ->
            val a = first.bins[index] - firstMean
            val b = second.bins[index] - secondMean
            numerator += a * b
            firstEnergy += a * a
            secondEnergy += b * b
        }
        val denominator = sqrt(firstEnergy * secondEnergy)
        val correlation = if (denominator > 0f) numerator / denominator else 0f
        return ((correlation + 1f) / 2f).coerceIn(0f, 1f)
    }

    private fun blendAppearance(previous: HsvAppearanceHistogram?, current: HsvAppearanceHistogram?): HsvAppearanceHistogram? {
        if (previous == null) return current
        if (current == null) return previous
        return HsvAppearanceHistogram(previous.bins.indices.map { index ->
            previous.bins[index] * (1f - APPEARANCE_BLEND) + current.bins[index] * APPEARANCE_BLEND
        })
    }

    private fun missingOrLost(target: TrackedTarget, timestampNanos: Long): TargetAssociationResult =
        if (timestampNanos - target.lastSeenSourceTimestampNanos >= MISSING_TIMEOUT_NANOS) {
            TargetAssociationResult.Lost()
        } else {
            TargetAssociationResult.TemporarilyMissing(target)
        }

    private fun evaluation(result: TargetAssociationResult, decision: TargetAssociationDecision, age: Long?) =
        TargetAssociationEvaluation(result, TargetAssociationDiagnostics(decision, age))

    private fun TargetAssociationResult.decision() = when (this) {
        is TargetAssociationResult.Matched -> TargetAssociationDecision.Matched
        is TargetAssociationResult.TemporarilyMissing -> TargetAssociationDecision.TemporarilyMissing
        is TargetAssociationResult.Lost -> TargetAssociationDecision.Lost
        is TargetAssociationResult.Ambiguous -> TargetAssociationDecision.Ambiguous
        is TargetAssociationResult.Ignored -> TargetAssociationDecision.Ignored
    }

    private data class Candidate(
        val detectionIndex: Int,
        val detection: PersonDetection,
        val metrics: TargetAssociationMetrics,
    ) {
        fun diagnostic() = TargetCandidateDiagnostic(
            detectionIndex = detectionIndex,
            strict = metrics,
            eligible = metrics.eligible,
            score = metrics.score.takeIf { it.isFinite() },
        )
    }

    private fun area(box: NormalizedBoundingBox) = max(0f, box.right - box.left) * max(0f, box.bottom - box.top)
    private fun centerX(box: NormalizedBoundingBox) = (box.left + box.right) / 2f
    private fun centerY(box: NormalizedBoundingBox) = (box.top + box.bottom) / 2f

    companion object {
        const val DETECTOR_CONFIDENCE = 0.30f
        const val MIN_IOU = 0.15f
        const val MAX_CENTER_DISPLACEMENT = 0.24f
        const val MIN_APPEARANCE_SIMILARITY = 0.45f
        const val AMBIGUITY_MARGIN = 0.10f
        const val MISSING_TIMEOUT_NANOS = 400_000_000L
        const val MIN_AREA_RATIO = 0.35f
        const val MAX_AREA_RATIO = 2.80f
        const val CANDIDATE_AMBIGUITY_MARGIN = AMBIGUITY_MARGIN
        const val ASSIGNMENT_AMBIGUITY_MARGIN = AMBIGUITY_MARGIN
        private const val IOU_WEIGHT = 0.42f
        private const val DISTANCE_WEIGHT = 0.23f
        private const val APPEARANCE_WEIGHT = 0.27f
        private const val CONFIDENCE_WEIGHT = 0.08f
        private const val APPEARANCE_BLEND = 0.04f
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
