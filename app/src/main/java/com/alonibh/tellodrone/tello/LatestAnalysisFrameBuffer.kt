package com.alonibh.tellodrone.tello

/**
 * Ordering and ownership needed by the platform-independent latest-frame handoff.
 * Implementations must make [close] idempotent.
 */
interface OrderedAnalysisFrame : AutoCloseable {
    val sequence: Long
    val captureTimestampNanos: Long
}

/**
 * A single-slot, drop-old frame handoff. It never contains more than one pending frame and owns
 * that frame until [takeLatest], replacement, [reset], or [close].
 */
class LatestAnalysisFrameBuffer<T : OrderedAnalysisFrame> : AutoCloseable {
    private val lock = Any()
    private var pending: T? = null
    private var lastAcceptedSequence = Long.MIN_VALUE
    private var lastAcceptedTimestampNanos = Long.MIN_VALUE
    private var terminallyClosed = false

    var droppedFrames: Long = 0
        private set

    val pendingCount: Int
        get() = synchronized(lock) { if (pending == null) 0 else 1 }

    /** Returns false and releases [frame] when closed or older than the accepted stream. */
    fun offer(frame: T): Boolean {
        var replaced: T? = null
        val accepted = synchronized(lock) {
            if (terminallyClosed ||
                frame.sequence <= lastAcceptedSequence ||
                frame.captureTimestampNanos < lastAcceptedTimestampNanos
            ) {
                droppedFrames++
                false
            } else {
                replaced = pending
                if (replaced != null) droppedFrames++
                pending = frame
                lastAcceptedSequence = frame.sequence
                lastAcceptedTimestampNanos = frame.captureTimestampNanos
                true
            }
        }
        if (accepted) replaced?.close() else frame.close()
        return accepted
    }

    /** Transfers ownership of the newest pending frame to the caller. */
    fun takeLatest(): T? = synchronized(lock) {
        pending.also { pending = null }
    }

    /** Clears a surface/session generation while keeping this handoff reusable. */
    fun reset() {
        val released = synchronized(lock) {
            pending.also {
                pending = null
                lastAcceptedSequence = Long.MIN_VALUE
                lastAcceptedTimestampNanos = Long.MIN_VALUE
                droppedFrames = 0
            }
        }
        released?.close()
    }

    /** Permanently closes the handoff and releases the pending frame. */
    override fun close() {
        val released = synchronized(lock) {
            terminallyClosed = true
            pending.also { pending = null }
        }
        released?.close()
    }
}
