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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val firstTelemetry = CompletableDeferred<TelloTelemetry>()
    private var telemetryJob: Job? = null
    private var healthJob: Job? = null
    private var lastTelemetryAtMillis: Long? = null
    private var closed = false
    private var fatalReported = false

    private val rcLoop = RcControlLoop(
        scope = scope,
        sender = transport::sendRc,
        clock = clock,
        onSendFailure = { error -> scope.launch { failConnection("RC transport failed: ${error.safeMessage()}") } },
    )

    suspend fun connect(): Boolean = commandStateMutex.withLock {
        if (closed) return@withLock false
        mutableState.value = mutableState.value.copy(
            connection = DroneConnectionState.Connecting,
            networkSelection = NetworkSelectionState.Available,
            flight = FlightState.Unknown,
            authority = ControlAuthority.Manual,
            tracking = TrackingMode.Off,
            lastMessage = "Tello Wi-Fi selected; entering SDK mode",
        )
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

        val grounded = (first.heightMeters ?: 0f) <= GROUNDED_HEIGHT_THRESHOLD_METERS
        mutableState.value = mutableState.value.copy(
            connection = DroneConnectionState.Connected,
            flight = if (grounded) FlightState.Grounded else FlightState.Unknown,
            telemetry = first.asSnapshot(isFresh = true),
            lastMessage = if (grounded) "Tello connected and telemetry verified" else
                "Tello connected, but airborne state is uncertain; land before normal commands",
        )
        rcLoop.setHealthy(true)
        rcLoop.start()
        startHealthMonitor()
        true
    }

    suspend fun takeOff() = commandStateMutex.withLock {
        val current = mutableState.value
        if (!current.canTakeOff()) return@withLock invalid("Takeoff requires fresh telemetry from a connected, grounded drone")
        mutableState.value = current.copy(flight = FlightState.TakingOff, lastMessage = "Takeoff in progress")
        when (val result = transport.sendCommand("takeoff", FLIGHT_COMMAND_TIMEOUT_MILLIS)) {
            is TelloCommandResult.Success -> {
                rcLoop.setEnabled(true)
                mutableState.value = mutableState.value.copy(flight = FlightState.Flying, lastMessage = "Takeoff acknowledged")
            }
            is TelloCommandResult.Rejected -> {
                mutableState.value = mutableState.value.copy(flight = FlightState.Grounded, lastMessage = "Takeoff rejected: ${result.response}")
            }
            else -> failConnection("Takeoff result is uncertain: ${result.description()}")
        }
    }

    suspend fun land() = commandStateMutex.withLock {
        val current = mutableState.value
        if (current.connection != DroneConnectionState.Connected ||
            current.flight !in setOf(FlightState.Flying, FlightState.Unknown)
        ) return@withLock invalid("Land requires a connected flying or uncertain-state drone")

        rcLoop.clearAndSendZero()
        rcLoop.setEnabled(false)
        mutableState.value = current.copy(
            flight = FlightState.Landing,
            manualVector = ManualControlVector(),
            lastMessage = "Landing in progress",
        )
        when (val result = transport.sendCommand("land", FLIGHT_COMMAND_TIMEOUT_MILLIS)) {
            is TelloCommandResult.Success -> mutableState.value = mutableState.value.copy(
                flight = FlightState.Grounded,
                manualVector = ManualControlVector(),
                lastMessage = "Landing acknowledged",
            )
            is TelloCommandResult.Rejected -> mutableState.value = mutableState.value.copy(
                flight = FlightState.Unknown,
                lastMessage = "Landing rejected; aircraft state is uncertain: ${result.response}",
            )
            else -> failConnection("Landing result is uncertain: ${result.description()}")
        }
    }

    suspend fun stopAndHover() {
        val current = mutableState.value
        if (current.connection != DroneConnectionState.Connected || current.flight != FlightState.Flying) {
            invalid("STOP / HOVER requires a connected flying drone")
            return
        }
        rcLoop.clearAndSendZero()
        mutableState.value = current.copy(
            authority = ControlAuthority.Manual,
            tracking = TrackingMode.Off,
            manualVector = ManualControlVector(),
            lastMessage = "STOP / HOVER: zero movement sent; aircraft remains flying",
        )
    }

    suspend fun emergencyMotorKill() = commandStateMutex.withLock {
        val current = mutableState.value
        if (current.connection != DroneConnectionState.Connected ||
            current.flight !in setOf(FlightState.TakingOff, FlightState.Flying, FlightState.Landing, FlightState.Unknown)
        ) return@withLock invalid("Emergency motor kill is unavailable while safely grounded or disconnected")

        rcLoop.lockOutAfterZero()
        mutableState.value = current.copy(
            flight = FlightState.Emergency,
            authority = ControlAuthority.Manual,
            tracking = TrackingMode.Off,
            manualVector = ManualControlVector(),
            lastMessage = "EMERGENCY MOTOR KILL sent; further flight commands are locked out",
        )
        // Emergency remains terminal even when its acknowledgement is lost.
        transport.sendCommand("emergency", EMERGENCY_TIMEOUT_MILLIS)
        mutableState.value = mutableState.value.copy(flight = FlightState.Emergency)
    }

    fun publishManualControl(vector: ManualControlVector) {
        val current = mutableState.value
        if (current.connection == DroneConnectionState.Connected &&
            current.flight == FlightState.Flying && current.telemetry.isFresh
        ) {
            rcLoop.publish(vector, current.speedPercent)
            if (current.manualVector != vector || current.tracking != TrackingMode.Off) {
                mutableState.value = current.copy(
                    authority = ControlAuthority.Manual,
                    tracking = TrackingMode.Off,
                    manualVector = vector,
                )
            }
        }
    }

    fun setSpeed(percent: Int) {
        mutableState.value = mutableState.value.copy(speedPercent = percent.coerceIn(10, 40))
    }

    suspend fun refreshConnectionHealth(nowMillis: Long = clock.nowMillis()) {
        val last = lastTelemetryAtMillis ?: return
        val age = nowMillis - last
        if (age >= TELEMETRY_STALE_MILLIS && mutableState.value.telemetry.isFresh) {
            rcLoop.setHealthy(false)
            rcLoop.clearAndSendZero()
            mutableState.value = mutableState.value.copy(
                telemetry = mutableState.value.telemetry.copy(isFresh = false),
                manualVector = ManualControlVector(),
                lastMessage = "Telemetry stale; non-zero RC output inhibited",
            )
        }
        if (age >= CONNECTION_LOST_MILLIS) failConnection("Tello telemetry connection lost")
    }

    suspend fun disconnect(): Boolean = commandStateMutex.withLock {
        val current = mutableState.value
        if (current.flight in setOf(FlightState.TakingOff, FlightState.Flying, FlightState.Landing, FlightState.Unknown)) {
            invalid("Land before disconnecting; aircraft state must be safely grounded")
            return@withLock false
        }
        closeResources(sendFinalZero = current.flight != FlightState.Emergency)
        mutableState.value = current.copy(
            connection = DroneConnectionState.Disconnected,
            networkSelection = NetworkSelectionState.Idle,
            flight = if (current.flight == FlightState.Emergency) FlightState.Emergency else FlightState.Grounded,
            telemetry = current.telemetry.copy(isFresh = false),
            manualVector = ManualControlVector(),
            lastMessage = "Tello session disconnected",
        )
        true
    }

    suspend fun networkLost(message: String = "Tello Wi-Fi network lost") = failConnection(message)

    private fun startTelemetryCollection() {
        if (telemetryJob?.isActive == true) return
        telemetryJob = scope.launch {
            transport.telemetry.collect { sample ->
                lastTelemetryAtMillis = sample.receivedAtMonotonicMillis
                firstTelemetry.complete(sample)
                val current = mutableState.value
                mutableState.value = current.copy(telemetry = sample.asSnapshot(isFresh = true))
                if (current.connection == DroneConnectionState.Connected) rcLoop.setHealthy(true)
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

    private suspend fun failConnection(message: String) {
        if (closed || mutableState.value.connection == DroneConnectionState.Error) return
        val wasEmergency = mutableState.value.flight == FlightState.Emergency
        rcLoop.setHealthy(false)
        if (!wasEmergency) rcLoop.clearAndSendZero()
        mutableState.value = mutableState.value.copy(
            connection = DroneConnectionState.Error,
            networkSelection = NetworkSelectionState.Lost,
            flight = if (wasEmergency) FlightState.Emergency else FlightState.Unknown,
            telemetry = mutableState.value.telemetry.copy(isFresh = false),
            authority = ControlAuthority.Manual,
            tracking = TrackingMode.Off,
            manualVector = ManualControlVector(),
            lastMessage = message,
        )
        closeResources(sendFinalZero = false)
        if (!fatalReported) {
            fatalReported = true
            onFatalConnectionLoss(message)
        }
    }

    private suspend fun closeResources(sendFinalZero: Boolean) {
        if (closed) return
        closed = true
        healthJob?.cancel()
        telemetryJob?.cancel()
        rcLoop.shutdown(sendFinalZero)
        transport.close()
    }

    private fun invalid(message: String) {
        mutableState.value = mutableState.value.copy(lastMessage = message)
    }

    private fun DroneSessionState.canTakeOff() =
        connection == DroneConnectionState.Connected && flight == FlightState.Grounded && telemetry.isFresh

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
        TelloCommandResult.Timeout -> "timed out"
        is TelloCommandResult.Failure -> cause.safeMessage()
    }

    private fun Throwable.safeMessage() = message ?: javaClass.simpleName

    companion object {
        const val COMMAND_MODE_TIMEOUT_MILLIS = 5_000L
        const val FIRST_TELEMETRY_TIMEOUT_MILLIS = 5_000L
        const val FLIGHT_COMMAND_TIMEOUT_MILLIS = 15_000L
        const val EMERGENCY_TIMEOUT_MILLIS = 3_000L
        const val TELEMETRY_STALE_MILLIS = 1_500L
        const val CONNECTION_LOST_MILLIS = 4_000L
        const val HEALTH_CHECK_PERIOD_MILLIS = 250L
        const val GROUNDED_HEIGHT_THRESHOLD_METERS = 0.20f
    }
}
