package com.alonibh.tellodrone.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlightSummaryTest {
    @Test fun calculates_duration_percentiles_suppressions_and_height_statistics() {
        val traces = listOf(trace(0, "Selected", 10), trace(1_000_000_000, "Matched", 20), trace(3_000_000_000, "TemporarilyMissing", 30), trace(4_000_000_000, "Matched", 40), trace(5_000_000_000, "Lost", 50))
        val controls = listOf(
            control(1_000_000_000, "ACTIVE", "NONE", yaw = 4, height = 1.0),
            control(2_000_000_000, "ACTIVE", "TARGET_JUMP_REJECTED", yaw = 7, height = 2.0),
            control(3_000_000_000, "REQUIRES_REARM", "CENTER_CROSSING_BRAKE", yaw = 9, height = 3.0, lateral = 1),
            control(4_000_000_000, "REQUIRES_REARM", "STALE_PERCEPTION", yaw = 2, height = 4.0, send = "PERCEPTION_AGE_EXPIRED"),
        )
        val summary = FlightSummaryBuilder.build(traces, controls)
        assertEquals(5000, summary.durationMs)
        assertEquals(1, summary.missingCount)
        assertEquals(1000, summary.longestMissingMs)
        assertEquals(1, summary.jumpSuppressions)
        assertEquals(1, summary.crossingBrakes)
        assertEquals(1, summary.nonYawAutonomousAxisViolations)
        assertEquals(1.0, summary.heightMin!!, .001)
        assertEquals(4.0, summary.heightMax!!, .001)
        assertEquals(3, summary.maxYawStep)
    }

    @Test fun represents_unrecorded_optional_metrics_honestly() {
        val summary = FlightSummaryBuilder.build(listOf(trace(0, "None", null)), emptyList())
        assertNull(summary.matchedPercent)
        assertNull(summary.previewFps)
        assertNull(summary.verticalVelocityP95)
        assertNull(summary.ageP50)
        assertEquals("null", Regex("\"preview_fps\": (null)").find(FlightSummaryBuilder.json(summary))!!.groupValues[1])
    }

    private fun trace(time: Long, state: String, inference: Int?) = "{\"sourceTimestampNanos\":$time,\"associationState\":\"$state\",\"detector\":{\"inferenceMillis\":${inference ?: "null"}}}"
    private fun control(time: Long, state: String, suppression: String, yaw: Int, height: Double, lateral: Int = 0, send: String = "NONE") = "{\"eventType\":\"rcPublication\",\"commandTimestampNanos\":$time,\"frameSequence\":1,\"perceptionAgeMillis\":100,\"yawFollowState\":\"$state\",\"yawFollowReason\":\"ACTIVE\",\"suppressionReason\":\"$suppression\",\"inputKind\":\"AUTONOMOUS_YAW\",\"sendSuppressionReason\":\"$send\",\"telemetryHeightMeters\":$height,\"actualSentVector\":{\"lateral\":$lateral,\"forward\":0,\"vertical\":0,\"yaw\":$yaw}}"
}
