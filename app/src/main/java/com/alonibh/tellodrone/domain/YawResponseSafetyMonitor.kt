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
    val isJustLatched: Boolean = false,
    val reason: String? = null,
    val anomalyReason: YawResponseAnomalyReason? = null,
    val consecutiveMismatchSamples: Int = 0,
    val rawYawRate: Float? = null,
    val filteredYawRate: Float? = null,
    val dominantRecentRc: Int = 0,
    val recentCommandedYawRc: Int = 0,
    val latestActualYawRc: Int = 0,
    val latestNonzeroYawRc: Int? = null,
    val anomalyDurationMillis: Long? = null,
    val rearmReady: Boolean = false,
    val consecutiveSettledSamples: Int = 0,
    val ageOfMostRecentNonzeroRcMillis: Long? = null,
    val zeroCommandDurationMillis: Long? = null,
    val currentCommandSignEpisodeAgeMillis: Long? = null,
)

/**
 * Pure, deterministic, thread-safe safety monitor evaluating physical aircraft yaw response
 * against commanded physical RC history.
 *
 * Protects against uncommanded physical yaw excursions, catastrophic runaway, sustained direction
 * mismatches, and zero-command runaway during autonomous yaw-follow without false-tripping on
 * normal proportional rotations, telemetry quantization, transport latency, or braking/inertia.
 */
class YawResponseSafetyMonitor(
    private val rcHistoryWindowMillis: Long = RC_HISTORY_WINDOW_MILLIS,
    private val commandLatencyGraceMillis: Long = COMMAND_LATENCY_GRACE_MILLIS,
    private val brakingGraceMillis: Long = BRAKING_GRACE_MILLIS,
    private val catastrophicRateThresholdDps: Float = CATASTROPHIC_RATE_THRESHOLD_DPS,
    private val severeMismatchRateThresholdDps: Float = SEVERE_MISMATCH_RATE_THRESHOLD_DPS,
    private val zeroRunawayRateThresholdDps: Float = ZERO_RUNAWAY_RATE_THRESHOLD_DPS,
    private val requiredConfirmationSamples: Int = REQUIRED_CONFIRMATION_SAMPLES,
    private val settledRateThresholdDps: Float = SETTLED_RATE_THRESHOLD_DPS,
    private val requiredSettledSamples: Int = REQUIRED_SETTLED_SAMPLES,
    private val requiredSettledDurationMillis: Long = REQUIRED_SETTLED_DURATION_MILLIS,
) {
    private val lock = Any()

    private val rcHistory = ArrayDeque<SentRcHistoryEntry>()
    private var consecutiveMismatchSamples = 0
    private var isLatchedState = false
    private var latchReason: String? = null
    private var latchedAnomalyReason: YawResponseAnomalyReason? = null
    private var firstMismatchTimestampMillis: Long? = null
    private var lastObservedTelemetryTimestampMillis: Long? = null

    // Explicit command timing tracking
    private var latestActualYawRc: Int = 0
    private var latestNonzeroYawRc: Int? = null
    private var latestNonzeroYawRcTimestampMillis: Long? = null
    private var timeZeroCommandStateBeganMillis: Long? = null
    private var currentCommandSign: Int = 0
    private var currentCommandSignEpisodeStartMillis: Long? = null

    // Settling tracking while latched
    private var consecutiveSettledSamplesWhileLatched = 0
    private var settledStartTimeMillis: Long? = null
    private var rearmReadyState = false

    /** Records a physically sent yaw RC command with its monotonic timestamp. Thread-safe. */
    fun recordSentRc(sentAtMillis: Long, yawRc: Int) = synchronized(lock) {
        val newSign = yawRc.sign
        if (newSign != currentCommandSign) {
            currentCommandSign = newSign
            currentCommandSignEpisodeStartMillis = sentAtMillis
        }

        if (yawRc != 0) {
            latestNonzeroYawRc = yawRc
            latestNonzeroYawRcTimestampMillis = sentAtMillis
            timeZeroCommandStateBeganMillis = null
        } else {
            if (timeZeroCommandStateBeganMillis == null) {
                timeZeroCommandStateBeganMillis = sentAtMillis
            }
        }
        latestActualYawRc = yawRc

        rcHistory.addLast(SentRcHistoryEntry(sentAtMillis, yawRc))
        pruneRcHistoryLocked(sentAtMillis)
    }

    /**
     * Evaluates a new incoming telemetry sample against recent command history.
     * Duplicate or backwards-time telemetry samples are ignored without altering state.
     * Returns an immutable evaluation while thread-safely updating internal state.
     */
    fun evaluate(
        sample: TelemetryYawSample,
        flightState: FlightState,
        yawFollowState: YawFollowState,
    ): YawResponseEvaluation = synchronized(lock) {
        val isNewSample = lastObservedTelemetryTimestampMillis == null || sample.receivedAtMillis > lastObservedTelemetryTimestampMillis!!
        if (isNewSample) {
            lastObservedTelemetryTimestampMillis = sample.receivedAtMillis
            pruneRcHistoryLocked(sample.receivedAtMillis)
        }

        val rawRate = sample.rawYawRateDegreesPerSecond
        val filteredRate = sample.filteredYawRateDegreesPerSecond ?: rawRate
        val dominantRc = latestNonzeroYawRc ?: latestActualYawRc
        val nonzeroRcAge = latestNonzeroYawRcTimestampMillis?.let { (sample.receivedAtMillis - it).coerceAtLeast(0L) }
        val zeroDuration = timeZeroCommandStateBeganMillis?.let { (sample.receivedAtMillis - it).coerceAtLeast(0L) }
        val signEpisodeAge = currentCommandSignEpisodeStartMillis?.let { (sample.receivedAtMillis - it).coerceAtLeast(0L) }

        if (isLatchedState) {
            // Monitor continues observing incoming telemetry to determine if aircraft rotation has safely settled
            if (isNewSample && rawRate != null && filteredRate != null) {
                val isSettled = abs(rawRate) <= settledRateThresholdDps && abs(filteredRate) <= settledRateThresholdDps
                if (isSettled) {
                    consecutiveSettledSamplesWhileLatched++
                    if (settledStartTimeMillis == null) {
                        settledStartTimeMillis = sample.receivedAtMillis
                    }
                    val settledDuration = sample.receivedAtMillis - (settledStartTimeMillis ?: sample.receivedAtMillis)
                    if (consecutiveSettledSamplesWhileLatched >= requiredSettledSamples && settledDuration >= requiredSettledDurationMillis) {
                        rearmReadyState = true
                    }
                } else {
                    consecutiveSettledSamplesWhileLatched = 0
                    settledStartTimeMillis = null
                    rearmReadyState = false
                }
            }

            val duration = firstMismatchTimestampMillis?.let { sample.receivedAtMillis - it }
            return YawResponseEvaluation(
                status = YawResponseSafetyStatus.ANOMALY_LATCHED,
                isJustLatched = false,
                reason = latchReason,
                anomalyReason = latchedAnomalyReason,
                consecutiveMismatchSamples = consecutiveMismatchSamples,
                rawYawRate = rawRate,
                filteredYawRate = filteredRate,
                dominantRecentRc = dominantRc,
                recentCommandedYawRc = latestActualYawRc,
                latestActualYawRc = latestActualYawRc,
                latestNonzeroYawRc = latestNonzeroYawRc,
                anomalyDurationMillis = duration,
                rearmReady = rearmReadyState,
                consecutiveSettledSamples = consecutiveSettledSamplesWhileLatched,
                ageOfMostRecentNonzeroRcMillis = nonzeroRcAge,
                zeroCommandDurationMillis = zeroDuration,
                currentCommandSignEpisodeAgeMillis = signEpisodeAge,
            )
        }

        // Only evaluate active flying states
        if (flightState != FlightState.Flying || yawFollowState != YawFollowState.ACTIVE) {
            consecutiveMismatchSamples = 0
            firstMismatchTimestampMillis = null
            return YawResponseEvaluation(
                status = YawResponseSafetyStatus.NORMAL,
                isJustLatched = false,
                rawYawRate = rawRate,
                filteredYawRate = filteredRate,
                dominantRecentRc = dominantRc,
                recentCommandedYawRc = latestActualYawRc,
                latestActualYawRc = latestActualYawRc,
                latestNonzeroYawRc = latestNonzeroYawRc,
                ageOfMostRecentNonzeroRcMillis = nonzeroRcAge,
                zeroCommandDurationMillis = zeroDuration,
                currentCommandSignEpisodeAgeMillis = signEpisodeAge,
            )
        }

        if (!isNewSample) {
            return YawResponseEvaluation(
                status = if (consecutiveMismatchSamples > 0) YawResponseSafetyStatus.MISMATCH_SUSPECT else YawResponseSafetyStatus.NORMAL,
                isJustLatched = false,
                consecutiveMismatchSamples = consecutiveMismatchSamples,
                rawYawRate = rawRate,
                filteredYawRate = filteredRate,
                dominantRecentRc = dominantRc,
                recentCommandedYawRc = latestActualYawRc,
                latestActualYawRc = latestActualYawRc,
                latestNonzeroYawRc = latestNonzeroYawRc,
                ageOfMostRecentNonzeroRcMillis = nonzeroRcAge,
                zeroCommandDurationMillis = zeroDuration,
                currentCommandSignEpisodeAgeMillis = signEpisodeAge,
            )
        }

        if (rawRate == null || filteredRate == null) {
            return YawResponseEvaluation(
                status = YawResponseSafetyStatus.NORMAL,
                isJustLatched = false,
                rawYawRate = rawRate,
                filteredYawRate = filteredRate,
                dominantRecentRc = dominantRc,
                recentCommandedYawRc = latestActualYawRc,
                latestActualYawRc = latestActualYawRc,
                latestNonzeroYawRc = latestNonzeroYawRc,
                ageOfMostRecentNonzeroRcMillis = nonzeroRcAge,
                zeroCommandDurationMillis = zeroDuration,
                currentCommandSignEpisodeAgeMillis = signEpisodeAge,
            )
        }

        val absRawRate = abs(rawRate)
        val absFilteredRate = abs(filteredRate)
        val rateSign = filteredRate.sign.toInt().takeIf { it != 0 } ?: rawRate.sign.toInt()

        // Critical 4 & 5: Raw rate is authoritative for absolute catastrophic protection regardless of RC sign.
        // Valid deltaMillis check: valid sample must have positive deltaMillis (e.g. >= 20ms).
        val validSampleTiming = sample.deltaMillis == null || sample.deltaMillis in 20L..1000L
        if (absRawRate >= catastrophicRateThresholdDps && validSampleTiming) {
            isLatchedState = true
            latchedAnomalyReason = YawResponseAnomalyReason.CATASTROPHIC_YAW_RATE
            if (firstMismatchTimestampMillis == null) firstMismatchTimestampMillis = sample.receivedAtMillis
            val duration = firstMismatchTimestampMillis?.let { sample.receivedAtMillis - it }
            latchReason = "Catastrophic raw physical yaw rate of ${"%.1f".format(rawRate)}°/s exceeded safety ceiling ($catastrophicRateThresholdDps°/s)"
            consecutiveMismatchSamples++
            return YawResponseEvaluation(
                status = YawResponseSafetyStatus.ANOMALY_LATCHED,
                isJustLatched = true,
                reason = latchReason,
                anomalyReason = latchedAnomalyReason,
                consecutiveMismatchSamples = consecutiveMismatchSamples,
                rawYawRate = rawRate,
                filteredYawRate = filteredRate,
                dominantRecentRc = dominantRc,
                recentCommandedYawRc = latestActualYawRc,
                latestActualYawRc = latestActualYawRc,
                latestNonzeroYawRc = latestNonzeroYawRc,
                anomalyDurationMillis = duration,
                ageOfMostRecentNonzeroRcMillis = nonzeroRcAge,
                zeroCommandDurationMillis = zeroDuration,
                currentCommandSignEpisodeAgeMillis = signEpisodeAge,
            )
        }

        // Critical 3: Zero-command runaway.
        // Evaluates when latest physical command is 0, continuously commanded for >= brakingGraceMillis,
        // and physical rate remains >= zeroRunawayRateThresholdDps.
        val isZeroRunaway = if (latestActualYawRc == 0 && timeZeroCommandStateBeganMillis != null) {
            val zeroBeganAge = sample.receivedAtMillis - timeZeroCommandStateBeganMillis!!
            zeroBeganAge >= brakingGraceMillis && maxOf(absFilteredRate, absRawRate) >= zeroRunawayRateThresholdDps
        } else false

        // Critical 6: Severe opposing direction mismatch.
        // Latency grace is measured from start of CURRENT contiguous command-sign episode.
        val isSevereMismatch = if (currentCommandSign != 0 && (currentCommandSign * rateSign) < 0) {
            val signEpisodeStart = currentCommandSignEpisodeStartMillis ?: sample.receivedAtMillis
            val episodeAge = sample.receivedAtMillis - signEpisodeStart
            episodeAge >= commandLatencyGraceMillis && absFilteredRate >= severeMismatchRateThresholdDps
        } else false

        if (isSevereMismatch || isZeroRunaway) {
            if (firstMismatchTimestampMillis == null) firstMismatchTimestampMillis = sample.receivedAtMillis
            val duration = firstMismatchTimestampMillis?.let { sample.receivedAtMillis - it }
            consecutiveMismatchSamples++
            val triggerReason = if (isSevereMismatch) YawResponseAnomalyReason.SUSTAINED_DIRECTION_MISMATCH else YawResponseAnomalyReason.ZERO_RUNAWAY
            if (consecutiveMismatchSamples >= requiredConfirmationSamples) {
                isLatchedState = true
                latchedAnomalyReason = triggerReason
                latchReason = if (isSevereMismatch) {
                    "Sustained command-response mismatch: physical rate ${"%.1f".format(filteredRate)}°/s opposed commanded direction across $consecutiveMismatchSamples samples"
                } else {
                    "Uncommanded zero runaway: physical rate ${"%.1f".format(filteredRate)}°/s sustained while zero commanded"
                }
                return YawResponseEvaluation(
                    status = YawResponseSafetyStatus.ANOMALY_LATCHED,
                    isJustLatched = true,
                    reason = latchReason,
                    anomalyReason = latchedAnomalyReason,
                    consecutiveMismatchSamples = consecutiveMismatchSamples,
                    rawYawRate = rawRate,
                    filteredYawRate = filteredRate,
                    dominantRecentRc = dominantRc,
                    recentCommandedYawRc = latestActualYawRc,
                    latestActualYawRc = latestActualYawRc,
                    latestNonzeroYawRc = latestNonzeroYawRc,
                    anomalyDurationMillis = duration,
                    ageOfMostRecentNonzeroRcMillis = nonzeroRcAge,
                    zeroCommandDurationMillis = zeroDuration,
                    currentCommandSignEpisodeAgeMillis = signEpisodeAge,
                )
            } else {
                return YawResponseEvaluation(
                    status = YawResponseSafetyStatus.MISMATCH_SUSPECT,
                    isJustLatched = false,
                    reason = "Suspect mismatch sample $consecutiveMismatchSamples of $requiredConfirmationSamples",
                    anomalyReason = triggerReason,
                    consecutiveMismatchSamples = consecutiveMismatchSamples,
                    rawYawRate = rawRate,
                    filteredYawRate = filteredRate,
                    dominantRecentRc = dominantRc,
                    recentCommandedYawRc = latestActualYawRc,
                    latestActualYawRc = latestActualYawRc,
                    latestNonzeroYawRc = latestNonzeroYawRc,
                    anomalyDurationMillis = duration,
                    ageOfMostRecentNonzeroRcMillis = nonzeroRcAge,
                    zeroCommandDurationMillis = zeroDuration,
                    currentCommandSignEpisodeAgeMillis = signEpisodeAge,
                )
            }
        } else {
            if (consecutiveMismatchSamples > 0) {
                consecutiveMismatchSamples--
            }
            if (consecutiveMismatchSamples == 0) {
                firstMismatchTimestampMillis = null
            }
            return YawResponseEvaluation(
                status = if (consecutiveMismatchSamples > 0) YawResponseSafetyStatus.MISMATCH_SUSPECT else YawResponseSafetyStatus.NORMAL,
                isJustLatched = false,
                consecutiveMismatchSamples = consecutiveMismatchSamples,
                rawYawRate = rawRate,
                filteredYawRate = filteredRate,
                dominantRecentRc = dominantRc,
                recentCommandedYawRc = latestActualYawRc,
                latestActualYawRc = latestActualYawRc,
                latestNonzeroYawRc = latestNonzeroYawRc,
                ageOfMostRecentNonzeroRcMillis = nonzeroRcAge,
                zeroCommandDurationMillis = zeroDuration,
                currentCommandSignEpisodeAgeMillis = signEpisodeAge,
            )
        }
    }

    /** Thread-safe check for latched status. */
    fun isLatched(): Boolean = synchronized(lock) { isLatchedState }

    /** Thread-safe check if physical settling conditions have been validated for re-arm. */
    fun isRearmReady(): Boolean = synchronized(lock) { rearmReadyState }

    /**
     * Atomically validates that the physical settling conditions are satisfied, acknowledges the anomaly,
     * resets monitor state, and establishes a fresh RC history boundary.
     * Returns true if re-arm was permitted and reset completed; false if physical settling was not met.
     */
    fun tryAcknowledgeAndResetForRearm(): Boolean = synchronized(lock) {
        if (!isLatchedState) {
            resetLocked()
            return true
        }
        if (rearmReadyState) {
            resetLocked()
            return true
        }
        false
    }

    /** Resets all internal monitor history and latches. Thread-safe. */
    fun reset() = synchronized(lock) {
        resetLocked()
    }

    private fun resetLocked() {
        rcHistory.clear()
        consecutiveMismatchSamples = 0
        isLatchedState = false
        latchReason = null
        latchedAnomalyReason = null
        firstMismatchTimestampMillis = null
        lastObservedTelemetryTimestampMillis = null
        latestActualYawRc = 0
        latestNonzeroYawRc = null
        timeZeroCommandStateBeganMillis = null
        currentCommandSign = 0
        currentCommandSignEpisodeStartMillis = null
        consecutiveSettledSamplesWhileLatched = 0
        settledStartTimeMillis = null
        rearmReadyState = false
    }

    private fun pruneRcHistoryLocked(nowMillis: Long) {
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
        /** Absolute catastrophic physical rate threshold tripping immediate latch (140 deg/s). */
        const val CATASTROPHIC_RATE_THRESHOLD_DPS = 140.0f
        /** Severe opposing rate threshold requiring confirmation (50 deg/s). */
        const val SEVERE_MISMATCH_RATE_THRESHOLD_DPS = 50.0f
        /** High rate threshold while commanded zero (45 deg/s). */
        const val ZERO_RUNAWAY_RATE_THRESHOLD_DPS = 45.0f
        /** Multi-sample confirmation count for moderate/severe mismatch. */
        const val REQUIRED_CONFIRMATION_SAMPLES = 2
        /** Physical rate threshold defining a settled aircraft (8.0 deg/s). */
        const val SETTLED_RATE_THRESHOLD_DPS = 8.0f
        /** Number of consecutive new settled samples required before re-arm is permitted (3 samples). */
        const val REQUIRED_SETTLED_SAMPLES = 3
        /** Minimum duration that physical rate must remain settled before re-arm is permitted (150ms). */
        const val REQUIRED_SETTLED_DURATION_MILLIS = 150L
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
