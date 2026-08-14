package com.alonibh.tellodrone.vision

import com.alonibh.tellodrone.domain.NormalizedBoundingBox
import com.alonibh.tellodrone.domain.DetectorBackend
import com.alonibh.tellodrone.domain.DetectorBackendPreference
import com.alonibh.tellodrone.domain.DetectorModel
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.tello.AnalysisFrameMetadata
import com.alonibh.tellodrone.tello.AnalysisPixelRepresentation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class PersonDetectionPipelineTest {
    @Test fun `fake detector result is published and zero result clears immediately`() {
        var result = listOf(detection(sourceTimestamp = 100L))
        val fake = FakePersonDetector { result }
        val snapshots = mutableListOf<PersonDetectionSnapshot>()
        var now = 200L
        val pipeline = PersonDetectionPipeline(
            detectorFactory = { fake },
            modelName = "fake-model",
            clockNanos = { now },
            onSnapshot = snapshots::add,
        )

        pipeline.start()
        pipeline.process(frame())
        assertEquals(1, snapshots.last().detections.size)

        result = emptyList()
        now = 300L
        pipeline.process(frame())
        assertEquals(PersonDetectionState.Detecting, snapshots.last().state)
        assertTrue(snapshots.last().detections.isEmpty())
        assertEquals(1L, snapshots.last().processedFrameSequence)
        assertEquals(100L, snapshots.last().processedSourceTimestampNanos)
    }

    @Test fun `runtime threshold filters detections below configured threshold`() {
        val result = listOf(
            detection(sourceTimestamp = 100L, confidence = 0.55f),
            detection(sourceTimestamp = 100L, confidence = 0.75f),
            detection(sourceTimestamp = 100L, confidence = 0.85f),
        )
        val fake = FakePersonDetector { result }
        val snapshots = mutableListOf<PersonDetectionSnapshot>()
        val pipeline = PersonDetectionPipeline(
            detectorFactory = { fake },
            modelName = "fake-model",
            onSnapshot = snapshots::add,
        )

        pipeline.start(confidenceThreshold = 0.70f)
        pipeline.process(frame())

        assertEquals(2, snapshots.last().detections.size)
        assertEquals(listOf(0.75f, 0.85f), snapshots.last().detections.map { it.confidence })

        pipeline.stop()
        pipeline.start(confidenceThreshold = 0.80f)
        pipeline.process(frame())

        assertEquals(1, snapshots.last().detections.size)
        assertEquals(listOf(0.85f), snapshots.last().detections.map { it.confidence })
    }

    @Test fun `changing confidence threshold does not recreate detector instance`() {
        var createCount = 0
        val fake = FakePersonDetector { emptyList() }
        val pipeline = PersonDetectionPipeline(
            detectorFactory = {
                createCount++
                fake
            },
            modelName = "fake-model",
            onSnapshot = {},
        )

        pipeline.start(confidenceThreshold = 0.50f)
        pipeline.process(frame())
        assertEquals(1, createCount)

        pipeline.stop()
        pipeline.setConfidenceThreshold(0.75f)
        pipeline.start()
        pipeline.process(frame())
        assertEquals(1, createCount)
    }

    @Test fun `stale result expires at five hundred milliseconds and off clears state`() {
        val store = PersonDetectionStore()
        store.start("fake-model")
        store.result(listOf(detection(sourceTimestamp = 1_000_000_000L)), 1L, 1_000_000_000L, 7f, 20L, descriptor())

        assertEquals(1, store.expire(1_499_999_999L).detections.size)
        assertTrue(store.expire(1_500_000_000L).detections.isEmpty())

        store.result(listOf(detection(sourceTimestamp = 2_000_000_000L)), 1L, 2_000_000_000L, 7f, 20L, descriptor())
        val off = store.stop()
        assertEquals(PersonDetectionState.Off, off.state)
        assertTrue(off.detections.isEmpty())
    }

    @Test fun `fake detector failure disables detection and exposes concise error`() {
        val snapshots = mutableListOf<PersonDetectionSnapshot>()
        val pipeline = PersonDetectionPipeline(
            detectorFactory = { FakePersonDetector { error("bad model") } },
            modelName = "fake-model",
            clockNanos = { 10L },
            onSnapshot = snapshots::add,
        )

        pipeline.start()
        pipeline.process(frame())

        assertEquals(PersonDetectionState.Error, snapshots.last().state)
        assertTrue(snapshots.last().detections.isEmpty())
        assertTrue(snapshots.last().errorReason!!.contains("bad model"))
    }

    @Test fun `off clears immediately and consumer-thread release closes detector`() {
        val fake = FakePersonDetector { emptyList() }
        val snapshots = mutableListOf<PersonDetectionSnapshot>()
        val pipeline = PersonDetectionPipeline(
            detectorFactory = { fake },
            modelName = "fake-model",
            onSnapshot = snapshots::add,
        )

        pipeline.start(DetectorBackendPreference.Cpu)
        pipeline.process(frame())
        pipeline.stop()

        assertEquals(PersonDetectionState.Off, snapshots.last().state)
        assertEquals(0, fake.closeCount)
        pipeline.releaseIfStopped()
        assertEquals(1, fake.closeCount)
    }

    @Test fun `stale accelerated creation is discarded before CPU detector publishes`() {
        staleCreationIsDiscarded(
            stalePreference = DetectorBackendPreference.Accelerated,
            activePreference = DetectorBackendPreference.Cpu,
        )
    }

    @Test fun `stale CPU creation is discarded before accelerated detector publishes`() {
        staleCreationIsDiscarded(
            stalePreference = DetectorBackendPreference.Cpu,
            activePreference = DetectorBackendPreference.Accelerated,
        )
    }

    @Test fun `cancel stops detection and discards a late detector creation result`() {
        val creationStarted = CountDownLatch(1)
        val allowCreation = CountDownLatch(1)
        val detector = FakePersonDetector { listOf(detection(100L)) }
        val snapshots = mutableListOf<PersonDetectionSnapshot>()
        val pipeline = PersonDetectionPipeline(
            detectorFactory = {
                creationStarted.countDown()
                assertTrue(allowCreation.await(2, TimeUnit.SECONDS))
                detector
            },
            modelName = "fake-model",
            onSnapshot = snapshots::add,
        )
        pipeline.start()
        val worker = thread(start = true) { pipeline.process(frame()) }
        assertTrue(creationStarted.await(2, TimeUnit.SECONDS))

        pipeline.stop() // benchmark cancellation uses this same generation-safe stop.
        allowCreation.countDown()
        worker.join(2_000)
        pipeline.releaseIfStopped()

        assertFalse(worker.isAlive)
        assertEquals(PersonDetectionState.Off, snapshots.last().state)
        assertTrue(snapshots.none { it.state == PersonDetectionState.Detecting })
        assertEquals(1, detector.closeCount)
    }

    private fun staleCreationIsDiscarded(
        stalePreference: DetectorBackendPreference,
        activePreference: DetectorBackendPreference,
    ) {
        val creationStarted = CountDownLatch(1)
        val allowStaleCreationToFinish = CountDownLatch(1)
        val createdPreferences = mutableListOf<DetectorBackendPreference>()
        val staleDetector = FakePersonDetector(backendFor(stalePreference)) { emptyList() }
        val activeDetector = FakePersonDetector(backendFor(activePreference)) { emptyList() }
        val snapshots = mutableListOf<PersonDetectionSnapshot>()
        val pipeline = PersonDetectionPipeline(
            detectorFactory = { requestedPreference ->
                synchronized(createdPreferences) { createdPreferences += requestedPreference }
                if (requestedPreference == stalePreference) {
                    creationStarted.countDown()
                    assertTrue(allowStaleCreationToFinish.await(2, TimeUnit.SECONDS))
                    staleDetector
                } else activeDetector
            },
            modelName = "fake-model",
            onSnapshot = snapshots::add,
        )
        val workerFailure = AtomicReference<Throwable?>()

        pipeline.start(stalePreference)
        val worker = thread(start = true) {
            runCatching { pipeline.process(frame()) }.onFailure(workerFailure::set)
        }
        assertTrue(creationStarted.await(2, TimeUnit.SECONDS))

        pipeline.stop()
        pipeline.start(activePreference)
        allowStaleCreationToFinish.countDown()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertNull(workerFailure.get())
        assertEquals(1, staleDetector.closeCount)
        assertEquals(listOf(stalePreference), synchronized(createdPreferences) { createdPreferences.toList() })
        assertTrue(snapshots.none { it.backend == backendFor(stalePreference) })

        pipeline.process(frame())

        assertEquals(listOf(stalePreference, activePreference), synchronized(createdPreferences) { createdPreferences.toList() })
        assertEquals(backendFor(activePreference), snapshots.last().backend)
        assertEquals("fake-model", snapshots.last().modelName)
        assertEquals(0, activeDetector.closeCount)
    }

    @Test fun `switching model recreates detector only when detection next starts`() {
        val createdModels = mutableListOf<DetectorModel>()
        val snapshots = mutableListOf<PersonDetectionSnapshot>()
        val pipeline = PersonDetectionPipeline(
            detectorFactory = { model, pref ->
                createdModels += model
                FakePersonDetector(backendFor(pref), modelName = model.displayName) { emptyList() }
            },
            onSnapshot = snapshots::add,
        )

        pipeline.start(DetectorModel.MobileNetV1)
        pipeline.process(frame())
        assertEquals(listOf(DetectorModel.MobileNetV1), createdModels)
        assertEquals(DetectorModel.MobileNetV1.displayName, snapshots.last().modelName)

        pipeline.stop()
        pipeline.setDetectorModel(DetectorModel.EfficientDetLite0)
        // Detector was not recreated immediately while stopped
        assertEquals(listOf(DetectorModel.MobileNetV1), createdModels)

        pipeline.start()
        pipeline.process(frame())
        // Recreated on start when processing frame
        assertEquals(listOf(DetectorModel.MobileNetV1, DetectorModel.EfficientDetLite0), createdModels)
        assertEquals(DetectorModel.EfficientDetLite0.displayName, snapshots.last().modelName)
    }

    private fun frame() = PersonDetectorFrame(
        AnalysisFrameMetadata(320, 240, 100L, AnalysisPixelRepresentation.ARGB_8888_BITMAP, 1L),
    ) { error("Fake detector must not request bitmap pixels") }

    private fun detection(sourceTimestamp: Long, confidence: Float = .8f) = PersonDetection(
        NormalizedBoundingBox(.1f, .2f, .4f, .8f),
        confidence,
        1L,
        sourceTimestamp,
    )

    private fun descriptor() = PersonDetectorDescriptor("fake-model", DetectorBackend.Cpu)

    private fun backendFor(preference: DetectorBackendPreference) = when (preference) {
        DetectorBackendPreference.Accelerated -> DetectorBackend.Gpu
        DetectorBackendPreference.Cpu -> DetectorBackend.Cpu
    }

    private class FakePersonDetector(
        backend: DetectorBackend = DetectorBackend.Cpu,
        modelName: String = "fake-model",
        private val result: () -> List<PersonDetection>,
    ) : PersonDetector {
        override val descriptor = PersonDetectorDescriptor(modelName, backend)
        var closeCount = 0
        override fun detect(frame: PersonDetectorFrame) = result()
        override fun close() { closeCount++ }
    }
}
