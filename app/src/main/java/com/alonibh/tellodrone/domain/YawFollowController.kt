package com.alonibh.tellodrone.domain

import kotlin.math.abs
import kotlin.math.roundToInt

enum class YawFollowState { DISARMED, ARMED_WAITING, ACTIVE, REQUIRES_REARM }

enum class YawFollowReason {
    EXPLICITLY_DISARMED,
    ACTIVE,
    CONNECTION_LOST,
    NOT_FLYING,
    TELEMETRY_STALE,
    VIDEO_UNAVAILABLE,
    DETECTOR_UNAVAILABLE,
    NO_TARGET,
    TARGET_NOT_MATCHED,
    TARGET_TEMPORARILY_MISSING,
    TARGET_AMBIGUOUS,
    TARGET_LOST,
    TRACKING_STALE,
    MANUAL_OVERRIDE,
    HOVER_INTERVENTION,
    LANDING,
    EMERGENCY,
}

data class YawFollowInput(
    val connection: DroneConnectionState,
    val flight: FlightState,
    val telemetryFresh: Boolean,
    val video: VideoAvailability,
    val detector: PersonDetectionState,
    val targetPresent: Boolean,
    val association: TargetAssociationState,
    val errors: TrackingErrors?,
    val manualInputNeutral: Boolean,
    val hoverActive: Boolean,
)

data class YawFollowDecision(
    val state: YawFollowState = YawFollowState.DISARMED,
    val reason: YawFollowReason = YawFollowReason.EXPLICITLY_DISARMED,
    val yawRc: Int = 0,
) {
    val requiresExplicitRearm: Boolean get() = state == YawFollowState.REQUIRES_REARM
}

/** Integer RC command shape deliberately makes every non-yaw axis structurally zero. */
data class YawOnlyRcCommand(
    val lateral: Int = 0,
    val forward: Int = 0,
    val vertical: Int = 0,
    val yaw: Int = 0,
)

/** Conservative production controller. It is independent of dry-run and distance planning. */
class ProductionYawController(
    private val proportionalGain: Float = DEFAULT_PROPORTIONAL_GAIN,
    private val horizontalDeadband: Float = DEFAULT_HORIZONTAL_DEADBAND,
    private val absoluteYawRcCap: Int = ABSOLUTE_YAW_RC_CAP,
) {
    init {
        require(proportionalGain.isFinite() && proportionalGain > 0f)
        require(horizontalDeadband.isFinite() && horizontalDeadband in 0f..0.5f)
        require(absoluteYawRcCap in 1..ABSOLUTE_YAW_RC_CAP)
    }

    fun command(errors: TrackingErrors?): YawOnlyRcCommand {
        val error = errors?.yawError
        if (errors?.targetPresent != true || !errors.targetFresh || error == null || !error.isFinite()) {
            return YawOnlyRcCommand()
        }
        if (abs(error) <= horizontalDeadband) return YawOnlyRcCommand()
        val yaw = (error * proportionalGain)
            .roundToInt()
            .coerceIn(-absoluteYawRcCap, absoluteYawRcCap)
        return YawOnlyRcCommand(yaw = yaw)
    }

    companion object {
        const val DEFAULT_HORIZONTAL_DEADBAND = 0.05f
        const val DEFAULT_PROPORTIONAL_GAIN = 24f
        const val ABSOLUTE_YAW_RC_CAP = 12
    }
}

/**
 * Explicitly armed, latched production gate. Target-missing is the sole resumable interruption;
 * every safety/manual intervention requires another explicit arm.
 */
class YawFollowGate(
    private val controller: ProductionYawController = ProductionYawController(),
) {
    private var state = YawFollowState.DISARMED
    private var reason = YawFollowReason.EXPLICITLY_DISARMED

    fun arm(input: YawFollowInput): YawFollowDecision {
        state = YawFollowState.ARMED_WAITING
        return evaluateArmed(input)
    }

    fun disarm(): YawFollowDecision {
        state = YawFollowState.DISARMED
        reason = YawFollowReason.EXPLICITLY_DISARMED
        return decision()
    }

    fun preempt(preemption: YawFollowReason): YawFollowDecision {
        require(preemption in LATCHING_REASONS)
        if (state != YawFollowState.DISARMED) {
            state = YawFollowState.REQUIRES_REARM
            reason = preemption
        }
        return decision()
    }

    fun evaluate(input: YawFollowInput): YawFollowDecision {
        if (state == YawFollowState.DISARMED || state == YawFollowState.REQUIRES_REARM) {
            return decision()
        }
        return evaluateArmed(input)
    }

    private fun evaluateArmed(input: YawFollowInput): YawFollowDecision {
        reason = blockingReason(input)
        state = when {
            reason in LATCHING_REASONS -> YawFollowState.REQUIRES_REARM
            reason == YawFollowReason.ACTIVE -> YawFollowState.ACTIVE
            else -> YawFollowState.ARMED_WAITING
        }
        return decision(input.errors)
    }

    private fun decision(errors: TrackingErrors? = null): YawFollowDecision {
        val yaw = if (state == YawFollowState.ACTIVE) controller.command(errors).yaw else 0
        return YawFollowDecision(state = state, reason = reason, yawRc = yaw)
    }

    private fun blockingReason(input: YawFollowInput): YawFollowReason = when {
        input.flight == FlightState.Emergency -> YawFollowReason.EMERGENCY
        input.flight == FlightState.Landing -> YawFollowReason.LANDING
        input.connection != DroneConnectionState.Connected -> YawFollowReason.CONNECTION_LOST
        !input.telemetryFresh -> YawFollowReason.TELEMETRY_STALE
        input.video != VideoAvailability.Streaming -> YawFollowReason.VIDEO_UNAVAILABLE
        input.detector != PersonDetectionState.Detecting -> YawFollowReason.DETECTOR_UNAVAILABLE
        !input.manualInputNeutral -> YawFollowReason.MANUAL_OVERRIDE
        input.hoverActive -> YawFollowReason.HOVER_INTERVENTION
        input.flight != FlightState.Flying -> YawFollowReason.NOT_FLYING
        input.association == TargetAssociationState.Ambiguous -> YawFollowReason.TARGET_AMBIGUOUS
        input.association == TargetAssociationState.Lost -> YawFollowReason.TARGET_LOST
        !input.targetPresent -> YawFollowReason.NO_TARGET
        input.association == TargetAssociationState.TemporarilyMissing ->
            YawFollowReason.TARGET_TEMPORARILY_MISSING
        input.association != TargetAssociationState.Matched -> YawFollowReason.TARGET_NOT_MATCHED
        input.errors?.targetPresent != true || !input.errors.targetFresh -> YawFollowReason.TRACKING_STALE
        else -> YawFollowReason.ACTIVE
    }

    companion object {
        private val LATCHING_REASONS = setOf(
            YawFollowReason.CONNECTION_LOST,
            YawFollowReason.TELEMETRY_STALE,
            YawFollowReason.VIDEO_UNAVAILABLE,
            YawFollowReason.DETECTOR_UNAVAILABLE,
            YawFollowReason.TARGET_AMBIGUOUS,
            YawFollowReason.TARGET_LOST,
            YawFollowReason.MANUAL_OVERRIDE,
            YawFollowReason.HOVER_INTERVENTION,
            YawFollowReason.LANDING,
            YawFollowReason.EMERGENCY,
        )
    }
}
