package com.alonibh.tellodrone.domain

import kotlin.math.abs
import kotlin.math.sign

data class SentRcHistoryEntry(
    val sentAtMillis: Long,
    val yawRc: Int,
)

data class TelemetryYawSample(
    val sequence: Long = 0L,
    val receivedAtMillis: Long,
    val yawDegrees: Int,
    val previousYawDegrees: Int? = null,
    val shortestDeltaDegrees: Int? = null,
    val deltaMillis: Long? = null,
    val rawYawRateDegreesPerSecond: Float? = null,
    val filteredYawRateDegreesPerSecond: Float? = null,
)

enum class YawResponseSafetyStatus {
    NORMAL,
    MISMATCH_SUSPECT,
    ANOMALY_LATCHED,
}

enum class YawResponseAnomalyReason {
    CATASTROPHIC_YAW_RATE,
    SUSTAINED_DIRECTION_MISMATCH,
    ZERO_RUNAWAY,
}

data class YawResponseEvaluation(
    val status: YawResponseSafetyStatus,
    val reason: String? = null,
    val anomalyReason: YawResponseAnomalyReason? = null,
    val consecutiveMismatchSamples: Int = 0,
    val rawYawRate: Float? = null,
    val filteredYawRate: Float? = null,
    val dominantRecentRc: Int = 0,
    val recentCommandedYawRc: Int = 0,
    val anomalyDurationMillis: Long? = null,
)

/**
 * Pure, deterministic safety monitor evaluating physical aircraft yaw response against recent
 * commanded physical RC history.
 *
 * Designed specifically to protect against catastrophic uncommanded physical yaw excursions during
 * autonomous yaw-follow without false-tripping on normal proportional rotations, telemetry
 * quantization, command-to-actuation transport latency, or braking/inertia center-crossings.
 */
class YawResponseSafetyMonitor(
    private val rcHistoryWindowMillis: Long = RC_HISTORY_WINDOW_MILLIS,
    private val commandLatencyGraceMillis: Long = COMMAND_LATENCY_GRACE_MILLIS,
    private val brakingGraceMillis: Long = BRAKING_GRACE_MILLIS,
    private val catastrophicRateThresholdDps: Float = CATASTROPHIC_RATE_THRESHOLD_DPS,
    private val severeMismatchRateThresholdDps: Float = SEVERE_MISMATCH_RATE_THRESHOLD_DPS,
    private val zeroRunawayRateThresholdDps: Float = ZERO_RUNAWAY_RATE_THRESHOLD_DPS,
    private val requiredConfirmationSamples: Int = REQUIRED_CONFIRMATION_SAMPLES,
) {
    private val rcHistory = ArrayDeque<SentRcHistoryEntry>()
    private var consecutiveMismatchSamples = 0
    private var isLatched = false
    private var latchReason: String? = null
    private var latchedAnomalyReason: YawResponseAnomalyReason? = null
    private var firstMismatchTimestampMillis: Long? = null
    private var lastObservedTelemetryTimestampMillis: Long? = null

    /** Records a physically sent yaw RC command with its monotonic timestamp. */
    fun recordSentRc(sentAtMillis: Long, yawRc: Int) {
        rcHistory.addLast(SentRcHistoryEntry(sentAtMillis, yawRc))
        pruneRcHistory(sentAtMillis)
    }

    /**
     * Evaluates a new incoming telemetry sample against recent command history.
     * Duplicate or backwards-time telemetry samples are ignored without altering state.
     */
    fun evaluate(
        sample: TelemetryYawSample,
        flightState: FlightState,
        yawFollowState: YawFollowState,
    ): YawResponseEvaluation {
        if (isLatched) {
            val duration = firstMismatchTimestampMillis?.let { sample.receivedAtMillis - it }
            return YawResponseEvaluation(
                status = YawResponseSafetyStatus.ANOMALY_LATCHED,
                reason = latchReason,
                anomalyReason = latchedAnomalyReason,
                consecutiveMismatchSamples = consecutiveMismatchSamples,
                rawYawRate = sample.rawYawRateDegreesPerSecond,
                filteredYawRate = sample.filteredYawRateDegreesPerSecond,
                anomalyDurationMillis = duration,
            )
        }

        // Only evaluate active flying states
        if (flightState != FlightState.Flying || yawFollowState != YawFollowState.ACTIVE) {
            consecutiveMismatchSamples = 0
            firstMismatchTimestampMillis = null
            return YawResponseEvaluation(
                status = YawResponseSafetyStatus.NORMAL,
                rawYawRate = sample.rawYawRateDegreesPerSecond,
                filteredYawRate = sample.filteredYawRateDegreesPerSecond,
            )
        }

        val lastTs = lastObservedTelemetryTimestampMillis
        if (lastTs != null && sample.receivedAtMillis <= lastTs) {
            return YawResponseEvaluation(
                status = if (consecutiveMismatchSamples > 0) YawResponseSafetyStatus.MISMATCH_SUSPECT else YawResponseSafetyStatus.NORMAL,
                consecutiveMismatchSamples = consecutiveMismatchSamples,
                rawYawRate = sample.rawYawRateDegreesPerSecond,
                filteredYawRate = sample.filteredYawRateDegreesPerSecond,
            )
        }
        lastObservedTelemetryTimestampMillis = sample.receivedAtMillis
        pruneRcHistory(sample.receivedAtMillis)

        val rawRate = sample.rawYawRateDegreesPerSecond
        val filteredRate = sample.filteredYawRateDegreesPerSecond ?: rawRate
        if (rawRate == null || filteredRate == null) {
            return YawResponseEvaluation(
                status = YawResponseSafetyStatus.NORMAL,
                rawYawRate = rawRate,
                filteredYawRate = filteredRate,
            )
        }

        val effectiveRate = if (abs(filteredRate) > 0f) filteredRate else rawRate
        val absEffectiveRate = abs(effectiveRate)
        val rateSign = effectiveRate.sign.toInt()

        // Analyze recent physical commands over latency-shifted window
        val relevantCommands = rcHistory.filter {
            it.sentAtMillis <= sample.receivedAtMillis &&
                it.sentAtMillis >= sample.receivedAtMillis - rcHistoryWindowMillis
        }

        val nonZeroCommands = relevantCommands.filter { it.yawRc != 0 }
        val mostRecentNonZero = nonZeroCommands.lastOrNull()
        val mostRecentCommand = relevantCommands.lastOrNull()
        val allRecentZero = nonZeroCommands.isEmpty() && relevantCommands.isNotEmpty()
        val dominantRc = mostRecentNonZero?.yawRc ?: 0

        // Case 1: Catastrophic opposing rate (e.g. rate >= 140 deg/s in opposite direction of recent commands or when zero)
        // In the real flight incident: rate jumped 89 -> 70 -> 65 -> 149 -> 155 -> 260 deg/s with negative/zero RC
        val opposingRecentCommand = mostRecentNonZero?.let { (it.yawRc.sign * rateSign) < 0 } ?: false
        val zeroedBeyondBraking = allRecentZero && mostRecentCommand?.let {
            (sample.receivedAtMillis - it.sentAtMillis) >= brakingGraceMillis
        } ?: false

        if (absEffectiveRate >= catastrophicRateThresholdDps) {
            if (opposingRecentCommand || allRecentZero || relevantCommands.isEmpty()) {
                isLatched = true
                latchedAnomalyReason = YawResponseAnomalyReason.CATASTROPHIC_YAW_RATE
                if (firstMismatchTimestampMillis == null) firstMismatchTimestampMillis = sample.receivedAtMillis
                val duration = firstMismatchTimestampMillis?.let { sample.receivedAtMillis - it }
                latchReason = "Catastrophic yaw rate of ${"%.1f".format(effectiveRate)}°/s opposing commanded physical state"
                consecutiveMismatchSamples++
                return YawResponseEvaluation(
                    status = YawResponseSafetyStatus.ANOMALY_LATCHED,
                    reason = latchReason,
                    anomalyReason = latchedAnomalyReason,
                    consecutiveMismatchSamples = consecutiveMismatchSamples,
                    rawYawRate = rawRate,
                    filteredYawRate = filteredRate,
                    dominantRecentRc = dominantRc,
                    recentCommandedYawRc = dominantRc,
                    anomalyDurationMillis = duration,
                )
            }
        }

        // Case 2: Severe opposing yaw rate (e.g. |rate| >= 50 deg/s opposite to recent commanded non-zero RC)
        val earliestOpposingCommand = nonZeroCommands.filter { (it.yawRc.sign * rateSign) < 0 }.minByOrNull { it.sentAtMillis }
        val isSevereMismatch = if (absEffectiveRate >= severeMismatchRateThresholdDps && opposingRecentCommand && earliestOpposingCommand != null) {
            val ageOfOpposing = sample.receivedAtMillis - earliestOpposingCommand.sentAtMillis
            // Outside initial latency grace window
            ageOfOpposing >= commandLatencyGraceMillis
        } else false

        // Case 3: Zero-command runaway (|rate| >= 45 deg/s continuing or accelerating when commanded zero)
        val isZeroRunaway = if (absEffectiveRate >= zeroRunawayRateThresholdDps && allRecentZero) {
            zeroedBeyondBraking
        } else false

        if (isSevereMismatch || isZeroRunaway) {
            if (firstMismatchTimestampMillis == null) firstMismatchTimestampMillis = sample.receivedAtMillis
            val duration = firstMismatchTimestampMillis?.let { sample.receivedAtMillis - it }
            consecutiveMismatchSamples++
            val triggerReason = if (isSevereMismatch) YawResponseAnomalyReason.SUSTAINED_DIRECTION_MISMATCH else YawResponseAnomalyReason.ZERO_RUNAWAY
            if (consecutiveMismatchSamples >= requiredConfirmationSamples) {
                isLatched = true
                latchedAnomalyReason = triggerReason
                latchReason = if (isSevereMismatch) {
                    "Sustained command-response mismatch: physical rate ${"%.1f".format(effectiveRate)}°/s opposed recent RC commands across $consecutiveMismatchSamples samples"
                } else {
                    "Uncommanded zero runaway: physical rate ${"%.1f".format(effectiveRate)}°/s sustained while zero commanded"
                }
                return YawResponseEvaluation(
                    status = YawResponseSafetyStatus.ANOMALY_LATCHED,
                    reason = latchReason,
                    anomalyReason = latchedAnomalyReason,
                    consecutiveMismatchSamples = consecutiveMismatchSamples,
                    rawYawRate = rawRate,
                    filteredYawRate = filteredRate,
                    dominantRecentRc = dominantRc,
                    recentCommandedYawRc = dominantRc,
                    anomalyDurationMillis = duration,
                )
            } else {
                return YawResponseEvaluation(
                    status = YawResponseSafetyStatus.MISMATCH_SUSPECT,
                    reason = "Suspect mismatch sample $consecutiveMismatchSamples of $requiredConfirmationSamples",
                    anomalyReason = triggerReason,
                    consecutiveMismatchSamples = consecutiveMismatchSamples,
                    rawYawRate = rawRate,
                    filteredYawRate = filteredRate,
                    dominantRecentRc = dominantRc,
                    recentCommandedYawRc = dominantRc,
                    anomalyDurationMillis = duration,
                )
            }
        } else {
            // Decays confirmation counter when sample is normal/consistent
            if (consecutiveMismatchSamples > 0) {
                consecutiveMismatchSamples--
            }
            if (consecutiveMismatchSamples == 0) {
                firstMismatchTimestampMillis = null
            }
            return YawResponseEvaluation(
                status = if (consecutiveMismatchSamples > 0) YawResponseSafetyStatus.MISMATCH_SUSPECT else YawResponseSafetyStatus.NORMAL,
                consecutiveMismatchSamples = consecutiveMismatchSamples,
                rawYawRate = rawRate,
                filteredYawRate = filteredRate,
                dominantRecentRc = dominantRc,
                recentCommandedYawRc = dominantRc,
            )
        }
    }

    fun reset() {
        rcHistory.clear()
        consecutiveMismatchSamples = 0
        isLatched = false
        latchReason = null
        latchedAnomalyReason = null
        firstMismatchTimestampMillis = null
        lastObservedTelemetryTimestampMillis = null
    }

    private fun pruneRcHistory(nowMillis: Long) {
        val cutoff = nowMillis - rcHistoryWindowMillis
        while (rcHistory.isNotEmpty() && rcHistory.first().sentAtMillis < cutoff) {
            rcHistory.removeFirst()
        }
    }

    private val Int.sign: Int get() = when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }

    companion object {
        /** Bounded history window to correlate physical command history against telemetry (~550ms). */
        const val RC_HISTORY_WINDOW_MILLIS = 550L
        /** Latency grace for initial command-to-actuation turnaround (100ms). */
        const val COMMAND_LATENCY_GRACE_MILLIS = 100L
        /** Grace period for aircraft braking deceleration before flagging zero runaway (220ms). */
        const val BRAKING_GRACE_MILLIS = 220L
        /** Absolute catastrophic physical rate threshold tripping immediate/early latch (140 deg/s). */
        const val CATASTROPHIC_RATE_THRESHOLD_DPS = 140.0f
        /** Severe opposing rate threshold requiring confirmation (50 deg/s). */
        const val SEVERE_MISMATCH_RATE_THRESHOLD_DPS = 50.0f
        /** High rate threshold while commanded zero (45 deg/s). */
        const val ZERO_RUNAWAY_RATE_THRESHOLD_DPS = 45.0f
        /** Multi-sample confirmation count for moderate/severe mismatch. */
        const val REQUIRED_CONFIRMATION_SAMPLES = 2
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
