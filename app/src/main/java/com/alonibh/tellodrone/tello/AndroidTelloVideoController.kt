package com.alonibh.tellodrone.tello

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Network
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.VideoState
import com.alonibh.tellodrone.vision.FallbackPersonDetectorFactory
import com.alonibh.tellodrone.vision.PersonDetectionPipeline
import com.alonibh.tellodrone.vision.PersonDetectionSnapshot
import com.alonibh.tellodrone.vision.PersonDetectionStore
import com.alonibh.tellodrone.vision.DetectorInferenceMeasurement
import com.alonibh.tellodrone.vision.Yolo11nLiteRtPersonDetector
import com.alonibh.tellodrone.vision.VisionTraceFeature
import com.alonibh.tellodrone.vision.startProductionDetection
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Physical, service-owned Tello video receiver and decoder. Receive and codec work have dedicated
 * threads and a bounded FIFO handoff, so they never execute on or backlog the RC path.
 */
class AndroidTelloVideoController(
    private val network: Network,
    context: Context,
) : TelloVideoController {
    private val receiveExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tello-video-udp").apply { isDaemon = true }
    }
    private val receiveDispatcher = receiveExecutor.asCoroutineDispatcher()
    private val codecExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tello-video-codec").apply { isDaemon = true }
    }
    private val codecDispatcher = codecExecutor.asCoroutineDispatcher()
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor)
    private val mutableState = MutableStateFlow(VideoState())
    override val state: StateFlow<VideoState> = mutableState.asStateFlow()
    private val decodedFrameSource: DecodedFrameSource = PixelCopyDecodedFrameSource(::updateAnalysisDiagnostics)
    private val detectorFactory = FallbackPersonDetectorFactory { model, backend ->
        Yolo11nLiteRtPersonDetector(context.applicationContext, model, backend)
    }
    private val visionRecorder = VisionTraceFeature.recorder(context.applicationContext)
    private val detectionPipeline = PersonDetectionPipeline(
        detectorFactory = { model, preference -> detectorFactory.create(model, preference) },
        onSnapshot = ::publishDetectionSnapshot,
        // Instrumentation has its own completed-inference publication path.  It must not depend
        // on the observational snapshot used by tracking and overlay rendering.
        onInferenceMeasurement = ::publishDetectorInstrumentation,
        onAnalyzedFrame = { frame ->
            if (visionRecorder.capturesFrames) {
                visionRecorder.captureAnalyzedFrame(
                    frame.metadata.sequence,
                    frame.metadata.captureTimestampNanos,
                    frame.bitmap,
                )
            }
        },
    )

    private val socket = AtomicReference<DatagramSocket?>()
    private val videoSurface = VideoSurfaceLifecycle<Surface>()
    private val prepared = AtomicBoolean()
    private val streamIsAcknowledged = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val accessUnits = BoundedAccessUnitBuffer()
    private val unitSignal = Channel<Unit>(Channel.CONFLATED)
    private var receiverJob: Job? = null
    private var decoderJob: Job? = null
    private var detectionStaleJob: Job? = null

    init {
        decodedFrameSource.setConsumer(detectionPipeline)
    }

    override suspend fun prepare(): Result<Unit> {
        if (closed.get()) return Result.failure(IllegalStateException("Video pipeline is closed"))
        if (!prepared.compareAndSet(false, true)) return Result.success(Unit)
        return try {
            val receiver = withContext(receiveDispatcher) {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    network.bindSocket(this)
                    bind(InetSocketAddress(VIDEO_PORT))
                    soTimeout = RECEIVE_POLL_MILLIS
                }
            }
            socket.set(receiver)
            receiverJob = scope.launch(receiveDispatcher) { receiveLoop(receiver) }
            decoderJob = scope.launch(codecDispatcher) { decodeLoop() }
            Result.success(Unit)
        } catch (error: Throwable) {
            streamFailed("Could not open UDP video receiver: ${error.safeMessage()}")
            Result.failure(error)
        }
    }

    override fun streamAcknowledged() {
        if (!prepared.get() || failed.get() || closed.get()) return
        streamIsAcknowledged.set(true)
        mutableState.value = VideoState(availability = VideoAvailability.Streaming)
        unitSignal.trySend(Unit)
    }

    override fun streamFailed(reason: String) {
        if (closed.get() || !failed.compareAndSet(false, true)) return
        streamIsAcknowledged.set(false)
        socket.getAndSet(null)?.close()
        receiverJob?.cancel()
        decoderJob?.cancel()
        receiveExecutor.shutdown()
        codecExecutor.shutdown()
        accessUnits.reset()
        stopDetectionAndScheduleRelease()
        detectionStaleJob?.cancel()
        scope.launch { decodedFrameSource.close() }
        mutableState.value = VideoState(
            availability = VideoAvailability.Error,
            errorReason = reason.take(MAX_ERROR_REASON_CHARS),
        )
    }

    fun attachSurface(value: Surface) {
        if (videoSurface.attach(value)) {
            decodedFrameSource.start(value)
        }
        unitSignal.trySend(Unit)
    }

    fun detachSurface(value: Surface) {
        if (videoSurface.detach(value)) {
            stopDetectionAndScheduleRelease()
            decodedFrameSource.stop(value)
        }
        unitSignal.trySend(Unit)
    }

    override fun setPersonDetectionEnabled(enabled: Boolean): Result<Unit> {
        if (!enabled) {
            stopDetectionAndScheduleRelease()
            detectionStaleJob?.cancel()
            return Result.success(Unit)
        }
        val current = mutableState.value
        if (closed.get() || failed.get() || !streamIsAcknowledged.get() ||
            current.availability != VideoAvailability.Streaming ||
            videoSurface.current?.isValid != true || current.analysisLatestSequence == null
        ) {
            return Result.failure(IllegalStateException("Live preview analysis is not ready"))
        }
        detectionPipeline.startProductionDetection()
        return Result.success(Unit)
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        streamIsAcknowledged.set(false)
        socket.getAndSet(null)?.close()
        unitSignal.close()
        receiverJob?.cancelAndJoin()
        decoderJob?.cancelAndJoin()
        detectionPipeline.stop()
        detectionStaleJob?.cancelAndJoin()
        decodedFrameSource.executeOnConsumerThread(detectionPipeline::releaseIfStopped)
        decodedFrameSource.setConsumer(null)
        decodedFrameSource.close()
        accessUnits.reset()
        supervisor.cancel()
        receiveDispatcher.close()
        codecDispatcher.close()
    }

    private suspend fun receiveLoop(receiver: DatagramSocket) {
        val assembler = TelloH264AccessUnitAssembler()
        val bytes = ByteArray(MAX_VIDEO_DATAGRAM_BYTES)
        var observedAssemblerDrops = 0L
        try {
            while (scope.isActive && !closed.get() && !failed.get()) {
                val packet = DatagramPacket(bytes, bytes.size)
                try {
                    receiver.receive(packet)
                    val unit = assembler.offerDatagram(packet.data, packet.length)
                    if (assembler.droppedAccessUnits != observedAssemblerDrops) {
                        observedAssemblerDrops = assembler.droppedAccessUnits
                        accessUnits.declareDiscontinuity()
                        unitSignal.trySend(Unit)
                    }
                    unit?.let {
                        accessUnits.offer(it)
                        unitSignal.trySend(Unit)
                    }
                } catch (_: SocketTimeoutException) {
                    // Poll cancellation and surface/session changes without inventing stream health.
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!closed.get() && !failed.get()) {
                streamFailed("Video receive failed: ${error.safeMessage()}")
            }
        } finally {
            assembler.reset()
        }
    }

    private suspend fun decodeLoop() {
        var codec: MediaCodec? = null
        var codecSurface: Surface? = null
        var observedSurfaceGeneration = -1L
        var sequenceParameterSet: ByteArray? = null
        var pictureParameterSet: ByteArray? = null
        var needsIdr = true
        val frameRate = RenderedFrameRate()
        val inputRetry = DecoderInputRetryState(MAX_CODEC_INPUT_STALL_NANOS)
        try {
            while (scope.isActive && !closed.get() && !failed.get()) {
                unitSignal.receive()
                val generation = videoSurface.generation
                if (generation != observedSurfaceGeneration) {
                    codec.releaseSafely()
                    codec = null
                    codecSurface = videoSurface.current?.takeIf { it.isValid }
                    observedSurfaceGeneration = generation
                    needsIdr = true
                    frameRate.reset()
                }

                if (!streamIsAcknowledged.get()) continue
                var input = accessUnits.poll() ?: continue
                do {
                    when (input) {
                        H264DecodeInput.Discontinuity -> {
                            inputRetry.clear()
                            codec.releaseSafely()
                            codec = null
                            needsIdr = true
                            frameRate.reset()
                        }
                        is H264DecodeInput.AccessUnit -> {
                            inputRetry.begin(input.value, System.nanoTime())
                            while (scope.isActive && !closed.get() && !failed.get()) {
                                if (accessUnits.takeDiscontinuity()) {
                                    inputRetry.clear()
                                    codec.releaseSafely()
                                    codec = null
                                    needsIdr = true
                                    frameRate.reset()
                                    break
                                }
                                val pendingUnit = checkNotNull(inputRetry.pendingAccessUnit)
                                val result = processAccessUnit(
                                    pendingUnit,
                                    codecSurface,
                                    codec,
                                    sequenceParameterSet,
                                    pictureParameterSet,
                                    needsIdr,
                                    frameRate,
                                )
                                codec = result.codec
                                sequenceParameterSet = result.sps
                                pictureParameterSet = result.pps
                                needsIdr = result.needsIdr
                                when (result.outcome) {
                                    AccessUnitProcessOutcome.Submitted,
                                    AccessUnitProcessOutcome.Skipped -> {
                                        check(inputRetry.complete() === pendingUnit)
                                        break
                                    }
                                    AccessUnitProcessOutcome.TemporaryBackpressure -> {
                                        if (inputRetry.onTemporaryMiss(System.nanoTime()) ==
                                            DecoderInputRetryDecision.Recover
                                        ) {
                                            inputRetry.clear()
                                            codec.releaseSafely()
                                            codec = null
                                            needsIdr = true
                                            accessUnits.declareDiscontinuity()
                                            frameRate.reset()
                                            break
                                        }
                                    }
                                    AccessUnitProcessOutcome.ContinuityLost -> {
                                        inputRetry.clear()
                                        accessUnits.declareDiscontinuity()
                                        frameRate.reset()
                                        break
                                    }
                                }
                            }
                        }
                    }
                    input = accessUnits.poll() ?: break
                } while (scope.isActive && !closed.get() && !failed.get())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!closed.get() && !failed.get()) {
                streamFailed("Video decoder failed: ${error.safeMessage()}")
            }
        } finally {
            codec.releaseSafely()
        }
    }

    private fun processAccessUnit(
        unit: H264AccessUnit,
        codecSurface: Surface?,
        currentCodec: MediaCodec?,
        currentSps: ByteArray?,
        currentPps: ByteArray?,
        currentlyNeedsIdr: Boolean,
        frameRate: RenderedFrameRate,
    ): DecoderState {
        var codec = currentCodec
        var sequenceParameterSet = currentSps
        var pictureParameterSet = currentPps
        var needsIdr = currentlyNeedsIdr
        AnnexBParser.parse(unit.bytes).forEach { nal ->
            when (nal.type) {
                H264NalUnitType.SPS -> sequenceParameterSet = nal.bytes
                H264NalUnitType.PPS -> pictureParameterSet = nal.bytes
            }
        }
        val display = codecSurface?.takeIf { it.isValid }
            ?: return DecoderState(
                codec,
                sequenceParameterSet,
                pictureParameterSet,
                needsIdr,
                AccessUnitProcessOutcome.Skipped,
            )
        if (needsIdr && !unit.hasIdr) {
            return DecoderState(
                codec,
                sequenceParameterSet,
                pictureParameterSet,
                needsIdr,
                AccessUnitProcessOutcome.Skipped,
            )
        }

        if (codec == null) {
            val sps = sequenceParameterSet
                ?: return DecoderState(
                    null,
                    sequenceParameterSet,
                    pictureParameterSet,
                    needsIdr,
                    AccessUnitProcessOutcome.Skipped,
                )
            val pps = pictureParameterSet
                ?: return DecoderState(
                    null,
                    sequenceParameterSet,
                    pictureParameterSet,
                    needsIdr,
                    AccessUnitProcessOutcome.Skipped,
                )
            codec = createDecoder(display, sps, pps)
            needsIdr = false
        }

        val activeCodec = codec
        drainOutput(activeCodec, display, frameRate)
        val inputIndex = activeCodec.dequeueInputBuffer(CODEC_INPUT_TIMEOUT_MICROS)
        if (inputIndex < 0) {
            return DecoderState(
                codec,
                sequenceParameterSet,
                pictureParameterSet,
                needsIdr,
                AccessUnitProcessOutcome.TemporaryBackpressure,
            )
        }
        val input = activeCodec.getInputBuffer(inputIndex)
            ?: run {
                codec.releaseSafely()
                return DecoderState(
                    null,
                    sequenceParameterSet,
                    pictureParameterSet,
                    needsIdr = true,
                    AccessUnitProcessOutcome.ContinuityLost,
                )
            }
        if (unit.bytes.size > input.capacity()) {
            activeCodec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs(), 0)
            codec.releaseSafely()
            codec = null
            needsIdr = true
            return DecoderState(
                codec,
                sequenceParameterSet,
                pictureParameterSet,
                needsIdr,
                AccessUnitProcessOutcome.ContinuityLost,
            )
        }
        input.clear()
        input.put(unit.bytes)
        activeCodec.queueInputBuffer(inputIndex, 0, unit.bytes.size, presentationTimeUs(), 0)
        drainOutput(activeCodec, display, frameRate)
        return DecoderState(
            codec,
            sequenceParameterSet,
            pictureParameterSet,
            needsIdr,
            AccessUnitProcessOutcome.Submitted,
        )
    }

    private fun createDecoder(display: Surface, sps: ByteArray, pps: ByteArray): MediaCodec {
        val format = MediaFormat.createVideoFormat(MIME_AVC, TELLO_VIDEO_WIDTH, TELLO_VIDEO_HEIGHT).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, TelloH264AccessUnitAssembler.DEFAULT_MAX_ACCESS_UNIT_BYTES)
        }
        val decoder = MediaCodec.createDecoderByType(MIME_AVC)
        try {
            if (Build.VERSION.SDK_INT >= 30) enableLowLatencyIfSupported(decoder, format)
            decoder.configure(format, display, null, 0)
            decoder.start()
            return decoder
        } catch (error: Throwable) {
            decoder.releaseSafely()
            throw error
        }
    }

    @RequiresApi(30)
    private fun enableLowLatencyIfSupported(codec: MediaCodec, format: MediaFormat) {
        val capabilities = codec.codecInfo.getCapabilitiesForType(MIME_AVC)
        if (capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
            format.setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency, true)
        }
    }

    private fun drainOutput(codec: MediaCodec, display: Surface, frameRate: RenderedFrameRate) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(info, 0)
            if (outputIndex < 0) return
            val render = display.isValid &&
                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0
            codec.releaseOutputBuffer(outputIndex, render)
            if (render) {
                val nowNanos = System.nanoTime()
                val measured = frameRate.onRendered(nowNanos)
                mutableState.update { current ->
                    if (current.availability != VideoAvailability.Streaming) current else current.copy(
                        measuredFps = measured ?: current.measuredFps,
                        lastFrameAt = Instant.now(),
                    )
                }
                decodedFrameSource.onFrameRendered(nowNanos)
            }
        }
    }

    private fun updateAnalysisDiagnostics(diagnostics: AnalysisFrameDiagnostics) {
        mutableState.update { current ->
            if (current.availability != VideoAvailability.Streaming) current else current.copy(
                analysisMeasuredFps = diagnostics.measuredFps,
                analysisLatestCaptureTimestampNanos = diagnostics.latestCaptureTimestampNanos,
                analysisFrameWidth = diagnostics.width,
                analysisFrameHeight = diagnostics.height,
                analysisLatestSequence = diagnostics.latestSequence,
                analysisCapturedFrames = diagnostics.capturedFrames,
                analysisDroppedFrames = diagnostics.droppedFrames,
            )
        }
    }

    private fun publishDetectionSnapshot(snapshot: PersonDetectionSnapshot) {
        detectionStaleJob?.cancel()
        detectionStaleJob = null
        val newestTimestamp = snapshot.detections.maxOfOrNull { it.sourceTimestampNanos }
        if (newestTimestamp != null) {
            val remainingNanos =
                (newestTimestamp + PersonDetectionStore.STALE_AFTER_NANOS - System.nanoTime()).coerceAtLeast(0L)
            detectionStaleJob = scope.launch {
                delay((remainingNanos + 999_999L) / 1_000_000L)
                detectionPipeline.expire()
            }
        }
        mutableState.update { current ->
            if (current.availability != VideoAvailability.Streaming) current else current.copy(
                personDetectionState = snapshot.state,
                detectorMeasuredFps = snapshot.measuredFps,
                detectorInferenceMillis = snapshot.inferenceMillis,
                detectorInitializationMillis = snapshot.initializationMillis,
                detectorInferenceP50Millis = snapshot.inferenceP50Millis,
                detectorInferenceP95Millis = snapshot.inferenceP95Millis,
                detectorAnalyzedFrames = snapshot.analyzedFrames,
                detectorBackend = snapshot.backend,
                detectorModelName = snapshot.modelName,
                detectorFellBackFromGpu = snapshot.fellBackFromGpu,
                detectorErrorReason = snapshot.errorReason,
                detectorConfidenceThreshold = snapshot.confidenceThreshold,
                processedDetectorFrameSequence = snapshot.processedFrameSequence,
                processedDetectorSourceTimestampNanos = snapshot.processedSourceTimestampNanos,
                detectorCandidates = snapshot.candidates,
                personDetections = snapshot.detections,
            )
        }
    }

    /** Publishes only benchmark fields; it never changes detection, tracking, or video cadence. */
    private fun publishDetectorInstrumentation(measurement: DetectorInferenceMeasurement) {
        mutableState.update { current ->
            if (current.availability != VideoAvailability.Streaming) current else current.copy(
                detectorModelName = measurement.descriptor.modelName,
                detectorBackend = measurement.descriptor.backend,
                detectorFellBackFromGpu = measurement.descriptor.fellBackFromGpu,
                detectorInitializationMillis = measurement.initializationMillis,
                detectorInferenceP50Millis = measurement.inferenceP50Millis,
                detectorInferenceP95Millis = measurement.inferenceP95Millis,
                detectorMeasuredFps = measurement.measuredFps,
                detectorAnalyzedFrames = measurement.analyzedFrames,
            )
        }
    }

    private fun stopDetectionAndScheduleRelease() {
        detectionPipeline.stop()
        decodedFrameSource.executeOnConsumerThread(detectionPipeline::releaseIfStopped)
    }

    private fun MediaCodec?.releaseSafely() {
        if (this == null) return
        runCatching { stop() }
        runCatching { release() }
    }

    private fun presentationTimeUs(): Long = System.nanoTime() / 1_000L
    private fun Throwable.safeMessage(): String = message ?: javaClass.simpleName

    private data class DecoderState(
        val codec: MediaCodec?,
        val sps: ByteArray?,
        val pps: ByteArray?,
        val needsIdr: Boolean,
        val outcome: AccessUnitProcessOutcome,
    )

    private enum class AccessUnitProcessOutcome {
        Submitted,
        Skipped,
        TemporaryBackpressure,
        ContinuityLost,
    }

    private class RenderedFrameRate {
        private var windowStartNanos = 0L
        private var renderedFrames = 0

        fun onRendered(nowNanos: Long): Float? {
            if (windowStartNanos == 0L) windowStartNanos = nowNanos
            renderedFrames++
            val elapsed = nowNanos - windowStartNanos
            if (elapsed < FPS_WINDOW_NANOS) return null
            val fps = renderedFrames * 1_000_000_000f / elapsed
            windowStartNanos = nowNanos
            renderedFrames = 0
            return fps
        }

        fun reset() {
            windowStartNanos = 0L
            renderedFrames = 0
        }
    }

    companion object {
        const val VIDEO_PORT = 11_111
        private const val MIME_AVC = "video/avc"
        private const val TELLO_VIDEO_WIDTH = 960
        private const val TELLO_VIDEO_HEIGHT = 720
        private const val MAX_VIDEO_DATAGRAM_BYTES = 2_048
        private const val RECEIVE_POLL_MILLIS = 500
        private const val MAX_ERROR_REASON_CHARS = 120
        private const val FPS_WINDOW_NANOS = 1_000_000_000L
        private const val CODEC_INPUT_TIMEOUT_MICROS = 5_000L
        private const val MAX_CODEC_INPUT_STALL_NANOS = 500_000_000L
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
