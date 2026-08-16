@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.alonibh.tellodrone.simulator

import com.alonibh.tellodrone.domain.ControllerMode
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.NetworkSelectionState
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.TrackingMode
import com.alonibh.tellodrone.domain.YawFollowReason
import com.alonibh.tellodrone.domain.YawFollowState
import com.alonibh.tellodrone.tello.MonotonicClock
import com.alonibh.tellodrone.tello.RcVector
import com.alonibh.tellodrone.tello.TelloFlightSession
import kotlin.math.abs
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorClosedLoopTest {
    @Test fun `right target produces positive final yaw and closed loop reduces error`() = runTest {
        val fixture = activeFixture(PersonSide.Right)
        val initialError = abs(fixture.plant.snapshot().horizontalError)

        advanceTimeBy(50L)
        runCurrent()
        assertTrue(fixture.transport.snapshot.value.latestRc.yaw > 0)
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(abs(fixture.plant.snapshot().horizontalError) < initialError)
    }

    @Test fun `left target produces negative final yaw and closed loop reduces error`() = runTest {
        val fixture = activeFixture(PersonSide.Left)
        val initialError = abs(fixture.plant.snapshot().horizontalError)

        advanceTimeBy(50L)
        runCurrent()
        assertTrue(fixture.transport.snapshot.value.latestRc.yaw < 0)
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(abs(fixture.plant.snapshot().horizontalError) < initialError)
    }

    @Test fun `centred target stays zero inside production deadband`() = runTest {
        val fixture = activeFixture(PersonSide.Center)
        advanceTimeBy(200L)
        runCurrent()

        assertEquals(YawFollowState.ACTIVE, fixture.session.state.value.yawFollowDecision.state)
        assertEquals(0, fixture.session.state.value.yawFollowDecision.yawRc)
        assertEquals(RcVector.Zero, fixture.transport.snapshot.value.latestRc)
    }

    @Test fun `continuing production loop converges instead of diverging`() = runTest {
        val fixture = activeFixture(PersonSide.Right)
        val initial = abs(fixture.plant.snapshot().horizontalError)
        advanceTimeBy(2_500L)
        runCurrent()
        val final = abs(fixture.plant.snapshot().horizontalError)

        assertTrue(final < initial)
        assertTrue(final <= .08f)
    }

    @Test fun `stop hidden target and manual override all select zero or manual axes fail closed`() = runTest {
        val fixture = activeFixture(PersonSide.Right)
        advanceTimeBy(50L)
        runCurrent()
        assertTrue(fixture.transport.snapshot.value.latestRc.yaw > 0)

        fixture.session.stopAndHover()
        assertEquals(RcVector.Zero, fixture.transport.snapshot.value.latestRc)
        assertEquals(YawFollowState.REQUIRES_REARM, fixture.session.state.value.yawFollowDecision.state)
        fixture.session.setYawFollowArmed(true)
        advanceTimeBy(100L)
        runCurrent()
        fixture.plant.togglePersonVisibility()
        advanceTimeBy(150L)
        runCurrent()
        assertEquals(RcVector.Zero, fixture.transport.snapshot.value.latestRc)

        fixture.plant.togglePersonVisibility()
        advanceTimeBy(150L)
        runCurrent()
        fixture.session.publishManualControl(ManualControlVector())
        fixture.session.setYawFollowArmed(true)
        advanceTimeBy(100L)
        runCurrent()
        fixture.session.publishManualControl(ManualControlVector(lateral = 1f))
        advanceTimeBy(50L)
        runCurrent()
        val manual = fixture.transport.snapshot.value.latestRc
        assertTrue(manual.lateral > 0)
        assertEquals(0, manual.yaw)
        assertEquals(YawFollowReason.MANUAL_OVERRIDE, fixture.session.state.value.yawFollowDecision.reason)
    }

    @Test fun `hidden target becomes lost and final RC remains zero`() = runTest {
        val fixture = activeFixture(PersonSide.Right)
        advanceTimeBy(50L)
        runCurrent()
        assertTrue(fixture.transport.snapshot.value.latestRc.yaw > 0)

        fixture.plant.togglePersonVisibility()
        advanceTimeBy(1_300L)
        runCurrent()

        assertEquals(TargetAssociationState.Lost, fixture.session.state.value.targetAssociationState)
        assertEquals(RcVector.Zero, fixture.transport.snapshot.value.latestRc)
        assertEquals(YawFollowState.REQUIRES_REARM, fixture.session.state.value.yawFollowDecision.state)
    }

    private suspend fun TestScope.activeFixture(side: PersonSide): Fixture {
        val plant = SimulatorPlant()
        when (side) {
            PersonSide.Left -> plant.movePersonLeft()
            PersonSide.Right -> plant.movePersonRight()
            PersonSide.Center -> Unit
        }
        repeat(20) { plant.step(.1f) }
        val clock = MonotonicClock { 1_000L + testScheduler.currentTime }
        val transport = SimulatorTelloTransport(backgroundScope, plant, clock)
        val video = SimulatorVideoController(
            backgroundScope,
            plant,
            sourceNowNanos = { 10_000_000_000L + testScheduler.currentTime * 1_000_000L },
        )
        val session = TelloFlightSession(
            transport = transport,
            scope = backgroundScope,
            clock = clock,
            video = video,
            sourceNowNanos = { 10_000_000_000L + testScheduler.currentTime * 1_000_000L },
            initialState = DroneSessionState(
                controllerMode = ControllerMode.Mock,
                connection = DroneConnectionState.Connecting,
                networkSelection = NetworkSelectionState.Available,
                flight = FlightState.Unknown,
            ),
        )
        assertTrue(session.connect())
        runCurrent()
        session.takeOff()
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(FlightState.Flying, session.state.value.flight)
        session.setTrackingMode(TrackingMode.DetectOnly)
        advanceTimeBy(100L)
        runCurrent()
        val detection = session.state.value.personDetections.single()
        session.selectTarget(detection)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(TargetAssociationState.Matched, session.state.value.targetAssociationState)
        session.setYawFollowArmed(true)
        assertEquals(YawFollowState.ACTIVE, session.state.value.yawFollowDecision.state)
        return Fixture(plant, transport, session)
    }

    private enum class PersonSide { Left, Center, Right }
    private data class Fixture(
        val plant: SimulatorPlant,
        val transport: SimulatorTelloTransport,
        val session: TelloFlightSession,
    )
}
