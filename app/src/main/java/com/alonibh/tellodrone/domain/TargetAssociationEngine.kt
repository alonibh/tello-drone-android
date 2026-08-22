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

enum class TargetAssociationDecision { NoTarget, Ignored, Matched, TemporarilyMissing, Lost, Ambiguous }

data class TargetAssociationMetrics(
    val centerDisplacement: Float,
    val iou: Float,
    val areaRatio: Float,
    val eligible: Boolean,
    val score: Float,
)

data class TargetCandidateDiagnostic(
    val detectionIndex: Int,
    val strict: TargetAssociationMetrics,
    val predicted: TargetAssociationMetrics?,
    val eligible: Boolean,
    val score: Float?,
)

data class CompetitorMatchDiagnostic(
    val detectionIndex: Int,
    val metrics: TargetAssociationMetrics,
)

data class CompetitorDiagnostic(
    val competitorIndex: Int,
    val boundingBox: NormalizedBoundingBox,
    val predictedBoundingBox: NormalizedBoundingBox?,
    val detectionMatches: List<CompetitorMatchDiagnostic>,
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
    ): TargetAssociationResult = evaluate(
        selectedTarget,
        frameSequence,
        sourceTimestampNanos,
        detections,
    ).result

    fun evaluate(
        selectedTarget: TrackedTarget?,
        frameSequence: Long,
        sourceTimestampNanos: Long,
        detections: List<PersonDetection>,
        includeDetailedDiagnostics: Boolean = true,
    ): TargetAssociationEvaluation {
        val target = selectedTarget ?: return evaluation(
            TargetAssociationResult.Lost(),
            TargetAssociationDecision.NoTarget,
            targetAgeNanos = null,
        )
        val targetAgeNanos = (sourceTimestampNanos - target.lastSeenSourceTimestampNanos).coerceAtLeast(0L)
        if (frameSequence <= target.lastSeenFrameSequence ||
            sourceTimestampNanos <= target.lastSeenSourceTimestampNanos
        ) return evaluation(
            TargetAssociationResult.Ignored(target),
            TargetAssociationDecision.Ignored,
            targetAgeNanos,
        )

        val frameDetections = detections.filter {
            it.frameSequence == frameSequence && it.sourceTimestampNanos == sourceTimestampNanos
        }
        if (target.identityUncertain) {
            val result = ambiguousOrLost(target, sourceTimestampNanos, frameDetections.size)
            return evaluation(result, result.decision(), targetAgeNanos)
        }

        val predictedBoundingBox = predictedBoundingBox(target, sourceTimestampNanos)
        val allCandidates = frameDetections
            .mapIndexed { index, detection ->
                Candidate(
                    detectionIndex = index,
                    detection = detection,
                    strictMetrics = metrics(target.boundingBox, detection.boundingBox),
                    predictedMetrics = predictedBoundingBox?.let { metrics(it, detection.boundingBox) },
                )
            }
        val candidates = allCandidates
            .asSequence()
            .filter { it.isEligible }
            .sortedBy { it.score }
            .toList()

        val competitorContinuity = target.competingPeople.map { competitor ->
            CompetitorContinuity(
                track = competitor,
                predictedBoundingBox = predictedBoundingBox(competitor, sourceTimestampNanos),
            )
        }
        fun diagnostics(
            decision: TargetAssociationDecision,
            selectedDetectionIndex: Int? = null,
        ) = TargetAssociationDiagnostics(
            decision = decision,
            targetAgeNanos = targetAgeNanos,
            predictedTargetBoundingBox = predictedBoundingBox,
            candidates = if (includeDetailedDiagnostics) allCandidates.map { it.diagnostic() } else emptyList(),
            competitors = if (includeDetailedDiagnostics) competitorContinuity.mapIndexed { index, competitor ->
                CompetitorDiagnostic(
                    competitorIndex = index,
                    boundingBox = competitor.track.boundingBox,
                    predictedBoundingBox = competitor.predictedBoundingBox,
                    detectionMatches = frameDetections.mapIndexed { detectionIndex, detection ->
                        CompetitorMatchDiagnostic(
                            detectionIndex,
                            metricsFor(competitor, detection.boundingBox).diagnostic(),
                        )
                    },
                )
            } else emptyList(),
            selectedDetectionIndex = selectedDetectionIndex,
            eligibleCandidateCount = candidates.size,
        )
        if (candidates.isEmpty()) {
            val result = missingOrLostWithCompetitorContinuity(
                target,
                competitorContinuity,
                frameDetections,
                sourceTimestampNanos,
            )
            return TargetAssociationEvaluation(result, diagnostics(result.decision()))
        }
        val decision = decideIdentity(candidates, frameDetections, competitorContinuity)
        if (decision is IdentityDecision.Ambiguous) {
            return TargetAssociationEvaluation(
                ambiguous(target, candidates.size),
                diagnostics(TargetAssociationDecision.Ambiguous),
            )
        }
        if (decision is IdentityDecision.TargetMissing) {
            val result = missingOrLostWithCompetitorContinuity(
                target,
                competitorContinuity,
                frameDetections,
                sourceTimestampNanos,
            )
            return TargetAssociationEvaluation(result, diagnostics(result.decision()))
        }
        val best = (decision as IdentityDecision.Match).candidate
        val result = TargetAssociationResult.Matched(
            target.copy(
                boundingBox = best.detection.boundingBox,
                confidence = best.detection.confidence,
                lastSeenFrameSequence = frameSequence,
                lastSeenSourceTimestampNanos = sourceTimestampNanos,
                previousMatchedBoundingBox = if (target.associationMatchCount > 0) target.boundingBox else null,
                previousMatchedSourceTimestampNanos = if (target.associationMatchCount > 0) {
                    target.lastSeenSourceTimestampNanos
                } else {
                    null
                },
                associationMatchCount = (target.associationMatchCount + 1).coerceAtMost(2),
                competingPeople = updateCompetingPeople(
                    previous = competitorContinuity,
                    detections = frameDetections.filterIndexed { index, _ -> index != best.detectionIndex },
                    sourceTimestampNanos = sourceTimestampNanos,
                ),
            ),
        )
        return TargetAssociationEvaluation(
            result,
            diagnostics(TargetAssociationDecision.Matched, best.detectionIndex),
        )
    }

    private fun evaluation(
        result: TargetAssociationResult,
        decision: TargetAssociationDecision,
        targetAgeNanos: Long?,
    ) = TargetAssociationEvaluation(result, TargetAssociationDiagnostics(decision, targetAgeNanos))

    private fun TargetAssociationResult.decision() = when (this) {
        is TargetAssociationResult.Matched -> TargetAssociationDecision.Matched
        is TargetAssociationResult.TemporarilyMissing -> TargetAssociationDecision.TemporarilyMissing
        is TargetAssociationResult.Lost -> TargetAssociationDecision.Lost
        is TargetAssociationResult.Ambiguous -> TargetAssociationDecision.Ambiguous
        is TargetAssociationResult.Ignored -> TargetAssociationDecision.Ignored
    }

    private fun decideIdentity(
        candidates: List<Candidate>,
        detections: List<PersonDetection>,
        competitors: List<CompetitorContinuity>,
    ): IdentityDecision {
        if (competitors.isEmpty()) return decideWithoutCompetitorHistory(candidates)

        val assignments = candidates.flatMap { candidate ->
            competitors.flatMap { competitor ->
                detections.mapIndexedNotNull { detectionIndex, detection ->
                    if (detectionIndex == candidate.detectionIndex) return@mapIndexedNotNull null
                    val competitorMetrics = metricsFor(competitor, detection.boundingBox)
                    if (!competitorMetrics.isEligible) return@mapIndexedNotNull null
                    IdentityAssignment(candidate, candidate.score + competitorMetrics.score)
                }
            }
        }.groupBy { it.candidate.detectionIndex }
            .map { (_, hypotheses) -> hypotheses.minBy { it.cost } }
            .sortedBy { it.cost }

        if (assignments.isNotEmpty()) {
            val best = assignments.first()
            val alternative = assignments.drop(1).firstOrNull()
            return if (alternative != null && alternative.cost - best.cost <= ASSIGNMENT_AMBIGUITY_MARGIN) {
                IdentityDecision.Ambiguous
            } else {
                IdentityDecision.Match(best.candidate)
            }
        }

        val best = candidates.first()
        val alternative = candidates.getOrNull(1)
        if (alternative != null && alternative.score - best.score <= CANDIDATE_AMBIGUITY_MARGIN) {
            return IdentityDecision.Ambiguous
        }
        val bestCompetitorScore = competitors
            .map { metricsFor(it, best.detection.boundingBox) }
            .filter { it.isEligible }
            .minOfOrNull { it.score }
            ?: return IdentityDecision.Match(best)
        return when {
            abs(best.score - bestCompetitorScore) <= CANDIDATE_AMBIGUITY_MARGIN -> IdentityDecision.Ambiguous
            bestCompetitorScore < best.score -> IdentityDecision.TargetMissing
            else -> IdentityDecision.Match(best)
        }
    }

    private fun decideWithoutCompetitorHistory(candidates: List<Candidate>): IdentityDecision {
        val best = candidates.first()
        val alternative = candidates.getOrNull(1)
        return if (alternative != null && alternative.score - best.score <= CANDIDATE_AMBIGUITY_MARGIN) {
            IdentityDecision.Ambiguous
        } else {
            IdentityDecision.Match(best)
        }
    }

    private fun updateCompetingPeople(
        previous: List<CompetitorContinuity>,
        detections: List<PersonDetection>,
        sourceTimestampNanos: Long,
    ): List<CompetingPersonTrack> {
        val matches = previous.flatMapIndexed { previousIndex, competitor ->
            detections.mapIndexedNotNull { detectionIndex, detection ->
                val continuity = metricsFor(competitor, detection.boundingBox)
                if (!continuity.isEligible) null else CompetitorMatch(previousIndex, detectionIndex, continuity.score)
            }
        }.sortedBy { it.score }
        val matchedByDetection = mutableMapOf<Int, CompetitorContinuity>()
        val usedPrevious = mutableSetOf<Int>()
        matches.forEach { match ->
            if (match.detectionIndex !in matchedByDetection && usedPrevious.add(match.previousIndex)) {
                matchedByDetection[match.detectionIndex] = previous[match.previousIndex]
            }
        }
        return detections.mapIndexed { detectionIndex, detection ->
            val matched = matchedByDetection[detectionIndex]
            if (matched == null) {
                CompetingPersonTrack(detection.boundingBox, sourceTimestampNanos)
            } else {
                CompetingPersonTrack(
                    boundingBox = detection.boundingBox,
                    sourceTimestampNanos = sourceTimestampNanos,
                    previousBoundingBox = matched.track.boundingBox,
                    previousSourceTimestampNanos = matched.track.sourceTimestampNanos,
                )
            }
        }
    }

    private fun ambiguous(target: TrackedTarget, candidateCount: Int) =
        TargetAssociationResult.Ambiguous(target.copy(identityUncertain = true), candidateCount)

    private fun missingOrLostWithCompetitorContinuity(
        target: TrackedTarget,
        previous: List<CompetitorContinuity>,
        detections: List<PersonDetection>,
        sourceTimestampNanos: Long,
    ): TargetAssociationResult {
        val updatedTarget = if (previous.isEmpty() || detections.isEmpty()) {
            target
        } else {
            target.copy(
                competingPeople = updateCompetingPeople(previous, detections, sourceTimestampNanos),
            )
        }
        return missingOrLost(updatedTarget, sourceTimestampNanos)
    }

    private fun ambiguousOrLost(
        target: TrackedTarget,
        timestampNanos: Long,
        candidateCount: Int,
    ): TargetAssociationResult =
        if (timestampNanos - target.lastSeenSourceTimestampNanos > MISSING_TIMEOUT_NANOS) {
            TargetAssociationResult.Lost()
        } else {
            TargetAssociationResult.Ambiguous(target, candidateCount)
        }

    private fun missingOrLost(target: TrackedTarget, timestampNanos: Long): TargetAssociationResult =
        if (timestampNanos - target.lastSeenSourceTimestampNanos > MISSING_TIMEOUT_NANOS) {
            TargetAssociationResult.Lost()
        } else {
            TargetAssociationResult.TemporarilyMissing(target)
        }

    private data class Candidate(
        val detectionIndex: Int,
        val detection: PersonDetection,
        val strictMetrics: Metrics,
        val predictedMetrics: Metrics?,
    ) {
        /** Prefer the strongest eligible evidence from last geometry or bounded prediction. */
        private val matchingMetrics = listOfNotNull(strictMetrics, predictedMetrics)
            .filter { it.isEligible }
            .minByOrNull { it.score }
        val isEligible: Boolean = matchingMetrics != null
        val score: Float = matchingMetrics?.score ?: Float.POSITIVE_INFINITY

        fun diagnostic() = TargetCandidateDiagnostic(
            detectionIndex = detectionIndex,
            strict = strictMetrics.diagnostic(),
            predicted = predictedMetrics?.diagnostic(),
            eligible = isEligible,
            score = score.takeIf { it.isFinite() },
        )
    }

    private data class CompetitorContinuity(
        val track: CompetingPersonTrack,
        val predictedBoundingBox: NormalizedBoundingBox?,
    )

    private data class IdentityAssignment(val candidate: Candidate, val cost: Float)

    private data class CompetitorMatch(
        val previousIndex: Int,
        val detectionIndex: Int,
        val score: Float,
    )

    private sealed interface IdentityDecision {
        data class Match(val candidate: Candidate) : IdentityDecision
        data object TargetMissing : IdentityDecision
        data object Ambiguous : IdentityDecision
    }

    private fun metricsFor(competitor: CompetitorContinuity, next: NormalizedBoundingBox): Metrics {
        val strict = metrics(competitor.track.boundingBox, next)
        val predicted = competitor.predictedBoundingBox?.let { metrics(it, next) }
        return listOfNotNull(strict, predicted)
            .filter { it.isEligible }
            .minByOrNull { it.score }
            ?: strict
    }

    private data class Metrics(val centerDisplacement: Float, val iou: Float, val areaRatio: Float) {
        val isEligible: Boolean = centerDisplacement <= MAX_CENTER_DISPLACEMENT &&
            iou >= MIN_IOU &&
            areaRatio in MIN_AREA_RATIO..MAX_AREA_RATIO
        val score: Float =
            CENTER_WEIGHT * (centerDisplacement / MAX_CENTER_DISPLACEMENT) +
                IOU_WEIGHT * (1f - iou) +
                SIZE_WEIGHT * abs(1f - areaRatio)

        fun diagnostic() = TargetAssociationMetrics(
            centerDisplacement = centerDisplacement,
            iou = iou,
            areaRatio = areaRatio,
            eligible = isEligible,
            score = score,
        )
    }

    private fun predictedBoundingBox(target: TrackedTarget, sourceTimestampNanos: Long): NormalizedBoundingBox? {
        if (target.associationMatchCount < 2) return null
        val previousBox = target.previousMatchedBoundingBox ?: return null
        val previousTimestamp = target.previousMatchedSourceTimestampNanos ?: return null
        return predictedBoundingBox(
            currentBox = target.boundingBox,
            currentTimestampNanos = target.lastSeenSourceTimestampNanos,
            previousBox = previousBox,
            previousTimestampNanos = previousTimestamp,
            sourceTimestampNanos = sourceTimestampNanos,
        )
    }

    private fun predictedBoundingBox(
        competitor: CompetingPersonTrack,
        sourceTimestampNanos: Long,
    ): NormalizedBoundingBox? {
        val previousBox = competitor.previousBoundingBox ?: return null
        val previousTimestamp = competitor.previousSourceTimestampNanos ?: return null
        return predictedBoundingBox(
            currentBox = competitor.boundingBox,
            currentTimestampNanos = competitor.sourceTimestampNanos,
            previousBox = previousBox,
            previousTimestampNanos = previousTimestamp,
            sourceTimestampNanos = sourceTimestampNanos,
        )
    }

    private fun predictedBoundingBox(
        currentBox: NormalizedBoundingBox,
        currentTimestampNanos: Long,
        previousBox: NormalizedBoundingBox,
        previousTimestampNanos: Long,
        sourceTimestampNanos: Long,
    ): NormalizedBoundingBox? {
        val historyInterval = currentTimestampNanos - previousTimestampNanos
        if (historyInterval !in 1..MAX_PREDICTION_HISTORY_INTERVAL_NANOS) return null
        val requestedHorizon = sourceTimestampNanos - currentTimestampNanos
        if (requestedHorizon <= 0L) return null
        val horizon = requestedHorizon.coerceAtMost(MAX_PREDICTION_HORIZON_NANOS)
        val horizonScale = (horizon.toDouble() / historyInterval.toDouble()).toFloat()
        val rawTranslationX = (centerX(currentBox) - centerX(previousBox)) * horizonScale
        val rawTranslationY = (centerY(currentBox) - centerY(previousBox)) * horizonScale
        val rawTranslation = hypot(rawTranslationX, rawTranslationY)
        val translationScale = if (rawTranslation > MAX_PREDICTED_CENTER_TRANSLATION) {
            MAX_PREDICTED_CENTER_TRANSLATION / rawTranslation
        } else {
            1f
        }
        val width = currentBox.right - currentBox.left
        val height = currentBox.bottom - currentBox.top
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val predictedCenterX = (centerX(currentBox) + rawTranslationX * translationScale)
            .coerceIn(halfWidth, 1f - halfWidth)
        val predictedCenterY = (centerY(currentBox) + rawTranslationY * translationScale)
            .coerceIn(halfHeight, 1f - halfHeight)
        return NormalizedBoundingBox(
            left = predictedCenterX - halfWidth,
            top = predictedCenterY - halfHeight,
            right = predictedCenterX + halfWidth,
            bottom = predictedCenterY + halfHeight,
        )
    }

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
        /** Velocity history and extrapolation are deliberately limited to brief detector gaps. */
        const val MAX_PREDICTION_HISTORY_INTERVAL_NANOS = 500_000_000L
        const val MAX_PREDICTION_HORIZON_NANOS = 500_000_000L
        const val MAX_PREDICTED_CENTER_TRANSLATION = 0.20f
        /** Maximum normalized center movement allowed in one association step. */
        const val MAX_CENTER_DISPLACEMENT = 0.20f
        /** Minimum box overlap; all three geometry checks must pass. */
        const val MIN_IOU = 0.05f
        /** Permitted next/previous target-area ratio. */
        const val MIN_AREA_RATIO = 0.50f
        const val MAX_AREA_RATIO = 2.00f
        /** One-candidate continuity scores closer than this cannot establish identity safely. */
        const val CANDIDATE_AMBIGUITY_MARGIN = 0.08f
        /** Joint target/competitor assignments closer than this are genuinely ambiguous. */
        const val ASSIGNMENT_AMBIGUITY_MARGIN = 0.12f
        private const val CENTER_WEIGHT = 0.50f
        private const val IOU_WEIGHT = 0.35f
        private const val SIZE_WEIGHT = 0.15f
    }
}
