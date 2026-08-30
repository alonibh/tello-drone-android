package com.alonibh.tellodrone.tello

import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.NormalizedBoundingBox
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.TargetSelection
import com.alonibh.tellodrone.domain.TelemetrySnapshot
import com.alonibh.tellodrone.domain.TrackingErrors
import com.alonibh.tellodrone.domain.TrackingMode
import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.VideoState
import com.alonibh.tellodrone.domain.YawFollowDecision
import com.alonibh.tellodrone.domain.YawFollowReason
import com.alonibh.tellodrone.domain.YawFollowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutonomousRcSendAuthorityTest {
    @Test fun `strict send-time predicate requires every physical autonomy invariant`() {
        val ready = readyState()
        val readyErrors = checkNotNull(ready.trackingErrors)
        assertNull(AutonomousRcSendAuthority.validate(ready))

        val unsafe = listOf(
            ready.copy(connection = DroneConnectionState.Error) to RcSendSuppressionReason.CONNECTION_INACTIVE,
            ready.copy(flight = FlightState.Landing) to RcSendSuppressionReason.FLIGHT_STATE_INACTIVE,
            ready.copy(telemetry = ready.telemetry.copy(isFresh = false)) to RcSendSuppressionReason.TELEMETRY_STALE,
            ready.copy(video = ready.video.copy(availability = VideoAvailability.Recovering)) to RcSendSuppressionReason.VIDEO_UNSAFE,
            ready.copy(video = ready.video.copy(personDetectionState = PersonDetectionState.Off)) to RcSendSuppressionReason.DETECTOR_INACTIVE,
            ready.copy(tracking = TrackingMode.Off) to RcSendSuppressionReason.TRACKING_INACTIVE,
            ready.copy(target = null) to RcSendSuppressionReason.TARGET_UNSAFE,
            ready.copy(targetAssociationState = TargetAssociationState.TemporarilyMissing) to RcSendSuppressionReason.TARGET_UNSAFE,
            ready.copy(trackingErrors = null) to RcSendSuppressionReason.TARGET_UNSAFE,
            ready.copy(trackingErrors = readyErrors.copy(targetPresent = false)) to RcSendSuppressionReason.TARGET_UNSAFE,
            ready.copy(trackingErrors = readyErrors.copy(targetFresh = false)) to RcSendSuppressionReason.TARGET_UNSAFE,
            ready.copy(manualVector = ManualControlVector(yaw = .01f)) to RcSendSuppressionReason.MANUAL_OVERRIDE,
            ready.copy(hoverActive = true) to RcSendSuppressionReason.HOVER_ACTIVE,
            ready.copy(yawFollowDecision = YawFollowDecision()) to RcSendSuppressionReason.TRACKING_INACTIVE,
        )
        unsafe.forEach { (state, expected) ->
            assertEquals(expected, AutonomousRcSendAuthority.validate(state))
        }
        assertEquals(
            RcSendSuppressionReason.VIDEO_UNSAFE,
            AutonomousRcSendAuthority.validate(ready, liveVideoAvailability = VideoAvailability.Recovering),
        )
    }

    private fun readyState(): DroneSessionState {
        val detection = PersonDetection(
            NormalizedBoundingBox(.55f, .2f, .8f, .9f),
            .9f,
            frameSequence = 7L,
            sourceTimestampNanos = 1_000_000_000L,
        )
        return DroneSessionState(
            connection = DroneConnectionState.Connected,
            flight = FlightState.Flying,
            telemetry = TelemetrySnapshot(isFresh = true),
            video = VideoState(
                availability = VideoAvailability.Streaming,
                personDetectionState = PersonDetectionState.Detecting,
            ),
            tracking = TrackingMode.TargetLocked,
            target = TargetSelection.select(detection),
            targetAssociationState = TargetAssociationState.Matched,
            trackingErrors = TrackingErrors(
                targetPresent = true,
                targetFresh = true,
                measurementFrameSequence = 7L,
                measurementSourceTimestampNanos = 1_000_000_000L,
            ),
            yawFollowDecision = YawFollowDecision(
                state = YawFollowState.ACTIVE,
                reason = YawFollowReason.ACTIVE,
                yawRc = 8,
            ),
        )
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
