package com.alonibh.tellodrone.data

import com.alonibh.tellodrone.domain.ControlAuthority
import com.alonibh.tellodrone.domain.ControllerMode
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DetectorBackendPreference
import com.alonibh.tellodrone.domain.DroneController
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.DryRunFollowPlanner
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.NetworkSelectionState
import com.alonibh.tellodrone.domain.NormalizedBoundingBox
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.domain.TelemetrySnapshot
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.TargetSelection
import com.alonibh.tellodrone.domain.ShadowAutonomyGate
import com.alonibh.tellodrone.domain.ShadowAutonomyInput
import com.alonibh.tellodrone.domain.TrackingErrorEngine
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
    private val trackingErrorEngine = TrackingErrorEngine()
    private val followPlanner = DryRunFollowPlanner(com.alonibh.tellodrone.domain.FollowPlannerConfig.LEGACY_SIMULATION)
    private val shadowGate = ShadowAutonomyGate()

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
            trackingErrors = null,
            targetAssociationState = TargetAssociationState.None,
            dryRunControlIntent = null,
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
            trackingErrors = null,
            targetAssociationState = TargetAssociationState.None,
            dryRunControlIntent = null,
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
                trackingErrors = null,
                targetAssociationState = TargetAssociationState.None,
                dryRunControlIntent = null,
            )
            TrackingMode.DetectOnly -> if (state.connection == DroneConnectionState.Connected) {
                val detections = mockPersonDetections(state.video.detectorConfidenceThreshold)
                state.copy(
                    tracking = TrackingMode.DetectOnly,
                    authority = ControlAuthority.Manual,
                    video = state.video.copy(
                        personDetectionState = PersonDetectionState.Detecting,
                        personDetections = detections,
                    ),
                    personDetections = detections,
                    target = null,
                    trackingErrors = null,
                    targetAssociationState = TargetAssociationState.None,
                    dryRunControlIntent = null,
                )
            } else state.invalid("Detection requires a connected mock drone")
            TrackingMode.TargetLocked, TrackingMode.Follow ->
                state.invalid("Target lock and Follow are not available in Phase 4A")
        }
    }

    override fun setDetectorBackendPreference(preference: DetectorBackendPreference) = update { state ->
        if (state.tracking != TrackingMode.Off) state.invalid("Turn person detection off before changing backend")
        else state.copy(video = state.video.copy(detectorBackendPreference = preference))
    }

    override fun setDetectorConfidenceThreshold(threshold: Float) = update { state ->
        if (state.tracking != TrackingMode.Off || state.video.personDetectionState != PersonDetectionState.Off) {
            state.invalid("Turn person detection off before changing confidence threshold")
        } else {
            val normalized = com.alonibh.tellodrone.vision.normalizeConfidenceThreshold(threshold)
            state.copy(video = state.video.copy(detectorConfidenceThreshold = normalized))
        }
    }

    override fun selectTarget(detection: PersonDetection) = update { state ->
        val currentDetection = state.personDetections.firstOrNull { it == detection }
        if (state.video.personDetectionState != PersonDetectionState.Detecting || currentDetection == null) {
            state.invalid("Select a currently visible mock person detection")
        } else {
            val target = TargetSelection.select(currentDetection)
            trackingErrorEngine.reset()
            val errors = trackingErrorEngine.update(target, targetFresh = true)
            state.copy(
                tracking = TrackingMode.TargetLocked,
                authority = ControlAuthority.Manual,
                target = target,
                trackingErrors = errors,
                targetAssociationState = TargetAssociationState.Selected,
                dryRunControlIntent = followPlanner.plan(errors, TargetAssociationState.Selected, 1f / 30f),
                lastMessage = "Mock target selected (dry run only)",
            )
        }
    }

    override fun setCurrentFollowDistance() = Unit

    override fun setShadowAutonomyArmed(armed: Boolean) = update { state ->
        state.copy(shadowAutonomyDecision = shadowGate.evaluate(
            ShadowAutonomyInput(state.connection, state.flight, state.telemetry.isFresh, state.video.availability,
                state.video.personDetectionState, state.target != null, state.targetAssociationState,
                state.trackingErrors, state.dryRunControlIntent, state.manualVector.isZero(), state.hoverActive,
                armRequested = armed, disarmRequested = !armed),
        ))
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
    private fun mockPersonDetections(
        threshold: Float = com.alonibh.tellodrone.vision.DEFAULT_PERSON_CONFIDENCE_THRESHOLD,
    ): List<PersonDetection> {
        val timestamp = System.nanoTime()
        return listOf(
            PersonDetection(
                boundingBox = NormalizedBoundingBox(left = .24f, top = .20f, right = .46f, bottom = .78f),
                confidence = .92f,
                frameSequence = 1L,
                sourceTimestampNanos = timestamp,
            ),
            PersonDetection(
                boundingBox = NormalizedBoundingBox(left = .60f, top = .30f, right = .82f, bottom = .84f),
                confidence = .84f,
                frameSequence = 1L,
                sourceTimestampNanos = timestamp,
            ),
        ).filter { it.confidence >= threshold }
    }

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
