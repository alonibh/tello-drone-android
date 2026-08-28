package com.alonibh.tellodrone.ui

import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.VideoState
import com.alonibh.tellodrone.domain.YawFollowDecision
import com.alonibh.tellodrone.domain.YawFollowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingUiPresentationTest {
    @Test fun maps_operational_statuses_and_actions() {
        val off = DroneSessionState().trackingUiPresentation()
        assertEquals("Off", off.detection.value)
        assertEquals("Select person", off.target.value)
        assertEquals(TrackingPrimaryAction.StartDetection, off.primaryAction)
        val active = DroneSessionState(video = VideoState(personDetectionState = PersonDetectionState.Detecting), targetAssociationState = TargetAssociationState.Matched, yawFollowDecision = YawFollowDecision(state = YawFollowState.ACTIVE)).trackingUiPresentation()
        assertEquals("Tracking", active.target.value)
        assertEquals("Following", active.yaw.value)
        assertEquals(TrackingPrimaryAction.DisarmYawFollow, active.primaryAction)
        assertNull(active.instruction)
    }

    @Test fun lost_requires_explicit_reselection_and_never_arms() {
        val lost = DroneSessionState(video = VideoState(personDetectionState = PersonDetectionState.Detecting), targetAssociationState = TargetAssociationState.Lost).trackingUiPresentation()
        assertEquals("Lost", lost.target.value)
        assertEquals(TrackingPrimaryAction.None, lost.primaryAction)
        assertEquals("Target lost — tap the person again to reselect", lost.instruction)
    }

    @Test fun rearm_is_explicitly_labeled() {
        val rearm = DroneSessionState(video = VideoState(personDetectionState = PersonDetectionState.Detecting), yawFollowDecision = YawFollowDecision(state = YawFollowState.REQUIRES_REARM)).trackingUiPresentation()
        assertEquals(TrackingPrimaryAction.RearmYawFollow, rearm.primaryAction)
        assertEquals("Yaw follow requires explicit re-arm", rearm.instruction)
    }
}
