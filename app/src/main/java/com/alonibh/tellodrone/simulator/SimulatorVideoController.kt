package com.alonibh.tellodrone.simulator

import com.alonibh.tellodrone.domain.NormalizedBoundingBox
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.VideoState
import com.alonibh.tellodrone.tello.TelloVideoController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Synthetic analysis metadata and boxes only; it creates no encoded or decoded video frames. */
class SimulatorVideoController(
    parentScope: CoroutineScope,
    private val plant: SimulatorPlant,
    private val sourceNowNanos: () -> Long = System::nanoTime,
    private val framePeriodMillis: Long = 100L,
) : TelloVideoController {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val mutableState = MutableStateFlow(VideoState())
    override val state: StateFlow<VideoState> = mutableState.asStateFlow()
    private var prepared = false
    private var acknowledged = false
    private var detectionEnabled = false
    private var closed = false
    private var frameSequence = 0L
    private var lastTimestampNanos = Long.MIN_VALUE
    private var frameJob: Job? = null

    override suspend fun prepare(): Result<Unit> {
        if (closed) return Result.failure(IllegalStateException("Simulator video is closed"))
        prepared = true
        return Result.success(Unit)
    }

    override fun streamAcknowledged() {
        if (!prepared || closed) {
            streamFailed("Simulator video was not prepared")
            return
        }
        acknowledged = true
        mutableState.value = mutableState.value.copy(
            availability = VideoAvailability.Streaming,
            measuredFps = 10f,
            analysisMeasuredFps = 10f,
            analysisFrameWidth = VIRTUAL_FRAME_WIDTH,
            analysisFrameHeight = VIRTUAL_FRAME_HEIGHT,
            errorReason = null,
        )
        startFrames()
    }

    override fun streamFailed(reason: String) {
        acknowledged = false
        detectionEnabled = false
        frameJob?.cancel()
        frameJob = null
        mutableState.value = clearedState(VideoAvailability.Error, PersonDetectionState.Off, reason)
    }

    override fun setPersonDetectionEnabled(enabled: Boolean): Result<Unit> {
        if (closed) return Result.failure(IllegalStateException("Simulator video is closed"))
        if (enabled && !acknowledged) {
            return Result.failure(IllegalStateException("Synthetic detection requires a streaming simulator preview"))
        }
        detectionEnabled = enabled
        mutableState.value = mutableState.value.copy(
            personDetectionState = if (enabled) PersonDetectionState.Starting else PersonDetectionState.Off,
            personDetections = emptyList(),
            detectorErrorReason = null,
        )
        return Result.success(Unit)
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        acknowledged = false
        detectionEnabled = false
        frameJob?.cancel()
        frameJob = null
        mutableState.value = clearedState(VideoAvailability.Unavailable, PersonDetectionState.Off, null)
        scope.cancel()
    }

    private fun startFrames() {
        if (frameJob?.isActive == true) return
        frameJob = scope.launch {
            while (isActive && !closed && acknowledged) {
                publishFrame()
                delay(framePeriodMillis)
            }
        }
    }

    private fun publishFrame() {
        frameSequence += 1L
        val now = sourceNowNanos()
        val timestamp = if (lastTimestampNanos == Long.MIN_VALUE) now else maxOf(now, lastTimestampNanos + 1L)
        lastTimestampNanos = timestamp
        val projection = plant.snapshot()
        val detection = if (detectionEnabled) projection.boundingBox?.let { box ->
            PersonDetection(
                boundingBox = NormalizedBoundingBox(box.left, box.top, box.right, box.bottom),
                confidence = SYNTHETIC_CONFIDENCE,
                frameSequence = frameSequence,
                sourceTimestampNanos = timestamp,
            )
        } else null
        mutableState.value = mutableState.value.copy(
            availability = VideoAvailability.Streaming,
            measuredFps = 10f,
            lastFrameAt = java.time.Instant.now(),
            analysisMeasuredFps = 10f,
            analysisLatestCaptureTimestampNanos = timestamp,
            analysisLatestSequence = frameSequence,
            analysisFrameWidth = VIRTUAL_FRAME_WIDTH,
            analysisFrameHeight = VIRTUAL_FRAME_HEIGHT,
            personDetectionState = if (detectionEnabled) PersonDetectionState.Detecting else PersonDetectionState.Off,
            detectorMeasuredFps = if (detectionEnabled) 10f else null,
            detectorInferenceMillis = if (detectionEnabled) 0L else null,
            detectorModelName = if (detectionEnabled) "Synthetic person oracle" else null,
            processedDetectorFrameSequence = if (detectionEnabled) frameSequence else null,
            processedDetectorSourceTimestampNanos = if (detectionEnabled) timestamp else null,
            personDetections = listOfNotNull(detection),
            detectorErrorReason = null,
        )
    }

    private fun clearedState(
        availability: VideoAvailability,
        detectionState: PersonDetectionState,
        reason: String?,
    ) = VideoState(
        availability = availability,
        personDetectionState = detectionState,
        personDetections = emptyList(),
        errorReason = reason,
    )

    companion object {
        const val VIRTUAL_FRAME_WIDTH = 960
        const val VIRTUAL_FRAME_HEIGHT = 720
        const val SYNTHETIC_CONFIDENCE = .95f
    }
}
