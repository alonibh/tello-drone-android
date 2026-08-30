package com.alonibh.tellodrone.tello

import com.alonibh.tellodrone.domain.VideoAvailability
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Result of an atomic render lease request. */
internal sealed interface RenderLeaseResult<out R> {
    data class Granted<R>(val value: R) : RenderLeaseResult<R>
    data class Denied(val isStaleGeneration: Boolean, val reason: String) : RenderLeaseResult<Nothing>
}

/**
 * Identity-based, synchronized ownership for the one UI surface currently allowed to receive decoded video.
 *
 * Synchronization invariant:
 * The internal lock protects active Surface identity, generation counter, and the minimal critical
 * section executing physical MediaCodec releaseOutputBuffer(..., render=true).
 * No outside locks (recoveryState, StateFlow, PixelCopy, UDP) are ever acquired while holding this lock.
 */
internal class VideoSurfaceLifecycle<T : Any> {
    private val lock = Any()
    private var active: T? = null
    private var generationCounter = 0L

    val current: T? get() = synchronized(lock) { active }
    val generation: Long get() = synchronized(lock) { generationCounter }

    fun attach(value: T): Boolean = synchronized(lock) {
        if (active === value) return false
        active = value
        generationCounter++
        true
    }

    fun detach(value: T): Boolean = synchronized(lock) {
        if (active !== value) return false
        active = null
        generationCounter++
        true
    }

    fun isCurrent(value: T, expectedGeneration: Long): Boolean = synchronized(lock) {
        active === value && generationCounter == expectedGeneration
    }

    /**
     * Atomically validates surface ownership and generation, and if valid, executes [action]
     * under the lifecycle lock so that a concurrent detach cannot invalidate or disconnect
     * the native surface before [action] completes.
     */
    fun <R> withRenderLease(
        expectedSurface: T? = null,
        expectedGeneration: Long,
        predicate: (T) -> Boolean = { true },
        action: (T) -> R,
    ): RenderLeaseResult<R> = synchronized(lock) {
        val currentSurface = active
        val currentGen = generationCounter
        if (currentSurface == null) {
            return RenderLeaseResult.Denied(
                isStaleGeneration = true,
                reason = "Surface detached (currentGen=$currentGen, expectedGen=$expectedGeneration)",
            )
        }
        if (expectedSurface != null && currentSurface !== expectedSurface) {
            return RenderLeaseResult.Denied(
                isStaleGeneration = true,
                reason = "Surface instance mismatch (current=$currentSurface != expected=$expectedSurface)",
            )
        }
        if (currentGen != expectedGeneration) {
            return RenderLeaseResult.Denied(
                isStaleGeneration = true,
                reason = "Stale generation (currentGen=$currentGen != expectedGen=$expectedGeneration)",
            )
        }
        if (!predicate(currentSurface)) {
            return RenderLeaseResult.Denied(
                isStaleGeneration = false,
                reason = "Surface is invalid",
            )
        }
        val result = action(currentSurface)
        RenderLeaseResult.Granted(result)
    }
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
