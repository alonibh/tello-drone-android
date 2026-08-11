package com.alonibh.tellodrone.vision

import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.domain.DetectorBackend
import com.alonibh.tellodrone.domain.DetectorBackendPreference
import com.alonibh.tellodrone.tello.DecodedFrameConsumer
import com.alonibh.tellodrone.tello.DecodedVideoFrame
import java.util.concurrent.atomic.AtomicLong

data class PersonDetectionSnapshot(
    val state: PersonDetectionState = PersonDetectionState.Off,
    val detections: List<PersonDetection> = emptyList(),
    val measuredFps: Float? = null,
    val inferenceMillis: Long? = null,
    val modelName: String? = null,
    val backend: DetectorBackend? = null,
    val fellBackFromGpu: Boolean = false,
    val errorReason: String? = null,
)

data class DetectorInferenceMeasurement(
    val completedAtNanos: Long,
    val inferenceNanos: Long,
    /** Creation time only for the first inference after a detector start/recreate. */
    val startupNanos: Long?,
    val descriptor: PersonDetectorDescriptor,
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
        detections: List<PersonDetection>,
        measuredFps: Float?,
        inferenceMillis: Long,
        descriptor: PersonDetectorDescriptor,
    ): PersonDetectionSnapshot {
        snapshot = PersonDetectionSnapshot(
            state = PersonDetectionState.Detecting,
            detections = detections.toList(),
            measuredFps = measuredFps,
            inferenceMillis = inferenceMillis,
            modelName = descriptor.modelName,
            backend = descriptor.backend,
            fellBackFromGpu = descriptor.fellBackFromGpu,
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
    private val detectorFactory: (DetectorBackendPreference) -> PersonDetector,
    private val modelName: String,
    private val clockNanos: () -> Long = System::nanoTime,
    private val onSnapshot: (PersonDetectionSnapshot) -> Unit,
    private val onInferenceMeasurement: (DetectorInferenceMeasurement) -> Unit = {},
) : DecodedFrameConsumer, AutoCloseable {
    private val detectorLock = Any()
    private val stateLock = Any()
    private val generation = AtomicLong()
    private val store = PersonDetectionStore()
    private val frameRate = DetectionFrameRate()
    @Volatile private var enabled = false
    @Volatile private var preference = DetectorBackendPreference.Accelerated
    private var detector: PersonDetector? = null
    private var detectorPreference: DetectorBackendPreference? = null

    fun start(preference: DetectorBackendPreference = DetectorBackendPreference.Accelerated) {
        synchronized(stateLock) {
            this.preference = preference
            generation.incrementAndGet()
            enabled = true
            frameRate.reset()
            onSnapshot(store.start(modelName))
        }
    }

    fun stop() {
        synchronized(stateLock) {
            enabled = false
            generation.incrementAndGet()
            frameRate.reset()
            onSnapshot(store.stop())
        }
    }

    override fun onFrame(frame: DecodedVideoFrame) {
        process(PersonDetectorFrame(frame.metadata) { frame.bitmap })
    }

    fun process(frame: PersonDetectorFrame) {
        val request = activeRequestSnapshot() ?: return
        try {
            val startedAt = clockNanos()
            var creationNanos: Long? = null
            val (detections, descriptor) = synchronized(detectorLock) {
                if (!isRequestCurrent(request)) return
                if (detectorPreference != request.preference) {
                    runCatching { detector?.close() }
                    detector = null
                    detectorPreference = null
                }
                val activeDetector = detector ?: run {
                    val creationStartedAt = clockNanos()
                    val createdDetector = detectorFactory(request.preference)
                    creationNanos = (clockNanos() - creationStartedAt).coerceAtLeast(0L)
                    if (!isRequestCurrent(request)) {
                        runCatching { createdDetector.close() }
                        return
                    }
                    detector = createdDetector
                    detectorPreference = request.preference
                    createdDetector
                }
                if (!isRequestCurrent(request)) return
                activeDetector.detect(frame) to activeDetector.descriptor
            }
            val finishedAt = clockNanos()
            val measuredFps = frameRate.onResult(finishedAt)
            synchronized(stateLock) {
                if (!isRequestCurrentLocked(request)) return
                onSnapshot(
                    store.result(
                        detections = detections,
                        measuredFps = measuredFps,
                        inferenceMillis = ((finishedAt - startedAt).coerceAtLeast(0L) / 1_000_000L),
                        descriptor = descriptor,
                    ),
                )
                onInferenceMeasurement(
                    DetectorInferenceMeasurement(
                        completedAtNanos = finishedAt,
                        inferenceNanos = (finishedAt - startedAt).coerceAtLeast(0L),
                        startupNanos = creationNanos,
                        descriptor = descriptor,
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
                    detectorPreference = null
                    onSnapshot(
                        store.fail(
                            "Person detector failed: ${error.message ?: error.javaClass.simpleName}",
                            descriptor,
                            modelName,
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
            detectorPreference = null
        }
    }

    override fun close() {
        stop()
        synchronized(detectorLock) {
            runCatching { detector?.close() }
            detector = null
            detectorPreference = null
        }
    }

    private fun activeRequestSnapshot(): DetectorRequest? = synchronized(stateLock) {
        if (!enabled) null else DetectorRequest(generation.get(), preference)
    }

    private fun isRequestCurrent(request: DetectorRequest): Boolean = synchronized(stateLock) {
        isRequestCurrentLocked(request)
    }

    private fun isRequestCurrentLocked(request: DetectorRequest): Boolean =
        enabled && generation.get() == request.generation && preference == request.preference

    private data class DetectorRequest(
        val generation: Long,
        val preference: DetectorBackendPreference,
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
}
