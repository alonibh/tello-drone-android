package com.alonibh.tellodrone.vision

import android.content.Context
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
import java.util.concurrent.atomic.AtomicLong

object VisionTraceFeature {
    const val isAvailable = true
    @Volatile private var instance: DebugVisionTraceRecorder? = null

    fun recorder(context: Context): VisionTraceRecorder = instance ?: synchronized(this) {
        instance ?: DebugVisionTraceRecorder(context.applicationContext).also { instance = it }
    }

    fun export(context: Context, destinationUri: String, onComplete: (Result<VisionTraceExport>) -> Unit) {
        recorder(context).export(destinationUri, onComplete)
    }
}

private class DebugVisionTraceRecorder(private val context: Context) : VisionTraceRecorder {
    override val capturesFrames = true
    private sealed interface Command {
        data class Record(val frame: VisionTraceFrame, val droppedBeforeFrame: Long) : Command
        data class Export(val destinationUri: String, val callback: (Result<VisionTraceExport>) -> Unit) : Command
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(capacity = 256)
    private val droppedFrames = AtomicLong()
    private var activeFile: File? = null
    private var writer: BufferedWriter? = null
    private var frameCount = 0L
    private var traceDroppedCount = 0L

    init {
        scope.launch {
            for (command in commands) when (command) {
                is Command.Record -> write(command)
                is Command.Export -> export(command)
            }
        }
    }

    override fun record(frame: VisionTraceFrame) {
        val dropped = droppedFrames.getAndSet(0L)
        if (!commands.trySend(Command.Record(frame, dropped)).isSuccess) {
            droppedFrames.addAndGet(dropped + 1L)
        }
    }

    override fun export(destinationUri: String, onComplete: (Result<VisionTraceExport>) -> Unit) {
        scope.launch { commands.send(Command.Export(destinationUri, onComplete)) }
    }

    private fun write(command: Command.Record) {
        ensureWriter()
        writer?.apply {
            write(VisionTraceJson.encode(command.frame, command.droppedBeforeFrame))
            newLine()
        }
        frameCount++
        traceDroppedCount += command.droppedBeforeFrame
    }

    private fun export(command: Command.Export) {
        val result = runCatching {
            writer?.flush()
            val source = activeFile ?: throw IllegalStateException("No analyzed vision frames are available to export")
            context.contentResolver.openOutputStream(Uri.parse(command.destinationUri), "wt")?.use { output ->
                source.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not open the selected export destination")
            VisionTraceExport(frameCount, traceDroppedCount + droppedFrames.get())
        }
        if (result.isSuccess) rotate()
        command.callback(result)
    }

    private fun ensureWriter() {
        if (writer != null) return
        val directory = File(context.cacheDir, "vision-traces").apply { mkdirs() }
        activeFile = File(directory, "active-vision-trace.jsonl")
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(activeFile, false), Charsets.UTF_8))
    }

    private fun rotate() {
        writer?.close()
        writer = null
        activeFile = null
        frameCount = 0L
        traceDroppedCount = 0L
        droppedFrames.set(0L)
    }
}
