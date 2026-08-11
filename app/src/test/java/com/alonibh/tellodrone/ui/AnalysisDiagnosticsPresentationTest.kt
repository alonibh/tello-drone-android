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

    @Test fun `preview badge keeps a stable footprint across measured and unavailable values`() {
        val first = previewFpsBadgeText(29f)
        val second = previewFpsBadgeText(30f)
        val unavailable = previewFpsBadgeText(null)

        assertEquals("FPS 29", first)
        assertEquals("FPS 30", second)
        assertEquals("FPS  —", unavailable)
        assertEquals(first.length, second.length)
        assertEquals(first.length, unavailable.length)
    }
}
