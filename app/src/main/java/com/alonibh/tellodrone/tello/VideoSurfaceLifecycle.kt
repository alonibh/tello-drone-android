package com.alonibh.tellodrone.tello

import com.alonibh.tellodrone.domain.VideoAvailability
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Identity-based ownership for the one UI surface currently allowed to receive decoded video. */
internal class VideoSurfaceLifecycle<T : Any> {
    private val active = AtomicReference<T?>()
    private val generationCounter = AtomicLong()

    val current: T? get() = active.get()
    val generation: Long get() = generationCounter.get()

    fun attach(value: T): Boolean {
        if (active.getAndSet(value) === value) return false
        generationCounter.incrementAndGet()
        return true
    }

    fun detach(value: T): Boolean {
        if (!active.compareAndSet(value, null)) return false
        generationCounter.incrementAndGet()
        return true
    }
}

internal data class VideoRecoveryTransition(
    val availability: VideoAvailability,
    val recoveryStarted: Boolean = false,
    val recoveryDurationNanos: Long? = null,
)

/** Pure state machine: only a real rendered frame can transition Recovering to Streaming. */
internal class VideoRecoveryStateMachine {
    private var streamAcknowledged = false
    private var surfaceAttached = false
    private var availability = VideoAvailability.Unavailable
    private var recoveryStartedAtNanos: Long? = null
    private var decoderResynchronizationPending = false

    @Synchronized
    fun onStreamAcknowledged(nowNanos: Long): VideoRecoveryTransition {
        streamAcknowledged = true
        return if (surfaceAttached) beginRecovery(nowNanos) else transition(VideoAvailability.Unavailable)
    }

    @Synchronized
    fun onSurfaceAttached(nowNanos: Long): VideoRecoveryTransition {
        surfaceAttached = true
        return if (streamAcknowledged) beginRecovery(nowNanos) else transition(VideoAvailability.Unavailable)
    }

    @Synchronized
    fun onSurfaceDetached(): VideoRecoveryTransition {
        surfaceAttached = false
        recoveryStartedAtNanos = null
        return transition(VideoAvailability.Unavailable)
    }

    @Synchronized
    fun requireRecovery(nowNanos: Long): VideoRecoveryTransition =
        if (streamAcknowledged && surfaceAttached) beginRecovery(nowNanos)
        else transition(VideoAvailability.Unavailable)

    @Synchronized
    fun requireDecoderResynchronization(nowNanos: Long): VideoRecoveryTransition {
        decoderResynchronizationPending = true
        return requireRecovery(nowNanos)
    }

    @Synchronized
    fun onDecoderResynchronized() {
        decoderResynchronizationPending = false
    }

    @Synchronized
    fun onFrameRendered(nowNanos: Long): VideoRecoveryTransition {
        if (!streamAcknowledged || !surfaceAttached) return transition(VideoAvailability.Unavailable)
        if (decoderResynchronizationPending) return beginRecovery(nowNanos)
        val started = recoveryStartedAtNanos
        recoveryStartedAtNanos = null
        availability = VideoAvailability.Streaming
        return VideoRecoveryTransition(
            availability = availability,
            recoveryDurationNanos = started?.let { (nowNanos - it).coerceAtLeast(0L) },
        )
    }

    @Synchronized
    fun onFailed(): VideoRecoveryTransition {
        streamAcknowledged = false
        surfaceAttached = false
        decoderResynchronizationPending = false
        recoveryStartedAtNanos = null
        return transition(VideoAvailability.Error)
    }

    private fun beginRecovery(nowNanos: Long): VideoRecoveryTransition {
        val started = recoveryStartedAtNanos == null
        if (started) recoveryStartedAtNanos = nowNanos
        availability = VideoAvailability.Recovering
        return VideoRecoveryTransition(availability, recoveryStarted = started)
    }

    private fun transition(next: VideoAvailability): VideoRecoveryTransition {
        availability = next
        return VideoRecoveryTransition(next)
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
