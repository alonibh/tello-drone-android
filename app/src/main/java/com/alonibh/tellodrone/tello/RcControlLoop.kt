package com.alonibh.tellodrone.tello

import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.ProductionYawController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

class RcControlLoop(
    private val scope: CoroutineScope,
    private val sender: suspend (RcVector) -> Unit,
    private val clock: MonotonicClock,
    private val periodMillis: Long = 50L,
    private val inputTtlMillis: Long = 250L,
    private val maximumRcMagnitude: Int = 40,
    private val onSendFailure: (Throwable) -> Unit = {},
) {
    private data class Desired(val vector: RcVector, val publishedAtMillis: Long)

    private val lock = Any()
    /** Serializes physical sends so a safety zero cannot be overtaken by an already-selected RC vector. */
    private val sendMutex = Mutex()
    private var desired = Desired(RcVector.Zero, -inputTtlMillis - 1L)
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

    fun setEnabled(value: Boolean) = synchronized(lock) {
        enabled = value && !lockedOut
        if (!enabled) preemptAutonomyLocked()
    }

    fun setHealthy(value: Boolean) = synchronized(lock) {
        healthy = value && !lockedOut
        if (!healthy) preemptAutonomyLocked()
    }

    /** Manual publication invalidates every previously issued autonomous generation first. */
    fun publish(vector: ManualControlVector, speedPercent: Int) = synchronized(lock) {
        if (enabled && healthy && !lockedOut) {
            preemptAutonomyLocked()
            val magnitude = speedPercent.coerceIn(MINIMUM_RC_MAGNITUDE, maximumRcMagnitude)
            desired = Desired(vector.toRcVector(magnitude, maximumRcMagnitude), clock.nowMillis())
        }
    }

    fun beginAutonomousYaw(): Long = synchronized(lock) {
        autonomyGeneration += 1L
        activeAutonomyGeneration = autonomyGeneration
        desired = Desired(RcVector.Zero, clock.nowMillis())
        autonomyGeneration
    }

    /** The yaw-only API cannot express lateral, forward/back, or vertical output. */
    fun publishAutonomousYaw(yawRc: Int, generation: Long) = synchronized(lock) {
        if (enabled && healthy && !lockedOut && activeAutonomyGeneration == generation) {
            desired = Desired(
                RcVector(yaw = yawRc.coerceIn(-AUTONOMOUS_YAW_RC_CAP, AUTONOMOUS_YAW_RC_CAP)),
                clock.nowMillis(),
            )
        }
    }

    /** Synchronously makes all outstanding autonomous publishers stale and selects zero. */
    fun preemptAutonomy(): Long = synchronized(lock) { preemptAutonomyLocked() }

    fun currentVector(nowMillis: Long = clock.nowMillis()): RcVector = synchronized(lock) {
        if (!enabled || !healthy || lockedOut || nowMillis - desired.publishedAtMillis >= inputTtlMillis) {
            RcVector.Zero
        } else desired.vector
    }

    suspend fun sendCycle(nowMillis: Long = clock.nowMillis()) {
        val shouldSend = synchronized(lock) { enabled && !lockedOut }
        if (!shouldSend) return
        sendMutex.withLock {
            try {
                // Read only after taking the send lock. A concurrent STOP/stale transition therefore
                // either sends its zero first or waits for this already-sent vector and finishes with zero.
                sender(currentVector(nowMillis))
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
            val stillCurrent = synchronized(lock) {
                autonomyGeneration == generation && desired.vector == RcVector.Zero
            }
            if (!stillCurrent) return@withLock
            try {
                sender(RcVector.Zero)
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
        autonomyGeneration += 1L
        activeAutonomyGeneration = null
        desired = Desired(RcVector.Zero, clock.nowMillis())
        return autonomyGeneration
    }

    companion object {
        const val MINIMUM_RC_MAGNITUDE = 10
        const val AUTONOMOUS_YAW_RC_CAP = ProductionYawController.ABSOLUTE_YAW_RC_CAP
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
