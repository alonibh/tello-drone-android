package com.alonibh.tellodrone.vision

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
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

    fun export(context: Context, destinationUri: String, onComplete: (Result<VisionTraceExport>) -> Unit) {
        manager(context).export(destinationUri, onComplete)
    }

    fun exportSession(context: Context, destinationUri: String, onComplete: (Result<VisionSessionExport>) -> Unit) {
        manager(context).exportSession(destinationUri, onComplete)
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
        data class Pair(
            val trace: VisionTraceFrame,
            val bitmap: Bitmap,
            val droppedBeforeFrame: Long,
            val epoch: CaptureEpoch,
        ) : Command
        data class ExportTrace(val destinationUri: String, val callback: (Result<VisionTraceExport>) -> Unit) : Command
        data class ExportSession(
            val destinationUri: String,
            val epoch: CaptureEpoch,
            val callback: (Result<VisionSessionExport>) -> Unit,
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

    private var activeDirectory: File? = null
    private var traceFile: File? = null
    private var writer: BufferedWriter? = null
    private val frames = mutableListOf<VisionSessionFrameEntry>()
    private var activeEpoch: CaptureEpoch? = null

    init {
        scope.launch {
            for (command in commands) when (command) {
                is Command.Pair -> write(command)
                is Command.ExportTrace -> exportTrace(command)
                is Command.ExportSession -> exportSession(command)
            }
        }
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

    override fun onTargetSelected(target: com.alonibh.tellodrone.domain.TrackedTarget) {
        synchronized(pendingLock) {
            pending.values.forEach { it.bitmap.recycle() }
            pending.clear()
            currentEpoch = CaptureEpoch(nextGeneration++, VisionCaptureStartReason.TargetSelected)
        }
    }

    override fun record(frame: VisionTraceFrame) {
        synchronized(pendingLock) {
            val pendingFrame = pending.remove(FrameKey(frame.frameSequence, frame.sourceTimestampNanos))
                ?: return
            val dropped = pendingFrame.epoch.drops.consumeSinceLastPair()
            if (!commands.trySend(Command.Pair(frame, pendingFrame.bitmap, dropped, pendingFrame.epoch)).isSuccess) {
                pendingFrame.bitmap.recycle()
                pendingFrame.epoch.drops.restoreSinceLastPair(dropped)
                pendingFrame.epoch.drops.recordDrop()
            }
        }
    }

    override fun export(destinationUri: String, onComplete: (Result<VisionTraceExport>) -> Unit) {
        scope.launch { commands.send(Command.ExportTrace(destinationUri, onComplete)) }
    }

    fun exportSession(destinationUri: String, onComplete: (Result<VisionSessionExport>) -> Unit) {
        val epoch = pauseAndDropPending()
        scope.launch { commands.send(Command.ExportSession(destinationUri, epoch, onComplete)) }
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
            return
        }
        prepareEpoch(command.epoch)
        val directory = ensureSessionDirectory()
        val index = frames.size + 1
        val relativePath = "frames/${index.toString().padStart(6, '0')}.jpg"
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
        ensureWriter().apply {
            write(VisionTraceJson.encode(command.trace, command.droppedBeforeFrame, relativePath))
            newLine()
        }
    }

    private fun exportTrace(command: Command.ExportTrace) {
        val result = runCatching {
            writer?.flush()
            val source = traceFile ?: throw IllegalStateException("No captured vision frames are available")
            context.contentResolver.openOutputStream(Uri.parse(command.destinationUri), "wt")?.use { output ->
                source.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not open the selected export destination")
            VisionTraceExport(frames.size.toLong(), activeEpoch?.drops?.total() ?: 0L)
        }
        command.callback(result)
    }

    private fun exportSession(command: Command.ExportSession) {
        val result = runCatching {
            writer?.flush()
            val directory = activeDirectory ?: throw IllegalStateException("No captured vision frames are available")
            val sourceTrace = traceFile ?: throw IllegalStateException("No captured trace is available")
            if (frames.isEmpty()) throw IllegalStateException("No captured vision frames are available")
            if (activeEpoch !== command.epoch) throw IllegalStateException("No frames captured for the current session")
            val manifest = VisionSessionManifest(
                capturedFrameCount = frames.size,
                droppedFrameCount = command.epoch.drops.total(),
                excludedAfterLimitFrameCount = command.epoch.excludedAfterLimit.get(),
                captureStartReason = command.epoch.startReason,
                frames = frames.toList(),
            )
            context.contentResolver.openOutputStream(Uri.parse(command.destinationUri), "w")?.use { output ->
                ZipOutputStream(output.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(VisionSessionManifestJson.encode(manifest).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("trace.jsonl"))
                    sourceTrace.inputStream().buffered().use { it.copyTo(zip) }
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

    private fun prepareEpoch(epoch: CaptureEpoch) {
        if (activeEpoch === epoch) return
        resetWorkerStorage()
        activeEpoch = epoch
    }

    private fun ensureSessionDirectory(): File {
        activeDirectory?.let { return it }
        val directory = File(context.cacheDir, "vision-session/active")
        directory.deleteRecursively()
        directory.mkdirs()
        activeDirectory = directory
        traceFile = File(directory, "trace.jsonl")
        return directory
    }

    private fun ensureWriter(): BufferedWriter = writer ?: run {
        ensureSessionDirectory()
        BufferedWriter(OutputStreamWriter(FileOutputStream(traceFile!!, false), Charsets.UTF_8)).also { writer = it }
    }

    private fun resetWorkerStorage() {
        writer?.close()
        writer = null
        activeDirectory?.deleteRecursively()
        activeDirectory = null
        traceFile = null
        frames.clear()
    }

    private fun rotate(exportedEpoch: CaptureEpoch) {
        resetWorkerStorage()
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
        internal const val QUEUE_CAPACITY = 24
        internal const val MAX_PENDING_BITMAPS = 8
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
