@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.alonibh.tellodrone.tello

import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelloFlightSessionTest {
    @Test fun `invalid takeoff is gated without transport command`() = runTest {
        val fixture = fixture()
        fixture.session.takeOff()
        assertTrue(fixture.transport.commands.isEmpty())
        assertEquals(FlightState.Unknown, fixture.session.state.value.flight)
    }

    @Test fun `connect takeoff stop land and disconnect follow conservative transitions`() = runTest {
        val fixture = connectedFixture()
        assertEquals(DroneConnectionState.Connected, fixture.session.state.value.connection)
        assertEquals(FlightState.Grounded, fixture.session.state.value.flight)

        takeOffAndVerify(fixture)
        assertEquals(FlightState.Flying, fixture.session.state.value.flight)
        fixture.session.publishManualControl(ManualControlVector(forward = 1f))
        fixture.session.stopAndHover()
        assertEquals(FlightState.Flying, fixture.session.state.value.flight)
        assertEquals(ManualControlVector(), fixture.session.state.value.manualVector)
        assertEquals(RcVector.Zero, fixture.transport.rc.last())

        landAndVerify(fixture)
        assertEquals(FlightState.Grounded, fixture.session.state.value.flight)
        assertTrue(fixture.session.disconnect())
        assertEquals(DroneConnectionState.Disconnected, fixture.session.state.value.connection)
        assertTrue(fixture.transport.closed)
        assertEquals(listOf("command", "takeoff", "land"), fixture.transport.commands)
    }

    @Test fun `disconnect is rejected while flying and does not clean up transport`() = runTest {
        val fixture = connectedFixture()
        takeOffAndVerify(fixture)
        assertFalse(fixture.session.disconnect())
        assertFalse(fixture.transport.closed)
        assertEquals(FlightState.Flying, fixture.session.state.value.flight)
    }

    @Test fun `emergency clears RC and permanently locks flight state`() = runTest {
        val fixture = connectedFixture()
        takeOffAndVerify(fixture)
        fixture.session.publishManualControl(ManualControlVector(lateral = 1f))
        fixture.session.emergencyMotorKill()
        assertEquals(FlightState.Emergency, fixture.session.state.value.flight)
        assertEquals(RcVector.Zero, fixture.transport.rc.last())
        val commandCount = fixture.transport.commands.size
        fixture.session.takeOff()
        assertEquals(commandCount, fixture.transport.commands.size)
        assertEquals(listOf("command", "takeoff", "emergency"), fixture.transport.commands)
    }

    @Test fun `telemetry becomes stale then connection loss clears and closes`() = runTest {
        val fixture = connectedFixture()
        takeOffAndVerify(fixture)
        fixture.session.publishManualControl(ManualControlVector(yaw = 1f))

        fixture.clock.value += TelloFlightSession.TELEMETRY_STALE_MILLIS
        fixture.session.refreshConnectionHealth()
        assertFalse(fixture.session.state.value.telemetry.isFresh)
        assertEquals(RcVector.Zero, fixture.transport.rc.last())
        assertEquals(DroneConnectionState.Connected, fixture.session.state.value.connection)

        fixture.clock.value += TelloFlightSession.CONNECTION_LOST_MILLIS
        fixture.session.refreshConnectionHealth()
        assertEquals(DroneConnectionState.Error, fixture.session.state.value.connection)
        assertEquals(FlightState.Unknown, fixture.session.state.value.flight)
        assertTrue(fixture.transport.closed)
    }

    @Test fun `new telemetry restores freshness without restoring stale movement`() = runTest {
        val fixture = connectedFixture()
        takeOffAndVerify(fixture)
        fixture.session.publishManualControl(ManualControlVector(forward = 1f))
        fixture.clock.value += TelloFlightSession.TELEMETRY_STALE_MILLIS
        fixture.session.refreshConnectionHealth()

        fixture.transport.emitTelemetry(fixture.clock.value)
        runCurrent()
        assertTrue(fixture.session.state.value.telemetry.isFresh)
        assertEquals(ManualControlVector(), fixture.session.state.value.manualVector)

        fixture.session.publishManualControl(ManualControlVector(forward = 1f))
        assertEquals(ManualControlVector(), fixture.session.state.value.manualVector)
        fixture.session.publishManualControl(ManualControlVector())
        fixture.session.publishManualControl(ManualControlVector(forward = 1f))
        assertEquals(ManualControlVector(forward = 1f), fixture.session.state.value.manualVector)
    }

    @Test fun `missing or invalid initial height leaves flight state unknown and prohibits takeoff`() = runTest {
        listOf<Float?>(null, -0.01f, Float.NaN).forEach { height ->
            val fixture = fixture()
            fixture.transport.emitTelemetry(fixture.clock.value, height)
            assertTrue(fixture.session.connect())
            runCurrent()

            assertEquals(DroneConnectionState.Connected, fixture.session.state.value.connection)
            assertEquals(FlightState.Unknown, fixture.session.state.value.flight)
            fixture.session.takeOff()
            assertEquals(listOf("command"), fixture.transport.commands)
        }
    }

    @Test fun `flight acknowledgements require post acknowledgement height verification`() = runTest {
        val fixture = connectedFixture()

        fixture.session.takeOff()
        assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)
        fixture.transport.emitTelemetry(fixture.clock.value, TelloFlightSession.GROUNDED_HEIGHT_THRESHOLD_METERS)
        runCurrent()
        assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)
        fixture.transport.emitTelemetry(fixture.clock.value, TelloFlightSession.GROUNDED_HEIGHT_THRESHOLD_METERS + 0.01f)
        runCurrent()
        assertEquals(FlightState.Flying, fixture.session.state.value.flight)

        fixture.session.land()
        assertEquals(FlightState.Landing, fixture.session.state.value.flight)
        fixture.transport.emitTelemetry(fixture.clock.value, 1f)
        runCurrent()
        assertEquals(FlightState.Landing, fixture.session.state.value.flight)
        fixture.transport.emitTelemetry(fixture.clock.value, TelloFlightSession.GROUNDED_HEIGHT_THRESHOLD_METERS)
        runCurrent()
        assertEquals(FlightState.Grounded, fixture.session.state.value.flight)
    }

    @Test fun `later telemetry cannot overwrite terminal emergency state`() = runTest {
        val fixture = connectedFixture()
        takeOffAndVerify(fixture)
        fixture.session.emergencyMotorKill()
        fixture.transport.emitTelemetry(fixture.clock.value, 0f)
        runCurrent()

        assertEquals(FlightState.Emergency, fixture.session.state.value.flight)
        val commandCount = fixture.transport.commands.size
        fixture.session.land()
        assertEquals(commandCount, fixture.transport.commands.size)
    }

    private suspend fun TestScope.connectedFixture(): Fixture = fixture().also {
        it.transport.emitTelemetry(it.clock.value)
        assertTrue(it.session.connect())
        runCurrent()
    }

    private suspend fun TestScope.takeOffAndVerify(fixture: Fixture) {
        fixture.session.takeOff()
        assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)
        fixture.transport.emitTelemetry(fixture.clock.value, TelloFlightSession.GROUNDED_HEIGHT_THRESHOLD_METERS + 0.01f)
        runCurrent()
        assertEquals(FlightState.Flying, fixture.session.state.value.flight)
    }

    private suspend fun TestScope.landAndVerify(fixture: Fixture) {
        fixture.session.land()
        assertEquals(FlightState.Landing, fixture.session.state.value.flight)
        fixture.transport.emitTelemetry(fixture.clock.value, 0f)
        runCurrent()
        assertEquals(FlightState.Grounded, fixture.session.state.value.flight)
    }

    private fun TestScope.fixture(): Fixture {
        val clock = RcControlLoopTest.FakeClock(1_000)
        val transport = FakeTransport()
        return Fixture(
            clock,
            transport,
            TelloFlightSession(transport, backgroundScope, clock),
        )
    }

    private data class Fixture(
        val clock: RcControlLoopTest.FakeClock,
        val transport: FakeTransport,
        val session: TelloFlightSession,
    )

    private class FakeTransport : TelloTransport {
        private val samples = MutableSharedFlow<TelloTelemetry>(replay = 1)
        override val telemetry: Flow<TelloTelemetry> = samples
        val commands = mutableListOf<String>()
        val rc = mutableListOf<RcVector>()
        var closed = false
        var nextResult: TelloCommandResult = TelloCommandResult.Success("ok")

        override suspend fun sendCommand(command: String, timeoutMillis: Long): TelloCommandResult {
            commands += command
            return nextResult
        }

        override suspend fun sendRc(vector: RcVector) { rc += vector }
        override suspend fun close() { closed = true }

        suspend fun emitTelemetry(at: Long, heightMeters: Float? = 0f) {
            samples.emit(
                TelloTelemetry(
                    batteryPercent = 80,
                    heightMeters = heightMeters,
                    flightTimeSeconds = 0,
                    temperatureCelsius = 30f,
                    velocityXCentimetersPerSecond = 0,
                    velocityYCentimetersPerSecond = 0,
                    velocityZCentimetersPerSecond = 0,
                    speedMetersPerSecond = 0f,
                    receivedAt = Instant.parse("2026-08-10T00:00:00Z"),
                    receivedAtMonotonicMillis = at,
                    fields = emptyMap(),
                ),
            )
        }
    }
}
