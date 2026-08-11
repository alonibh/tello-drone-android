package com.alonibh.tellodrone.ui

import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.VideoState

internal data class AnalysisDiagnosticsPresentation(
    val rate: String,
    val frame: String,
    val age: String,
    val paused: Boolean,
)

internal fun analysisDiagnosticsPresentation(
    video: VideoState,
    previewSurfaceAttached: Boolean,
    nowNanos: Long,
): AnalysisDiagnosticsPresentation {
    if (video.availability == VideoAvailability.Streaming && !previewSurfaceAttached) {
        return AnalysisDiagnosticsPresentation(rate = "Paused", frame = "—", age = "—", paused = true)
    }
    val ageMillis = video.analysisLatestCaptureTimestampNanos?.let { capturedAt ->
        ((nowNanos - capturedAt).coerceAtLeast(0L) / 1_000_000L)
    }
    return AnalysisDiagnosticsPresentation(
        rate = video.analysisMeasuredFps?.let { "%.1f FPS".format(it) } ?: "—",
        frame = if (video.analysisFrameWidth != null && video.analysisFrameHeight != null) {
            "${video.analysisFrameWidth} × ${video.analysisFrameHeight}"
        } else "—",
        age = ageMillis?.let { "$it ms" } ?: "—",
        paused = false,
    )
}
