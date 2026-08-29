package com.alonibh.tellodrone.tello

import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.ProductionYawController
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.YawControlOutcome
import com.alonibh.tellodrone.domain.YawFollowReason
import com.alonibh.tellodrone.domain.YawFollowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

enum class RcInputKind { MANUAL, AUTONOMOUS_YAW, SAFETY_ZERO }

enum class RcSendSuppressionReason {
    NONE, DISABLED, UNHEALTHY, LOCKED_OUT, RC_TTL_EXPIRED, PERCEPTION_AGE_EXPIRED,
    AUTONOMOUS_COMMAND_HOLD_EXPIRED, STALE_FLIGHT_EPOCH, STALE_AUTONOMY_GENERATION,
    TRACKING_INACTIVE, FLIGHT_STATE_INACTIVE,
}

data class AutonomousYawContext(
    val control: YawControlOutcome,
    val associationState: TargetAssociationState,
    val yawFollowState: YawFollowState,
    val yawFollowReason: YawFollowReason,
    val telemetryHeightMeters: Float?,
)

data class RcPublication(
    val commandTimestampNanos: Long,
    val desiredPublishedAtNanos: Long,
    val sendStartedAtNanos: Long,
    val sentAtNanos: Long,
    val requestedVector: RcVector,
    val actualVector: RcVector,
    val inputKind: RcInputKind,
    val desiredPublishedAtMillis: Long,
    val sentAtMillis: Long,
    val suppressionReason: RcSendSuppressionReason,
    val flightEpoch: Long,
    val autonomyGeneration: Long?,
    val autonomousContext: AutonomousYawContext?,
)

class RcControlLoop(
    private val scope: CoroutineScope,
    private val sender: suspend (RcVector) -> Unit,
    private val clock: MonotonicClock,
    private val periodMillis: Long = 50L,
    private val inputTtlMillis: Long = 250L,
    private val maximumManualRcMagnitude: Int = MAXIMUM_MANUAL_RC_MAGNITUDE,
    private val onSendFailure: (Throwable) -> Unit = {},
    private val traceClockNanos: () -> Long = { clock.nowMillis() * NANOS_PER_MILLISECOND },
    private val onRcSent: (RcPublication) -> Unit = {},
    private val authorityValidator: ((RcInputKind, AutonomousYawContext?) -> RcSendSuppressionReason?)? = null,
) {
    private data class Desired(
        val vector: RcVector,
        val publishedAtMillis: Long,
        val publishedAtNanos: Long,
        val inputKind: RcInputKind,
        val flightEpoch: Long,
        val autonomyGeneration: Long? = null,
        val perceptionValidityMillis: Long? = null,
        val validityExpiryReason: RcSendSuppressionReason = RcSendSuppressionReason.PERCEPTION_AGE_EXPIRED,
        val autonomousContext: AutonomousYawContext? = null,
    )

    private data class Selection(
        val desired: Desired,
        val actualVector: RcVector,
        val suppressionReason: RcSendSuppressionReason,
    )

    private val lock = Any()
    /** Serializes physical sends so a safety zero cannot be overtaken by an already-selected RC vector. */
    private val sendMutex = Mutex()
    private var flightEpoch = 0L
    private var desired = Desired(
        vector = RcVector.Zero,
        publishedAtMillis = -inputTtlMillis - 1L,
        publishedAtNanos = Long.MIN_VALUE,
        inputKind = RcInputKind.SAFETY_ZERO,
        flightEpoch = 0L,
    )
    private var enabled = false
    private var healthy = false
    private var lockedOut = false
    private var autonomyGeneration = 0L
    private var activeAutonomyGeneration: Long? = null
    private var loopJob: Job? = null

    fun start() {
        synchronized(lock) {
            if (loopJob?.isActive == true) return
            loopJob = scope.launch {
                while (isActive) {
                    sendCycle()
                    delay(periodMillis)
                }
            }
        }
    }

    /**
     * Atomically arms RC for a newly started flying epoch.
     * Increments flight epoch, clears any previous desired command to safety zero,
     * invalidates past autonomy generations, and guarantees the initial publication is ZERO.
     */
    fun enableForNewFlight(): Long = synchronized(lock) {
        flightEpoch += 1L
        autonomyGeneration += 1L
        activeAutonomyGeneration = null
        desired = Desired(
            vector = RcVector.Zero,
            publishedAtMillis = clock.nowMillis(),
            publishedAtNanos = traceClockNanos(),
            inputKind = RcInputKind.SAFETY_ZERO,
            flightEpoch = flightEpoch,
        )
        enabled = !lockedOut
        healthy = !lockedOut
        flightEpoch
    }

    fun setEnabled(value: Boolean) = synchronized(lock) {
        enabled = value && !lockedOut
        if (!enabled) preemptAutonomyLocked()
    }

    fun setHealthy(value: Boolean) = synchronized(lock) {
        healthy = value && !lockedOut
        if (!healthy) preemptAutonomyLocked()
    }

    /** Manual publication invalidates every previously issued autonomous generation first. */
    fun publish(vector: ManualControlVector, manualRcMagnitude: Int) = synchronized(lock) {
        if (enabled && healthy && !lockedOut) {
            preemptAutonomyLocked()
            val magnitude = manualRcMagnitude.coerceIn(MINIMUM_RC_MAGNITUDE, maximumManualRcMagnitude)
            desired = Desired(
                vector.toRcVector(magnitude, maximumManualRcMagnitude),
                clock.nowMillis(),
                traceClockNanos(),
                RcInputKind.MANUAL,
                flightEpoch = flightEpoch,
            )
        }
    }

    fun beginAutonomousYaw(): Long = synchronized(lock) {
        autonomyGeneration += 1L
        activeAutonomyGeneration = autonomyGeneration
        desired = Desired(
            vector = RcVector.Zero,
            publishedAtMillis = clock.nowMillis(),
            publishedAtNanos = traceClockNanos(),
            inputKind = RcInputKind.AUTONOMOUS_YAW,
            flightEpoch = flightEpoch,
            autonomyGeneration = activeAutonomyGeneration,
        )
        autonomyGeneration
    }

    /** The yaw-only API cannot express lateral, forward/back, or vertical output. */
    fun publishAutonomousYaw(
        yawRc: Int,
        generation: Long,
        validForMillis: Long = inputTtlMillis,
        validityExpiryReason: RcSendSuppressionReason = RcSendSuppressionReason.PERCEPTION_AGE_EXPIRED,
        context: AutonomousYawContext? = null,
    ) = synchronized(lock) {
        if (enabled && healthy && !lockedOut && activeAutonomyGeneration == generation) {
            desired = Desired(
                RcVector(yaw = yawRc.coerceIn(-AUTONOMOUS_YAW_RC_CAP, AUTONOMOUS_YAW_RC_CAP)),
                clock.nowMillis(),
                traceClockNanos(),
                RcInputKind.AUTONOMOUS_YAW,
                flightEpoch = flightEpoch,
                autonomyGeneration = generation,
                perceptionValidityMillis = validForMillis.coerceAtLeast(0L),
                validityExpiryReason = validityExpiryReason,
                autonomousContext = context,
            )
        }
    }

    /** Synchronously makes all outstanding autonomous publishers stale and selects zero. */
    fun preemptAutonomy(): Long = synchronized(lock) { preemptAutonomyLocked() }

    fun currentVector(nowMillis: Long = clock.nowMillis()): RcVector = synchronized(lock) {
        selectLocked(nowMillis).actualVector
    }

    suspend fun sendCycle() {
        val shouldSend = synchronized(lock) { enabled && !lockedOut }
        if (!shouldSend) return
        sendMutex.withLock {
            try {
                // Read only after taking the send lock. A concurrent STOP/stale transition therefore
                // either sends its zero first or waits for this already-sent vector and finishes with zero.
                val nowMillis = clock.nowMillis()
                val selection = synchronized(lock) { selectLocked(nowMillis) }
                val sendStartedAtNanos = traceClockNanos()
                sender(selection.actualVector)
                val sentAtNanos = traceClockNanos()
                runCatching {
                    onRcSent(
                        RcPublication(
                            commandTimestampNanos = sendStartedAtNanos,
                            desiredPublishedAtNanos = selection.desired.publishedAtNanos,
                            sendStartedAtNanos = sendStartedAtNanos,
                            sentAtNanos = sentAtNanos,
                            requestedVector = selection.desired.vector,
                            actualVector = selection.actualVector,
                            inputKind = selection.desired.inputKind,
                            desiredPublishedAtMillis = selection.desired.publishedAtMillis,
                            sentAtMillis = nowMillis,
                            suppressionReason = selection.suppressionReason,
                            flightEpoch = selection.desired.flightEpoch,
                            autonomyGeneration = selection.desired.autonomyGeneration,
                            autonomousContext = selection.desired.autonomousContext,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                setHealthy(false)
                onSendFailure(error)
            }
        }
    }

    suspend fun clearAndSendZero() {
        val generation = preemptAutonomy()
        sendZeroIfCurrent(generation)
    }

    /**
     * Sends the preemption zero only if no newer manual, safety, or re-arm action has won. This
     * preserves send serialization without allowing a delayed zero to overwrite a newer command.
     */
    suspend fun sendZeroIfCurrent(generation: Long) {
        sendMutex.withLock {
            val currentDesired = synchronized(lock) {
                desired.takeIf { autonomyGeneration == generation && it.vector == RcVector.Zero }
            }
            if (currentDesired == null) return@withLock
            try {
                val sendStartedAtNanos = traceClockNanos()
                sender(RcVector.Zero)
                val sentAtNanos = traceClockNanos()
                val sentAtMillis = clock.nowMillis()
                runCatching {
                    onRcSent(
                        RcPublication(
                            commandTimestampNanos = sendStartedAtNanos,
                            desiredPublishedAtNanos = currentDesired.publishedAtNanos,
                            sendStartedAtNanos = sendStartedAtNanos,
                            sentAtNanos = sentAtNanos,
                            requestedVector = RcVector.Zero,
                            actualVector = RcVector.Zero,
                            inputKind = RcInputKind.SAFETY_ZERO,
                            desiredPublishedAtMillis = currentDesired.publishedAtMillis,
                            sentAtMillis = sentAtMillis,
                            suppressionReason = RcSendSuppressionReason.NONE,
                            flightEpoch = currentDesired.flightEpoch,
                            autonomyGeneration = currentDesired.autonomyGeneration,
                            autonomousContext = currentDesired.autonomousContext,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onSendFailure(error)
            }
        }
    }

    suspend fun lockOutAfterZero() {
        clearAndSendZero()
        synchronized(lock) {
            lockedOut = true
            enabled = false
            healthy = false
        }
    }

    suspend fun shutdown(sendFinalZero: Boolean = true) {
        if (sendFinalZero && synchronized(lock) { !lockedOut }) clearAndSendZero()
        synchronized(lock) {
            enabled = false
            healthy = false
            loopJob?.cancel()
            loopJob = null
        }
    }

    private fun ManualControlVector.toRcVector(magnitude: Int, maximum: Int) = RcVector(
        lateral = axisToRc(lateral, magnitude, maximum),
        forward = axisToRc(forward, magnitude, maximum),
        vertical = axisToRc(vertical, magnitude, maximum),
        yaw = axisToRc(yaw, magnitude, maximum),
    )

    private fun axisToRc(value: Float, magnitude: Int, maximum: Int): Int =
        (value.coerceIn(-1f, 1f) * magnitude).roundToInt().coerceIn(-maximum, maximum)

    private fun preemptAutonomyLocked(): Long {
        val previousAutonomousContext = desired.autonomousContext
        autonomyGeneration += 1L
        activeAutonomyGeneration = null
        desired = Desired(
            vector = RcVector.Zero,
            publishedAtMillis = clock.nowMillis(),
            publishedAtNanos = traceClockNanos(),
            inputKind = RcInputKind.SAFETY_ZERO,
            flightEpoch = flightEpoch,
            autonomousContext = previousAutonomousContext,
        )
        return autonomyGeneration
    }

    private fun selectLocked(nowMillis: Long): Selection {
        val ageMillis = nowMillis - desired.publishedAtMillis
        val validatorSuppression = authorityValidator?.invoke(desired.inputKind, desired.autonomousContext)
        val suppression = when {
            lockedOut -> RcSendSuppressionReason.LOCKED_OUT
            !enabled -> RcSendSuppressionReason.DISABLED
            !healthy -> RcSendSuppressionReason.UNHEALTHY
            desired.flightEpoch != flightEpoch -> RcSendSuppressionReason.STALE_FLIGHT_EPOCH
            validatorSuppression != null -> validatorSuppression
            ageMillis >= inputTtlMillis -> RcSendSuppressionReason.RC_TTL_EXPIRED
            desired.inputKind == RcInputKind.AUTONOMOUS_YAW &&
                desired.autonomyGeneration != activeAutonomyGeneration ->
                RcSendSuppressionReason.STALE_AUTONOMY_GENERATION
            desired.inputKind == RcInputKind.AUTONOMOUS_YAW &&
                ageMillis >= (desired.perceptionValidityMillis ?: 0L) ->
                desired.validityExpiryReason
            else -> RcSendSuppressionReason.NONE
        }
        return Selection(
            desired = desired,
            actualVector = if (suppression == RcSendSuppressionReason.NONE && desired.inputKind != RcInputKind.SAFETY_ZERO) desired.vector else RcVector.Zero,
            suppressionReason = suppression,
        )
    }

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MINIMUM_RC_MAGNITUDE = 10
        const val MAXIMUM_MANUAL_RC_MAGNITUDE = 100
        const val AUTONOMOUS_YAW_RC_CAP = ProductionYawController.ABSOLUTE_YAW_RC_CAP
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
