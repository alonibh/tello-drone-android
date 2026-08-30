package com.alonibh.tellodrone.tello

import com.alonibh.tellodrone.domain.ControlAuthority
import com.alonibh.tellodrone.domain.DryRunFollowPlanner
import com.alonibh.tellodrone.domain.FollowPlannerConfig
import com.alonibh.tellodrone.domain.FollowDistanceCalibrator
import com.alonibh.tellodrone.domain.FollowDistanceCalibrationState
import com.alonibh.tellodrone.domain.FollowDistanceEligibility
import com.alonibh.tellodrone.domain.FollowDistanceEligibilityReason
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.NetworkSelectionState
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.domain.RcSpeedMode
import com.alonibh.tellodrone.domain.TargetAssociationEngine
import com.alonibh.tellodrone.domain.TargetAssociationDiagnostics
import com.alonibh.tellodrone.domain.TargetAssociationResult
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.TargetSelection
import com.alonibh.tellodrone.domain.TelemetrySnapshot
import com.alonibh.tellodrone.domain.TrackingErrorEngine
import com.alonibh.tellodrone.domain.TrackingMode
import kotlin.math.roundToInt
import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.VideoState
import com.alonibh.tellodrone.domain.YawFollowDecision
import com.alonibh.tellodrone.domain.YawFollowGate
import com.alonibh.tellodrone.domain.YawFollowInput
import com.alonibh.tellodrone.domain.YawFollowReason
import com.alonibh.tellodrone.domain.YawFollowState
import com.alonibh.tellodrone.domain.YawControlSuppressionReason
import com.alonibh.tellodrone.domain.withPersonDetectionVideoState
import com.alonibh.tellodrone.domain.isZero
import com.alonibh.tellodrone.vision.PersonDetectionStore
import com.alonibh.tellodrone.vision.NoOpVisionTraceRecorder
import com.alonibh.tellodrone.vision.VisionTraceFrame
import com.alonibh.tellodrone.vision.VisionTraceRecorder
import com.alonibh.tellodrone.vision.YawControlMeasurementTrace
import com.alonibh.tellodrone.vision.RcPublicationTrace
import com.alonibh.tellodrone.vision.SdkCommandCategory
import com.alonibh.tellodrone.vision.SdkCommandTrace
import com.alonibh.tellodrone.domain.TargetSelectionPoint
import com.alonibh.tellodrone.domain.TargetSelectionAttemptResult
import com.alonibh.tellodrone.vision.TargetSelectionAttemptTrace
import com.alonibh.tellodrone.vision.FlightStateTransitionTrace
import com.alonibh.tellodrone.domain.YawResponseSafetyMonitor
import com.alonibh.tellodrone.domain.TelemetryYawSample
import com.alonibh.tellodrone.domain.YawResponseSafetyStatus
import com.alonibh.tellodrone.vision.TelemetrySampleTrace
import com.alonibh.tellodrone.vision.YawResponseAnomalyEventTrace
import com.alonibh.tellodrone.vision.ExternalGroundingTrace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Stateful SDK session. Android service lifecycle and Wi-Fi selection live outside this class. */
class TelloFlightSession(
    private val transport: TelloTransport,
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    private val video: TelloVideoController? = null,
    private val sourceNowNanos: () -> Long = System::nanoTime,
    private val onFatalConnectionLoss: (String) -> Unit = {},
    initialState: DroneSessionState = DroneSessionState(
        connection = DroneConnectionState.Connecting,
        networkSelection = NetworkSelectionState.Available,
        flight = FlightState.Unknown,
    ),
    private val visionTrace: VisionTraceRecorder = NoOpVisionTraceRecorder,
    /** Test-only interleaving point immediately before a yaw-owned state commit. */
    private val beforeYawFollowStateCommit: ((MutableStateFlow<DroneSessionState>) -> Unit)? = null,
) {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<DroneSessionState> = mutableState.asStateFlow()

    private val commandStateMutex = Mutex()
    private val resourceCloseMutex = Mutex()
    private val firstTelemetry = CompletableDeferred<TelloTelemetry>()
    private var telemetryJob: Job? = null
    private var healthJob: Job? = null
    private var keepaliveJob: Job? = null
    private var videoStateJob: Job? = null
    private var groundedSampleCount = 0
    private var firstGroundedSampleAtMillis: Long? = null
    @Volatile private var lastTelemetryAtMillis: Long? = null
    private var lastTelemetryYawDegrees: Int? = null
    private var lastTelemetryYawTimestampMillis: Long? = null
    private var currentYawRateDegreesPerSecond: Float? = null
    @Volatile private var closed = false
    private val fatalReportLock = Any()
    private var fatalReported = false
    @Volatile private var takeoffAcknowledged = false
    @Volatile private var landingAcknowledged = false
    @Volatile private var videoStreamAcknowledged = false
    private val manualInputLock = Any()
    private var manualInputRequiresNeutral = false
    private val trackingLock = Any()
    private val targetAssociation = TargetAssociationEngine()
    private val trackingErrors = TrackingErrorEngine()
    /** Existing dry-run diagnostic values only; this feature never produces RC input. */
    private val dryRunPlanner = DryRunFollowPlanner(FollowPlannerConfig.LEGACY_DIAGNOSTIC)
    private var latestAcceptedDetectorFrame: DetectorFrameIdentity? = null
    private var lastPlannerFrameTimestampNanos: Long? = null
    private val distanceCalibrator = FollowDistanceCalibrator()
    private val yawFollowLock = Any()
    private val yawFollowGate = YawFollowGate()
    private val yawRateFilter = TelemetryYawRateFilter()
    private val yawResponseSafetyMonitor = YawResponseSafetyMonitor()
    private var telemetrySequence = 0L
    private var currentRawYawRateDegreesPerSecond: Float? = null
    private var yawFollowGeneration: Long? = null
    private var takeoffStabilizationSamples = 0
    private var firstTakeoffStabilizationAtMillis: Long? = null
    private val takeoffStabilizationHeights = mutableListOf<Float>()

    private val rcLoop = RcControlLoop(
        scope = scope,
        sender = transport::sendRc,
        clock = clock,
        onSendFailure = { error -> scope.launch { failConnection("RC transport failed: ${error.safeMessage()}") } },
        traceClockNanos = sourceNowNanos,
        onRcSent = ::recordRcPublication,
        authorityValidator = { kind, _ ->
            val current = mutableState.value
            when (kind) {
                RcInputKind.AUTONOMOUS_YAW -> AutonomousRcSendAuthority.validate(
                    current,
                    video?.state?.value?.availability ?: current.video.availability,
                )
                RcInputKind.MANUAL -> {
                    if (current.flight != FlightState.Flying &&
                        current.flight != FlightState.TakingOff &&
                        current.flight != FlightState.Landing
                    ) {
                        RcSendSuppressionReason.FLIGHT_STATE_INACTIVE
                    } else null
                }
                RcInputKind.SAFETY_ZERO -> null
            }
        },
    )

    suspend fun connect(): Boolean = commandStateMutex.withLock {
        if (closed) return@withLock false
        visionTrace.startNewSession()
        resetRealTracking()
        val yawDecision = resetYawFollowForNewSession()
        takeoffAcknowledged = false
        landingAcknowledged = false
        takeoffStabilizationSamples = 0
        firstTakeoffStabilizationAtMillis = null
        takeoffStabilizationHeights.clear()
        videoStreamAcknowledged = false
        mutableState.update {
            it.copy(
                connection = DroneConnectionState.Connecting,
                networkSelection = NetworkSelectionState.Available,
                flight = FlightState.Unknown,
                authority = ControlAuthority.Manual,
                tracking = TrackingMode.Off,
                video = VideoState(),
                personDetections = emptyList(),
                target = null,
                yawFollowDecision = yawDecision,
                followDistanceReference = null,
                followDistanceCalibrationState = FollowDistanceCalibrationState.NotSet,
                lastMessage = "Tello Wi-Fi selected; entering SDK mode",
            )
        }
        startTelemetryCollection()
        when (val result = sendSdkCommand("command", SdkCommandCategory.CONNECT, COMMAND_MODE_TIMEOUT_MILLIS)) {
            is TelloCommandResult.Success -> Unit
            else -> {
                failConnection("Could not enter Tello SDK mode: ${result.description()}")
                return@withLock false
            }
        }
        val first = withTimeoutOrNull(FIRST_TELEMETRY_TIMEOUT_MILLIS) { firstTelemetry.await() }
        if (first == null) {
            failConnection("SDK mode acknowledged, but no Tello telemetry was received")
            return@withLock false
        }
        if (closed || mutableState.value.connection == DroneConnectionState.Error) return@withLock false

        val grounded = first.isVerifiedGrounded()
        mutableState.update {
            it.copy(
                connection = DroneConnectionState.Connected,
                flight = if (grounded) FlightState.Grounded else FlightState.Unknown,
                telemetry = first.asSnapshot(isFresh = true),
                lastMessage = if (grounded) "Tello connected and telemetry verified"
                else "Tello connected, but airborne state is uncertain; land before normal commands",
            )
        }
        visionTrace.recordFlightStateTransition(
            FlightStateTransitionTrace(
                timestampMillis = clock.nowMillis(),
                fromState = FlightState.Unknown.name,
                toState = if (grounded) FlightState.Grounded.name else FlightState.Unknown.name,
                triggerReason = if (grounded) "Tello connected and telemetry verified" else "Tello connected, airborne state uncertain",
                batteryPercent = first.batteryPercent,
                heightMeters = first.heightMeters,
            ),
        )
        rcLoop.setHealthy(true)
        rcLoop.start()
        startHealthMonitor()
        startVideoStateCollection()
        startVideoStreaming()
        true
    }

    suspend fun takeOff() = commandStateMutex.withLock {
        val current = mutableState.value
        if (current.connection != DroneConnectionState.Connected || current.flight != FlightState.Grounded || !current.telemetry.isFresh) {
            return@withLock invalid("Takeoff requires fresh telemetry from a connected, grounded drone")
        }
        val battery = current.telemetry.batteryPercent
        if (battery == null) {
            return@withLock invalid("Takeoff blocked: battery level unknown")
        }
        if (battery < MINIMUM_TAKEOFF_BATTERY_PERCENT) {
            return@withLock invalid("Takeoff blocked: battery ($battery%) below $MINIMUM_TAKEOFF_BATTERY_PERCENT% minimum")
        }
        takeoffAcknowledged = false
        landingAcknowledged = false
        takeoffStabilizationSamples = 0
        firstTakeoffStabilizationAtMillis = null
        takeoffStabilizationHeights.clear()
        rcLoop.setEnabled(false)
        requireManualNeutral()
        mutableState.update {
            it.copy(flight = FlightState.TakingOff, manualVector = ManualControlVector(), hoverActive = false, lastMessage = "Takeoff in progress")
        }
        visionTrace.recordFlightStateTransition(
            FlightStateTransitionTrace(
                timestampMillis = clock.nowMillis(),
                fromState = FlightState.Grounded.name,
                toState = FlightState.TakingOff.name,
                triggerReason = "Takeoff in progress",
                batteryPercent = battery,
                heightMeters = current.telemetry.heightMeters,
            ),
        )
        when (val result = sendSdkCommand("takeoff", SdkCommandCategory.TAKEOFF, FLIGHT_COMMAND_TIMEOUT_MILLIS)) {
            is TelloCommandResult.Success -> {
                takeoffAcknowledged = true
                mutableState.update { state ->
                    if (state.connection == DroneConnectionState.Connected && state.flight == FlightState.TakingOff) {
                        state.copy(lastMessage = "Takeoff acknowledged; waiting for airborne telemetry")
                    } else state
                }
            }
            is TelloCommandResult.Rejected -> {
                mutableState.update { state ->
                    if (state.flight == FlightState.TakingOff) {
                        state.copy(flight = FlightState.Grounded, lastMessage = "Takeoff rejected: ${result.response}")
                    } else state
                }
                visionTrace.recordFlightStateTransition(
                    FlightStateTransitionTrace(
                        timestampMillis = clock.nowMillis(),
                        fromState = FlightState.TakingOff.name,
                        toState = FlightState.Grounded.name,
                        triggerReason = "Takeoff rejected: ${result.response}",
                        batteryPercent = current.telemetry.batteryPercent,
                        heightMeters = current.telemetry.heightMeters,
                    ),
                )
            }
            else -> failConnection("Takeoff result is uncertain: ${result.description()}")
        }
    }

    suspend fun land() = commandStateMutex.withLock {
        val current = mutableState.value
        if (current.connection != DroneConnectionState.Connected ||
            current.flight !in setOf(FlightState.Flying, FlightState.Unknown)
        ) return@withLock invalid("Land requires a connected flying or uncertain-state drone")

        takeoffAcknowledged = false
        landingAcknowledged = false
        stopKeepalive()
        requireManualNeutral()
        latchYawFollow(YawFollowReason.LANDING)
        rcLoop.clearAndSendZero()
        rcLoop.setEnabled(false)
        stopDetectionAndClearTracking()
        mutableState.update {
            it.copy(
                flight = FlightState.Landing,
                manualVector = ManualControlVector(),
                hoverActive = false,
                lastMessage = "Landing in progress",
            )
        }
        visionTrace.recordFlightStateTransition(
            FlightStateTransitionTrace(
                timestampMillis = clock.nowMillis(),
                fromState = current.flight.name,
                toState = FlightState.Landing.name,
                triggerReason = "Landing in progress",
                batteryPercent = current.telemetry.batteryPercent,
                heightMeters = current.telemetry.heightMeters,
            ),
        )
        when (val result = sendSdkCommand("land", SdkCommandCategory.LAND, FLIGHT_COMMAND_TIMEOUT_MILLIS)) {
            is TelloCommandResult.Success -> {
                landingAcknowledged = true
                mutableState.update { state ->
                    if (state.connection == DroneConnectionState.Connected && state.flight == FlightState.Landing) {
                        state.copy(lastMessage = "Landing acknowledged; waiting for grounded telemetry")
                    } else state
                }
            }
            is TelloCommandResult.Rejected -> mutableState.update { state ->
                if (state.flight == FlightState.Landing) {
                    state.copy(
                        flight = FlightState.Unknown,
                        lastMessage = "Landing rejected; aircraft state is uncertain: ${result.response}",
                    )
                } else state
            }
            else -> failConnection("Landing result is uncertain: ${result.description()}")
        }
    }

    suspend fun stopAndHover() = commandStateMutex.withLock {
        val current = mutableState.value
        if (current.connection != DroneConnectionState.Connected || current.flight != FlightState.Flying) {
            invalid("STOP / HOVER requires a connected flying drone")
            return@withLock
        }
        requireManualNeutral()
        latchYawFollow(YawFollowReason.HOVER_INTERVENTION)
        rcLoop.clearAndSendZero()
        mutableState.update { state ->
            if (state.connection == DroneConnectionState.Connected && state.flight == FlightState.Flying) {
                state.copy(
                    authority = ControlAuthority.Manual,
                    manualVector = ManualControlVector(),
                    hoverActive = true,
                    lastMessage = "STOP / HOVER: zero movement sent; aircraft remains flying",
                )
            } else state
        }
    }

    suspend fun emergencyMotorKill() = commandStateMutex.withLock {
        val current = mutableState.value
        if (current.connection != DroneConnectionState.Connected ||
            current.flight !in setOf(FlightState.TakingOff, FlightState.Flying, FlightState.Landing, FlightState.Unknown)
        ) return@withLock invalid("Emergency motor kill is unavailable while safely grounded or disconnected")

        takeoffAcknowledged = false
        landingAcknowledged = false
        stopKeepalive()
        requireManualNeutral()
        latchYawFollow(YawFollowReason.EMERGENCY)
        rcLoop.lockOutAfterZero()
        stopDetectionAndClearTracking()
        mutableState.update {
            it.withTrackingCleared().copy(
                flight = FlightState.Emergency,
                authority = ControlAuthority.Manual,
                manualVector = ManualControlVector(),
                hoverActive = false,
                lastMessage = "EMERGENCY MOTOR KILL sent; further flight commands are locked out",
            )
        }
        visionTrace.recordFlightStateTransition(
            FlightStateTransitionTrace(
                timestampMillis = clock.nowMillis(),
                fromState = current.flight.name,
                toState = FlightState.Emergency.name,
                triggerReason = "EMERGENCY MOTOR KILL sent",
                batteryPercent = current.telemetry.batteryPercent,
                heightMeters = current.telemetry.heightMeters,
            ),
        )
        // Emergency remains terminal even when its acknowledgement is lost.
        sendSdkCommand("emergency", SdkCommandCategory.EMERGENCY, EMERGENCY_TIMEOUT_MILLIS)
    }

    fun publishManualControl(vector: ManualControlVector) {
        if (requiresNeutralInput(vector)) {
            // Preserve the neutral interlock for manual RC, but never let it leave yaw autonomy
            // active after the pilot has made a non-zero control attempt.
            if (!vector.isZero()) latchYawFollowAndSendZero(YawFollowReason.MANUAL_OVERRIDE)
            return
        }
        val current = mutableState.value
        if (current.connection == DroneConnectionState.Connected && current.flight == FlightState.Flying && current.telemetry.isFresh) {
            synchronized(yawFollowLock) {
                val state = mutableState.value
                if (state.connection != DroneConnectionState.Connected || state.flight != FlightState.Flying || !state.telemetry.isFresh) {
                    return@synchronized
                }
                if (!vector.isZero()) {
                    // RC invalidation and gate latching share this lock with every autonomous publish.
                    rcLoop.publish(vector, RcSpeedMode.fromPercent(state.speedPercent).rcMagnitude)
                    yawFollowGeneration = null
                    val decision = yawFollowGate.preempt(YawFollowReason.MANUAL_OVERRIDE)
                    updateYawFollowState {
                        it.copy(
                            authority = ControlAuthority.Manual,
                            manualVector = vector,
                            hoverActive = false,
                            yawFollowDecision = decision,
                        )
                    }
                } else if (state.yawFollowDecision.state != YawFollowState.ACTIVE) {
                    rcLoop.publish(vector, RcSpeedMode.fromPercent(state.speedPercent).rcMagnitude)
                    updateYawFollowState { it.copy(manualVector = vector) }
                }
            }
        }
    }

    fun setYawFollowArmed(armed: Boolean) {
        var zeroGeneration: Long? = null
        synchronized(yawFollowLock) {
            if (armed && !mutableState.value.canRequestYawFollowArm()) {
                invalid("Yaw follow requires connected Flying state, fresh telemetry, live detection, and a valid selected target")
                return@synchronized
            }
            if (armed) {
                if (yawResponseSafetyMonitor.isLatched()) {
                    if (!yawResponseSafetyMonitor.tryAcknowledgeAndResetForRearm()) {
                        invalid("Yaw follow cannot re-arm until aircraft rotation has settled")
                        mutableState.update { it.copy(lastMessage = "Yaw follow cannot re-arm until aircraft rotation has settled") }
                        return@synchronized
                    }
                    visionTrace.recordYawResponseAnomalyEvent(
                        YawResponseAnomalyEventTrace(
                            timestampNanos = sourceNowNanos(),
                            eventType = "yaw_response_anomaly_acknowledged",
                            rawYawRate = currentRawYawRateDegreesPerSecond,
                            filteredYawRate = currentYawRateDegreesPerSecond,
                            currentYawDegrees = mutableState.value.telemetry.yawDegrees,
                            recentActualYawRcSummary = null,
                            ageOfMostRecentNonzeroRcMillis = null,
                            recentRcSign = null,
                            controllerPhase = mutableState.value.yawFollowDecision.control?.phase,
                            targetCenter = mutableState.value.trackingErrors?.targetCenterX,
                            rawError = mutableState.value.trackingErrors?.rawYawError,
                            controlError = mutableState.value.trackingErrors?.yawError,
                            frameSequence = mutableState.value.video.processedDetectorFrameSequence,
                            reason = "Explicit rearm after aircraft settled",
                        ),
                    )
                }
                // ARM is the explicit acknowledgement for a previous STOP / HOVER intervention.
                // It clears only that sticky UI/session flag; every other yaw safety prerequisite
                // is still evaluated below and may latch or wait as usual.
                mutableState.update { state ->
                    if (state.hoverActive) state.copy(hoverActive = false) else state
                }
            }
            val current = mutableState.value
            val decision = if (armed) yawFollowGate.arm(current.toYawFollowInput()) else yawFollowGate.disarm()
            val manualWins = decision.reason == YawFollowReason.MANUAL_OVERRIDE && !current.manualVector.isZero()
            if (decision.state == YawFollowState.ACTIVE) {
                val generation = rcLoop.beginAutonomousYaw()
                yawFollowGeneration = generation
                publishAutonomousYaw(decision, generation, current)
            } else if (!manualWins) {
                yawFollowGeneration = null
                zeroGeneration = rcLoop.preemptAutonomy()
            }
            updateYawFollowState {
                it.copy(
                    authority = if (decision.state == YawFollowState.ACTIVE) ControlAuthority.Autonomous else ControlAuthority.Manual,
                    yawFollowDecision = decision,
                    lastMessage = if (armed) "Yaw follow ${decision.state.name}: ${decision.reason.displayName()}" else
                        "Yaw follow disarmed; zero movement selected",
                )
            }
        }
        if (!armed || zeroGeneration != null && mutableState.value.yawFollowDecision.requiresExplicitRearm) {
            zeroGeneration?.let(::sendYawFollowZero)
        }
    }

    fun setSpeed(percent: Int) {
        mutableState.update { it.copy(speedPercent = RcSpeedMode.fromPercent(percent).percent) }
    }

    fun setTrackingMode(mode: TrackingMode) {
        val activeVideo = video
        when (mode) {
            TrackingMode.Off -> {
                latchYawFollowAndSendZero(YawFollowReason.DETECTOR_UNAVAILABLE)
                stopDetectionAndClearTracking()
                mutableState.update {
                    it.withTrackingCleared().copy(lastMessage = "Person detection off")
                }
            }
            TrackingMode.DetectOnly -> {
                val current = mutableState.value
                if (current.connection != DroneConnectionState.Connected ||
                    current.video.availability != VideoAvailability.Streaming ||
                    current.flight in setOf(FlightState.Landing, FlightState.Emergency)
                ) {
                    invalid("Person detection requires a connected live preview outside Landing or Emergency")
                    return
                }
                val started = activeVideo?.setPersonDetectionEnabled(true)
                    ?: Result.failure(IllegalStateException("Video analysis is unavailable"))
                if (started.isSuccess) {
                    resetRealTracking()
                    mutableState.update {
                        it.copy(
                            tracking = TrackingMode.DetectOnly,
                            authority = ControlAuthority.Manual,
                            personDetections = emptyList(),
                            target = null,
                            trackingStateTransitions = emptyList(),
                            lastMessage = "Starting on-device person detection",
                        )
                    }
                } else {
                    invalid(started.exceptionOrNull()?.message ?: "Person detection could not start")
                }
            }
            TrackingMode.TargetLocked, TrackingMode.Follow ->
                invalid("Target lock and Follow are not available in Phase 4A")
        }
    }

    /**
     * Explicit real-mode selection boundary. Resolves user's tap point against the current
     * newest detector frame detections with a bounded hit-slop.
     */
    fun selectTargetAt(
        normalizedX: Float,
        normalizedY: Float,
        displayedFrameSequence: Long? = null,
    ) = synchronized(trackingLock) {
        val nowNanos = sourceNowNanos()
        val current = mutableState.value
        val currentFrame = current.video.detectorFrameIdentity()
        val isConnected = current.connection == DroneConnectionState.Connected
        val isStreaming = current.video.availability == VideoAvailability.Streaming
        val isDetecting = current.video.personDetectionState == PersonDetectionState.Detecting
        val frameAgeNanos = if (currentFrame != null) nowNanos - currentFrame.sourceTimestampNanos else null
        val frameAgeMillis = frameAgeNanos?.let { (it / 1_000_000L).coerceAtLeast(0L) }

        if (!isConnected || !isStreaming || !isDetecting || currentFrame == null || currentFrame != latestAcceptedDetectorFrame) {
            visionTrace.recordTargetSelectionAttempt(
                TargetSelectionAttemptTrace(
                    tapTimestampNanos = nowNanos,
                    normalizedTapX = normalizedX,
                    normalizedTapY = normalizedY,
                    displayedFrameSequence = displayedFrameSequence,
                    sessionCurrentFrameSequence = currentFrame?.sequence,
                    detectorFrameAgeMillis = frameAgeMillis,
                    currentDetectionsCount = current.personDetections.size,
                    hitCandidatesCount = 0,
                    result = TargetSelectionAttemptResult.DETECTOR_NOT_READY,
                ),
            )
            invalid("Detector not ready for target selection")
            return@synchronized
        }

        if (frameAgeNanos !in 0 until PersonDetectionStore.STALE_AFTER_NANOS) {
            visionTrace.recordTargetSelectionAttempt(
                TargetSelectionAttemptTrace(
                    tapTimestampNanos = nowNanos,
                    normalizedTapX = normalizedX,
                    normalizedTapY = normalizedY,
                    displayedFrameSequence = displayedFrameSequence,
                    sessionCurrentFrameSequence = currentFrame.sequence,
                    detectorFrameAgeMillis = frameAgeMillis,
                    currentDetectionsCount = current.personDetections.size,
                    hitCandidatesCount = 0,
                    result = TargetSelectionAttemptResult.STALE_FRAME,
                ),
            )
            invalid("Detector frame is stale; wait for fresh frame")
            return@synchronized
        }

        // Inspect ONLY personDetections belonging to the CURRENT newest detector frame
        val currentDetections = current.personDetections.filter {
            it.frameSequence == currentFrame.sequence && it.sourceTimestampNanos == currentFrame.sourceTimestampNanos
        }

        val matchingCandidates = currentDetections.filter { detection ->
            detection.boundingBox.containsWithHitSlop(normalizedX, normalizedY)
        }

        if (matchingCandidates.isEmpty()) {
            visionTrace.recordTargetSelectionAttempt(
                TargetSelectionAttemptTrace(
                    tapTimestampNanos = nowNanos,
                    normalizedTapX = normalizedX,
                    normalizedTapY = normalizedY,
                    displayedFrameSequence = displayedFrameSequence,
                    sessionCurrentFrameSequence = currentFrame.sequence,
                    detectorFrameAgeMillis = frameAgeMillis,
                    currentDetectionsCount = currentDetections.size,
                    hitCandidatesCount = 0,
                    result = TargetSelectionAttemptResult.NO_MATCH,
                ),
            )
            invalid("No person detected at tap location")
            return@synchronized
        }

        if (matchingCandidates.size > 1) {
            visionTrace.recordTargetSelectionAttempt(
                TargetSelectionAttemptTrace(
                    tapTimestampNanos = nowNanos,
                    normalizedTapX = normalizedX,
                    normalizedTapY = normalizedY,
                    displayedFrameSequence = displayedFrameSequence,
                    sessionCurrentFrameSequence = currentFrame.sequence,
                    detectorFrameAgeMillis = frameAgeMillis,
                    currentDetectionsCount = currentDetections.size,
                    hitCandidatesCount = matchingCandidates.size,
                    result = TargetSelectionAttemptResult.AMBIGUOUS,
                ),
            )
            invalid("Multiple people overlap tap location; tap a distinct person")
            return@synchronized
        }

        val selected = matchingCandidates.first()
        visionTrace.recordTargetSelectionAttempt(
            TargetSelectionAttemptTrace(
                tapTimestampNanos = nowNanos,
                normalizedTapX = normalizedX,
                normalizedTapY = normalizedY,
                displayedFrameSequence = displayedFrameSequence,
                sessionCurrentFrameSequence = currentFrame.sequence,
                detectorFrameAgeMillis = frameAgeMillis,
                currentDetectionsCount = currentDetections.size,
                hitCandidatesCount = 1,
                result = TargetSelectionAttemptResult.ACCEPTED,
            ),
        )

        val target = TargetSelection.select(selected)
        trackingErrors.reset()
        dryRunPlanner.reset()
        lastPlannerFrameTimestampNanos = selected.sourceTimestampNanos
        val errors = trackingErrors.update(target, targetFresh = true)
        var selectionAccepted = false
        mutableState.update { state ->
            selectionAccepted = true
            state.copy(
                tracking = TrackingMode.TargetLocked,
                authority = ControlAuthority.Manual,
                target = target,
                trackingErrors = errors,
                targetAssociationState = TargetAssociationState.Selected,
                trackingStateTransitions = state.trackingStateTransitions.appendTransition(
                    from = state.targetAssociationState,
                    to = TargetAssociationState.Selected,
                    frameSequence = selected.frameSequence,
                    sourceTimestampNanos = selected.sourceTimestampNanos,
                ),
                dryRunControlIntent = dryRunPlanner.plan(errors, TargetAssociationState.Selected, Float.NaN),
                followDistanceReference = null,
                followDistanceCalibrationState = FollowDistanceCalibrationState.NotSet,
                lastMessage = "Real target selected; dry run only, no commands sent",
            )
        }
        if (selectionAccepted) visionTrace.onTargetSelected(target)
        reconcileSafetyGate()
    }

    fun setCurrentFollowDistance() = synchronized(trackingLock) {
        val state = mutableState.value
        if (FollowDistanceEligibility.evaluate(state) != FollowDistanceEligibilityReason.READY) { invalid("Current distance target is not ready"); return@synchronized }
        distanceCalibrator.start(sourceNowNanos())
        mutableState.update { it.copy(followDistanceReference = null, followDistanceCalibrationState = FollowDistanceCalibrationState.Calibrating, followDistanceCalibrationSamples = 0, lastMessage = "Collecting visual follow-distance samples") }
    }

    suspend fun refreshConnectionHealth(nowMillis: Long = clock.nowMillis()) {
        val last = lastTelemetryAtMillis ?: return
        val age = nowMillis - last
        // A receive that arrived after this health check started wins; never turn a fresh link into
        // a false terminal loss based on an obsolete timestamp.
        if (lastTelemetryAtMillis != last) return
        if (age >= TELEMETRY_STALE_MILLIS && mutableState.value.telemetry.isFresh) {
            currentYawRateDegreesPerSecond = null
            lastTelemetryYawDegrees = null
            lastTelemetryYawTimestampMillis = null
            requireManualNeutral()
            latchYawFollow(YawFollowReason.TELEMETRY_STALE)
            rcLoop.setHealthy(false)
            rcLoop.clearAndSendZero()
            mutableState.update { state ->
                if (state.telemetry.isFresh) {
                    state.copy(
                        telemetry = state.telemetry.copy(isFresh = false, yawRateDegreesPerSecond = null),
                        manualVector = ManualControlVector(),
                        hoverActive = false,
                        lastMessage = "Telemetry stale; non-zero RC output inhibited",
                    )
                } else state
            }
        }
        if (age >= CONNECTION_LOST_MILLIS && lastTelemetryAtMillis == last) {
            failConnection("Tello telemetry connection lost")
        }
    }

    suspend fun disconnect(): Boolean = commandStateMutex.withLock {
        val current = mutableState.value
        if (current.flight in setOf(FlightState.TakingOff, FlightState.Flying, FlightState.Landing, FlightState.Unknown)) {
            invalid("Land before disconnecting; aircraft state must be safely grounded")
            return@withLock false
        }
        stopKeepalive()
        closeResources(
            sendFinalZero = current.flight != FlightState.Emergency,
            requestStreamOff = videoStreamAcknowledged,
        )
        resetRealTracking()
        mutableState.update { state ->
            state.copy(
                connection = DroneConnectionState.Disconnected,
                networkSelection = NetworkSelectionState.Idle,
                flight = if (state.flight == FlightState.Emergency) FlightState.Emergency else FlightState.Grounded,
                telemetry = state.telemetry.copy(isFresh = false),
                video = VideoState(),
                tracking = TrackingMode.Off,
                authority = ControlAuthority.Manual,
                personDetections = emptyList(),
                target = null,
                manualVector = ManualControlVector(),
                hoverActive = false,
                lastMessage = "Tello session disconnected",
            )
        }
        true
    }

    suspend fun networkLost(message: String = "Tello Wi-Fi network lost") = failConnection(message)

    private fun startTelemetryCollection() {
        if (telemetryJob?.isActive == true) return
        telemetryJob = scope.launch {
            transport.telemetry.collect { sample ->
                if (closed) return@collect
                lastTelemetryAtMillis = sample.receivedAtMonotonicMillis
                firstTelemetry.complete(sample)
                visionTrace.recordTelemetrySample(sample.batteryPercent, sample.heightMeters)
                var becameFlying = false
                var becameGrounded = false
                var becameExternalGrounded = false

                val currentBefore = mutableState.value
                val isGroundedSample = sample.isVerifiedGrounded()

                val isAirborneSample = sample.isVerifiedAirborne()
                val isVerticallyStable = sample.velocityZCentimetersPerSecond?.let {
                    kotlin.math.abs(it) <= TAKEOFF_MAX_VERTICAL_SPEED_CPS
                } == true

                var stabilizationMinHeight: Float? = null
                var stabilizationMaxHeight: Float? = null
                var stabilizationHeightRange: Float? = null
                var stabilizationDuration: Long? = null
                var stabilizationSampleCountSnapshot: Int? = null

                if (currentBefore.connection == DroneConnectionState.Connected &&
                    currentBefore.flight == FlightState.TakingOff && takeoffAcknowledged
                ) {
                    val height = sample.heightMeters
                    if (isAirborneSample && isVerticallyStable && height != null) {
                        if (takeoffStabilizationSamples == 0) {
                            firstTakeoffStabilizationAtMillis = sample.receivedAtMonotonicMillis
                            takeoffStabilizationHeights.clear()
                        }
                        takeoffStabilizationSamples++
                        takeoffStabilizationHeights.add(height)

                        val minH = takeoffStabilizationHeights.min()
                        val maxH = takeoffStabilizationHeights.max()
                        val range = maxH - minH
                        val firstAt = firstTakeoffStabilizationAtMillis ?: sample.receivedAtMonotonicMillis
                        val duration = sample.receivedAtMonotonicMillis - firstAt

                        if (range <= TAKEOFF_MAX_HEIGHT_VARIATION_METERS) {
                            if (takeoffStabilizationSamples >= TAKEOFF_STABILIZATION_MIN_SAMPLES &&
                                duration >= TAKEOFF_STABILIZATION_MIN_DURATION_MILLIS
                            ) {
                                becameFlying = true
                                stabilizationMinHeight = minH
                                stabilizationMaxHeight = maxH
                                stabilizationHeightRange = range
                                stabilizationDuration = duration
                                stabilizationSampleCountSnapshot = takeoffStabilizationSamples
                            }
                        } else {
                            takeoffStabilizationSamples = 0
                            firstTakeoffStabilizationAtMillis = null
                            takeoffStabilizationHeights.clear()
                        }
                    } else {
                        takeoffStabilizationSamples = 0
                        firstTakeoffStabilizationAtMillis = null
                        takeoffStabilizationHeights.clear()
                    }
                } else {
                    takeoffStabilizationSamples = 0
                    firstTakeoffStabilizationAtMillis = null
                    takeoffStabilizationHeights.clear()
                }

                if (currentBefore.connection == DroneConnectionState.Connected && currentBefore.flight == FlightState.Flying) {
                    if (isGroundedSample) {
                        if (groundedSampleCount == 0) {
                            firstGroundedSampleAtMillis = sample.receivedAtMonotonicMillis
                        }
                        groundedSampleCount++
                        val firstSampleAt = firstGroundedSampleAtMillis ?: sample.receivedAtMonotonicMillis
                        val groundedDuration = sample.receivedAtMonotonicMillis - firstSampleAt
                        if (groundedSampleCount >= EXTERNAL_GROUNDING_MIN_SAMPLES && groundedDuration >= EXTERNAL_GROUNDING_WINDOW_MILLIS) {
                            becameExternalGrounded = true
                        }
                    } else {
                        groundedSampleCount = 0
                        firstGroundedSampleAtMillis = null
                    }
                } else {
                    groundedSampleCount = 0
                    firstGroundedSampleAtMillis = null
                }

                telemetrySequence++
                val sampleTimestampMillis = sample.receivedAtMonotonicMillis
                val currentYaw = sample.yawDegrees
                val previousYaw = lastTelemetryYawDegrees
                val previousTimestampMillis = lastTelemetryYawTimestampMillis
                val rawYawRate = if (currentYaw != null && previousYaw != null && previousTimestampMillis != null) {
                    calculateYawRateDegreesPerSecond(
                        previousYawDegrees = previousYaw,
                        currentYawDegrees = currentYaw,
                        previousTimestampMillis = previousTimestampMillis,
                        currentTimestampMillis = sampleTimestampMillis,
                    )
                } else null
                val filteredYawRate = rawYawRate?.let { yawRateFilter.filter(it, sampleTimestampMillis) }
                if (rawYawRate == null) {
                    yawRateFilter.reset()
                }
                if (currentYaw != null) {
                    lastTelemetryYawDegrees = currentYaw
                    lastTelemetryYawTimestampMillis = sampleTimestampMillis
                }
                currentYawRateDegreesPerSecond = filteredYawRate ?: rawYawRate
                currentRawYawRateDegreesPerSecond = rawYawRate

                val deltaMillis = if (previousTimestampMillis != null) sampleTimestampMillis - previousTimestampMillis else null
                val deltaYawDegrees = if (currentYaw != null && previousYaw != null) {
                    shortestAngularDifferenceDegrees(previousYaw.toFloat(), currentYaw.toFloat()).toInt()
                } else null

                val isSettledSample = rawYawRate != null && kotlin.math.abs(rawYawRate) <= YawResponseSafetyMonitor.SETTLED_RATE_THRESHOLD_DPS &&
                    (filteredYawRate != null && kotlin.math.abs(filteredYawRate) <= YawResponseSafetyMonitor.SETTLED_RATE_THRESHOLD_DPS)
                val usedForAnomaly = currentBefore.flight == FlightState.Flying && currentBefore.yawFollowDecision.state == YawFollowState.ACTIVE

                visionTrace.recordTelemetryDetailedSample(
                    TelemetrySampleTrace(
                        telemetrySequence = telemetrySequence,
                        receivedAtMonotonicMillis = sampleTimestampMillis,
                        receivedAtNanos = sourceNowNanos(),
                        yawDegrees = sample.yawDegrees,
                        previousYawDegrees = previousYaw,
                        shortestYawDeltaDegrees = deltaYawDegrees,
                        deltaMillis = deltaMillis,
                        rawYawRateDegreesPerSecond = rawYawRate,
                        filteredYawRateDegreesPerSecond = filteredYawRate,
                        heightMeters = sample.heightMeters,
                        velocityXCentimetersPerSecond = sample.velocityXCentimetersPerSecond,
                        velocityYCentimetersPerSecond = sample.velocityYCentimetersPerSecond,
                        velocityZCentimetersPerSecond = sample.velocityZCentimetersPerSecond,
                        batteryPercent = sample.batteryPercent,
                        acceptedForSettling = isSettledSample,
                        usedForAnomalyMonitor = usedForAnomaly,
                    ),
                )

                val currentFlight = currentBefore.flight
                val currentFollowState = currentBefore.yawFollowDecision.state
                val anomalyEval = yawResponseSafetyMonitor.evaluate(
                    sample = TelemetryYawSample(
                        sequence = telemetrySequence,
                        yawDegrees = sample.yawDegrees ?: 0,
                        previousYawDegrees = previousYaw,
                        shortestDeltaDegrees = deltaYawDegrees,
                        deltaMillis = deltaMillis,
                        rawYawRateDegreesPerSecond = rawYawRate,
                        filteredYawRateDegreesPerSecond = filteredYawRate,
                        receivedAtMillis = sampleTimestampMillis,
                    ),
                    flightState = currentFlight,
                    yawFollowState = currentFollowState,
                )

                if (anomalyEval.isJustLatched) {
                    visionTrace.recordYawResponseAnomalyEvent(
                        YawResponseAnomalyEventTrace(
                            timestampNanos = sourceNowNanos(),
                            eventType = "yaw_response_anomaly_latched",
                            rawYawRate = rawYawRate,
                            filteredYawRate = filteredYawRate,
                            currentYawDegrees = sample.yawDegrees,
                            recentActualYawRcSummary = "dominantRc=${anomalyEval.dominantRecentRc}",
                            ageOfMostRecentNonzeroRcMillis = anomalyEval.zeroCommandDurationMillis,
                            recentRcSign = if (anomalyEval.dominantRecentRc > 0) 1 else if (anomalyEval.dominantRecentRc < 0) -1 else 0,
                            controllerPhase = currentBefore.yawFollowDecision.control?.phase,
                            targetCenter = currentBefore.trackingErrors?.targetCenterX,
                            rawError = currentBefore.trackingErrors?.rawYawError,
                            controlError = currentBefore.trackingErrors?.yawError,
                            frameSequence = currentBefore.video.processedDetectorFrameSequence,
                            reason = anomalyEval.reason,
                        ),
                    )
                    latchYawFollowAndSendZero(YawFollowReason.YAW_RESPONSE_ANOMALY)
                } else if (anomalyEval.status == YawResponseSafetyStatus.MISMATCH_SUSPECT) {
                    visionTrace.recordYawResponseAnomalyEvent(
                        YawResponseAnomalyEventTrace(
                            timestampNanos = sourceNowNanos(),
                            eventType = "yaw_response_mismatch_suspect",
                            rawYawRate = rawYawRate,
                            filteredYawRate = filteredYawRate,
                            currentYawDegrees = sample.yawDegrees,
                            recentActualYawRcSummary = "dominantRc=${anomalyEval.dominantRecentRc}",
                            ageOfMostRecentNonzeroRcMillis = anomalyEval.zeroCommandDurationMillis,
                            recentRcSign = if (anomalyEval.dominantRecentRc > 0) 1 else if (anomalyEval.dominantRecentRc < 0) -1 else 0,
                            controllerPhase = currentBefore.yawFollowDecision.control?.phase,
                            targetCenter = currentBefore.trackingErrors?.targetCenterX,
                            rawError = currentBefore.trackingErrors?.rawYawError,
                            controlError = currentBefore.trackingErrors?.yawError,
                            frameSequence = currentBefore.video.processedDetectorFrameSequence,
                            reason = anomalyEval.reason,
                        ),
                    )
                }

                mutableState.update { current ->
                    if (closed || current.connection !in setOf(DroneConnectionState.Connecting, DroneConnectionState.Connected)) {
                        current
                    } else {
                        val nextFlight = when {
                            becameFlying && current.flight == FlightState.TakingOff -> FlightState.Flying
                            current.connection == DroneConnectionState.Connected &&
                                current.flight == FlightState.Landing && landingAcknowledged && sample.isVerifiedGrounded() -> {
                                becameGrounded = true
                                FlightState.Grounded
                            }
                            becameExternalGrounded && current.connection == DroneConnectionState.Connected && current.flight == FlightState.Flying -> {
                                FlightState.Grounded
                            }
                            else -> current.flight
                        }
                        current.copy(
                            telemetry = sample.asSnapshot(isFresh = true),
                            flight = nextFlight,
                            manualVector = if (nextFlight == FlightState.Flying) current.manualVector else ManualControlVector(),
                            lastMessage = when {
                                becameExternalGrounded -> "Aircraft landed outside app command / firmware landing detected"
                                becameFlying -> "Takeoff stabilized by verified airborne telemetry"
                                becameGrounded -> "Landing verified by grounded telemetry"
                                else -> current.lastMessage
                            },
                        )
                    }
                }
                if (becameFlying) {
                    takeoffAcknowledged = false
                    takeoffStabilizationSamples = 0
                    firstTakeoffStabilizationAtMillis = null
                    takeoffStabilizationHeights.clear()
                    rcLoop.enableForNewFlight()
                    startKeepalive()
                    visionTrace.recordFlightStateTransition(
                        FlightStateTransitionTrace(
                            timestampMillis = clock.nowMillis(),
                            fromState = FlightState.TakingOff.name,
                            toState = FlightState.Flying.name,
                            triggerReason = "Takeoff stabilized by verified airborne telemetry",
                            batteryPercent = sample.batteryPercent,
                            heightMeters = sample.heightMeters,
                            verticalVelocityCentimetersPerSecond = sample.velocityZCentimetersPerSecond,
                            stabilizationSampleCount = stabilizationSampleCountSnapshot,
                            stabilizationDurationMillis = stabilizationDuration,
                            minHeightMeters = stabilizationMinHeight,
                            maxHeightMeters = stabilizationMaxHeight,
                            heightRangeMeters = stabilizationHeightRange,
                        ),
                    )
                }
                if (becameGrounded) {
                    landingAcknowledged = false
                    stopKeepalive()
                    stopDetectionAndClearTracking()
                    visionTrace.recordFlightStateTransition(
                        FlightStateTransitionTrace(
                            timestampMillis = clock.nowMillis(),
                            fromState = FlightState.Landing.name,
                            toState = FlightState.Grounded.name,
                            triggerReason = "Landing verified by grounded telemetry",
                            batteryPercent = sample.batteryPercent,
                            heightMeters = sample.heightMeters,
                        ),
                    )
                }
                if (becameExternalGrounded) {
                    groundedSampleCount = 0
                    firstGroundedSampleAtMillis = null
                    takeoffAcknowledged = false
                    landingAcknowledged = false
                    stopKeepalive()
                    rcLoop.setEnabled(false)
                    rcLoop.clearAndSendZero()
                    requireManualNeutral()
                    latchYawFollow(YawFollowReason.LANDING)
                    stopDetectionAndClearTracking()
                    visionTrace.recordExternalGrounding(
                        ExternalGroundingTrace(
                            timestampMillis = sample.receivedAtMonotonicMillis,
                            heightMeters = sample.heightMeters,
                            sampleCount = EXTERNAL_GROUNDING_MIN_SAMPLES,
                        ),
                    )
                    visionTrace.recordFlightStateTransition(
                        FlightStateTransitionTrace(
                            timestampMillis = clock.nowMillis(),
                            fromState = FlightState.Flying.name,
                            toState = FlightState.Grounded.name,
                            triggerReason = "Aircraft landed outside app command / firmware landing detected",
                            batteryPercent = sample.batteryPercent,
                            heightMeters = sample.heightMeters,
                        ),
                    )
                }
                if (!closed && mutableState.value.connection == DroneConnectionState.Connected) rcLoop.setHealthy(true)
                val currentTelemetry = mutableState.value.telemetry
                yawFollowGate.observeTelemetry(
                    currentTelemetry.yawDegrees,
                    currentTelemetry.yawRateDegreesPerSecond,
                    sample.receivedAtMonotonicMillis,
                )
                reconcileSafetyGate()
            }
        }
    }


    private fun startHealthMonitor() {
        if (healthJob?.isActive == true) return
        healthJob = scope.launch {
            while (isActive && !closed) {
                delay(HEALTH_CHECK_PERIOD_MILLIS)
                refreshConnectionHealth()
            }
        }
    }

    private fun startVideoStateCollection() {
        val activeVideo = video ?: return
        if (videoStateJob?.isActive == true) return
        videoStateJob = scope.launch {
            activeVideo.state.collect { videoState ->
                if (!closed) applyVideoState(videoState)
            }
        }
    }


    private fun applyVideoState(videoState: VideoState) {
        var publishActive = false
        var traceFrame: VisionTraceFrame? = null
        synchronized(trackingLock) {
            val frame = videoState.detectorFrameIdentity()
            val ready = videoState.availability == VideoAvailability.Streaming &&
                videoState.personDetectionState == PersonDetectionState.Detecting
            if (!ready) {
                resetRealTrackingLocked()
                mutableState.update { it.withPersonDetectionVideoState(videoState) }
            } else {
                val newAcceptedFrame = frame?.takeIf { it.isNewerThan(latestAcceptedDetectorFrame) }
                if (newAcceptedFrame != null) {
                    latestAcceptedDetectorFrame = newAcceptedFrame
                    publishActive = true
                }
                var selectedTargetBefore: com.alonibh.tellodrone.domain.TrackedTarget? = null
                var associationDiagnostics: TargetAssociationDiagnostics? = null
                mutableState.update { current ->
                    val baseline = current.withPersonDetectionVideoState(videoState)
                    when {
                        newAcceptedFrame != null && current.target != null -> {
                            selectedTargetBefore = current.target
                            applyAssociation(baseline, current.target, newAcceptedFrame).also {
                                associationDiagnostics = it.diagnostics
                            }.state
                        }
                        current.target != null && current.video.personDetections.isNotEmpty() &&
                            videoState.personDetections.isEmpty() && frame == current.video.detectorFrameIdentity() -> {
                            val errors = trackingErrors.update(current.target, targetFresh = false)
                            baseline.copy(
                                tracking = TrackingMode.TargetLocked,
                                authority = ControlAuthority.Manual,
                                trackingErrors = errors,
                                dryRunControlIntent = dryRunPlanner.plan(
                                    errors,
                                    current.targetAssociationState,
                                    Float.NaN,
                                ),
                                lastMessage = "Real detector result expired; no commands sent",
                            )
                        }
                        else -> baseline
                    }
                }
                if (newAcceptedFrame != null) {
                    val after = mutableState.value
                    traceFrame = VisionTraceFrame(
                        frameSequence = newAcceptedFrame.sequence,
                        sourceTimestampNanos = newAcceptedFrame.sourceTimestampNanos,
                        detectorModel = videoState.detectorModelName,
                        detectorBackend = videoState.detectorBackend?.name,
                        confidenceThreshold = videoState.detectorConfidenceThreshold,
                        inferenceMillis = videoState.detectorInferenceMillis,
                        candidates = videoState.detectorCandidates,
                        detections = videoState.personDetections,
                        selectedTargetBefore = selectedTargetBefore,
                        selectedTargetAfter = after.target,
                        associationState = after.targetAssociationState,
                        associationDiagnostics = associationDiagnostics,
                        renderedFrameTimestampNanos = videoState.processedRenderedFrameTimestampNanos,
                        captureRequestTimestampNanos = videoState.processedCaptureRequestTimestampNanos,
                        pixelCopyCompletedTimestampNanos = videoState.processedPixelCopyCompletedTimestampNanos,
                        detectorInferenceStartedTimestampNanos = videoState.processedDetectorInferenceStartedTimestampNanos,
                        detectorInferenceCompletedTimestampNanos = videoState.processedDetectorInferenceCompletedTimestampNanos,
                        associationCompletedTimestampNanos = sourceNowNanos(),
                        detectorPreprocessingNanos = videoState.detectorPreprocessingNanos,
                        detectorModelInferenceNanos = videoState.detectorModelInferenceNanos,
                        detectorDecodeAndNmsNanos = videoState.detectorDecodeAndNmsNanos,
                        detectorAppearanceNanos = videoState.detectorAppearanceNanos,
                        analysisMeasuredFps = videoState.analysisMeasuredFps,
                        analysisCapturedFrames = videoState.analysisCapturedFrames,
                        analysisDroppedFrames = videoState.analysisDroppedFrames,
                        analysisPendingFrameDepth = videoState.analysisPendingFrameDepth,
                        detectorMeasuredFps = videoState.detectorMeasuredFps,
                    )
                }
            }
        }
        val decision = if (publishActive) reconcileFreshPerception() else reconcileSafetyGate()
        traceFrame?.let { frame ->
            visionTrace.record(frame)
            recordControlMeasurement(frame, decision)
        }
    }

    private fun applyAssociation(
        baseline: DroneSessionState,
        currentTarget: com.alonibh.tellodrone.domain.TrackedTarget,
        frame: DetectorFrameIdentity,
    ): AssociationUpdate {
        val evaluation = targetAssociation.evaluate(
            selectedTarget = currentTarget,
            frameSequence = frame.sequence,
            sourceTimestampNanos = frame.sourceTimestampNanos,
            detections = baseline.personDetections,
            includeDetailedDiagnostics = visionTrace.capturesFrames,
        )
        val result = evaluation.result
        if (result is TargetAssociationResult.Ignored) {
            return AssociationUpdate(baseline, evaluation.diagnostics)
        }
        val dtSeconds = lastPlannerFrameTimestampNanos
            ?.let { (frame.sourceTimestampNanos - it) / 1_000_000_000f }
            ?: Float.NaN
        lastPlannerFrameTimestampNanos = frame.sourceTimestampNanos
        val state = when (result) {
            is TargetAssociationResult.Matched -> {
                val calibration = acceptCalibrationSample(baseline, result.target, frame)
                val errors = trackingErrors.update(result.target, targetFresh = true, distanceReference = calibration.reference)
                baseline.copy(
                    tracking = TrackingMode.TargetLocked,
                    authority = baseline.authority,
                    target = result.target,
                    trackingErrors = errors,
                    targetAssociationState = TargetAssociationState.Matched,
                    dryRunControlIntent = dryRunPlanner.plan(errors, TargetAssociationState.Matched, dtSeconds),
                    followDistanceReference = calibration.reference,
                    followDistanceCalibrationState = calibration.state,
                    followDistanceCalibrationSamples = calibration.samples,
                    lastMessage = "Real target matched; yaw-follow safety gate evaluated",
                )
            }
            is TargetAssociationResult.TemporarilyMissing -> {
                val preserveSet = baseline.followDistanceCalibrationState == FollowDistanceCalibrationState.Set
                if (!preserveSet) cancelCalibration()
                val reference = if (preserveSet) baseline.followDistanceReference else null
                val state = if (preserveSet) FollowDistanceCalibrationState.Set else FollowDistanceCalibrationState.NotSet
                val errors = trackingErrors.update(result.target, targetFresh = false, reference)
                baseline.copy(
                    tracking = TrackingMode.TargetLocked,
                    authority = ControlAuthority.Manual,
                    target = result.target,
                    trackingErrors = errors,
                    targetAssociationState = TargetAssociationState.TemporarilyMissing,
                    dryRunControlIntent = dryRunPlanner.plan(errors, TargetAssociationState.TemporarilyMissing, dtSeconds),
                    followDistanceReference = reference,
                    followDistanceCalibrationState = state,
                    followDistanceCalibrationSamples = if (preserveSet) baseline.followDistanceCalibrationSamples else 0,
                    lastMessage = "Real target temporarily missing; yaw zero selected",
                )
            }
            is TargetAssociationResult.Ambiguous -> {
                val preserveSet = baseline.followDistanceCalibrationState == FollowDistanceCalibrationState.Set
                if (!preserveSet) cancelCalibration()
                val reference = if (preserveSet) baseline.followDistanceReference else null
                val state = if (preserveSet) FollowDistanceCalibrationState.Set else FollowDistanceCalibrationState.NotSet
                val errors = trackingErrors.update(result.target, targetFresh = false, reference)
                baseline.copy(
                    tracking = TrackingMode.TargetLocked,
                    authority = ControlAuthority.Manual,
                    target = result.target,
                    trackingErrors = errors,
                    targetAssociationState = TargetAssociationState.Ambiguous,
                    dryRunControlIntent = dryRunPlanner.plan(errors, TargetAssociationState.Ambiguous, dtSeconds),
                    followDistanceReference = reference,
                    followDistanceCalibrationState = state,
                    followDistanceCalibrationSamples = if (preserveSet) baseline.followDistanceCalibrationSamples else 0,
                    lastMessage = "Real target ambiguous; tap a person to select again",
                )
            }
            is TargetAssociationResult.Lost -> {
                trackingErrors.reset()
                cancelCalibration()
                baseline.copy(
                    tracking = TrackingMode.DetectOnly,
                    authority = ControlAuthority.Manual,
                    target = null,
                    followDistanceReference = null,
                    followDistanceCalibrationState = FollowDistanceCalibrationState.NotSet,
                    trackingErrors = null,
                    targetAssociationState = TargetAssociationState.Lost,
                    dryRunControlIntent = dryRunPlanner.plan(null, TargetAssociationState.Lost, dtSeconds),
                    lastMessage = "Real target lost; explicit reselection required",
                )
            }
            is TargetAssociationResult.Ignored -> baseline
        }
        val instrumented = state.copy(
            trackingStateTransitions = baseline.trackingStateTransitions.appendTransition(
                from = baseline.targetAssociationState,
                to = state.targetAssociationState,
                frameSequence = frame.sequence,
                sourceTimestampNanos = frame.sourceTimestampNanos,
            ),
        )
        return AssociationUpdate(instrumented, evaluation.diagnostics)
    }

    private data class AssociationUpdate(
        val state: DroneSessionState,
        val diagnostics: TargetAssociationDiagnostics,
    )

    private fun List<com.alonibh.tellodrone.domain.TrackingStateTransition>.appendTransition(
        from: TargetAssociationState,
        to: TargetAssociationState,
        frameSequence: Long?,
        sourceTimestampNanos: Long?,
    ): List<com.alonibh.tellodrone.domain.TrackingStateTransition> {
        if (from == to) return this
        return (this + com.alonibh.tellodrone.domain.TrackingStateTransition(
            from,
            to,
            frameSequence,
            sourceTimestampNanos,
        )).takeLast(MAX_TRACKING_TRANSITIONS)
    }

    private fun resetRealTracking() = synchronized(trackingLock) { resetRealTrackingLocked() }

    private fun stopDetectionAndClearTracking() {
        video?.setPersonDetectionEnabled(false)
        resetRealTracking()
        mutableState.update { it.withTrackingCleared() }
    }

    private fun DroneSessionState.withTrackingCleared() = copy(
        tracking = TrackingMode.Off,
        authority = ControlAuthority.Manual,
        video = video.copy(
            personDetectionState = PersonDetectionState.Off,
            detectorCandidates = emptyList(),
            personDetections = emptyList(),
              processedDetectorFrameSequence = null,
              processedDetectorSourceTimestampNanos = null,
              processedRenderedFrameTimestampNanos = null,
              processedCaptureRequestTimestampNanos = null,
              processedPixelCopyCompletedTimestampNanos = null,
              processedDetectorInferenceStartedTimestampNanos = null,
              processedDetectorInferenceCompletedTimestampNanos = null,
              detectorPreprocessingNanos = null,
              detectorModelInferenceNanos = null,
              detectorDecodeAndNmsNanos = null,
              detectorAppearanceNanos = null,
        ),
        personDetections = emptyList(),
        target = null,
        trackingErrors = null,
        targetAssociationState = TargetAssociationState.None,
        dryRunControlIntent = null,
        followDistanceReference = null,
        followDistanceCalibrationState = FollowDistanceCalibrationState.NotSet,
        followDistanceCalibrationSamples = 0,
        shadowAutonomyDecision = null,
        trackingStateTransitions = emptyList(),
    )

    private fun resetYawFollowForNewSession(): YawFollowDecision = synchronized(yawFollowLock) {
        yawFollowGeneration = null
        yawResponseSafetyMonitor.reset()
        yawRateFilter.reset()
        yawFollowGate.disarm()
    }

    private fun reconcileFreshPerception(): YawFollowDecision {
        var zeroGeneration: Long? = null
        lateinit var resolvedDecision: YawFollowDecision
        synchronized(yawFollowLock) {
            val current = mutableState.value
            val previous = current.yawFollowDecision
            val decision = yawFollowGate.processFreshPerception(current.toYawFollowInput())
            resolvedDecision = decision
            if (decision.state == YawFollowState.ACTIVE) {
                val generation = yawFollowGeneration
                    ?.takeIf { previous.state == YawFollowState.ACTIVE }
                    ?: rcLoop.beginAutonomousYaw().also { yawFollowGeneration = it }
                publishAutonomousYaw(decision, generation, current)
            } else {
                val newlyStopped = previous.state == YawFollowState.ACTIVE
                val newlyLatched = decision.state == YawFollowState.REQUIRES_REARM &&
                    previous.state != YawFollowState.REQUIRES_REARM
                if ((newlyStopped || newlyLatched) && decision.reason != YawFollowReason.MANUAL_OVERRIDE) {
                    zeroGeneration = rcLoop.preemptAutonomy()
                }
                yawFollowGeneration = null
            }
            updateYawFollowState {
                it.copy(
                    authority = if (decision.state == YawFollowState.ACTIVE) ControlAuthority.Autonomous else ControlAuthority.Manual,
                    yawFollowDecision = decision,
                )
            }
        }
        zeroGeneration?.let(::sendYawFollowZero)
        return resolvedDecision
    }

    private fun reconcileSafetyGate(): YawFollowDecision {
        var zeroGeneration: Long? = null
        lateinit var resolvedDecision: YawFollowDecision
        synchronized(yawFollowLock) {
            val current = mutableState.value
            val previous = current.yawFollowDecision
            val decision = yawFollowGate.evaluateSafetyGate(current.toYawFollowInput())
            resolvedDecision = decision
            if (decision.state != YawFollowState.ACTIVE) {
                val newlyStopped = previous.state == YawFollowState.ACTIVE
                val newlyLatched = decision.state == YawFollowState.REQUIRES_REARM &&
                    previous.state != YawFollowState.REQUIRES_REARM
                if ((newlyStopped || newlyLatched) && decision.reason != YawFollowReason.MANUAL_OVERRIDE) {
                    zeroGeneration = rcLoop.preemptAutonomy()
                }
                yawFollowGeneration = null
            }
            updateYawFollowState {
                it.copy(
                    authority = if (decision.state == YawFollowState.ACTIVE) ControlAuthority.Autonomous else ControlAuthority.Manual,
                    yawFollowDecision = decision,
                )
            }
        }
        zeroGeneration?.let(::sendYawFollowZero)
        return resolvedDecision
    }
    /** Latches a named intervention; the owning safety path performs its own serialized zero. */
    private fun latchYawFollow(reason: YawFollowReason): Long? = synchronized(yawFollowLock) {
        val current = mutableState.value
        val previous = current.yawFollowDecision
        val decision = yawFollowGate.preempt(reason)
        yawFollowGeneration = null
        val generation = if (previous.state in setOf(YawFollowState.ARMED_WAITING, YawFollowState.ACTIVE)) {
            rcLoop.preemptAutonomy()
        } else {
            null
        }
        updateYawFollowState {
            it.copy(
                authority = ControlAuthority.Manual,
                yawFollowDecision = decision,
            )
        }
        generation
    }

    private fun latchYawFollowAndSendZero(reason: YawFollowReason) {
        latchYawFollow(reason)?.let(::sendYawFollowZero)
    }

    private fun sendYawFollowZero(generation: Long) {
        scope.launch { rcLoop.sendZeroIfCurrent(generation) }
    }

    private fun publishAutonomousYaw(
        decision: YawFollowDecision,
        generation: Long,
        state: DroneSessionState,
    ) {
        val control = decision.control
        rcLoop.publishAutonomousYaw(
            yawRc = control?.safetyFilteredYawRc ?: 0,
            generation = generation,
            validForMillis = control?.validForMillis ?: 0L,
            validityExpiryReason = if (control?.validityLimitedByCommandHold == true) {
                RcSendSuppressionReason.AUTONOMOUS_COMMAND_HOLD_EXPIRED
            } else {
                RcSendSuppressionReason.PERCEPTION_AGE_EXPIRED
            },
            context = control?.let {
                AutonomousYawContext(
                    control = it,
                    associationState = state.targetAssociationState,
                    yawFollowState = decision.state,
                    yawFollowReason = decision.reason,
                    telemetryHeightMeters = state.telemetry.heightMeters,
                )
            },
        )
    }

    private fun recordControlMeasurement(frame: VisionTraceFrame, decision: YawFollowDecision) {
        val current = mutableState.value
        val control = decision.control
        val commandTimestampNanos = control?.commandTimestampNanos ?: sourceNowNanos()
        val ageNanos = commandTimestampNanos - frame.sourceTimestampNanos
        visionTrace.recordControlMeasurement(
            YawControlMeasurementTrace(
                frameSequence = frame.frameSequence,
                sourceTimestampNanos = frame.sourceTimestampNanos,
                commandTimestampNanos = commandTimestampNanos,
                perceptionAgeMillis = ageNanos.takeIf { it >= 0L }?.div(NANOS_PER_MILLISECOND),
                targetCenterX = current.trackingErrors?.targetCenterX,
                rawYawError = current.trackingErrors?.rawYawError,
                filteredYawError = current.trackingErrors?.yawError,
                associationState = current.targetAssociationState,
                previousYawRc = control?.previousYawRc ?: 0,
                requestedYawRc = control?.requestedYawRc ?: 0,
                safetyFilteredYawRc = control?.safetyFilteredYawRc ?: 0,
                suppressionReason = control?.suppressionReason ?: YawControlSuppressionReason.GATE_BLOCKED,
                telemetryHeightMeters = current.telemetry.heightMeters,
                yawFollowState = decision.state,
                yawFollowReason = decision.reason,
                estimatedTargetCenterX = control?.estimatedTargetCenterX,
                targetCenterVelocityPerSecond = control?.targetCenterVelocityPerSecond,
                predictionHorizonMillis = control?.predictionHorizonMillis,
                predictionMode = control?.predictionMode,
                controlYawError = control?.controlYawError,
                controllerPhase = control?.phase,
                telloYawDegrees = control?.telloYawDegrees ?: current.telemetry.yawDegrees,
                telloYawRateDegreesPerSecond = control?.telloYawRateDegreesPerSecond ?: current.telemetry.yawRateDegreesPerSecond,
                rawYawRateDegreesPerSecond = control?.rawYawRateDegreesPerSecond ?: currentRawYawRateDegreesPerSecond ?: current.telemetry.rawYawRateDegreesPerSecond,
                associationCompletedTimestampNanos = frame.associationCompletedTimestampNanos,
                yawDecisionTimestampNanos = commandTimestampNanos,
            ),
        )
    }

    private fun recordRcPublication(publication: RcPublication) {
        yawResponseSafetyMonitor.recordSentRc(publication.sentAtMillis, publication.actualVector.yaw)
        val current = mutableState.value
        val activeContext = publication.autonomousContext.takeIf {
            publication.inputKind == RcInputKind.AUTONOMOUS_YAW
        }
        val control = publication.autonomousContext?.control ?: current.yawFollowDecision.control
        val commandTimestampNanos = publication.commandTimestampNanos
        val frameSequence = publication.autonomousContext?.control?.frameSequence ?:
            current.video.processedDetectorFrameSequence
        val sourceTimestampNanos = publication.autonomousContext?.control?.sourceTimestampNanos ?:
            current.video.processedDetectorSourceTimestampNanos
        val ageNanos = sourceTimestampNanos?.let { commandTimestampNanos - it }
        val manualVec = current.manualVector.let {
            RcVector(
                lateral = (it.lateral * current.speedPercent).roundToInt(),
                forward = (it.forward * current.speedPercent).roundToInt(),
                vertical = (it.vertical * current.speedPercent).roundToInt(),
                yaw = (it.yaw * current.speedPercent).roundToInt(),
            )
        }
        visionTrace.recordRcPublication(
            RcPublicationTrace(
                commandTimestampNanos = commandTimestampNanos,
                frameSequence = frameSequence,
                sourceTimestampNanos = sourceTimestampNanos,
                perceptionAgeMillis = ageNanos?.takeIf { it >= 0L }?.div(NANOS_PER_MILLISECOND),
                targetCenterX = control?.targetCenterX ?: current.trackingErrors?.targetCenterX,
                rawYawError = control?.rawYawError ?: current.trackingErrors?.rawYawError,
                filteredYawError = control?.filteredYawError ?: current.trackingErrors?.yawError,
                associationState = activeContext?.associationState ?: current.targetAssociationState,
                previousYawRc = control?.previousYawRc ?: 0,
                requestedYawRc = control?.requestedYawRc ?: 0,
                safetyFilteredYawRc = control?.safetyFilteredYawRc ?: 0,
                yawSuppressionReason = control?.suppressionReason,
                requestedVector = publication.requestedVector,
                actualSentVector = publication.actualVector,
                inputKind = publication.inputKind,
                sendSuppressionReason = publication.suppressionReason,
                telemetryHeightMeters = activeContext?.telemetryHeightMeters ?: current.telemetry.heightMeters,
                yawFollowState = activeContext?.yawFollowState ?: current.yawFollowDecision.state,
                yawFollowReason = activeContext?.yawFollowReason ?: current.yawFollowDecision.reason,
                desiredPublishedAtNanos = publication.desiredPublishedAtNanos,
                sendStartedAtNanos = publication.sendStartedAtNanos,
                actualSentAtNanos = publication.sentAtNanos,
                estimatedTargetCenterX = control?.estimatedTargetCenterX,
                targetCenterVelocityPerSecond = control?.targetCenterVelocityPerSecond,
                predictionHorizonMillis = control?.predictionHorizonMillis,
                predictionMode = control?.predictionMode,
                controlYawError = control?.controlYawError,
                controllerPhase = control?.phase,
                telloYawDegrees = control?.telloYawDegrees ?: current.telemetry.yawDegrees,
                telloYawRateDegreesPerSecond = control?.telloYawRateDegreesPerSecond ?: current.telemetry.yawRateDegreesPerSecond,
                rawYawRateDegreesPerSecond = control?.rawYawRateDegreesPerSecond ?: currentRawYawRateDegreesPerSecond ?: current.telemetry.rawYawRateDegreesPerSecond,
                yawDecisionTimestampNanos = control?.commandTimestampNanos,
                flightState = current.flight,
                trackingMode = current.tracking,
                controlAuthority = current.authority,
                manualVector = manualVec,
                flightControlEpoch = publication.flightEpoch,
                yawFollowGeneration = publication.autonomyGeneration,
            ),
        )
    }

    /** Commits only yaw-follow-owned fields against the latest session state. */
    private fun updateYawFollowState(transform: (DroneSessionState) -> DroneSessionState) {
        beforeYawFollowStateCommit?.invoke(mutableState)
        mutableState.update(transform)
    }

    private fun DroneSessionState.toYawFollowInput() = YawFollowInput(
        connection = connection,
        flight = flight,
        telemetryFresh = telemetry.isFresh,
        video = video.availability,
        detector = video.personDetectionState,
        targetPresent = target != null,
        association = targetAssociationState,
        errors = trackingErrors,
        manualInputNeutral = manualVector.isZero(),
        hoverActive = hoverActive,
        commandTimestampNanos = sourceNowNanos(),
        telemetryYawDegrees = telemetry.yawDegrees,
        telemetryYawRateDegreesPerSecond = telemetry.yawRateDegreesPerSecond,
        rawYawRateDegreesPerSecond = currentRawYawRateDegreesPerSecond ?: telemetry.rawYawRateDegreesPerSecond,
        responseAnomalyDetected = yawResponseSafetyMonitor.isLatched(),
    )

    private fun YawFollowReason.displayName() = name.replace('_', ' ')

    private fun resetRealTrackingLocked() {
        trackingErrors.reset()
        dryRunPlanner.reset()
        latestAcceptedDetectorFrame = null
        lastPlannerFrameTimestampNanos = null
        distanceCalibrator.cancel()
    }

    private data class CalibrationUpdate(val reference: com.alonibh.tellodrone.domain.FollowDistanceReference?, val state: FollowDistanceCalibrationState, val samples: Int)
    private fun acceptCalibrationSample(baseline: DroneSessionState, target: com.alonibh.tellodrone.domain.TrackedTarget, frame: DetectorFrameIdentity): CalibrationUpdate {
        if (baseline.followDistanceCalibrationState != FollowDistanceCalibrationState.Calibrating) return CalibrationUpdate(baseline.followDistanceReference, baseline.followDistanceCalibrationState, baseline.followDistanceCalibrationSamples)
        if (distanceCalibrator.timedOut(sourceNowNanos())) { cancelCalibration(); return CalibrationUpdate(null, FollowDistanceCalibrationState.NotSet, 0) }
        val reference = distanceCalibrator.add(frame.sequence, frame.sourceTimestampNanos, target.boundingBox)
        if (reference != null) { trackingErrors.resetDistance(); dryRunPlanner.reset(); return CalibrationUpdate(reference, FollowDistanceCalibrationState.Set, FollowDistanceCalibrator.REQUIRED_SAMPLES) }
        return CalibrationUpdate(null, FollowDistanceCalibrationState.Calibrating, distanceCalibrator.sampleCount)
    }
    private fun cancelCalibration() = distanceCalibrator.cancel()

    private suspend fun startVideoStreaming() {
        val activeVideo = video ?: return
        val prepared = activeVideo.prepare()
        if (prepared.isFailure) {
            activeVideo.streamFailed(
                "Video receiver could not start: ${prepared.exceptionOrNull()?.safeMessage() ?: "unknown error"}",
            )
            return
        }
        when (val result = sendSdkCommand("streamon", SdkCommandCategory.STREAM, VIDEO_COMMAND_TIMEOUT_MILLIS)) {
            is TelloCommandResult.Success -> {
                videoStreamAcknowledged = true
                activeVideo.streamAcknowledged()
            }
            else -> activeVideo.streamFailed("streamon failed: ${result.description()}")
        }
    }

    private suspend fun failConnection(message: String) {
        if (closed || mutableState.value.connection == DroneConnectionState.Error) return
        var failed = false
        var wasEmergency = false
        takeoffAcknowledged = false
        landingAcknowledged = false
        stopKeepalive()
        requireManualNeutral()
        latchYawFollow(YawFollowReason.CONNECTION_LOST)
        resetRealTracking()
        mutableState.update { state ->
            if (state.connection == DroneConnectionState.Error || closed) state else {
                failed = true
                wasEmergency = state.flight == FlightState.Emergency
                state.copy(
                    connection = DroneConnectionState.Error,
                    networkSelection = NetworkSelectionState.Lost,
                    flight = if (wasEmergency) FlightState.Emergency else FlightState.Unknown,
                    telemetry = state.telemetry.copy(isFresh = false),
                    video = VideoState(availability = VideoAvailability.Error, errorReason = message),
                    authority = ControlAuthority.Manual,
                    tracking = TrackingMode.Off,
                    personDetections = emptyList(),
                    target = null,
                    manualVector = ManualControlVector(),
                    hoverActive = false,
                    lastMessage = message,
                )
            }
        }
        if (!failed) return
        rcLoop.setHealthy(false)
        if (!wasEmergency) rcLoop.clearAndSendZero()
        closeResources(sendFinalZero = false, requestStreamOff = false)
        val shouldReportFatal = synchronized(fatalReportLock) {
            if (fatalReported) false else {
                fatalReported = true
                true
            }
        }
        if (shouldReportFatal) onFatalConnectionLoss(message)
    }

    private suspend fun closeResources(sendFinalZero: Boolean, requestStreamOff: Boolean) {
        resourceCloseMutex.withLock {
            if (closed) return@withLock
            closed = true
            takeoffAcknowledged = false
            landingAcknowledged = false
            videoStreamAcknowledged = false
            stopKeepalive()
            requireManualNeutral()
            healthJob?.cancel()
            telemetryJob?.cancel()
            videoStateJob?.cancel()
            rcLoop.shutdown(sendFinalZero)
            video?.close()
            if (requestStreamOff) {
                withTimeoutOrNull(STREAMOFF_CLEANUP_TIMEOUT_MILLIS) {
                    sendSdkCommand("streamoff", SdkCommandCategory.STREAM, STREAMOFF_ACK_TIMEOUT_MILLIS)
                }
            }
            transport.close()
        }
    }

    private fun startKeepalive() {
        if (keepaliveJob?.isActive == true) return
        keepaliveJob = scope.launch {
            while (isActive && !closed) {
                delay(KEEPALIVE_PERIOD_MILLIS)
                if (!isActive || closed || mutableState.value.flight != FlightState.Flying) break
                performKeepalive()
            }
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    private suspend fun performKeepalive() {
        commandStateMutex.withLock {
            if (closed || mutableState.value.flight != FlightState.Flying || mutableState.value.connection != DroneConnectionState.Connected) {
                return@withLock
            }
            sendSdkCommand("command", SdkCommandCategory.KEEPALIVE, KEEPALIVE_TIMEOUT_MILLIS)
        }
    }

    private suspend fun sendSdkCommand(command: String, category: SdkCommandCategory, timeoutMillis: Long): TelloCommandResult {
        val sentAt = clock.nowMillis()
        val startNanos = sourceNowNanos()
        val result = transport.sendCommand(command, timeoutMillis)
        val latencyMillis = ((sourceNowNanos() - startNanos) / NANOS_PER_MILLISECOND).coerceAtLeast(0L)
        visionTrace.recordSdkCommand(
            SdkCommandTrace(
                command = command,
                category = category,
                sentAtMonotonicMillis = sentAt,
                latencyMillis = latencyMillis,
                result = result.diagnosticDescription(),
            ),
        )
        return result
    }

    private fun TelloCommandResult.diagnosticDescription(): String = when (this) {
        is TelloCommandResult.Success -> "Success($response)"
        is TelloCommandResult.Rejected -> "Rejected($response)"
        TelloCommandResult.Timeout -> "Timeout"
        is TelloCommandResult.Failure -> "Failure(${cause.safeMessage()})"
    }

    private fun invalid(message: String) {
        mutableState.update { it.copy(lastMessage = message) }
    }

    private data class DetectorFrameIdentity(val sequence: Long, val sourceTimestampNanos: Long) {
        fun isNewerThan(previous: DetectorFrameIdentity?): Boolean = previous == null ||
            (sequence > previous.sequence && sourceTimestampNanos > previous.sourceTimestampNanos)
    }

    private fun VideoState.detectorFrameIdentity(): DetectorFrameIdentity? {
        val sequence = processedDetectorFrameSequence ?: return null
        val timestamp = processedDetectorSourceTimestampNanos ?: return null
        return DetectorFrameIdentity(sequence, timestamp)
    }

    private fun DroneSessionState.canTakeOff() =
        connection == DroneConnectionState.Connected &&
            flight == FlightState.Grounded &&
            telemetry.isFresh &&
            (telemetry.batteryPercent ?: 0) >= MINIMUM_TAKEOFF_BATTERY_PERCENT

    private fun DroneSessionState.canRequestYawFollowArm() =
        connection == DroneConnectionState.Connected &&
            flight == FlightState.Flying &&
            telemetry.isFresh &&
            video.availability == VideoAvailability.Streaming &&
            video.personDetectionState == PersonDetectionState.Detecting &&
            manualVector.isZero() &&
            target != null && !target.identityUncertain &&
            targetAssociationState in setOf(TargetAssociationState.Selected, TargetAssociationState.Matched)

    private fun TelloTelemetry.isVerifiedGrounded(): Boolean =
        heightMeters?.let { it.isFinite() && it >= 0f && it <= GROUNDED_HEIGHT_THRESHOLD_METERS } == true

    private fun TelloTelemetry.isVerifiedAirborne(): Boolean =
        heightMeters?.let { it.isFinite() && it > GROUNDED_HEIGHT_THRESHOLD_METERS } == true

    private fun requireManualNeutral() = synchronized(manualInputLock) { manualInputRequiresNeutral = true }

    private fun requiresNeutralInput(vector: ManualControlVector): Boolean = synchronized(manualInputLock) {
        if (!manualInputRequiresNeutral) false
        else if (vector.isZero()) {
            manualInputRequiresNeutral = false
            false
        } else true
    }

    private fun TelloTelemetry.asSnapshot(isFresh: Boolean) = TelemetrySnapshot(
        batteryPercent = batteryPercent,
        heightMeters = heightMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        velocityXCentimetersPerSecond = velocityXCentimetersPerSecond,
        velocityYCentimetersPerSecond = velocityYCentimetersPerSecond,
        velocityZCentimetersPerSecond = velocityZCentimetersPerSecond,
        flightTimeSeconds = flightTimeSeconds,
        temperatureCelsius = temperatureCelsius,
        yawDegrees = yawDegrees,
        yawRateDegreesPerSecond = if (isFresh) currentYawRateDegreesPerSecond else null,
        rawYawRateDegreesPerSecond = if (isFresh) currentRawYawRateDegreesPerSecond else null,
        receivedAt = receivedAt,
        isFresh = isFresh,
    )

    private fun TelloCommandResult.description(): String = when (this) {
        is TelloCommandResult.Success -> response
        is TelloCommandResult.Rejected -> response.filter { it in '\u0020'..'\u007E' }.ifBlank { "rejected" }
        TelloCommandResult.Timeout -> "no valid acknowledgement received"
        is TelloCommandResult.Failure -> cause.safeMessage()
    }

    private fun Throwable.safeMessage() = message ?: javaClass.simpleName

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MINIMUM_TAKEOFF_BATTERY_PERCENT = 30
        const val KEEPALIVE_PERIOD_MILLIS = 5_000L
        const val KEEPALIVE_TIMEOUT_MILLIS = 2_500L
        const val EXTERNAL_GROUNDING_MIN_SAMPLES = 5
        const val EXTERNAL_GROUNDING_WINDOW_MILLIS = 500L
        const val COMMAND_MODE_TIMEOUT_MILLIS = 5_000L
        const val FIRST_TELEMETRY_TIMEOUT_MILLIS = 5_000L
        const val FLIGHT_COMMAND_TIMEOUT_MILLIS = 15_000L
        const val EMERGENCY_TIMEOUT_MILLIS = 3_000L
        const val VIDEO_COMMAND_TIMEOUT_MILLIS = 3_000L
        const val STREAMOFF_ACK_TIMEOUT_MILLIS = 500L
        const val STREAMOFF_CLEANUP_TIMEOUT_MILLIS = 750L
        const val TELEMETRY_STALE_MILLIS = 1_500L
        const val CONNECTION_LOST_MILLIS = 4_000L
        const val HEALTH_CHECK_PERIOD_MILLIS = 250L
        const val GROUNDED_HEIGHT_THRESHOLD_METERS = 0.20f
        const val TAKEOFF_AIRBORNE_HEIGHT_THRESHOLD_METERS = 0.20f
        const val TAKEOFF_STABILIZATION_MIN_SAMPLES = 4
        const val TAKEOFF_STABILIZATION_MIN_DURATION_MILLIS = 300L
        const val TAKEOFF_MAX_VERTICAL_SPEED_CPS = 20
        /**
         * Bounded height stability window threshold across the multi-sample stabilization sequence.
         * Tello ultrasonic/barometer height reporting has a natural ~0.05-0.10m noise floor during hover,
         * so 0.15m reliably suppresses active climb/descent while avoiding false lockouts on sensor jitter.
         */
        const val TAKEOFF_MAX_HEIGHT_VARIATION_METERS = 0.15f
        const val MAX_TRACKING_TRANSITIONS = 100
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
