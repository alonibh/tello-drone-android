package com.alonibh.tellodrone.vision

import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.tello.DecodedFrameConsumer
import com.alonibh.tellodrone.tello.DecodedVideoFrame
import java.util.concurrent.atomic.AtomicLong

data class PersonDetectionSnapshot(
    val state: PersonDetectionState = PersonDetectionState.Off,
    val detections: List<PersonDetection> = emptyList(),
    val measuredFps: Float? = null,
    val inferenceMillis: Long? = null,
    val errorReason: String? = null,
)

/** Pure lifecycle/staleness state used by the service-owned runtime and JVM tests. */
class PersonDetectionStore(
    private val staleAfterNanos: Long = STALE_AFTER_NANOS,
) {
    @Volatile var snapshot: PersonDetectionSnapshot = PersonDetectionSnapshot()
        private set

    @Synchronized
    fun start(): PersonDetectionSnapshot {
        snapshot = PersonDetectionSnapshot(state = PersonDetectionState.Starting)
        return snapshot
    }

    @Synchronized
    fun result(
        detections: List<PersonDetection>,
        measuredFps: Float?,
        inferenceMillis: Long,
    ): PersonDetectionSnapshot {
        snapshot = PersonDetectionSnapshot(
            state = PersonDetectionState.Detecting,
            detections = detections.toList(),
            measuredFps = measuredFps,
            inferenceMillis = inferenceMillis,
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
    fun fail(message: String): PersonDetectionSnapshot {
        snapshot = PersonDetectionSnapshot(
            state = PersonDetectionState.Error,
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
    private val detectorFactory: () -> PersonDetector,
    private val clockNanos: () -> Long = System::nanoTime,
    private val onSnapshot: (PersonDetectionSnapshot) -> Unit,
) : DecodedFrameConsumer, AutoCloseable {
    private val detectorLock = Any()
    private val stateLock = Any()
    private val generation = AtomicLong()
    private val store = PersonDetectionStore()
    private val frameRate = DetectionFrameRate()
    @Volatile private var enabled = false
    private var detector: PersonDetector? = null

    fun start() {
        synchronized(stateLock) {
            generation.incrementAndGet()
            enabled = true
            frameRate.reset()
            onSnapshot(store.start())
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
        if (!enabled) return
        val activeGeneration = generation.get()
        try {
            val startedAt = clockNanos()
            val detections = synchronized(detectorLock) {
                if (!enabled || generation.get() != activeGeneration) return
                (detector ?: detectorFactory().also { detector = it }).detect(frame)
            }
            val finishedAt = clockNanos()
            val measuredFps = frameRate.onResult(finishedAt)
            synchronized(stateLock) {
                if (!enabled || generation.get() != activeGeneration) return
                onSnapshot(
                    store.result(
                        detections = detections,
                        measuredFps = measuredFps,
                        inferenceMillis = ((finishedAt - startedAt).coerceAtLeast(0L) / 1_000_000L),
                    ),
                )
            }
        } catch (error: Throwable) {
            synchronized(stateLock) {
                if (!enabled || generation.get() != activeGeneration) return
                enabled = false
                generation.incrementAndGet()
                synchronized(detectorLock) {
                    runCatching { detector?.close() }
                    detector = null
                }
                onSnapshot(store.fail("Person detector failed: ${error.message ?: error.javaClass.simpleName}"))
            }
        }
    }

    fun expire(nowNanos: Long = clockNanos()) = synchronized(stateLock) {
        onSnapshot(store.expire(nowNanos))
    }

    override fun close() {
        stop()
        synchronized(detectorLock) {
            runCatching { detector?.close() }
            detector = null
        }
    }

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
