package com.alonibh.tellodrone.vision

import com.alonibh.tellodrone.domain.DetectorBackend
import com.alonibh.tellodrone.domain.DetectorBackendPreference
import com.alonibh.tellodrone.domain.DetectorModel
import com.alonibh.tellodrone.domain.NormalizedBoundingBox
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.TrackedTarget
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionSessionTest {
    @Test fun `manifest round trip preserves frame and drop accounting`() {
        val manifest = manifest(
            listOf(frame(1, 7, 70)),
            dropped = 3,
            excludedAfterLimit = 9,
            startReason = VisionCaptureStartReason.TargetSelected,
        )
        assertEquals(manifest, VisionSessionManifestJson.decode(VisionSessionManifestJson.encode(manifest)))
    }

    @Test fun `legacy manifest imports conservatively as unanchored capture`() {
        val current = VisionSessionManifestJson.encode(manifest(listOf(frame(1, 7, 70))))
        val legacy = current
            .replace("\"schemaVersion\":2", "\"schemaVersion\":1")
            .replace(",\"excludedAfterLimitFrameCount\":0", "")
            .replace(",\"captureStartReason\":\"DetectionStarted\"", "")
        val decoded = VisionSessionManifestJson.decode(legacy)
        assertEquals(1, decoded.schemaVersion)
        assertEquals(VisionCaptureStartReason.Legacy, decoded.captureStartReason)
        assertEquals(0L, decoded.excludedAfterLimitFrameCount)
    }

    @Test fun `capture limiter enforces both frame and duration bounds`() {
        val limiter = VisionCaptureLimiter(maxFrames = 2, maxDurationNanos = 100)
        assertEquals(VisionCaptureReservation.Accepted, limiter.reserve(1_000))
        assertEquals(VisionCaptureReservation.Accepted, limiter.reserve(1_100))
        assertEquals(VisionCaptureReservation.FrameLimitReached, limiter.reserve(1_101))
        assertEquals(2, limiter.acceptedCount())
        limiter.reset()
        assertEquals(VisionCaptureReservation.Accepted, limiter.reserve(5_000))
        assertEquals(VisionCaptureReservation.InvalidTimestamp, limiter.reserve(4_999))

        val durationLimited = VisionCaptureLimiter(maxFrames = 3, maxDurationNanos = 100)
        assertEquals(VisionCaptureReservation.Accepted, durationLimited.reserve(1_000))
        assertEquals(VisionCaptureReservation.DurationLimitReached, durationLimited.reserve(1_101))
    }

    @Test fun `drop counter restores drops when bounded queue rejects a pair`() {
        val drops = VisionCaptureDropCounter()
        drops.recordDrop(2)
        val attachedToRejectedPair = drops.consumeSinceLastPair()
        assertEquals(2, attachedToRejectedPair)
        drops.restoreSinceLastPair(attachedToRejectedPair)
        drops.recordDrop()
        assertEquals(3, drops.total())
        assertEquals(3, drops.consumeSinceLastPair())
    }

    @Test fun `archive validates correspondence and replays timestamp then sequence order`() {
        val later = frame(1, 20, 2_000)
        val earlier = frame(2, 10, 1_000)
        val archive = sessionZip(manifest(listOf(later, earlier)), listOf(trace(later), trace(earlier)))
        try {
            val opened = VisionSessionArchive.open(archive)
            assertEquals(listOf(10L, 20L), opened.orderedFrames.map { it.frameSequence })
            assertEquals(2, opened.traceSeeds.size)
        } finally {
            archive.delete()
        }
    }

    @Test fun `archive rejects trace frame mismatch and missing frame`() {
        val expected = frame(1, 1, 10)
        val wrong = frame(1, 2, 20)
        val mismatch = sessionZip(manifest(listOf(expected)), listOf(trace(wrong)))
        try {
            assertThrows(MalformedVisionSessionException::class.java) { VisionSessionArchive.open(mismatch) }
        } finally { mismatch.delete() }

        val missing = sessionZip(manifest(listOf(expected)), listOf(trace(expected)), includeFrames = false)
        try {
            assertThrows(MalformedVisionSessionException::class.java) { VisionSessionArchive.open(missing) }
        } finally { missing.delete() }
    }

    @Test fun `comparison report contains timing detections transitions duplicates and safety`() {
        val frames = listOf(
            replayFrame(1, 10, TargetAssociationState.Matched, duplicates = 0),
            replayFrame(2, 20, TargetAssociationState.TemporarilyMissing, duplicates = 2),
            replayFrame(3, 30, TargetAssociationState.TemporarilyMissing, duplicates = 0, violation = true),
        )
        val model = VisionReplayModelResult(
            model = "test", assetFile = "test.tflite", quantization = "INT8", backend = "Cpu",
            confidenceThreshold = .55f, startupNanos = 99, frames = frames,
        )
        val timing = VisionComparisonReportJson.timing(frames)
        assertEquals(10, timing.minNanos)
        assertEquals(20, timing.p50Nanos)
        assertEquals(30, timing.p95Nanos)
        val json = VisionComparisonReportJson.encode(VisionComparisonReport(
            sessionFrameCount = 3,
            sessionDroppedFrameCount = 4,
            sessionExcludedAfterLimitFrameCount = 7,
            captureStartReason = VisionCaptureStartReason.TargetSelected,
            associationEvaluationValid = false,
            associationEvaluationWarning = "capture is incomplete",
            recordedLiveAssociationFrames = listOf(
                VisionRecordedAssociationFrame(1, 100, TargetAssociationState.Matched),
                VisionRecordedAssociationFrame(2, 200, TargetAssociationState.TemporarilyMissing),
                VisionRecordedAssociationFrame(3, 300, TargetAssociationState.Lost),
            ),
            models = listOf(model),
        ))
        assertTrue(json.contains("\"missingTransitions\":1"))
        assertTrue(json.contains("\"recordedLiveMissingTransitions\":1"))
        assertTrue(json.contains("\"recordedLiveLostTransitions\":1"))
        assertTrue(json.contains("\"associationEvaluationValid\":false"))
        assertTrue(json.contains("\"sessionExcludedAfterLimitFrameCount\":7"))
        assertTrue(json.contains("\"duplicateDetections\":2"))
        assertTrue(json.contains("\"identitySwitchSafetyViolation\":true"))
        assertTrue(json.contains("\"acceptedDetectionCount\":1"))
        assertTrue(json.contains("\"candidates\""))
    }

    @Test fun `deterministic replay never reacquires after Lost without explicit reselection`() {
        val replay = VisionReplayAssociation()
        val firstSelection = trackedTarget(selectedFrame = 1, selectedTimestamp = 1)
        assertEquals(TargetAssociationState.Selected, replay.evaluate(1, 1, emptyList(), firstSelection).state)
        assertEquals(
            TargetAssociationState.Lost,
            replay.evaluate(2, 1_000_000_002, emptyList(), firstSelection).state,
        )
        val temptingDetection = PersonDetection(firstSelection.boundingBox, .99f, 3, 1_100_000_000)
        val noReacquire = replay.evaluate(3, 1_100_000_000, listOf(temptingDetection), firstSelection)
        assertEquals(TargetAssociationState.Lost, noReacquire.state)
        assertFalse(noReacquire.identitySwitchSafetyViolation)

        val explicitReselection = trackedTarget(selectedFrame = 3, selectedTimestamp = 1_100_000_000)
        assertEquals(
            TargetAssociationState.Selected,
            replay.evaluate(3, 1_100_000_000, listOf(temptingDetection), explicitReselection).state,
        )
    }

    @Test fun `selection before first stored frame is applied before that frame association`() {
        val replay = VisionReplayAssociation()
        val selected = trackedTarget(selectedFrame = 1, selectedTimestamp = 1_000_000_000)
        val firstStored = replay.evaluate(
            frameSequence = 2,
            sourceTimestampNanos = 1_100_000_000,
            detections = emptyList(),
            recordedSelection = selected,
        )
        assertEquals(TargetAssociationState.TemporarilyMissing, firstStored.state)
    }

    @Test fun `association evaluation requires target anchored complete capture`() {
        val selected = trackedTarget(selectedFrame = 1, selectedTimestamp = 10)
        val captured = frame(1, 2, 20)
        val completeArchive = sessionZip(
            manifest(
                listOf(captured),
                startReason = VisionCaptureStartReason.TargetSelected,
            ),
            listOf(trace(captured, selected, TargetAssociationState.Matched)),
        )
        val droppedArchive = sessionZip(
            manifest(
                listOf(captured),
                dropped = 1,
                startReason = VisionCaptureStartReason.TargetSelected,
            ),
            listOf(trace(captured, selected, TargetAssociationState.Matched)),
        )
        try {
            assertTrue(VisionSessionArchive.open(completeArchive).associationEvaluation().valid)
            val incomplete = VisionSessionArchive.open(droppedArchive).associationEvaluation()
            assertFalse(incomplete.valid)
            assertTrue(incomplete.warning!!.contains("1 analyzed frame"))
        } finally {
            completeArchive.delete()
            droppedArchive.delete()
        }
    }

    @Test fun `production detector configuration remains Lite0 CPU point fifty five`() {
        assertEquals(DetectorModel.EfficientDetLite0, DetectorModel.Default)
        assertEquals(DetectorModel.EfficientDetLite0, ProductionPersonDetectorConfiguration.model)
        assertEquals(DetectorBackendPreference.Cpu, ProductionPersonDetectorConfiguration.backendPreference)
        assertEquals(.55f, ProductionPersonDetectorConfiguration.confidenceThreshold)
        assertEquals(
            listOf(DetectorModel.EfficientDetLite0, DetectorModel.EfficientDetLite2Int8),
            DEBUG_REPLAY_MODELS.map { it.model },
        )
        assertTrue(DEBUG_REPLAY_MODELS.all { it.quantization.startsWith("INT8") })
    }

    private fun manifest(
        frames: List<VisionSessionFrameEntry>,
        dropped: Long = 0,
        excludedAfterLimit: Long = 0,
        startReason: VisionCaptureStartReason = VisionCaptureStartReason.DetectionStarted,
    ) = VisionSessionManifest(
        capturedFrameCount = frames.size,
        droppedFrameCount = dropped,
        excludedAfterLimitFrameCount = excludedAfterLimit,
        captureStartReason = startReason,
        frames = frames,
    )

    private fun frame(index: Int, sequence: Long, timestamp: Long) = VisionSessionFrameEntry(
        captureIndex = index,
        frameSequence = sequence,
        sourceTimestampNanos = timestamp,
        file = "frames/${index.toString().padStart(6, '0')}.jpg",
        width = 640,
        height = 360,
    )

    private fun trace(
        frame: VisionSessionFrameEntry,
        selectedTarget: TrackedTarget? = null,
        state: TargetAssociationState = TargetAssociationState.None,
    ): String = VisionTraceJson.encode(
        VisionTraceFrame(
            frameSequence = frame.frameSequence,
            sourceTimestampNanos = frame.sourceTimestampNanos,
            detectorModel = "capture",
            detectorBackend = DetectorBackend.Cpu.name,
            confidenceThreshold = .55f,
            inferenceMillis = 10,
            candidates = emptyList(),
            detections = emptyList(),
            selectedTargetBefore = selectedTarget,
            selectedTargetAfter = selectedTarget,
            associationState = state,
            associationDiagnostics = null,
        ),
        droppedBeforeFrame = 0,
        capturedFrameFile = frame.file,
    )

    private fun sessionZip(
        manifest: VisionSessionManifest,
        traces: List<String>,
        includeFrames: Boolean = true,
    ): File = File.createTempFile("vision-session", ".zip").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(VisionSessionManifestJson.encode(manifest).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("trace.jsonl"))
            zip.write(traces.joinToString("\n", postfix = "\n").toByteArray())
            zip.closeEntry()
            if (includeFrames) manifest.frames.forEach { frame ->
                zip.putNextEntry(ZipEntry(frame.file))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
    }

    private fun replayFrame(
        sequence: Long,
        inference: Long,
        state: TargetAssociationState,
        duplicates: Int,
        violation: Boolean = false,
    ): VisionReplayFrameResult {
        val detection = PersonDetection(NormalizedBoundingBox(.1f, .2f, .4f, .9f), .8f, sequence, sequence * 100)
        return VisionReplayFrameResult(
            frameFile = "frames/${sequence.toString().padStart(6, '0')}.jpg",
            frameSequence = sequence,
            sourceTimestampNanos = sequence * 100,
            inferenceNanos = inference,
            candidates = listOf(detection),
            acceptedDetections = listOf(detection),
            duplicateDetectionCount = duplicates,
            associationState = state,
            selectedDetectionIndex = 0,
            identitySwitchSafetyViolation = violation,
        )
    }

    private fun trackedTarget(selectedFrame: Long, selectedTimestamp: Long) = TrackedTarget(
        boundingBox = NormalizedBoundingBox(.1f, .2f, .4f, .9f),
        confidence = .9f,
        selectedFrameSequence = selectedFrame,
        selectedSourceTimestampNanos = selectedTimestamp,
    )
}
