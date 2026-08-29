package com.alonibh.tellodrone.vision

import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.domain.DetectorBackend
import com.alonibh.tellodrone.domain.DetectorBackendPreference
import com.alonibh.tellodrone.tello.DecodedFrameConsumer
import com.alonibh.tellodrone.tello.DecodedVideoFrame
import java.util.concurrent.atomic.AtomicLong

import com.alonibh.tellodrone.domain.DetectorModel

data class PersonDetectionSnapshot(
    val state: PersonDetectionState = PersonDetectionState.Off,
    val candidates: List<PersonDetection> = emptyList(),
    val detections: List<PersonDetection> = emptyList(),
    val measuredFps: Float? = null,
    val inferenceMillis: Long? = null,
    val initializationMillis: Long? = null,
    val inferenceP50Millis: Float? = null,
    val inferenceP95Millis: Float? = null,
    val analyzedFrames: Long = 0,
    val modelName: String? = null,
    val backend: DetectorBackend? = null,
    val fellBackFromGpu: Boolean = false,
    val errorReason: String? = null,
    val confidenceThreshold: Float? = null,
    /** Present only for a completed detector inference; it is retained when that result is empty. */
    val processedFrameSequence: Long? = null,
    val processedSourceTimestampNanos: Long? = null,
    val renderedFrameTimestampNanos: Long? = null,
    val captureRequestTimestampNanos: Long? = null,
    val pixelCopyCompletedTimestampNanos: Long? = null,
    val detectorInferenceStartedTimestampNanos: Long? = null,
    val detectorInferenceCompletedTimestampNanos: Long? = null,
    val detectorStageTiming: PersonDetectorStageTiming? = null,
)

data class DetectorInferenceMeasurement(
    val frameSequence: Long,
    val sourceTimestampNanos: Long,
    val renderedFrameTimestampNanos: Long,
    val captureRequestTimestampNanos: Long,
    val pixelCopyCompletedTimestampNanos: Long,
    val inferenceStartedAtNanos: Long,
    val completedAtNanos: Long,
    val inferenceNanos: Long,
    /** Creation time only for the first inference after a detector start/recreate. */
    val startupNanos: Long?,
    val descriptor: PersonDetectorDescriptor,
    /** Running detector instrumentation, emitted with every completed inference. */
    val initializationMillis: Long?,
    val inferenceP50Millis: Float?,
    val inferenceP95Millis: Float?,
    val analyzedFrames: Long,
    val measuredFps: Float?,
    val stageTiming: PersonDetectorStageTiming?,
)

/** Pure lifecycle/staleness state used by the service-owned runtime and JVM tests. */
class PersonDetectionStore(
    private val staleAfterNanos: Long = STALE_AFTER_NANOS,
) {
    @Volatile var snapshot: PersonDetectionSnapshot = PersonDetectionSnapshot()
        private set

    @Synchronized
    fun start(modelName: String): PersonDetectionSnapshot {
        snapshot = PersonDetectionSnapshot(
            state = PersonDetectionState.Starting,
            modelName = modelName,
        )
        return snapshot
    }

    @Synchronized
    fun result(
        candidates: List<PersonDetection>,
        detections: List<PersonDetection>,
        processedFrameSequence: Long,
        processedSourceTimestampNanos: Long,
        measuredFps: Float?,
        inferenceMillis: Long,
        descriptor: PersonDetectorDescriptor,
        confidenceThreshold: Float,
        initializationMillis: Long? = null,
        inferenceP50Millis: Float? = null,
        inferenceP95Millis: Float? = null,
        analyzedFrames: Long = 0,
        frameMetadata: com.alonibh.tellodrone.tello.AnalysisFrameMetadata? = null,
        inferenceStartedAtNanos: Long? = null,
        inferenceCompletedAtNanos: Long? = null,
        detectorStageTiming: PersonDetectorStageTiming? = null,
    ): PersonDetectionSnapshot {
        snapshot = PersonDetectionSnapshot(
            state = PersonDetectionState.Detecting,
            candidates = candidates.toList(),
            detections = detections.toList(),
            measuredFps = measuredFps,
            inferenceMillis = inferenceMillis,
            initializationMillis = initializationMillis,
            inferenceP50Millis = inferenceP50Millis,
            inferenceP95Millis = inferenceP95Millis,
            analyzedFrames = analyzedFrames,
            modelName = descriptor.modelName,
            backend = descriptor.backend,
            fellBackFromGpu = descriptor.fellBackFromGpu,
            confidenceThreshold = confidenceThreshold,
            processedFrameSequence = processedFrameSequence,
            processedSourceTimestampNanos = processedSourceTimestampNanos,
            renderedFrameTimestampNanos = frameMetadata?.renderedFrameTimestampNanos,
            captureRequestTimestampNanos = frameMetadata?.captureRequestTimestampNanos,
            pixelCopyCompletedTimestampNanos = frameMetadata?.pixelCopyCompletedTimestampNanos,
            detectorInferenceStartedTimestampNanos = inferenceStartedAtNanos,
            detectorInferenceCompletedTimestampNanos = inferenceCompletedAtNanos,
            detectorStageTiming = detectorStageTiming,
        )
        return snapshot
    }

    @Synchronized
    fun expire(nowNanos: Long): PersonDetectionSnapshot {
        val newest = snapshot.detections.maxOfOrNull { it.sourceTimestampNanos } ?: return snapshot
        if (nowNanos - newest >= staleAfterNanos) snapshot = snapshot.copy(detections = emptyList())
        return snapshot
    }

    @Synchronized
    fun stop(): PersonDetectionSnapshot {
        snapshot = PersonDetectionSnapshot()
        return snapshot
    }

    @Synchronized
    fun fail(
        message: String,
        descriptor: PersonDetectorDescriptor?,
        defaultModelName: String,
    ): PersonDetectionSnapshot {
        snapshot = PersonDetectionSnapshot(
            state = PersonDetectionState.Error,
            modelName = descriptor?.modelName ?: defaultModelName,
            backend = descriptor?.backend,
            fellBackFromGpu = descriptor?.fellBackFromGpu == true,
            errorReason = message.take(MAX_ERROR_CHARS),
        )
        return snapshot
    }

    companion object {
        const val STALE_AFTER_NANOS = 500_000_000L
        private const val MAX_ERROR_CHARS = 120
    }
}

/**
 * Runs detector creation and synchronous inference in the DecodedFrameConsumer callback. There is
 * no additional frame queue; generation checks discard an in-flight result after stop/surface loss.
 */
class PersonDetectionPipeline(
    private val detectorFactory: (DetectorModel, DetectorBackendPreference) -> PersonDetector,
    private val defaultModel: DetectorModel = DetectorModel.Default,
    private val clockNanos: () -> Long = System::nanoTime,
    private val onSnapshot: (PersonDetectionSnapshot) -> Unit,
    private val onInferenceMeasurement: (DetectorInferenceMeasurement) -> Unit = {},
    private val onAnalyzedFrame: (PersonDetectorFrame) -> Unit = {},
) : DecodedFrameConsumer, AutoCloseable {
    constructor(
        detectorFactory: (DetectorBackendPreference) -> PersonDetector,
        modelName: String = DetectorModel.Default.displayName,
        clockNanos: () -> Long = System::nanoTime,
        onSnapshot: (PersonDetectionSnapshot) -> Unit,
        onInferenceMeasurement: (DetectorInferenceMeasurement) -> Unit = {},
    ) : this(
        detectorFactory = { _, pref -> detectorFactory(pref) },
        defaultModel = DetectorModel.Default,
        clockNanos = clockNanos,
        onSnapshot = onSnapshot,
        onInferenceMeasurement = onInferenceMeasurement,
    )

    private val detectorLock = Any()
    private val stateLock = Any()
    private val generation = AtomicLong()
    private val store = PersonDetectionStore()
    private val frameRate = DetectionFrameRate()
    private val timing = DetectionTiming()
    @Volatile private var enabled = false
    @Volatile private var model = defaultModel
    @Volatile private var preference = DetectorBackendPreference.Cpu
    @Volatile private var confidenceThreshold = DEFAULT_PERSON_CONFIDENCE_THRESHOLD
    private var detector: PersonDetector? = null
    private var detectorModel: DetectorModel? = null
    private var detectorPreference: DetectorBackendPreference? = null

    fun start(
        model: DetectorModel = this.model,
        preference: DetectorBackendPreference = this.preference,
        confidenceThreshold: Float = this.confidenceThreshold,
    ) {
        synchronized(stateLock) {
            this.model = model
            this.preference = preference
            this.confidenceThreshold = normalizeConfidenceThreshold(confidenceThreshold)
            generation.incrementAndGet()
            enabled = true
            frameRate.reset()
            timing.reset()
            onSnapshot(store.start(model.displayName))
        }
    }

    fun start(
        preference: DetectorBackendPreference,
        confidenceThreshold: Float = this.confidenceThreshold,
    ) = start(this.model, preference, confidenceThreshold)

    fun setDetectorModel(model: DetectorModel) {
        synchronized(stateLock) {
            this.model = model
        }
    }

    fun setConfidenceThreshold(threshold: Float) {
        synchronized(stateLock) {
            this.confidenceThreshold = normalizeConfidenceThreshold(threshold)
        }
    }

    fun stop() {
        synchronized(stateLock) {
            enabled = false
            generation.incrementAndGet()
            frameRate.reset()
            timing.reset()
            onSnapshot(store.stop())
        }
    }

    override fun onFrame(frame: DecodedVideoFrame) {
        process(PersonDetectorFrame(frame.metadata) { frame.bitmap })
    }

    fun process(frame: PersonDetectorFrame) {
        val request = activeRequestSnapshot() ?: return
        try {
            var creationNanos: Long? = null
            val completed = synchronized(detectorLock) {
                if (!isRequestCurrent(request)) return
                if (detectorModel != request.model || detectorPreference != request.preference) {
                    runCatching { detector?.close() }
                    detector = null
                    detectorModel = null
                    detectorPreference = null
                }
                val activeDetector = detector ?: run {
                    val creationStartedAt = clockNanos()
                    val createdDetector = detectorFactory(request.model, request.preference)
                    creationNanos = (clockNanos() - creationStartedAt).coerceAtLeast(0L)
                    if (!isRequestCurrent(request)) {
                        runCatching { createdDetector.close() }
                        return
                    }
                    detector = createdDetector
                    detectorModel = request.model
                    detectorPreference = request.preference
                    createdDetector
                }
                if (!isRequestCurrent(request)) return
                onAnalyzedFrame(frame)
                val inferenceStartedAt = clockNanos()
                val detected = activeDetector.detectDetailed(frame)
                val inferenceCompletedAt = clockNanos()
                CompletedDetection(
                    output = detected,
                    descriptor = activeDetector.descriptor,
                    inferenceStartedAtNanos = inferenceStartedAt,
                    inferenceCompletedAtNanos = inferenceCompletedAt,
                    totalInferenceNanos = (inferenceCompletedAt - inferenceStartedAt).coerceAtLeast(0L),
                )
            }
            val detections = completed.output.candidates
            val descriptor = completed.descriptor
            val inferenceNanos = completed.totalInferenceNanos
            val filteredDetections = detections.filter { it.confidence >= request.confidenceThreshold }
            val finishedAt = clockNanos()
            val measuredFps = frameRate.onResult(finishedAt)
            val timingSnapshot = timing.onInference(inferenceNanos, creationNanos)
            synchronized(stateLock) {
                if (!isRequestCurrentLocked(request)) return
                onSnapshot(
                    store.result(
                        candidates = detections,
                        detections = filteredDetections,
                        processedFrameSequence = frame.metadata.sequence,
                        processedSourceTimestampNanos = frame.metadata.captureTimestampNanos,
                        measuredFps = measuredFps,
                        inferenceMillis = inferenceNanos / 1_000_000L,
                        descriptor = descriptor,
                        confidenceThreshold = request.confidenceThreshold,
                        initializationMillis = timingSnapshot.initializationMillis,
                        inferenceP50Millis = timingSnapshot.p50Millis,
                        inferenceP95Millis = timingSnapshot.p95Millis,
                        analyzedFrames = timingSnapshot.frames,
                        frameMetadata = frame.metadata,
                        inferenceStartedAtNanos = completed.inferenceStartedAtNanos,
                        inferenceCompletedAtNanos = completed.inferenceCompletedAtNanos,
                        detectorStageTiming = completed.output.stageTiming,
                    ),
                )
                onInferenceMeasurement(
                    DetectorInferenceMeasurement(
                        frameSequence = frame.metadata.sequence,
                        sourceTimestampNanos = frame.metadata.captureTimestampNanos,
                        renderedFrameTimestampNanos = frame.metadata.renderedFrameTimestampNanos,
                        captureRequestTimestampNanos = frame.metadata.captureRequestTimestampNanos,
                        pixelCopyCompletedTimestampNanos = frame.metadata.pixelCopyCompletedTimestampNanos,
                        inferenceStartedAtNanos = completed.inferenceStartedAtNanos,
                        completedAtNanos = completed.inferenceCompletedAtNanos,
                        inferenceNanos = inferenceNanos,
                        startupNanos = creationNanos,
                        descriptor = descriptor,
                        initializationMillis = timingSnapshot.initializationMillis,
                        inferenceP50Millis = timingSnapshot.p50Millis,
                        inferenceP95Millis = timingSnapshot.p95Millis,
                        analyzedFrames = timingSnapshot.frames,
                        measuredFps = measuredFps,
                        stageTiming = completed.output.stageTiming,
                    ),
                )
            }
        } catch (error: Throwable) {
            synchronized(stateLock) {
                if (!isRequestCurrentLocked(request)) return
                enabled = false
                generation.incrementAndGet()
                synchronized(detectorLock) {
                    val descriptor = detector?.descriptor
                    runCatching { detector?.close() }
                    detector = null
                    detectorModel = null
                    detectorPreference = null
                    onSnapshot(
                        store.fail(
                            "Person detector failed: ${error.message ?: error.javaClass.simpleName}",
                            descriptor,
                            request.model.displayName,
                        ),
                    )
                }
            }
        }
    }

    fun expire(nowNanos: Long = clockNanos()) = synchronized(stateLock) {
        onSnapshot(store.expire(nowNanos))
    }

    /** Must run on the same analysis-consumer thread used for creation/inference. */
    fun releaseIfStopped() {
        if (enabled) return
        synchronized(detectorLock) {
            if (enabled) return
            runCatching { detector?.close() }
            detector = null
            detectorModel = null
            detectorPreference = null
        }
    }

    override fun close() {
        stop()
        synchronized(detectorLock) {
            runCatching { detector?.close() }
            detector = null
            detectorModel = null
            detectorPreference = null
        }
    }

    private fun activeRequestSnapshot(): DetectorRequest? = synchronized(stateLock) {
        if (!enabled) null else DetectorRequest(generation.get(), model, preference, confidenceThreshold)
    }

    private fun isRequestCurrent(request: DetectorRequest): Boolean = synchronized(stateLock) {
        isRequestCurrentLocked(request)
    }

    private fun isRequestCurrentLocked(request: DetectorRequest): Boolean =
        enabled && generation.get() == request.generation && model == request.model && preference == request.preference && confidenceThreshold == request.confidenceThreshold

    private data class DetectorRequest(
        val generation: Long,
        val model: DetectorModel,
        val preference: DetectorBackendPreference,
        val confidenceThreshold: Float,
    )

    private data class CompletedDetection(
        val output: PersonDetectorOutput,
        val descriptor: PersonDetectorDescriptor,
        val inferenceStartedAtNanos: Long,
        val inferenceCompletedAtNanos: Long,
        val totalInferenceNanos: Long,
    )

    private class DetectionFrameRate {
        private var windowStartNanos = 0L
        private var frames = 0
        private var measured: Float? = null

        @Synchronized fun onResult(nowNanos: Long): Float? {
            if (windowStartNanos == 0L) windowStartNanos = nowNanos
            frames++
            val elapsed = nowNanos - windowStartNanos
            if (elapsed >= 1_000_000_000L) {
                measured = frames * 1_000_000_000f / elapsed
                windowStartNanos = nowNanos
                frames = 0
            }
            return measured
        }

        @Synchronized fun reset() {
            windowStartNanos = 0L
            frames = 0
            measured = null
        }
    }

    private class DetectionTiming {
        private val samples = ArrayDeque<Long>()
        private var initializationMillis: Long? = null
        private var frames = 0L

        @Synchronized fun onInference(inferenceNanos: Long, startupNanos: Long?): Snapshot {
            if (startupNanos != null) initializationMillis = startupNanos / 1_000_000L
            samples += inferenceNanos
            if (samples.size > MAX_SAMPLES) samples.removeFirst()
            frames++
            val ordered = samples.sorted()
            fun percentile(fraction: Float): Float? {
                if (ordered.isEmpty()) return null
                val index = ((ordered.lastIndex) * fraction).toInt().coerceIn(0, ordered.lastIndex)
                return ordered[index] / 1_000_000f
            }
            return Snapshot(initializationMillis, percentile(.50f), percentile(.95f), frames)
        }

        @Synchronized fun reset() {
            samples.clear()
            initializationMillis = null
            frames = 0
        }

        data class Snapshot(
            val initializationMillis: Long?,
            val p50Millis: Float?,
            val p95Millis: Float?,
            val frames: Long,
        )

        companion object { const val MAX_SAMPLES = 300 }
    }
}

internal fun PersonDetectionPipeline.startProductionDetection() = start(
    model = ProductionPersonDetectorConfiguration.model,
    preference = ProductionPersonDetectorConfiguration.backendPreference,
    confidenceThreshold = ProductionPersonDetectorConfiguration.confidenceThreshold,
)
// SPDX-License-Identifier: AGPL-3.0-only
