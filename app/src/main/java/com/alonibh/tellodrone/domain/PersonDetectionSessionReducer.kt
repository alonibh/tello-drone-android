package com.alonibh.tellodrone.domain

/** Applies detector-owned observational state without changing connection, flight, or manual input. */
fun DroneSessionState.withPersonDetectionVideoState(nextVideo: VideoState): DroneSessionState {
    val active = nextVideo.personDetectionState in setOf(
        PersonDetectionState.Starting,
        PersonDetectionState.Detecting,
    )
    return copy(
        video = nextVideo,
        tracking = if (active) TrackingMode.DetectOnly else TrackingMode.Off,
        authority = ControlAuthority.Manual,
        personDetections = nextVideo.personDetections,
        target = null,
        trackingErrors = null,
        targetAssociationState = TargetAssociationState.None,
        lastMessage = if (nextVideo.personDetectionState == PersonDetectionState.Error) {
            nextVideo.detectorErrorReason ?: "Person detector failed"
        } else lastMessage,
    )
}
