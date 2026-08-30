package com.alonibh.tellodrone.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightSummaryTest {
    @Test fun calculates_duration_percentiles_suppressions_and_height_statistics() {
        val traces = listOf(trace(0, "Selected", 10), trace(1_000_000_000, "Matched", 20), trace(3_000_000_000, "TemporarilyMissing", 30), trace(4_000_000_000, "Matched", 40), trace(5_000_000_000, "Lost", 50))
        val controls = listOf(
            control(1_000_000_000, "ACTIVE", "NONE", yaw = 4, height = 1.0, error = 0.10),
            control(2_000_000_000, "ACTIVE", "TARGET_JUMP_REJECTED", yaw = 7, height = 2.0, error = 0.25),
            control(3_000_000_000, "REQUIRES_REARM", "CENTER_CROSSING_BRAKE", yaw = 9, height = 3.0, lateral = 1, error = 0.30),
            control(4_000_000_000, "REQUIRES_REARM", "STALE_PERCEPTION", yaw = 2, height = 4.0, send = "PERCEPTION_AGE_EXPIRED", error = 0.05),
        )
        val summary = FlightSummaryBuilder.build(traces, controls)
        assertEquals(5000, summary.durationMs)
        assertEquals(1, summary.missingCount)
        assertEquals(1000, summary.longestMissingMs)
        assertEquals(1, summary.jumpSuppressions)
        assertEquals(1, summary.crossingBrakes)
        assertEquals(1, summary.centerCrossingsCount)
        assertEquals(1, summary.nonYawAutonomousAxisViolations)
        assertEquals(1.0, summary.heightMin!!, .001)
        assertEquals(4.0, summary.heightMax!!, .001)
        assertEquals(3, summary.maxYawStep)
        assertNotNull(summary.fractionOfActiveNonZeroYaw)
        assertEquals(1, summary.sourceAgeExpiredCount)
        assertEquals(2000L, summary.timeOutsideError15Ms)
        assertEquals(2000L, summary.timeOutsideError20Ms)
    }

    @Test fun represents_unrecorded_optional_metrics_honestly() {
        val summary = FlightSummaryBuilder.build(listOf(trace(0, "None", null)), emptyList())
        assertNull(summary.matchedPercent)
        assertNull(summary.previewFps)
        assertNull(summary.verticalVelocityP95)
        assertNull(summary.ageP50)
        assertNull(summary.fractionOfActiveNonZeroYaw)
        assertNull(summary.sourceAgeExpiredPercent)
        assertNull(summary.commandHoldExpiredPercent)
        assertTrue(FlightSummaryBuilder.json(summary).contains("\"preview_fps\": null"))
    }

    @Test fun derives_distinct_end_to_end_and_detector_stage_latency_metrics() {
        val traces = listOf(
            """{"sourceTimestampNanos":1000000000,"associationState":"Matched","detector":{"inferenceMillis":30},"renderedFrameTimestampNanos":1000000000,"pixelCopyCompletedTimestampNanos":1010000000,"detectorInferenceStartedTimestampNanos":1020000000,"detectorInferenceCompletedTimestampNanos":1050000000,"associationCompletedTimestampNanos":1055000000,"detectorPreprocessingNanos":3000000,"detectorModelInferenceNanos":20000000,"detectorDecodeAndNmsNanos":5000000,"detectorAppearanceNanos":2000000,"analysisMeasuredFps":15.0,"detectorMeasuredFps":9.0,"analysisDroppedFrames":3,"analysisPendingFrameDepth":1}""",
        )
        val controls = listOf(
            """{"eventType":"controlMeasurement","sourceTimestampNanos":1000000000,"yawDecisionTimestampNanos":1060000000,"commandTimestampNanos":1060000000,"yawFollowState":"ACTIVE"}""",
            """{"eventType":"rcPublication","sourceTimestampNanos":1000000000,"yawDecisionTimestampNanos":1060000000,"actualSentAtNanos":1080000000,"commandTimestampNanos":1079000000,"inputKind":"AUTONOMOUS_YAW","sendSuppressionReason":"NONE","yawFollowState":"ACTIVE","actualSentVector":{"lateral":0,"forward":0,"vertical":0,"yaw":8}}""",
        )

        val summary = FlightSummaryBuilder.build(traces, controls)

        assertEquals(10.0, summary.renderToPixelCopyP50!!, .001)
        assertEquals(60.0, summary.sourceToDecisionP50!!, .001)
        assertEquals(20.0, summary.decisionToSendP50!!, .001)
        assertEquals(80.0, summary.sourceToPhysicalSendP50!!, .001)
        assertEquals(15.0, summary.analysisFps!!, .001)
        assertEquals(9.0, summary.detectorFps!!, .001)
        assertEquals(3L, summary.analysisDroppedFrames)
        assertEquals(1, summary.maximumAnalysisPendingDepth)
        assertEquals(20.0, summary.modelInferenceP50!!, .001)
    }

    @Test fun counts_distinct_episodes_from_chronological_false_to_true_transitions() {
        val controls = listOf(
            // Jump episode 1 (2 consecutive records) -> count 1
            control(1_000_000_000, suppression = "TARGET_JUMP_REJECTED", frameSequence = 10),
            control(1_100_000_000, suppression = "TARGET_JUMP_REJECTED", frameSequence = 11),
            // Normal control separating episodes
            control(1_200_000_000, suppression = "NONE", frameSequence = 12),
            // Jump episode 2 (1 record) -> total count 2
            control(1_300_000_000, suppression = "TARGET_JUMP_REJECTED", frameSequence = 13),
            // Crossing brake episode 1
            control(1_400_000_000, suppression = "CENTER_CROSSING_BRAKE", frameSequence = 14),
            control(1_450_000_000, suppression = "CENTER_CROSSING_BRAKE", frameSequence = 15),
            // Normal
            control(1_500_000_000, suppression = "NONE", frameSequence = 16),
            // Crossing brake episode 2
            control(1_600_000_000, suppression = "CENTER_CROSSING_BRAKE", frameSequence = 17),
            // REQUIRES_REARM episode 1
            control(1_700_000_000, state = "REQUIRES_REARM", frameSequence = 18),
            control(1_750_000_000, state = "REQUIRES_REARM", frameSequence = 19),
            // Resumed / armed
            control(1_800_000_000, state = "ACTIVE", frameSequence = 20),
            // REQUIRES_REARM episode 2
            control(1_900_000_000, state = "REQUIRES_REARM", frameSequence = 21),
            // Preemption: manual override #1
            control(2_000_000_000, reason = "MANUAL_OVERRIDE", frameSequence = 22),
            // Normal active
            control(2_100_000_000, reason = "ACTIVE", frameSequence = 23),
            // Preemption: manual override #2
            control(2_200_000_000, reason = "MANUAL_OVERRIDE", frameSequence = 24),
            // Preemption: STOP/HOVER #1
            control(2_300_000_000, reason = "HOVER_INTERVENTION", frameSequence = 25),
            control(2_350_000_000, reason = "HOVER_INTERVENTION", frameSequence = 26),
            // Normal
            control(2_400_000_000, reason = "ACTIVE", frameSequence = 27),
            // Preemption: STOP/HOVER #2
            control(2_500_000_000, reason = "HOVER_INTERVENTION", frameSequence = 28),
        )
        val summary = FlightSummaryBuilder.build(emptyList(), controls)
        assertEquals(2, summary.jumpSuppressions)
        assertEquals(2, summary.crossingBrakes)
        assertEquals(2, summary.requiresRearmCount)
        assertEquals(2, summary.manualOverrides)
        assertEquals(2, summary.stopHoverPreemptions)

        // Verify notable events point to the first record of each distinct episode
        val jumpEvents = summary.notableEvents.filter { it.kind == "jump rejection" }
        assertEquals(2, jumpEvents.size)
        assertEquals(1_000_000_000L, jumpEvents[0].timestampNanos)
        assertEquals(10L, jumpEvents[0].frameSequence)
        assertEquals(1_300_000_000L, jumpEvents[1].timestampNanos)
        assertEquals(13L, jumpEvents[1].frameSequence)

        val crossingEvents = summary.notableEvents.filter { it.kind == "center-crossing brake" }
        assertEquals(2, crossingEvents.size)
        assertEquals(1_400_000_000L, crossingEvents[0].timestampNanos)
        assertEquals(14L, crossingEvents[0].frameSequence)
        assertEquals(1_600_000_000L, crossingEvents[1].timestampNanos)
        assertEquals(17L, crossingEvents[1].frameSequence)
    }

    @Test fun counts_distinct_lost_episodes_from_non_lost_transitions() {
        val traces = listOf(
            trace(1_000_000_000, "None"),
            trace(2_000_000_000, "Selected"),
            trace(3_000_000_000, "Matched"),
            trace(4_000_000_000, "Lost"), // Lost episode 1 start
            trace(4_500_000_000, "Lost"), // Lost continuation
            trace(5_000_000_000, "Matched"), // Reselected/matched
            trace(6_000_000_000, "Lost"), // Lost episode 2 start
        )
        val summary = FlightSummaryBuilder.build(traces, emptyList())
        assertEquals(2, summary.lostCount)
    }

    @Test fun preserves_and_reports_true_autonomous_physical_yaw_without_fabrication_or_clamping() {
        val normalControls = listOf(
            control(1_000_000_000, yaw = 6),
            control(2_000_000_000, yaw = -12),
        )
        val normalSummary = FlightSummaryBuilder.build(emptyList(), normalControls)
        assertEquals(12, normalSummary.maxAbsYaw)
        assertEquals(9.0, normalSummary.meanAbsYaw!!, 0.001)

        val unconstrainedTraceControls = listOf(
            control(1_000_000_000, yaw = 15),
            control(2_000_000_000, yaw = -20),
        )
        val unconstrainedSummary = FlightSummaryBuilder.build(emptyList(), unconstrainedTraceControls)
        // Values > 12 are faithfully preserved and reported as recorded in trace
        assertEquals(20, unconstrainedSummary.maxAbsYaw)
        assertEquals(17.5, unconstrainedSummary.meanAbsYaw!!, 0.001)
    }

    @Test fun `test H - diagnostic summary and export build successfully without vision frames`() {
        val controls = listOf(
            """{"eventType":"flightTransition","timestampMillis":1000,"fromState":"Grounded","toState":"TakingOff","triggerReason":"Takeoff in progress"}""",
            """{"eventType":"flightTransition","timestampMillis":1400,"fromState":"TakingOff","toState":"Flying","triggerReason":"Takeoff stabilized by verified airborne telemetry"}""",
            """{"eventType":"rcPublication","commandTimestampNanos":1450000000,"inputKind":"SAFETY_ZERO","sendSuppressionReason":"NONE","yawFollowState":"DISARMED","actualSentVector":{"lateral":0,"forward":0,"vertical":0,"yaw":0}}""",
        )
        val summary = FlightSummaryBuilder.build(traceLines = emptyList(), controlLines = controls)
        val json = FlightSummaryBuilder.json(summary)
        val text = FlightSummaryBuilder.text(summary)

        assertTrue(json.contains("\"center_crossings_count\": 0"))
        assertTrue(json.contains("\"non_yaw_autonomous_axis_violations\": 0"))
        assertTrue(text.contains("FLIGHT / YAW FOLLOW SUMMARY"))
        assertTrue(text.contains("NON-YAW AUTONOMOUS AXIS VIOLATIONS: 0"))

        val zipFile = java.io.File.createTempFile("diag-bundle", ".zip")
        try {
            java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("control.jsonl"))
                zip.write(controls.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("flight_summary.json"))
                zip.write(json.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("flight_summary.txt"))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            java.util.zip.ZipFile(zipFile).use { z ->
                val entries = z.entries().asSequence().map { it.name }.toSet()
                assertEquals(setOf("control.jsonl", "flight_summary.json", "flight_summary.txt"), entries)
            }
        } finally {
            zipFile.delete()
        }
    }

    @Test fun `fresh perception age uses unique control decisions and expirations remain distinct`() {
        val controls = listOf(
            measurement(frame = 1, source = 1_000_000_000, decision = 1_010_000_000),
            measurement(frame = 1, source = 1_000_000_000, decision = 1_900_000_000),
            measurement(frame = 2, source = 2_000_000_000, decision = 2_020_000_000),
            measurement(frame = 3, source = 3_000_000_000, decision = 3_100_000_000),
            // Repeated 50 Hz publications deliberately carry misleading old ages; they must not
            // contribute to fresh perception latency.
            publication(4_000_000_000, "AUTONOMOUS_COMMAND_HOLD_EXPIRED", perceptionAge = 900),
            publication(4_050_000_000, "AUTONOMOUS_COMMAND_HOLD_EXPIRED", perceptionAge = 950),
            publication(4_100_000_000, "PERCEPTION_AGE_EXPIRED", perceptionAge = 1_000),
            publication(4_150_000_000, "NONE", perceptionAge = 1_050, yaw = 8),
            publication(4_200_000_000, "NONE", perceptionAge = 1_100, yaw = 8),
        )

        val summary = FlightSummaryBuilder.build(emptyList(), controls)

        assertEquals(20.0, summary.ageP50!!, .001)
        assertEquals(20.0, summary.ageP95!!, .001)
        assertEquals(100L, summary.ageMax)
        assertEquals(2, summary.commandHoldExpiredCount)
        assertEquals(40.0, summary.commandHoldExpiredPercent!!, .001)
        assertEquals(1, summary.sourceAgeExpiredCount)
        assertEquals(20.0, summary.sourceAgeExpiredPercent!!, .001)
        assertEquals(150L, summary.longestExpirationZeroIntervalMs)
        val json = FlightSummaryBuilder.json(summary)
        assertTrue(json.contains("\"command_hold_expiration_count\": 2"))
        assertTrue(json.contains("\"source_age_expiration_count\": 1"))
    }

    @Test fun `interleaved measurements do not fragment publication expiration episodes`() {
        val controls = listOf(
            publication(1_000_000_000, "AUTONOMOUS_COMMAND_HOLD_EXPIRED", perceptionAge = 200),
            // Interleaved measurement record (must NOT split the expiration episode into 2)
            measurement(frame = 1, source = 1_000_000_000, decision = 1_010_000_000),
            publication(1_050_000_000, "AUTONOMOUS_COMMAND_HOLD_EXPIRED", perceptionAge = 250),
            publication(1_100_000_000, "NONE", perceptionAge = 100, yaw = 8),
        )

        val summary = FlightSummaryBuilder.build(emptyList(), controls)
        assertEquals(1, summary.physicalExpirations)
    }

    @Test fun `summarizes telemetry anomalies and transport timing`() {
        val controls = listOf(
            """{"eventType":"rcPublication","commandTimestampNanos":1000000000,"inputKind":"AUTONOMOUS_YAW","sendSuppressionReason":"NONE","yawFollowState":"ACTIVE","actualSentVector":{"lateral":0,"forward":0,"vertical":0,"yaw":8},"interSendIntervalMillis":20.5,"sendDurationNanos":1500000}""",
            """{"eventType":"rcPublication","commandTimestampNanos":1020000000,"inputKind":"AUTONOMOUS_YAW","sendSuppressionReason":"NONE","yawFollowState":"ACTIVE","actualSentVector":{"lateral":0,"forward":0,"vertical":0,"yaw":16},"interSendIntervalMillis":19.8,"sendDurationNanos":1200000}""",
            """{"eventType":"yawResponseAnomalyEvent","timestampMillis":1030,"subType":"yaw_response_anomaly_latched","triggerReason":"SUSTAINED_DIRECTION_MISMATCH","rawYawRateDegreesPerSecond":75.0,"filteredYawRateDegreesPerSecond":70.0}""",
            """{"eventType":"yawResponseAnomalyEvent","timestampMillis":1025,"subType":"yaw_response_mismatch_suspect","triggerReason":"SUSPECT_MISMATCH","rawYawRateDegreesPerSecond":60.0,"filteredYawRateDegreesPerSecond":60.0}""",
            """{"eventType":"telemetryDetailedSample","receivedAtMonotonicMillis":1030,"rawYawRateDegreesPerSecond":75.0,"filteredYawRateDegreesPerSecond":70.0}""",
        )
        val summary = FlightSummaryBuilder.build(emptyList(), controls)
        assertEquals(1, summary.yawResponseAnomaliesCount)
        assertEquals(1, summary.yawResponseMismatchSuspectCount)
        assertEquals(75.0, summary.maxObservedRawTelemetryYawRateDps!!, 0.01)
        assertEquals(70.0, summary.maxObservedTelemetryYawRateDps!!, 0.01)
        assertEquals(20.15, summary.meanInterSendIntervalMs!!, 0.01)
        assertEquals(1.5, summary.maxSendDurationMs!!, 0.01)

        val json = FlightSummaryBuilder.json(summary)
        assertTrue(json.contains("\"yaw_response_anomalies_count\": 1"))
        assertTrue(json.contains("\"yaw_response_mismatch_suspect_count\": 1"))
    }

    private fun trace(time: Long, state: String, inference: Int? = null) = "{\"sourceTimestampNanos\":$time,\"associationState\":\"$state\",\"detector\":{\"inferenceMillis\":${inference ?: "null"}}}"
    private fun control(time: Long, state: String = "ACTIVE", suppression: String = "NONE", reason: String = "ACTIVE", yaw: Int = 0, height: Double = 1.0, lateral: Int = 0, send: String = "NONE", frameSequence: Long = 1, error: Double? = null) = "{\"eventType\":\"rcPublication\",\"commandTimestampNanos\":$time,\"frameSequence\":$frameSequence,\"perceptionAgeMillis\":100,\"yawFollowState\":\"$state\",\"yawFollowReason\":\"$reason\",\"suppressionReason\":\"$suppression\",\"inputKind\":\"AUTONOMOUS_YAW\",\"sendSuppressionReason\":\"$send\",\"telemetryHeightMeters\":$height,\"rawYawError\":${error ?: "null"},\"actualSentVector\":{\"lateral\":$lateral,\"forward\":0,\"vertical\":0,\"yaw\":$yaw}}"
    private fun measurement(frame: Long, source: Long, decision: Long) =
        "{\"eventType\":\"controlMeasurement\",\"frameSequence\":$frame,\"sourceTimestampNanos\":$source,\"yawDecisionTimestampNanos\":$decision,\"commandTimestampNanos\":$decision,\"yawFollowState\":\"ACTIVE\"}"
    private fun publication(time: Long, suppression: String, perceptionAge: Long, yaw: Int = 0) =
        "{\"eventType\":\"rcPublication\",\"commandTimestampNanos\":$time,\"perceptionAgeMillis\":$perceptionAge,\"yawFollowState\":\"ACTIVE\",\"inputKind\":\"AUTONOMOUS_YAW\",\"sendSuppressionReason\":\"$suppression\",\"actualSentVector\":{\"lateral\":0,\"forward\":0,\"vertical\":0,\"yaw\":$yaw}}"
}

// SPDX-License-Identifier: AGPL-3.0-only
