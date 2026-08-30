package com.alonibh.tellodrone.tello

import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.VideoAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class VideoSurfaceLifecycleTest {

    @Test
    fun `test 1 - render critical section blocks detach until render completes`() {
        val lifecycle = VideoSurfaceLifecycle<Any>()
        val surface1 = Any()
        assertTrue(lifecycle.attach(surface1))
        val gen1 = lifecycle.generation
        assertEquals(1L, gen1)

        val insideLease = CountDownLatch(1)
        val allowLeaseToComplete = CountDownLatch(1)
        val detachStarted = CountDownLatch(1)
        val detachCompleted = AtomicBoolean(false)
        val renderActionExecuted = AtomicBoolean(false)

        val renderThread = thread(name = "test-render-thread") {
            lifecycle.withRenderLease(
                expectedSurface = surface1,
                expectedGeneration = gen1,
                predicate = { true },
            ) {
                renderActionExecuted.set(true)
                insideLease.countDown()
                allowLeaseToComplete.await(5, TimeUnit.SECONDS)
            }
        }

        assertTrue("Render thread should enter critical section", insideLease.await(5, TimeUnit.SECONDS))
        assertTrue("Render action must be executing", renderActionExecuted.get())

        val detachThread = thread(name = "test-ui-detach-thread") {
            detachStarted.countDown()
            lifecycle.detach(surface1)
            detachCompleted.set(true)
        }

        assertTrue("detachThread must have started", detachStarted.await(5, TimeUnit.SECONDS))
        // Verify detach has NOT completed while render lease is held
        Thread.sleep(100)
        assertFalse("detach must be blocked while render lease is active", detachCompleted.get())

        // Release render lease
        allowLeaseToComplete.countDown()
        renderThread.join(5000)
        detachThread.join(5000)

        assertTrue("detach must complete once render lease finishes", detachCompleted.get())
        assertNull("Surface must be detached", lifecycle.current)
        assertEquals("Generation must advance on detach", 2L, lifecycle.generation)
    }

    @Test
    fun `test 2 - detach first denies subsequent render lease and action is never executed`() {
        val lifecycle = VideoSurfaceLifecycle<Any>()
        val surface1 = Any()
        assertTrue(lifecycle.attach(surface1))
        val gen1 = lifecycle.generation

        assertTrue(lifecycle.detach(surface1))
        assertEquals(2L, lifecycle.generation)

        var actionExecuted = false
        val result = lifecycle.withRenderLease(
            expectedSurface = surface1,
            expectedGeneration = gen1,
            predicate = { true },
        ) {
            actionExecuted = true
        }

        assertFalse("Render action must NOT execute after detach", actionExecuted)
        assertTrue("Lease result must be Denied", result is RenderLeaseResult.Denied)
        val denied = result as RenderLeaseResult.Denied
        assertTrue(denied.isStaleGeneration)
        assertTrue(denied.reason.contains("detached") || denied.reason.contains("Stale"))
    }

    @Test
    fun `test 3 - replacement Surface denies old render request and preserves active replacement`() {
        val lifecycle = VideoSurfaceLifecycle<Any>()
        val surfaceA = Any()
        val surfaceB = Any()

        assertTrue(lifecycle.attach(surfaceA))
        val genA = lifecycle.generation

        assertTrue(lifecycle.detach(surfaceA))
        assertTrue(lifecycle.attach(surfaceB))
        val genB = lifecycle.generation
        assertEquals(3L, genB)

        var oldActionExecuted = false
        val result = lifecycle.withRenderLease(
            expectedSurface = surfaceA,
            expectedGeneration = genA,
            predicate = { true },
        ) {
            oldActionExecuted = true
        }

        assertFalse("Old render action must NOT execute", oldActionExecuted)
        assertTrue("Old render must be denied", result is RenderLeaseResult.Denied)
        assertSame("Current surface must remain surfaceB", surfaceB, lifecycle.current)

        // Stale detach of surfaceA must be ignored
        assertFalse("Stale detach must be rejected", lifecycle.detach(surfaceA))
        assertSame("surfaceB must remain active", surfaceB, lifecycle.current)
    }

    @Test
    fun `test 4 - current generation render executes action exactly once`() {
        val lifecycle = VideoSurfaceLifecycle<Any>()
        val surfaceB = Any()
        assertTrue(lifecycle.attach(surfaceB))
        val genB = lifecycle.generation

        var executionCount = 0
        val result = lifecycle.withRenderLease(
            expectedSurface = surfaceB,
            expectedGeneration = genB,
            predicate = { true },
        ) {
            executionCount++
            "rendered-ok"
        }

        assertEquals(1, executionCount)
        assertTrue(result is RenderLeaseResult.Granted)
        assertEquals("rendered-ok", (result as RenderLeaseResult.Granted).value)
    }

    @Test
    fun `test 5 - invalid surface denies render lease without executing action`() {
        val lifecycle = VideoSurfaceLifecycle<Any>()
        val surfaceB = Any()
        assertTrue(lifecycle.attach(surfaceB))
        val genB = lifecycle.generation

        var actionExecuted = false
        val result = lifecycle.withRenderLease(
            expectedSurface = surfaceB,
            expectedGeneration = genB,
            predicate = { false }, // surface is invalid
        ) {
            actionExecuted = true
        }

        assertFalse("Render action must NOT execute when predicate returns false", actionExecuted)
        assertTrue(result is RenderLeaseResult.Denied)
        val denied = result as RenderLeaseResult.Denied
        assertFalse(denied.isStaleGeneration)
        assertEquals("Surface is invalid", denied.reason)
    }

    @Test
    fun `test 6 - repeated concurrent lifecycle cycles maintain exact single surface and monotonic generations`() {
        val lifecycle = VideoSurfaceLifecycle<Any>()
        val recovery = VideoRecoveryStateMachine()
        recovery.onStreamAcknowledged(100L)

        val renderedSuccessCount = AtomicInteger(0)

        repeat(50) { cycle ->
            val surface = Any()
            assertTrue(lifecycle.attach(surface))
            val currentGen = lifecycle.generation
            assertSame(surface, lifecycle.current)

            recovery.onSurfaceAttached(currentGen, 1000L * cycle)

            val renderResult = lifecycle.withRenderLease(
                expectedSurface = surface,
                expectedGeneration = currentGen,
                predicate = { true },
            ) {
                renderedSuccessCount.incrementAndGet()
            }
            assertTrue(renderResult is RenderLeaseResult.Granted)

            val renderTransition = recovery.onFrameRendered(currentGen, 1000L * cycle + 50L)
            assertEquals(VideoAvailability.Streaming, renderTransition.availability)

            assertTrue(lifecycle.detach(surface))
            assertNull(lifecycle.current)

            val detachTransition = recovery.onSurfaceDetached(lifecycle.generation)
            assertEquals(VideoAvailability.Unavailable, detachTransition.availability)

            // Post-detach render must be denied
            val postDetachRender = lifecycle.withRenderLease(
                expectedSurface = surface,
                expectedGeneration = currentGen,
                predicate = { true },
            ) {
                renderedSuccessCount.incrementAndGet()
            }
            assertTrue(postDetachRender is RenderLeaseResult.Denied)
        }

        assertEquals(50, renderedSuccessCount.get())
        assertEquals(100L, lifecycle.generation)
    }

    @Test
    fun `test 7 - stale detach preserves replacement surface and generation`() {
        val lifecycle = VideoSurfaceLifecycle<Any>()
        val surfaceA = Any()
        val surfaceB = Any()

        assertTrue(lifecycle.attach(surfaceA))
        assertTrue(lifecycle.attach(surfaceB))
        val genB = lifecycle.generation

        assertFalse("Stale detach of A must return false", lifecycle.detach(surfaceA))
        assertSame(surfaceB, lifecycle.current)
        assertEquals(genB, lifecycle.generation)
    }

    @Test
    fun `test 8 - recovery state enforces that only current generation frame transitions to Streaming`() {
        val recovery = VideoRecoveryStateMachine()
        recovery.onStreamAcknowledged(100L)

        // Surface 1 attached and streaming
        recovery.onSurfaceAttached(1L, 110L)
        assertEquals(VideoAvailability.Streaming, recovery.onFrameRendered(1L, 120L).availability)

        // Detach
        recovery.onSurfaceDetached(2L)
        assertEquals(VideoAvailability.Unavailable, recovery.currentAvailability)

        // Surface 2 attached (generation 3)
        recovery.onSurfaceAttached(3L, 200L)
        assertEquals(VideoAvailability.Recovering, recovery.currentAvailability)

        // Stale frame from generation 1 cannot end recovery
        val staleTransition = recovery.onFrameRendered(1L, 250L)
        assertEquals(VideoAvailability.Recovering, staleTransition.availability)
        assertEquals(VideoAvailability.Recovering, recovery.currentAvailability)

        // Current generation 3 frame transitions to Streaming
        val currentTransition = recovery.onFrameRendered(3L, 300L)
        assertEquals(VideoAvailability.Streaming, currentTransition.availability)
        assertEquals(VideoAvailability.Streaming, recovery.currentAvailability)
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
