package com.alonibh.tellodrone.vision

import android.graphics.Bitmap
import com.alonibh.tellodrone.domain.CompetitorDiagnostic
import com.alonibh.tellodrone.domain.NormalizedBoundingBox
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.TargetAssociationDiagnostics
import com.alonibh.tellodrone.domain.TargetAssociationMetrics
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.TrackedTarget

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

interface VisionTraceRecorder {
    val capturesFrames: Boolean
    /** Called while the decoded-frame lease is valid; debug implementations must detach it. */
    fun captureAnalyzedFrame(frameSequence: Long, sourceTimestampNanos: Long, bitmap: Bitmap) = Unit
    fun record(frame: VisionTraceFrame)
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

    private fun StringBuilder.detections(values: List<PersonDetection>) {
        append('[')
        values.forEachIndexed { index, detection ->
            if (index > 0) comma()
            append('{'); name("box"); box(detection.boundingBox)
            comma(); field("confidence", detection.confidence)
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
