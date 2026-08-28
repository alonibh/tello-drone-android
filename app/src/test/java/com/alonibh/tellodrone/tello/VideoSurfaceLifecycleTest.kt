package com.alonibh.tellodrone.tello

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
}
// SPDX-License-Identifier: AGPL-3.0-only
