package com.alonibh.tellodrone.vision

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.UUID

/**
 * Owns one clean diagnostic session across multiple vision epochs.
 * Both trace.jsonl and control.jsonl are session-scoped and preserved continuously.
 */
internal class DiagnosticSessionFiles(
    private val rootDirectory: File,
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
) : Closeable {
    private var traceWriter: BufferedWriter? = null
    private var controlWriter: BufferedWriter? = null
    private var activeDirectory: File? = null

    var sessionId: String = ""
        private set

    init {
        startNewSession()
    }

    @Synchronized
    fun startNewSession() {
        closeWriters()
        val directory = File(rootDirectory, ACTIVE_DIRECTORY_NAME)
        if (directory.exists()) directory.deleteRecursively()
        check(directory.mkdirs() || directory.isDirectory) { "Could not create diagnostic session directory" }
        activeDirectory = directory
        sessionId = sessionIdFactory()
        File(directory, SESSION_FILE_NAME).writeText(
            "{\"sessionId\":\"${sessionId.replace("\"", "\\\"")}\"}\n",
            Charsets.UTF_8,
        )
        check(controlFile.createNewFile() || controlFile.isFile) { "Could not create control.jsonl" }
    }

    @Synchronized
    fun startVisionEpoch() {
        traceWriter?.flush()
        if (!framesDirectory.exists()) {
            check(framesDirectory.mkdirs() || framesDirectory.isDirectory) { "Could not create frames directory" }
        }
        if (!traceFile.exists()) {
            check(traceFile.createNewFile() || traceFile.isFile) { "Could not create trace.jsonl" }
        }
    }

    @Synchronized
    fun appendTrace(line: String) {
        val active = traceWriter ?: BufferedWriter(
            OutputStreamWriter(FileOutputStream(traceFile, true), Charsets.UTF_8),
        ).also { traceWriter = it }
        active.write(line)
        active.newLine()
    }

    @Synchronized
    fun appendControl(line: String) {
        val active = controlWriter ?: BufferedWriter(
            OutputStreamWriter(FileOutputStream(controlFile, true), Charsets.UTF_8),
        ).also { controlWriter = it }
        active.write(line)
        active.newLine()
    }

    @Synchronized
    fun flush() {
        traceWriter?.flush()
        controlWriter?.flush()
    }

    val directory: File
        @Synchronized get() = checkNotNull(activeDirectory)
    val traceFile: File get() = File(directory, TRACE_FILE_NAME)
    val controlFile: File get() = File(directory, CONTROL_FILE_NAME)
    val framesDirectory: File get() = File(directory, FRAMES_DIRECTORY_NAME)
    val sessionFile: File get() = File(directory, SESSION_FILE_NAME)

    @Synchronized
    override fun close() {
        closeWriters()
    }

    private fun closeWriters() {
        traceWriter?.close()
        traceWriter = null
        controlWriter?.close()
        controlWriter = null
    }

    private companion object {
        const val ACTIVE_DIRECTORY_NAME = "active"
        const val TRACE_FILE_NAME = "trace.jsonl"
        const val CONTROL_FILE_NAME = "control.jsonl"
        const val FRAMES_DIRECTORY_NAME = "frames"
        const val SESSION_FILE_NAME = "session.json"
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
