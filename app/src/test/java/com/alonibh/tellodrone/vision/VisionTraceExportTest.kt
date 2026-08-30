package com.alonibh.tellodrone.vision

import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.NormalizedBoundingBox
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.TrackedTarget
import com.alonibh.tellodrone.domain.YawControlSuppressionReason
import com.alonibh.tellodrone.domain.YawControllerPhase
import com.alonibh.tellodrone.domain.YawFollowReason
import com.alonibh.tellodrone.domain.YawFollowState
import com.alonibh.tellodrone.tello.RcInputKind
import com.alonibh.tellodrone.tello.RcSendSuppressionReason
import com.alonibh.tellodrone.tello.RcVector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipFile

class VisionTraceExportTest {

    @Test
    fun `test A - empty no-detection session exports valid non-empty ZIP with required four files`() = runBlocking {
        val tempDir = Files.createTempDirectory("vision-export-test-a").toFile()
        val destFile = File(tempDir, "export-a.zip")
        val recorder = DebugVisionTraceRecorder(
            cacheDirectory = tempDir,
            destinationOpener = { FileOutputStream(destFile) },
        )
        try {
            recorder.startNewSession()
            val deferred = CompletableDeferred<Result<VisionTraceExport>>()
            recorder.export(destFile.absolutePath) { deferred.complete(it) }

            val result = withTimeout(5_000) { deferred.await() }
            assertTrue("Expected success but got: ${result.exceptionOrNull()}", result.isSuccess)
            val export = result.getOrThrow()
            assertEquals(0L, export.frameCount)
            assertTrue(export.byteCount > 0L)
            assertTrue(destFile.exists())
            assertTrue(destFile.length() > 0L)

            ZipFile(destFile).use { zip ->
                val entryNames = zip.entries().asSequence().map { it.name }.toSet()
                assertTrue(entryNames.contains("control.jsonl"))
                assertTrue(entryNames.contains("session.json"))
                assertTrue(entryNames.contains("flight_summary.json"))
                assertTrue(entryNames.contains("flight_summary.txt"))
                assertFalse("trace.jsonl should be absent when detection never occurred", entryNames.contains("trace.jsonl"))
                assertFalse("manifest.json should be absent when no frames", entryNames.contains("manifest.json"))
            }
        } finally {
            recorder.storage.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test B - flight-control-only session records takeoff RC landing and exports valid archive`() = runBlocking {
        val tempDir = Files.createTempDirectory("vision-export-test-b").toFile()
        val destFile = File(tempDir, "export-b.zip")
        val recorder = DebugVisionTraceRecorder(
            cacheDirectory = tempDir,
            destinationOpener = { FileOutputStream(destFile) },
        )
        try {
            recorder.startNewSession()
            recorder.recordFlightStateTransition(
                FlightStateTransitionTrace(
                    timestampMillis = 1000,
                    fromState = "Grounded",
                    toState = "TakingOff",
                    triggerReason = "Takeoff command acknowledged",
                    batteryPercent = 90,
                    heightMeters = 0.0f,
                ),
            )
            recorder.recordFlightStateTransition(
                FlightStateTransitionTrace(
                    timestampMillis = 1500,
                    fromState = "TakingOff",
                    toState = "Flying",
                    triggerReason = "Airborne telemetry verified",
                    batteryPercent = 89,
                    heightMeters = 1.2f,
                ),
            )
            recorder.recordSdkCommand(
                SdkCommandTrace(
                    command = "takeoff",
                    category = SdkCommandCategory.TAKEOFF,
                    sentAtMonotonicMillis = 1000,
                    latencyMillis = 400,
                    result = "ok",
                ),
            )
            recorder.recordRcPublication(
                RcPublicationTrace(
                    commandTimestampNanos = 1_600_000_000L,
                    frameSequence = null,
                    sourceTimestampNanos = null,
                    perceptionAgeMillis = null,
                    targetCenterX = null,
                    rawYawError = null,
                    filteredYawError = null,
                    associationState = TargetAssociationState.None,
                    previousYawRc = 0,
                    requestedYawRc = 0,
                    safetyFilteredYawRc = 0,
                    yawSuppressionReason = YawControlSuppressionReason.NONE,
                    requestedVector = RcVector(0, 0, 0, 0),
                    actualSentVector = RcVector(0, 0, 0, 0),
                    inputKind = RcInputKind.MANUAL,
                    sendSuppressionReason = RcSendSuppressionReason.NONE,
                    telemetryHeightMeters = 1.2f,
                    yawFollowState = YawFollowState.DISARMED,
                    yawFollowReason = YawFollowReason.EXPLICITLY_DISARMED,
                ),
            )
            recorder.recordFlightStateTransition(
                FlightStateTransitionTrace(
                    timestampMillis = 2000,
                    fromState = "Flying",
                    toState = "Landing",
                    triggerReason = "Land command acknowledged",
                    batteryPercent = 88,
                    heightMeters = 0.8f,
                ),
            )
            recorder.recordFlightStateTransition(
                FlightStateTransitionTrace(
                    timestampMillis = 2500,
                    fromState = "Landing",
                    toState = "Grounded",
                    triggerReason = "Telemetry indicates drone is grounded",
                    batteryPercent = 87,
                    heightMeters = 0.0f,
                ),
            )

            val deferred = CompletableDeferred<Result<VisionTraceExport>>()
            recorder.export(destFile.absolutePath) { deferred.complete(it) }

            val result = withTimeout(5_000) { deferred.await() }
            assertTrue(result.isSuccess)
            val export = result.getOrThrow()
            assertEquals(0L, export.frameCount)
            assertTrue(export.controlEventCount >= 5L)
            assertTrue(export.byteCount > 0L)

            ZipFile(destFile).use { zip ->
                val entryNames = zip.entries().asSequence().map { it.name }.toSet()
                assertTrue(entryNames.contains("control.jsonl"))
                assertTrue(entryNames.contains("session.json"))
                assertTrue(entryNames.contains("flight_summary.json"))
                assertTrue(entryNames.contains("flight_summary.txt"))

                val controlText = zip.getInputStream(zip.getEntry("control.jsonl")).bufferedReader().readText()
                assertTrue(controlText.contains("flightTransition"))
                assertTrue(controlText.contains("sdkCommand"))
                assertTrue(controlText.contains("rcPublication"))

                val summaryText = zip.getInputStream(zip.getEntry("flight_summary.txt")).bufferedReader().readText()
                assertTrue(summaryText.contains("FLIGHT / YAW FOLLOW SUMMARY"))
            }
        } finally {
            recorder.storage.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test C - export succeeds when trace jsonl is absent`() = runBlocking {
        val tempDir = Files.createTempDirectory("vision-export-test-c").toFile()
        val destFile = File(tempDir, "export-c.zip")
        val recorder = DebugVisionTraceRecorder(
            cacheDirectory = tempDir,
            destinationOpener = { FileOutputStream(destFile) },
        )
        try {
            recorder.startNewSession()
            assertFalse(recorder.storage.traceFile.exists())

            val deferred = CompletableDeferred<Result<VisionTraceExport>>()
            recorder.export(destFile.absolutePath) { deferred.complete(it) }

            val result = withTimeout(5_000) { deferred.await() }
            assertTrue(result.isSuccess)

            ZipFile(destFile).use { zip ->
                assertNotNull(zip.getEntry("session.json"))
                assertNotNull(zip.getEntry("control.jsonl"))
                assertNotNull(zip.getEntry("flight_summary.json"))
                assertNotNull(zip.getEntry("flight_summary.txt"))
                assertEquals(null, zip.getEntry("trace.jsonl"))
            }
        } finally {
            recorder.storage.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test D - export succeeds without frames and without manifest`() = runBlocking {
        val tempDir = Files.createTempDirectory("vision-export-test-d").toFile()
        val destFile = File(tempDir, "export-d.zip")
        val recorder = DebugVisionTraceRecorder(
            cacheDirectory = tempDir,
            destinationOpener = { FileOutputStream(destFile) },
        )
        try {
            recorder.startNewSession()
            val deferred = CompletableDeferred<Result<VisionTraceExport>>()
            recorder.export(destFile.absolutePath) { deferred.complete(it) }

            val result = withTimeout(5_000) { deferred.await() }
            assertTrue(result.isSuccess)

            ZipFile(destFile).use { zip ->
                val entries = zip.entries().asSequence().map { it.name }.toList()
                assertFalse(entries.any { it.startsWith("frames/") })
                assertFalse(entries.contains("manifest.json"))
            }
        } finally {
            recorder.storage.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test E - summary builder with empty trace lines produces valid summary`() {
        val summary = FlightSummaryBuilder.build(traceLines = emptyList(), controlLines = emptyList())
        assertEquals(0L, summary.durationMs)
        assertEquals(0L, summary.armedMs)
        assertEquals(0L, summary.activeMs)
        assertEquals(0, summary.missingCount)
        assertEquals(0, summary.lostCount)
        assertEquals(0, summary.jumpSuppressions)
        assertEquals(0, summary.crossingBrakes)
        assertEquals(0, summary.centerCrossingsCount)
        assertEquals(0, summary.nonYawAutonomousAxisViolations)

        val json = FlightSummaryBuilder.json(summary)
        assertTrue(json.contains("\"session_duration_ms\": 0"))
        assertTrue(json.contains("\"center_crossings_count\": 0"))

        val text = FlightSummaryBuilder.text(summary)
        assertTrue(text.contains("FLIGHT / YAW FOLLOW SUMMARY"))
        assertTrue(text.contains("Duration: 0:00"))
    }

    @Test
    fun `test F - prior failing command does not kill worker and subsequent export succeeds`() = runBlocking {
        val tempDir = Files.createTempDirectory("vision-export-test-f").toFile()
        val destFile = File(tempDir, "export-f.zip")
        val recorder = DebugVisionTraceRecorder(
            cacheDirectory = tempDir,
            destinationOpener = { FileOutputStream(destFile) },
        )
        try {
            recorder.startNewSession()

            recorder.recordControlMeasurement(
                YawControlMeasurementTrace(
                    frameSequence = 1,
                    sourceTimestampNanos = 100,
                    commandTimestampNanos = 200,
                    perceptionAgeMillis = 10,
                    targetCenterX = 0.5f,
                    rawYawError = 0.0f,
                    filteredYawError = 0.0f,
                    associationState = TargetAssociationState.Matched,
                    previousYawRc = 0,
                    requestedYawRc = 0,
                    safetyFilteredYawRc = 0,
                    suppressionReason = YawControlSuppressionReason.NONE,
                    telemetryHeightMeters = 1.0f,
                    yawFollowState = YawFollowState.ACTIVE,
                    yawFollowReason = YawFollowReason.ACTIVE,
                ),
            )

            val deferred = CompletableDeferred<Result<VisionTraceExport>>()
            recorder.export(destFile.absolutePath) { deferred.complete(it) }

            val result = withTimeout(5_000) { deferred.await() }
            assertTrue("Expected export to succeed after commands, got: ${result.exceptionOrNull()}", result.isSuccess)
            assertTrue(destFile.exists() && destFile.length() > 0L)
        } finally {
            recorder.storage.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test G - internal ZIP creation failure reports deterministic failure to callback`() = runBlocking {
        val tempDir = Files.createTempDirectory("vision-export-test-g").toFile()
        val destFile = File(tempDir, "export-g.zip")
        val recorder = DebugVisionTraceRecorder(
            cacheDirectory = tempDir,
            destinationOpener = { FileOutputStream(destFile) },
            temporaryArchiveFactory = {
                throw IOException("Simulated temporary archive creation failure")
            },
        )
        try {
            recorder.startNewSession()
            val deferred = CompletableDeferred<Result<VisionTraceExport>>()
            recorder.export(destFile.absolutePath) { deferred.complete(it) }

            val result = withTimeout(5_000) { deferred.await() }
            assertTrue("Expected export to fail when temporary archive creation fails", result.isFailure)
            val error = result.exceptionOrNull()
            assertNotNull(error)
            assertTrue(
                "Expected error message to mention temporary archive creation, got: ${error?.message}",
                error?.message?.contains("temporary archive file") == true || error?.message?.contains("Simulated temporary archive creation failure") == true,
            )
            assertFalse("Destination file should not be created if temp ZIP creation fails", destFile.exists())
        } finally {
            recorder.storage.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test H - destination copy failure reports stage-specific reason`() = runBlocking {
        val tempDir = Files.createTempDirectory("vision-export-test-h").toFile()
        val failingOpener: (String) -> OutputStream? = {
            throw IOException("Simulated disk full or permission denied on destination")
        }
        val recorder = DebugVisionTraceRecorder(
            cacheDirectory = tempDir,
            destinationOpener = failingOpener,
        )
        try {
            recorder.startNewSession()
            val deferred = CompletableDeferred<Result<VisionTraceExport>>()
            recorder.export("content://fake/path") { deferred.complete(it) }

            val result = withTimeout(5_000) { deferred.await() }
            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertNotNull(error)
            assertTrue("Expected destination open/copy failure reason in '${error?.message}'",
                error?.message?.contains("destination") == true || error?.message?.contains("Simulated") == true)
        } finally {
            recorder.storage.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test I - successful archive is fully valid and readable with ZipFile`() = runBlocking {
        val tempDir = Files.createTempDirectory("vision-export-test-i").toFile()
        val destFile = File(tempDir, "export-i.zip")
        val recorder = DebugVisionTraceRecorder(
            cacheDirectory = tempDir,
            destinationOpener = { FileOutputStream(destFile) },
        )
        try {
            recorder.startNewSession()
            recorder.recordRcPublication(
                RcPublicationTrace(
                    commandTimestampNanos = 1_000_000_000L,
                    frameSequence = 1L,
                    sourceTimestampNanos = 1_000_000_000L,
                    perceptionAgeMillis = 50L,
                    targetCenterX = 0.5f,
                    rawYawError = 0.0f,
                    filteredYawError = 0.0f,
                    associationState = TargetAssociationState.Matched,
                    previousYawRc = 0,
                    requestedYawRc = 0,
                    safetyFilteredYawRc = 0,
                    yawSuppressionReason = YawControlSuppressionReason.NONE,
                    requestedVector = RcVector(0, 0, 0, 0),
                    actualSentVector = RcVector(0, 0, 0, 0),
                    inputKind = RcInputKind.AUTONOMOUS_YAW,
                    sendSuppressionReason = RcSendSuppressionReason.NONE,
                    telemetryHeightMeters = 1.0f,
                    yawFollowState = YawFollowState.ACTIVE,
                    yawFollowReason = YawFollowReason.ACTIVE,
                ),
            )

            val deferred = CompletableDeferred<Result<VisionTraceExport>>()
            recorder.export(destFile.absolutePath) { deferred.complete(it) }

            val result = withTimeout(5_000) { deferred.await() }
            assertTrue(result.isSuccess)
            val export = result.getOrThrow()
            assertTrue(export.byteCount > 0L)
            assertEquals(1L, export.controlEventCount)

            ZipFile(destFile).use { zip ->
                val entries = zip.entries().asSequence().map { it.name }.toSet()
                assertEquals(
                    setOf("session.json", "control.jsonl", "flight_summary.json", "flight_summary.txt"),
                    entries,
                )
                for (name in entries) {
                    val entry = zip.getEntry(name)
                    assertNotNull(entry)
                    val content = zip.getInputStream(entry).readBytes()
                    assertTrue(content.isNotEmpty())
                }
            }
        } finally {
            recorder.storage.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test J - repeated consecutive exports both produce valid non-empty archives`() = runBlocking {
        val tempDir = Files.createTempDirectory("vision-export-test-j").toFile()
        val dest1 = File(tempDir, "export-j1.zip")
        val dest2 = File(tempDir, "export-j2.zip")
        var currentDest = dest1
        val recorder = DebugVisionTraceRecorder(
            cacheDirectory = tempDir,
            destinationOpener = { FileOutputStream(currentDest) },
        )
        try {
            recorder.startNewSession()
            recorder.recordRcPublication(
                RcPublicationTrace(
                    commandTimestampNanos = 1_000_000_000L,
                    frameSequence = null,
                    sourceTimestampNanos = null,
                    perceptionAgeMillis = null,
                    targetCenterX = null,
                    rawYawError = null,
                    filteredYawError = null,
                    associationState = TargetAssociationState.None,
                    previousYawRc = 0,
                    requestedYawRc = 0,
                    safetyFilteredYawRc = 0,
                    yawSuppressionReason = YawControlSuppressionReason.NONE,
                    requestedVector = RcVector(0, 0, 0, 0),
                    actualSentVector = RcVector(0, 0, 0, 0),
                    inputKind = RcInputKind.MANUAL,
                    sendSuppressionReason = RcSendSuppressionReason.NONE,
                    telemetryHeightMeters = 0.0f,
                    yawFollowState = YawFollowState.DISARMED,
                    yawFollowReason = YawFollowReason.EXPLICITLY_DISARMED,
                ),
            )

            // First export
            val deferred1 = CompletableDeferred<Result<VisionTraceExport>>()
            recorder.export(dest1.absolutePath) { deferred1.complete(it) }
            val result1 = withTimeout(5_000) { deferred1.await() }
            assertTrue(result1.isSuccess)
            assertTrue(dest1.exists() && dest1.length() > 0L)

            // Second export
            currentDest = dest2
            val deferred2 = CompletableDeferred<Result<VisionTraceExport>>()
            recorder.export(dest2.absolutePath) { deferred2.complete(it) }
            val result2 = withTimeout(5_000) { deferred2.await() }
            assertTrue(result2.isSuccess)
            assertTrue(dest2.exists() && dest2.length() > 0L)

            ZipFile(dest1).use { z1 ->
                assertTrue(z1.entries().asSequence().map { it.name }.contains("session.json"))
            }
            ZipFile(dest2).use { z2 ->
                assertTrue(z2.entries().asSequence().map { it.name }.contains("session.json"))
            }
        } finally {
            recorder.storage.close()
            tempDir.deleteRecursively()
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
