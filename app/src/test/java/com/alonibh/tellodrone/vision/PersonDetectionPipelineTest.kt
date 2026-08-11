package com.alonibh.tellodrone.vision

import com.alonibh.tellodrone.domain.NormalizedBoundingBox
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.tello.AnalysisFrameMetadata
import com.alonibh.tellodrone.tello.AnalysisPixelRepresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonDetectionPipelineTest {
    @Test fun `fake detector result is published and zero result clears immediately`() {
        var result = listOf(detection(sourceTimestamp = 100L))
        val fake = FakePersonDetector { result }
        val snapshots = mutableListOf<PersonDetectionSnapshot>()
        var now = 200L
        val pipeline = PersonDetectionPipeline({ fake }, { now }, snapshots::add)

        pipeline.start()
        pipeline.process(frame())
        assertEquals(1, snapshots.last().detections.size)

        result = emptyList()
        now = 300L
        pipeline.process(frame())
        assertEquals(PersonDetectionState.Detecting, snapshots.last().state)
        assertTrue(snapshots.last().detections.isEmpty())
    }

    @Test fun `stale result expires at five hundred milliseconds and off clears state`() {
        val store = PersonDetectionStore()
        store.start()
        store.result(listOf(detection(sourceTimestamp = 1_000_000_000L)), 7f, 20L)

        assertEquals(1, store.expire(1_499_999_999L).detections.size)
        assertTrue(store.expire(1_500_000_000L).detections.isEmpty())

        store.result(listOf(detection(sourceTimestamp = 2_000_000_000L)), 7f, 20L)
        val off = store.stop()
        assertEquals(PersonDetectionState.Off, off.state)
        assertTrue(off.detections.isEmpty())
    }

    @Test fun `fake detector failure disables detection and exposes concise error`() {
        val snapshots = mutableListOf<PersonDetectionSnapshot>()
        val pipeline = PersonDetectionPipeline(
            detectorFactory = { FakePersonDetector { error("bad model") } },
            clockNanos = { 10L },
            onSnapshot = snapshots::add,
        )

        pipeline.start()
        pipeline.process(frame())

        assertEquals(PersonDetectionState.Error, snapshots.last().state)
        assertTrue(snapshots.last().detections.isEmpty())
        assertTrue(snapshots.last().errorReason!!.contains("bad model"))
    }

    private fun frame() = PersonDetectorFrame(
        AnalysisFrameMetadata(320, 240, 100L, AnalysisPixelRepresentation.ARGB_8888_BITMAP, 1L),
    ) { error("Fake detector must not request bitmap pixels") }

    private fun detection(sourceTimestamp: Long) = PersonDetection(
        NormalizedBoundingBox(.1f, .2f, .4f, .8f),
        .8f,
        1L,
        sourceTimestamp,
    )

    private class FakePersonDetector(
        private val result: () -> List<PersonDetection>,
    ) : PersonDetector {
        override fun detect(frame: PersonDetectorFrame) = result()
        override fun close() = Unit
    }
}
