package com.alonibh.tellodrone.vision

import com.alonibh.tellodrone.domain.DetectorBackend
import com.alonibh.tellodrone.domain.DetectorBackendPreference
import com.alonibh.tellodrone.domain.DetectorModel
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.tello.AnalysisFrameMetadata
import com.alonibh.tellodrone.tello.AnalysisPixelRepresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackPersonDetectorFactoryTest {
    @Test fun `accelerated selection keeps a working GPU detector`() {
        val created = mutableListOf<DetectorBackend>()
        val factory = FallbackPersonDetectorFactory { _, backend ->
            created += backend
            FakeDetector(backend)
        }

        val detector = factory.create(DetectorBackendPreference.Accelerated)

        assertEquals(listOf(DetectorBackend.Gpu), created)
        assertEquals(DetectorBackend.Gpu, detector.descriptor.backend)
        assertFalse(detector.descriptor.fellBackFromGpu)
    }

    @Test fun `GPU initialization failure falls back to CPU`() {
        val created = mutableListOf<DetectorBackend>()
        val factory = FallbackPersonDetectorFactory { _, backend ->
            created += backend
            if (backend == DetectorBackend.Gpu) error("delegate unavailable")
            FakeDetector(backend)
        }

        val detector = factory.create(DetectorBackendPreference.Accelerated)

        assertEquals(listOf(DetectorBackend.Gpu, DetectorBackend.Cpu), created)
        assertEquals(DetectorBackend.Cpu, detector.descriptor.backend)
        assertTrue(detector.descriptor.fellBackFromGpu)
    }

    @Test fun `GPU runtime failure closes GPU and retries the same frame on CPU`() {
        lateinit var gpu: FakeDetector
        val cpu = FakeDetector(DetectorBackend.Cpu, result = listOf(detection()))
        val factory = FallbackPersonDetectorFactory { _, backend ->
            if (backend == DetectorBackend.Gpu) {
                FakeDetector(backend, failure = IllegalStateException("delegate runtime failure")).also { gpu = it }
            } else cpu
        }
        val detector = factory.create(DetectorBackendPreference.Accelerated)

        assertEquals(1, detector.detect(frame()).size)
        assertEquals(1, gpu.closeCount)
        assertEquals(DetectorBackend.Cpu, detector.descriptor.backend)
        assertTrue(detector.descriptor.fellBackFromGpu)
    }

    @Test fun `CPU comparison selection never attempts GPU`() {
        val created = mutableListOf<DetectorBackend>()
        val detector = FallbackPersonDetectorFactory { _, backend ->
            created += backend
            FakeDetector(backend)
        }.create(DetectorBackendPreference.Cpu)

        detector.detect(frame())

        assertEquals(listOf(DetectorBackend.Cpu), created)
        assertEquals(DetectorBackend.Cpu, detector.descriptor.backend)
        assertFalse(detector.descriptor.fellBackFromGpu)
    }

    @Test fun `factory forwards requested model to creator`() {
        val created = mutableListOf<Pair<DetectorModel, DetectorBackend>>()
        val factory = FallbackPersonDetectorFactory { model, backend ->
            created += model to backend
            FakeDetector(backend, modelName = model.displayName)
        }

        val detectorMobileNet = factory.create(DetectorModel.MobileNetV1, DetectorBackendPreference.Cpu)
        assertEquals(DetectorModel.MobileNetV1.displayName, detectorMobileNet.descriptor.modelName)

        val detectorEfficient = factory.create(DetectorModel.EfficientDetLite0, DetectorBackendPreference.Cpu)
        assertEquals(DetectorModel.EfficientDetLite0.displayName, detectorEfficient.descriptor.modelName)

        assertEquals(
            listOf(
                DetectorModel.MobileNetV1 to DetectorBackend.Cpu,
                DetectorModel.EfficientDetLite0 to DetectorBackend.Cpu,
            ),
            created,
        )
    }

    private class FakeDetector(
        backend: DetectorBackend,
        modelName: String = "fake-model",
        private val failure: Throwable? = null,
        private val result: List<PersonDetection> = emptyList(),
    ) : PersonDetector {
        override val descriptor = PersonDetectorDescriptor(modelName, backend)
        var closeCount = 0
        override fun detect(frame: PersonDetectorFrame): List<PersonDetection> {
            failure?.let { throw it }
            return result
        }
        override fun close() { closeCount++ }
    }

    private fun frame() = PersonDetectorFrame(
        AnalysisFrameMetadata(320, 240, 1L, AnalysisPixelRepresentation.ARGB_8888_BITMAP, 1L),
    ) { error("Fake detector must not request pixels") }

    private fun detection() = PersonDetection(
        boundingBox = com.alonibh.tellodrone.domain.NormalizedBoundingBox(.1f, .1f, .2f, .4f),
        confidence = .8f,
        frameSequence = 1L,
        sourceTimestampNanos = 1L,
    )
}
