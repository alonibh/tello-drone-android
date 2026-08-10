@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.alonibh.tellodrone.tello

import com.alonibh.tellodrone.domain.ManualControlVector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RcControlLoopTest {
    @Test fun `RC axes and requested speed are clamped conservatively`() = runTest {
        val clock = FakeClock(1_000)
        val loop = RcControlLoop(backgroundScope, {}, clock)
        loop.setEnabled(true)
        loop.setHealthy(true)
        loop.publish(ManualControlVector(lateral = 3f, forward = -2f, vertical = .5f, yaw = -.5f), 100)

        assertEquals(RcVector(40, -40, 20, -20), loop.currentVector())
    }

    @Test fun `stale desired input becomes zero movement`() = runTest {
        val clock = FakeClock(1_000)
        val loop = RcControlLoop(backgroundScope, {}, clock, inputTtlMillis = 250)
        loop.setEnabled(true)
        loop.setHealthy(true)
        loop.publish(ManualControlVector(forward = 1f), 20)
        assertEquals(RcVector(forward = 20), loop.currentVector())

        clock.value = 1_251
        assertEquals(RcVector.Zero, loop.currentVector())
    }

    @Test fun `desired input expires at the TTL boundary`() = runTest {
        val clock = FakeClock(1_000)
        val loop = RcControlLoop(backgroundScope, {}, clock, inputTtlMillis = 250)
        loop.setEnabled(true)
        loop.setHealthy(true)
        loop.publish(ManualControlVector(forward = 1f), 20)

        clock.value = 1_250
        assertEquals(RcVector.Zero, loop.currentVector())
    }

    @Test fun `joystick release publishes immediate zero`() = runTest {
        val sent = mutableListOf<RcVector>()
        val clock = FakeClock(1_000)
        val loop = RcControlLoop(backgroundScope, { sent += it }, clock)
        loop.setEnabled(true)
        loop.setHealthy(true)
        loop.publish(ManualControlVector(yaw = 1f), 20)
        loop.sendCycle()
        loop.publish(ManualControlVector(), 20)
        loop.sendCycle()

        assertEquals(RcVector(yaw = 20), sent.first())
        assertEquals(RcVector.Zero, sent.last())
    }

    @Test fun `safety zero cannot be overtaken by a prepared nonzero send`() = runTest {
        val clock = FakeClock(1_000)
        val sent = mutableListOf<RcVector>()
        val firstSendStarted = CompletableDeferred<Unit>()
        val releaseFirstSend = CompletableDeferred<Unit>()
        val loop = RcControlLoop(backgroundScope, { vector ->
            sent += vector
            if (vector != RcVector.Zero && !firstSendStarted.isCompleted) {
                firstSendStarted.complete(Unit)
                releaseFirstSend.await()
            }
        }, clock)
        loop.setEnabled(true)
        loop.setHealthy(true)
        loop.publish(ManualControlVector(forward = 1f), 20)

        val cycle = async { loop.sendCycle() }
        firstSendStarted.await()
        val stop = async { loop.clearAndSendZero() }
        runCurrent()
        assertEquals(listOf(RcVector(forward = 20)), sent)

        releaseFirstSend.complete(Unit)
        cycle.await()
        stop.await()
        assertEquals(listOf(RcVector(forward = 20), RcVector.Zero), sent)
    }

    internal class FakeClock(var value: Long) : MonotonicClock { override fun nowMillis() = value }
}
