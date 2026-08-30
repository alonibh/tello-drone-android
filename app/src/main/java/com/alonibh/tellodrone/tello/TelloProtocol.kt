package com.alonibh.tellodrone.tello

import java.time.Instant
import kotlin.math.sqrt

data class TelloTelemetry(
    val batteryPercent: Int?,
    val heightMeters: Float?,
    val flightTimeSeconds: Int?,
    val temperatureCelsius: Float?,
    val velocityXCentimetersPerSecond: Int?,
    val velocityYCentimetersPerSecond: Int?,
    val velocityZCentimetersPerSecond: Int?,
    val speedMetersPerSecond: Float?,
    val yawDegrees: Int? = null,
    val receivedAt: Instant,
    val receivedAtMonotonicMillis: Long,
    val fields: Map<String, String>,
)

object TelloTelemetryParser {
    fun parse(
        packet: String,
        receivedAt: Instant = Instant.now(),
        receivedAtMonotonicMillis: Long,
    ): TelloTelemetry? {
        val fields = packet
            .trim()
            .split(';')
            .mapNotNull { item ->
                val separator = item.indexOf(':')
                if (separator <= 0 || separator == item.lastIndex) null
                else item.substring(0, separator) to item.substring(separator + 1)
            }
            .toMap()
        if (fields.keys.none { it in KNOWN_STATE_FIELDS }) return null

        val velocityX = fields["vgx"]?.toIntOrNull()
        val velocityY = fields["vgy"]?.toIntOrNull()
        val velocityZ = fields["vgz"]?.toIntOrNull()
        val speed = totalTranslationalSpeedMetersPerSecond(velocityX, velocityY, velocityZ)
        val lowTemperature = fields["templ"]?.toFiniteFloatOrNull()
        val highTemperature = fields["temph"]?.toFiniteFloatOrNull()
        val temperature = when {
            lowTemperature != null && highTemperature != null -> (lowTemperature + highTemperature) / 2f
            lowTemperature != null -> lowTemperature
            else -> highTemperature
        }
        val yaw = fields["yaw"]?.toIntOrNull()

        return TelloTelemetry(
            batteryPercent = fields["bat"]?.toIntOrNull()?.takeIf { it in 0..100 },
            heightMeters = fields["h"]?.toNonNegativeIntOrNull()?.div(100f),
            flightTimeSeconds = fields["time"]?.toNonNegativeIntOrNull(),
            temperatureCelsius = temperature,
            velocityXCentimetersPerSecond = velocityX,
            velocityYCentimetersPerSecond = velocityY,
            velocityZCentimetersPerSecond = velocityZ,
            speedMetersPerSecond = speed,
            yawDegrees = yaw,
            receivedAt = receivedAt,
            receivedAtMonotonicMillis = receivedAtMonotonicMillis,
            fields = fields,
        )
    }

    private fun String.toFiniteFloatOrNull(): Float? = toFloatOrNull()?.takeIf { it.isFinite() }

    private fun String.toNonNegativeIntOrNull(): Int? = toIntOrNull()?.takeIf { it >= 0 }

    private val KNOWN_STATE_FIELDS = setOf(
        "bat", "h", "time", "templ", "temph", "vgx", "vgy", "vgz", "pitch", "roll", "yaw", "tof", "baro",
    )
}

/** Computes the shortest angular difference in degrees from [fromDegrees] to [toDegrees] in [-180, 180]. */
fun shortestAngularDifferenceDegrees(fromDegrees: Float, toDegrees: Float): Float {
    var diff = (toDegrees - fromDegrees) % 360f
    if (diff > 180f) diff -= 360f
    else if (diff < -180f) diff += 360f
    return diff
}

/**
 * Derives physical aircraft yaw rate in deg/s from consecutive sample angles and monotonic timestamps.
 * Returns null if the sample gap is zero, negative, or exceeds 1.0 second.
 */
fun calculateYawRateDegreesPerSecond(
    previousYawDegrees: Int,
    currentYawDegrees: Int,
    previousTimestampMillis: Long,
    currentTimestampMillis: Long,
): Float? {
    val elapsedMillis = currentTimestampMillis - previousTimestampMillis
    if (elapsedMillis <= 0L || elapsedMillis > 1000L) return null
    val deltaDegrees = shortestAngularDifferenceDegrees(
        previousYawDegrees.toFloat(),
        currentYawDegrees.toFloat(),
    )
    val elapsedSeconds = elapsedMillis / 1000f
    return deltaDegrees / elapsedSeconds
}

/**
 * Lightweight median filter for raw telemetry yaw-rate samples.
 * Preserves high/catastrophic physical angular velocity while smoothing single-sample integer degree quantization jitter.
 * Automatically resets its median window when telemetry continuity is broken by time gaps or stale delays.
 */
class TelemetryYawRateFilter(private val windowSize: Int = 3) {
    private val samples = ArrayDeque<Float>()
    private var lastSampleTimestampMillis: Long? = null

    fun filter(rawRate: Float, sampleTimestampMillis: Long? = null): Float {
        if (sampleTimestampMillis != null) {
            val lastTs = lastSampleTimestampMillis
            if (lastTs != null && (sampleTimestampMillis - lastTs > MAX_CONTINUOUS_TELEMETRY_GAP_MILLIS || sampleTimestampMillis <= lastTs)) {
                reset()
            }
            lastSampleTimestampMillis = sampleTimestampMillis
        }
        samples.addLast(rawRate)
        if (samples.size > windowSize) {
            samples.removeFirst()
        }
        val sorted = samples.sorted()
        return sorted[sorted.size / 2]
    }

    fun reset() {
        samples.clear()
        lastSampleTimestampMillis = null
    }

    companion object {
        const val MAX_CONTINUOUS_TELEMETRY_GAP_MILLIS = 1000L
    }
}

/** Total 3D translational speed from Tello SDK vgx/vgy/vgz values, which are reported in cm/s. */
internal fun totalTranslationalSpeedMetersPerSecond(
    velocityXCentimetersPerSecond: Int?,
    velocityYCentimetersPerSecond: Int?,
    velocityZCentimetersPerSecond: Int?,
): Float? {
    val x = velocityXCentimetersPerSecond ?: return null
    val y = velocityYCentimetersPerSecond ?: return null
    val z = velocityZCentimetersPerSecond ?: return null
    return sqrt(
        x.toDouble() * x +
            y.toDouble() * y +
            z.toDouble() * z,
    ).toFloat() / CENTIMETERS_PER_METER
}

private const val CENTIMETERS_PER_METER = 100f

data class RcVector(val lateral: Int = 0, val forward: Int = 0, val vertical: Int = 0, val yaw: Int = 0) {
    fun asCommand(): String = "rc $lateral $forward $vertical $yaw"
    fun isZero(): Boolean = lateral == 0 && forward == 0 && vertical == 0 && yaw == 0

    companion object { val Zero = RcVector() }
}

sealed interface TelloCommandResult {
    data class Success(val response: String) : TelloCommandResult
    data class Rejected(val response: String) : TelloCommandResult
    data object Timeout : TelloCommandResult
    data class Failure(val cause: Throwable) : TelloCommandResult
}
// SPDX-License-Identifier: AGPL-3.0-only
