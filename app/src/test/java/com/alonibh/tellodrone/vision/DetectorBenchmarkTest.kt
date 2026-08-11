package com.alonibh.tellodrone.vision

import com.alonibh.tellodrone.domain.DetectorBackend
import com.alonibh.tellodrone.domain.DetectorBackendPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorBenchmarkTest {
    private val device = BenchmarkDeviceInfo("Test", "Tablet", "15", 35, listOf("arm64-v8a"), 8)
    private val descriptor = PersonDetectorDescriptor("SSD MobileNet V1", DetectorBackend.Gpu)

    @Test fun percentileHandlesZeroAndOneSample() {
        assertNull(DetectorBenchmarkMath.percentileMillis(emptyList(), .50))
        assertEquals(42L, DetectorBenchmarkMath.percentileMillis(listOf(42), .95))
        assertEquals(100L, DetectorBenchmarkMath.percentileMillis(listOf(10, 50, 95, 100), .95))
    }

    @Test fun warmupIsExcludedAndAggregationUsesSteadySamples() {
        val benchmark = DetectorBenchmarkAggregator(device, DetectorBackendPreference.Accelerated, 0)
        listOf(900L, 800L, 700L, 10L, 20L).forEachIndexed { index, millis ->
            benchmark.onInference((index + 1) * 1_000_000_000L, millis * 1_000_000L, if (index == 0) 100_000_000L else null, descriptor)
        }
        val result = benchmark.result(6_000_000_000L)
        assertEquals(10L, result.inferenceMinMillis)
        assertEquals(20L, result.inferenceP50Millis)
        assertEquals(20L, result.inferenceP95Millis)
        assertEquals(5, result.completedInferences)
        assertEquals(100L, result.startupMillis)
    }

    @Test fun completionUsesThirtySecondsOfValidInferenceTime() {
        val benchmark = DetectorBenchmarkAggregator(device, DetectorBackendPreference.Cpu, 0)
        benchmark.onInference(5_000_000_000L, 10, 1, descriptor)
        assertFalse(benchmark.isComplete(34_999_999_999L))
        assertTrue(benchmark.isComplete(35_000_000_000L))
    }

    @Test fun backendFallbackAndUnavailableMetricsAreReportedHonestly() {
        val empty = DetectorBenchmarkAggregator(device, DetectorBackendPreference.Accelerated, 0).result(1)
        assertNull(empty.actualBackend)
        assertNull(empty.inferenceP50Millis)
        assertTrue(empty.formatReport().contains("ACTUAL: Unavailable"))
        assertTrue(empty.formatReport().contains("INFERENCE: min Unavailable"))
        val fallback = DetectorBenchmarkAggregator(device, DetectorBackendPreference.Accelerated, 0)
        fallback.onInference(1, 1, 1, descriptor.copy(backend = DetectorBackend.Cpu, fellBackFromGpu = true))
        assertTrue(fallback.result(2).fellBackFromGpu)
    }

    @Test fun cancellationFailureAndVideoLossCanReturnPartialButNotInventedResult() {
        val benchmark = DetectorBenchmarkAggregator(device, DetectorBackendPreference.Cpu, 0)
        benchmark.onInference(1_000_000_000L, 25_000_000L, 5_000_000L, PersonDetectorDescriptor("SSD MobileNet V1", DetectorBackend.Cpu))
        val cancelled = benchmark.result(2_000_000_000L)
        assertEquals(1, cancelled.completedInferences)
        assertNull(cancelled.inferenceP50Millis) // only warm-up exists after cancellation/failure/video loss
        assertNull(cancelled.previewFps)
        assertNull(cancelled.analysisFrameFps)
    }
}
