package com.alonibh.tellodrone.domain

import kotlin.math.abs
import kotlin.math.roundToInt

enum class YawControllerPhase { HOLD, CORRECTING, SETTLING }

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
    YAW_RESPONSE_ANOMALY,
}

enum class YawControlSuppressionReason {
    NONE,
    GATE_BLOCKED,
    INVALID_MEASUREMENT,
    STALE_PERCEPTION,
    NONZERO_COMMAND_HOLD_EXPIRED,
    TARGET_JUMP_REJECTED,
    CENTER_CROSSING_BRAKE,
    STABLE_RESUME,
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
    /** Same monotonic domain as detector source timestamps (System.nanoTime in production). */
    val commandTimestampNanos: Long,
    val telemetryYawDegrees: Int? = null,
    val telemetryYawRateDegreesPerSecond: Float? = null,
    val rawYawRateDegreesPerSecond: Float? = null,
    val responseAnomalyDetected: Boolean = false,
)

data class YawControlOutcome(
    val frameSequence: Long? = null,
    val sourceTimestampNanos: Long? = null,
    val commandTimestampNanos: Long,
    val perceptionAgeMillis: Long? = null,
    val targetCenterX: Float? = null,
    val estimatedTargetCenterX: Float? = null,
    val targetCenterVelocityPerSecond: Float? = null,
    val predictionHorizonMillis: Long? = null,
    val predictionMode: TargetPredictionMode? = null,
    val rawYawError: Float? = null,
    val filteredYawError: Float? = null,
    val controlYawError: Float? = null,
    val phase: YawControllerPhase = YawControllerPhase.HOLD,
    val telloYawDegrees: Int? = null,
    val telloYawRateDegreesPerSecond: Float? = null,
    val rawYawRateDegreesPerSecond: Float? = null,
    val previousYawRc: Int = 0,
    val requestedYawRc: Int = 0,
    val safetyFilteredYawRc: Int = 0,
    val suppressionReason: YawControlSuppressionReason,
    /** Remaining capture-to-actuation budget; RC TTL is an additional independent bound. */
    val validForMillis: Long = 0L,
    val validityLimitedByCommandHold: Boolean = false,
) {
    val command: YawOnlyRcCommand get() = YawOnlyRcCommand(yaw = safetyFilteredYawRc)
}

data class YawFollowDecision(
    val state: YawFollowState = YawFollowState.DISARMED,
    val reason: YawFollowReason = YawFollowReason.EXPLICITLY_DISARMED,
    val yawRc: Int = 0,
    val control: YawControlOutcome? = null,
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

/**
 * Stateful fail-closed yaw controller between accepted tracking geometry and physical RC.
 * Bounded source-time prediction compensates only measured perception delay; identity decisions
 * remain entirely outside this controller.
 */
class ProductionYawController(
    private val nearProportionalGain: Float = NEAR_PROPORTIONAL_GAIN,
    private val mediumProportionalGain: Float = MEDIUM_PROPORTIONAL_GAIN,
    private val farProportionalGain: Float = FAR_PROPORTIONAL_GAIN,
    private val engageThreshold: Float = ENGAGE_THRESHOLD,
    private val releaseThreshold: Float = RELEASE_THRESHOLD,
    private val absoluteYawRcCap: Int = ABSOLUTE_YAW_RC_CAP,
    private val maximumAccelerationStep: Int = MAXIMUM_ACCELERATION_STEP,
    private val maximumBrakingStep: Int = MAXIMUM_BRAKING_STEP,
    private val maximumPerceptionAgeMillis: Long = MAXIMUM_PERCEPTION_AGE_MILLIS,
    private val maximumNonzeroCommandHoldMillis: Long = MAXIMUM_NONZERO_COMMAND_HOLD_MILLIS,
    private val maximumTargetCenterJump: Float = MAXIMUM_TARGET_CENTER_JUMP,
    private val maximumRawErrorJump: Float = MAXIMUM_RAW_ERROR_JUMP,
    private val stableResumeMeasurements: Int = STABLE_RESUME_MEASUREMENTS,
    private val settledYawRateThreshold: Float = SETTLED_YAW_RATE_THRESHOLD,
    private val settledConsecutiveSamples: Int = SETTLED_CONSECUTIVE_SAMPLES,
    private val fallbackSettlingDurationMillis: Long = FALLBACK_SETTLING_DURATION_MILLIS,
    private val fallbackSettlingMeasurements: Int = FALLBACK_SETTLING_MEASUREMENTS,
    private val estimator: YawTargetEstimator = YawTargetEstimator(),
) {
    private var lastFrameSequence: Long? = null
    private var lastSourceTimestampNanos: Long? = null
    private var lastTargetCenterX: Float? = null
    private var lastRawYawError: Float? = null
    private var lastFilteredYawError: Float? = null
    private var lastEstimate: YawTargetEstimate? = null
    private var lastMeasurementDecisionTimestampNanos: Long? = null
    private var lastYawRc = 0
    private var phase = YawControllerPhase.HOLD
    private var lastObservedTelemetryTimestampMillis: Long? = null
    private var consecutiveSettledSamples = 0
    private var settlingStartedTimestampNanos: Long? = null
    private var settlingMeasurementsCount = 0
    private var recovering = false
    private var consistentRecoveryMeasurements = 0
    private var staleGapSettlingRequired = false
    private var staleGapPreviousYaw = 0

    init {
        require(nearProportionalGain.isFinite() && nearProportionalGain > 0f)
        require(mediumProportionalGain >= nearProportionalGain)
        require(farProportionalGain >= mediumProportionalGain)
        require(releaseThreshold in 0f..<engageThreshold && engageThreshold < .5f)
        require(absoluteYawRcCap in 1..ABSOLUTE_YAW_RC_CAP)
        require(maximumAccelerationStep in 1..absoluteYawRcCap)
        require(maximumBrakingStep in maximumAccelerationStep..absoluteYawRcCap)
        require(maximumPerceptionAgeMillis > 0L)
        require(maximumNonzeroCommandHoldMillis in 1..maximumPerceptionAgeMillis)
        require(maximumTargetCenterJump.isFinite() && maximumTargetCenterJump > 0f)
        require(maximumRawErrorJump.isFinite() && maximumRawErrorJump > 0f)
        require(stableResumeMeasurements >= 1)
        require(settledYawRateThreshold.isFinite() && settledYawRateThreshold > 0f)
        require(settledConsecutiveSamples >= 1)
        require(fallbackSettlingDurationMillis > 0L)
        require(fallbackSettlingMeasurements >= 1)
    }

    /**
     * Updates telemetry yaw and yaw-rate settling state without re-evaluating perception
     * or resetting target estimation. Non-new/duplicate telemetry samples are ignored.
     */
    fun observeTelemetry(
        telemetryYawDegrees: Int? = null,
        telemetryYawRateDegreesPerSecond: Float? = null,
        telemetryTimestampMillis: Long,
        rawYawRateDegreesPerSecond: Float? = null,
    ) {
        val last = lastObservedTelemetryTimestampMillis
        if (last != null && telemetryTimestampMillis <= last) {
            return
        }
        lastObservedTelemetryTimestampMillis = telemetryTimestampMillis
        val rate = telemetryYawRateDegreesPerSecond ?: rawYawRateDegreesPerSecond
        if (rate != null) {
            if (abs(rate) <= settledYawRateThreshold) {
                consecutiveSettledSamples++
            } else {
                consecutiveSettledSamples = 0
            }
        }
    }

    fun command(
        errors: TrackingErrors?,
        commandTimestampNanos: Long,
        telemetryYawDegrees: Int? = null,
        telemetryYawRateDegreesPerSecond: Float? = null,
        rawYawRateDegreesPerSecond: Float? = null,
    ): YawControlOutcome {
        val measurement = Measurement.from(errors)
            ?: return suppress(errors, commandTimestampNanos, YawControlSuppressionReason.INVALID_MEASUREMENT, true)
        val ageNanos = commandTimestampNanos - measurement.sourceTimestampNanos
        val maximumAgeNanos = maximumPerceptionAgeMillis * NANOS_PER_MILLISECOND
        if (ageNanos !in 0 until maximumAgeNanos) {
            return suppressMeasurement(
                measurement,
                commandTimestampNanos,
                YawControlSuppressionReason.STALE_PERCEPTION,
                requireStable = true,
            )
        }

        val lastFrame = lastFrameSequence
        val lastSource = lastSourceTimestampNanos
        if (lastFrame != null && lastSource != null) {
            val sameMeasurement = measurement.frameSequence == lastFrame &&
                measurement.sourceTimestampNanos == lastSource
            if (sameMeasurement) {
                val heldForMillis = lastMeasurementDecisionTimestampNanos
                    ?.let { (commandTimestampNanos - it).coerceAtLeast(0L) / NANOS_PER_MILLISECOND }
                    ?: maximumNonzeroCommandHoldMillis
                if (lastYawRc != 0 && heldForMillis >= maximumNonzeroCommandHoldMillis) {
                    val estimate = lastEstimate ?: estimator.brake(
                        measurement.targetCenterX,
                        measurement.sourceTimestampNanos,
                    )
                    val requested = requestedYaw(estimate.estimatedCenterX - CENTER_X)
                    return outcome(
                        measurement,
                        commandTimestampNanos,
                        estimate = estimate,
                        phase = phase,
                        telloYawDegrees = telemetryYawDegrees,
                        telloYawRateDegreesPerSecond = telemetryYawRateDegreesPerSecond,
                        rawYawRateDegreesPerSecond = rawYawRateDegreesPerSecond,
                        previousYawRc = lastYawRc,
                        requestedYawRc = requested,
                        safetyFilteredYawRc = 0,
                        suppressionReason = YawControlSuppressionReason.NONZERO_COMMAND_HOLD_EXPIRED,
                        commandHeldForMillis = heldForMillis,
                    )
                }
                val estimate = lastEstimate ?: estimator.brake(
                    measurement.targetCenterX,
                    measurement.sourceTimestampNanos,
                )
                val requested = requestedYaw(estimate.estimatedCenterX - CENTER_X)
                return outcome(
                    measurement,
                    commandTimestampNanos,
                    estimate = estimate,
                    phase = phase,
                    telloYawDegrees = telemetryYawDegrees,
                    telloYawRateDegreesPerSecond = telemetryYawRateDegreesPerSecond,
                    rawYawRateDegreesPerSecond = rawYawRateDegreesPerSecond,
                    previousYawRc = lastYawRc,
                    requestedYawRc = requested,
                    safetyFilteredYawRc = if (phase == YawControllerPhase.SETTLING) 0 else lastYawRc,
                    suppressionReason = if (recovering) YawControlSuppressionReason.STABLE_RESUME else
                        YawControlSuppressionReason.NONE,
                    commandHeldForMillis = heldForMillis,
                )
            }
            if (measurement.frameSequence <= lastFrame || measurement.sourceTimestampNanos <= lastSource) {
                return suppressMeasurement(
                    measurement,
                    commandTimestampNanos,
                    YawControlSuppressionReason.INVALID_MEASUREMENT,
                    requireStable = true,
                )
            }
        }

        val targetJumped = lastTargetCenterX?.let { abs(measurement.targetCenterX - it) > maximumTargetCenterJump } == true ||
            lastRawYawError?.let { abs(measurement.rawYawError - it) > maximumRawErrorJump } == true
        if (targetJumped) {
            observe(measurement, estimator.brake(measurement.targetCenterX, measurement.sourceTimestampNanos), commandTimestampNanos)
            return suppressMeasurement(
                measurement,
                commandTimestampNanos,
                YawControlSuppressionReason.TARGET_JUMP_REJECTED,
                requireStable = true,
            )
        }

        var estimate = estimator.update(
            centerX = measurement.targetCenterX,
            sourceTimestampNanos = measurement.sourceTimestampNanos,
            perceptionAgeNanos = ageNanos,
            physicalYawRateDegreesPerSecond = telemetryYawRateDegreesPerSecond ?: rawYawRateDegreesPerSecond,
            isSettling = (phase == YawControllerPhase.SETTLING),
            isRecovery = recovering,
        )
        val controlError = estimate.estimatedCenterX - CENTER_X

        if (staleGapSettlingRequired) {
            val requested = requestedYaw(controlError)
            if (requested != 0 && staleGapPreviousYaw != 0 && requested.sign != staleGapPreviousYaw.sign) {
                val rate = telemetryYawRateDegreesPerSecond ?: rawYawRateDegreesPerSecond
                val isSettled = if (rate != null) {
                    consecutiveSettledSamples >= settledConsecutiveSamples
                } else {
                    false
                }
                if (!isSettled) {
                    phase = YawControllerPhase.SETTLING
                    settlingStartedTimestampNanos = commandTimestampNanos
                    settlingMeasurementsCount = 0
                    consecutiveSettledSamples = 0
                    staleGapSettlingRequired = false
                    staleGapPreviousYaw = 0
                    val brakedEstimate = estimator.brake(measurement.targetCenterX, measurement.sourceTimestampNanos)
                    observe(measurement, brakedEstimate, commandTimestampNanos)
                    if (recovering) {
                        consistentRecoveryMeasurements++
                        if (consistentRecoveryMeasurements >= stableResumeMeasurements) {
                            recovering = false
                            consistentRecoveryMeasurements = 0
                        }
                    }
                    lastYawRc = 0
                    return outcome(
                        measurement,
                        commandTimestampNanos,
                        estimate = brakedEstimate,
                        phase = YawControllerPhase.SETTLING,
                        telloYawDegrees = telemetryYawDegrees,
                        telloYawRateDegreesPerSecond = telemetryYawRateDegreesPerSecond,
                        rawYawRateDegreesPerSecond = rawYawRateDegreesPerSecond,
                        previousYawRc = 0,
                        requestedYawRc = requested,
                        safetyFilteredYawRc = 0,
                        suppressionReason = YawControlSuppressionReason.CENTER_CROSSING_BRAKE,
                    )
                }
            }
            staleGapSettlingRequired = false
            staleGapPreviousYaw = 0
        }

        if (phase == YawControllerPhase.SETTLING) {
            settlingMeasurementsCount++
            val rate = telemetryYawRateDegreesPerSecond ?: rawYawRateDegreesPerSecond
            val isSettled = if (rate != null) {
                consecutiveSettledSamples >= settledConsecutiveSamples
            } else {
                val settlingDurationMillis = settlingStartedTimestampNanos?.let {
                    (commandTimestampNanos - it).coerceAtLeast(0L) / NANOS_PER_MILLISECOND
                } ?: 0L
                settlingDurationMillis >= fallbackSettlingDurationMillis &&
                    settlingMeasurementsCount >= fallbackSettlingMeasurements
            }
            if (!isSettled) {
                val requested = requestedYaw(controlError)
                observe(measurement, estimate, commandTimestampNanos)
                if (recovering) {
                    consistentRecoveryMeasurements++
                    if (consistentRecoveryMeasurements >= stableResumeMeasurements) {
                        recovering = false
                        consistentRecoveryMeasurements = 0
                    }
                }
                lastYawRc = 0
                return outcome(
                    measurement,
                    commandTimestampNanos,
                    estimate = estimate,
                    phase = YawControllerPhase.SETTLING,
                    telloYawDegrees = telemetryYawDegrees,
                    telloYawRateDegreesPerSecond = telemetryYawRateDegreesPerSecond,
                    rawYawRateDegreesPerSecond = rawYawRateDegreesPerSecond,
                    previousYawRc = 0,
                    requestedYawRc = requested,
                    safetyFilteredYawRc = 0,
                    suppressionReason = YawControlSuppressionReason.CENTER_CROSSING_BRAKE,
                )
            }
            phase = if (abs(controlError) >= engageThreshold) {
                YawControllerPhase.CORRECTING
            } else {
                YawControllerPhase.HOLD
            }
        }

        if (abs(measurement.rawYawError) <= releaseThreshold) {
            val brakedEstimate = estimator.brake(measurement.targetCenterX, measurement.sourceTimestampNanos)
            val previousYaw = lastYawRc
            if (phase == YawControllerPhase.CORRECTING && previousYaw != 0) {
                phase = YawControllerPhase.SETTLING
                settlingStartedTimestampNanos = commandTimestampNanos
                settlingMeasurementsCount = 0
                consecutiveSettledSamples = 0
            } else {
                phase = YawControllerPhase.HOLD
            }
            observe(measurement, brakedEstimate, commandTimestampNanos)
            if (recovering) {
                consistentRecoveryMeasurements++
                if (consistentRecoveryMeasurements >= stableResumeMeasurements) {
                    recovering = false
                    consistentRecoveryMeasurements = 0
                }
            }
            lastYawRc = 0
            return outcome(
                measurement,
                commandTimestampNanos,
                estimate = brakedEstimate,
                phase = phase,
                telloYawDegrees = telemetryYawDegrees,
                telloYawRateDegreesPerSecond = telemetryYawRateDegreesPerSecond,
                rawYawRateDegreesPerSecond = rawYawRateDegreesPerSecond,
                previousYawRc = previousYaw,
                requestedYawRc = 0,
                safetyFilteredYawRc = 0,
                suppressionReason = YawControlSuppressionReason.NONE,
            )
        }

        val previousFiltered = lastFilteredYawError
        val previousYaw = lastYawRc
        if (phase == YawControllerPhase.HOLD && abs(controlError) < engageThreshold) {
            val brakedEstimate = estimator.brake(measurement.targetCenterX, measurement.sourceTimestampNanos)
            observe(measurement, brakedEstimate, commandTimestampNanos)
            if (recovering) {
                consistentRecoveryMeasurements++
                if (consistentRecoveryMeasurements >= stableResumeMeasurements) {
                    recovering = false
                    consistentRecoveryMeasurements = 0
                }
            }
            lastYawRc = 0
            return outcome(
                measurement,
                commandTimestampNanos,
                estimate = brakedEstimate,
                phase = YawControllerPhase.HOLD,
                telloYawDegrees = telemetryYawDegrees,
                telloYawRateDegreesPerSecond = telemetryYawRateDegreesPerSecond,
                rawYawRateDegreesPerSecond = rawYawRateDegreesPerSecond,
                previousYawRc = previousYaw,
                requestedYawRc = 0,
                safetyFilteredYawRc = 0,
                suppressionReason = YawControlSuppressionReason.NONE,
            )
        }

        val requested = requestedYaw(controlError)
        val rateForBrake = telemetryYawRateDegreesPerSecond ?: rawYawRateDegreesPerSecond
        val hasHighAngularVelocity = rateForBrake != null && abs(rateForBrake) >= HIGH_ANGULAR_VELOCITY_BRAKE_RATE
        val approachingCenterFast = previousYaw != 0 &&
            abs(measurement.rawYawError) <= RAPID_APPROACH_BRAKE_ERROR &&
            hasHighAngularVelocity && (previousYaw * rateForBrake > 0f)

        val crossedCenter = previousFiltered != null && previousYaw != 0 && (
            previousFiltered * measurement.rawYawError < 0f ||
                abs(previousFiltered) >= RAPID_APPROACH_START_ERROR &&
                abs(measurement.rawYawError) <= RAPID_APPROACH_BRAKE_ERROR ||
                approachingCenterFast
            )
        if (crossedCenter || (requested != 0 && previousYaw != 0 && requested.sign != previousYaw.sign)) {
            val brakedEstimate = estimator.brake(measurement.targetCenterX, measurement.sourceTimestampNanos)
            phase = YawControllerPhase.SETTLING
            settlingStartedTimestampNanos = commandTimestampNanos
            settlingMeasurementsCount = 0
            consecutiveSettledSamples = 0
            observe(measurement, brakedEstimate, commandTimestampNanos)
            lastYawRc = 0
            return outcome(
                measurement,
                commandTimestampNanos,
                estimate = brakedEstimate,
                phase = YawControllerPhase.SETTLING,
                telloYawDegrees = telemetryYawDegrees,
                telloYawRateDegreesPerSecond = telemetryYawRateDegreesPerSecond,
                rawYawRateDegreesPerSecond = rawYawRateDegreesPerSecond,
                previousYawRc = previousYaw,
                requestedYawRc = requested,
                safetyFilteredYawRc = 0,
                suppressionReason = YawControlSuppressionReason.CENTER_CROSSING_BRAKE,
            )
        }

        if (recovering) {
            observe(measurement, estimate, commandTimestampNanos)
            consistentRecoveryMeasurements++
            if (consistentRecoveryMeasurements < stableResumeMeasurements) {
                return outcome(
                    measurement,
                    commandTimestampNanos,
                    estimate = estimate,
                    phase = YawControllerPhase.HOLD,
                    telloYawDegrees = telemetryYawDegrees,
                    telloYawRateDegreesPerSecond = telemetryYawRateDegreesPerSecond,
                    previousYawRc = 0,
                    requestedYawRc = requested,
                    safetyFilteredYawRc = 0,
                    suppressionReason = YawControlSuppressionReason.STABLE_RESUME,
                )
            }
            recovering = false
            consistentRecoveryMeasurements = 0
        }

        val safetyYaw = slew(previousYaw, requested)
        if (safetyYaw == 0) {
            phase = if (previousYaw != 0) YawControllerPhase.SETTLING else YawControllerPhase.HOLD
            if (phase == YawControllerPhase.SETTLING) {
                settlingStartedTimestampNanos = commandTimestampNanos
                settlingMeasurementsCount = 0
                consecutiveSettledSamples = 0
            }
        } else {
            phase = YawControllerPhase.CORRECTING
        }
        lastYawRc = safetyYaw
        observe(measurement, estimate, commandTimestampNanos)
        return outcome(
            measurement,
            commandTimestampNanos,
            estimate = estimate,
            phase = phase,
            telloYawDegrees = telemetryYawDegrees,
            telloYawRateDegreesPerSecond = telemetryYawRateDegreesPerSecond,
            previousYawRc = previousYaw,
            requestedYawRc = requested,
            safetyFilteredYawRc = safetyYaw,
            suppressionReason = YawControlSuppressionReason.NONE,
        )
    }

    fun suppress(
        errors: TrackingErrors?,
        commandTimestampNanos: Long,
        reason: YawControlSuppressionReason = YawControlSuppressionReason.GATE_BLOCKED,
        requireStable: Boolean = false,
    ): YawControlOutcome {
        val measurement = Measurement.from(errors)
        return if (measurement == null) {
            val previous = lastYawRc
            if (previous != 0) {
                staleGapSettlingRequired = true
                staleGapPreviousYaw = previous
            }
            lastYawRc = 0
            phase = YawControllerPhase.HOLD
            consecutiveSettledSamples = 0
            settlingStartedTimestampNanos = null
            settlingMeasurementsCount = 0
            estimator.reset()
            lastEstimate = null
            lastMeasurementDecisionTimestampNanos = null
            if (requireStable) startRecovery()
            YawControlOutcome(
                commandTimestampNanos = commandTimestampNanos,
                phase = YawControllerPhase.HOLD,
                previousYawRc = previous,
                suppressionReason = reason,
            )
        } else {
            suppressMeasurement(measurement, commandTimestampNanos, reason, requireStable)
        }
    }

    fun reset() {
        lastFrameSequence = null
        lastSourceTimestampNanos = null
        lastTargetCenterX = null
        lastRawYawError = null
        lastFilteredYawError = null
        lastEstimate = null
        lastMeasurementDecisionTimestampNanos = null
        lastYawRc = 0
        phase = YawControllerPhase.HOLD
        lastObservedTelemetryTimestampMillis = null
        consecutiveSettledSamples = 0
        settlingStartedTimestampNanos = null
        settlingMeasurementsCount = 0
        recovering = false
        consistentRecoveryMeasurements = 0
        staleGapSettlingRequired = false
        staleGapPreviousYaw = 0
        estimator.reset()
    }

    fun currentYawRc(): Int = lastYawRc

    internal fun settledTelemetrySampleCount(): Int = consecutiveSettledSamples

    private fun suppressMeasurement(
        measurement: Measurement,
        commandTimestampNanos: Long,
        reason: YawControlSuppressionReason,
        requireStable: Boolean,
        requestedYawRc: Int = 0,
    ): YawControlOutcome {
        val previous = lastYawRc
        if (previous != 0) {
            staleGapSettlingRequired = true
            staleGapPreviousYaw = previous
        }
        lastYawRc = 0
        phase = YawControllerPhase.HOLD
        consecutiveSettledSamples = 0
        settlingStartedTimestampNanos = null
        settlingMeasurementsCount = 0
        estimator.reset()
        val estimate = lastEstimate
        lastEstimate = null
        lastMeasurementDecisionTimestampNanos = null
        if (requireStable) startRecovery()
        return outcome(
            measurement = measurement,
            commandTimestampNanos = commandTimestampNanos,
            estimate = estimate,
            phase = YawControllerPhase.HOLD,
            previousYawRc = previous,
            requestedYawRc = requestedYawRc,
            safetyFilteredYawRc = 0,
            suppressionReason = reason,
        )
    }

    private fun startRecovery() {
        recovering = true
        consistentRecoveryMeasurements = 0
    }

    private fun observe(
        measurement: Measurement,
        estimate: YawTargetEstimate,
        commandTimestampNanos: Long,
    ) {
        lastFrameSequence = measurement.frameSequence
        lastSourceTimestampNanos = measurement.sourceTimestampNanos
        lastTargetCenterX = measurement.targetCenterX
        lastRawYawError = measurement.rawYawError
        lastFilteredYawError = estimate.estimatedCenterX - CENTER_X
        lastEstimate = estimate
        lastMeasurementDecisionTimestampNanos = commandTimestampNanos
    }

    private fun requestedYaw(error: Float): Int {
        val gain = when {
            abs(error) < MEDIUM_ERROR_THRESHOLD -> nearProportionalGain
            abs(error) < FAR_ERROR_THRESHOLD -> mediumProportionalGain
            else -> farProportionalGain
        }
        return (error * gain).roundToInt().coerceIn(-absoluteYawRcCap, absoluteYawRcCap)
    }

    private fun slew(previous: Int, requested: Int): Int = when {
        requested == 0 -> 0
        previous == 0 -> requested.coerceIn(-maximumAccelerationStep, maximumAccelerationStep)
        requested.sign != previous.sign -> 0
        abs(requested) >= abs(previous) -> requested.coerceIn(
            previous - maximumAccelerationStep,
            previous + maximumAccelerationStep,
        )
        else -> requested.coerceIn(previous - maximumBrakingStep, previous + maximumBrakingStep)
    }

    private fun outcome(
        measurement: Measurement,
        commandTimestampNanos: Long,
        estimate: YawTargetEstimate? = lastEstimate,
        phase: YawControllerPhase = this.phase,
        telloYawDegrees: Int? = null,
        telloYawRateDegreesPerSecond: Float? = null,
        rawYawRateDegreesPerSecond: Float? = null,
        previousYawRc: Int,
        requestedYawRc: Int,
        safetyFilteredYawRc: Int,
        suppressionReason: YawControlSuppressionReason,
        commandHeldForMillis: Long = 0L,
    ): YawControlOutcome {
        val ageNanos = commandTimestampNanos - measurement.sourceTimestampNanos
        val remainingNanos = maximumPerceptionAgeMillis * NANOS_PER_MILLISECOND - ageNanos
        val perceptionRemainingMillis = (remainingNanos / NANOS_PER_MILLISECOND).coerceAtLeast(0L)
        val commandHoldRemainingMillis = if (safetyFilteredYawRc == 0) Long.MAX_VALUE else {
            (maximumNonzeroCommandHoldMillis - commandHeldForMillis).coerceAtLeast(0L)
        }
        val limitedByCommandHold = commandHoldRemainingMillis < perceptionRemainingMillis
        return YawControlOutcome(
            frameSequence = measurement.frameSequence,
            sourceTimestampNanos = measurement.sourceTimestampNanos,
            commandTimestampNanos = commandTimestampNanos,
            perceptionAgeMillis = if (ageNanos >= 0L) ageNanos / NANOS_PER_MILLISECOND else null,
            targetCenterX = measurement.targetCenterX,
            estimatedTargetCenterX = estimate?.estimatedCenterX,
            targetCenterVelocityPerSecond = estimate?.velocityPerSecond,
            predictionHorizonMillis = estimate?.predictionHorizonMillis,
            predictionMode = estimate?.predictionMode,
            rawYawError = measurement.rawYawError,
            filteredYawError = estimate?.let { it.estimatedCenterX - CENTER_X } ?: measurement.filteredYawError,
            controlYawError = estimate?.let { it.estimatedCenterX - CENTER_X } ?: measurement.filteredYawError,
            phase = phase,
            telloYawDegrees = telloYawDegrees,
            telloYawRateDegreesPerSecond = telloYawRateDegreesPerSecond,
            rawYawRateDegreesPerSecond = rawYawRateDegreesPerSecond,
            previousYawRc = previousYawRc,
            requestedYawRc = requestedYawRc,
            safetyFilteredYawRc = safetyFilteredYawRc,
            suppressionReason = suppressionReason,
            validForMillis = minOf(perceptionRemainingMillis, commandHoldRemainingMillis),
            validityLimitedByCommandHold = limitedByCommandHold,
        )
    }

    private data class Measurement(
        val frameSequence: Long,
        val sourceTimestampNanos: Long,
        val targetCenterX: Float,
        val rawYawError: Float,
        val filteredYawError: Float,
    ) {
        companion object {
            fun from(errors: TrackingErrors?): Measurement? {
                if (errors?.targetPresent != true || !errors.targetFresh) return null
                val frame = errors.measurementFrameSequence ?: return null
                val source = errors.measurementSourceTimestampNanos ?: return null
                val center = errors.targetCenterX ?: return null
                if (frame < 0L || source < 0L || !center.isFinite() || center !in 0f..1f ||
                    !errors.rawYawError.isFinite() || !errors.yawError.isFinite()
                ) return null
                return Measurement(frame, source, center, errors.rawYawError, errors.yawError)
            }
        }
    }

    private val Int.sign: Int get() = when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }

    companion object {
        const val ENGAGE_THRESHOLD = .065f
        const val RELEASE_THRESHOLD = .035f
        const val NEAR_PROPORTIONAL_GAIN = 78f
        const val MEDIUM_PROPORTIONAL_GAIN = 92f
        const val FAR_PROPORTIONAL_GAIN = 108f
        const val ABSOLUTE_YAW_RC_CAP = 28
        const val MAXIMUM_ACCELERATION_STEP = 8
        const val MAXIMUM_BRAKING_STEP = 20
        const val MAXIMUM_PERCEPTION_AGE_MILLIS = 450L
        const val MAXIMUM_NONZERO_COMMAND_HOLD_MILLIS = 200L
        const val MAXIMUM_TARGET_CENTER_JUMP = 0.18f
        const val MAXIMUM_RAW_ERROR_JUMP = 0.18f
        const val STABLE_RESUME_MEASUREMENTS = 2
        const val SETTLED_YAW_RATE_THRESHOLD = 8.0f
        const val SETTLED_CONSECUTIVE_SAMPLES = 2
        const val FALLBACK_SETTLING_DURATION_MILLIS = 200L
        const val FALLBACK_SETTLING_MEASUREMENTS = 2
        private const val CENTER_X = .5f
        private const val MEDIUM_ERROR_THRESHOLD = .12f
        private const val FAR_ERROR_THRESHOLD = .24f
        private const val RAPID_APPROACH_START_ERROR = 0.12f
        private const val RAPID_APPROACH_BRAKE_ERROR = 0.06f
        private const val HIGH_ANGULAR_VELOCITY_BRAKE_RATE = 20.0f
        private const val NANOS_PER_MILLISECOND = 1_000_000L
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
        controller.reset()
        state = YawFollowState.ARMED_WAITING
        return evaluateArmed(input)
    }

    fun disarm(): YawFollowDecision {
        controller.reset()
        state = YawFollowState.DISARMED
        reason = YawFollowReason.EXPLICITLY_DISARMED
        return decision()
    }

    fun preempt(preemption: YawFollowReason): YawFollowDecision {
        require(preemption in LATCHING_REASONS)
        controller.reset()
        if (state != YawFollowState.DISARMED) {
            state = YawFollowState.REQUIRES_REARM
            reason = preemption
        }
        return decision()
    }

    fun evaluate(input: YawFollowInput): YawFollowDecision = processFreshPerception(input)

    fun processFreshPerception(input: YawFollowInput): YawFollowDecision {
        if (state == YawFollowState.DISARMED || state == YawFollowState.REQUIRES_REARM) {
            return decision(input)
        }
        return evaluateArmed(input)
    }

    fun observeTelemetry(
        telemetryYawDegrees: Int? = null,
        telemetryYawRateDegreesPerSecond: Float? = null,
        telemetryTimestampMillis: Long,
        rawYawRateDegreesPerSecond: Float? = null,
    ) {
        controller.observeTelemetry(
            telemetryYawDegrees = telemetryYawDegrees,
            telemetryYawRateDegreesPerSecond = telemetryYawRateDegreesPerSecond,
            telemetryTimestampMillis = telemetryTimestampMillis,
            rawYawRateDegreesPerSecond = rawYawRateDegreesPerSecond,
        )
    }

    fun evaluateSafetyGate(input: YawFollowInput): YawFollowDecision {
        if (state == YawFollowState.DISARMED || state == YawFollowState.REQUIRES_REARM) {
            return decision()
        }
        val newReason = blockingReason(input)
        if (newReason in LATCHING_REASONS) {
            controller.reset()
            state = YawFollowState.REQUIRES_REARM
            reason = newReason
            return decision()
        }
        if (newReason != YawFollowReason.ACTIVE && state == YawFollowState.ACTIVE) {
            state = YawFollowState.ARMED_WAITING
            reason = newReason
            return decision(input)
        }
        return YawFollowDecision(
            state = state,
            reason = reason,
            yawRc = if (state == YawFollowState.ACTIVE) controller.currentYawRc() else 0,
            control = null,
        )
    }

    private fun evaluateArmed(input: YawFollowInput): YawFollowDecision {
        reason = blockingReason(input)
        state = when {
            reason in LATCHING_REASONS -> YawFollowState.REQUIRES_REARM
            reason == YawFollowReason.ACTIVE -> YawFollowState.ACTIVE
            else -> YawFollowState.ARMED_WAITING
        }
        return decision(input)
    }

    private fun decision(input: YawFollowInput? = null): YawFollowDecision {
        val control = input?.let {
            if (state == YawFollowState.ACTIVE) {
                controller.command(
                    errors = it.errors,
                    commandTimestampNanos = it.commandTimestampNanos,
                    telemetryYawDegrees = it.telemetryYawDegrees,
                    telemetryYawRateDegreesPerSecond = it.telemetryYawRateDegreesPerSecond,
                    rawYawRateDegreesPerSecond = it.rawYawRateDegreesPerSecond,
                )
            } else {
                controller.suppress(
                    errors = it.errors,
                    commandTimestampNanos = it.commandTimestampNanos,
                    requireStable = reason in RESUMABLE_INTERRUPTION_REASONS,
                )
            }
        }
        return YawFollowDecision(
            state = state,
            reason = reason,
            yawRc = if (state == YawFollowState.ACTIVE) control?.safetyFilteredYawRc ?: 0 else 0,
            control = control,
        )
    }

    private fun blockingReason(input: YawFollowInput): YawFollowReason = when {
        input.responseAnomalyDetected -> YawFollowReason.YAW_RESPONSE_ANOMALY
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
        private val RESUMABLE_INTERRUPTION_REASONS = setOf(
            YawFollowReason.TARGET_TEMPORARILY_MISSING,
            YawFollowReason.TRACKING_STALE,
        )
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
            YawFollowReason.YAW_RESPONSE_ANOMALY,
        )
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
