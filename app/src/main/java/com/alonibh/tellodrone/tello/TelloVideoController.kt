package com.alonibh.tellodrone.tello

import com.alonibh.tellodrone.domain.VideoState
import kotlinx.coroutines.flow.StateFlow

/** Session-facing video lifecycle without Android display details, allowing JVM lifecycle tests. */
interface TelloVideoController {
    val state: StateFlow<VideoState>
    suspend fun prepare(): Result<Unit>
    fun streamAcknowledged()
    fun streamFailed(reason: String)
    /** Enables observational person detection only; implementations must never claim RC authority. */
    fun setPersonDetectionEnabled(enabled: Boolean): Result<Unit> =
        if (enabled) Result.failure(UnsupportedOperationException("Person detection is unavailable"))
        else Result.success(Unit)
    suspend fun close()
}
