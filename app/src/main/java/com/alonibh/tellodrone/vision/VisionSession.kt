package com.alonibh.tellodrone.vision

import com.alonibh.tellodrone.domain.NormalizedBoundingBox
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.HsvAppearanceHistogram
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.TargetAssociationEngine
import com.alonibh.tellodrone.domain.TargetAssociationResult
import com.alonibh.tellodrone.domain.TrackedTarget
import java.io.File
import java.util.zip.ZipFile
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

const val VISION_SESSION_SCHEMA_VERSION = 2
const val VISION_SESSION_MAX_FRAMES = 600
const val VISION_SESSION_MAX_DURATION_NANOS = 90_000_000_000L
const val VISION_SESSION_JPEG_QUALITY = 82

/** Thread-safe reservation boundary shared by capture and JVM regression tests. */
class VisionCaptureLimiter(
    private val maxFrames: Int = VISION_SESSION_MAX_FRAMES,
    private val maxDurationNanos: Long = VISION_SESSION_MAX_DURATION_NANOS,
) {
    private var firstTimestampNanos: Long? = null
    private var accepted = 0

    @Synchronized
    fun reserve(sourceTimestampNanos: Long): VisionCaptureReservation {
        if (sourceTimestampNanos < 0L) return VisionCaptureReservation.InvalidTimestamp
        if (accepted >= maxFrames) return VisionCaptureReservation.FrameLimitReached
        val first = firstTimestampNanos
        if (first != null && sourceTimestampNanos < first) return VisionCaptureReservation.InvalidTimestamp
        if (first != null && sourceTimestampNanos - first > maxDurationNanos) {
            return VisionCaptureReservation.DurationLimitReached
        }
        if (first == null) firstTimestampNanos = sourceTimestampNanos
        accepted++
        return VisionCaptureReservation.Accepted
    }

    @Synchronized fun acceptedCount(): Int = accepted

    @Synchronized fun reset() {
        firstTimestampNanos = null
        accepted = 0
    }
}

enum class VisionCaptureReservation {
    Accepted,
    FrameLimitReached,
    DurationLimitReached,
    InvalidTimestamp,
}

enum class VisionCaptureStartReason {
    DetectionStarted,
    TargetSelected,
    Legacy,
}

class VisionCaptureDropCounter {
    private val total = AtomicLong()
    private val sinceLastPair = AtomicLong()
    fun recordDrop(count: Long = 1L) {
        require(count >= 0L)
        total.addAndGet(count)
        sinceLastPair.addAndGet(count)
    }
    fun consumeSinceLastPair(): Long = sinceLastPair.getAndSet(0L)
    fun restoreSinceLastPair(count: Long) {
        require(count >= 0L)
        sinceLastPair.addAndGet(count)
    }
    fun total(): Long = total.get()
    fun reset() { total.set(0L); sinceLastPair.set(0L) }
}

data class VisionSessionFrameEntry(
    val captureIndex: Int,
    val frameSequence: Long,
    val sourceTimestampNanos: Long,
    val file: String,
    val width: Int,
    val height: Int,
)

data class VisionSessionManifest(
    val schemaVersion: Int = VISION_SESSION_SCHEMA_VERSION,
    val frameEncoding: String = "jpeg",
    val jpegQuality: Int = VISION_SESSION_JPEG_QUALITY,
    val maxFrames: Int = VISION_SESSION_MAX_FRAMES,
    val maxDurationNanos: Long = VISION_SESSION_MAX_DURATION_NANOS,
    val capturedFrameCount: Int,
    val droppedFrameCount: Long,
    val excludedAfterLimitFrameCount: Long = 0,
    val captureStartReason: VisionCaptureStartReason = VisionCaptureStartReason.DetectionStarted,
    val frames: List<VisionSessionFrameEntry>,
)

data class VisionSessionExport(
    val capturedFrameCount: Int,
    val droppedFrameCount: Long,
    val excludedAfterLimitFrameCount: Long,
    val durationNanos: Long,
)

data class VisionSessionSelection(
    val frameCount: Int,
    val droppedFrameCount: Long,
    val associationEvaluationValid: Boolean,
)

data class VisionSessionTraceSeed(
    val frameSequence: Long,
    val sourceTimestampNanos: Long,
    val capturedFrameFile: String,
    val selectedTargetBefore: TrackedTarget?,
    val associationState: TargetAssociationState,
)

data class VisionSessionContents(
    val archive: File,
    val manifest: VisionSessionManifest,
    val orderedFrames: List<VisionSessionFrameEntry>,
    val traceSeeds: Map<Pair<Long, Long>, VisionSessionTraceSeed>,
)

class MalformedVisionSessionException(message: String) : IllegalArgumentException(message)

object VisionSessionManifestJson {
    fun encode(manifest: VisionSessionManifest): String = buildString(256 + manifest.frames.size * 128) {
        append('{')
        append("\"schemaVersion\":").append(manifest.schemaVersion)
        append(",\"frameEncoding\":\"").append(manifest.frameEncoding).append('"')
        append(",\"jpegQuality\":").append(manifest.jpegQuality)
        append(",\"maxFrames\":").append(manifest.maxFrames)
        append(",\"maxDurationNanos\":").append(manifest.maxDurationNanos)
        append(",\"capturedFrameCount\":").append(manifest.capturedFrameCount)
        append(",\"droppedFrameCount\":").append(manifest.droppedFrameCount)
        append(",\"excludedAfterLimitFrameCount\":").append(manifest.excludedAfterLimitFrameCount)
        append(",\"captureStartReason\":\"").append(manifest.captureStartReason.name).append('"')
        append(",\"frames\":[")
        manifest.frames.forEachIndexed { index, frame ->
            if (index > 0) append(',')
            append('{')
            append("\"captureIndex\":").append(frame.captureIndex)
            append(",\"frameSequence\":").append(frame.frameSequence)
            append(",\"sourceTimestampNanos\":").append(frame.sourceTimestampNanos)
            append(",\"file\":\"").append(frame.file).append('"')
            append(",\"width\":").append(frame.width)
            append(",\"height\":").append(frame.height)
            append('}')
        }
        append("]}")
    }

    fun decode(json: String): VisionSessionManifest {
        val root = CompactJson.parseObject(json)
        val frames = root.array("frames").map { value ->
            val frame = value.asObject("frame")
            VisionSessionFrameEntry(
                captureIndex = frame.int("captureIndex"),
                frameSequence = frame.long("frameSequence"),
                sourceTimestampNanos = frame.long("sourceTimestampNanos"),
                file = frame.string("file"),
                width = frame.int("width"),
                height = frame.int("height"),
            )
        }
        val schemaVersion = root.int("schemaVersion")
        return VisionSessionManifest(
            schemaVersion = schemaVersion,
            frameEncoding = root.string("frameEncoding"),
            jpegQuality = root.int("jpegQuality"),
            maxFrames = root.int("maxFrames"),
            maxDurationNanos = root.long("maxDurationNanos"),
            capturedFrameCount = root.int("capturedFrameCount"),
            droppedFrameCount = root.long("droppedFrameCount"),
            excludedAfterLimitFrameCount = root.optionalLong("excludedAfterLimitFrameCount") ?: 0L,
            captureStartReason = root.optionalString("captureStartReason")?.let { raw ->
                runCatching { VisionCaptureStartReason.valueOf(raw) }
                    .getOrElse { throw MalformedVisionSessionException("Invalid captureStartReason") }
            } ?: VisionCaptureStartReason.Legacy,
            frames = frames,
        )
    }
}

object VisionSessionArchive {
    private const val MAX_MANIFEST_BYTES = 1_000_000L
    private const val MAX_TRACE_BYTES = 16_000_000L
    private const val MAX_CONTROL_TRACE_BYTES = 16_000_000L
    private const val MAX_FRAME_BYTES = 4_000_000L

    fun open(file: File): VisionSessionContents {
        if (!file.isFile) malformed("Session file does not exist")
        ZipFile(file).use { zip ->
            val entryNames = zip.entries().asSequence().map { it.name }.toList()
            if (entryNames.toSet().size != entryNames.size) malformed("Duplicate ZIP entry")
            val manifestEntry = zip.getEntry("manifest.json") ?: malformed("manifest.json is missing")
            val traceEntry = zip.getEntry("trace.jsonl") ?: malformed("trace.jsonl is missing")
            val controlEntry = zip.getEntry("control.jsonl")
            if (manifestEntry.size !in 1..MAX_MANIFEST_BYTES) malformed("manifest.json has an unsafe size")
            if (traceEntry.size !in 1..MAX_TRACE_BYTES) malformed("trace.jsonl has an unsafe size")
            val manifest = runCatching {
                zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() }
                    .let(VisionSessionManifestJson::decode)
            }.getOrElse { malformed("Invalid manifest.json: ${it.message}") }
            validateManifest(manifest)
            if (controlEntry != null && controlEntry.size !in 0..MAX_CONTROL_TRACE_BYTES) {
                malformed("control.jsonl has an unsafe size")
            }
            val expectedEntries = setOf("manifest.json", "trace.jsonl") +
                (if (controlEntry != null) setOf("control.jsonl") else emptySet()) +
                manifest.frames.map { it.file }
            if (entryNames.toSet() != expectedEntries) malformed("Unexpected ZIP entries")
            manifest.frames.forEach { frame ->
                val entry = zip.getEntry(frame.file) ?: malformed("Missing frame ${frame.file}")
                if (entry.isDirectory || entry.size !in 1..MAX_FRAME_BYTES) malformed("Unsafe frame entry ${frame.file}")
            }
            val traceLines = zip.getInputStream(traceEntry).bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.toList()
            }
            if (traceLines.size != manifest.frames.size) malformed("trace/frame count mismatch")
            val seeds = traceLines.map { decodeTraceSeed(it) }
            val traceKeys = seeds.map { it.frameSequence to it.sourceTimestampNanos }
            val frameKeys = manifest.frames.map { it.frameSequence to it.sourceTimestampNanos }
            if (traceKeys.toSet().size != traceKeys.size || frameKeys.toSet().size != frameKeys.size) {
                malformed("Duplicate frame identity")
            }
            if (traceKeys.toSet() != frameKeys.toSet()) malformed("trace/frame identity mismatch")
            val filesByKey = manifest.frames.associate { (it.frameSequence to it.sourceTimestampNanos) to it.file }
            if (seeds.any { filesByKey[it.frameSequence to it.sourceTimestampNanos] != it.capturedFrameFile }) {
                malformed("trace/frame file mismatch")
            }
            return VisionSessionContents(
                archive = file,
                manifest = manifest,
                orderedFrames = manifest.frames.sortedWith(
                    compareBy<VisionSessionFrameEntry> { it.sourceTimestampNanos }
                        .thenBy { it.frameSequence }
                        .thenBy { it.captureIndex },
                ),
                traceSeeds = seeds.associateBy { it.frameSequence to it.sourceTimestampNanos },
            )
        }
    }

    private fun validateManifest(manifest: VisionSessionManifest) {
        if (manifest.schemaVersion !in 1..VISION_SESSION_SCHEMA_VERSION) malformed("Unsupported schema version")
        if (manifest.frameEncoding != "jpeg") malformed("Unsupported frame encoding")
        if (manifest.jpegQuality !in 1..100) malformed("Invalid JPEG quality")
        if (manifest.maxFrames != VISION_SESSION_MAX_FRAMES || manifest.maxDurationNanos != VISION_SESSION_MAX_DURATION_NANOS) {
            malformed("Unexpected capture bounds")
        }
        if (manifest.frames.isEmpty() || manifest.frames.size > VISION_SESSION_MAX_FRAMES) malformed("Invalid frame count")
        if (manifest.capturedFrameCount != manifest.frames.size) malformed("Manifest frame count mismatch")
        if (manifest.droppedFrameCount < 0L) malformed("Invalid dropped-frame count")
        if (manifest.excludedAfterLimitFrameCount < 0L) malformed("Invalid post-limit frame count")
        if (manifest.frames.map { it.captureIndex }.toSet().size != manifest.frames.size) malformed("Duplicate capture index")
        manifest.frames.forEach { frame ->
            if (frame.captureIndex !in 1..VISION_SESSION_MAX_FRAMES) malformed("Invalid capture index")
            if (frame.frameSequence < 0L || frame.sourceTimestampNanos < 0L) malformed("Invalid frame identity")
            if (frame.width !in 1..MAX_FRAME_DIMENSION || frame.height !in 1..MAX_FRAME_DIMENSION ||
                frame.width.toLong() * frame.height.toLong() > MAX_FRAME_PIXELS
            ) malformed("Invalid frame dimensions")
            if (frame.file != "frames/${frame.captureIndex.toString().padStart(6, '0')}.jpg") malformed("Unsafe frame path")
        }
        val timestamps = manifest.frames.map { it.sourceTimestampNanos }
        if ((timestamps.maxOrNull()!! - timestamps.minOrNull()!!) > VISION_SESSION_MAX_DURATION_NANOS) malformed("Session exceeds duration bound")
    }

    private fun decodeTraceSeed(line: String): VisionSessionTraceSeed {
        val root = CompactJson.parseObject(line)
        val target = (root["selectedTargetBefore"] as? Map<*, *>)?.let { raw ->
            @Suppress("UNCHECKED_CAST") val value = raw as Map<String, Any?>
            val boxValues = value.array("box").map { it.asFloat("box coordinate") }
            if (boxValues.size != 4) malformed("Invalid selected target box")
            TrackedTarget(
                boundingBox = NormalizedBoundingBox(boxValues[0], boxValues[1], boxValues[2], boxValues[3]),
                confidence = value.float("confidence"),
                selectedFrameSequence = value.long("selectedFrameSequence"),
                selectedSourceTimestampNanos = value.long("selectedSourceTimestampNanos"),
                lastSeenFrameSequence = value.long("lastSeenFrameSequence"),
                lastSeenSourceTimestampNanos = value.long("lastSeenSourceTimestampNanos"),
                identityUncertain = value.boolean("identityUncertain"),
                appearance = (value["appearance"] as? List<*>)?.let { bins ->
                    val decoded = bins.map { it.asFloat("appearance bin") }
                    if (decoded.size != HsvAppearanceHistogram.BIN_COUNT) malformed("Invalid appearance histogram")
                    HsvAppearanceHistogram(decoded)
                },
            )
        }
        return VisionSessionTraceSeed(
            root.long("frameSequence"),
            root.long("sourceTimestampNanos"),
            root.string("capturedFrameFile"),
            target,
            runCatching { TargetAssociationState.valueOf(root.string("associationState")) }
                .getOrElse { malformed("Invalid association state") },
        )
    }

    private fun malformed(message: String): Nothing = throw MalformedVisionSessionException(message)

    private const val MAX_FRAME_DIMENSION = 4_096
    private const val MAX_FRAME_PIXELS = 16_777_216L
}

data class VisionReplayFrameResult(
    val frameFile: String,
    val frameSequence: Long,
    val sourceTimestampNanos: Long,
    val inferenceNanos: Long,
    val candidates: List<PersonDetection>,
    val acceptedDetections: List<PersonDetection>,
    val duplicateDetectionCount: Int,
    val associationState: TargetAssociationState,
    val selectedDetectionIndex: Int?,
    val identitySwitchSafetyViolation: Boolean,
)

data class VisionReplayAssociationOutcome(
    val state: TargetAssociationState,
    val selectedDetectionIndex: Int?,
    val identitySwitchSafetyViolation: Boolean,
)

/** Deterministic replay of the production fail-closed association state machine. */
class VisionReplayAssociation(
    private val engine: TargetAssociationEngine = TargetAssociationEngine(),
) {
    private var target: TrackedTarget? = null
    private var state = TargetAssociationState.None
    private var activeOriginalSelection: Pair<Long, Long>? = null
    private var lostLatched = false

    fun evaluate(
        frameSequence: Long,
        sourceTimestampNanos: Long,
        detections: List<PersonDetection>,
        recordedSelection: TrackedTarget?,
    ): VisionReplayAssociationOutcome {
        val originalSelection = recordedSelection?.let { it.selectedFrameSequence to it.selectedSourceTimestampNanos }
        var selectedIndex: Int? = null
        var violation = false
        val explicitReselection = originalSelection != null && originalSelection != activeOriginalSelection
        if (explicitReselection) {
            target = if (recordedSelection.appearance != null) recordedSelection else {
                val matching = detections.minByOrNull { detection ->
                    kotlin.math.abs(detection.boundingBox.left - recordedSelection.boundingBox.left) +
                        kotlin.math.abs(detection.boundingBox.top - recordedSelection.boundingBox.top) +
                        kotlin.math.abs(detection.boundingBox.right - recordedSelection.boundingBox.right) +
                        kotlin.math.abs(detection.boundingBox.bottom - recordedSelection.boundingBox.bottom)
                }
                recordedSelection.copy(appearance = matching?.appearance)
            }
            activeOriginalSelection = originalSelection
            state = TargetAssociationState.Selected
            lostLatched = false
        }
        val selectionIsThisFrame = explicitReselection && originalSelection == (frameSequence to sourceTimestampNanos)
        if (target != null && !selectionIsThisFrame) {
            val evaluation = engine.evaluate(
                selectedTarget = target,
                frameSequence = frameSequence,
                sourceTimestampNanos = sourceTimestampNanos,
                detections = detections,
                includeDetailedDiagnostics = true,
            )
            selectedIndex = evaluation.diagnostics.selectedDetectionIndex
            when (val associated = evaluation.result) {
                is TargetAssociationResult.Matched -> {
                    violation = lostLatched
                    target = associated.target
                    state = TargetAssociationState.Matched
                }
                is TargetAssociationResult.TemporarilyMissing -> {
                    target = associated.target
                    state = TargetAssociationState.TemporarilyMissing
                }
                is TargetAssociationResult.Ambiguous -> {
                    target = associated.target
                    state = TargetAssociationState.Ambiguous
                }
                is TargetAssociationResult.Lost -> {
                    target = null
                    state = TargetAssociationState.Lost
                    lostLatched = true
                }
                is TargetAssociationResult.Ignored -> target = associated.target
            }
        } else if (!explicitReselection && lostLatched) {
            state = TargetAssociationState.Lost
        }
        return VisionReplayAssociationOutcome(state, selectedIndex, violation)
    }
}

data class VisionReplayModelResult(
    val model: String,
    val assetFile: String,
    val quantization: String,
    val backend: String,
    val confidenceThreshold: Float,
    val startupNanos: Long,
    val frames: List<VisionReplayFrameResult>,
)

data class VisionComparisonReport(
    val schemaVersion: Int = 2,
    val sessionFrameCount: Int,
    val sessionDroppedFrameCount: Long,
    val sessionExcludedAfterLimitFrameCount: Long = 0,
    val captureStartReason: VisionCaptureStartReason = VisionCaptureStartReason.Legacy,
    val associationEvaluationValid: Boolean = false,
    val associationEvaluationWarning: String? = null,
    val recordedLiveAssociationFrames: List<VisionRecordedAssociationFrame> = emptyList(),
    val models: List<VisionReplayModelResult>,
)

data class VisionRecordedAssociationFrame(
    val frameSequence: Long,
    val sourceTimestampNanos: Long,
    val state: TargetAssociationState,
)

data class VisionAssociationEvaluation(
    val valid: Boolean,
    val warning: String?,
)

fun VisionSessionContents.associationEvaluation(): VisionAssociationEvaluation {
    val reasons = buildList {
        if (manifest.captureStartReason != VisionCaptureStartReason.TargetSelected) {
            add("capture did not start at an explicit target selection")
        }
        if (manifest.droppedFrameCount > 0L) add("${manifest.droppedFrameCount} analyzed frame(s) were dropped")
        if (manifest.excludedAfterLimitFrameCount > 0L) {
            add("${manifest.excludedAfterLimitFrameCount} frame(s) occurred after the capture limit")
        }
        if (traceSeeds.values.none { it.selectedTargetBefore != null }) add("no selected-target seed is present")
    }
    return if (reasons.isEmpty()) VisionAssociationEvaluation(true, null) else {
        VisionAssociationEvaluation(false, "Association evaluation is incomplete: ${reasons.joinToString("; ")}.")
    }
}

data class VisionTimingSummary(
    val minNanos: Long,
    val p50Nanos: Long,
    val p95Nanos: Long,
    val maxNanos: Long,
    val effectiveFps: Double,
)

object VisionComparisonReportJson {
    fun timing(frames: List<VisionReplayFrameResult>): VisionTimingSummary {
        require(frames.isNotEmpty())
        val sorted = frames.map { it.inferenceNanos }.sorted()
        val total = sorted.sum().coerceAtLeast(1L)
        return VisionTimingSummary(
            minNanos = sorted.first(),
            p50Nanos = percentile(sorted, .50),
            p95Nanos = percentile(sorted, .95),
            maxNanos = sorted.last(),
            effectiveFps = frames.size * 1_000_000_000.0 / total,
        )
    }

    fun encode(report: VisionComparisonReport): String = buildString(4_096) {
        append('{').append("\"schemaVersion\":").append(report.schemaVersion)
        append(",\"sessionFrameCount\":").append(report.sessionFrameCount)
        append(",\"sessionDroppedFrameCount\":").append(report.sessionDroppedFrameCount)
        append(",\"sessionExcludedAfterLimitFrameCount\":").append(report.sessionExcludedAfterLimitFrameCount)
        append(','); stringField("captureStartReason", report.captureStartReason.name)
        append(",\"associationEvaluationValid\":").append(report.associationEvaluationValid)
        append(",\"associationEvaluationWarning\":")
        report.associationEvaluationWarning?.let { append('"'); escaped(it); append('"') } ?: append("null")
        append(",\"recordedLiveMissingTransitions\":")
            .append(recordedTransitionCount(report.recordedLiveAssociationFrames, TargetAssociationState.TemporarilyMissing))
        append(",\"recordedLiveAmbiguousTransitions\":")
            .append(recordedTransitionCount(report.recordedLiveAssociationFrames, TargetAssociationState.Ambiguous))
        append(",\"recordedLiveLostTransitions\":")
            .append(recordedTransitionCount(report.recordedLiveAssociationFrames, TargetAssociationState.Lost))
        append(",\"recordedLiveAssociationTransitions\":[")
        recordedAssociationTransitions(report.recordedLiveAssociationFrames).forEachIndexed { index, transition ->
            if (index > 0) append(',')
            append("{\"frameSequence\":").append(transition.frame.frameSequence)
            append(",\"sourceTimestampNanos\":").append(transition.frame.sourceTimestampNanos)
            append(",\"from\":\"").append(transition.from.name).append('"')
            append(",\"to\":\"").append(transition.frame.state.name).append("\"}")
        }
        append(']')
        append(",\"models\":[")
        report.models.forEachIndexed { modelIndex, model ->
            if (modelIndex > 0) append(',')
            val timing = timing(model.frames)
            append('{'); stringField("model", model.model)
            append(','); stringField("assetFile", model.assetFile)
            append(','); stringField("quantization", model.quantization)
            append(','); stringField("backend", model.backend)
            append(",\"confidenceThreshold\":").append(model.confidenceThreshold)
            append(",\"startupNanos\":").append(model.startupNanos)
            append(",\"inferenceNanos\":{")
            append("\"min\":").append(timing.minNanos)
            append(",\"p50\":").append(timing.p50Nanos)
            append(",\"p95\":").append(timing.p95Nanos)
            append(",\"max\":").append(timing.maxNanos).append('}')
            append(",\"effectiveFps\":").append(timing.effectiveFps)
            append(",\"associationEvaluationValid\":").append(report.associationEvaluationValid)
            append(",\"associationEvaluationWarning\":")
            report.associationEvaluationWarning?.let { append('"'); escaped(it); append('"') } ?: append("null")
            append(",\"missingTransitions\":").append(transitionCount(model.frames, TargetAssociationState.TemporarilyMissing))
            append(",\"ambiguousTransitions\":").append(transitionCount(model.frames, TargetAssociationState.Ambiguous))
            append(",\"lostTransitions\":").append(transitionCount(model.frames, TargetAssociationState.Lost))
            append(",\"duplicateDetections\":").append(model.frames.sumOf { it.duplicateDetectionCount })
            append(",\"identitySwitchSafetyViolation\":").append(model.frames.any { it.identitySwitchSafetyViolation })
            append(",\"associationTransitions\":[")
            associationTransitions(model.frames).forEachIndexed { index, transition ->
                if (index > 0) append(',')
                append("{\"frameSequence\":").append(transition.frame.frameSequence)
                append(",\"sourceTimestampNanos\":").append(transition.frame.sourceTimestampNanos)
                append(",\"from\":\"").append(transition.from.name).append('"')
                append(",\"to\":\"").append(transition.frame.associationState.name).append("\"}")
            }
            append(']')
            append(",\"frames\":[")
            model.frames.forEachIndexed { frameIndex, frame ->
                if (frameIndex > 0) append(',')
                append('{'); stringField("frameFile", frame.frameFile)
                append(",\"frameSequence\":").append(frame.frameSequence)
                append(",\"sourceTimestampNanos\":").append(frame.sourceTimestampNanos)
                append(",\"inferenceNanos\":").append(frame.inferenceNanos)
                append(",\"acceptedDetectionCount\":").append(frame.acceptedDetections.size)
                append(",\"duplicateDetectionCount\":").append(frame.duplicateDetectionCount)
                append(",\"associationState\":\"").append(frame.associationState.name).append('"')
                append(",\"selectedDetectionIndex\":").append(frame.selectedDetectionIndex ?: "null")
                append(",\"identitySwitchSafetyViolation\":").append(frame.identitySwitchSafetyViolation)
                append(",\"candidates\":"); detections(frame.candidates)
                append(",\"acceptedDetections\":"); detections(frame.acceptedDetections)
                append('}')
            }
            append("]}")
        }
        append("]}")
    }

    private fun percentile(sorted: List<Long>, percentile: Double): Long =
        sorted[(ceil(percentile * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]

    private fun transitionCount(frames: List<VisionReplayFrameResult>, state: TargetAssociationState): Int =
        associationTransitions(frames).count { it.frame.associationState == state }

    private fun associationTransitions(frames: List<VisionReplayFrameResult>): List<AssociationTransition> =
        frames.mapIndexedNotNull { index, frame ->
            val previous = frames.getOrNull(index - 1)?.associationState ?: TargetAssociationState.None
            if (frame.associationState == previous) null else AssociationTransition(previous, frame)
        }

    private data class AssociationTransition(
        val from: TargetAssociationState,
        val frame: VisionReplayFrameResult,
    )

    private fun recordedTransitionCount(
        frames: List<VisionRecordedAssociationFrame>,
        state: TargetAssociationState,
    ): Int = recordedAssociationTransitions(frames).count { it.frame.state == state }

    private fun recordedAssociationTransitions(
        frames: List<VisionRecordedAssociationFrame>,
    ): List<RecordedAssociationTransition> = frames.mapIndexedNotNull { index, frame ->
        val previous = frames.getOrNull(index - 1)?.state ?: TargetAssociationState.None
        if (frame.state == previous) null else RecordedAssociationTransition(previous, frame)
    }

    private data class RecordedAssociationTransition(
        val from: TargetAssociationState,
        val frame: VisionRecordedAssociationFrame,
    )

    private fun StringBuilder.detections(values: List<PersonDetection>) {
        append('[')
        values.forEachIndexed { index, detection ->
            if (index > 0) append(',')
            append("{\"box\":[").append(detection.boundingBox.left).append(',')
                .append(detection.boundingBox.top).append(',').append(detection.boundingBox.right)
                .append(',').append(detection.boundingBox.bottom).append(']')
                .append(",\"confidence\":").append(detection.confidence).append('}')
        }
        append(']')
    }

    private fun StringBuilder.stringField(name: String, value: String) {
        append('"').append(name).append("\":\"")
        escaped(value)
        append('"')
    }

    private fun StringBuilder.escaped(value: String) {
        value.forEach { if (it == '"' || it == '\\') append('\\'); append(it) }
    }
}

private object CompactJson {
    fun parseObject(text: String): Map<String, Any?> = Parser(text).parse().asObject("root")

    private class Parser(private val text: String) {
        private var index = 0
        fun parse(): Any? {
            val value = value()
            whitespace()
            if (index != text.length) fail("Trailing JSON")
            return value
        }
        private fun value(): Any? {
            whitespace()
            if (index >= text.length) fail("Unexpected end")
            return when (text[index]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> numberValue()
            }
        }
        private fun objectValue(): Map<String, Any?> {
            index++
            val result = linkedMapOf<String, Any?>()
            whitespace()
            if (take('}')) return result
            while (true) {
                whitespace(); if (index >= text.length || text[index] != '"') fail("Expected object key")
                val key = stringValue(); whitespace(); expect(':')
                if (result.put(key, value()) != null) fail("Duplicate key $key")
                whitespace(); if (take('}')) return result; expect(',')
            }
        }
        private fun arrayValue(): List<Any?> {
            index++
            val result = mutableListOf<Any?>()
            whitespace(); if (take(']')) return result
            while (true) {
                result += value(); whitespace(); if (take(']')) return result; expect(',')
            }
        }
        private fun stringValue(): String {
            expect('"'); val result = StringBuilder()
            while (index < text.length) {
                val char = text[index++]
                if (char == '"') return result.toString()
                if (char != '\\') { result.append(char); continue }
                if (index >= text.length) fail("Invalid escape")
                when (val escaped = text[index++]) {
                    '"', '\\', '/' -> result.append(escaped)
                    'b' -> result.append('\b'); 'f' -> result.append('\u000c'); 'n' -> result.append('\n')
                    'r' -> result.append('\r'); 't' -> result.append('\t')
                    'u' -> {
                        if (index + 4 > text.length) fail("Invalid unicode escape")
                        result.append(text.substring(index, index + 4).toInt(16).toChar()); index += 4
                    }
                    else -> fail("Invalid escape")
                }
            }
            fail("Unterminated string")
        }
        private fun numberValue(): Number {
            val start = index
            while (index < text.length && text[index] in "-+0123456789.eE") index++
            if (start == index) fail("Expected value")
            val raw = text.substring(start, index)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: fail("Invalid number")
        }
        private fun <T> literal(expected: String, value: T): T {
            if (!text.startsWith(expected, index)) fail("Invalid literal")
            index += expected.length; return value
        }
        private fun whitespace() { while (index < text.length && text[index].isWhitespace()) index++ }
        private fun take(char: Char): Boolean = if (index < text.length && text[index] == char) { index++; true } else false
        private fun expect(char: Char) { if (!take(char)) fail("Expected $char") }
        private fun fail(message: String): Nothing = throw MalformedVisionSessionException("$message at $index")
    }
}

private fun Any?.asObject(name: String): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return this as? Map<String, Any?> ?: throw MalformedVisionSessionException("$name must be an object")
}
private fun Any?.asFloat(name: String): Float = (this as? Number)?.toFloat()
    ?: throw MalformedVisionSessionException("$name must be a number")
private fun Map<String, Any?>.required(name: String): Any? =
    if (containsKey(name)) get(name) else throw MalformedVisionSessionException("Missing $name")
private fun Map<String, Any?>.string(name: String) = required(name) as? String
    ?: throw MalformedVisionSessionException("$name must be a string")
private fun Map<String, Any?>.long(name: String) = (required(name) as? Number)?.toLong()
    ?: throw MalformedVisionSessionException("$name must be a number")
private fun Map<String, Any?>.int(name: String) = long(name).toInt()
private fun Map<String, Any?>.float(name: String) = (required(name) as? Number)?.toFloat()
    ?: throw MalformedVisionSessionException("$name must be a number")
private fun Map<String, Any?>.boolean(name: String) = required(name) as? Boolean
    ?: throw MalformedVisionSessionException("$name must be boolean")
private fun Map<String, Any?>.array(name: String) = required(name) as? List<*>
    ?: throw MalformedVisionSessionException("$name must be an array")
private fun Map<String, Any?>.optionalLong(name: String) = get(name)?.let {
    (it as? Number)?.toLong() ?: throw MalformedVisionSessionException("$name must be a number")
}
private fun Map<String, Any?>.optionalString(name: String) = get(name)?.let {
    it as? String ?: throw MalformedVisionSessionException("$name must be a string")
}
// SPDX-License-Identifier: AGPL-3.0-only
