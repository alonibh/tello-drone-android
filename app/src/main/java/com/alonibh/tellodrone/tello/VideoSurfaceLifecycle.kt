package com.alonibh.tellodrone.tello

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
// SPDX-License-Identifier: AGPL-3.0-only
