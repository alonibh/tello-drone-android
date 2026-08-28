package com.alonibh.tellodrone.ui

import androidx.compose.ui.graphics.Color
import com.alonibh.tellodrone.TelloGreen
import com.alonibh.tellodrone.TelloRed
import com.alonibh.tellodrone.TelloTextMuted
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.YawFollowState

internal data class TrackingStatus(val label: String, val value: String, val color: Color)
internal enum class TrackingPrimaryAction { StartDetection, ArmYawFollow, RearmYawFollow, DisarmYawFollow, None }
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
        TargetAssociationState.Matched, TargetAssociationState.Selected -> TrackingStatus("Target", "Tracking", TelloGreen)
        TargetAssociationState.TemporarilyMissing, TargetAssociationState.Ambiguous -> TrackingStatus("Target", "Missing", Color(0xFFFFC857))
        TargetAssociationState.Lost -> TrackingStatus("Target", "Lost", TelloRed)
        TargetAssociationState.None -> TrackingStatus("Target", "Select person", TelloTextMuted)
    }
    val yawStatus = when (yawFollowDecision.state) {
        YawFollowState.ACTIVE -> TrackingStatus("Yaw", "Following", TelloGreen)
        YawFollowState.ARMED_WAITING -> TrackingStatus("Yaw", "Ready", Color(0xFFFFC857))
        YawFollowState.REQUIRES_REARM -> TrackingStatus("Yaw", "Ready", TelloRed)
        YawFollowState.DISARMED -> TrackingStatus("Yaw", "Off", TelloTextMuted)
    }
    val action = when {
        !detecting -> TrackingPrimaryAction.StartDetection
        yawFollowDecision.state in setOf(YawFollowState.ACTIVE, YawFollowState.ARMED_WAITING) -> TrackingPrimaryAction.DisarmYawFollow
        yawFollowDecision.requiresExplicitRearm -> TrackingPrimaryAction.RearmYawFollow
        target != null && targetAssociationState in setOf(TargetAssociationState.Selected, TargetAssociationState.Matched) -> TrackingPrimaryAction.ArmYawFollow
        else -> TrackingPrimaryAction.None
    }
    val instruction = when {
        !detecting -> "Start detection to select a person"
        targetAssociationState == TargetAssociationState.Lost -> "Target lost — tap the person again to reselect"
        targetAssociationState in setOf(TargetAssociationState.TemporarilyMissing, TargetAssociationState.Ambiguous) -> "Target temporarily missing"
        yawFollowDecision.requiresExplicitRearm -> "Yaw follow requires explicit re-arm"
        targetAssociationState in setOf(TargetAssociationState.Selected, TargetAssociationState.Matched) -> null
        target == null -> "Tap a person in the video"
        else -> null
    }
    return TrackingUiPresentation(
        TrackingStatus("Detection", if (detecting) "On" else "Off", if (detecting) TelloGreen else TelloTextMuted),
        targetStatus, yawStatus, instruction, action, detecting,
    )
}
