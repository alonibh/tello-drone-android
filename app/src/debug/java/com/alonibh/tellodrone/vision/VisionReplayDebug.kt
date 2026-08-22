package com.alonibh.tellodrone.vision

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.alonibh.tellodrone.domain.DetectorBackend
import com.alonibh.tellodrone.domain.DetectorModel
import com.alonibh.tellodrone.tello.AnalysisFrameMetadata
import com.alonibh.tellodrone.tello.AnalysisPixelRepresentation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

internal data class ReplayModelConfiguration(
    val model: DetectorModel,
    val quantization: String,
)

internal val DEBUG_REPLAY_MODELS = listOf(
    ReplayModelConfiguration(DetectorModel.EfficientDetLite0, "INT8 post-training quantized"),
    ReplayModelConfiguration(DetectorModel.EfficientDetLite2Int8, "INT8 post-training quantized"),
)

internal class DebugVisionReplayManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var selected: VisionSessionContents? = null
    @Volatile private var lastReportJson: String? = null

    fun importSession(uriString: String, callback: (Result<VisionSessionSelection>) -> Unit) {
        scope.launch {
            val result = runCatching {
                val directory = File(context.cacheDir, "vision-session/imported").apply { mkdirs() }
                val destination = File(directory, "candidate-${System.nanoTime()}.zip")
                context.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                    FileOutputStream(destination, false).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_IMPORTED_SESSION_BYTES) {
                                throw MalformedVisionSessionException("Session ZIP exceeds the safe size limit")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                } ?: throw IllegalStateException("Could not open the selected session")
                val opened = try {
                    VisionSessionArchive.open(destination)
                } catch (error: Throwable) {
                    destination.delete()
                    throw error
                }
                selected?.archive?.takeIf { it != destination }?.delete()
                selected = opened
                lastReportJson = null
                VisionSessionSelection(opened.manifest.capturedFrameCount, opened.manifest.droppedFrameCount)
            }
            withContext(Dispatchers.Main) { callback(result) }
        }
    }

    fun runComparison(callback: (Result<VisionComparisonReport>) -> Unit) {
        val session = selected
        if (session == null) {
            callback(Result.failure(IllegalStateException("Import a vision session first")))
            return
        }
        scope.launch {
            val result = runCatching {
                val models = DEBUG_REPLAY_MODELS.map { runModel(session, it) }
                VisionComparisonReport(
                    sessionFrameCount = session.manifest.capturedFrameCount,
                    sessionDroppedFrameCount = session.manifest.droppedFrameCount,
                    models = models,
                ).also { lastReportJson = VisionComparisonReportJson.encode(it) }
            }
            withContext(Dispatchers.Main) { callback(result) }
        }
    }

    fun reportText(): String? = lastReportJson

    fun exportReport(destinationUri: String, callback: (Result<Unit>) -> Unit) {
        val report = lastReportJson
        if (report == null) {
            callback(Result.failure(IllegalStateException("Run model comparison first")))
            return
        }
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                context.contentResolver.openOutputStream(Uri.parse(destinationUri), "wt")?.bufferedWriter()?.use {
                    it.write(report)
                } ?: throw IllegalStateException("Could not open the report destination")
            }
            withContext(Dispatchers.Main) { callback(result) }
        }
    }

    private fun runModel(
        session: VisionSessionContents,
        configuration: ReplayModelConfiguration,
    ): VisionReplayModelResult {
        val startupStarted = System.nanoTime()
        val detector = TfliteTaskPersonDetector(context, configuration.model, DetectorBackend.Cpu)
        val startupNanos = (System.nanoTime() - startupStarted).coerceAtLeast(0L)
        return detector.use {
            val results = mutableListOf<VisionReplayFrameResult>()
            val association = VisionReplayAssociation()
            ZipFile(session.archive).use { zip ->
                session.orderedFrames.forEach { frame ->
                    val bitmap = zip.getInputStream(zip.getEntry(frame.file)).use(BitmapFactory::decodeStream)
                        ?: throw MalformedVisionSessionException("Could not decode ${frame.file}")
                    try {
                        if (bitmap.width != frame.width || bitmap.height != frame.height) {
                            throw MalformedVisionSessionException("Frame dimensions do not match ${frame.file}")
                        }
                        val metadata = AnalysisFrameMetadata(
                            width = bitmap.width,
                            height = bitmap.height,
                            captureTimestampNanos = frame.sourceTimestampNanos,
                            pixelRepresentation = AnalysisPixelRepresentation.ARGB_8888_BITMAP,
                            sequence = frame.frameSequence,
                        )
                        val started = System.nanoTime()
                        val output = detector.detectDetailed(PersonDetectorFrame(metadata) { bitmap })
                        val inferenceNanos = (System.nanoTime() - started).coerceAtLeast(0L)
                        val accepted = output.candidates.filter { it.confidence >= DEFAULT_PERSON_CONFIDENCE_THRESHOLD }
                        val traceSeed = session.traceSeeds[frame.frameSequence to frame.sourceTimestampNanos]?.selectedTargetBefore
                        val associationOutcome = association.evaluate(
                            frameSequence = frame.frameSequence,
                            sourceTimestampNanos = frame.sourceTimestampNanos,
                            detections = accepted,
                            recordedSelection = traceSeed,
                        )
                        results += VisionReplayFrameResult(
                            frameFile = frame.file,
                            frameSequence = frame.frameSequence,
                            sourceTimestampNanos = frame.sourceTimestampNanos,
                            inferenceNanos = inferenceNanos,
                            candidates = output.candidates,
                            acceptedDetections = accepted,
                            duplicateDetectionCount = output.duplicateDetectionCount,
                            associationState = associationOutcome.state,
                            selectedDetectionIndex = associationOutcome.selectedDetectionIndex,
                            identitySwitchSafetyViolation = associationOutcome.identitySwitchSafetyViolation,
                        )
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            VisionReplayModelResult(
                model = configuration.model.displayName,
                assetFile = configuration.model.assetFileName,
                quantization = configuration.quantization,
                backend = DetectorBackend.Cpu.name,
                confidenceThreshold = DEFAULT_PERSON_CONFIDENCE_THRESHOLD,
                startupNanos = startupNanos,
                frames = results,
            )
        }
    }

    companion object {
        private const val MAX_IMPORTED_SESSION_BYTES = 256L * 1024L * 1024L
    }
}
