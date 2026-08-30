package com.alonibh.tellodrone.vision

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.alonibh.tellodrone.domain.TrackedTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object VisionTraceFeature {
    const val isAvailable = true
    @Volatile private var instance: DebugVisionTraceRecorder? = null

    internal fun manager(context: Context): DebugVisionTraceRecorder = instance ?: synchronized(this) {
        instance ?: DebugVisionTraceRecorder(context.applicationContext).also { instance = it }
    }

    fun recorder(context: Context): VisionTraceRecorder = manager(context)

    fun startNewSession(context: Context) {
        manager(context).startNewSession()
    }

    fun export(context: Context, destinationUri: String, onComplete: (Result<VisionTraceExport>) -> Unit) {
        manager(context).export(destinationUri, onComplete)
    }

    fun exportSession(context: Context, destinationUri: String, onComplete: (Result<VisionSessionExport>) -> Unit) {
        manager(context).exportSession(destinationUri, onComplete)
    }

    fun exportFlightDiagnostics(context: Context, destinationUri: String, onComplete: (Result<FlightDiagnosticsExport>) -> Unit) {
        manager(context).exportFlightDiagnostics(destinationUri, onComplete)
    }
}

internal class DebugVisionTraceRecorder(private val context: Context) : VisionTraceRecorder {
    override val capturesFrames = true

    private data class FrameKey(val sequence: Long, val timestampNanos: Long)
    private class CaptureEpoch(
        val generation: Long,
        val startReason: VisionCaptureStartReason,
        val limiter: VisionCaptureLimiter = VisionCaptureLimiter(),
        val drops: VisionCaptureDropCounter = VisionCaptureDropCounter(),
        val excludedAfterLimit: AtomicLong = AtomicLong(),
    )
    private data class PendingFrame(val bitmap: Bitmap, val epoch: CaptureEpoch)
    private sealed interface Command {
        data object StartNewSession : Command
        data class Pair(
            val trace: VisionTraceFrame,
            val bitmap: Bitmap,
            val droppedBeforeFrame: Long,
            val epoch: CaptureEpoch,
        ) : Command
        data class ControlMeasurement(val trace: YawControlMeasurementTrace) : Command
        data class RcPublication(val trace: RcPublicationTrace) : Command
        data class SdkCommand(val trace: SdkCommandTrace) : Command
        data class FlightTransition(val trace: FlightStateTransitionTrace) : Command
        data class ExternalGrounding(val trace: ExternalGroundingTrace) : Command
        data class TargetSelectionAttempt(val trace: TargetSelectionAttemptTrace) : Command
        data class CorruptFrame(val trace: CorruptFrameTrace) : Command
        data class VideoDiagnostic(val trace: VideoDiagnosticTrace) : Command
        data class ExportTrace(val destinationUri: String, val callback: (Result<VisionTraceExport>) -> Unit) : Command
        data class ExportSession(
            val destinationUri: String,
            val epoch: CaptureEpoch,
            val callback: (Result<VisionSessionExport>) -> Unit,
        ) : Command
        data class ExportFlightDiagnostics(
            val destinationUri: String,
            val callback: (Result<FlightDiagnosticsExport>) -> Unit,
        ) : Command
    }

    private val encoderDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vision-session-encoder").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + encoderDispatcher)
    private val commands = Channel<Command>(capacity = QUEUE_CAPACITY)
    private val pendingLock = Any()
    private val pending = linkedMapOf<FrameKey, PendingFrame>()
    private var nextGeneration = 1L
    @Volatile private var currentEpoch = CaptureEpoch(0L, VisionCaptureStartReason.DetectionStarted)
    private var capturePaused = false

    private val storage = DiagnosticSessionFiles(File(context.cacheDir, "vision-session"))
    private val frames = mutableListOf<VisionSessionFrameEntry>()
    private var activeEpoch: CaptureEpoch? = null

    // Flight diagnostics tracking
    private val transitions = mutableListOf<FlightStateTransitionTrace>()
    private val sdkCommands = mutableListOf<SdkCommandTrace>()
    private val externalGroundings = mutableListOf<ExternalGroundingTrace>()
    private var rcPublicationCount = 0L
    private var isAirborne = false
    private var lastAirborneOutboundTimeMillis: Long? = null
    private var maxAirborneOutboundGapMillis: Long? = null
    private var lastAirborneRcTimeMillis: Long? = null
    private var maxAirborneRcGapMillis: Long? = null

    init {
        scope.launch {
            for (command in commands) when (command) {
                is Command.StartNewSession -> resetWorkerStorage()
                is Command.Pair -> write(command)
                is Command.ControlMeasurement -> write(command)
                is Command.RcPublication -> write(command)
                is Command.SdkCommand -> write(command)
                is Command.FlightTransition -> write(command)
                is Command.ExternalGrounding -> write(command)
                is Command.TargetSelectionAttempt -> write(command)
                is Command.CorruptFrame -> write(command)
                is Command.VideoDiagnostic -> write(command)
                is Command.ExportTrace -> exportTrace(command)
                is Command.ExportSession -> exportSession(command)
                is Command.ExportFlightDiagnostics -> exportFlightDiagnostics(command)
            }
        }
    }

    override fun startNewSession() {
        synchronized(pendingLock) {
            pending.values.forEach { it.bitmap.recycle() }
            pending.clear()
            currentEpoch = CaptureEpoch(nextGeneration++, VisionCaptureStartReason.DetectionStarted)
            capturePaused = false
        }
        commands.trySend(Command.StartNewSession)
    }

    override fun captureAnalyzedFrame(frameSequence: Long, sourceTimestampNanos: Long, bitmap: Bitmap) {
        val key = FrameKey(frameSequence, sourceTimestampNanos)
        synchronized(pendingLock) {
            if (capturePaused) return
            val epoch = currentEpoch
            when (epoch.limiter.reserve(sourceTimestampNanos)) {
                VisionCaptureReservation.Accepted -> Unit
                VisionCaptureReservation.FrameLimitReached,
                VisionCaptureReservation.DurationLimitReached -> {
                    epoch.excludedAfterLimit.incrementAndGet()
                    return
                }
                VisionCaptureReservation.InvalidTimestamp -> {
                    epoch.drops.recordDrop()
                    return
                }
            }
            if (pending.size >= MAX_PENDING_BITMAPS) {
                val oldest = pending.entries.firstOrNull()
                if (oldest != null) {
                    pending.remove(oldest.key)?.let { dropped ->
                        dropped.bitmap.recycle()
                        dropped.epoch.drops.recordDrop()
                    }
                }
            }
            val detached = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            if (detached == null) {
                epoch.drops.recordDrop()
                return
            }
            pending.put(key, PendingFrame(detached, epoch))?.let { replaced ->
                replaced.bitmap.recycle()
                replaced.epoch.drops.recordDrop()
            }
        }
    }

    override fun onTargetSelected(target: TrackedTarget) {
        synchronized(pendingLock) {
            pending.values.forEach { it.bitmap.recycle() }
            pending.clear()
            currentEpoch = CaptureEpoch(nextGeneration++, VisionCaptureStartReason.TargetSelected)
            capturePaused = false
        }
    }

    override fun record(frame: VisionTraceFrame) {
        val key = FrameKey(frame.frameSequence, frame.sourceTimestampNanos)
        val pendingFrame = synchronized(pendingLock) { pending.remove(key) } ?: return
        val dropped = pendingFrame.epoch.drops.consumeSinceLastPair()
        if (capturePaused) {
            pendingFrame.bitmap.recycle()
            pendingFrame.epoch.drops.restoreSinceLastPair(dropped)
            return
        }
        if (!commands.trySend(Command.Pair(frame, pendingFrame.bitmap, dropped, pendingFrame.epoch)).isSuccess) {
            pendingFrame.bitmap.recycle()
            pendingFrame.epoch.drops.restoreSinceLastPair(dropped)
            pendingFrame.epoch.drops.recordDrop()
        }
    }

    override fun recordControlMeasurement(trace: YawControlMeasurementTrace) {
        commands.trySend(Command.ControlMeasurement(trace))
    }

    override fun recordRcPublication(trace: RcPublicationTrace) {
        commands.trySend(Command.RcPublication(trace))
    }

    override fun recordSdkCommand(trace: SdkCommandTrace) {
        commands.trySend(Command.SdkCommand(trace))
    }

    override fun recordFlightStateTransition(trace: FlightStateTransitionTrace) {
        commands.trySend(Command.FlightTransition(trace))
    }

    override fun recordExternalGrounding(trace: ExternalGroundingTrace) {
        commands.trySend(Command.ExternalGrounding(trace))
    }

    override fun recordTargetSelectionAttempt(trace: TargetSelectionAttemptTrace) {
        commands.trySend(Command.TargetSelectionAttempt(trace))
    }

    override fun recordCorruptFrame(trace: CorruptFrameTrace) {
        commands.trySend(Command.CorruptFrame(trace))
    }

    override fun recordVideoDiagnostic(trace: VideoDiagnosticTrace) {
        commands.trySend(Command.VideoDiagnostic(trace))
    }

    override fun export(destinationUri: String, onComplete: (Result<VisionTraceExport>) -> Unit) {
        scope.launch { commands.send(Command.ExportTrace(destinationUri, onComplete)) }
    }

    fun exportSession(destinationUri: String, onComplete: (Result<VisionSessionExport>) -> Unit) {
        val epoch = pauseAndDropPending()
        scope.launch { commands.send(Command.ExportSession(destinationUri, epoch, onComplete)) }
    }

    override fun exportFlightDiagnostics(destinationUri: String, onComplete: (Result<FlightDiagnosticsExport>) -> Unit) {
        scope.launch { commands.send(Command.ExportFlightDiagnostics(destinationUri, onComplete)) }
    }

    private fun pauseAndDropPending(): CaptureEpoch = synchronized(pendingLock) {
        capturePaused = true
        pending.values.forEach { pendingFrame ->
            pendingFrame.bitmap.recycle()
            pendingFrame.epoch.drops.recordDrop()
        }
        pending.clear()
        currentEpoch
    }

    private fun write(command: Command.Pair) {
        if (command.epoch.generation < currentEpoch.generation) {
            command.bitmap.recycle()
            command.epoch.drops.recordDrop()
            return
        }
        prepareEpoch(command.epoch)
        val directory = storage.directory
        val index = frames.size
        val relativePath = "frames/%06d.jpg".format(index)
        val frameFile = File(directory, relativePath)
        frameFile.parentFile?.mkdirs()
        val width = command.bitmap.width
        val height = command.bitmap.height
        val wrote = try {
            FileOutputStream(frameFile).use { output ->
                command.bitmap.compress(Bitmap.CompressFormat.JPEG, VISION_SESSION_JPEG_QUALITY, output)
            }
        } finally {
            command.bitmap.recycle()
        }
        if (!wrote) {
            frameFile.delete()
            command.epoch.drops.restoreSinceLastPair(command.droppedBeforeFrame)
            command.epoch.drops.recordDrop()
            return
        }
        val entry = VisionSessionFrameEntry(
            captureIndex = index,
            frameSequence = command.trace.frameSequence,
            sourceTimestampNanos = command.trace.sourceTimestampNanos,
            file = relativePath,
            width = width,
            height = height,
        )
        frames += entry
        storage.appendTrace(VisionTraceJson.encode(command.trace, command.droppedBeforeFrame, relativePath))
    }

    private fun write(command: Command.ControlMeasurement) {
        storage.appendControl(VisionTraceJson.encodeControlMeasurement(command.trace))
    }

    private fun write(command: Command.RcPublication) {
        rcPublicationCount++
        if (isAirborne) {
            val timeMillis = command.trace.commandTimestampNanos / 1_000_000L
            val prevOutbound = lastAirborneOutboundTimeMillis
            if (prevOutbound != null) {
                val gap = (timeMillis - prevOutbound).coerceAtLeast(0L)
                maxAirborneOutboundGapMillis = maxOf(maxAirborneOutboundGapMillis ?: 0L, gap)
            }
            lastAirborneOutboundTimeMillis = timeMillis
            val prevRc = lastAirborneRcTimeMillis
            if (prevRc != null) {
                val rcGap = (timeMillis - prevRc).coerceAtLeast(0L)
                maxAirborneRcGapMillis = maxOf(maxAirborneRcGapMillis ?: 0L, rcGap)
            }
            lastAirborneRcTimeMillis = timeMillis
        }
        storage.appendControl(VisionTraceJson.encodeRcPublication(command.trace))
    }

    private fun write(command: Command.SdkCommand) {
        sdkCommands += command.trace
        if (isAirborne) {
            val prev = lastAirborneOutboundTimeMillis
            if (prev != null) {
                val gap = (command.trace.sentAtMonotonicMillis - prev).coerceAtLeast(0L)
                maxAirborneOutboundGapMillis = maxOf(maxAirborneOutboundGapMillis ?: 0L, gap)
            }
            lastAirborneOutboundTimeMillis = command.trace.sentAtMonotonicMillis
        }
        storage.appendControl(VisionTraceJson.encodeSdkCommand(command.trace))
    }

    private fun write(command: Command.FlightTransition) {
        transitions += command.trace
        if (command.trace.toState == "Flying") {
            isAirborne = true
            lastAirborneOutboundTimeMillis = command.trace.timestampMillis
            lastAirborneRcTimeMillis = command.trace.timestampMillis
        } else if (command.trace.toState in setOf("Grounded", "Landing", "Emergency", "Unknown")) {
            isAirborne = false
        }
        storage.appendControl(VisionTraceJson.encodeFlightStateTransition(command.trace))
    }

    private fun write(command: Command.ExternalGrounding) {
        externalGroundings += command.trace
        storage.appendControl(VisionTraceJson.encodeExternalGrounding(command.trace))
    }

    private fun write(command: Command.TargetSelectionAttempt) {
        storage.appendControl(VisionTraceJson.encodeTargetSelectionAttempt(command.trace))
    }

    private fun write(command: Command.CorruptFrame) {
        storage.appendControl(VisionTraceJson.encodeCorruptFrame(command.trace))
    }

    private fun write(command: Command.VideoDiagnostic) {
        storage.appendControl(VisionTraceJson.encodeVideoDiagnostic(command.trace))
    }

    private fun exportTrace(command: Command.ExportTrace) {
        val result = runCatching {
            storage.flush()
            val directory = storage.directory
            val sourceTrace = storage.traceFile.takeIf { it.exists() && it.length() > 0 }
            val controlSource = storage.controlFile
            val traceLines = sourceTrace?.readLines(Charsets.UTF_8) ?: emptyList()
            val controlLines = if (controlSource.exists()) controlSource.readLines(Charsets.UTF_8) else emptyList()
            val summary = FlightSummaryBuilder.build(traceLines, controlLines)

            context.contentResolver.openOutputStream(Uri.parse(command.destinationUri), "w")?.use { output ->
                ZipOutputStream(output.buffered()).use { zip ->
                    if (frames.isNotEmpty()) {
                        val manifest = VisionSessionManifest(
                            capturedFrameCount = frames.size,
                            droppedFrameCount = activeEpoch?.drops?.total() ?: 0L,
                            excludedAfterLimitFrameCount = activeEpoch?.excludedAfterLimit?.get() ?: 0L,
                            captureStartReason = activeEpoch?.startReason ?: VisionCaptureStartReason.TargetSelected,
                            frames = frames.toList(),
                        )
                        zip.putNextEntry(ZipEntry("manifest.json"))
                        zip.write(VisionSessionManifestJson.encode(manifest).toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                    if (sourceTrace != null) {
                        zip.putNextEntry(ZipEntry("trace.jsonl"))
                        sourceTrace.inputStream().buffered().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry("control.jsonl"))
                    controlSource.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("session.json"))
                    storage.sessionFile.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("flight_summary.json"))
                    zip.write(FlightSummaryBuilder.json(summary).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("flight_summary.txt"))
                    zip.write(FlightSummaryBuilder.text(summary).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    if (frames.isNotEmpty()) {
                        frames.forEach { frame ->
                            val frameFile = File(directory, frame.file)
                            if (frameFile.exists()) {
                                zip.putNextEntry(ZipEntry(frame.file))
                                frameFile.inputStream().buffered().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                }
            } ?: throw IllegalStateException("Could not open the selected export destination")
            VisionTraceExport(frames.size.toLong(), activeEpoch?.drops?.total() ?: 0L)
        }
        command.callback(result)
    }

    private fun exportSession(command: Command.ExportSession) {
        val result = runCatching {
            storage.flush()
            val directory = storage.directory
            val sourceTrace = storage.traceFile
            if (frames.isEmpty()) throw IllegalStateException("No captured vision frames are available")
            if (activeEpoch !== command.epoch) throw IllegalStateException("No frames captured for the current session")
            val manifest = VisionSessionManifest(
                capturedFrameCount = frames.size,
                droppedFrameCount = command.epoch.drops.total(),
                excludedAfterLimitFrameCount = command.epoch.excludedAfterLimit.get(),
                captureStartReason = command.epoch.startReason,
                frames = frames.toList(),
            )
            val summary = FlightSummaryBuilder.build(
                sourceTrace.readLines(Charsets.UTF_8),
                storage.controlFile.readLines(Charsets.UTF_8),
            )
            context.contentResolver.openOutputStream(Uri.parse(command.destinationUri), "w")?.use { output ->
                ZipOutputStream(output.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(VisionSessionManifestJson.encode(manifest).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("trace.jsonl"))
                    sourceTrace.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("control.jsonl"))
                    storage.controlFile.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("session.json"))
                    storage.sessionFile.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("flight_summary.json"))
                    zip.write(FlightSummaryBuilder.json(summary).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("flight_summary.txt"))
                    zip.write(FlightSummaryBuilder.text(summary).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    frames.forEach { frame ->
                        zip.putNextEntry(ZipEntry(frame.file))
                        File(directory, frame.file).inputStream().buffered().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } ?: throw IllegalStateException("Could not open the selected export destination")
            val duration = frames.last().sourceTimestampNanos - frames.first().sourceTimestampNanos
            VisionSessionExport(
                frames.size,
                command.epoch.drops.total(),
                command.epoch.excludedAfterLimit.get(),
                duration.coerceAtLeast(0L),
            )
        }
        if (result.isSuccess) rotate(command.epoch) else synchronized(pendingLock) { capturePaused = false }
        command.callback(result)
    }

    private fun exportFlightDiagnostics(command: Command.ExportFlightDiagnostics) {
        val result = runCatching {
            storage.flush()
            val content = buildString {
                appendLine("{")
                appendLine("  \"schemaVersion\": 1,")
                appendLine("  \"exportedAtMonotonicMillis\": ${System.currentTimeMillis()},")
                appendLine("  \"rcPublicationsCount\": $rcPublicationCount,")
                appendLine("  \"maxAirborneOutboundGapMillis\": ${maxAirborneOutboundGapMillis ?: "null"},")
                appendLine("  \"maxAirborneRcGapMillis\": ${maxAirborneRcGapMillis ?: "null"},")
                appendLine("  \"transitionsCount\": ${transitions.size},")
                appendLine("  \"sdkCommandsCount\": ${sdkCommands.size},")
                appendLine("  \"externalGroundingsCount\": ${externalGroundings.size},")
                appendLine("  \"transitions\": [")
                transitions.forEachIndexed { i, t ->
                    append("    ").append(VisionTraceJson.encodeFlightStateTransition(t))
                    if (i < transitions.size - 1) appendLine(",") else appendLine()
                }
                appendLine("  ],")
                appendLine("  \"sdkCommands\": [")
                sdkCommands.forEachIndexed { i, c ->
                    append("    ").append(VisionTraceJson.encodeSdkCommand(c))
                    if (i < sdkCommands.size - 1) appendLine(",") else appendLine()
                }
                appendLine("  ],")
                appendLine("  \"externalGroundings\": [")
                externalGroundings.forEachIndexed { i, g ->
                    append("    ").append(VisionTraceJson.encodeExternalGrounding(g))
                    if (i < externalGroundings.size - 1) appendLine(",") else appendLine()
                }
                appendLine("  ]")
                appendLine("}")
            }
            context.contentResolver.openOutputStream(Uri.parse(command.destinationUri), "w")?.use { output ->
                output.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
            } ?: throw IllegalStateException("Could not open destination URI for flight diagnostics export")
            FlightDiagnosticsExport(
                transitionsCount = transitions.size,
                commandsCount = sdkCommands.size,
                rcCount = rcPublicationCount,
                maxAirborneOutboundGapMillis = maxAirborneOutboundGapMillis,
                maxAirborneRcGapMillis = maxAirborneRcGapMillis,
            )
        }
        command.callback(result)
    }

    private fun prepareEpoch(epoch: CaptureEpoch) {
        if (activeEpoch === epoch) return
        resetVisionEpochStorage()
        activeEpoch = epoch
    }

    private fun resetVisionEpochStorage() {
        storage.startVisionEpoch()
        frames.clear()
    }

    private fun resetWorkerStorage() {
        storage.startNewSession()
        frames.clear()
        activeEpoch = null
        rcPublicationCount = 0
        maxAirborneOutboundGapMillis = null
        maxAirborneRcGapMillis = null
        lastAirborneOutboundTimeMillis = null
        lastAirborneRcTimeMillis = null
        isAirborne = false
        transitions.clear()
        sdkCommands.clear()
        externalGroundings.clear()
    }

    private fun rotate(exportedEpoch: CaptureEpoch) {
        resetVisionEpochStorage()
        activeEpoch = null
        synchronized(pendingLock) {
            pending.values.forEach { it.bitmap.recycle() }
            pending.clear()
            if (currentEpoch === exportedEpoch) {
                currentEpoch = CaptureEpoch(nextGeneration++, VisionCaptureStartReason.DetectionStarted)
            }
            capturePaused = false
        }
    }

    companion object {
        internal const val QUEUE_CAPACITY = 128
        internal const val MAX_PENDING_BITMAPS = 8
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
