package com.alonibh.tellodrone.domain

/** Applies detector-owned observational state without changing connection, flight, or manual input. */
fun DroneSessionState.withPersonDetectionVideoState(nextVideo: VideoState): DroneSessionState {
    val streaming = nextVideo.availability == VideoAvailability.Streaming
    if (streaming && nextVideo.personDetectionState == PersonDetectionState.Starting) {
        return copy(
            video = nextVideo,
            tracking = TrackingMode.DetectOnly,
            authority = ControlAuthority.Manual,
            personDetections = emptyList(),
            target = null,
            trackingErrors = null,
            targetAssociationState = TargetAssociationState.None,
            dryRunControlIntent = null,
            followDistanceReference = null,
            followDistanceCalibrationState = FollowDistanceCalibrationState.NotSet,
        )
    }
    if (streaming && nextVideo.personDetectionState == PersonDetectionState.Detecting) {
        return copy(
            video = nextVideo,
            tracking = if (target == null) TrackingMode.DetectOnly else TrackingMode.TargetLocked,
            authority = ControlAuthority.Manual,
            personDetections = nextVideo.personDetections,
        )
    }
    return copy(
        video = nextVideo,
        tracking = TrackingMode.Off,
        authority = ControlAuthority.Manual,
        personDetections = emptyList(),
        target = null,
        trackingErrors = null,
        targetAssociationState = TargetAssociationState.None,
        dryRunControlIntent = null,
        followDistanceReference = null,
        followDistanceCalibrationState = FollowDistanceCalibrationState.NotSet,
        lastMessage = if (nextVideo.personDetectionState == PersonDetectionState.Error) {
            nextVideo.detectorErrorReason ?: "Person detector failed"
        } else lastMessage,
    )
}
// SPDX-License-Identifier: AGPL-3.0-only
