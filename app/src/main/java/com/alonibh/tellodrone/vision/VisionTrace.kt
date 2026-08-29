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
    val renderedFrameTimestampNanos: Long? = null,
    val captureRequestTimestampNanos: Long? = null,
    val pixelCopyCompletedTimestampNanos: Long? = null,
    val detectorInferenceStartedTimestampNanos: Long? = null,
    val detectorInferenceCompletedTimestampNanos: Long? = null,
    val associationCompletedTimestampNanos: Long? = null,
    val detectorPreprocessingNanos: Long? = null,
    val detectorModelInferenceNanos: Long? = null,
    val detectorDecodeAndNmsNanos: Long? = null,
    val detectorAppearanceNanos: Long? = null,
    val analysisMeasuredFps: Float? = null,
    val analysisCapturedFrames: Long? = null,
    val analysisDroppedFrames: Long? = null,
    val analysisPendingFrameDepth: Int? = null,
    val detectorMeasuredFps: Float? = null,
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
    val estimatedTargetCenterX: Float? = null,
    val targetCenterVelocityPerSecond: Float? = null,
    val predictionHorizonMillis: Long? = null,
    val controlYawError: Float? = null,
    val associationCompletedTimestampNanos: Long? = null,
    val yawDecisionTimestampNanos: Long = commandTimestampNanos,
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
    val desiredPublishedAtNanos: Long? = null,
    val sendStartedAtNanos: Long? = null,
    val actualSentAtNanos: Long? = null,
    val estimatedTargetCenterX: Float? = null,
    val targetCenterVelocityPerSecond: Float? = null,
    val predictionHorizonMillis: Long? = null,
    val controlYawError: Float? = null,
    val yawDecisionTimestampNanos: Long? = null,
)

enum class SdkCommandCategory { CONNECT, TAKEOFF, LAND, EMERGENCY, KEEPALIVE, STREAM, CONTROL_MODE, OTHER }

data class SdkCommandTrace(
    val command: String,
    val category: SdkCommandCategory,
    val sentAtMonotonicMillis: Long,
    val latencyMillis: Long,
    val result: String,
)

data class FlightStateTransitionTrace(
    val timestampMillis: Long,
    val fromState: String,
    val toState: String,
    val triggerReason: String,
    val batteryPercent: Int?,
    val heightMeters: Float?,
)

data class ExternalGroundingTrace(
    val timestampMillis: Long,
    val heightMeters: Float?,
    val sampleCount: Int,
)

data class FlightDiagnosticsExport(
    val transitionsCount: Int,
    val commandsCount: Int,
    val rcCount: Long,
    val maxAirborneOutboundGapMillis: Long?,
    val maxAirborneRcGapMillis: Long?,
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
    fun recordSdkCommand(trace: SdkCommandTrace) = Unit
    fun recordFlightStateTransition(trace: FlightStateTransitionTrace) = Unit
    fun recordExternalGrounding(trace: ExternalGroundingTrace) = Unit
    fun recordTelemetrySample(batteryPercent: Int?, heightMeters: Float?) = Unit
    fun export(destinationUri: String, onComplete: (Result<VisionTraceExport>) -> Unit)
    fun exportFlightDiagnostics(destinationUri: String, onComplete: (Result<FlightDiagnosticsExport>) -> Unit) {
        onComplete(Result.failure(IllegalStateException("Flight diagnostics export is available only in debug builds")))
    }
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
        field("schemaVersion", 2)
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
        comma(); field("renderedFrameTimestampNanos", frame.renderedFrameTimestampNanos)
        comma(); field("captureRequestTimestampNanos", frame.captureRequestTimestampNanos)
        comma(); field("pixelCopyCompletedTimestampNanos", frame.pixelCopyCompletedTimestampNanos)
        comma(); field("detectorInferenceStartedTimestampNanos", frame.detectorInferenceStartedTimestampNanos)
        comma(); field("detectorInferenceCompletedTimestampNanos", frame.detectorInferenceCompletedTimestampNanos)
        comma(); field("associationCompletedTimestampNanos", frame.associationCompletedTimestampNanos)
        comma(); field("detectorPreprocessingNanos", frame.detectorPreprocessingNanos)
        comma(); field("detectorModelInferenceNanos", frame.detectorModelInferenceNanos)
        comma(); field("detectorDecodeAndNmsNanos", frame.detectorDecodeAndNmsNanos)
        comma(); field("detectorAppearanceNanos", frame.detectorAppearanceNanos)
        comma(); field("analysisMeasuredFps", frame.analysisMeasuredFps)
        comma(); field("analysisCapturedFrames", frame.analysisCapturedFrames)
        comma(); field("analysisDroppedFrames", frame.analysisDroppedFrames)
        comma(); field("analysisPendingFrameDepth", frame.analysisPendingFrameDepth)
        comma(); field("detectorMeasuredFps", frame.detectorMeasuredFps)
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
        comma(); field("estimatedTargetCenterX", trace.estimatedTargetCenterX)
        comma(); field("targetCenterVelocityPerSecond", trace.targetCenterVelocityPerSecond)
        comma(); field("predictionHorizonMillis", trace.predictionHorizonMillis)
        comma(); field("controlYawError", trace.controlYawError)
        comma(); field("associationCompletedTimestampNanos", trace.associationCompletedTimestampNanos)
        comma(); field("yawDecisionTimestampNanos", trace.yawDecisionTimestampNanos)
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
        comma(); field("estimatedTargetCenterX", trace.estimatedTargetCenterX)
        comma(); field("targetCenterVelocityPerSecond", trace.targetCenterVelocityPerSecond)
        comma(); field("predictionHorizonMillis", trace.predictionHorizonMillis)
        comma(); field("controlYawError", trace.controlYawError)
        comma(); field("yawDecisionTimestampNanos", trace.yawDecisionTimestampNanos)
        comma(); field("desiredPublishedAtNanos", trace.desiredPublishedAtNanos)
        comma(); field("sendStartedAtNanos", trace.sendStartedAtNanos)
        comma(); field("actualSentAtNanos", trace.actualSentAtNanos)
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

    fun encodeSdkCommand(trace: SdkCommandTrace): String = buildString(256) {
        append('{'); field("schemaVersion", 1)
        comma(); field("eventType", "sdkCommand")
        comma(); field("command", trace.command)
        comma(); field("category", trace.category.name)
        comma(); field("sentAtMonotonicMillis", trace.sentAtMonotonicMillis)
        comma(); field("latencyMillis", trace.latencyMillis)
        comma(); field("result", trace.result)
        append('}')
    }

    fun encodeFlightStateTransition(trace: FlightStateTransitionTrace): String = buildString(256) {
        append('{'); field("schemaVersion", 1)
        comma(); field("eventType", "flightTransition")
        comma(); field("timestampMillis", trace.timestampMillis)
        comma(); field("fromState", trace.fromState)
        comma(); field("toState", trace.toState)
        comma(); field("triggerReason", trace.triggerReason)
        comma(); field("batteryPercent", trace.batteryPercent)
        comma(); field("heightMeters", trace.heightMeters)
        append('}')
    }

    fun encodeExternalGrounding(trace: ExternalGroundingTrace): String = buildString(256) {
        append('{'); field("schemaVersion", 1)
        comma(); field("eventType", "externalGrounding")
        comma(); field("timestampMillis", trace.timestampMillis)
        comma(); field("heightMeters", trace.heightMeters)
        comma(); field("sampleCount", trace.sampleCount)
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
        comma(); field("appearanceSimilarity", value.appearanceSimilarity)
        comma(); field("maximumCenterDisplacement", value.maximumCenterDisplacement)
        comma(); field("usedTimeAwareContinuity", value.usedTimeAwareContinuity)
        comma(); name("rejectionReasons"); append('[')
        value.rejectionReasons.forEachIndexed { index, reason ->
            if (index > 0) comma()
            string(reason.name)
        }
        append(']')
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
