package com.alonibh.tellodrone.ui

import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.VideoState
import kotlin.math.roundToInt

internal data class AnalysisDiagnosticsPresentation(
    val rate: String,
    val frame: String,
    val age: String,
    val paused: Boolean,
)

internal data class DashboardDiagnosticsRows(
    val preview: String,
    val analysis: String,
)

internal fun dashboardDiagnosticsRows(
    previewFps: Float?,
    analysis: AnalysisDiagnosticsPresentation,
): DashboardDiagnosticsRows {
    val previewValue = previewFps?.let { "%2d".format(it.roundToInt()) } ?: " —"
    return DashboardDiagnosticsRows(
        preview = "PREVIEW $previewValue FPS",
        analysis = "ANALYSIS ${analysis.rate.padStart(8)} · ${analysis.frame.padEnd(9)} · ${analysis.age.padStart(7)}",
    )
}

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
