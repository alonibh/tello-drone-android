package com.alonibh.tellodrone.tello

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.Surface
import androidx.core.graphics.createBitmap
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lossy analysis tap over the existing decoded preview Surface. PixelCopy, consumer work, video
 * decode, UDP receive, RC, command, and telemetry all run on separate threads.
 */
class PixelCopyDecodedFrameSource(
    private val onDiagnostics: (AnalysisFrameDiagnostics) -> Unit,
) : DecodedFrameSource {
    private val lock = Any()
    private val captureThread = HandlerThread("tello-analysis-capture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private val consumerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tello-analysis-consumer").apply { isDaemon = true }
    }
    private val bitmapPool = BitmapPool(ANALYSIS_WIDTH, ANALYSIS_HEIGHT, BITMAP_POOL_CAPACITY)
    private val latestFrame = LatestAnalysisFrameBuffer<PooledDecodedVideoFrame>()
    private val consumer = AtomicReference<DecodedFrameConsumer?>()
    private val consumerDrainScheduled = AtomicBoolean()
    private val sequence = AtomicLong()
    private val frameRate = SuccessfulFrameRate()
    private val closeComplete = CompletableDeferred<Unit>()

    private var surface: Surface? = null
    private var surfaceGeneration = 0L
    private var lastCaptureRequestNanos = Long.MIN_VALUE
    private var copyInFlight = false
    private var closed = false
    private var cleanupStarted = false

    override fun start(surface: Surface) {
        synchronized(lock) {
            if (closed) return
            this.surface = surface
            surfaceGeneration++
            lastCaptureRequestNanos = Long.MIN_VALUE
            frameRate.reset()
        }
        latestFrame.reset()
        onDiagnostics(AnalysisFrameDiagnostics())
    }

    override fun stop(surface: Surface) {
        synchronized(lock) {
            if (this.surface !== surface) return
            this.surface = null
            surfaceGeneration++
            lastCaptureRequestNanos = Long.MIN_VALUE
            frameRate.reset()
        }
        latestFrame.reset()
        onDiagnostics(AnalysisFrameDiagnostics())
    }

    override fun onFrameRendered(captureTimestampNanos: Long) {
        val generation = synchronized(lock) {
            if (closed || copyInFlight || surface?.isValid != true) return
            if (lastCaptureRequestNanos != Long.MIN_VALUE &&
                captureTimestampNanos - lastCaptureRequestNanos < CAPTURE_INTERVAL_NANOS
            ) return
            lastCaptureRequestNanos = captureTimestampNanos
            copyInFlight = true
            surfaceGeneration
        }
        if (!captureHandler.post { requestCopy(generation, captureTimestampNanos) }) {
            finishCopyAttempt()
        }
    }

    override fun setConsumer(consumer: DecodedFrameConsumer?) {
        this.consumer.set(consumer)
        if (consumer != null) scheduleConsumerDrain()
    }

    override suspend fun close() {
        val startCleanup = synchronized(lock) {
            if (!closed) {
                closed = true
                surface = null
                surfaceGeneration++
            }
            !copyInFlight
        }
        consumer.set(null)
        latestFrame.close()
        bitmapPool.close()
        consumerExecutor.shutdownNow()
        if (startCleanup) beginThreadCleanup()
        // PixelCopy normally completes in a few milliseconds. Never delay session cleanup
        // indefinitely if a platform implementation fails to deliver its callback.
        withTimeoutOrNull(CLOSE_WAIT_MILLIS) { closeComplete.await() }
    }

    private fun requestCopy(generation: Long, captureTimestampNanos: Long) {
        val targetSurface = synchronized(lock) {
            surface?.takeIf { !closed && generation == surfaceGeneration && it.isValid }
        }
        if (targetSurface == null) {
            finishCopyAttempt()
            return
        }
        val bitmap = bitmapPool.acquire()
        if (bitmap == null) {
            finishCopyAttempt()
            return
        }
        try {
            PixelCopy.request(targetSurface, bitmap, { result ->
                if (result == PixelCopy.SUCCESS && isCurrent(generation)) {
                    val nowNanos = System.nanoTime()
                    val frameSequence = sequence.incrementAndGet()
                    val metadata = AnalysisFrameMetadata(
                        width = bitmap.width,
                        height = bitmap.height,
                        captureTimestampNanos = captureTimestampNanos,
                        pixelRepresentation = AnalysisPixelRepresentation.ARGB_8888_BITMAP,
                        sequence = frameSequence,
                    )
                    val frame = PooledDecodedVideoFrame(metadata, bitmap, bitmapPool::release)
                    val accepted = latestFrame.offer(frame)
                    if (accepted) {
                        val measuredFps = synchronized(lock) { frameRate.onFrame(nowNanos) }
                        onDiagnostics(
                            AnalysisFrameDiagnostics(
                                measuredFps = measuredFps,
                                latestCaptureTimestampNanos = captureTimestampNanos,
                                width = bitmap.width,
                                height = bitmap.height,
                                latestSequence = frameSequence,
                            ),
                        )
                        scheduleConsumerDrain()
                    }
                } else {
                    bitmapPool.release(bitmap)
                }
                finishCopyAttempt()
            }, captureHandler)
        } catch (_: Throwable) {
            bitmapPool.release(bitmap)
            finishCopyAttempt()
        }
    }

    private fun isCurrent(generation: Long): Boolean = synchronized(lock) {
        !closed && generation == surfaceGeneration && surface?.isValid == true
    }

    private fun finishCopyAttempt() {
        val cleanUp = synchronized(lock) {
            copyInFlight = false
            closed
        }
        if (cleanUp) beginThreadCleanup()
    }

    private fun scheduleConsumerDrain() {
        if (consumer.get() == null || latestFrame.pendingCount == 0) return
        if (!consumerDrainScheduled.compareAndSet(false, true)) return
        runCatching {
            consumerExecutor.execute {
                try {
                    while (true) {
                        val activeConsumer = consumer.get() ?: break
                        val frame = latestFrame.takeLatest() ?: break
                        try {
                            activeConsumer.onFrame(frame)
                        } finally {
                            frame.close()
                        }
                    }
                } finally {
                    consumerDrainScheduled.set(false)
                    if (consumer.get() != null && latestFrame.pendingCount != 0) scheduleConsumerDrain()
                }
            }
        }.onFailure { consumerDrainScheduled.set(false) }
    }

    private fun beginThreadCleanup() {
        val shouldStart = synchronized(lock) {
            if (cleanupStarted) false else {
                cleanupStarted = true
                true
            }
        }
        if (!shouldStart) return
        captureThread.quitSafely()
        closeComplete.complete(Unit)
    }

    private class PooledDecodedVideoFrame(
        override val metadata: AnalysisFrameMetadata,
        override val bitmap: Bitmap,
        private val releaseBitmap: (Bitmap) -> Unit,
    ) : DecodedVideoFrame {
        private val released = AtomicBoolean()
        override fun close() {
            if (released.compareAndSet(false, true)) releaseBitmap(bitmap)
        }
    }

    private class BitmapPool(
        private val width: Int,
        private val height: Int,
        private val capacity: Int,
    ) {
        private val lock = Any()
        private val available = ArrayDeque<Bitmap>()
        private var allocated = 0
        private var closed = false

        fun acquire(): Bitmap? = synchronized(lock) {
            if (closed) return@synchronized null
            available.pollFirst() ?: if (allocated < capacity) {
                allocated++
                try {
                    createBitmap(width, height)
                } catch (_: Throwable) {
                    allocated--
                    null
                }
            } else null
        }

        fun release(bitmap: Bitmap) {
            val recycle = synchronized(lock) {
                if (closed) true else {
                    available.addLast(bitmap)
                    false
                }
            }
            if (recycle) bitmap.recycle()
        }

        fun close() {
            val bitmaps = synchronized(lock) {
                if (closed) return
                closed = true
                buildList { while (available.isNotEmpty()) add(available.removeFirst()) }
            }
            bitmaps.forEach(Bitmap::recycle)
        }
    }

    private class SuccessfulFrameRate {
        private var windowStartNanos = 0L
        private var successfulFrames = 0
        private var lastMeasured: Float? = null

        fun onFrame(nowNanos: Long): Float? {
            if (windowStartNanos == 0L) {
                windowStartNanos = nowNanos
                successfulFrames = 0
                return lastMeasured
            }
            successfulFrames++
            val elapsed = nowNanos - windowStartNanos
            if (elapsed >= FPS_WINDOW_NANOS) {
                lastMeasured = successfulFrames * 1_000_000_000f / elapsed
                windowStartNanos = nowNanos
                successfulFrames = 0
            }
            return lastMeasured
        }

        fun reset() {
            windowStartNanos = 0L
            successfulFrames = 0
            lastMeasured = null
        }
    }

    companion object {
        const val ANALYSIS_WIDTH = 320
        const val ANALYSIS_HEIGHT = 240
        const val MAX_ANALYSIS_FPS = 8
        private const val BITMAP_POOL_CAPACITY = 3
        private const val CAPTURE_INTERVAL_NANOS = 1_000_000_000L / MAX_ANALYSIS_FPS
        private const val FPS_WINDOW_NANOS = 1_000_000_000L
        private const val CLOSE_WAIT_MILLIS = 1_000L
    }
}
