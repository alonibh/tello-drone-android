package com.alonibh.tellodrone.domain

enum class ShadowAutonomyState { Disarmed, ArmedWaiting, Eligible, RequiresRearm }
enum class ShadowAutonomyReason { NOT_ARMED, ELIGIBLE, CONNECTION_LOST, NOT_FLYING, TELEMETRY_STALE, VIDEO_UNAVAILABLE, DETECTOR_UNAVAILABLE, NO_TARGET, TARGET_MISSING, TARGET_AMBIGUOUS, TARGET_LOST, TRACKING_STALE, PLANNER_NOT_ACTIONABLE, MANUAL_OVERRIDE, HOVER_INTERVENTION, LANDING, EMERGENCY }
data class ShadowAutonomyInput(
    val connection: DroneConnectionState,
    val flight: FlightState,
    val telemetryFresh: Boolean,
    val video: VideoAvailability,
    val detector: PersonDetectionState,
    val targetPresent: Boolean,
    val association: TargetAssociationState,
    val errors: TrackingErrors?,
    val intent: DryRunControlIntent?,
    val manualInputNeutral: Boolean,
    val hoverActive: Boolean,
    val armRequested: Boolean = false,
    val disarmRequested: Boolean = false,
)
data class ShadowAutonomyDecision(val state: ShadowAutonomyState, val eligible: Boolean, val reason: ShadowAutonomyReason, val requiresExplicitRearm: Boolean)

/** Pure shadow-only gate. It has no authority, transport, Android, or command dependency. */
class ShadowAutonomyGate {
    private var state = ShadowAutonomyState.Disarmed
    fun evaluate(input: ShadowAutonomyInput): ShadowAutonomyDecision {
        if (input.disarmRequested) state = ShadowAutonomyState.Disarmed
        if (state == ShadowAutonomyState.RequiresRearm && input.armRequested) state = ShadowAutonomyState.ArmedWaiting
        if (state == ShadowAutonomyState.Disarmed && input.armRequested) state = ShadowAutonomyState.ArmedWaiting
        val reason = reason(input)
        val latch = reason in setOf(ShadowAutonomyReason.MANUAL_OVERRIDE, ShadowAutonomyReason.HOVER_INTERVENTION, ShadowAutonomyReason.TARGET_AMBIGUOUS, ShadowAutonomyReason.TARGET_LOST, ShadowAutonomyReason.TELEMETRY_STALE, ShadowAutonomyReason.VIDEO_UNAVAILABLE, ShadowAutonomyReason.DETECTOR_UNAVAILABLE, ShadowAutonomyReason.CONNECTION_LOST, ShadowAutonomyReason.EMERGENCY, ShadowAutonomyReason.LANDING)
        if (latch) state = ShadowAutonomyState.RequiresRearm
        else if (state == ShadowAutonomyState.ArmedWaiting && reason == ShadowAutonomyReason.ELIGIBLE) state = ShadowAutonomyState.Eligible
        else if (state == ShadowAutonomyState.Eligible && reason != ShadowAutonomyReason.ELIGIBLE) state = ShadowAutonomyState.ArmedWaiting
        return ShadowAutonomyDecision(state, state == ShadowAutonomyState.Eligible, if (state == ShadowAutonomyState.Disarmed) ShadowAutonomyReason.NOT_ARMED else reason, state == ShadowAutonomyState.RequiresRearm)
    }
    private fun reason(i: ShadowAutonomyInput): ShadowAutonomyReason = when {
        i.flight == FlightState.Emergency -> ShadowAutonomyReason.EMERGENCY
        i.flight in setOf(FlightState.Landing, FlightState.Grounded, FlightState.TakingOff, FlightState.Unknown, FlightState.Error) -> ShadowAutonomyReason.LANDING
        i.connection != DroneConnectionState.Connected -> ShadowAutonomyReason.CONNECTION_LOST
        !i.telemetryFresh -> ShadowAutonomyReason.TELEMETRY_STALE
        i.video != VideoAvailability.Streaming -> ShadowAutonomyReason.VIDEO_UNAVAILABLE
        i.detector != PersonDetectionState.Detecting -> ShadowAutonomyReason.DETECTOR_UNAVAILABLE
        !i.targetPresent -> ShadowAutonomyReason.NO_TARGET
        i.association == TargetAssociationState.TemporarilyMissing -> ShadowAutonomyReason.TARGET_MISSING
        i.association == TargetAssociationState.Ambiguous -> ShadowAutonomyReason.TARGET_AMBIGUOUS
        i.association == TargetAssociationState.Lost -> ShadowAutonomyReason.TARGET_LOST
        i.errors?.targetPresent != true || i.errors.targetFresh != true -> ShadowAutonomyReason.TRACKING_STALE
        i.intent?.actionable != true -> ShadowAutonomyReason.PLANNER_NOT_ACTIONABLE
        !i.manualInputNeutral -> ShadowAutonomyReason.MANUAL_OVERRIDE
        i.hoverActive -> ShadowAutonomyReason.HOVER_INTERVENTION
        else -> ShadowAutonomyReason.ELIGIBLE
    }
}

class ShadowAutonomyReplay(private val gate: ShadowAutonomyGate = ShadowAutonomyGate()) {
    fun replay(inputs: List<ShadowAutonomyInput>): List<ShadowAutonomyDecision> = inputs.map(gate::evaluate)
}
// SPDX-License-Identifier: AGPL-3.0-only
