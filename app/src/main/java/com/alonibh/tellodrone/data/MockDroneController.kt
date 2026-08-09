package com.alonibh.tellodrone.data

import androidx.compose.ui.geometry.Rect
import com.alonibh.tellodrone.domain.ControlAuthority
import com.alonibh.tellodrone.domain.ControllerMode
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneController
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.NetworkSelectionState
import com.alonibh.tellodrone.domain.TelemetrySnapshot
import com.alonibh.tellodrone.domain.TrackedTarget
import com.alonibh.tellodrone.domain.TrackingMode
import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.VideoState
import com.alonibh.tellodrone.domain.isZero
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Interactive development simulation. It contains no hardware, network, video, or ML code. */
class MockDroneController(initialState: DroneSessionState = mockInitialState()) : DroneController {
    private val mutableState = MutableStateFlow(initialState)
    override val state: StateFlow<DroneSessionState> = mutableState.asStateFlow()

    override fun connect() = update {
        it.copy(
            controllerMode = ControllerMode.Mock,
            connection = DroneConnectionState.Connected,
            networkSelection = NetworkSelectionState.Available,
            telemetry = it.telemetry.copy(isFresh = true),
            lastMessage = "Mock drone connected",
        )
    }

    override fun disconnect() = update {
        it.copy(
            connection = DroneConnectionState.Disconnected,
            networkSelection = NetworkSelectionState.Idle,
            flight = FlightState.Grounded,
            tracking = TrackingMode.Off,
            authority = ControlAuthority.Manual,
            target = null,
            manualVector = ManualControlVector(),
            telemetry = it.telemetry.copy(
                heightMeters = 0f,
                speedMetersPerSecond = 0f,
                flightTimeSeconds = 0,
                isFresh = false,
            ),
            lastMessage = "Mock session disconnected",
        )
    }

    override fun takeOff() = update { state ->
        if (state.connection == DroneConnectionState.Connected && state.flight == FlightState.Grounded) {
            state.copy(
                flight = FlightState.Flying,
                telemetry = state.telemetry.copy(heightMeters = 1.2f),
                lastMessage = "Mock takeoff complete",
            )
        } else state.invalid("Takeoff requires a connected, grounded drone")
    }

    override fun land() = update { state ->
        if (state.flight == FlightState.Flying) {
            state.copy(
                flight = FlightState.Grounded,
                tracking = TrackingMode.Off,
                authority = ControlAuthority.Manual,
                target = state.target?.copy(locked = false),
                manualVector = ManualControlVector(),
                telemetry = state.telemetry.copy(heightMeters = 0f, speedMetersPerSecond = 0f),
                lastMessage = "Mock landing complete",
            )
        } else state.invalid("Landing requires a flying drone")
    }

    override fun stopAndHover() = update { state ->
        if (state.flight == FlightState.Flying) {
            state.copy(
                tracking = if (state.target?.locked == true) TrackingMode.TargetLocked else TrackingMode.Off,
                authority = ControlAuthority.Manual,
                manualVector = ManualControlVector(),
                telemetry = state.telemetry.copy(speedMetersPerSecond = 0f),
                lastMessage = "Mock STOP / HOVER: movement cancelled",
            )
        } else state.invalid("STOP / HOVER is available only in flight")
    }

    override fun emergencyMotorKill() = update { state ->
        state.copy(
            flight = FlightState.Emergency,
            tracking = TrackingMode.Off,
            authority = ControlAuthority.Manual,
            target = state.target?.copy(locked = false),
            manualVector = ManualControlVector(),
            telemetry = state.telemetry.copy(heightMeters = 0f, speedMetersPerSecond = 0f),
            lastMessage = "Mock EMERGENCY MOTOR KILL activated",
        )
    }

    override fun setTrackingMode(mode: TrackingMode) = update { state ->
        when (mode) {
            TrackingMode.Off -> state.copy(tracking = TrackingMode.Off, authority = ControlAuthority.Manual)
            TrackingMode.DetectOnly -> if (state.connection == DroneConnectionState.Connected) {
                state.copy(tracking = TrackingMode.DetectOnly, authority = ControlAuthority.Manual, target = mockTarget(false))
            } else state.invalid("Detection requires a connected mock drone")
            TrackingMode.TargetLocked -> if (state.flight == FlightState.Flying && state.target != null) {
                state.copy(tracking = TrackingMode.TargetLocked, authority = ControlAuthority.Manual, target = state.target.copy(locked = true))
            } else state.invalid("Target lock requires a detected target while flying")
            TrackingMode.Follow -> if (state.flight == FlightState.Flying && state.target?.locked == true) {
                state.copy(tracking = TrackingMode.Follow, authority = ControlAuthority.Autonomous)
            } else state.invalid("Follow requires a flying drone and locked target")
        }
    }

    override fun setTargetLock(locked: Boolean) = update { state ->
        if (locked && state.flight != FlightState.Flying) state.invalid("Target lock requires a flying drone")
        else if (locked && state.target == null) state.invalid("Detect a person before locking a target")
        else state.copy(
            target = (state.target ?: mockTarget(false)).copy(locked = locked),
            tracking = if (locked) TrackingMode.TargetLocked else TrackingMode.DetectOnly,
            authority = ControlAuthority.Manual,
        )
    }

    override fun setManualControlVector(vector: ManualControlVector) = update { state ->
        if (state.flight != FlightState.Flying) state.invalid("Manual control requires a flying drone") else {
            val wasFollowing = state.tracking == TrackingMode.Follow
            state.copy(
                tracking = if (wasFollowing) TrackingMode.TargetLocked else state.tracking,
                authority = ControlAuthority.Manual,
                manualVector = vector,
                telemetry = state.telemetry.copy(speedMetersPerSecond = if (vector.isZero()) 0f else 0.3f),
                lastMessage = if (wasFollowing) "Manual override: Follow cancelled" else state.lastMessage,
            )
        }
    }

    override fun setSpeed(percent: Int) = update { it.copy(speedPercent = percent.coerceIn(10, 40)) }

    private fun update(transform: (DroneSessionState) -> DroneSessionState) { mutableState.value = transform(mutableState.value) }
    private fun DroneSessionState.invalid(message: String) = copy(lastMessage = message)
    private fun mockTarget(locked: Boolean) = TrackedTarget(
        boundingBox = Rect(left = .40f, top = .20f, right = .62f, bottom = .82f),
        confidence = .92f,
        estimatedDistanceMeters = 1.8f,
        locked = locked,
    )

    companion object {
        fun mockInitialState() = DroneSessionState(
            controllerMode = ControllerMode.Mock,
            telemetry = TelemetrySnapshot(
                batteryPercent = 78,
                heightMeters = 0f,
                speedMetersPerSecond = 0f,
                velocityXCentimetersPerSecond = 0,
                velocityYCentimetersPerSecond = 0,
                velocityZCentimetersPerSecond = 0,
                flightTimeSeconds = 0,
                temperatureCelsius = 31f,
                isFresh = false,
            ),
            video = VideoState(VideoAvailability.Mock, measuredFps = 30f),
        )
    }
}
