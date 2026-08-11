package com.alonibh.tellodrone.tello

import com.alonibh.tellodrone.domain.ControlAuthority
import com.alonibh.tellodrone.domain.ControllerMode
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.NetworkSelectionState
import com.alonibh.tellodrone.domain.TelemetrySnapshot
import com.alonibh.tellodrone.domain.TrackingMode
import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.VideoState
import com.alonibh.tellodrone.domain.withPersonDetectionVideoState
import com.alonibh.tellodrone.domain.isZero
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
    private val onFatalConnectionLoss: (String) -> Unit = {},
    initialState: DroneSessionState = DroneSessionState(
        controllerMode = ControllerMode.Real,
        connection = DroneConnectionState.Connecting,
        networkSelection = NetworkSelectionState.Available,
        flight = FlightState.Unknown,
    ),
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

    private val rcLoop = RcControlLoop(
        scope = scope,
        sender = transport::sendRc,
        clock = clock,
        onSendFailure = { error -> scope.launch { failConnection("RC transport failed: ${error.safeMessage()}") } },
    )

    suspend fun connect(): Boolean = commandStateMutex.withLock {
        if (closed) return@withLock false
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
                lastMessage = if (grounded) "Tello connected and telemetry verified" else
                    "Tello connected, but airborne state is uncertain; land before normal commands",
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
        rcLoop.lockOutAfterZero()
        video?.setPersonDetectionEnabled(false)
        mutableState.update {
            it.copy(
                flight = FlightState.Emergency,
                authority = ControlAuthority.Manual,
                tracking = TrackingMode.Off,
                personDetections = emptyList(),
                target = null,
                manualVector = ManualControlVector(),
                hoverActive = false,
                lastMessage = "EMERGENCY MOTOR KILL sent; further flight commands are locked out",
            )
        }
        // Emergency remains terminal even when its acknowledgement is lost.
        transport.sendCommand("emergency", EMERGENCY_TIMEOUT_MILLIS)
    }

    fun publishManualControl(vector: ManualControlVector) {
        if (requiresNeutralInput(vector)) return
        val current = mutableState.value
        if (current.connection == DroneConnectionState.Connected && current.flight == FlightState.Flying && current.telemetry.isFresh) {
            rcLoop.publish(vector, current.speedPercent)
            mutableState.update { state ->
                if (state.connection == DroneConnectionState.Connected && state.flight == FlightState.Flying && state.telemetry.isFresh &&
                    (state.manualVector != vector || state.authority != ControlAuthority.Manual)
                ) {
                    state.copy(
                        authority = ControlAuthority.Manual,
                        manualVector = vector,
                    hoverActive = if (vector.isZero()) state.hoverActive else false,
                )
                } else state
            }
        }
    }

    fun setSpeed(percent: Int) {
        mutableState.update { it.copy(speedPercent = percent.coerceIn(10, 40)) }
    }

    fun setTrackingMode(mode: TrackingMode) {
        val activeVideo = video
        when (mode) {
            TrackingMode.Off -> {
                activeVideo?.setPersonDetectionEnabled(false)
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

    suspend fun refreshConnectionHealth(nowMillis: Long = clock.nowMillis()) {
        val last = lastTelemetryAtMillis ?: return
        val age = nowMillis - last
        // A receive that arrived after this health check started wins; never turn a fresh link into
        // a false terminal loss based on an obsolete timestamp.
        if (lastTelemetryAtMillis != last) return
        if (age >= TELEMETRY_STALE_MILLIS && mutableState.value.telemetry.isFresh) {
            requireManualNeutral()
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
                if (!closed) mutableState.update { it.withPersonDetectionVideoState(videoState) }
            }
        }
    }

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
