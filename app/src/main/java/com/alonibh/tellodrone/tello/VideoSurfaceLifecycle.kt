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

    fun isCurrent(value: T, expectedGeneration: Long): Boolean =
        active.get() === value && generationCounter.get() == expectedGeneration
}

internal data class RenderAuthorizationDecision(
    val shouldRender: Boolean,
    val isStaleGeneration: Boolean,
    val reason: String,
)

internal object VideoRenderAuthorizer {
    fun authorizeRender(
        codecBoundGeneration: Long,
        currentSurfaceGeneration: Long,
        isSurfaceAttached: Boolean,
        isSurfaceValid: Boolean,
        isCodecConfigOrEos: Boolean,
    ): RenderAuthorizationDecision {
        if (isCodecConfigOrEos) {
            return RenderAuthorizationDecision(
                shouldRender = false,
                isStaleGeneration = false,
                reason = "Codec config or EOS buffer",
            )
        }
        if (!isSurfaceAttached) {
            return RenderAuthorizationDecision(
                shouldRender = false,
                isStaleGeneration = true,
                reason = "Surface detached (currentGen=$currentSurfaceGeneration, codecGen=$codecBoundGeneration)",
            )
        }
        if (codecBoundGeneration != currentSurfaceGeneration) {
            return RenderAuthorizationDecision(
                shouldRender = false,
                isStaleGeneration = true,
                reason = "Stale generation (codecGen=$codecBoundGeneration != currentGen=$currentSurfaceGeneration)",
            )
        }
        if (!isSurfaceValid) {
            return RenderAuthorizationDecision(
                shouldRender = false,
                isStaleGeneration = false,
                reason = "Surface is invalid",
            )
        }
        return RenderAuthorizationDecision(
            shouldRender = true,
            isStaleGeneration = false,
            reason = "Authorized for generation $codecBoundGeneration",
        )
    }
}

internal data class VideoRecoveryTransition(
    val availability: VideoAvailability,
    val recoveryStarted: Boolean = false,
    val recoveryDurationNanos: Long? = null,
)

/** Pure state machine: only a real rendered frame matching the current active generation can transition Recovering to Streaming. */
internal class VideoRecoveryStateMachine {
    private var streamAcknowledged = false
    private var surfaceAttached = false
    private var activeGeneration: Long? = null
    private var availability = VideoAvailability.Unavailable
    private var recoveryStartedAtNanos: Long? = null
    private var decoderResynchronizationPending = false

    val currentAvailability: VideoAvailability get() = synchronized(this) { availability }
    val currentGeneration: Long? get() = synchronized(this) { activeGeneration }

    @Synchronized
    fun onStreamAcknowledged(nowNanos: Long): VideoRecoveryTransition {
        streamAcknowledged = true
        return if (surfaceAttached) beginRecovery(nowNanos) else transition(VideoAvailability.Unavailable)
    }

    @Synchronized
    fun onSurfaceAttached(generation: Long, nowNanos: Long): VideoRecoveryTransition {
        surfaceAttached = true
        activeGeneration = generation
        return if (streamAcknowledged) beginRecovery(nowNanos) else transition(VideoAvailability.Unavailable)
    }

    @Synchronized
    fun onSurfaceAttached(nowNanos: Long): VideoRecoveryTransition {
        surfaceAttached = true
        return if (streamAcknowledged) beginRecovery(nowNanos) else transition(VideoAvailability.Unavailable)
    }

    @Synchronized
    fun onSurfaceDetached(generation: Long? = null): VideoRecoveryTransition {
        surfaceAttached = false
        activeGeneration = null
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
    fun onFrameRendered(frameGeneration: Long, nowNanos: Long): VideoRecoveryTransition {
        if (!streamAcknowledged || !surfaceAttached) return transition(VideoAvailability.Unavailable)
        if (activeGeneration == null || frameGeneration != activeGeneration) {
            return VideoRecoveryTransition(availability)
        }
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
    fun onFrameRendered(nowNanos: Long): VideoRecoveryTransition =
        activeGeneration?.let { onFrameRendered(it, nowNanos) } ?: transition(VideoAvailability.Unavailable)

    @Synchronized
    fun onFailed(): VideoRecoveryTransition {
        streamAcknowledged = false
        surfaceAttached = false
        activeGeneration = null
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
