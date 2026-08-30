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
import com.alonibh.tellodrone.vision.FrameQualityGate
import com.alonibh.tellodrone.vision.PersonDetectionPipeline
import com.alonibh.tellodrone.vision.PersonDetectionSnapshot
import com.alonibh.tellodrone.vision.PersonDetectionStore
import com.alonibh.tellodrone.vision.DetectorInferenceMeasurement
import com.alonibh.tellodrone.vision.Yolo11nLiteRtPersonDetector
import com.alonibh.tellodrone.vision.VisionTraceFeature
import com.alonibh.tellodrone.vision.CorruptFrameTrace
import com.alonibh.tellodrone.vision.VideoDiagnosticTrace
import com.alonibh.tellodrone.vision.startProductionDetection
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    private val recoveryState = VideoRecoveryStateMachine()
    private val udpDatagramsReceived = AtomicLong()
    private val assemblerDroppedAccessUnits = AtomicLong()
    private val decoderResets = AtomicLong()
    private val codecInputStalls = AtomicLong()
    private val renderedFrames = AtomicLong()
    private val corruptFramesRejected = AtomicLong()
    private val lastReportedBufferDrops = AtomicLong()
    private val lastReportedDiscontinuities = AtomicLong()
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
        onCorruptFrame = { sequence, timestampNanos, consecutive, blackFraction, avgLum ->
            corruptFramesRejected.incrementAndGet()
            visionRecorder.recordCorruptFrame(
                CorruptFrameTrace(
                    frameSequence = sequence,
                    sourceTimestampNanos = timestampNanos,
                    consecutiveCorruptCount = consecutive,
                    blackPixelFraction = blackFraction,
                    averageLuminance = avgLum,
                ),
            )
            recordVideoDiagnostic(
                eventType = "corrupt_analysis_frame_rejected",
                detail = "Rejected analysis frame $sequence",
                consecutiveCorruptFrames = consecutive,
            )
            if (consecutive == FrameQualityGate.MAX_CONSECUTIVE_CORRUPT_FRAMES) {
                publishRecoveryTransition(
                    recoveryState.requireDecoderResynchronization(System.nanoTime()),
                    "corrupt_frame_threshold",
                )
                accessUnits.declareDiscontinuity()
                recordAccessUnitDiagnostics("decoder_resync_start", "Repeated corrupt analysis frames")
                unitSignal.trySend(Unit)
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
        publishRecoveryTransition(
            recoveryState.onStreamAcknowledged(System.nanoTime()),
            "stream_acknowledged",
        )
        unitSignal.trySend(Unit)
    }

    override fun streamFailed(reason: String) {
        if (closed.get() || !failed.compareAndSet(false, true)) return
        streamIsAcknowledged.set(false)
        recoveryState.onFailed()
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
            val nowNanos = System.nanoTime()
            publishRecoveryTransition(recoveryState.onSurfaceAttached(nowNanos), "surface_attached")
            recordVideoDiagnostic("surface_attached", "Surface generation ${videoSurface.generation}")
        }
        unitSignal.trySend(Unit)
    }

    fun detachSurface(value: Surface) {
        if (videoSurface.detach(value)) {
            stopDetectionAndScheduleRelease()
            decodedFrameSource.stop(value)
            publishRecoveryTransition(recoveryState.onSurfaceDetached(), "surface_detached")
            recordVideoDiagnostic("surface_detached", "Surface generation ${videoSurface.generation}")
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
        recordVideoDiagnostic("video_session_closed", "Video controller closing")
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
                    val datagramCount = udpDatagramsReceived.incrementAndGet()
                    val unit = assembler.offerDatagram(packet.data, packet.length)
                    if (assembler.droppedAccessUnits != observedAssemblerDrops) {
                        observedAssemblerDrops = assembler.droppedAccessUnits
                        assemblerDroppedAccessUnits.set(observedAssemblerDrops)
                        publishRecoveryTransition(
                            recoveryState.requireDecoderResynchronization(System.nanoTime()),
                            "assembler_drop",
                        )
                        accessUnits.declareDiscontinuity()
                        recordAccessUnitDiagnostics("assembler_access_unit_drop", "Assembler dropped an access unit")
                        unitSignal.trySend(Unit)
                    }
                    unit?.let {
                        accessUnits.offer(it)
                        recordAccessUnitDiagnostics("access_unit_buffer_update")
                        unitSignal.trySend(Unit)
                    }
                    if (datagramCount % VIDEO_DIAGNOSTIC_DATAGRAM_INTERVAL == 0L) {
                        recordVideoDiagnostic("udp_progress", "Video UDP receive progress")
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
                    val newSurface = videoSurface.current?.takeIf { it.isValid }
                    if (newSurface != null && codec != null) {
                        val switched = runCatching {
                            codec.setOutputSurface(newSurface)
                        }.isSuccess
                        if (switched) {
                            codecSurface = newSurface
                            observedSurfaceGeneration = generation
                            recordVideoDiagnostic(
                                "decoder_surface_switched",
                                "setOutputSurface succeeded for generation $generation",
                            )
                        } else {
                            codec.releaseSafely()
                            codec = null
                            codecSurface = newSurface
                            observedSurfaceGeneration = generation
                            needsIdr = true
                            publishRecoveryTransition(
                                recoveryState.requireDecoderResynchronization(System.nanoTime()),
                                "decoder_surface_switch_failed",
                            )
                            frameRate.reset()
                            recordDecoderReset("setOutputSurface failed for generation $generation")
                        }
                    } else {
                        codecSurface = newSurface
                        observedSurfaceGeneration = generation
                        if (newSurface != null) {
                            // No running decoder exists, so normal SPS/PPS/IDR bootstrap remains required.
                            needsIdr = true
                        } else {
                            // Keep a configured decoder alive across a brief detach. A replacement surface
                            // can then use setOutputSurface without discarding inter-frame decode state.
                            frameRate.reset()
                        }
                    }
                }

                if (!streamIsAcknowledged.get()) continue
                if (codecSurface == null) continue
                var input = accessUnits.poll() ?: continue
                do {
                    when (input) {
                        H264DecodeInput.Discontinuity -> {
                            inputRetry.clear()
                            publishRecoveryTransition(
                                recoveryState.requireDecoderResynchronization(System.nanoTime()),
                                "decoder_discontinuity",
                            )
                            codec.releaseSafely()
                            codec = null
                            needsIdr = true
                            frameRate.reset()
                            recordDecoderReset("Declared H264 discontinuity")
                        }
                        is H264DecodeInput.AccessUnit -> {
                            inputRetry.begin(input.value, System.nanoTime())
                            while (scope.isActive && !closed.get() && !failed.get()) {
                                if (accessUnits.takeDiscontinuity()) {
                                    inputRetry.clear()
                                    publishRecoveryTransition(
                                        recoveryState.requireDecoderResynchronization(System.nanoTime()),
                                        "decoder_discontinuity",
                                    )
                                    codec.releaseSafely()
                                    codec = null
                                    needsIdr = true
                                    frameRate.reset()
                                    recordDecoderReset("Discontinuity interrupted pending decoder input")
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
                                            publishRecoveryTransition(
                                                recoveryState.requireDecoderResynchronization(System.nanoTime()),
                                                "codec_input_stall",
                                            )
                                            codec.releaseSafely()
                                            codec = null
                                            needsIdr = true
                                            accessUnits.declareDiscontinuity()
                                            frameRate.reset()
                                            recordDecoderReset("Codec input stall exceeded recovery limit")
                                            recordAccessUnitDiagnostics("decoder_resync_start", "Codec input stall")
                                            break
                                        }
                                    }
                                    AccessUnitProcessOutcome.ContinuityLost -> {
                                        inputRetry.clear()
                                        publishRecoveryTransition(
                                            recoveryState.requireDecoderResynchronization(System.nanoTime()),
                                            "decoder_continuity_lost",
                                        )
                                        accessUnits.declareDiscontinuity()
                                        frameRate.reset()
                                        recordAccessUnitDiagnostics("decoder_resync_start", "Decoder continuity lost")
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
            recordCodecInputStall()
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
                recordDecoderReset("Codec returned a null input buffer")
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
            recordDecoderReset("Encoded access unit exceeded codec input capacity")
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
        if (unit.hasIdr) recoveryState.onDecoderResynchronized()
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
                renderedFrames.incrementAndGet()
                val measured = frameRate.onRendered(nowNanos)
                val transition = recoveryState.onFrameRendered(nowNanos)
                mutableState.update { current ->
                    if (transition.availability != VideoAvailability.Streaming) current else current.copy(
                        availability = transition.availability,
                        measuredFps = measured ?: current.measuredFps,
                        lastFrameAt = Instant.now(),
                    )
                }
                transition.recoveryDurationNanos?.let { duration ->
                    recordVideoDiagnostic(
                        eventType = "first_good_frame_after_recovery",
                        detail = "Rendered a frame to the current surface",
                        recoveryDurationMillis = duration / 1_000_000L,
                    )
                }
                if (renderedFrames.get() % VIDEO_DIAGNOSTIC_RENDER_INTERVAL == 0L) {
                    recordVideoDiagnostic("render_progress", "Rendered video frame progress")
                }
                decodedFrameSource.onFrameRendered(nowNanos)
            }
        }
    }

    private fun publishRecoveryTransition(transition: VideoRecoveryTransition, reason: String) {
        mutableState.update { current ->
            if (current.availability == VideoAvailability.Error) {
                current
            } else when (transition.availability) {
                VideoAvailability.Recovering,
                VideoAvailability.Unavailable -> current.withoutCurrentPerception(transition.availability)
                VideoAvailability.Streaming -> current.copy(availability = VideoAvailability.Streaming)
                VideoAvailability.Error -> current.copy(availability = VideoAvailability.Error)
            }
        }
        if (transition.recoveryStarted) {
            recordVideoDiagnostic("decoder_resync_start", reason)
        }
    }

    private fun VideoState.withoutCurrentPerception(availability: VideoAvailability): VideoState = copy(
        availability = availability,
        personDetectionState = com.alonibh.tellodrone.domain.PersonDetectionState.Off,
        detectorCandidates = emptyList(),
        personDetections = emptyList(),
        processedDetectorFrameSequence = null,
        processedDetectorSourceTimestampNanos = null,
        processedRenderedFrameTimestampNanos = null,
        processedCaptureRequestTimestampNanos = null,
        processedPixelCopyCompletedTimestampNanos = null,
        processedDetectorInferenceStartedTimestampNanos = null,
        processedDetectorInferenceCompletedTimestampNanos = null,
        detectorPreprocessingNanos = null,
        detectorModelInferenceNanos = null,
        detectorDecodeAndNmsNanos = null,
        detectorAppearanceNanos = null,
    )

    private fun recordAccessUnitDiagnostics(eventType: String, detail: String? = null) {
        val diagnostics = accessUnits.diagnostics()
        val dropsChanged = lastReportedBufferDrops.getAndSet(diagnostics.droppedAccessUnits) !=
            diagnostics.droppedAccessUnits
        val discontinuitiesChanged = lastReportedDiscontinuities.getAndSet(diagnostics.discontinuities) !=
            diagnostics.discontinuities
        if (dropsChanged || discontinuitiesChanged || eventType != "access_unit_buffer_update") {
            recordVideoDiagnostic(eventType, detail)
        }
    }

    private fun recordDecoderReset(reason: String) {
        decoderResets.incrementAndGet()
        recordVideoDiagnostic("decoder_reset", reason)
    }

    private fun recordCodecInputStall() {
        val count = codecInputStalls.incrementAndGet()
        if (count == 1L || count % CODEC_STALL_DIAGNOSTIC_INTERVAL == 0L) {
            recordVideoDiagnostic("codec_input_stall", "MediaCodec input buffer unavailable")
        }
    }

    private fun recordVideoDiagnostic(
        eventType: String,
        detail: String? = null,
        consecutiveCorruptFrames: Int? = null,
        recoveryDurationMillis: Long? = null,
    ) {
        val accessUnitDiagnostics = accessUnits.diagnostics()
        visionRecorder.recordVideoDiagnostic(
            VideoDiagnosticTrace(
                timestampNanos = System.nanoTime(),
                eventType = eventType,
                detail = detail,
                udpDatagramsReceived = udpDatagramsReceived.get(),
                droppedAccessUnits = assemblerDroppedAccessUnits.get(),
                accessUnitBufferDrops = accessUnitDiagnostics.droppedAccessUnits,
                pendingAccessUnits = accessUnitDiagnostics.pendingAccessUnits,
                waitingForIdr = accessUnitDiagnostics.waitingForIdr,
                discontinuities = accessUnitDiagnostics.discontinuities,
                decoderResets = decoderResets.get(),
                codecInputStalls = codecInputStalls.get(),
                corruptFramesRejected = corruptFramesRejected.get(),
                consecutiveCorruptFrames = consecutiveCorruptFrames,
                renderedFrames = renderedFrames.get(),
                recoveryDurationMillis = recoveryDurationMillis,
            ),
        )
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
                analysisLatestCaptureRequestTimestampNanos = diagnostics.latestCaptureRequestTimestampNanos,
                analysisLatestPixelCopyCompletedTimestampNanos = diagnostics.latestPixelCopyCompletedTimestampNanos,
                analysisPendingFrameDepth = diagnostics.pendingFrameDepth,
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
                processedRenderedFrameTimestampNanos = snapshot.renderedFrameTimestampNanos,
                processedCaptureRequestTimestampNanos = snapshot.captureRequestTimestampNanos,
                processedPixelCopyCompletedTimestampNanos = snapshot.pixelCopyCompletedTimestampNanos,
                processedDetectorInferenceStartedTimestampNanos = snapshot.detectorInferenceStartedTimestampNanos,
                processedDetectorInferenceCompletedTimestampNanos = snapshot.detectorInferenceCompletedTimestampNanos,
                detectorPreprocessingNanos = snapshot.detectorStageTiming?.preprocessingNanos,
                detectorModelInferenceNanos = snapshot.detectorStageTiming?.modelInferenceNanos,
                detectorDecodeAndNmsNanos = snapshot.detectorStageTiming?.decodeAndNmsNanos,
                detectorAppearanceNanos = snapshot.detectorStageTiming?.appearanceNanos,
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
        private const val VIDEO_DIAGNOSTIC_DATAGRAM_INTERVAL = 100L
        private const val VIDEO_DIAGNOSTIC_RENDER_INTERVAL = 30L
        private const val CODEC_STALL_DIAGNOSTIC_INTERVAL = 10L
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
