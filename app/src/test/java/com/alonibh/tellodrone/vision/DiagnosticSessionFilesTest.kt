package com.alonibh.tellodrone.vision

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSessionFilesTest {
    @Test fun `target selection rotates vision files while control remains continuous and export stays valid`() {
        val root = Files.createTempDirectory("tello-diagnostic-session").toFile()
        val storage = DiagnosticSessionFiles(root) { "session-a" }
        try {
            storage.appendControl("connect")
            storage.startVisionEpoch()
            storage.appendTrace("pre-selection-frame")
            java.io.File(storage.framesDirectory, "000000.jpg").writeBytes(byteArrayOf(1))
            storage.appendControl("takeoff")

            storage.startVisionEpoch()
            assertTrue(storage.traceFile.isFile)
            assertTrue(storage.framesDirectory.isDirectory)
            assertEquals(listOf("pre-selection-frame"), storage.traceFile.readLines())

            storage.appendControl("target-selection")
            storage.appendTrace("post-selection-frame")
            java.io.File(storage.framesDirectory, "000001.jpg").writeBytes(byteArrayOf(2, 3))
            storage.appendControl("follow")
            storage.flush()

            assertEquals(listOf("connect", "takeoff", "target-selection", "follow"), storage.controlFile.readLines())
            assertEquals(listOf("pre-selection-frame", "post-selection-frame"), storage.traceFile.readLines())

            val bundle = java.io.File(root, "trace.zip")
            ZipOutputStream(bundle.outputStream()).use { zip ->
                listOf(
                    "session.json" to storage.sessionFile,
                    "control.jsonl" to storage.controlFile,
                    "trace.jsonl" to storage.traceFile,
                    "frames/000000.jpg" to java.io.File(storage.framesDirectory, "000000.jpg"),
                    "frames/000001.jpg" to java.io.File(storage.framesDirectory, "000001.jpg"),
                ).forEach { (name, file) ->
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            ZipFile(bundle).use { zip ->
                assertEquals(
                    setOf("session.json", "control.jsonl", "trace.jsonl", "frames/000000.jpg", "frames/000001.jpg"),
                    zip.entries().asSequence().map { it.name }.toSet(),
                )
                val traceLines = zip.getInputStream(zip.getEntry("trace.jsonl")).bufferedReader().readLines()
                assertEquals(listOf("pre-selection-frame", "post-selection-frame"), traceLines)
            }
        } finally {
            storage.close()
            root.deleteRecursively()
        }
    }

    @Test fun `new diagnostic session never appends previous process or flight control log`() {
        val root = Files.createTempDirectory("tello-diagnostic-reset").toFile()
        var id = 0
        val storage = DiagnosticSessionFiles(root) { "session-${++id}" }
        try {
            storage.appendControl("old-session-command")
            storage.flush()
            val firstSessionId = storage.sessionId

            storage.startNewSession()
            storage.appendControl("new-session-command")
            storage.flush()

            assertFalse(storage.controlFile.readText().contains("old-session-command"))
            assertEquals(listOf("new-session-command"), storage.controlFile.readLines())
            assertTrue(storage.sessionId != firstSessionId)
        } finally {
            storage.close()
            root.deleteRecursively()
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
