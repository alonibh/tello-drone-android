package com.alonibh.tellodrone.data

import com.alonibh.tellodrone.domain.ControlAuthority
import com.alonibh.tellodrone.domain.ControllerMode
import com.alonibh.tellodrone.domain.DetectorBackendPreference
import com.alonibh.tellodrone.domain.DetectorModel
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneController
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.NetworkSelectionState
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.SimulatorDiagnostics
import com.alonibh.tellodrone.domain.SimulatorScenarioAction
import com.alonibh.tellodrone.domain.TelemetrySnapshot
import com.alonibh.tellodrone.domain.TrackingMode
import com.alonibh.tellodrone.domain.VideoState
import com.alonibh.tellodrone.simulator.SimulatorPlant
import com.alonibh.tellodrone.simulator.SimulatorTelloTransport
import com.alonibh.tellodrone.simulator.SimulatorTransportSnapshot
import com.alonibh.tellodrone.simulator.SimulatorVideoController
import com.alonibh.tellodrone.tello.MonotonicClock
import com.alonibh.tellodrone.tello.TelloFlightSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application-owned in-app simulator adapter. It never calls the physical service or network. */
class MockDroneController(
    initialState: DroneSessionState = simulatorInitialState(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    parentScope: CoroutineScope? = null,
) : DroneController {
    private val applicationScope = parentScope ?: CoroutineScope(SupervisorJob() + dispatcher)
    private val lifecycleMutex = Mutex()
    private val mutableState = MutableStateFlow(
        initialState.copy(
            controllerMode = ControllerMode.Mock,
            simulatorDiagnostics = initialState.simulatorDiagnostics ?: SimulatorDiagnostics(),
        ),
    )
    override val state: StateFlow<DroneSessionState> = mutableState.asStateFlow()
    @Volatile private var runtime: Runtime? = null

    override fun connect() {
        if (mutableState.value.connection in setOf(DroneConnectionState.Connecting, DroneConnectionState.Connected)) return
        mutableState.value = simulatorInitialState().copy(
            connection = DroneConnectionState.Connecting,
            networkSelection = NetworkSelectionState.Available,
            flight = FlightState.Unknown,
            lastMessage = "Starting in-app simulator",
        )
        applicationScope.launch {
            lifecycleMutex.withLock {
                stopRuntime(force = true)
                startFreshRuntime()
            }
        }
    }

    override fun disconnect() {
        applicationScope.launch {
            lifecycleMutex.withLock {
                val active = runtime
                if (active == null) {
                    mutableState.value = simulatorInitialState(lastMessage = "Simulator stopped")
                    return@withLock
                }
                if (active.session.disconnect()) {
                    mutableState.value = active.session.state.value.copy(
                        controllerMode = ControllerMode.Mock,
                        simulatorDiagnostics = diagnostics(active.transport.snapshot.value),
                        lastMessage = "Simulator stopped",
                    )
                    active.scope.cancel()
                    if (runtime === active) runtime = null
                }
            }
        }
    }

    override fun takeOff() = launchSession { it.takeOff() }
    override fun land() = launchSession { it.land() }
    override fun stopAndHover() = launchSession { it.stopAndHover() }
    override fun emergencyMotorKill() = launchSession { it.emergencyMotorKill() }
    override fun setTrackingMode(mode: TrackingMode) { runtime?.session?.setTrackingMode(mode) }
    override fun selectTarget(detection: PersonDetection) { runtime?.session?.selectTarget(detection) }
    override fun setCurrentFollowDistance() { runtime?.session?.setCurrentFollowDistance() }
    override fun setYawFollowArmed(armed: Boolean) { runtime?.session?.setYawFollowArmed(armed) }
    override fun setManualControlVector(vector: ManualControlVector) { runtime?.session?.publishManualControl(vector) }
    override fun setSpeed(percent: Int) { runtime?.session?.setSpeed(percent) }

    override fun setDetectorModel(model: DetectorModel) = simulatorOnlyConfigurationMessage()
    override fun setDetectorBackendPreference(preference: DetectorBackendPreference) = simulatorOnlyConfigurationMessage()
    override fun setDetectorConfidenceThreshold(threshold: Float) = simulatorOnlyConfigurationMessage()

    override fun applySimulatorScenario(action: SimulatorScenarioAction) {
        if (action == SimulatorScenarioAction.Reset) {
            applicationScope.launch {
                lifecycleMutex.withLock {
                    val reconnect = runtime != null || mutableState.value.connection == DroneConnectionState.Connected
                    stopRuntime(force = true)
                    mutableState.value = simulatorInitialState(lastMessage = "Simulator scenario reset")
                    if (reconnect) startFreshRuntime()
                }
            }
            return
        }
        val plant = runtime?.plant ?: return
        when (action) {
            SimulatorScenarioAction.MovePersonLeft -> plant.movePersonLeft()
            SimulatorScenarioAction.MovePersonRight -> plant.movePersonRight()
            SimulatorScenarioAction.CenterPerson -> plant.centerPerson()
            SimulatorScenarioAction.TogglePersonVisibility -> plant.togglePersonVisibility()
            SimulatorScenarioAction.Reset -> Unit
        }
    }

    private suspend fun startFreshRuntime() {
        val runtimeScope = CoroutineScope(
            applicationScope.coroutineContext + SupervisorJob(applicationScope.coroutineContext[Job]),
        )
        val plant = SimulatorPlant()
        val monotonicClock = MonotonicClock { System.nanoTime() / 1_000_000L }
        val transport = SimulatorTelloTransport(runtimeScope, plant, monotonicClock)
        val video = SimulatorVideoController(runtimeScope, plant)
        val session = TelloFlightSession(
            transport = transport,
            scope = runtimeScope,
            clock = monotonicClock,
            video = video,
            initialState = simulatorInitialState().copy(
                connection = DroneConnectionState.Connecting,
                networkSelection = NetworkSelectionState.Available,
                flight = FlightState.Unknown,
            ),
        )
        val next = Runtime(runtimeScope, plant, transport, session)
        runtime = next
        runtimeScope.launch {
            session.state.collect { sessionState ->
                if (runtime === next) {
                    mutableState.value = sessionState.copy(
                        controllerMode = ControllerMode.Mock,
                        simulatorDiagnostics = diagnostics(transport.snapshot.value),
                    )
                }
            }
        }
        runtimeScope.launch {
            transport.snapshot.collect { transportState ->
                if (runtime === next) {
                    mutableState.value = mutableState.value.copy(
                        simulatorDiagnostics = diagnostics(transportState),
                    )
                }
            }
        }
        if (!session.connect() && runtime === next) {
            mutableState.value = session.state.value.copy(
                controllerMode = ControllerMode.Mock,
                simulatorDiagnostics = diagnostics(transport.snapshot.value),
            )
        }
    }

    private suspend fun stopRuntime(force: Boolean) {
        val active = runtime ?: return
        if (force && !active.transport.isClosed()) {
            active.session.networkLost("Simulator runtime replaced")
        }
        active.scope.cancel()
        if (runtime === active) runtime = null
    }

    private fun launchSession(block: suspend (TelloFlightSession) -> Unit) {
        val active = runtime ?: return
        active.scope.launch { block(active.session) }
    }

    private fun simulatorOnlyConfigurationMessage() {
        mutableState.value = mutableState.value.copy(
            lastMessage = "Detector model, backend, threshold, and benchmark controls do not apply to synthetic detection",
        )
    }

    private fun diagnostics(snapshot: SimulatorTransportSnapshot) = SimulatorDiagnostics(
        lateralRc = snapshot.latestRc.lateral,
        forwardRc = snapshot.latestRc.forward,
        verticalRc = snapshot.latestRc.vertical,
        yawRc = snapshot.latestRc.yaw,
        personHorizontalPosition = snapshot.plant.horizontalPosition,
        personHorizontalError = snapshot.plant.horizontalError,
        personVisible = snapshot.plant.personVisible,
    )

    private data class Runtime(
        val scope: CoroutineScope,
        val plant: SimulatorPlant,
        val transport: SimulatorTelloTransport,
        val session: TelloFlightSession,
    )

    companion object {
        fun simulatorInitialState(lastMessage: String? = null) = DroneSessionState(
            controllerMode = ControllerMode.Mock,
            connection = DroneConnectionState.Disconnected,
            networkSelection = NetworkSelectionState.Idle,
            flight = FlightState.Grounded,
            authority = ControlAuthority.Manual,
            telemetry = TelemetrySnapshot(
                batteryPercent = 100,
                heightMeters = 0f,
                speedMetersPerSecond = 0f,
                velocityXCentimetersPerSecond = 0,
                velocityYCentimetersPerSecond = 0,
                velocityZCentimetersPerSecond = 0,
                flightTimeSeconds = 0,
                temperatureCelsius = 25f,
                isFresh = false,
            ),
            video = VideoState(),
            simulatorDiagnostics = SimulatorDiagnostics(),
            lastMessage = lastMessage,
        )

        /** Retained source-level alias for previews and downstream callers. */
        fun mockInitialState() = simulatorInitialState()
    }
}
