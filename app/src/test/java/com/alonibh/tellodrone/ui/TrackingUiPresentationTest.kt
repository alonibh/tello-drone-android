package com.alonibh.tellodrone.ui

import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.NormalizedBoundingBox
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.TargetSelection
import com.alonibh.tellodrone.domain.TelemetrySnapshot
import com.alonibh.tellodrone.domain.TrackingMode
import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.VideoState
import com.alonibh.tellodrone.domain.YawFollowDecision
import com.alonibh.tellodrone.domain.YawFollowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingUiPresentationTest {
    @Test fun `off and detection states use unambiguous pilot wording`() {
        val off = DroneSessionState().trackingUiPresentation()
        assertEquals(TrackingHudState.Off, DroneSessionState().trackingHudState())
        assertEquals(TrackingPrimaryAction.DetectPeople, off.primaryAction)
        assertNull(off.instruction)

        val detecting = operationalState()
        val presentation = detecting.trackingUiPresentation()
        assertEquals(TrackingHudState.SelectTarget, detecting.trackingHudState())
        assertEquals(TrackingPrimaryAction.None, presentation.primaryAction)
        assertEquals("Tap a detected person to select", presentation.instruction)
    }

    @Test fun `selected target is ready and active follow is distinct`() {
        val ready = operationalState(targetState = TargetAssociationState.Selected)
        assertEquals(TrackingHudState.TargetReady, ready.trackingHudState())
        assertEquals("Selected", ready.trackingUiPresentation().target.value)
        assertEquals(TrackingPrimaryAction.StartFollow, ready.trackingUiPresentation().primaryAction)
        assertTrue(ready.canStartFollow())

        val active = ready.copy(
            targetAssociationState = TargetAssociationState.Matched,
            yawFollowDecision = YawFollowDecision(state = YawFollowState.ACTIVE),
        )
        assertEquals(TrackingHudState.Active, active.trackingHudState())
        assertEquals(TrackingPrimaryAction.StopFollow, active.trackingUiPresentation().primaryAction)
    }

    @Test fun `searching and lost remain fail closed and identity safe`() {
        val searching = operationalState(targetState = TargetAssociationState.TemporarilyMissing)
        assertEquals(TrackingHudState.Searching, searching.trackingHudState())
        assertFalse(searching.canStartFollow())

        val lost = operationalState(targetState = TargetAssociationState.Lost)
        assertEquals(TrackingHudState.Lost, lost.trackingHudState())
        assertEquals(TrackingPrimaryAction.None, lost.trackingUiPresentation().primaryAction)
        assertEquals("Target lost — tap a detected person to reselect", lost.trackingUiPresentation().instruction)
        assertFalse(lost.canStartFollow())
    }

    @Test fun `landing grounded and emergency presentations do not advertise follow`() {
        val staleActive = operationalState(targetState = TargetAssociationState.Matched).copy(
            yawFollowDecision = YawFollowDecision(state = YawFollowState.ACTIVE),
        )
        listOf(FlightState.Landing, FlightState.Emergency).forEach { flight ->
            val state = staleActive.copy(flight = flight)
            assertEquals(TrackingHudState.Off, state.trackingHudState())
            assertEquals(TrackingPrimaryAction.None, state.trackingUiPresentation().primaryAction)
            assertFalse(state.canStartFollow())
        }
        val grounded = DroneSessionState(
            connection = DroneConnectionState.Connected,
            flight = FlightState.Grounded,
            telemetry = TelemetrySnapshot(isFresh = true),
        )
        assertEquals(TrackingHudState.Off, grounded.trackingHudState())
        assertFalse(grounded.canStartFollow())
    }

    @Test fun `follow action gating agrees with connection flight telemetry video and target safety`() {
        val ready = operationalState(targetState = TargetAssociationState.Matched)
        assertTrue(ready.canStartFollow())
        assertFalse(ready.copy(connection = DroneConnectionState.Disconnected).canStartFollow())
        assertFalse(ready.copy(flight = FlightState.TakingOff).canStartFollow())
        assertFalse(ready.copy(flight = FlightState.Landing).canStartFollow())
        assertFalse(ready.copy(telemetry = ready.telemetry.copy(isFresh = false)).canStartFollow())
        assertFalse(ready.copy(video = ready.video.copy(availability = VideoAvailability.Error)).canStartFollow())
        assertFalse(ready.copy(targetAssociationState = TargetAssociationState.Ambiguous).canStartFollow())
    }

    private fun operationalState(targetState: TargetAssociationState = TargetAssociationState.None): DroneSessionState {
        val detection = PersonDetection(NormalizedBoundingBox(.3f, .2f, .6f, .8f), .9f, 1L, 2L)
        val target = if (targetState == TargetAssociationState.None) null else TargetSelection.select(detection)
        return DroneSessionState(
            connection = DroneConnectionState.Connected,
            flight = FlightState.Flying,
            telemetry = TelemetrySnapshot(isFresh = true),
            tracking = if (target == null) TrackingMode.DetectOnly else TrackingMode.TargetLocked,
            video = VideoState(
                availability = VideoAvailability.Streaming,
                analysisLatestSequence = 1L,
                personDetectionState = PersonDetectionState.Detecting,
            ),
            target = target,
            targetAssociationState = targetState,
        )
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
