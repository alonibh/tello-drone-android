package com.alonibh.tellodrone.domain

import java.time.Instant

enum class DroneConnectionState { Disconnected, AwaitingPermission, Connecting, Connected, Error }
enum class NetworkSelectionState { Idle, PermissionRequired, PermissionDenied, Requesting, Available, Lost, Error }
enum class FlightState { Grounded, TakingOff, Flying, Landing, Unknown, Error, Emergency }
enum class TrackingMode { Off, DetectOnly, TargetLocked, Follow }
enum class ControlAuthority { Manual, Autonomous }
enum class VideoAvailability { Unavailable, Streaming, Error }
enum class PersonDetectionState { Off, Starting, Detecting, Error }
enum class DetectorBackendPreference { Accelerated, Cpu }
enum class DetectorBackend { Gpu, Cpu }
enum class DetectorModel(
    val assetFileName: String,
    val displayName: String,
) {
    Yolo11nLiteRtFloat32(
        assetFileName = "yolo11n_320_float32.tflite",
        displayName = "Ultralytics YOLO11n 320px LiteRT FP32",
    );

    companion object {
        val Default = Yolo11nLiteRtFloat32
    }
}
enum class FollowDistanceCalibrationState { NotSet, Calibrating, Set }

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
    val appearance: HsvAppearanceHistogram? = null,
)

/** Normalized 24x16 hue/saturation histogram extracted from the benchmark-defined body crop. */
data class HsvAppearanceHistogram(val bins: List<Float>) {
    init {
        require(bins.size == BIN_COUNT)
    }

    companion object {
        const val HUE_BINS = 24
        const val SATURATION_BINS = 16
        const val BIN_COUNT = HUE_BINS * SATURATION_BINS
    }
}

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
    val detectorInitializationMillis: Long? = null,
    val detectorInferenceP50Millis: Float? = null,
    val detectorInferenceP95Millis: Float? = null,
    val detectorAnalyzedFrames: Long = 0,
    val analysisCapturedFrames: Long = 0,
    val analysisDroppedFrames: Long = 0,
    val detectorBackend: DetectorBackend? = null,
    val detectorModelName: String? = null,
    val detectorFellBackFromGpu: Boolean = false,
    val detectorErrorReason: String? = null,
    /** App-visible threshold used for this completed inference. */
    val detectorConfidenceThreshold: Float? = null,
    /** Identity of the detector frame that most recently completed inference, even with no people. */
    val processedDetectorFrameSequence: Long? = null,
    /** Monotonic capture timestamp for [processedDetectorFrameSequence]. */
    val processedDetectorSourceTimestampNanos: Long? = null,
    /** Person candidates emitted by the model before the app-visible confidence threshold. */
    val detectorCandidates: List<PersonDetection> = emptyList(),
    val personDetections: List<PersonDetection> = emptyList(),
    val errorReason: String? = null,
)

/**
 * An explicitly selected, dry-run tracking target. All geometry and freshness values are in the
 * detector's normalized / monotonic source domain; Compose converts only when rendering it.
 */
data class TrackedTarget(
    val boundingBox: NormalizedBoundingBox,
    val confidence: Float,
    val selectedFrameSequence: Long,
    val selectedSourceTimestampNanos: Long,
    val lastSeenFrameSequence: Long = selectedFrameSequence,
    val lastSeenSourceTimestampNanos: Long = selectedSourceTimestampNanos,
    /** Previous association match used only for bounded short-term motion prediction. */
    val previousMatchedBoundingBox: NormalizedBoundingBox? = null,
    val previousMatchedSourceTimestampNanos: Long? = null,
    /** Saturates at two; explicit selection starts with no association matches. */
    val associationMatchCount: Int = 0,
    /** Non-target people retained with brief continuity history to prevent identity transfer. */
    val competingPeople: List<CompetingPersonTrack> = emptyList(),
    /** Once set, association cannot match again; only explicit selection creates a new identity. */
    val identityUncertain: Boolean = false,
    val appearance: HsvAppearanceHistogram? = null,
)

/** Brief geometry history for a separately observed non-target person. */
data class CompetingPersonTrack(
    val boundingBox: NormalizedBoundingBox,
    val sourceTimestampNanos: Long,
    val previousBoundingBox: NormalizedBoundingBox? = null,
    val previousSourceTimestampNanos: Long? = null,
)

/** User-selected visual standoff scale; it is not a physical distance or meter estimate. */
data class FollowDistanceReference(
    val visualScale: Float,
    val sourceFrameSequence: Long,
    val sourceTimestampNanos: Long,
    val sampleCount: Int,
)

/** Pure explicit-selection boundary. Nothing in detection or association calls this implicitly. */
object TargetSelection {
    fun select(detection: PersonDetection): TrackedTarget = TrackedTarget(
        boundingBox = detection.boundingBox,
        confidence = detection.confidence,
        selectedFrameSequence = detection.frameSequence,
        selectedSourceTimestampNanos = detection.sourceTimestampNanos,
        appearance = detection.appearance,
    )
}

/** Normalized axes in -1f..1f. The session stamps monotonic freshness when it accepts a vector. */
data class ManualControlVector(
    val lateral: Float = 0f,
    val forward: Float = 0f,
    val vertical: Float = 0f,
    val yaw: Float = 0f,
)

data class DroneSessionState(
    val connection: DroneConnectionState = DroneConnectionState.Disconnected,
    val networkSelection: NetworkSelectionState = NetworkSelectionState.Idle,
    val flight: FlightState = FlightState.Grounded,
    val tracking: TrackingMode = TrackingMode.Off,
    val authority: ControlAuthority = ControlAuthority.Manual,
    val telemetry: TelemetrySnapshot = TelemetrySnapshot(),
    val video: VideoState = VideoState(),
    val personDetections: List<PersonDetection> = emptyList(),
    val target: TrackedTarget? = null,
    /** Dry-run only. These values are never converted to RC commands in Phase 4B. */
    val trackingErrors: TrackingErrors? = null,
    val targetAssociationState: TargetAssociationState = TargetAssociationState.None,
    val dryRunControlIntent: DryRunControlIntent? = null,
    val followDistanceReference: FollowDistanceReference? = null,
    val followDistanceCalibrationState: FollowDistanceCalibrationState = FollowDistanceCalibrationState.NotSet,
    val followDistanceCalibrationSamples: Int = 0,
    val shadowAutonomyDecision: ShadowAutonomyDecision? = null,
    val yawFollowDecision: YawFollowDecision = YawFollowDecision(),
    val speedPercent: Int = 20,
    val manualVector: ManualControlVector = ManualControlVector(),
    /** App command state only: STOP/HOVER completed its explicit RC-zero action. */
    val hoverActive: Boolean = false,
    val lastMessage: String? = null,
    val trackingStateTransitions: List<TrackingStateTransition> = emptyList(),
)

data class TrackingStateTransition(
    val from: TargetAssociationState,
    val to: TargetAssociationState,
    val frameSequence: Long?,
    val sourceTimestampNanos: Long?,
)

fun ManualControlVector.isZero(): Boolean =
    lateral == 0f && forward == 0f && vertical == 0f && yaw == 0f
// SPDX-License-Identifier: AGPL-3.0-only
