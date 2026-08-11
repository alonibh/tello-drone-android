package com.alonibh.tellodrone.data

import com.alonibh.tellodrone.domain.ControlAuthority
import com.alonibh.tellodrone.domain.ControllerMode
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneController
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.NetworkSelectionState
import com.alonibh.tellodrone.domain.NormalizedBoundingBox
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.domain.TelemetrySnapshot
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
            video = it.video.copy(
                personDetectionState = PersonDetectionState.Off,
                personDetections = emptyList(),
            ),
            personDetections = emptyList(),
            target = null,
            manualVector = ManualControlVector(),
            hoverActive = false,
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
                hoverActive = false,
                telemetry = state.telemetry.copy(heightMeters = 1.2f),
                lastMessage = "Mock takeoff complete",
            )
        } else state.invalid("Takeoff requires a connected, grounded drone")
    }

    override fun land() = update { state ->
        if (state.flight == FlightState.Flying) {
            state.copy(
                flight = FlightState.Grounded,
                authority = ControlAuthority.Manual,
                manualVector = ManualControlVector(),
                hoverActive = false,
                telemetry = state.telemetry.copy(heightMeters = 0f, speedMetersPerSecond = 0f),
                lastMessage = "Mock landing complete",
            )
        } else state.invalid("Landing requires a flying drone")
    }

    override fun stopAndHover() = update { state ->
        if (state.flight == FlightState.Flying) {
            state.copy(
                authority = ControlAuthority.Manual,
                manualVector = ManualControlVector(),
                hoverActive = true,
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
            video = state.video.copy(
                personDetectionState = PersonDetectionState.Off,
                personDetections = emptyList(),
            ),
            personDetections = emptyList(),
            target = null,
            manualVector = ManualControlVector(),
            hoverActive = false,
            telemetry = state.telemetry.copy(heightMeters = 0f, speedMetersPerSecond = 0f),
            lastMessage = "Mock EMERGENCY MOTOR KILL activated",
        )
    }

    override fun setTrackingMode(mode: TrackingMode) = update { state ->
        when (mode) {
            TrackingMode.Off -> state.copy(
                tracking = TrackingMode.Off,
                authority = ControlAuthority.Manual,
                video = state.video.copy(personDetectionState = PersonDetectionState.Off),
                personDetections = emptyList(),
                target = null,
            )
            TrackingMode.DetectOnly -> if (state.connection == DroneConnectionState.Connected) {
                val detections = listOf(mockPersonDetection())
                state.copy(
                    tracking = TrackingMode.DetectOnly,
                    authority = ControlAuthority.Manual,
                    video = state.video.copy(
                        personDetectionState = PersonDetectionState.Detecting,
                        personDetections = detections,
                    ),
                    personDetections = detections,
                    target = null,
                )
            } else state.invalid("Detection requires a connected mock drone")
            TrackingMode.TargetLocked, TrackingMode.Follow ->
                state.invalid("Target lock and Follow are not available in Phase 4A")
        }
    }

    override fun setTargetLock(locked: Boolean) = update {
        it.invalid("Target lock is not available in Phase 4A")
    }

    override fun setManualControlVector(vector: ManualControlVector) = update { state ->
        if (state.flight != FlightState.Flying) state.invalid("Manual control requires a flying drone") else {
            state.copy(
                authority = ControlAuthority.Manual,
                manualVector = vector,
                hoverActive = if (vector.isZero()) state.hoverActive else false,
                telemetry = state.telemetry.copy(speedMetersPerSecond = if (vector.isZero()) 0f else 0.3f),
            )
        }
    }

    override fun setSpeed(percent: Int) = update { it.copy(speedPercent = percent.coerceIn(10, 40)) }

    private fun update(transform: (DroneSessionState) -> DroneSessionState) { mutableState.value = transform(mutableState.value) }
    private fun DroneSessionState.invalid(message: String) = copy(lastMessage = message)
    private fun mockPersonDetection() = PersonDetection(
        boundingBox = NormalizedBoundingBox(left = .40f, top = .20f, right = .62f, bottom = .82f),
        confidence = .92f,
        frameSequence = 1L,
        sourceTimestampNanos = System.nanoTime(),
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
