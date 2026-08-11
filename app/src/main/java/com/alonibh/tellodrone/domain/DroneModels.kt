package com.alonibh.tellodrone.domain

import androidx.compose.ui.geometry.Rect
import java.time.Instant

enum class ControllerMode { Real, Mock }
enum class DroneConnectionState { Disconnected, AwaitingPermission, Connecting, Connected, Error }
enum class NetworkSelectionState { Idle, PermissionRequired, PermissionDenied, Requesting, Available, Lost, Error }
enum class FlightState { Grounded, TakingOff, Flying, Landing, Unknown, Error, Emergency }
enum class TrackingMode { Off, DetectOnly, TargetLocked, Follow }
enum class ControlAuthority { Manual, Autonomous }
enum class VideoAvailability { Unavailable, Mock, Streaming, Error }
enum class PersonDetectionState { Off, Starting, Detecting, Error }

/** A finite, non-empty box normalized to the captured preview surface (0f..1f). */
data class NormalizedBoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** One frame-local observation. It is deliberately not a selected or tracked target. */
data class PersonDetection(
    val boundingBox: NormalizedBoundingBox,
    val confidence: Float,
    val frameSequence: Long,
    val sourceTimestampNanos: Long,
)

/**
 * A real telemetry sample. Nullable fields were not present in the aircraft state packet and must
 * be rendered as unavailable rather than fabricated. [isFresh] is maintained by the session's
 * monotonic health monitor; [receivedAt] is the user-facing wall-clock timestamp.
 */
data class TelemetrySnapshot(
    val batteryPercent: Int? = null,
    val heightMeters: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val velocityXCentimetersPerSecond: Int? = null,
    val velocityYCentimetersPerSecond: Int? = null,
    val velocityZCentimetersPerSecond: Int? = null,
    val flightTimeSeconds: Int? = null,
    val temperatureCelsius: Float? = null,
    val receivedAt: Instant? = null,
    val isFresh: Boolean = false,
)

data class VideoState(
    val availability: VideoAvailability = VideoAvailability.Unavailable,
    val measuredFps: Float? = null,
    val lastFrameAt: Instant? = null,
    val analysisMeasuredFps: Float? = null,
    val analysisLatestCaptureTimestampNanos: Long? = null,
    val analysisFrameWidth: Int? = null,
    val analysisFrameHeight: Int? = null,
    val analysisLatestSequence: Long? = null,
    val personDetectionState: PersonDetectionState = PersonDetectionState.Off,
    val detectorMeasuredFps: Float? = null,
    val detectorInferenceMillis: Long? = null,
    val detectorErrorReason: String? = null,
    val personDetections: List<PersonDetection> = emptyList(),
    val errorReason: String? = null,
)

/** Coordinates are normalized to the displayed video (0f..1f), not screen pixels. */
data class TrackedTarget(
    val boundingBox: Rect,
    val confidence: Float,
    val estimatedDistanceMeters: Float? = null,
    val lastSeenAt: Instant = Instant.now(),
    val locked: Boolean = false,
)

/** Normalized axes in -1f..1f. The session stamps monotonic freshness when it accepts a vector. */
data class ManualControlVector(
    val lateral: Float = 0f,
    val forward: Float = 0f,
    val vertical: Float = 0f,
    val yaw: Float = 0f,
)

data class DroneSessionState(
    val controllerMode: ControllerMode = ControllerMode.Real,
    val connection: DroneConnectionState = DroneConnectionState.Disconnected,
    val networkSelection: NetworkSelectionState = NetworkSelectionState.Idle,
    val flight: FlightState = FlightState.Grounded,
    val tracking: TrackingMode = TrackingMode.Off,
    val authority: ControlAuthority = ControlAuthority.Manual,
    val telemetry: TelemetrySnapshot = TelemetrySnapshot(),
    val video: VideoState = VideoState(),
    val personDetections: List<PersonDetection> = emptyList(),
    val target: TrackedTarget? = null,
    val speedPercent: Int = 20,
    val manualVector: ManualControlVector = ManualControlVector(),
    /** App command state only: STOP/HOVER completed its explicit RC-zero action. */
    val hoverActive: Boolean = false,
    val lastMessage: String? = null,
)

fun ManualControlVector.isZero(): Boolean =
    lateral == 0f && forward == 0f && vertical == 0f && yaw == 0f
