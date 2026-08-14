package com.alonibh.tellodrone.tello

import com.alonibh.tellodrone.domain.DetectorBackendPreference
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
    fun setPersonDetectorBackendPreference(preference: DetectorBackendPreference): Result<Unit> =
        Result.failure(UnsupportedOperationException("Detector backend selection is unavailable"))
    fun setPersonDetectorConfidenceThreshold(threshold: Float): Result<Unit> =
        Result.failure(UnsupportedOperationException("Detector confidence threshold selection is unavailable"))
    fun runDetectorBenchmark(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Detector benchmark is unavailable"))
    fun cancelDetectorBenchmark(): Boolean = false
    suspend fun close()
}
