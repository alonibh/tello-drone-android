package com.alonibh.tellodrone.vision

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
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
    private data class PendingFrame(val bitmap: Bitmap)
    private sealed interface Command {
        data class Pair(val trace: VisionTraceFrame, val bitmap: Bitmap, val droppedBeforeFrame: Long) : Command
        data class ExportTrace(val destinationUri: String, val callback: (Result<VisionTraceExport>) -> Unit) : Command
        data class ExportSession(val destinationUri: String, val callback: (Result<VisionSessionExport>) -> Unit) : Command
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(capacity = QUEUE_CAPACITY)
    private val pendingLock = Any()
    private val pending = linkedMapOf<FrameKey, PendingFrame>()
    private val limiter = VisionCaptureLimiter()
    private var capturePaused = false
    private val drops = VisionCaptureDropCounter()

    private var activeDirectory: File? = null
    private var traceFile: File? = null
    private var writer: BufferedWriter? = null
    private val frames = mutableListOf<VisionSessionFrameEntry>()

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
            if (!limiter.tryReserve(sourceTimestampNanos)) {
                countDrop()
                return
            }
            if (pending.size >= MAX_PENDING_BITMAPS) {
                val oldest = pending.entries.firstOrNull()
                if (oldest != null) {
                    pending.remove(oldest.key)?.bitmap?.recycle()
                    countDrop()
                }
            }
            val detached = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            if (detached == null) {
                countDrop()
                return
            }
            pending.put(key, PendingFrame(detached))?.bitmap?.recycle()
        }
    }

    override fun record(frame: VisionTraceFrame) {
        synchronized(pendingLock) {
            val detached = pending.remove(FrameKey(frame.frameSequence, frame.sourceTimestampNanos))?.bitmap
                ?: return
            val dropped = drops.consumeSinceLastPair()
            if (!commands.trySend(Command.Pair(frame, detached, dropped)).isSuccess) {
                detached.recycle()
                drops.restoreSinceLastPair(dropped)
                countDrop()
            }
        }
    }

    override fun export(destinationUri: String, onComplete: (Result<VisionTraceExport>) -> Unit) {
        scope.launch { commands.send(Command.ExportTrace(destinationUri, onComplete)) }
    }

    fun exportSession(destinationUri: String, onComplete: (Result<VisionSessionExport>) -> Unit) {
        pauseAndDropPending()
        scope.launch { commands.send(Command.ExportSession(destinationUri, onComplete)) }
    }

    private fun pauseAndDropPending() = synchronized(pendingLock) {
        capturePaused = true
        pending.values.forEach { it.bitmap.recycle(); countDrop() }
        pending.clear()
    }

    private fun countDrop() {
        drops.recordDrop()
    }

    private fun write(command: Command.Pair) {
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
            drops.restoreSinceLastPair(command.droppedBeforeFrame)
            countDrop()
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
            VisionTraceExport(frames.size.toLong(), drops.total())
        }
        command.callback(result)
    }

    private fun exportSession(command: Command.ExportSession) {
        val result = runCatching {
            writer?.flush()
            val directory = activeDirectory ?: throw IllegalStateException("No captured vision frames are available")
            val sourceTrace = traceFile ?: throw IllegalStateException("No captured trace is available")
            if (frames.isEmpty()) throw IllegalStateException("No captured vision frames are available")
            val manifest = VisionSessionManifest(
                capturedFrameCount = frames.size,
                droppedFrameCount = drops.total(),
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
            VisionSessionExport(frames.size, drops.total(), duration.coerceAtLeast(0L))
        }
        if (result.isSuccess) rotate() else synchronized(pendingLock) { capturePaused = false }
        command.callback(result)
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

    private fun rotate() {
        writer?.close()
        writer = null
        activeDirectory?.deleteRecursively()
        activeDirectory = null
        traceFile = null
        frames.clear()
        drops.reset()
        synchronized(pendingLock) {
            pending.values.forEach { it.bitmap.recycle() }
            pending.clear()
            limiter.reset()
            capturePaused = false
        }
    }

    companion object {
        internal const val QUEUE_CAPACITY = 8
        internal const val MAX_PENDING_BITMAPS = 4
    }
}
