package com.alonibh.tellodrone.ui

import androidx.compose.ui.graphics.Color
import com.alonibh.tellodrone.TelloGreen
import com.alonibh.tellodrone.TelloRed
import com.alonibh.tellodrone.TelloTextMuted
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.TrackingMode
import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.YawFollowState
import com.alonibh.tellodrone.domain.isZero

internal data class TrackingStatus(val label: String, val value: String, val color: Color)
internal enum class TrackingPrimaryAction { DetectPeople, StartFollow, RearmFollow, StopFollow, None }
internal enum class TrackingHudState(val label: String) {
    Off("OFF"),
    SelectTarget("SELECT TARGET"),
    TargetReady("TARGET READY"),
    Active("ACTIVE"),
    Searching("SEARCHING"),
    Lost("LOST"),
}
internal data class TrackingUiPresentation(
    val detection: TrackingStatus,
    val target: TrackingStatus,
    val yaw: TrackingStatus,
    val instruction: String?,
    val primaryAction: TrackingPrimaryAction,
    val showStopDetection: Boolean,
)

/** Pilot-facing projection of detailed state. Raw state/reason values stay diagnostic-only. */
internal fun DroneSessionState.trackingUiPresentation(): TrackingUiPresentation {
    val detecting = video.personDetectionState in setOf(PersonDetectionState.Starting, PersonDetectionState.Detecting)
    val targetStatus = when (targetAssociationState) {
        TargetAssociationState.Matched, TargetAssociationState.Selected -> TrackingStatus("Target", "Selected", TelloGreen)
        TargetAssociationState.TemporarilyMissing, TargetAssociationState.Ambiguous -> TrackingStatus("Target", "Missing", Color(0xFFFFC857))
        TargetAssociationState.Lost -> TrackingStatus("Target", "Lost", TelloRed)
        TargetAssociationState.None -> TrackingStatus("Target", "None", TelloTextMuted)
    }
    val yawStatus = when (yawFollowDecision.state) {
        YawFollowState.ACTIVE -> TrackingStatus("Yaw", "Following", TelloGreen)
        YawFollowState.ARMED_WAITING -> TrackingStatus("Yaw", "Ready", Color(0xFFFFC857))
        YawFollowState.REQUIRES_REARM -> TrackingStatus("Yaw", "Ready", TelloRed)
        YawFollowState.DISARMED -> TrackingStatus("Yaw", "Off", TelloTextMuted)
    }
    val action = when {
        flight in setOf(FlightState.Landing, FlightState.Emergency) -> TrackingPrimaryAction.None
        !detecting -> TrackingPrimaryAction.DetectPeople
        yawFollowDecision.state in setOf(YawFollowState.ACTIVE, YawFollowState.ARMED_WAITING) -> TrackingPrimaryAction.StopFollow
        yawFollowDecision.requiresExplicitRearm && targetIsFollowReady() -> TrackingPrimaryAction.RearmFollow
        targetIsFollowReady() -> TrackingPrimaryAction.StartFollow
        else -> TrackingPrimaryAction.None
    }
    val instruction = when {
        flight == FlightState.Landing -> "Tracking unavailable while landing"
        flight == FlightState.Emergency -> "Tracking unavailable after Emergency Stop"
        !detecting -> null
        targetAssociationState == TargetAssociationState.Lost -> "Target lost — tap a detected person to reselect"
        targetAssociationState in setOf(TargetAssociationState.TemporarilyMissing, TargetAssociationState.Ambiguous) -> "Target temporarily missing"
        yawFollowDecision.requiresExplicitRearm -> "Yaw follow requires explicit re-arm"
        targetAssociationState in setOf(TargetAssociationState.Selected, TargetAssociationState.Matched) -> null
        target == null -> "Tap a detected person to select"
        else -> null
    }
    return TrackingUiPresentation(
        TrackingStatus("Detection", if (detecting) "On" else "Off", if (detecting) TelloGreen else TelloTextMuted),
        targetStatus, yawStatus, instruction, action, detecting,
    )
}

internal fun DroneSessionState.trackingHudState(): TrackingHudState {
    val detecting = video.personDetectionState in setOf(PersonDetectionState.Starting, PersonDetectionState.Detecting)
    return when {
        flight in setOf(FlightState.Landing, FlightState.Emergency) -> TrackingHudState.Off
        tracking == TrackingMode.Off || !detecting -> TrackingHudState.Off
        yawFollowDecision.state == YawFollowState.ACTIVE -> TrackingHudState.Active
        targetAssociationState == TargetAssociationState.Lost -> TrackingHudState.Lost
        targetAssociationState in setOf(TargetAssociationState.TemporarilyMissing, TargetAssociationState.Ambiguous) -> TrackingHudState.Searching
        targetIsFollowReady() -> TrackingHudState.TargetReady
        else -> TrackingHudState.SelectTarget
    }
}

internal fun DroneSessionState.canStartDetection(): Boolean =
    connection == DroneConnectionState.Connected &&
        flight !in setOf(FlightState.Landing, FlightState.Emergency) &&
        video.availability == VideoAvailability.Streaming &&
        video.analysisLatestSequence != null

internal fun DroneSessionState.canStartFollow(): Boolean =
    connection == DroneConnectionState.Connected &&
        flight == FlightState.Flying &&
        telemetry.isFresh &&
        video.availability == VideoAvailability.Streaming &&
        video.personDetectionState == PersonDetectionState.Detecting &&
        manualVector.isZero() &&
        targetIsFollowReady()

private fun DroneSessionState.targetIsFollowReady(): Boolean =
    target != null && !target.identityUncertain &&
        targetAssociationState in setOf(TargetAssociationState.Selected, TargetAssociationState.Matched)
