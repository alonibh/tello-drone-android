@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.alonibh.tellodrone.simulator

import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.tello.MonotonicClock
import com.alonibh.tellodrone.tello.RcVector
import com.alonibh.tellodrone.tello.TelloCommandResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorTransportVideoTest {
    @Test fun `commands telemetry and delayed flight transitions follow SDK ordering`() = runTest {
        val plant = SimulatorPlant()
        val transport = SimulatorTelloTransport(
            backgroundScope,
            plant,
            MonotonicClock { testScheduler.currentTime },
            flightTransitionDelayMillis = 150L,
        )

        assertTrue(transport.telemetry.first().receivedAtMonotonicMillis >= 0L)
        assertTrue(transport.sendCommand("command") is TelloCommandResult.Success)
        val takeoff = transport.sendCommand("takeoff")
        assertTrue(takeoff is TelloCommandResult.Success)
        assertFalse(plant.snapshot().airborne)
        advanceTimeBy(149L)
        assertFalse(plant.snapshot().airborne)
        advanceTimeBy(1L)
        runCurrent()
        assertTrue(plant.snapshot().airborne)

        assertTrue(transport.sendCommand("land") is TelloCommandResult.Success)
        assertTrue(plant.snapshot().airborne)
        advanceTimeBy(150L)
        runCurrent()
        assertFalse(plant.snapshot().airborne)
        assertTrue(transport.sendCommand("flip l") is TelloCommandResult.Rejected)
    }

    @Test fun `transport captures exact final RC and stops after close`() = runTest {
        val plant = SimulatorPlant().also { it.setAirborne(true) }
        val transport = SimulatorTelloTransport(backgroundScope, plant)
        val vector = RcVector(1, 2, 3, 4)

        transport.sendRc(vector)
        assertEquals(vector, transport.snapshot.value.latestRc)
        assertEquals(SimulatorAxes(1, 2, 3, 4), plant.snapshot().appliedAxes)
        transport.close()
        val closedSnapshot = transport.snapshot.value
        advanceTimeBy(1_000L)
        assertEquals(closedSnapshot, transport.snapshot.value)
        assertTrue(transport.isClosed())
    }

    @Test fun `virtual video publishes fresh synthetic results and clears them immediately`() = runTest {
        val plant = SimulatorPlant()
        val video = SimulatorVideoController(
            backgroundScope,
            plant,
            sourceNowNanos = { 1_000_000_000L + testScheduler.currentTime * 1_000_000L },
        )

        assertTrue(video.prepare().isSuccess)
        video.streamAcknowledged()
        runCurrent()
        assertEquals(VideoAvailability.Streaming, video.state.value.availability)
        assertTrue(video.setPersonDetectionEnabled(true).isSuccess)
        advanceTimeBy(100L)
        runCurrent()
        val detected = video.state.value
        assertEquals(PersonDetectionState.Detecting, detected.personDetectionState)
        assertEquals(1, detected.personDetections.size)
        assertEquals(detected.processedDetectorFrameSequence, detected.personDetections.single().frameSequence)
        assertEquals(detected.processedDetectorSourceTimestampNanos, detected.personDetections.single().sourceTimestampNanos)

        plant.togglePersonVisibility()
        advanceTimeBy(100L)
        runCurrent()
        assertTrue(video.state.value.personDetections.isEmpty())
        video.setPersonDetectionEnabled(false)
        assertEquals(PersonDetectionState.Off, video.state.value.personDetectionState)
        assertTrue(video.state.value.personDetections.isEmpty())
        video.close()
        assertEquals(VideoAvailability.Unavailable, video.state.value.availability)
    }
}
