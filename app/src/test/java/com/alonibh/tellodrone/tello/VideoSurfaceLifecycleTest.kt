package com.alonibh.tellodrone.tello

import com.alonibh.tellodrone.domain.VideoAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSurfaceLifecycleTest {
    @Test fun `repeated destination transitions retain exactly one active surface`() {
        val lifecycle = VideoSurfaceLifecycle<Any>()
        var active = Any()
        assertTrue(lifecycle.attach(active))

        repeat(20) {
            val old = active
            active = Any()
            assertTrue(lifecycle.attach(active))
            assertFalse(lifecycle.detach(old))
            assertSame(active, lifecycle.current)
        }

        assertEquals(21L, lifecycle.generation)
    }

    @Test fun `stale old detach cannot clear or advance the replacement surface`() {
        val lifecycle = VideoSurfaceLifecycle<Any>()
        val dashboard = Any()
        val tracking = Any()

        assertTrue(lifecycle.attach(dashboard))
        assertTrue(lifecycle.attach(tracking))
        val replacementGeneration = lifecycle.generation

        assertFalse(lifecycle.detach(dashboard))
        assertSame(tracking, lifecycle.current)
        assertEquals(replacementGeneration, lifecycle.generation)
        assertFalse(lifecycle.attach(tracking))
        assertEquals(replacementGeneration, lifecycle.generation)

        assertTrue(lifecycle.detach(tracking))
        assertNull(lifecycle.current)
        assertEquals(replacementGeneration + 1L, lifecycle.generation)
    }

    @Test fun `stream acknowledgement and surface attach remain recovering until a rendered frame`() {
        val recovery = VideoRecoveryStateMachine()

        assertEquals(VideoAvailability.Unavailable, recovery.onSurfaceAttached(100L).availability)
        val acknowledged = recovery.onStreamAcknowledged(200L)
        assertEquals(VideoAvailability.Recovering, acknowledged.availability)
        assertTrue(acknowledged.recoveryStarted)

        val rendered = recovery.onFrameRendered(350L)
        assertEquals(VideoAvailability.Streaming, rendered.availability)
        assertEquals(150L, rendered.recoveryDurationNanos)
    }

    @Test fun `surface detach and reattach reenter recovery and only current render resumes streaming`() {
        val recovery = VideoRecoveryStateMachine()
        recovery.onSurfaceAttached(100L)
        recovery.onStreamAcknowledged(110L)
        recovery.onFrameRendered(120L)

        assertEquals(VideoAvailability.Unavailable, recovery.onSurfaceDetached().availability)
        assertEquals(VideoAvailability.Unavailable, recovery.onFrameRendered(130L).availability)
        assertEquals(VideoAvailability.Recovering, recovery.onSurfaceAttached(200L).availability)
        assertEquals(VideoAvailability.Streaming, recovery.onFrameRendered(260L).availability)
    }

    @Test fun `repeated corruption recovery waits for decoder resynchronization and a new rendered frame`() {
        val recovery = VideoRecoveryStateMachine()
        recovery.onSurfaceAttached(100L)
        recovery.onStreamAcknowledged(110L)
        recovery.onFrameRendered(120L)

        val corrupt = recovery.requireDecoderResynchronization(500L)
        assertEquals(VideoAvailability.Recovering, corrupt.availability)
        assertTrue(corrupt.recoveryStarted)
        assertEquals(VideoAvailability.Recovering, recovery.onFrameRendered(600L).availability)
        recovery.onDecoderResynchronized()
        assertEquals(VideoAvailability.Streaming, recovery.onFrameRendered(700L).availability)
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
