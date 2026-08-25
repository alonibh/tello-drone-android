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

/** Compact operational badge; its padded numeric field keeps a stable footprint. */
internal fun previewFpsBadgeText(previewFps: Float?): String {
    val value = previewFps?.roundToInt()?.toString() ?: "—"
    return "FPS ${value.padStart(2)}"
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
// SPDX-License-Identifier: AGPL-3.0-only
