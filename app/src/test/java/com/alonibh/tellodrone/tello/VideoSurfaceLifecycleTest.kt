package com.alonibh.tellodrone.tello

import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.VideoAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSurfaceLifecycleTest {

    @Test
    fun `test 1 - detach while a render is pending suppresses output rendering`() {
        val lifecycle = VideoSurfaceLifecycle<Any>()
        val surface1 = Any()
        assertTrue(lifecycle.attach(surface1))
        val gen1 = lifecycle.generation
        assertEquals(1L, gen1)

        // Codec prepared for gen1
        val codecBoundGen = gen1

        // Detach surface 1
        assertTrue(lifecycle.detach(surface1))
        val currentGen = lifecycle.generation
        assertEquals(2L, currentGen)

        // Render decision executes after detach
        val decision = VideoRenderAuthorizer.authorizeRender(
            codecBoundGeneration = codecBoundGen,
            currentSurfaceGeneration = currentGen,
            isSurfaceAttached = lifecycle.current != null,
            isSurfaceValid = true,
            isCodecConfigOrEos = false,
        )

        assertFalse("Old output must NOT be rendered after detach", decision.shouldRender)
        assertTrue("Decision must indicate stale generation", decision.isStaleGeneration)
        assertTrue(decision.reason.contains("detached") || decision.reason.contains("Stale"))
    }

    @Test
    fun `test 2 - replacement surface rejects older generation outputs from ending recovery`() {
        val recovery = VideoRecoveryStateMachine()
        recovery.onStreamAcknowledged(100L)

        // Surface 1 attached and streaming
        recovery.onSurfaceAttached(1L, 110L)
        assertEquals(VideoAvailability.Streaming, recovery.onFrameRendered(1L, 120L).availability)

        // Surface 1 detached
        assertEquals(VideoAvailability.Unavailable, recovery.onSurfaceDetached(2L).availability)

        // Surface 2 attached -> enters Recovering
        val attachedTransition = recovery.onSurfaceAttached(3L, 200L)
        assertEquals(VideoAvailability.Recovering, attachedTransition.availability)

        // Stale frame from generation 1 arrives
        val staleRenderTransition = recovery.onFrameRendered(1L, 250L)
        assertEquals(
            "Stale frame from generation 1 must NOT end recovery or transition to Streaming",
            VideoAvailability.Recovering,
            staleRenderTransition.availability,
        )
        assertEquals(
            "State machine must remain in Recovering",
            VideoAvailability.Recovering,
            recovery.currentAvailability,
        )
    }

    @Test
    fun `test 3 - current-generation frame resumes streaming after reattach`() {
        val recovery = VideoRecoveryStateMachine()
        recovery.onStreamAcknowledged(100L)

        // Surface 1 attached and streaming
        recovery.onSurfaceAttached(1L, 110L)
        recovery.onFrameRendered(1L, 120L)

        // Detach
        recovery.onSurfaceDetached(2L)

        // Surface 2 attached (generation 3)
        recovery.onSurfaceAttached(3L, 200L)
        assertEquals(VideoAvailability.Recovering, recovery.currentAvailability)

        // Current generation 3 frame rendered
        val transition = recovery.onFrameRendered(3L, 260L)
        assertEquals(VideoAvailability.Streaming, transition.availability)
        assertEquals(60L, transition.recoveryDurationNanos)
        assertEquals(VideoAvailability.Streaming, recovery.currentAvailability)
    }

    @Test
    fun `test 4 - stale old detach cannot clear or advance the replacement surface`() {
        val lifecycle = VideoSurfaceLifecycle<Any>()
        val surface1 = Any()
        val surface2 = Any()

        assertTrue(lifecycle.attach(surface1))
        assertTrue(lifecycle.attach(surface2))
        val replacementGeneration = lifecycle.generation

        assertFalse("Stale detach of surface1 must be rejected", lifecycle.detach(surface1))
        assertSame(surface2, lifecycle.current)
        assertEquals(replacementGeneration, lifecycle.generation)
        assertFalse(lifecycle.attach(surface2))
        assertEquals(replacementGeneration, lifecycle.generation)

        assertTrue(lifecycle.detach(surface2))
        assertNull(lifecycle.current)
        assertEquals(replacementGeneration + 1L, lifecycle.generation)
    }

    @Test
    fun `test 5 - setOutputSurface transition safely authorizes only new generation frames`() {
        val recovery = VideoRecoveryStateMachine()
        recovery.onStreamAcknowledged(100L)

        // Surface 1 streaming
        recovery.onSurfaceAttached(1L, 110L)
        recovery.onFrameRendered(1L, 120L)
        assertEquals(VideoAvailability.Streaming, recovery.currentAvailability)

        // Detach surface 1
        recovery.onSurfaceDetached(2L)
        assertEquals(VideoAvailability.Unavailable, recovery.currentAvailability)

        // Surface 2 attached (generation 3)
        recovery.onSurfaceAttached(3L, 200L)
        assertEquals(VideoAvailability.Recovering, recovery.currentAvailability)

        // setOutputSurface succeeds: codec is now bound to generation 3
        val codecBoundGeneration = 3L

        // Verify older generation 1 render is rejected
        val staleDecision = VideoRenderAuthorizer.authorizeRender(
            codecBoundGeneration = 1L,
            currentSurfaceGeneration = 3L,
            isSurfaceAttached = true,
            isSurfaceValid = true,
            isCodecConfigOrEos = false,
        )
        assertFalse(staleDecision.shouldRender)
        assertTrue(staleDecision.isStaleGeneration)

        // Verify generation 3 render is authorized
        val validDecision = VideoRenderAuthorizer.authorizeRender(
            codecBoundGeneration = codecBoundGeneration,
            currentSurfaceGeneration = 3L,
            isSurfaceAttached = true,
            isSurfaceValid = true,
            isCodecConfigOrEos = false,
        )
        assertTrue(validDecision.shouldRender)
        assertFalse(validDecision.isStaleGeneration)

        // Render generation 3 frame -> Streaming
        val transition = recovery.onFrameRendered(codecBoundGeneration, 280L)
        assertEquals(VideoAvailability.Streaming, transition.availability)
    }

    @Test
    fun `test 6 - setOutputSurface failure path releases decoder and requires fresh IDR without terminal error`() {
        val recovery = VideoRecoveryStateMachine()
        recovery.onStreamAcknowledged(100L)

        // Surface 1 streaming
        recovery.onSurfaceAttached(1L, 110L)
        recovery.onFrameRendered(1L, 120L)

        // Surface 1 detached, Surface 2 attached (generation 3)
        recovery.onSurfaceDetached(2L)
        recovery.onSurfaceAttached(3L, 200L)

        // setOutputSurface fails: require decoder resync
        val resyncTransition = recovery.requireDecoderResynchronization(210L)
        assertEquals(VideoAvailability.Recovering, resyncTransition.availability)
        assertFalse("Failure must not transition to Error", resyncTransition.availability == VideoAvailability.Error)

        // Attempting to render before IDR resynchronization keeps state in Recovering
        val earlyRender = recovery.onFrameRendered(3L, 250L)
        assertEquals(VideoAvailability.Recovering, earlyRender.availability)

        // IDR arrived and decoder was recreated
        recovery.onDecoderResynchronized()

        // First valid frame from recreated decoder resumes Streaming
        val recoveredRender = recovery.onFrameRendered(3L, 300L)
        assertEquals(VideoAvailability.Streaming, recoveredRender.availability)
    }

    @Test
    fun `test 7 - decoder output racing with detach suppresses stale render deterministically`() {
        val lifecycle = VideoSurfaceLifecycle<Any>()
        val recovery = VideoRecoveryStateMachine()
        recovery.onStreamAcknowledged(100L)

        val surface1 = Any()
        assertTrue(lifecycle.attach(surface1))
        val gen1 = lifecycle.generation
        recovery.onSurfaceAttached(gen1, 110L)
        recovery.onFrameRendered(gen1, 120L)
        assertEquals(VideoAvailability.Streaming, recovery.currentAvailability)

        // Codec is processing an output buffer bound to gen1
        val codecBoundGen = gen1

        // Concurrent detach occurs on UI thread
        assertTrue(lifecycle.detach(surface1))
        val detachGen = lifecycle.generation
        recovery.onSurfaceDetached(detachGen)
        assertEquals(VideoAvailability.Unavailable, recovery.currentAvailability)

        // Render authorizer executes on codec thread
        val renderDecision = VideoRenderAuthorizer.authorizeRender(
            codecBoundGeneration = codecBoundGen,
            currentSurfaceGeneration = lifecycle.generation,
            isSurfaceAttached = lifecycle.current != null,
            isSurfaceValid = true,
            isCodecConfigOrEos = false,
        )
        assertFalse("Render must be suppressed when racing with detach", renderDecision.shouldRender)
        assertTrue(renderDecision.isStaleGeneration)

        // Even if old frame was processed, state machine rejects it
        val transition = recovery.onFrameRendered(codecBoundGen, 150L)
        assertEquals(VideoAvailability.Unavailable, transition.availability)
    }

    @Test
    fun `test 8 - repeated lifecycle cycles maintain exact single surface and monotonic generations`() {
        val lifecycle = VideoSurfaceLifecycle<Any>()
        val recovery = VideoRecoveryStateMachine()
        recovery.onStreamAcknowledged(100L)

        var expectedGeneration = 0L

        repeat(25) { cycle ->
            val surface = Any()
            assertTrue(lifecycle.attach(surface))
            expectedGeneration++
            assertEquals(expectedGeneration, lifecycle.generation)
            assertSame(surface, lifecycle.current)

            val attachTransition = recovery.onSurfaceAttached(lifecycle.generation, 1000L * cycle)
            assertEquals(VideoAvailability.Recovering, attachTransition.availability)

            val renderTransition = recovery.onFrameRendered(lifecycle.generation, 1000L * cycle + 50L)
            assertEquals(VideoAvailability.Streaming, renderTransition.availability)
            assertEquals(VideoAvailability.Streaming, recovery.currentAvailability)

            assertTrue(lifecycle.detach(surface))
            expectedGeneration++
            assertEquals(expectedGeneration, lifecycle.generation)
            assertNull(lifecycle.current)

            val detachTransition = recovery.onSurfaceDetached(lifecycle.generation)
            assertEquals(VideoAvailability.Unavailable, detachTransition.availability)
            assertEquals(VideoAvailability.Unavailable, recovery.currentAvailability)
        }

        assertEquals(50L, lifecycle.generation)
    }

    @Test
    fun `test 9 - export-style Activity transition recovers video from Grounded to Streaming`() {
        val lifecycle = VideoSurfaceLifecycle<Any>()
        val recovery = VideoRecoveryStateMachine()
        recovery.onStreamAcknowledged(100L)

        var flightState = FlightState.Grounded

        // Drone is grounded, surface 1 is attached and streaming
        val surface1 = Any()
        assertTrue(lifecycle.attach(surface1))
        recovery.onSurfaceAttached(lifecycle.generation, 200L)
        recovery.onFrameRendered(lifecycle.generation, 250L)
        assertEquals(VideoAvailability.Streaming, recovery.currentAvailability)

        // User taps "Export Trace" while Grounded -> CreateDocument activity opens -> surface 1 detached
        assertEquals(FlightState.Grounded, flightState)
        assertTrue(lifecycle.detach(surface1))
        recovery.onSurfaceDetached(lifecycle.generation)
        assertEquals(VideoAvailability.Unavailable, recovery.currentAvailability)

        // User finishes exporting ZIP and returns to app -> replacement surface 2 attached
        val surface2 = Any()
        assertTrue(lifecycle.attach(surface2))
        val surface2Gen = lifecycle.generation
        val attachTransition = recovery.onSurfaceAttached(surface2Gen, 500L)
        assertEquals(VideoAvailability.Recovering, attachTransition.availability)

        // Codec resyncs on replacement surface and renders first frame
        val frameTransition = recovery.onFrameRendered(surface2Gen, 650L)
        assertEquals(VideoAvailability.Streaming, frameTransition.availability)
        assertEquals(150L, frameTransition.recoveryDurationNanos)
        assertEquals(VideoAvailability.Streaming, recovery.currentAvailability)
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
