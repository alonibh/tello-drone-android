package com.alonibh.tellodrone.domain

import androidx.compose.ui.geometry.Rect
import java.time.Instant

enum class DroneConnectionState { Disconnected, Connecting, Connected, Error }
enum class FlightState { Grounded, TakingOff, Flying, Landing, Emergency }
enum class TrackingMode { Off, DetectOnly, TargetLocked, Follow }
enum class ControlAuthority { Manual, Autonomous }
enum class VideoAvailability { Unavailable, Mock, Streaming, Error }

data class TelemetrySnapshot(
    val batteryPercent: Int = 78,
    val signalLabel: String = "Mock signal",
    val heightMeters: Float = 0f,
    val speedMetersPerSecond: Float = 0f,
    val flightTimeSeconds: Int = 0,
    val temperatureCelsius: Float? = 31f,
    val timestamp: Instant = Instant.now(),
)

data class VideoState(
    val availability: VideoAvailability = VideoAvailability.Mock,
    val measuredFps: Float? = 30f,
    val lastFrameAt: Instant = Instant.now(),
)

/** Coordinates are normalized to the displayed video (0f..1f), not screen pixels. */
data class TrackedTarget(
    val boundingBox: Rect,
    val confidence: Float,
    val estimatedDistanceMeters: Float? = null,
    val lastSeenAt: Instant = Instant.now(),
    val locked: Boolean = false,
)

data class ManualControlVector(
    val lateral: Float = 0f,
    val forward: Float = 0f,
    val vertical: Float = 0f,
    val yaw: Float = 0f,
    val createdAt: Instant = Instant.now(),
)

data class DroneSessionState(
    val connection: DroneConnectionState = DroneConnectionState.Disconnected,
    val flight: FlightState = FlightState.Grounded,
    val tracking: TrackingMode = TrackingMode.Off,
    val authority: ControlAuthority = ControlAuthority.Manual,
    val telemetry: TelemetrySnapshot = TelemetrySnapshot(),
    val video: VideoState = VideoState(),
    val target: TrackedTarget? = null,
    val speedPercent: Int = 50,
    val manualVector: ManualControlVector = ManualControlVector(),
    val lastMessage: String? = null,
)
