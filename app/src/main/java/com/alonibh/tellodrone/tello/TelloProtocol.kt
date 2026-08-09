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
        if (fields.isEmpty()) return null

        val velocityX = fields["vgx"]?.toIntOrNull()
        val velocityY = fields["vgy"]?.toIntOrNull()
        val velocityZ = fields["vgz"]?.toIntOrNull()
        val speed = if (velocityX != null && velocityY != null && velocityZ != null) {
            sqrt(
                velocityX.toDouble() * velocityX +
                    velocityY.toDouble() * velocityY +
                    velocityZ.toDouble() * velocityZ,
            ).toFloat() / 100f
        } else null
        val lowTemperature = fields["templ"]?.toFloatOrNull()
        val highTemperature = fields["temph"]?.toFloatOrNull()
        val temperature = when {
            lowTemperature != null && highTemperature != null -> (lowTemperature + highTemperature) / 2f
            lowTemperature != null -> lowTemperature
            else -> highTemperature
        }

        return TelloTelemetry(
            batteryPercent = fields["bat"]?.toIntOrNull()?.takeIf { it in 0..100 },
            heightMeters = fields["h"]?.toFloatOrNull()?.div(100f),
            flightTimeSeconds = fields["time"]?.toIntOrNull(),
            temperatureCelsius = temperature,
            velocityXCentimetersPerSecond = velocityX,
            velocityYCentimetersPerSecond = velocityY,
            velocityZCentimetersPerSecond = velocityZ,
            speedMetersPerSecond = speed,
            receivedAt = receivedAt,
            receivedAtMonotonicMillis = receivedAtMonotonicMillis,
            fields = fields,
        )
    }
}

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
