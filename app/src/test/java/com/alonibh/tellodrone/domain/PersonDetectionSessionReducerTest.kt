package com.alonibh.tellodrone.domain

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonDetectionSessionReducerTest {
    @Test fun `detection failure does not affect connection flight or manual authority`() {
        val manual = ManualControlVector(forward = .4f, yaw = -.2f)
        val initial = DroneSessionState(
            connection = DroneConnectionState.Connected,
            flight = FlightState.Flying,
            authority = ControlAuthority.Manual,
            manualVector = manual,
            target = TrackedTarget(Rect(.1f, .1f, .2f, .2f), .9f),
        )
        val failedVideo = VideoState(
            availability = VideoAvailability.Streaming,
            personDetectionState = PersonDetectionState.Error,
            detectorErrorReason = "Detector unavailable",
        )

        val result = initial.withPersonDetectionVideoState(failedVideo)

        assertEquals(initial.connection, result.connection)
        assertEquals(initial.flight, result.flight)
        assertEquals(manual, result.manualVector)
        assertEquals(ControlAuthority.Manual, result.authority)
        assertEquals(TrackingMode.Off, result.tracking)
        assertNull(result.target)
        assertTrue(result.personDetections.isEmpty())
        assertEquals("Detector unavailable", result.lastMessage)
    }

    @Test fun `detect only results never create target or autonomous authority`() {
        val detection = PersonDetection(NormalizedBoundingBox(.1f, .2f, .5f, .9f), .8f, 3L, 4L)
        val result = DroneSessionState(connection = DroneConnectionState.Connected)
            .withPersonDetectionVideoState(
                VideoState(
                    availability = VideoAvailability.Streaming,
                    personDetectionState = PersonDetectionState.Detecting,
                    personDetections = listOf(detection),
                ),
            )

        assertEquals(TrackingMode.DetectOnly, result.tracking)
        assertEquals(ControlAuthority.Manual, result.authority)
        assertNull(result.target)
        assertEquals(listOf(detection), result.personDetections)
    }
}
