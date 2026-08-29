package com.alonibh.tellodrone.vision

import com.alonibh.tellodrone.domain.NormalizedBoundingBox
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.TargetAssociationDecision
import com.alonibh.tellodrone.domain.TargetAssociationDiagnostics
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.TargetSelection
import com.alonibh.tellodrone.domain.YawControlSuppressionReason
import com.alonibh.tellodrone.domain.YawFollowReason
import com.alonibh.tellodrone.domain.YawFollowState
import com.alonibh.tellodrone.tello.RcInputKind
import com.alonibh.tellodrone.tello.RcSendSuppressionReason
import com.alonibh.tellodrone.tello.RcVector
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
                renderedFrameTimestampNanos = 90L,
                captureRequestTimestampNanos = 91L,
                pixelCopyCompletedTimestampNanos = 92L,
                detectorInferenceStartedTimestampNanos = 93L,
                detectorInferenceCompletedTimestampNanos = 96L,
                associationCompletedTimestampNanos = 97L,
                detectorPreprocessingNanos = 1L,
                detectorModelInferenceNanos = 2L,
                detectorDecodeAndNmsNanos = 3L,
                detectorAppearanceNanos = 4L,
                analysisMeasuredFps = 15f,
                analysisCapturedFrames = 12L,
                analysisDroppedFrames = 2L,
                analysisPendingFrameDepth = 1,
                detectorMeasuredFps = 9f,
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
        assertTrue(line.contains("\"detectorInferenceStartedTimestampNanos\":93"))
        assertTrue(line.contains("\"detectorAppearanceNanos\":4"))
        assertTrue(line.contains("\"analysisPendingFrameDepth\":1"))
        assertFalse(line.contains('\n'))
    }

    @Test fun `control trace includes capture age filtering and actual physical RC vector`() {
        val measurement = VisionTraceJson.encodeControlMeasurement(
            YawControlMeasurementTrace(
                frameSequence = 12L,
                sourceTimestampNanos = 1_000_000_000L,
                commandTimestampNanos = 1_134_000_000L,
                perceptionAgeMillis = 134L,
                targetCenterX = .7f,
                rawYawError = .2f,
                filteredYawError = .16f,
                associationState = TargetAssociationState.Matched,
                previousYawRc = 3,
                requestedYawRc = 8,
                safetyFilteredYawRc = 6,
                suppressionReason = YawControlSuppressionReason.NONE,
                telemetryHeightMeters = 1.2f,
                yawFollowState = YawFollowState.ACTIVE,
                yawFollowReason = YawFollowReason.ACTIVE,
                controllerPhase = com.alonibh.tellodrone.domain.YawControllerPhase.CORRECTING,
                telloYawDegrees = 45,
                telloYawRateDegreesPerSecond = 12.5f,
            ),
        )
        val publication = VisionTraceJson.encodeRcPublication(
            RcPublicationTrace(
                commandTimestampNanos = 1_150_000_000L,
                frameSequence = 12L,
                sourceTimestampNanos = 1_000_000_000L,
                perceptionAgeMillis = 150L,
                targetCenterX = .7f,
                rawYawError = .2f,
                filteredYawError = .16f,
                associationState = TargetAssociationState.Matched,
                previousYawRc = 3,
                requestedYawRc = 8,
                safetyFilteredYawRc = 6,
                yawSuppressionReason = YawControlSuppressionReason.NONE,
                requestedVector = RcVector(yaw = 6),
                actualSentVector = RcVector(yaw = 6),
                inputKind = RcInputKind.AUTONOMOUS_YAW,
                sendSuppressionReason = RcSendSuppressionReason.NONE,
                telemetryHeightMeters = 1.2f,
                yawFollowState = YawFollowState.ACTIVE,
                yawFollowReason = YawFollowReason.ACTIVE,
                controllerPhase = com.alonibh.tellodrone.domain.YawControllerPhase.CORRECTING,
                telloYawDegrees = 45,
                telloYawRateDegreesPerSecond = 12.5f,
                yawDecisionTimestampNanos = 1_134_000_000L,
                desiredPublishedAtNanos = 1_135_000_000L,
                sendStartedAtNanos = 1_150_000_000L,
                actualSentAtNanos = 1_151_000_000L,
            ),
        )

        assertTrue(measurement.contains("\"perceptionAgeMillis\":134"))
        assertTrue(measurement.contains("\"rawYawError\":0.2"))
        assertTrue(measurement.contains("\"safetyFilteredYawRc\":6"))
        assertTrue(measurement.contains("\"controllerPhase\":\"CORRECTING\""))
        assertTrue(measurement.contains("\"telloYawDegrees\":45"))
        assertTrue(measurement.contains("\"telloYawRateDegreesPerSecond\":12.5"))
        assertTrue(publication.contains("\"actualSentVector\":{\"lateral\":0,\"forward\":0,\"vertical\":0,\"yaw\":6}"))
        assertTrue(publication.contains("\"inputKind\":\"AUTONOMOUS_YAW\""))
        assertTrue(publication.contains("\"controllerPhase\":\"CORRECTING\""))
        assertTrue(publication.contains("\"telloYawDegrees\":45"))
        assertTrue(publication.contains("\"telloYawRateDegreesPerSecond\":12.5"))
        assertTrue(publication.contains("\"yawDecisionTimestampNanos\":1134000000"))
        assertTrue(publication.contains("\"actualSentAtNanos\":1151000000"))
        assertFalse(publication.contains('\n'))
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
