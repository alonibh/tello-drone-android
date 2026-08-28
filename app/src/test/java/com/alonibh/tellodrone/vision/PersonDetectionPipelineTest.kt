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
    @Test fun `production start uses YOLO11n LiteRT FP32 CPU at thirty percent`() {
        val requests = mutableListOf<Pair<DetectorModel, DetectorBackendPreference>>()
        val snapshots = mutableListOf<PersonDetectionSnapshot>()
        val pipeline = PersonDetectionPipeline(
            detectorFactory = { model, preference ->
                requests += model to preference
                FakePersonDetector(backendFor(preference), model.displayName) {
                    listOf(
                        detection(sourceTimestamp = 100L, confidence = .29f),
                        detection(sourceTimestamp = 100L, confidence = .30f),
                    )
                }
            },
            onSnapshot = snapshots::add,
        )

        pipeline.startProductionDetection()
        pipeline.process(frame())

        assertEquals(
            listOf(DetectorModel.Yolo11nLiteRtFloat32 to DetectorBackendPreference.Cpu),
            requests,
        )
        assertEquals(DetectorBackend.Cpu, snapshots.last().backend)
        assertEquals(DetectorModel.Yolo11nLiteRtFloat32.displayName, snapshots.last().modelName)
        assertEquals(listOf(.30f), snapshots.last().detections.map { it.confidence })
    }

    @Test fun `completed inference measurement supplies grounded benchmark metrics`() {
        val measurements = mutableListOf<DetectorInferenceMeasurement>()
        var now = 0L
        val pipeline = PersonDetectionPipeline(
            detectorFactory = { model, _ ->
                now = 12_000_000L // detector creation completes
                FakePersonDetector(modelName = model.displayName) {
                    now += 8_000_000L // detector inference completes
                    emptyList()
                }
            },
            clockNanos = { now },
            onSnapshot = {},
            onInferenceMeasurement = measurements::add,
        )

        pipeline.startProductionDetection()
        pipeline.process(frame())
        now = 2_012_000_000L
        pipeline.process(frame())

        assertEquals(2, measurements.size)
        assertEquals(DetectorModel.Yolo11nLiteRtFloat32.displayName, measurements.last().descriptor.modelName)
        assertEquals(12L, measurements.last().initializationMillis)
        assertEquals(2L, measurements.last().analyzedFrames)
        assertTrue(measurements.last().inferenceP50Millis != null)
        assertTrue(measurements.last().inferenceP95Millis != null)
        assertTrue(measurements.last().measuredFps != null)
    }

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
        assertEquals(3, snapshots.last().candidates.size)
        assertEquals(.70f, snapshots.last().confidenceThreshold)
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
        store.result(
            candidates = listOf(detection(sourceTimestamp = 1_000_000_000L)),
            detections = listOf(detection(sourceTimestamp = 1_000_000_000L)),
            processedFrameSequence = 1L,
            processedSourceTimestampNanos = 1_000_000_000L,
            measuredFps = 7f,
            inferenceMillis = 20L,
            descriptor = descriptor(),
            confidenceThreshold = .55f,
        )

        assertEquals(1, store.expire(1_499_999_999L).detections.size)
        assertTrue(store.expire(1_500_000_000L).detections.isEmpty())

        store.result(
            candidates = listOf(detection(sourceTimestamp = 2_000_000_000L)),
            detections = listOf(detection(sourceTimestamp = 2_000_000_000L)),
            processedFrameSequence = 1L,
            processedSourceTimestampNanos = 2_000_000_000L,
            measuredFps = 7f,
            inferenceMillis = 20L,
            descriptor = descriptor(),
            confidenceThreshold = .55f,
        )
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

        pipeline.stop()
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

    @Test fun `capture hook receives the exact frame object passed to detection`() {
        var captured: PersonDetectorFrame? = null
        var detected: PersonDetectorFrame? = null
        val pipeline = PersonDetectionPipeline(
            detectorFactory = { _, _ -> object : PersonDetector {
                override val descriptor = descriptor()
                override fun detect(frame: PersonDetectorFrame): List<PersonDetection> {
                    detected = frame
                    return emptyList()
                }
                override fun close() = Unit
            } },
            onSnapshot = {},
            onAnalyzedFrame = { captured = it },
        )
        val input = frame()

        pipeline.start()
        pipeline.process(input)

        assertTrue(captured === input)
        assertTrue(detected === input)
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
// SPDX-License-Identifier: AGPL-3.0-only
