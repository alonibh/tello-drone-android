package com.alonibh.tellodrone.tello

import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.TrackingMode
import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.YawFollowState
import com.alonibh.tellodrone.domain.isZero

/** Strict physical-send authorization. ControlAuthority is presentation state, not a safety input. */
internal object AutonomousRcSendAuthority {
    fun validate(
        state: DroneSessionState,
        liveVideoAvailability: VideoAvailability = state.video.availability,
        isAnomalyLatched: Boolean = false,
    ): RcSendSuppressionReason? = when {
        isAnomalyLatched -> RcSendSuppressionReason.YAW_RESPONSE_ANOMALY
        state.connection != DroneConnectionState.Connected -> RcSendSuppressionReason.CONNECTION_INACTIVE
        state.flight != FlightState.Flying -> RcSendSuppressionReason.FLIGHT_STATE_INACTIVE
        !state.telemetry.isFresh -> RcSendSuppressionReason.TELEMETRY_STALE
        state.video.availability != VideoAvailability.Streaming ||
            liveVideoAvailability != VideoAvailability.Streaming -> RcSendSuppressionReason.VIDEO_UNSAFE
        state.video.personDetectionState != PersonDetectionState.Detecting ->
            RcSendSuppressionReason.DETECTOR_INACTIVE
        state.tracking == TrackingMode.Off -> RcSendSuppressionReason.TRACKING_INACTIVE
        state.target == null -> RcSendSuppressionReason.TARGET_UNSAFE
        state.targetAssociationState != TargetAssociationState.Matched -> RcSendSuppressionReason.TARGET_UNSAFE
        state.trackingErrors == null ||
            !state.trackingErrors.targetPresent ||
            !state.trackingErrors.targetFresh -> RcSendSuppressionReason.TARGET_UNSAFE
        !state.manualVector.isZero() -> RcSendSuppressionReason.MANUAL_OVERRIDE
        state.hoverActive -> RcSendSuppressionReason.HOVER_ACTIVE
        state.yawFollowDecision.state != YawFollowState.ACTIVE -> RcSendSuppressionReason.TRACKING_INACTIVE
        else -> null
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
