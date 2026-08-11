package com.alonibh.tellodrone.domain

/** Pure bounded PID. It never reads a clock and returns null for unsafe input. */
data class PidConfig(
    val kP: Float,
    val kI: Float,
    val kD: Float,
    val outputMin: Float,
    val outputMax: Float,
    val integralMin: Float,
    val integralMax: Float,
)

class PidController(private val config: PidConfig) {
    private var integral = 0f
    private var previousError: Float? = null

    fun compute(error: Float, dtSeconds: Float): Float? {
        if (!error.isFinite() || !dtSeconds.isFinite() || dtSeconds <= 0f) return null
        integral = (integral + error * dtSeconds).coerceIn(config.integralMin, config.integralMax)
        val derivative = previousError?.let { (error - it) / dtSeconds } ?: 0f
        previousError = error
        return (config.kP * error + config.kI * integral + config.kD * derivative)
            .coerceIn(config.outputMin, config.outputMax)
    }

    fun reset() { integral = 0f; previousError = null }
}

enum class DryRunControlReason { TARGET_MATCHED, TARGET_SELECTED, NO_TARGET, TARGET_MISSING, AMBIGUOUS, LOST, STALE, INVALID_TIMING, INVALID_ERRORS }

/** Diagnostic only: this type is intentionally separate from every flight-command transport type. */
data class DryRunControlIntent(
    val yaw: Float = 0f,
    val vertical: Float = 0f,
    val forwardBack: Float = 0f,
    val lateral: Float = 0f,
    val actionable: Boolean = false,
    val reason: DryRunControlReason,
)

data class FollowPlannerConfig(val yaw: PidConfig, val vertical: PidConfig, val forwardBack: PidConfig) {
    companion object {
        /** Test/legacy simulation values only; not production flight tuning. */
        val LEGACY_SIMULATION = FollowPlannerConfig(
            yaw = PidConfig(1f, .1f, 0f, -.5f, .5f, -.25f, .25f),
            vertical = PidConfig(1f, .1f, 0f, -.5f, .5f, -.25f, .25f),
            forwardBack = PidConfig(1f, .1f, 0f, -.5f, .5f, -.25f, .25f),
        )
    }
}

/** Hardware-independent dry-run Follow planning. No caller can obtain an RC command from it. */
class DryRunFollowPlanner(config: FollowPlannerConfig) {
    private val yaw = PidController(config.yaw)
    private val vertical = PidController(config.vertical)
    private val forwardBack = PidController(config.forwardBack)

    fun plan(errors: TrackingErrors?, association: TargetAssociationState, dtSeconds: Float): DryRunControlIntent {
        if (association == TargetAssociationState.Selected || association == TargetAssociationState.Lost) reset()
        gateReason(errors, association, dtSeconds)?.let { return zero(it) }
        val outputs = listOf(
            yaw.compute(errors!!.yawError, dtSeconds),
            vertical.compute(errors.verticalError, dtSeconds),
            forwardBack.compute(errors.forwardBackError, dtSeconds),
        )
        if (outputs.any { it == null }) return zero(DryRunControlReason.INVALID_TIMING)
        return DryRunControlIntent(
            outputs[0]!!,
            outputs[1]!!,
            outputs[2]!!,
            actionable = true,
            reason = if (association == TargetAssociationState.Selected) DryRunControlReason.TARGET_SELECTED else DryRunControlReason.TARGET_MATCHED,
        )
    }

    fun reset() { yaw.reset(); vertical.reset(); forwardBack.reset() }

    private fun gateReason(errors: TrackingErrors?, state: TargetAssociationState, dt: Float): DryRunControlReason? {
        if (!dt.isFinite() || dt <= 0f) return DryRunControlReason.INVALID_TIMING
        when (state) {
            TargetAssociationState.None -> return DryRunControlReason.NO_TARGET
            TargetAssociationState.TemporarilyMissing -> return DryRunControlReason.TARGET_MISSING
            TargetAssociationState.Ambiguous -> return DryRunControlReason.AMBIGUOUS
            TargetAssociationState.Lost -> return DryRunControlReason.LOST
            else -> Unit
        }
        if (errors == null) return DryRunControlReason.NO_TARGET
        if (!errors.targetPresent || !errors.targetFresh) return DryRunControlReason.STALE
        if (!errors.yawError.isFinite() || !errors.verticalError.isFinite() || !errors.forwardBackError.isFinite()) return DryRunControlReason.INVALID_ERRORS
        return null
    }

    private fun zero(reason: DryRunControlReason) = DryRunControlIntent(reason = reason)
}
