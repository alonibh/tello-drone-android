package com.alonibh.tellodrone.tello

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestAnalysisFrameBufferTest {
    @Test fun `newer unread frame replaces and releases old frame`() {
        val buffer = LatestAnalysisFrameBuffer<FakeFrame>()
        val old = FakeFrame(1, 100)
        val newest = FakeFrame(2, 200)

        assertTrue(buffer.offer(old))
        assertTrue(buffer.offer(newest))

        assertEquals(1, old.closeCount)
        assertEquals(1, buffer.pendingCount)
        assertEquals(1, buffer.droppedFrames)
        assertSame(newest, buffer.takeLatest())
    }

    @Test fun `many producer offers never grow beyond one pending frame`() {
        val buffer = LatestAnalysisFrameBuffer<FakeFrame>()
        val frames = (1L..1_000L).map { FakeFrame(it, it * 10) }

        frames.forEach {
            assertTrue(buffer.offer(it))
            assertEquals(1, buffer.pendingCount)
        }

        assertEquals(999, frames.count { it.closeCount == 1 })
        assertEquals(999, buffer.droppedFrames)
        assertSame(frames.last(), buffer.takeLatest())
        assertEquals(0, buffer.pendingCount)
    }

    @Test fun `out of order sequence or timestamp is rejected and released`() {
        val buffer = LatestAnalysisFrameBuffer<FakeFrame>()
        val accepted = FakeFrame(5, 500)
        val oldSequence = FakeFrame(4, 600)
        val oldTimestamp = FakeFrame(6, 400)

        assertTrue(buffer.offer(accepted))
        assertFalse(buffer.offer(oldSequence))
        assertFalse(buffer.offer(oldTimestamp))

        assertEquals(1, oldSequence.closeCount)
        assertEquals(1, oldTimestamp.closeCount)
        assertSame(accepted, buffer.takeLatest())
    }

    @Test fun `slow consumer holds one frame while producer keeps only newest pending`() {
        val buffer = LatestAnalysisFrameBuffer<FakeFrame>()
        val consumed = FakeFrame(1, 100)
        buffer.offer(consumed)
        assertSame(consumed, buffer.takeLatest())

        val pendingFrames = (2L..20L).map { FakeFrame(it, it * 100) }
        pendingFrames.forEach { buffer.offer(it) }

        assertEquals(0, consumed.closeCount)
        assertEquals(1, buffer.pendingCount)
        assertEquals(18, pendingFrames.dropLast(1).count { it.closeCount == 1 })
        assertSame(pendingFrames.last(), buffer.takeLatest())
    }

    @Test fun `reset releases pending frame and accepts a new reconnect ordering`() {
        val buffer = LatestAnalysisFrameBuffer<FakeFrame>()
        val beforeReset = FakeFrame(10, 1_000)
        buffer.offer(beforeReset)

        buffer.reset()
        val afterReconnect = FakeFrame(1, 100)

        assertEquals(1, beforeReset.closeCount)
        assertNull(buffer.takeLatest())
        assertEquals(0, buffer.droppedFrames)
        assertTrue(buffer.offer(afterReconnect))
        assertSame(afterReconnect, buffer.takeLatest())
    }

    @Test fun `close releases pending frame and rejects all later offers`() {
        val buffer = LatestAnalysisFrameBuffer<FakeFrame>()
        val pending = FakeFrame(1, 100)
        val afterClose = FakeFrame(2, 200)
        buffer.offer(pending)

        buffer.close()

        assertEquals(1, pending.closeCount)
        assertEquals(0, buffer.pendingCount)
        assertFalse(buffer.offer(afterClose))
        assertEquals(1, afterClose.closeCount)
        assertNull(buffer.takeLatest())
    }

    @Test fun `absent consumer replaces frames and releases every obsolete lease`() {
        val buffer = LatestAnalysisFrameBuffer<FakeFrame>()
        val frames = (1L..5L).map { FakeFrame(it, it) }
        frames.forEach(buffer::offer)

        assertEquals(listOf(1, 1, 1, 1, 0), frames.map { it.closeCount })
        buffer.reset()
        assertEquals(1, frames.last().closeCount)
        assertEquals(0, buffer.pendingCount)
    }

    private class FakeFrame(
        override val sequence: Long,
        override val captureTimestampNanos: Long,
    ) : OrderedAnalysisFrame {
        var closeCount = 0
            private set

        override fun close() {
            closeCount++
        }
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
