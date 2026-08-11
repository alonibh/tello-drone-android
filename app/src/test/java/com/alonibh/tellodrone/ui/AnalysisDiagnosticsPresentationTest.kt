package com.alonibh.tellodrone.ui

import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.VideoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisDiagnosticsPresentationTest {
    @Test fun `live preview formats real analysis measurements`() {
        val presentation = analysisDiagnosticsPresentation(
            video = VideoState(
                availability = VideoAvailability.Streaming,
                analysisMeasuredFps = 8f,
                analysisLatestCaptureTimestampNanos = 10_000_000_000L,
                analysisFrameWidth = 320,
                analysisFrameHeight = 240,
            ),
            previewSurfaceAttached = true,
            nowNanos = 10_035_000_000L,
        )

        assertEquals("8.0 FPS", presentation.rate)
        assertEquals("320 × 240", presentation.frame)
        assertEquals("35 ms", presentation.age)
        assertFalse(presentation.paused)
    }

    @Test fun `streaming session reports paused when preview surface is absent`() {
        val presentation = analysisDiagnosticsPresentation(
            video = VideoState(availability = VideoAvailability.Streaming, analysisMeasuredFps = 8f),
            previewSurfaceAttached = false,
            nowNanos = 1L,
        )

        assertEquals("Paused", presentation.rate)
        assertEquals("—", presentation.frame)
        assertEquals("—", presentation.age)
        assertTrue(presentation.paused)
    }

    @Test fun `unavailable measurements remain unavailable while preview is attached`() {
        val presentation = analysisDiagnosticsPresentation(VideoState(), previewSurfaceAttached = true, nowNanos = 1L)

        assertEquals("—", presentation.rate)
        assertEquals("—", presentation.frame)
        assertEquals("—", presentation.age)
        assertFalse(presentation.paused)
    }

    @Test fun `dashboard rows retain their character footprint across normal live values`() {
        val first = dashboardDiagnosticsRows(
            previewFps = 29f,
            analysis = AnalysisDiagnosticsPresentation("7.2 FPS", "320 × 240", "0 ms", paused = false),
        )
        val second = dashboardDiagnosticsRows(
            previewFps = 30f,
            analysis = AnalysisDiagnosticsPresentation("8.0 FPS", "320 × 240", "125 ms", paused = false),
        )
        val unavailable = dashboardDiagnosticsRows(
            previewFps = null,
            analysis = AnalysisDiagnosticsPresentation("—", "—", "—", paused = false),
        )

        assertEquals(first.preview.length, second.preview.length)
        assertEquals(first.preview.length, unavailable.preview.length)
        assertEquals(first.analysis.length, second.analysis.length)
        assertEquals(first.analysis.length, unavailable.analysis.length)
    }
}
