package com.alonibh.tellodrone.vision

import android.graphics.Bitmap
import com.alonibh.tellodrone.domain.CompetitorDiagnostic
import com.alonibh.tellodrone.domain.NormalizedBoundingBox
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.HsvAppearanceHistogram
import com.alonibh.tellodrone.domain.TargetAssociationDiagnostics
import com.alonibh.tellodrone.domain.TargetAssociationMetrics
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.TrackedTarget
import com.alonibh.tellodrone.domain.YawControlSuppressionReason
import com.alonibh.tellodrone.domain.YawFollowReason
import com.alonibh.tellodrone.domain.YawFollowState
import com.alonibh.tellodrone.tello.RcInputKind
import com.alonibh.tellodrone.tello.RcSendSuppressionReason
import com.alonibh.tellodrone.tello.RcVector

data class VisionTraceFrame(
    val frameSequence: Long,
    val sourceTimestampNanos: Long,
    val detectorModel: String?,
    val detectorBackend: String?,
    val confidenceThreshold: Float?,
    val inferenceMillis: Long?,
    val candidates: List<PersonDetection>,
    val detections: List<PersonDetection>,
    val selectedTargetBefore: TrackedTarget?,
    val selectedTargetAfter: TrackedTarget?,
    val associationState: TargetAssociationState,
    val associationDiagnostics: TargetAssociationDiagnostics?,
)

data class VisionTraceExport(val frameCount: Long, val droppedFrameCount: Long)

data class YawControlMeasurementTrace(
    val frameSequence: Long,
    val sourceTimestampNanos: Long,
    val commandTimestampNanos: Long,
    val perceptionAgeMillis: Long?,
    val targetCenterX: Float?,
    val rawYawError: Float?,
    val filteredYawError: Float?,
    val associationState: TargetAssociationState,
    val previousYawRc: Int,
    val requestedYawRc: Int,
    val safetyFilteredYawRc: Int,
    val suppressionReason: YawControlSuppressionReason,
    val telemetryHeightMeters: Float?,
    val yawFollowState: YawFollowState,
    val yawFollowReason: YawFollowReason,
)

data class RcPublicationTrace(
    val commandTimestampNanos: Long,
    val frameSequence: Long?,
    val sourceTimestampNanos: Long?,
    val perceptionAgeMillis: Long?,
    val targetCenterX: Float?,
    val rawYawError: Float?,
    val filteredYawError: Float?,
    val associationState: TargetAssociationState,
    val previousYawRc: Int,
    val requestedYawRc: Int,
    val safetyFilteredYawRc: Int,
    val yawSuppressionReason: YawControlSuppressionReason?,
    val requestedVector: RcVector,
    val actualSentVector: RcVector,
    val inputKind: RcInputKind,
    val sendSuppressionReason: RcSendSuppressionReason,
    val telemetryHeightMeters: Float?,
    val yawFollowState: YawFollowState,
    val yawFollowReason: YawFollowReason,
)

interface VisionTraceRecorder {
    val capturesFrames: Boolean
    /** Called while the decoded-frame lease is valid; debug implementations must detach it. */
    fun captureAnalyzedFrame(frameSequence: Long, sourceTimestampNanos: Long, bitmap: Bitmap) = Unit
    /** Starts a fresh diagnostic epoch at the explicit user-selection boundary. */
    fun onTargetSelected(target: TrackedTarget) = Unit
    fun record(frame: VisionTraceFrame)
    fun recordControlMeasurement(trace: YawControlMeasurementTrace) = Unit
    fun recordRcPublication(trace: RcPublicationTrace) = Unit
    fun export(destinationUri: String, onComplete: (Result<VisionTraceExport>) -> Unit)
}

object NoOpVisionTraceRecorder : VisionTraceRecorder {
    override val capturesFrames = false
    override fun record(frame: VisionTraceFrame) = Unit
    override fun export(destinationUri: String, onComplete: (Result<VisionTraceExport>) -> Unit) {
        onComplete(Result.failure(IllegalStateException("Vision trace export is available only in debug builds")))
    }
}

/** Pure compact encoder; file I/O exists only in the debug source set. */
internal object VisionTraceJson {
    fun encode(
        frame: VisionTraceFrame,
        droppedBeforeFrame: Long,
        capturedFrameFile: String? = null,
    ): String = buildString(1_024) {
        append('{')
        field("schemaVersion", 1)
        comma(); field("frameSequence", frame.frameSequence)
        comma(); field("sourceTimestampNanos", frame.sourceTimestampNanos)
        comma(); field("capturedFrameFile", capturedFrameFile)
        comma(); name("detector"); append('{')
        field("model", frame.detectorModel)
        comma(); field("backend", frame.detectorBackend)
        comma(); field("confidenceThreshold", frame.confidenceThreshold)
        comma(); field("inferenceMillis", frame.inferenceMillis)
        comma(); name("candidates"); detections(frame.candidates)
        comma(); name("acceptedDetections"); detections(frame.detections)
        append('}')
        comma(); name("selectedTargetBefore"); target(frame.selectedTargetBefore)
        comma(); name("selectedTargetAfter"); target(frame.selectedTargetAfter)
        comma(); field("associationState", frame.associationState.name)
        comma(); name("associationDiagnostics"); diagnostics(frame.associationDiagnostics)
        comma(); field("droppedBeforeFrame", droppedBeforeFrame)
        append('}')
    }

    fun encodeControlMeasurement(trace: YawControlMeasurementTrace): String = buildString(512) {
        append('{'); field("schemaVersion", 2)
        comma(); field("eventType", "controlMeasurement")
        comma(); field("frameSequence", trace.frameSequence)
        comma(); field("sourceTimestampNanos", trace.sourceTimestampNanos)
        comma(); field("commandTimestampNanos", trace.commandTimestampNanos)
        comma(); field("perceptionAgeMillis", trace.perceptionAgeMillis)
        comma(); field("targetCenterX", trace.targetCenterX)
        comma(); field("rawYawError", trace.rawYawError)
        comma(); field("filteredYawError", trace.filteredYawError)
        comma(); field("associationState", trace.associationState.name)
        comma(); field("previousYawRc", trace.previousYawRc)
        comma(); field("requestedYawRc", trace.requestedYawRc)
        comma(); field("safetyFilteredYawRc", trace.safetyFilteredYawRc)
        comma(); field("suppressionReason", trace.suppressionReason.name)
        comma(); field("telemetryHeightMeters", trace.telemetryHeightMeters)
        comma(); field("yawFollowState", trace.yawFollowState.name)
        comma(); field("yawFollowReason", trace.yawFollowReason.name)
        append('}')
    }

    fun encodeRcPublication(trace: RcPublicationTrace): String = buildString(640) {
        append('{'); field("schemaVersion", 2)
        comma(); field("eventType", "rcPublication")
        comma(); field("commandTimestampNanos", trace.commandTimestampNanos)
        comma(); field("frameSequence", trace.frameSequence)
        comma(); field("sourceTimestampNanos", trace.sourceTimestampNanos)
        comma(); field("perceptionAgeMillis", trace.perceptionAgeMillis)
        comma(); field("targetCenterX", trace.targetCenterX)
        comma(); field("rawYawError", trace.rawYawError)
        comma(); field("filteredYawError", trace.filteredYawError)
        comma(); field("associationState", trace.associationState.name)
        comma(); field("previousYawRc", trace.previousYawRc)
        comma(); field("requestedYawRc", trace.requestedYawRc)
        comma(); field("safetyFilteredYawRc", trace.safetyFilteredYawRc)
        comma(); field("yawSuppressionReason", trace.yawSuppressionReason?.name)
        comma(); name("requestedVector"); vector(trace.requestedVector)
        comma(); name("actualSentVector"); vector(trace.actualSentVector)
        comma(); field("inputKind", trace.inputKind.name)
        comma(); field("sendSuppressionReason", trace.sendSuppressionReason.name)
        comma(); field("telemetryHeightMeters", trace.telemetryHeightMeters)
        comma(); field("yawFollowState", trace.yawFollowState.name)
        comma(); field("yawFollowReason", trace.yawFollowReason.name)
        append('}')
    }

    private fun StringBuilder.vector(value: RcVector) {
        append('{'); field("lateral", value.lateral)
        comma(); field("forward", value.forward)
        comma(); field("vertical", value.vertical)
        comma(); field("yaw", value.yaw)
        append('}')
    }

    private fun StringBuilder.detections(values: List<PersonDetection>) {
        append('[')
        values.forEachIndexed { index, detection ->
            if (index > 0) comma()
            append('{'); name("box"); box(detection.boundingBox)
            comma(); field("confidence", detection.confidence)
            comma(); name("appearance"); appearance(detection.appearance)
            append('}')
        }
        append(']')
    }

    private fun StringBuilder.target(value: TrackedTarget?) {
        if (value == null) { append("null"); return }
        append('{'); name("box"); box(value.boundingBox)
        comma(); field("confidence", value.confidence)
        comma(); field("selectedFrameSequence", value.selectedFrameSequence)
        comma(); field("selectedSourceTimestampNanos", value.selectedSourceTimestampNanos)
        comma(); field("lastSeenFrameSequence", value.lastSeenFrameSequence)
        comma(); field("lastSeenSourceTimestampNanos", value.lastSeenSourceTimestampNanos)
        comma(); field("identityUncertain", value.identityUncertain)
        comma(); name("appearance"); appearance(value.appearance)
        comma(); field("competitorCount", value.competingPeople.size)
        append('}')
    }

    private fun StringBuilder.diagnostics(value: TargetAssociationDiagnostics?) {
        if (value == null) { append("null"); return }
        append('{'); field("decision", value.decision.name)
        comma(); field("targetAgeNanos", value.targetAgeNanos)
        comma(); name("predictedTargetBox"); box(value.predictedTargetBoundingBox)
        comma(); field("selectedDetectionIndex", value.selectedDetectionIndex)
        comma(); field("eligibleCandidateCount", value.eligibleCandidateCount)
        comma(); name("targetCandidates"); append('[')
        value.candidates.forEachIndexed { index, candidate ->
            if (index > 0) comma()
            append('{'); field("detectionIndex", candidate.detectionIndex)
            comma(); field("eligible", candidate.eligible)
            comma(); field("score", candidate.score)
            comma(); name("strict"); metrics(candidate.strict)
            comma(); name("predicted"); metrics(candidate.predicted)
            append('}')
        }
        append(']')
        comma(); name("competitors"); append('[')
        value.competitors.forEachIndexed { index, competitor ->
            if (index > 0) comma()
            competitor(competitor)
        }
        append(']'); append('}')
    }

    private fun StringBuilder.competitor(value: CompetitorDiagnostic) {
        append('{'); field("competitorIndex", value.competitorIndex)
        comma(); name("box"); box(value.boundingBox)
        comma(); name("predictedBox"); box(value.predictedBoundingBox)
        comma(); name("detectionMatches"); append('[')
        value.detectionMatches.forEachIndexed { index, match ->
            if (index > 0) comma()
            append('{'); field("detectionIndex", match.detectionIndex)
            comma(); name("metrics"); metrics(match.metrics)
            append('}')
        }
        append(']'); append('}')
    }

    private fun StringBuilder.metrics(value: TargetAssociationMetrics?) {
        if (value == null) { append("null"); return }
        append('{'); field("centerDisplacement", value.centerDisplacement)
        comma(); field("iou", value.iou)
        comma(); field("areaRatio", value.areaRatio)
        comma(); field("eligible", value.eligible)
        comma(); field("score", value.score)
        append('}')
    }

    private fun StringBuilder.box(value: NormalizedBoundingBox?) {
        if (value == null) { append("null"); return }
        append('[').append(value.left).append(',').append(value.top).append(',')
            .append(value.right).append(',').append(value.bottom).append(']')
    }

    private fun StringBuilder.appearance(value: HsvAppearanceHistogram?) {
        if (value == null) { append("null"); return }
        append('[')
        value.bins.forEachIndexed { index, bin ->
            if (index > 0) comma()
            append(bin)
        }
        append(']')
    }

    private fun StringBuilder.name(value: String) { string(value); append(':') }
    private fun StringBuilder.comma() { append(',') }
    private fun StringBuilder.field(name: String, value: String?) { name(name); if (value == null) append("null") else string(value) }
    private fun StringBuilder.field(name: String, value: Long?) { name(name); append(value ?: "null") }
    private fun StringBuilder.field(name: String, value: Int) { name(name); append(value) }
    private fun StringBuilder.field(name: String, value: Int?) { name(name); append(value ?: "null") }
    private fun StringBuilder.field(name: String, value: Float?) { name(name); append(value ?: "null") }
    private fun StringBuilder.field(name: String, value: Boolean) { name(name); append(value) }
    private fun StringBuilder.string(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
