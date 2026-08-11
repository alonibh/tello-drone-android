package com.alonibh.tellodrone.tello

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Network
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.VideoState
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
import kotlinx.coroutines.withContext

/**
 * Physical, service-owned Tello video receiver and decoder. Receive and codec work have dedicated
 * threads and a bounded recovery-plus-latest handoff, so they never execute on or backlog the RC
 * path.
 */
class AndroidTelloVideoController(
    private val network: Network,
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

    private val socket = AtomicReference<DatagramSocket?>()
    private val surface = AtomicReference<Surface?>()
    private val surfaceGeneration = AtomicLong()
    private val prepared = AtomicBoolean()
    private val streamIsAcknowledged = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val latestUnit = LatestAccessUnitBuffer()
    private val unitSignal = Channel<Unit>(Channel.CONFLATED)
    private var receiverJob: Job? = null
    private var decoderJob: Job? = null

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
        latestUnit.reset()
        mutableState.value = VideoState(
            availability = VideoAvailability.Error,
            errorReason = reason.take(MAX_ERROR_REASON_CHARS),
        )
    }

    fun attachSurface(value: Surface) {
        if (surface.getAndSet(value) !== value) surfaceGeneration.incrementAndGet()
        unitSignal.trySend(Unit)
    }

    fun detachSurface(value: Surface) {
        if (surface.compareAndSet(value, null)) surfaceGeneration.incrementAndGet()
        unitSignal.trySend(Unit)
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        streamIsAcknowledged.set(false)
        socket.getAndSet(null)?.close()
        unitSignal.close()
        receiverJob?.cancelAndJoin()
        decoderJob?.cancelAndJoin()
        latestUnit.reset()
        supervisor.cancel()
        receiveDispatcher.close()
        codecDispatcher.close()
    }

    private suspend fun receiveLoop(receiver: DatagramSocket) {
        val assembler = TelloH264AccessUnitAssembler()
        val bytes = ByteArray(MAX_VIDEO_DATAGRAM_BYTES)
        try {
            while (scope.isActive && !closed.get() && !failed.get()) {
                val packet = DatagramPacket(bytes, bytes.size)
                try {
                    receiver.receive(packet)
                    assembler.offerDatagram(packet.data, packet.length)?.let { unit ->
                        latestUnit.offer(unit)
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
        try {
            while (scope.isActive && !closed.get() && !failed.get()) {
                unitSignal.receive()
                val generation = surfaceGeneration.get()
                if (generation != observedSurfaceGeneration) {
                    codec.releaseSafely()
                    codec = null
                    codecSurface = surface.get()?.takeIf { it.isValid }
                    observedSurfaceGeneration = generation
                    needsIdr = true
                    frameRate.reset()
                }

                var unit = latestUnit.pollLatest() ?: continue
                if (!streamIsAcknowledged.get()) continue
                do {
                    processAccessUnit(unit, codecSurface, codec, sequenceParameterSet, pictureParameterSet, needsIdr, frameRate).let { result ->
                        codec = result.codec
                        sequenceParameterSet = result.sps
                        pictureParameterSet = result.pps
                        needsIdr = result.needsIdr
                    }
                    unit = latestUnit.pollLatest() ?: break
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
            ?: return DecoderState(codec, sequenceParameterSet, pictureParameterSet, needsIdr)
        if (needsIdr && !unit.hasIdr) {
            return DecoderState(codec, sequenceParameterSet, pictureParameterSet, needsIdr)
        }

        if (codec == null) {
            val sps = sequenceParameterSet
                ?: return DecoderState(null, sequenceParameterSet, pictureParameterSet, needsIdr)
            val pps = pictureParameterSet
                ?: return DecoderState(null, sequenceParameterSet, pictureParameterSet, needsIdr)
            codec = createDecoder(display, sps, pps)
            needsIdr = false
        }

        val activeCodec = codec
        drainOutput(activeCodec, display, frameRate)
        val inputIndex = activeCodec.dequeueInputBuffer(0)
        if (inputIndex < 0) return DecoderState(codec, sequenceParameterSet, pictureParameterSet, needsIdr)
        val input = activeCodec.getInputBuffer(inputIndex)
            ?: return DecoderState(codec, sequenceParameterSet, pictureParameterSet, needsIdr)
        if (unit.bytes.size > input.capacity()) {
            activeCodec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs(), 0)
            codec.releaseSafely()
            codec = null
            needsIdr = true
            return DecoderState(codec, sequenceParameterSet, pictureParameterSet, needsIdr)
        }
        input.clear()
        input.put(unit.bytes)
        activeCodec.queueInputBuffer(inputIndex, 0, unit.bytes.size, presentationTimeUs(), 0)
        drainOutput(activeCodec, display, frameRate)
        return DecoderState(codec, sequenceParameterSet, pictureParameterSet, needsIdr)
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
            }
        }
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
    )

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
    }
}
