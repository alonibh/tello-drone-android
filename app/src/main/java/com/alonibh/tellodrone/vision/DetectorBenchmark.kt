package com.alonibh.tellodrone.vision

import com.alonibh.tellodrone.domain.DetectorBackend
import com.alonibh.tellodrone.domain.DetectorBackendPreference

/** Pure, monotonic benchmark aggregation. The first three completed inferences are warm-up. */
data class DetectorBenchmarkResult(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkLevel: Int,
    val supportedAbis: List<String>,
    val availableProcessors: Int,
    val requestedBackend: DetectorBackendPreference,
    val actualBackend: DetectorBackend?,
    val fellBackFromGpu: Boolean,
    val detectorModel: String?,
    val startupMillis: Long?,
    val durationMillis: Long,
    val completedInferences: Int,
    val steadyStateInferences: Int,
    val inferenceMinMillis: Long?,
    val inferenceP50Millis: Long?,
    val inferenceP95Millis: Long?,
    val inferenceMaxMillis: Long?,
    val detectorFps: Float?,
    val previewFps: Float?,
    val analysisFrameFps: Float?,
)

data class BenchmarkDeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkLevel: Int,
    val supportedAbis: List<String>,
    val availableProcessors: Int,
)

object DetectorBenchmarkMath {
    fun percentileMillis(samples: List<Long>, percentile: Double): Long? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        val index = kotlin.math.ceil(percentile.coerceIn(0.0, 1.0) * (sorted.size - 1)).toInt()
        return sorted[index]
    }
}

class DetectorBenchmarkAggregator(
    private val device: BenchmarkDeviceInfo,
    private val requestedBackend: DetectorBackendPreference,
    private val startedAtNanos: Long,
    private val warmupInferences: Int = WARMUP_INFERENCES,
) {
    private var steadyStateStartedAtNanos: Long? = null
    private var startupNanos: Long? = null
    private var descriptor: PersonDetectorDescriptor? = null
    private var completed = 0
    private val steadySamplesNanos = mutableListOf<Long>()
    private var previewFrames = 0
    private var firstPreviewAtNanos: Long? = null
    private var latestPreviewAtNanos: Long? = null
    private var analysisFrames = 0
    private var firstAnalysisAtNanos: Long? = null
    private var latestAnalysisAtNanos: Long? = null

    fun onInference(completedAtNanos: Long, inferenceNanos: Long, startupNanos: Long?, descriptor: PersonDetectorDescriptor) {
        if (this.startupNanos == null) this.startupNanos = startupNanos
        this.descriptor = descriptor
        completed++
        if (completed == warmupInferences) steadyStateStartedAtNanos = completedAtNanos
        if (completed > warmupInferences) steadySamplesNanos += inferenceNanos.coerceAtLeast(0L)
    }

    fun onPreviewRendered(nowNanos: Long) {
        if (steadyStateStartedAtNanos == null) return
        if (firstPreviewAtNanos == null) firstPreviewAtNanos = nowNanos
        latestPreviewAtNanos = nowNanos
        previewFrames++
    }

    fun onAnalysisFrame(nowNanos: Long) {
        if (steadyStateStartedAtNanos == null) return
        if (firstAnalysisAtNanos == null) firstAnalysisAtNanos = nowNanos
        latestAnalysisAtNanos = nowNanos
        analysisFrames++
    }

    fun isComplete(nowNanos: Long): Boolean = steadyStateStartedAtNanos?.let { nowNanos - it >= BENCHMARK_DURATION_NANOS } == true

    fun result(endedAtNanos: Long): DetectorBenchmarkResult {
        val samplesMillis = steadySamplesNanos.map { it / NANOS_PER_MILLI }
        val durationNanos = steadyStateStartedAtNanos?.let { (endedAtNanos - it).coerceAtLeast(0L) } ?: 0L
        return DetectorBenchmarkResult(
            manufacturer = device.manufacturer, model = device.model, androidVersion = device.androidVersion,
            sdkLevel = device.sdkLevel, supportedAbis = device.supportedAbis, availableProcessors = device.availableProcessors,
            requestedBackend = requestedBackend, actualBackend = descriptor?.backend, fellBackFromGpu = descriptor?.fellBackFromGpu == true,
            detectorModel = descriptor?.modelName, startupMillis = startupNanos?.div(NANOS_PER_MILLI),
            durationMillis = durationNanos / NANOS_PER_MILLI, completedInferences = completed,
            steadyStateInferences = steadySamplesNanos.size,
            inferenceMinMillis = samplesMillis.minOrNull(), inferenceP50Millis = DetectorBenchmarkMath.percentileMillis(samplesMillis, .50),
            inferenceP95Millis = DetectorBenchmarkMath.percentileMillis(samplesMillis, .95), inferenceMaxMillis = samplesMillis.maxOrNull(),
            detectorFps = rate(steadySamplesNanos.size, durationNanos), previewFps = rate(previewFrames, elapsed(firstPreviewAtNanos, latestPreviewAtNanos)),
            analysisFrameFps = rate(analysisFrames, elapsed(firstAnalysisAtNanos, latestAnalysisAtNanos)),
        )
    }

    private fun elapsed(first: Long?, last: Long?): Long = if (first == null || last == null) 0L else (last - first).coerceAtLeast(0L)
    private fun rate(frames: Int, elapsedNanos: Long): Float? = if (frames < 2 || elapsedNanos <= 0L) null else frames * 1_000_000_000f / elapsedNanos
    companion object { const val WARMUP_INFERENCES = 3; const val BENCHMARK_DURATION_NANOS = 30_000_000_000L; private const val NANOS_PER_MILLI = 1_000_000L }
}

fun DetectorBenchmarkResult.formatReport(): String = buildString {
    appendLine("TELLO DETECTOR BENCHMARK")
    appendLine("DEVICE: $manufacturer $model")
    appendLine("ANDROID: $androidVersion / API $sdkLevel")
    appendLine("ABI: ${supportedAbis.joinToString().ifBlank { "Unavailable" }}")
    appendLine("PROCESSORS: $availableProcessors")
    appendLine("MODEL: ${detectorModel ?: "Unavailable"}")
    appendLine("REQUESTED: ${requestedBackend.name}")
    appendLine("ACTUAL: ${actualBackend?.name ?: "Unavailable"}")
    appendLine("GPU FALLBACK: ${if (fellBackFromGpu) "YES" else "NO"}")
    appendLine("STARTUP: ${startupMillis?.let { "$it ms" } ?: "Unavailable"}")
    appendLine("DURATION: $durationMillis ms")
    appendLine("FRAMES: $completedInferences total; $steadyStateInferences steady-state (after 3 warm-up)")
    appendLine("INFERENCE: min ${inferenceMinMillis?.let { "$it ms" } ?: "Unavailable"}; p50 ${inferenceP50Millis?.let { "$it ms" } ?: "Unavailable"}; p95 ${inferenceP95Millis?.let { "$it ms" } ?: "Unavailable"}; max ${inferenceMaxMillis?.let { "$it ms" } ?: "Unavailable"}")
    appendLine("DETECTOR FPS: ${detectorFps?.let { "%.1f".format(it) } ?: "Unavailable"}")
    appendLine("PREVIEW FPS: ${previewFps?.let { "%.1f".format(it) } ?: "Unavailable"}")
    append("ANALYSIS FPS: ${analysisFrameFps?.let { "%.1f".format(it) } ?: "Unavailable"}")
}
