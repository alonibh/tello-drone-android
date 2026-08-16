package com.alonibh.tellodrone.simulator

import com.alonibh.tellodrone.tello.MonotonicClock
import com.alonibh.tellodrone.tello.RcVector
import com.alonibh.tellodrone.tello.TelloCommandResult
import com.alonibh.tellodrone.tello.TelloTelemetry
import com.alonibh.tellodrone.tello.TelloTransport
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SimulatorTransportSnapshot(
    val latestRc: RcVector = RcVector.Zero,
    val plant: SimulatorPlantSnapshot,
)

/** In-memory SDK adapter. No network, socket, service, or Android API is referenced. */
class SimulatorTelloTransport(
    parentScope: CoroutineScope,
    private val plant: SimulatorPlant,
    private val clock: MonotonicClock = MonotonicClock { System.nanoTime() / 1_000_000L },
    private val runtimeTickMillis: Long = 50L,
    private val telemetryPeriodMillis: Long = 100L,
    private val flightTransitionDelayMillis: Long = 150L,
) : TelloTransport {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val mutableTelemetry = MutableSharedFlow<TelloTelemetry>(replay = 1, extraBufferCapacity = 1)
    override val telemetry: Flow<TelloTelemetry> = mutableTelemetry
    private val mutableSnapshot = MutableStateFlow(SimulatorTransportSnapshot(plant = plant.snapshot()))
    val snapshot: StateFlow<SimulatorTransportSnapshot> = mutableSnapshot.asStateFlow()
    private var closed = false
    private var streaming = false
    private var lastTelemetryMillis = Long.MIN_VALUE
    private var telemetryAccumulatorMillis = 0L

    init {
        emitTelemetry(plant.snapshot())
        scope.launch {
            while (isActive) {
                delay(runtimeTickMillis)
                if (closed) break
                val current = plant.step(runtimeTickMillis / 1_000f)
                mutableSnapshot.value = mutableSnapshot.value.copy(plant = current)
                telemetryAccumulatorMillis += runtimeTickMillis
                if (telemetryAccumulatorMillis >= telemetryPeriodMillis) {
                    telemetryAccumulatorMillis %= telemetryPeriodMillis
                    emitTelemetry(current)
                }
            }
        }
    }

    override suspend fun sendCommand(command: String, timeoutMillis: Long): TelloCommandResult {
        if (closed) return TelloCommandResult.Failure(IllegalStateException("Simulator transport is closed"))
        return when (command.trim().lowercase()) {
            "command" -> TelloCommandResult.Success("ok")
            "streamon" -> {
                streaming = true
                TelloCommandResult.Success("ok")
            }
            "streamoff" -> {
                streaming = false
                TelloCommandResult.Success("ok")
            }
            "takeoff" -> {
                if (plant.snapshot().airborne) TelloCommandResult.Rejected("error already airborne")
                else {
                    scheduleFlightState(airborne = true)
                    TelloCommandResult.Success("ok")
                }
            }
            "land" -> {
                if (!plant.snapshot().airborne) TelloCommandResult.Rejected("error already grounded")
                else {
                    scheduleFlightState(airborne = false)
                    TelloCommandResult.Success("ok")
                }
            }
            "emergency" -> {
                plant.emergencyStop()
                publishPlantSnapshot()
                TelloCommandResult.Success("ok")
            }
            else -> TelloCommandResult.Rejected("unsupported simulator command: $command")
        }
    }

    override suspend fun sendRc(vector: RcVector) {
        check(!closed) { "Simulator transport is closed" }
        plant.applyAxes(SimulatorAxes(vector.lateral, vector.forward, vector.vertical, vector.yaw))
        mutableSnapshot.value = SimulatorTransportSnapshot(latestRc = vector, plant = plant.snapshot())
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        streaming = false
        plant.applyAxes(SimulatorAxes())
        mutableSnapshot.value = SimulatorTransportSnapshot(RcVector.Zero, plant.snapshot())
        scope.cancel()
    }

    fun isClosed(): Boolean = closed

    private fun scheduleFlightState(airborne: Boolean) {
        scope.launch {
            delay(flightTransitionDelayMillis)
            if (!closed) {
                plant.setAirborne(airborne)
                publishPlantSnapshot()
                emitTelemetry(plant.snapshot())
            }
        }
    }

    private fun publishPlantSnapshot() {
        mutableSnapshot.value = mutableSnapshot.value.copy(plant = plant.snapshot())
    }

    private fun emitTelemetry(snapshot: SimulatorPlantSnapshot) {
        val now = clock.nowMillis()
        val monotonic = if (lastTelemetryMillis == Long.MIN_VALUE) now else maxOf(now, lastTelemetryMillis + 1L)
        lastTelemetryMillis = monotonic
        val rc = mutableSnapshot.value.latestRc
        val moving = snapshot.airborne && !rc.isZero()
        mutableTelemetry.tryEmit(
            TelloTelemetry(
                batteryPercent = 100,
                heightMeters = snapshot.heightMeters,
                flightTimeSeconds = snapshot.flightTimeSeconds,
                temperatureCelsius = 25f,
                velocityXCentimetersPerSecond = if (moving) rc.forward else 0,
                velocityYCentimetersPerSecond = if (moving) rc.lateral else 0,
                velocityZCentimetersPerSecond = if (moving) rc.vertical else 0,
                speedMetersPerSecond = if (moving) {
                    (kotlin.math.sqrt(
                        (rc.forward * rc.forward + rc.lateral * rc.lateral + rc.vertical * rc.vertical).toFloat(),
                    ) / 100f)
                } else 0f,
                receivedAt = Instant.now(),
                receivedAtMonotonicMillis = monotonic,
                fields = mapOf("simulator" to "true"),
            ),
        )
    }
}
