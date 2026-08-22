package com.alonibh.tellodrone.tello

import com.alonibh.tellodrone.domain.ControlAuthority
import com.alonibh.tellodrone.domain.DryRunFollowPlanner
import com.alonibh.tellodrone.domain.FollowPlannerConfig
import com.alonibh.tellodrone.domain.FollowDistanceCalibrator
import com.alonibh.tellodrone.domain.FollowDistanceCalibrationState
import com.alonibh.tellodrone.domain.FollowDistanceEligibility
import com.alonibh.tellodrone.domain.FollowDistanceEligibilityReason
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DetectorBackendPreference
import com.alonibh.tellodrone.domain.DetectorModel
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.NetworkSelectionState
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.domain.TargetAssociationEngine
import com.alonibh.tellodrone.domain.TargetAssociationResult
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.TargetSelection
import com.alonibh.tellodrone.domain.TelemetrySnapshot
import com.alonibh.tellodrone.domain.TrackingErrorEngine
import com.alonibh.tellodrone.domain.TrackingMode
import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.VideoState
import com.alonibh.tellodrone.domain.YawFollowDecision
import com.alonibh.tellodrone.domain.YawFollowGate
import com.alonibh.tellodrone.domain.YawFollowInput
import com.alonibh.tellodrone.domain.YawFollowReason
import com.alonibh.tellodrone.domain.YawFollowState
import com.alonibh.tellodrone.domain.withPersonDetectionVideoState
import com.alonibh.tellodrone.domain.isZero
import com.alonibh.tellodrone.vision.PersonDetectionStore
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
    private var videoStateJob: Job? = null
    @Volatile private var lastTelemetryAtMillis: Long? = null
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
    private var yawFollowGeneration: Long? = null

    private val rcLoop = RcControlLoop(
        scope = scope,
        sender = transport::sendRc,
        clock = clock,
        onSendFailure = { error -> scope.launch { failConnection("RC transport failed: ${error.safeMessage()}") } },
    )

    suspend fun connect(): Boolean = commandStateMutex.withLock {
        if (closed) return@withLock false
        resetRealTracking()
        val yawDecision = resetYawFollowForNewSession()
        takeoffAcknowledged = false
        landingAcknowledged = false
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
        when (val result = transport.sendCommand("command", COMMAND_MODE_TIMEOUT_MILLIS)) {
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
        rcLoop.setHealthy(true)
        rcLoop.start()
        startHealthMonitor()
        startVideoStateCollection()
        startVideoStreaming()
        true
    }

    suspend fun takeOff() = commandStateMutex.withLock {
        val current = mutableState.value
        if (!current.canTakeOff()) return@withLock invalid("Takeoff requires fresh telemetry from a connected, grounded drone")
        takeoffAcknowledged = false
        landingAcknowledged = false
        requireManualNeutral()
        mutableState.update {
            it.copy(flight = FlightState.TakingOff, manualVector = ManualControlVector(), hoverActive = false, lastMessage = "Takeoff in progress")
        }
        when (val result = transport.sendCommand("takeoff", FLIGHT_COMMAND_TIMEOUT_MILLIS)) {
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
        requireManualNeutral()
        latchYawFollow(YawFollowReason.LANDING)
        rcLoop.clearAndSendZero()
        rcLoop.setEnabled(false)
        mutableState.update {
            it.copy(
                flight = FlightState.Landing,
                manualVector = ManualControlVector(),
                hoverActive = false,
                lastMessage = "Landing in progress",
            )
        }
        when (val result = transport.sendCommand("land", FLIGHT_COMMAND_TIMEOUT_MILLIS)) {
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
        requireManualNeutral()
        latchYawFollow(YawFollowReason.EMERGENCY)
        rcLoop.lockOutAfterZero()
        video?.setPersonDetectionEnabled(false)
        resetRealTracking()
        mutableState.update {
            it.copy(
                flight = FlightState.Emergency,
                authority = ControlAuthority.Manual,
                tracking = TrackingMode.Off,
                personDetections = emptyList(),
                target = null,
                followDistanceReference = null,
                followDistanceCalibrationState = FollowDistanceCalibrationState.NotSet,
                manualVector = ManualControlVector(),
                hoverActive = false,
                lastMessage = "EMERGENCY MOTOR KILL sent; further flight commands are locked out",
            )
        }
        // Emergency remains terminal even when its acknowledgement is lost.
        transport.sendCommand("emergency", EMERGENCY_TIMEOUT_MILLIS)
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
                    rcLoop.publish(vector, state.speedPercent)
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
                    rcLoop.publish(vector, state.speedPercent)
                    updateYawFollowState { it.copy(manualVector = vector) }
                }
            }
        }
    }

    fun setYawFollowArmed(armed: Boolean) {
        var zeroGeneration: Long? = null
        synchronized(yawFollowLock) {
            if (armed) {
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
                rcLoop.publishAutonomousYaw(decision.yawRc, generation)
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
        mutableState.update { it.copy(speedPercent = percent.coerceIn(10, 40)) }
    }

    fun setTrackingMode(mode: TrackingMode) {
        val activeVideo = video
        when (mode) {
            TrackingMode.Off -> {
                latchYawFollowAndSendZero(YawFollowReason.DETECTOR_UNAVAILABLE)
                activeVideo?.setPersonDetectionEnabled(false)
                activeVideo?.cancelDetectorBenchmark()
                resetRealTracking()
                mutableState.update {
                    it.copy(
                        tracking = TrackingMode.Off,
                        authority = ControlAuthority.Manual,
                        personDetections = emptyList(),
                        target = null,
                        lastMessage = "Person detection off",
                    )
                }
            }
            TrackingMode.DetectOnly -> {
                val current = mutableState.value
                if (current.connection != DroneConnectionState.Connected ||
                    current.video.availability != VideoAvailability.Streaming
                ) {
                    invalid("Person detection requires a connected live preview")
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

    fun setDetectorModel(model: DetectorModel) {
        val current = mutableState.value
        if (current.tracking != TrackingMode.Off) {
            invalid("Turn person detection off before changing model")
            return
        }
        val changed = video?.setPersonDetectorModel(model)
            ?: Result.failure(IllegalStateException("Video analysis is unavailable"))
        if (changed.isFailure) {
            invalid(changed.exceptionOrNull()?.message ?: "Detector model could not be changed")
            return
        }
        resetRealTracking()
        mutableState.update { state ->
            state.copy(
                video = state.video.copy(detectorModel = model),
                lastMessage = "${model.displayName} selected",
            )
        }
    }

    fun setDetectorBackendPreference(preference: DetectorBackendPreference) {
        val current = mutableState.value
        if (current.tracking != TrackingMode.Off) {
            invalid("Turn person detection off before changing backend")
            return
        }
        val changed = video?.setPersonDetectorBackendPreference(preference)
            ?: Result.failure(IllegalStateException("Video analysis is unavailable"))
        if (changed.isFailure) {
            invalid(changed.exceptionOrNull()?.message ?: "Detector backend could not be changed")
            return
        }
        resetRealTracking()
        mutableState.update { state ->
            state.copy(
                video = state.video.copy(detectorBackendPreference = preference),
                lastMessage = when (preference) {
                    DetectorBackendPreference.Accelerated -> "GPU preferred; CPU fallback enabled"
                    DetectorBackendPreference.Cpu -> "CPU comparison backend selected"
                },
            )
        }
    }

    fun setDetectorConfidenceThreshold(threshold: Float) {
        val current = mutableState.value
        if (current.tracking != TrackingMode.Off) {
            invalid("Turn person detection off before changing confidence threshold")
            return
        }
        val normalized = com.alonibh.tellodrone.vision.normalizeConfidenceThreshold(threshold)
        val changed = video?.setPersonDetectorConfidenceThreshold(normalized)
            ?: Result.failure(IllegalStateException("Video analysis is unavailable"))
        if (changed.isFailure) {
            invalid(changed.exceptionOrNull()?.message ?: "Detector confidence threshold could not be changed")
            return
        }
        resetRealTracking()
        mutableState.update { state ->
            state.copy(
                video = state.video.copy(detectorConfidenceThreshold = normalized),
                lastMessage = "Person confidence threshold set to ${(normalized * 100f).toInt()}%",
            )
        }
    }

    fun runDetectorBenchmark() {
        val current = mutableState.value
        if (current.tracking != TrackingMode.Off) {
            invalid("Turn person detection off before running the benchmark")
            return
        }
        val started = video?.runDetectorBenchmark()
            ?: Result.failure(IllegalStateException("Video analysis is unavailable"))
        if (started.isSuccess) {
            resetRealTracking()
            mutableState.update { it.copy(tracking = TrackingMode.DetectOnly, authority = ControlAuthority.Manual, personDetections = emptyList(), target = null, lastMessage = "Running 30-second detector benchmark") }
        } else invalid(started.exceptionOrNull()?.message ?: "Detector benchmark could not start")
    }

    fun cancelDetectorBenchmark() {
        video?.cancelDetectorBenchmark()
        resetRealTracking()
        mutableState.update { it.copy(tracking = TrackingMode.Off, authority = ControlAuthority.Manual, personDetections = emptyList(), target = null, lastMessage = "Detector benchmark cancelled") }
    }

    /**
     * Explicit real-mode selection boundary. The service session accepts only the exact object
     * currently rendered from the newest fresh detector result; it never derives a target itself.
     */
    fun selectTarget(detection: PersonDetection) = synchronized(trackingLock) {
        val nowNanos = sourceNowNanos()
        val current = mutableState.value
        if (!current.isSelectableRealDetection(detection, latestAcceptedDetectorFrame, nowNanos)) {
            invalid("Select a fresh person box from the current detector frame")
            return@synchronized
        }
        val target = TargetSelection.select(detection)
        trackingErrors.reset()
        dryRunPlanner.reset()
        lastPlannerFrameTimestampNanos = detection.sourceTimestampNanos
        val errors = trackingErrors.update(target, targetFresh = true)
        mutableState.update { state ->
            if (!state.isSelectableRealDetection(detection, latestAcceptedDetectorFrame, nowNanos)) state else {
                state.copy(
                    tracking = TrackingMode.TargetLocked,
                    authority = ControlAuthority.Manual,
                    target = target,
                    trackingErrors = errors,
                    targetAssociationState = TargetAssociationState.Selected,
                    // A selection has no preceding detector-result interval. The planner fail-closes.
                    dryRunControlIntent = dryRunPlanner.plan(errors, TargetAssociationState.Selected, Float.NaN),
                    followDistanceReference = null,
                    followDistanceCalibrationState = FollowDistanceCalibrationState.NotSet,
                    lastMessage = "Real target selected; dry run only, no commands sent",
                )
            }
        }
        reconcileYawFollow()
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
            requireManualNeutral()
            latchYawFollow(YawFollowReason.TELEMETRY_STALE)
            rcLoop.setHealthy(false)
            rcLoop.clearAndSendZero()
            mutableState.update { state ->
                if (state.telemetry.isFresh) {
                    state.copy(
                        telemetry = state.telemetry.copy(isFresh = false),
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
                var becameFlying = false
                var becameGrounded = false
                mutableState.update { current ->
                    if (closed || current.connection !in setOf(DroneConnectionState.Connecting, DroneConnectionState.Connected)) {
                        current
                    } else {
                        val nextFlight = when {
                            current.connection == DroneConnectionState.Connected &&
                                current.flight == FlightState.TakingOff && takeoffAcknowledged && sample.isVerifiedAirborne() -> {
                                becameFlying = true
                                FlightState.Flying
                            }
                            current.connection == DroneConnectionState.Connected &&
                                current.flight == FlightState.Landing && landingAcknowledged && sample.isVerifiedGrounded() -> {
                                becameGrounded = true
                                FlightState.Grounded
                            }
                            else -> current.flight
                        }
                        current.copy(
                            telemetry = sample.asSnapshot(isFresh = true),
                            flight = nextFlight,
                            manualVector = if (nextFlight == FlightState.Flying) current.manualVector else ManualControlVector(),
                            lastMessage = when {
                                becameFlying -> "Takeoff verified by airborne telemetry"
                                becameGrounded -> "Landing verified by grounded telemetry"
                                else -> current.lastMessage
                            },
                        )
                    }
                }
                if (becameFlying) {
                    takeoffAcknowledged = false
                    rcLoop.setEnabled(true)
                }
                if (becameGrounded) landingAcknowledged = false
                if (!closed && mutableState.value.connection == DroneConnectionState.Connected) rcLoop.setHealthy(true)
                reconcileYawFollow()
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
                mutableState.update { current ->
                    val baseline = current.withPersonDetectionVideoState(videoState)
                    when {
                        newAcceptedFrame != null && current.target != null ->
                            applyAssociation(baseline, current.target, newAcceptedFrame)
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
            }
        }
        reconcileYawFollow(publishActive)
    }

    private fun applyAssociation(
        baseline: DroneSessionState,
        currentTarget: com.alonibh.tellodrone.domain.TrackedTarget,
        frame: DetectorFrameIdentity,
    ): DroneSessionState {
        val result = targetAssociation.associate(
            selectedTarget = currentTarget,
            frameSequence = frame.sequence,
            sourceTimestampNanos = frame.sourceTimestampNanos,
            detections = baseline.personDetections,
        )
        if (result is TargetAssociationResult.Ignored) return baseline
        val dtSeconds = lastPlannerFrameTimestampNanos
            ?.let { (frame.sourceTimestampNanos - it) / 1_000_000_000f }
            ?: Float.NaN
        lastPlannerFrameTimestampNanos = frame.sourceTimestampNanos
        return when (result) {
            is TargetAssociationResult.Matched -> {
                val calibration = acceptCalibrationSample(baseline, result.target, frame)
                val errors = trackingErrors.update(result.target, targetFresh = true, distanceReference = calibration.reference)
                baseline.copy(
                    tracking = TrackingMode.TargetLocked,
                    authority = ControlAuthority.Manual,
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
    }

    private fun resetRealTracking() = synchronized(trackingLock) { resetRealTrackingLocked() }

    private fun resetYawFollowForNewSession(): YawFollowDecision = synchronized(yawFollowLock) {
        yawFollowGeneration = null
        yawFollowGate.disarm()
    }

    private fun reconcileYawFollow(publishActive: Boolean = false) {
        var zeroGeneration: Long? = null
        synchronized(yawFollowLock) {
            val current = mutableState.value
            val previous = current.yawFollowDecision
            val decision = yawFollowGate.evaluate(current.toYawFollowInput())
            if (decision.state == YawFollowState.ACTIVE) {
                val generation = yawFollowGeneration
                    ?.takeIf { previous.state == YawFollowState.ACTIVE }
                    ?: rcLoop.beginAutonomousYaw().also { yawFollowGeneration = it }
                if (publishActive || previous.state != YawFollowState.ACTIVE) {
                    rcLoop.publishAutonomousYaw(decision.yawRc, generation)
                }
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
        when (val result = transport.sendCommand("streamon", VIDEO_COMMAND_TIMEOUT_MILLIS)) {
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
                    video = VideoState(VideoAvailability.Error, errorReason = message),
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
            requireManualNeutral()
            healthJob?.cancel()
            telemetryJob?.cancel()
            videoStateJob?.cancel()
            rcLoop.shutdown(sendFinalZero)
            video?.close()
            if (requestStreamOff) {
                withTimeoutOrNull(STREAMOFF_CLEANUP_TIMEOUT_MILLIS) {
                    transport.sendCommand("streamoff", STREAMOFF_ACK_TIMEOUT_MILLIS)
                }
            }
            transport.close()
        }
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

    private fun DroneSessionState.isSelectableRealDetection(
        detection: PersonDetection,
        newestAcceptedFrame: DetectorFrameIdentity?,
        nowNanos: Long,
    ): Boolean {
        val currentFrame = video.detectorFrameIdentity()
        val ageNanos = nowNanos - detection.sourceTimestampNanos
        return connection == DroneConnectionState.Connected &&
            video.availability == VideoAvailability.Streaming &&
            video.personDetectionState == PersonDetectionState.Detecting &&
            currentFrame != null && currentFrame == newestAcceptedFrame &&
            detection.frameSequence == currentFrame.sequence &&
            detection.sourceTimestampNanos == currentFrame.sourceTimestampNanos &&
            ageNanos in 0 until PersonDetectionStore.STALE_AFTER_NANOS &&
            personDetections.any { it === detection }
    }

    private fun DroneSessionState.canTakeOff() =
        connection == DroneConnectionState.Connected && flight == FlightState.Grounded && telemetry.isFresh

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
        receivedAt = receivedAt,
        isFresh = isFresh,
    )

    private fun TelloCommandResult.description(): String = when (this) {
        is TelloCommandResult.Success -> response
        is TelloCommandResult.Rejected -> response.ifBlank { "rejected" }
        TelloCommandResult.Timeout -> "response timed out"
        is TelloCommandResult.Failure -> cause.safeMessage()
    }

    private fun Throwable.safeMessage() = message ?: javaClass.simpleName

    companion object {
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
    }
}
