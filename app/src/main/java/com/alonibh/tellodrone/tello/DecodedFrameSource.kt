package com.alonibh.tellodrone.tello

import android.graphics.Bitmap
import android.view.Surface

enum class AnalysisPixelRepresentation {
    ARGB_8888_BITMAP,
}

/** Immutable ordering and representation metadata for one decoded analysis frame. */
data class AnalysisFrameMetadata(
    val width: Int,
    val height: Int,
    val captureTimestampNanos: Long,
    val pixelRepresentation: AnalysisPixelRepresentation,
    val sequence: Long,
)

/**
 * A short-lived frame lease. Consumers may read [bitmap] only during their callback and must not
 * retain it. The source closes the lease after the callback and returns its bitmap to a fixed pool.
 */
interface DecodedVideoFrame : OrderedAnalysisFrame {
    val metadata: AnalysisFrameMetadata
    val bitmap: Bitmap
    override val sequence: Long get() = metadata.sequence
    override val captureTimestampNanos: Long get() = metadata.captureTimestampNanos
}

fun interface DecodedFrameConsumer {
    fun onFrame(frame: DecodedVideoFrame)
}

data class AnalysisFrameDiagnostics(
    val measuredFps: Float? = null,
    val latestCaptureTimestampNanos: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val latestSequence: Long? = null,
)

/**
 * Capture-technique boundary used by the service-owned decoder. Future vision code depends on
 * this contract, not on PixelCopy or the preview implementation.
 */
interface DecodedFrameSource {
    fun start(surface: Surface)
    fun stop(surface: Surface)
    fun onFrameRendered(captureTimestampNanos: Long)
    fun setConsumer(consumer: DecodedFrameConsumer?)
    suspend fun close()
}
