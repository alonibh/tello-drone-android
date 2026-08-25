package com.alonibh.tellodrone.vision

import com.alonibh.tellodrone.domain.NormalizedBoundingBox
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.TargetAssociationDecision
import com.alonibh.tellodrone.domain.TargetAssociationDiagnostics
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.TargetSelection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionTraceJsonTest {
    @Test fun `compact frame line includes detector target association diagnostics and timing`() {
        val detection = PersonDetection(NormalizedBoundingBox(.1f, .2f, .4f, .8f), .62f, 9L, 99L)
        val target = TargetSelection.select(detection)
        val line = VisionTraceJson.encode(
            VisionTraceFrame(
                frameSequence = 9L,
                sourceTimestampNanos = 99L,
                detectorModel = "EfficientDet-Lite0",
                detectorBackend = "Cpu",
                confidenceThreshold = .55f,
                inferenceMillis = 123L,
                candidates = listOf(detection.copy(confidence = .52f), detection),
                detections = listOf(detection),
                selectedTargetBefore = target,
                selectedTargetAfter = target,
                associationState = TargetAssociationState.Matched,
                associationDiagnostics = TargetAssociationDiagnostics(
                    decision = TargetAssociationDecision.Matched,
                    targetAgeNanos = 10L,
                    selectedDetectionIndex = 0,
                    eligibleCandidateCount = 1,
                ),
            ),
            droppedBeforeFrame = 2L,
        )

        assertTrue(line.contains("\"frameSequence\":9"))
        assertTrue(line.contains("\"inferenceMillis\":123"))
        assertTrue(line.contains("\"candidates\":["))
        assertTrue(line.contains("\"acceptedDetections\":["))
        assertTrue(line.contains("\"selectedTargetBefore\":{"))
        assertTrue(line.contains("\"decision\":\"Matched\""))
        assertTrue(line.contains("\"droppedBeforeFrame\":2"))
        assertFalse(line.contains('\n'))
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
