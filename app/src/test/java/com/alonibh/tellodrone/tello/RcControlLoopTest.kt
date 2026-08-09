package com.alonibh.tellodrone.tello

import com.alonibh.tellodrone.domain.ManualControlVector
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

    internal class FakeClock(var value: Long) : MonotonicClock { override fun nowMillis() = value }
}
