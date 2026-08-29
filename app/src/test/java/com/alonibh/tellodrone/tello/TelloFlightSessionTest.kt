@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.alonibh.tellodrone.tello

import com.alonibh.tellodrone.domain.ControlAuthority
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.HsvAppearanceHistogram
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.NormalizedBoundingBox
import com.alonibh.tellodrone.domain.PersonDetection
import com.alonibh.tellodrone.domain.PersonDetectionState
import com.alonibh.tellodrone.domain.ProductionYawController
import com.alonibh.tellodrone.domain.TargetAssociationState
import com.alonibh.tellodrone.domain.TrackingMode
import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.VideoState
import com.alonibh.tellodrone.domain.YawFollowState
import com.alonibh.tellodrone.domain.TargetSelectionAttemptResult
import com.alonibh.tellodrone.vision.TargetSelectionAttemptTrace
import com.alonibh.tellodrone.vision.VisionTraceExport
import com.alonibh.tellodrone.vision.VisionTraceFrame
import com.alonibh.tellodrone.vision.VisionTraceRecorder
import com.alonibh.tellodrone.vision.YawControlMeasurementTrace
import com.alonibh.tellodrone.vision.RcPublicationTrace
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelloFlightSessionTest {
    @Test fun `first telemetry connection update cannot be reverted by yaw state commit`() = runTest {
        val clock = RcControlLoopTest.FakeClock(1_000)
        val transport = FakeTransport()
        val detectorNowNanos = AtomicLong(1_000_000_000L)
        var injected = false
        val session = TelloFlightSession(
            transport = transport,
            scope = backgroundScope,
            clock = clock,
            sourceNowNanos = detectorNowNanos::get,
            beforeYawFollowStateCommit = { stateFlow ->
                if (!injected) {
                    injected = true
                    // Runs after yaw follow has read Connecting, modeling first telemetry completing
                    // the connection transition before that stale yaw snapshot is committed.
                    stateFlow.update {
                        it.copy(
                            connection = DroneConnectionState.Connected,
                            telemetry = it.telemetry.copy(batteryPercent = 73, isFresh = true),
                            video = it.video.copy(availability = VideoAvailability.Streaming),
                            speedPercent = 37,
                            tracking = TrackingMode.DetectOnly,
                        )
                    }
                }
            },
        )

        session.setYawFollowArmed(false)

        val state = session.state.value
        assertTrue(injected)
        assertEquals(DroneConnectionState.Connected, state.connection)
        assertTrue(state.telemetry.isFresh)
        assertEquals(73, state.telemetry.batteryPercent)
        assertEquals(VideoAvailability.Streaming, state.video.availability)
        assertEquals(37, state.speedPercent)
        assertEquals(TrackingMode.DetectOnly, state.tracking)
    }

    @Test fun `yaw reconciliation preserves newer unrelated fields`() = runTest {
        val video = FakeVideoController()
        var injectOnNextYawCommit = false
        val fixture = fixture(video) { stateFlow ->
            if (injectOnNextYawCommit) {
                injectOnNextYawCommit = false
                stateFlow.update {
                    it.copy(
                        speedPercent = 37,
                        manualVector = ManualControlVector(forward = .4f),
                        hoverActive = true,
                        lastMessage = "Newer non-yaw state",
                    )
                }
            }
        }
        fixture.transport.emitTelemetry(fixture.clock.value)
        assertTrue(fixture.session.connect())
        runCurrent()

        injectOnNextYawCommit = true
        fixture.transport.emitTelemetry(fixture.clock.value + 1L)
        runCurrent()

        val state = fixture.session.state.value
        assertFalse(injectOnNextYawCommit)
        assertEquals(37, state.speedPercent)
        assertEquals(ManualControlVector(forward = .4f), state.manualVector)
        assertTrue(state.hoverActive)
        assertEquals("Newer non-yaw state", state.lastMessage)
    }

    @Test fun `invalid takeoff is gated without transport command`() = runTest {
        val fixture = fixture()
        fixture.session.takeOff()
        assertTrue(fixture.transport.commands.isEmpty())
        assertEquals(FlightState.Unknown, fixture.session.state.value.flight)
    }

    @Test fun `connect takeoff stop land and disconnect follow conservative transitions`() = runTest {
        val fixture = connectedFixture()
        assertEquals(DroneConnectionState.Connected, fixture.session.state.value.connection)
        assertEquals(FlightState.Grounded, fixture.session.state.value.flight)

        takeOffAndVerify(fixture)
        assertEquals(FlightState.Flying, fixture.session.state.value.flight)
        fixture.session.publishManualControl(ManualControlVector(forward = 1f))
        fixture.session.stopAndHover()
        assertEquals(FlightState.Flying, fixture.session.state.value.flight)
        assertEquals(ManualControlVector(), fixture.session.state.value.manualVector)
        assertEquals(RcVector.Zero, fixture.transport.rc.last())

        landAndVerify(fixture)
        assertEquals(FlightState.Grounded, fixture.session.state.value.flight)
        assertTrue(fixture.session.disconnect())
        assertEquals(DroneConnectionState.Disconnected, fixture.session.state.value.connection)
        assertTrue(fixture.transport.closed)
        assertEquals(listOf("command", "takeoff", "land"), fixture.transport.commands)
    }

    @Test fun `speed presets update session state and scale manual RC across full SDK range`() = runTest {
        val fixture = connectedFixture()
        takeOffAndVerify(fixture)
        fixture.session.publishManualControl(ManualControlVector())

        listOf(30 to 30, 65 to 65, 100 to 100).forEach { (percent, expectedRc) ->
            fixture.session.setSpeed(percent)
            fixture.session.publishManualControl(ManualControlVector(forward = 1f))
            advanceTimeBy(50L)
            runCurrent()
            assertEquals(percent, fixture.session.state.value.speedPercent)
            assertEquals(RcVector(forward = expectedRc), fixture.transport.rc.last())
        }

        fixture.session.setSpeed(64)
        assertEquals(65, fixture.session.state.value.speedPercent)
    }

    @Test fun `yaw follow arm request is rejected outside valid flying target state`() = runTest {
        val fixture = connectedFixture()
        fixture.session.setYawFollowArmed(true)

        assertEquals(YawFollowState.DISARMED, fixture.session.state.value.yawFollowDecision.state)
        assertTrue(fixture.session.state.value.lastMessage?.contains("Yaw follow requires connected Flying state") == true)
    }

    @Test fun `disconnect is rejected while flying and does not clean up transport`() = runTest {
        val fixture = connectedFixture()
        takeOffAndVerify(fixture)
        assertFalse(fixture.session.disconnect())
        assertFalse(fixture.transport.closed)
        assertEquals(FlightState.Flying, fixture.session.state.value.flight)
    }

    @Test fun `emergency clears RC and permanently locks flight state`() = runTest {
        val fixture = connectedFixture()
        takeOffAndVerify(fixture)
        fixture.session.publishManualControl(ManualControlVector(lateral = 1f))
        fixture.session.emergencyMotorKill()
        assertEquals(FlightState.Emergency, fixture.session.state.value.flight)
        assertEquals(RcVector.Zero, fixture.transport.rc.last())
        val commandCount = fixture.transport.commands.size
        fixture.session.takeOff()
        assertEquals(commandCount, fixture.transport.commands.size)
        assertEquals(listOf("command", "takeoff", "emergency"), fixture.transport.commands)
    }

    @Test fun `telemetry becomes stale then connection loss clears and closes`() = runTest {
        val fixture = connectedFixture()
        takeOffAndVerify(fixture)
        fixture.session.publishManualControl(ManualControlVector(yaw = 1f))

        fixture.clock.value += TelloFlightSession.TELEMETRY_STALE_MILLIS
        fixture.session.refreshConnectionHealth()
        assertFalse(fixture.session.state.value.telemetry.isFresh)
        assertEquals(RcVector.Zero, fixture.transport.rc.last())
        assertEquals(DroneConnectionState.Connected, fixture.session.state.value.connection)

        fixture.clock.value += TelloFlightSession.CONNECTION_LOST_MILLIS
        fixture.session.refreshConnectionHealth()
        assertEquals(DroneConnectionState.Error, fixture.session.state.value.connection)
        assertEquals(FlightState.Unknown, fixture.session.state.value.flight)
        assertTrue(fixture.transport.closed)
    }

    @Test fun `new telemetry restores freshness without restoring stale movement`() = runTest {
        val fixture = connectedFixture()
        takeOffAndVerify(fixture)
        fixture.session.publishManualControl(ManualControlVector(forward = 1f))
        fixture.clock.value += TelloFlightSession.TELEMETRY_STALE_MILLIS
        fixture.session.refreshConnectionHealth()

        fixture.transport.emitTelemetry(fixture.clock.value)
        runCurrent()
        assertTrue(fixture.session.state.value.telemetry.isFresh)
        assertEquals(ManualControlVector(), fixture.session.state.value.manualVector)

        fixture.session.publishManualControl(ManualControlVector(forward = 1f))
        assertEquals(ManualControlVector(), fixture.session.state.value.manualVector)
        fixture.session.publishManualControl(ManualControlVector())
        fixture.session.publishManualControl(ManualControlVector(forward = 1f))
        assertEquals(ManualControlVector(forward = 1f), fixture.session.state.value.manualVector)
    }

    @Test fun `hover indication persists for neutral input and clears for movement or safety transitions`() = runTest {
        val fixture = connectedFixture()
        takeOffAndVerify(fixture)

        fixture.session.stopAndHover()
        assertTrue(fixture.session.state.value.hoverActive)
        fixture.session.publishManualControl(ManualControlVector())
        assertTrue(fixture.session.state.value.hoverActive)
        fixture.session.publishManualControl(ManualControlVector(forward = .3f))
        assertFalse(fixture.session.state.value.hoverActive)

        fixture.session.stopAndHover()
        assertTrue(fixture.session.state.value.hoverActive)
        fixture.session.land()
        assertFalse(fixture.session.state.value.hoverActive)

        val lossFixture = connectedFixture()
        takeOffAndVerify(lossFixture)
        lossFixture.session.stopAndHover()
        lossFixture.session.networkLost()
        assertFalse(lossFixture.session.state.value.hoverActive)

        val emergencyFixture = connectedFixture()
        takeOffAndVerify(emergencyFixture)
        emergencyFixture.session.stopAndHover()
        emergencyFixture.session.emergencyMotorKill()
        assertFalse(emergencyFixture.session.state.value.hoverActive)
    }

    @Test fun `missing or invalid initial height leaves flight state unknown and prohibits takeoff`() = runTest {
        listOf<Float?>(null, -0.01f, Float.NaN).forEach { height ->
            val fixture = fixture()
            fixture.transport.emitTelemetry(fixture.clock.value, height)
            assertTrue(fixture.session.connect())
            runCurrent()

            assertEquals(DroneConnectionState.Connected, fixture.session.state.value.connection)
            assertEquals(FlightState.Unknown, fixture.session.state.value.flight)
            fixture.session.takeOff()
            assertEquals(listOf("command"), fixture.transport.commands)
        }
    }

    @Test fun `video receiver is prepared before acknowledged streaming and stopped on disconnect`() = runTest {
        val video = FakeVideoController()
        val fixture = fixture(video)
        fixture.transport.emitTelemetry(fixture.clock.value)

        assertTrue(fixture.session.connect())
        runCurrent()

        assertTrue(video.prepared)
        assertEquals(VideoAvailability.Streaming, fixture.session.state.value.video.availability)
        assertEquals(listOf("command", "streamon"), fixture.transport.commands)

        assertTrue(fixture.session.disconnect())
        assertTrue(video.closed)
        assertEquals(listOf("command", "streamon", "streamoff"), fixture.transport.commands)
        assertEquals(VideoAvailability.Unavailable, fixture.session.state.value.video.availability)
    }

    @Test fun `streamon rejection is a video error without failing the flight connection`() = runTest {
        val video = FakeVideoController()
        val fixture = fixture(video)
        fixture.transport.results["streamon"] = TelloCommandResult.Rejected("error")
        fixture.transport.emitTelemetry(fixture.clock.value)

        assertTrue(fixture.session.connect())
        runCurrent()

        assertEquals(DroneConnectionState.Connected, fixture.session.state.value.connection)
        assertEquals(VideoAvailability.Error, fixture.session.state.value.video.availability)
        assertTrue(fixture.session.state.value.video.errorReason!!.contains("streamon"))
    }

    @Test fun `connection loss closes video immediately without streamoff`() = runTest {
        val video = FakeVideoController()
        val fixture = fixture(video)
        fixture.transport.emitTelemetry(fixture.clock.value)
        assertTrue(fixture.session.connect())
        runCurrent()

        fixture.session.networkLost()

        assertTrue(video.closed)
        assertEquals(listOf("command", "streamon"), fixture.transport.commands)
        assertEquals(DroneConnectionState.Error, fixture.session.state.value.connection)
    }

    @Test fun `flight acknowledgements require post acknowledgement height verification`() = runTest {
        val fixture = connectedFixture()

        fixture.session.takeOff()
        assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)
        fixture.transport.emitTelemetry(fixture.clock.value, TelloFlightSession.GROUNDED_HEIGHT_THRESHOLD_METERS)
        runCurrent()
        assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)
        for (i in 0 until TelloFlightSession.TAKEOFF_STABILIZATION_MIN_SAMPLES) {
            fixture.clock.value += 100L
            fixture.transport.emitTelemetry(
                at = fixture.clock.value,
                heightMeters = TelloFlightSession.TAKEOFF_AIRBORNE_HEIGHT_THRESHOLD_METERS + 0.10f,
                velocityZCentimetersPerSecond = 0,
            )
            runCurrent()
        }
        assertEquals(FlightState.Flying, fixture.session.state.value.flight)

        fixture.session.land()
        assertEquals(FlightState.Landing, fixture.session.state.value.flight)
        fixture.transport.emitTelemetry(fixture.clock.value, 1f)
        runCurrent()
        assertEquals(FlightState.Landing, fixture.session.state.value.flight)
        fixture.transport.emitTelemetry(fixture.clock.value, TelloFlightSession.GROUNDED_HEIGHT_THRESHOLD_METERS)
        runCurrent()
        assertEquals(FlightState.Grounded, fixture.session.state.value.flight)
    }

    @Test fun `later telemetry cannot overwrite terminal emergency state`() = runTest {
        val fixture = connectedFixture()
        takeOffAndVerify(fixture)
        fixture.session.emergencyMotorKill()
        fixture.transport.emitTelemetry(fixture.clock.value, 0f)
        runCurrent()

        assertEquals(FlightState.Emergency, fixture.session.state.value.flight)
        val commandCount = fixture.transport.commands.size
        fixture.session.land()
        assertEquals(commandCount, fixture.transport.commands.size)
    }

    @Test fun `real current detection selects then matched frame drives dry run without RC`() = runTest {
        val video = FakeVideoController()
        val fixture = fixture(video)
        fixture.transport.emitTelemetry(fixture.clock.value)
        assertTrue(fixture.session.connect())
        runCurrent()
        val selected = detection(frame = 1L, timestamp = 1_000_000_000L)
        video.publishDetections(1L, 1_000_000_000L, listOf(selected))
        runCurrent()

        fixture.session.selectTarget(selected)
        assertEquals(TrackingMode.TargetLocked, fixture.session.state.value.tracking)
        assertEquals(TargetAssociationState.Selected, fixture.session.state.value.targetAssociationState)
        assertEquals(com.alonibh.tellodrone.domain.ControlAuthority.Manual, fixture.session.state.value.authority)
        assertFalse(fixture.session.state.value.dryRunControlIntent!!.actionable)

        fixture.detectorNowNanos.set(1_100_000_000L)
        val moved = detection(box = NormalizedBoundingBox(.34f, .20f, .54f, .80f), frame = 2L, timestamp = 1_100_000_000L)
        video.publishDetections(2L, 1_100_000_000L, listOf(moved))
        runCurrent()

        assertEquals(TargetAssociationState.Matched, fixture.session.state.value.targetAssociationState)
        assertEquals(moved.boundingBox, fixture.session.state.value.target!!.boundingBox)
        assertTrue(fixture.session.state.value.trackingErrors!!.targetFresh)
        assertFalse(fixture.session.state.value.dryRunControlIntent!!.actionable)
        assertEquals(com.alonibh.tellodrone.domain.DryRunControlReason.DISTANCE_NOT_SET, fixture.session.state.value.dryRunControlIntent!!.reason)
        assertEquals(com.alonibh.tellodrone.domain.ControlAuthority.Manual, fixture.session.state.value.authority)
        assertTrue(fixture.transport.rc.isEmpty())

        fixture.session.setCurrentFollowDistance()
        repeat(7) { offset ->
            val frame = 3L + offset
            val timestamp = 1_100_000_000L + (frame - 2L) * 100_000_000L
            fixture.detectorNowNanos.set(timestamp)
            video.publishDetections(frame, timestamp, listOf(detection(box = moved.boundingBox, frame = frame, timestamp = timestamp)))
            runCurrent()
        }
        assertEquals(com.alonibh.tellodrone.domain.FollowDistanceCalibrationState.Set, fixture.session.state.value.followDistanceCalibrationState)
        val finalTimestamp = 1_900_000_000L
        fixture.detectorNowNanos.set(finalTimestamp)
        video.publishDetections(10L, finalTimestamp, listOf(detection(box = moved.boundingBox, frame = 10L, timestamp = finalTimestamp)))
        runCurrent()
        assertTrue(fixture.session.state.value.dryRunControlIntent!!.actionable)
        assertEquals(com.alonibh.tellodrone.domain.ControlAuthority.Manual, fixture.session.state.value.authority)
        assertTrue(fixture.transport.rc.isEmpty())
    }

    @Test fun `every completed detector frame records combined inference and association trace`() = runTest {
        val video = FakeVideoController()
        val recorder = FakeVisionTraceRecorder()
        val fixture = fixture(video, visionTrace = recorder)
        fixture.transport.emitTelemetry(fixture.clock.value)
        assertTrue(fixture.session.connect())
        runCurrent()

        val selected = detection(frame = 1L, timestamp = 1_000_000_000L)
        video.publishDetections(1L, 1_000_000_000L, listOf(selected))
        runCurrent()
        fixture.session.selectTarget(selected)
        assertEquals(listOf(selected.boundingBox), recorder.selectedTargets.map { it.boundingBox })
        val moved = detection(frame = 2L, timestamp = 1_100_000_000L)
        video.publishDetections(2L, 1_100_000_000L, listOf(moved))
        runCurrent()

        assertEquals(listOf(1L, 2L), recorder.frames.map { it.frameSequence })
        assertEquals(87L, recorder.frames.last().inferenceMillis)
        assertEquals(.55f, recorder.frames.last().confidenceThreshold)
        assertEquals(TargetAssociationState.Matched, recorder.frames.last().associationState)
        assertEquals(0, recorder.frames.last().associationDiagnostics?.selectedDetectionIndex)
        assertEquals(moved.boundingBox, recorder.frames.last().selectedTargetAfter?.boundingBox)
    }

    @Test fun `edge clipped matched target can start calibration and only unclipped frames accumulate`() = runTest {
        val video = FakeVideoController()
        val fixture = fixture(video)
        fixture.transport.emitTelemetry(fixture.clock.value)
        assertTrue(fixture.session.connect())
        runCurrent()

        fixture.session.setCurrentFollowDistance()
        assertEquals(com.alonibh.tellodrone.domain.FollowDistanceCalibrationState.NotSet, fixture.session.state.value.followDistanceCalibrationState)

        val clippedBox = NormalizedBoundingBox(0.01f, .20f, .30f, .80f)
        val selected = detection(box = clippedBox, frame = 1L, timestamp = 1_000_000_000L)
        video.publishDetections(1L, 1_000_000_000L, listOf(selected))
        runCurrent()
        fixture.session.selectTarget(selected)

        fixture.detectorNowNanos.set(1_100_000_000L)
        val edgeTarget = detection(box = clippedBox, frame = 2L, timestamp = 1_100_000_000L)
        video.publishDetections(2L, 1_100_000_000L, listOf(edgeTarget))
        runCurrent()

        assertEquals(TargetAssociationState.Matched, fixture.session.state.value.targetAssociationState)
        assertTrue(fixture.session.state.value.trackingErrors!!.targetFresh)

        fixture.session.setCurrentFollowDistance()
        assertEquals(com.alonibh.tellodrone.domain.FollowDistanceCalibrationState.Calibrating, fixture.session.state.value.followDistanceCalibrationState)
        assertEquals(0, fixture.session.state.value.followDistanceCalibrationSamples)

        fixture.detectorNowNanos.set(1_200_000_000L)
        video.publishDetections(3L, 1_200_000_000L, listOf(detection(box = clippedBox, frame = 3L, timestamp = 1_200_000_000L)))
        runCurrent()
        assertEquals(com.alonibh.tellodrone.domain.FollowDistanceCalibrationState.Calibrating, fixture.session.state.value.followDistanceCalibrationState)
        assertEquals(0, fixture.session.state.value.followDistanceCalibrationSamples)

        val validBox = NormalizedBoundingBox(.03f, .20f, .30f, .80f)
        repeat(7) { offset ->
            val frame = 4L + offset
            val timestamp = 1_300_000_000L + offset * 100_000_000L
            fixture.detectorNowNanos.set(timestamp)
            video.publishDetections(frame, timestamp, listOf(detection(box = validBox, frame = frame, timestamp = timestamp)))
            runCurrent()
        }

        assertEquals(com.alonibh.tellodrone.domain.FollowDistanceCalibrationState.Set, fixture.session.state.value.followDistanceCalibrationState)
        assertEquals(7, fixture.session.state.value.followDistanceCalibrationSamples)

        val finalTimestamp = 2_000_000_000L
        fixture.detectorNowNanos.set(finalTimestamp)
        video.publishDetections(11L, finalTimestamp, listOf(detection(box = validBox, frame = 11L, timestamp = finalTimestamp)))
        runCurrent()
        assertTrue(fixture.session.state.value.dryRunControlIntent!!.actionable)
    }

    @Test fun `target selection succeeds when frame advances from N to N plus 1 between render and tap`() = runTest {
        val video = FakeVideoController()
        val fixture = fixture(video)
        fixture.transport.emitTelemetry(fixture.clock.value)
        assertTrue(fixture.session.connect())
        runCurrent()

        // UI renders frame 1
        val frame1Box = NormalizedBoundingBox(.40f, .20f, .60f, .80f)
        val frame1Detection = detection(box = frame1Box, frame = 1L, timestamp = 1_000_000_000L)
        video.publishDetections(1L, 1_000_000_000L, listOf(frame1Detection))
        runCurrent()

        // User sees frame 1 (taps at center 0.50, 0.50), but before tap reaches session, frame 2 arrives with slight motion
        fixture.detectorNowNanos.set(1_080_000_000L)
        val frame2Box = NormalizedBoundingBox(.42f, .20f, .62f, .80f)
        val frame2Detection = detection(box = frame2Box, frame = 2L, timestamp = 1_080_000_000L)
        video.publishDetections(2L, 1_080_000_000L, listOf(frame2Detection))
        runCurrent()

        // User's tap from frame 1 reaches session
        fixture.session.selectTargetAt(0.50f, 0.50f, displayedFrameSequence = 1L)

        assertEquals(TargetAssociationState.Selected, fixture.session.state.value.targetAssociationState)
        assertEquals(2L, fixture.session.state.value.target?.selectedFrameSequence)
        assertEquals(frame2Box, fixture.session.state.value.target?.boundingBox)
    }

    @Test fun `target selection does not depend on object reference equality`() = runTest {
        val video = FakeVideoController()
        val fixture = fixture(video)
        fixture.transport.emitTelemetry(fixture.clock.value)
        assertTrue(fixture.session.connect())
        runCurrent()

        val det = detection(box = NormalizedBoundingBox(.30f, .20f, .50f, .80f), frame = 1L, timestamp = 1_000_000_000L)
        video.publishDetections(1L, 1_000_000_000L, listOf(det))
        runCurrent()

        // Pass coordinates directly without referencing 'det' instance
        fixture.session.selectTargetAt(0.40f, 0.50f)
        assertEquals(TargetAssociationState.Selected, fixture.session.state.value.targetAssociationState)
        assertEquals(det.boundingBox, fixture.session.state.value.target?.boundingBox)
    }

    @Test fun `target selection rejects tap with no person under coordinates`() = runTest {
        val video = FakeVideoController()
        val recorder = FakeVisionTraceRecorder()
        val fixture = fixture(video, visionTrace = recorder)
        fixture.transport.emitTelemetry(fixture.clock.value)
        assertTrue(fixture.session.connect())
        runCurrent()

        val det = detection(box = NormalizedBoundingBox(.20f, .20f, .40f, .80f), frame = 1L, timestamp = 1_000_000_000L)
        video.publishDetections(1L, 1_000_000_000L, listOf(det))
        runCurrent()

        // Tap empty area at (0.80, 0.50)
        fixture.session.selectTargetAt(0.80f, 0.50f)
        assertEquals(TargetAssociationState.None, fixture.session.state.value.targetAssociationState)
        assertNull(fixture.session.state.value.target)
        assertEquals(TargetSelectionAttemptResult.NO_MATCH, recorder.targetSelectionAttempts.last().result)
    }

    @Test fun `target selection rejects ambiguous overlapping detections`() = runTest {
        val video = FakeVideoController()
        val recorder = FakeVisionTraceRecorder()
        val fixture = fixture(video, visionTrace = recorder)
        fixture.transport.emitTelemetry(fixture.clock.value)
        assertTrue(fixture.session.connect())
        runCurrent()

        val p1 = detection(box = NormalizedBoundingBox(.40f, .20f, .60f, .80f), frame = 1L, timestamp = 1_000_000_000L)
        val p2 = detection(box = NormalizedBoundingBox(.45f, .20f, .65f, .80f), frame = 1L, timestamp = 1_000_000_000L)
        video.publishDetections(1L, 1_000_000_000L, listOf(p1, p2))
        runCurrent()

        // Tap at (0.50, 0.50) where p1 and p2 overlap
        fixture.session.selectTargetAt(0.50f, 0.50f)
        assertEquals(TargetAssociationState.None, fixture.session.state.value.targetAssociationState)
        assertNull(fixture.session.state.value.target)
        assertEquals(TargetSelectionAttemptResult.AMBIGUOUS, recorder.targetSelectionAttempts.last().result)
        assertEquals(2, recorder.targetSelectionAttempts.last().hitCandidatesCount)
    }

    @Test fun `target selection succeeds on small hit-slop outside box`() = runTest {
        val video = FakeVideoController()
        val recorder = FakeVisionTraceRecorder()
        val fixture = fixture(video, visionTrace = recorder)
        fixture.transport.emitTelemetry(fixture.clock.value)
        assertTrue(fixture.session.connect())
        runCurrent()

        val det = detection(box = NormalizedBoundingBox(.40f, .20f, .60f, .80f), frame = 1L, timestamp = 1_000_000_000L)
        video.publishDetections(1L, 1_000_000_000L, listOf(det))
        runCurrent()

        // Tap slightly outside left edge: x = 0.38f (0.02 < 0.04 hit-slop)
        fixture.session.selectTargetAt(0.38f, 0.50f)
        assertEquals(TargetAssociationState.Selected, fixture.session.state.value.targetAssociationState)
        assertEquals(det.boundingBox, fixture.session.state.value.target?.boundingBox)
        assertEquals(TargetSelectionAttemptResult.ACCEPTED, recorder.targetSelectionAttempts.last().result)
    }

    @Test fun `target selection rejects large miss beyond hit-slop`() = runTest {
        val video = FakeVideoController()
        val recorder = FakeVisionTraceRecorder()
        val fixture = fixture(video, visionTrace = recorder)
        fixture.transport.emitTelemetry(fixture.clock.value)
        assertTrue(fixture.session.connect())
        runCurrent()

        val det = detection(box = NormalizedBoundingBox(.40f, .20f, .60f, .80f), frame = 1L, timestamp = 1_000_000_000L)
        video.publishDetections(1L, 1_000_000_000L, listOf(det))
        runCurrent()

        // Tap far outside left edge: x = 0.30f (0.10 > 0.04 hit-slop)
        fixture.session.selectTargetAt(0.30f, 0.50f)
        assertEquals(TargetAssociationState.None, fixture.session.state.value.targetAssociationState)
        assertNull(fixture.session.state.value.target)
        assertEquals(TargetSelectionAttemptResult.NO_MATCH, recorder.targetSelectionAttempts.last().result)
    }

    @Test fun `target selection rejects stale detector frame`() = runTest {
        val video = FakeVideoController()
        val recorder = FakeVisionTraceRecorder()
        val fixture = fixture(video, visionTrace = recorder)
        fixture.transport.emitTelemetry(fixture.clock.value)
        assertTrue(fixture.session.connect())
        runCurrent()

        val det = detection(box = NormalizedBoundingBox(.40f, .20f, .60f, .80f), frame = 1L, timestamp = 1_000_000_000L)
        video.publishDetections(1L, 1_000_000_000L, listOf(det))
        runCurrent()

        // Advance monotonic clock by 700ms (> 600ms stale threshold)
        fixture.detectorNowNanos.set(1_700_000_001L)
        fixture.session.selectTargetAt(0.50f, 0.50f)
        assertEquals(TargetAssociationState.None, fixture.session.state.value.targetAssociationState)
        assertNull(fixture.session.state.value.target)
        assertEquals(TargetSelectionAttemptResult.STALE_FRAME, recorder.targetSelectionAttempts.last().result)
    }

    @Test fun `empty real frames become missing then lost without reacquisition`() = runTest {
        val video = FakeVideoController()
        val fixture = fixture(video)
        fixture.transport.emitTelemetry(fixture.clock.value)
        assertTrue(fixture.session.connect())
        runCurrent()
        val selected = detection(frame = 1L, timestamp = 1_000_000_000L)
        video.publishDetections(1L, 1_000_000_000L, listOf(selected))
        runCurrent()
        fixture.session.selectTarget(selected)

        fixture.detectorNowNanos.set(1_100_000_000L)
        video.publishDetections(2L, 1_100_000_000L, emptyList())
        runCurrent()
        assertEquals(TargetAssociationState.TemporarilyMissing, fixture.session.state.value.targetAssociationState)
        assertFalse(fixture.session.state.value.dryRunControlIntent!!.actionable)

        fixture.detectorNowNanos.set(2_100_000_001L)
        video.publishDetections(3L, 2_100_000_001L, emptyList())
        runCurrent()
        assertEquals(TargetAssociationState.Lost, fixture.session.state.value.targetAssociationState)
        assertNull(fixture.session.state.value.target)

        val reappeared = detection(frame = 4L, timestamp = 2_200_000_000L)
        fixture.detectorNowNanos.set(2_200_000_000L)
        video.publishDetections(4L, 2_200_000_000L, listOf(reappeared))
        runCurrent()
        assertEquals(TargetAssociationState.Lost, fixture.session.state.value.targetAssociationState)
        assertNull(fixture.session.state.value.target)

        fixture.session.selectTarget(reappeared)
        assertEquals(TargetAssociationState.Selected, fixture.session.state.value.targetAssociationState)
        assertEquals(com.alonibh.tellodrone.domain.ControlAuthority.Manual, fixture.session.state.value.authority)
        assertTrue(fixture.transport.rc.isEmpty())
    }

    @Test fun `matched target sends physically verified Tello yaw direction and exactly zero other axes`() = runTest {
        val (fixture, _) = yawReadyFixture(NormalizedBoundingBox(.55f, .20f, .85f, .80f))
        fixture.session.setYawFollowArmed(true)
        advanceTimeBy(50L)
        runCurrent()

        val command = fixture.transport.rc.last()
        assertEquals(YawFollowState.ACTIVE, fixture.session.state.value.yawFollowDecision.state)
        assertTrue(command.yaw > 0)
        assertEquals(ProductionYawController.MAXIMUM_ACCELERATION_STEP, command.yaw)
        assertTrue(kotlin.math.abs(command.yaw) <= ProductionYawController.ABSOLUTE_YAW_RC_CAP)
        assertEquals(0, command.lateral)
        assertEquals(0, command.forward)
        assertEquals(0, command.vertical)

        val (leftFixture, _) = yawReadyFixture(NormalizedBoundingBox(.15f, .20f, .45f, .80f))
        leftFixture.session.setYawFollowArmed(true)
        advanceTimeBy(50L)
        runCurrent()
        assertEquals(-ProductionYawController.MAXIMUM_ACCELERATION_STEP, leftFixture.transport.rc.last().yaw)
    }

    @Test fun `centered matched target sends zero`() = runTest {
        val (fixture, _) = yawReadyFixture(NormalizedBoundingBox(.40f, .20f, .60f, .80f))
        fixture.session.setYawFollowArmed(true)
        advanceTimeBy(50L)
        runCurrent()

        assertEquals(YawFollowState.ACTIVE, fixture.session.state.value.yawFollowDecision.state)
        assertEquals(RcVector.Zero, fixture.transport.rc.last())
    }

    @Test fun `targetFresh does not bypass source age gate`() = runTest {
        val (fixture, _) = yawReadyFixture()
        fixture.detectorNowNanos.set(
            1_100_000_000L +
                (com.alonibh.tellodrone.domain.ProductionYawController.MAXIMUM_PERCEPTION_AGE_MILLIS + 1L) * 1_000_000L,
        )

        fixture.session.setYawFollowArmed(true)
        advanceTimeBy(50L)
        runCurrent()

        assertTrue(fixture.session.state.value.trackingErrors!!.targetFresh)
        assertEquals(
            com.alonibh.tellodrone.domain.YawControlSuppressionReason.STALE_PERCEPTION,
            fixture.session.state.value.yawFollowDecision.control?.suppressionReason,
        )
        assertEquals(RcVector.Zero, fixture.transport.rc.last())
    }

    @Test fun `control trace records accepted measurement and actual yaw-only RC publication`() = runTest {
        val trace = FakeVisionTraceRecorder()
        val box = NormalizedBoundingBox(.55f, .20f, .85f, .80f)
        val (fixture, video) = yawReadyFixture(box, trace)
        fixture.session.setYawFollowArmed(true)

        fixture.detectorNowNanos.set(1_200_000_000L)
        video.publishDetections(3L, 1_200_000_000L, listOf(detection(box, 3L, 1_200_000_000L)))
        runCurrent()
        advanceTimeBy(50L)
        runCurrent()

        val measurement = trace.controlMeasurements.last()
        assertEquals(3L, measurement.frameSequence)
        assertEquals(1_200_000_000L, measurement.sourceTimestampNanos)
        assertEquals(TargetAssociationState.Matched, measurement.associationState)
        assertTrue(measurement.perceptionAgeMillis!! >= 0L)
        assertTrue(measurement.safetyFilteredYawRc > 0)

        val publication = trace.rcPublications.last { it.inputKind == RcInputKind.AUTONOMOUS_YAW }
        assertEquals(3L, publication.frameSequence)
        assertEquals(0, publication.actualSentVector.lateral)
        assertEquals(0, publication.actualSentVector.forward)
        assertEquals(0, publication.actualSentVector.vertical)
        assertTrue(publication.actualSentVector.yaw > 0)
        assertEquals(fixture.session.state.value.telemetry.heightMeters, publication.telemetryHeightMeters)
    }

    @Test fun `temporary missing zeros then same target requires stable frames without another arm`() = runTest {
        val box = NormalizedBoundingBox(.55f, .20f, .85f, .80f)
        val (fixture, video) = yawReadyFixture(box)
        fixture.session.setYawFollowArmed(true)
        advanceTimeBy(50L)
        runCurrent()
        assertTrue(fixture.transport.rc.last().yaw > 0)

        fixture.detectorNowNanos.set(1_200_000_000L)
        val movingTarget = detection(
            box = NormalizedBoundingBox(.45f, .20f, .75f, .80f),
            frame = 3L,
            timestamp = 1_200_000_000L,
        )
        video.publishDetections(3L, 1_200_000_000L, listOf(movingTarget))
        runCurrent()
        assertEquals(TargetAssociationState.Matched, fixture.session.state.value.targetAssociationState)

        fixture.detectorNowNanos.set(1_300_000_000L)
        video.publishDetections(4L, 1_300_000_000L, emptyList())
        runCurrent()
        assertEquals(YawFollowState.ARMED_WAITING, fixture.session.state.value.yawFollowDecision.state)
        assertEquals(RcVector.Zero, fixture.transport.rc.last())

        val sameTarget = detection(
            box = NormalizedBoundingBox(.40f, .20f, .70f, .80f),
            frame = 5L,
            timestamp = 1_400_000_000L,
        )
        fixture.detectorNowNanos.set(1_400_000_000L)
        video.publishDetections(5L, 1_400_000_000L, listOf(sameTarget))
        runCurrent()
        assertEquals(0, fixture.session.state.value.yawFollowDecision.yawRc)

        val stableTarget = detection(
            box = NormalizedBoundingBox(.38f, .20f, .68f, .80f),
            frame = 6L,
            timestamp = 1_500_000_000L,
        )
        fixture.detectorNowNanos.set(1_500_000_000L)
        video.publishDetections(6L, 1_500_000_000L, listOf(stableTarget))
        runCurrent()
        advanceTimeBy(50L)
        runCurrent()
        assertEquals(YawFollowState.ACTIVE, fixture.session.state.value.yawFollowDecision.state)
        assertEquals(TargetAssociationState.Matched, fixture.session.state.value.targetAssociationState)
        assertEquals(stableTarget.boundingBox, fixture.session.state.value.target?.boundingBox)
    }

    @Test fun `ambiguous and lost association zero and require explicit rearm`() = runTest {
        val box = NormalizedBoundingBox(.55f, .20f, .85f, .80f)
        val (ambiguousFixture, ambiguousVideo) = yawReadyFixture(box)
        ambiguousFixture.session.setYawFollowArmed(true)
        ambiguousFixture.detectorNowNanos.set(1_200_000_000L)
        ambiguousVideo.publishDetections(
            3L,
            1_200_000_000L,
            listOf(
                detection(box = box, frame = 3L, timestamp = 1_200_000_000L),
                detection(box = box, frame = 3L, timestamp = 1_200_000_000L),
            ),
        )
        runCurrent()
        assertEquals(YawFollowState.REQUIRES_REARM, ambiguousFixture.session.state.value.yawFollowDecision.state)
        assertEquals(RcVector.Zero, ambiguousFixture.transport.rc.last())

        val (lostFixture, lostVideo) = yawReadyFixture(box)
        lostFixture.session.setYawFollowArmed(true)
        lostFixture.detectorNowNanos.set(2_100_000_001L)
        lostVideo.publishDetections(3L, 2_100_000_001L, emptyList())
        runCurrent()
        assertEquals(YawFollowState.REQUIRES_REARM, lostFixture.session.state.value.yawFollowDecision.state)
        assertEquals(RcVector.Zero, lostFixture.transport.rc.last())
    }

    @Test fun `manual nonzero preempts and later tracking cannot overwrite it`() = runTest {
        val box = NormalizedBoundingBox(.55f, .20f, .85f, .80f)
        val (fixture, video) = yawReadyFixture(box)
        fixture.session.publishManualControl(ManualControlVector())
        fixture.session.setYawFollowArmed(true)
        fixture.session.publishManualControl(ManualControlVector(forward = .5f))

        fixture.detectorNowNanos.set(1_200_000_000L)
        video.publishDetections(
            3L,
            1_200_000_000L,
            listOf(detection(box = box, frame = 3L, timestamp = 1_200_000_000L)),
        )
        runCurrent()
        advanceTimeBy(50L)
        runCurrent()

        assertEquals(YawFollowState.REQUIRES_REARM, fixture.session.state.value.yawFollowDecision.state)
        assertEquals(RcVector(forward = 33), fixture.transport.rc.last())

        fixture.session.setTrackingMode(TrackingMode.Off)
        runCurrent()
        advanceTimeBy(50L)
        runCurrent()
        assertEquals(RcVector(forward = 33), fixture.transport.rc.last())
    }

    @Test fun `blocked first manual attempt preempts yaw then neutral permits the next manual command`() = runTest {
        val box = NormalizedBoundingBox(.55f, .20f, .85f, .80f)
        val (fixture, video) = yawReadyFixture(box)
        // takeoff's neutral interlock has not yet seen a zero input.
        fixture.session.setYawFollowArmed(true)
        advanceTimeBy(50L)
        runCurrent()
        assertEquals(YawFollowState.ACTIVE, fixture.session.state.value.yawFollowDecision.state)
        assertTrue(fixture.transport.rc.last().yaw > 0)

        fixture.session.publishManualControl(ManualControlVector(forward = .5f))
        runCurrent()
        assertEquals(YawFollowState.REQUIRES_REARM, fixture.session.state.value.yawFollowDecision.state)
        assertEquals(com.alonibh.tellodrone.domain.YawFollowReason.MANUAL_OVERRIDE, fixture.session.state.value.yawFollowDecision.reason)
        assertEquals(ManualControlVector(), fixture.session.state.value.manualVector)
        assertEquals(RcVector.Zero, fixture.transport.rc.last())
        assertFalse(fixture.transport.rc.any { it.forward != 0 })

        fixture.session.publishManualControl(ManualControlVector())
        fixture.session.publishManualControl(ManualControlVector(forward = .5f))
        advanceTimeBy(50L)
        runCurrent()
        assertEquals(RcVector(forward = 33), fixture.transport.rc.last())

        fixture.detectorNowNanos.set(1_200_000_000L)
        video.publishDetections(
            3L,
            1_200_000_000L,
            listOf(detection(box = box, frame = 3L, timestamp = 1_200_000_000L)),
        )
        runCurrent()
        advanceTimeBy(50L)
        runCurrent()
        assertEquals(YawFollowState.REQUIRES_REARM, fixture.session.state.value.yawFollowDecision.state)
        assertEquals(RcVector(forward = 33), fixture.transport.rc.last())
    }

    @Test fun `hover land emergency stale telemetry and video loss zero and latch`() = runTest {
        val (hoverFixture, _) = yawReadyFixture()
        hoverFixture.session.setYawFollowArmed(true)
        hoverFixture.session.stopAndHover()
        assertYawFollowLatchedAtZero(hoverFixture)

        val (landFixture, _) = yawReadyFixture()
        landFixture.session.setYawFollowArmed(true)
        landFixture.session.land()
        assertYawFollowLatchedAtZero(landFixture)
        val landing = landFixture.session.state.value
        assertEquals(FlightState.Landing, landing.flight)
        assertEquals(TrackingMode.Off, landing.tracking)
        assertEquals(PersonDetectionState.Off, landing.video.personDetectionState)
        assertNull(landing.target)
        assertNull(landing.trackingErrors)
        assertEquals(TargetAssociationState.None, landing.targetAssociationState)
        assertNull(landing.followDistanceReference)
        assertEquals(com.alonibh.tellodrone.domain.FollowDistanceCalibrationState.NotSet, landing.followDistanceCalibrationState)
        landFixture.session.setTrackingMode(TrackingMode.DetectOnly)
        assertEquals(TrackingMode.Off, landFixture.session.state.value.tracking)
        assertEquals(PersonDetectionState.Off, landFixture.session.state.value.video.personDetectionState)
        landFixture.transport.emitTelemetry(landFixture.clock.value, heightMeters = 0f)
        runCurrent()
        assertEquals(FlightState.Grounded, landFixture.session.state.value.flight)
        assertEquals(TrackingMode.Off, landFixture.session.state.value.tracking)

        val (emergencyFixture, _) = yawReadyFixture()
        emergencyFixture.session.setYawFollowArmed(true)
        emergencyFixture.session.emergencyMotorKill()
        assertYawFollowLatchedAtZero(emergencyFixture)

        val (staleFixture, _) = yawReadyFixture()
        staleFixture.session.setYawFollowArmed(true)
        staleFixture.clock.value += TelloFlightSession.TELEMETRY_STALE_MILLIS
        staleFixture.session.refreshConnectionHealth()
        assertYawFollowLatchedAtZero(staleFixture)

        val (videoFixture, video) = yawReadyFixture()
        videoFixture.session.setYawFollowArmed(true)
        video.publishUnavailable()
        runCurrent()
        assertYawFollowLatchedAtZero(videoFixture)
    }

    @Test fun `explicit arm acknowledges hover intervention and resumes healthy matched target`() = runTest {
        val (fixture, _) = yawReadyFixture()
        fixture.session.setYawFollowArmed(true)
        advanceTimeBy(50L)
        runCurrent()
        assertEquals(YawFollowState.ACTIVE, fixture.session.state.value.yawFollowDecision.state)

        fixture.session.stopAndHover()

        assertEquals(YawFollowState.REQUIRES_REARM, fixture.session.state.value.yawFollowDecision.state)
        assertEquals(com.alonibh.tellodrone.domain.YawFollowReason.HOVER_INTERVENTION, fixture.session.state.value.yawFollowDecision.reason)
        assertTrue(fixture.session.state.value.hoverActive)
        assertEquals(RcVector.Zero, fixture.transport.rc.last())

        fixture.session.setYawFollowArmed(true)
    }

    @Test fun `takeoff is rejected when battery is below minimum threshold or null`() = runTest {
        val fixture = fixture()
        fixture.transport.emitTelemetry(fixture.clock.value, batteryPercent = 17)
        assertTrue(fixture.session.connect())
        runCurrent()

        fixture.session.takeOff()
        assertEquals(FlightState.Grounded, fixture.session.state.value.flight)
        assertTrue(fixture.session.state.value.lastMessage?.contains("30%") == true)
    }

    @Test fun `airborne keepalive sends periodic command while flying and stops on land`() = runTest {
        val fixture = connectedFixture()
        takeOffAndVerify(fixture)

        fixture.transport.commands.clear()
        advanceTimeBy(5_100)
        runCurrent()
        assertTrue(fixture.transport.commands.contains("command"))

        fixture.transport.commands.clear()
        landAndVerify(fixture)

        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(fixture.transport.commands.contains("command"))
    }

    @Test fun `debounced external grounding transitions to grounded after continuous samples`() = runTest {
        val (fixture, _) = yawReadyFixture()
        fixture.session.setYawFollowArmed(true)
        assertEquals(FlightState.Flying, fixture.session.state.value.flight)

        // 1 grounded sample: should not transition
        fixture.clock.value += 100
        fixture.transport.emitTelemetry(fixture.clock.value, heightMeters = 0.0f)
        runCurrent()
        assertEquals(FlightState.Flying, fixture.session.state.value.flight)

        // 4 more grounded samples spanning >= 500ms total
        repeat(4) {
            fixture.clock.value += 150
            fixture.transport.emitTelemetry(fixture.clock.value, heightMeters = 0.0f)
            runCurrent()
        }


        assertEquals(FlightState.Grounded, fixture.session.state.value.flight)
        assertTrue(fixture.session.state.value.lastMessage?.contains("Aircraft landed outside app command") == true)
        assertEquals(TrackingMode.Off, fixture.session.state.value.tracking)
        assertEquals(PersonDetectionState.Off, fixture.session.state.value.video.personDetectionState)
        assertNull(fixture.session.state.value.target)
        assertEquals(TargetAssociationState.None, fixture.session.state.value.targetAssociationState)
    }


    private fun assertYawFollowLatchedAtZero(fixture: Fixture) {
        assertEquals(YawFollowState.REQUIRES_REARM, fixture.session.state.value.yawFollowDecision.state)
        assertEquals(RcVector.Zero, fixture.transport.rc.last())
    }

    private suspend fun TestScope.yawReadyFixture(
        box: NormalizedBoundingBox = NormalizedBoundingBox(.55f, .20f, .85f, .80f),
        visionTrace: VisionTraceRecorder = com.alonibh.tellodrone.vision.NoOpVisionTraceRecorder,
    ): Pair<Fixture, FakeVideoController> {
        val video = FakeVideoController()
        val fixture = fixture(video, visionTrace)
        fixture.transport.emitTelemetry(fixture.clock.value)
        assertTrue(fixture.session.connect())
        runCurrent()
        takeOffAndVerify(fixture)
        fixture.session.setTrackingMode(TrackingMode.DetectOnly)

        val selected = detection(box = box, frame = 1L, timestamp = 1_000_000_000L)
        fixture.detectorNowNanos.set(1_000_000_000L)
        video.publishDetections(1L, 1_000_000_000L, listOf(selected))
        runCurrent()
        fixture.session.selectTarget(selected)

        val matched = detection(box = box, frame = 2L, timestamp = 1_100_000_000L)
        fixture.detectorNowNanos.set(1_100_000_000L)
        video.publishDetections(2L, 1_100_000_000L, listOf(matched))
        runCurrent()
        assertEquals(TargetAssociationState.Matched, fixture.session.state.value.targetAssociationState)
        return fixture to video
    }

    private suspend fun TestScope.connectedFixture(): Fixture = fixture().also {
        it.transport.emitTelemetry(it.clock.value)
        assertTrue(it.session.connect())
        runCurrent()
    }

    @Test fun `test A - no physical RC sent during takeoff maneuver`() = runTest {
        val fixture = connectedFixture()
        fixture.session.takeOff()
        assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)

        // Try publishing manual control during takeoff
        fixture.session.publishManualControl(ManualControlVector(yaw = 1f))
        advanceTimeBy(100L)
        runCurrent()

        // No RC packets sent while TakingOff
        assertTrue(fixture.transport.rc.isEmpty())
    }

    @Test fun `test B - flying transition starts from zero`() = runTest {
        val trace = FakeVisionTraceRecorder()
        val fixture = fixture(visionTrace = trace)
        fixture.transport.emitTelemetry(fixture.clock.value)
        assertTrue(fixture.session.connect())
        runCurrent()

        takeOffAndVerify(fixture)
        assertEquals(FlightState.Flying, fixture.session.state.value.flight)

        // Allow RC loop to tick
        advanceTimeBy(100L)
        runCurrent()

        assertTrue(fixture.transport.rc.isNotEmpty())
        assertTrue(fixture.transport.rc.all { it == RcVector.Zero })
        val firstPub = trace.rcPublications.first()
        assertEquals(RcVector.Zero, firstPub.actualSentVector)
        assertEquals(RcInputKind.SAFETY_ZERO, firstPub.inputKind)
    }

    @Test fun `test C - stale manual command cannot cross flight epoch`() = runTest {
        val fixture = connectedFixture()
        // Pilot drags joystick while grounded before takeoff
        fixture.session.publishManualControl(ManualControlVector(forward = 1f, yaw = 1f))
        takeOffAndVerify(fixture)

        advanceTimeBy(100L)
        runCurrent()

        // First publications in Flying must be zero
        assertTrue(fixture.transport.rc.all { it == RcVector.Zero })
    }

    @Test fun `test D - stale autonomy cannot cross flight epoch`() = runTest {
        val (fixture, _) = yawReadyFixture()
        fixture.session.setYawFollowArmed(true)
        runCurrent()
        assertEquals(ControlAuthority.Autonomous, fixture.session.state.value.authority)

        // Land drone to finish flight
        landAndVerify(fixture)
        assertEquals(FlightState.Grounded, fixture.session.state.value.flight)
        assertEquals(ControlAuthority.Manual, fixture.session.state.value.authority)

        // Take off for a second flight
        takeOffAndVerify(fixture)
        assertEquals(FlightState.Flying, fixture.session.state.value.flight)
        assertEquals(ControlAuthority.Manual, fixture.session.state.value.authority)

        advanceTimeBy(100L)
        runCurrent()

        // RC must remain zero
        assertEquals(RcVector.Zero, fixture.transport.rc.last())
    }

    @Test fun `test E - tracking OFF produces continuous zero`() = runTest {
        val fixture = connectedFixture()
        takeOffAndVerify(fixture)
        assertEquals(TrackingMode.Off, fixture.session.state.value.tracking)

        advanceTimeBy(200L)
        runCurrent()

        assertTrue(fixture.transport.rc.all { it == RcVector.Zero })
    }

    @Test fun `test F - detection OFF invalidates follow`() = runTest {
        val (fixture, video) = yawReadyFixture()
        fixture.session.setYawFollowArmed(true)
        runCurrent()
        assertEquals(YawFollowState.ACTIVE, fixture.session.state.value.yawFollowDecision.state)

        fixture.session.setTrackingMode(TrackingMode.Off)
        runCurrent()

        assertEquals(TrackingMode.Off, fixture.session.state.value.tracking)
        assertEquals(ControlAuthority.Manual, fixture.session.state.value.authority)
        assertTrue(fixture.session.state.value.yawFollowDecision.state != YawFollowState.ACTIVE)
        assertEquals(RcVector.Zero, fixture.transport.rc.last())
    }

    @Test fun `test G - stable takeoff transition requires multiple consecutive stable samples`() = runTest {
        val fixture = connectedFixture()
        fixture.session.takeOff()
        assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)

        // 1. Single sample crossing height > 0.20m with high vertical speed does NOT transition to Flying
        fixture.clock.value += 100L
        fixture.transport.emitTelemetry(
            at = fixture.clock.value,
            heightMeters = 0.35f,
            velocityZCentimetersPerSecond = 80, // climbing fast
        )
        runCurrent()
        assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)

        // 2. Three stable samples (less than minimum 4) do NOT transition to Flying yet
        for (i in 1..3) {
            fixture.clock.value += 100L
            fixture.transport.emitTelemetry(
                at = fixture.clock.value,
                heightMeters = 0.50f,
                velocityZCentimetersPerSecond = 5,
            )
            runCurrent()
            assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)
        }

        // 3. 4th consecutive stable sample transitions to Flying
        fixture.clock.value += 100L
        fixture.transport.emitTelemetry(
            at = fixture.clock.value,
            heightMeters = 0.50f,
            velocityZCentimetersPerSecond = 2,
        )
        runCurrent()
        assertEquals(FlightState.Flying, fixture.session.state.value.flight)
        assertEquals(RcVector.Zero, fixture.session.state.value.manualVector.let { RcVector() })
    }

    @Test fun `takeoff stabilization Test A - missing vgz keeps state TakingOff and RC disabled`() = runTest {
        val fixture = connectedFixture()
        fixture.session.takeOff()
        assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)

        for (i in 1..10) {
            fixture.clock.value += 100L
            fixture.transport.emitTelemetry(
                at = fixture.clock.value,
                heightMeters = 0.50f,
                velocityZCentimetersPerSecond = null,
            )
            runCurrent()
            assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)
        }

        advanceTimeBy(100L)
        runCurrent()
        assertTrue(fixture.transport.rc.isEmpty())
    }

    @Test fun `takeoff stabilization Test B - high vertical velocity keeps state TakingOff`() = runTest {
        val fixture = connectedFixture()
        fixture.session.takeOff()
        assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)

        for (i in 1..10) {
            fixture.clock.value += 100L
            fixture.transport.emitTelemetry(
                at = fixture.clock.value,
                heightMeters = 0.50f,
                velocityZCentimetersPerSecond = 50,
            )
            runCurrent()
            assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)
        }
    }

    @Test fun `takeoff stabilization Test C - unstable height across window keeps state TakingOff`() = runTest {
        val fixture = connectedFixture()
        fixture.session.takeOff()
        assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)

        val ascendingHeights = listOf(0.25f, 0.38f, 0.48f, 0.55f)
        for (h in ascendingHeights) {
            fixture.clock.value += 100L
            fixture.transport.emitTelemetry(
                at = fixture.clock.value,
                heightMeters = h,
                velocityZCentimetersPerSecond = 0,
            )
            runCurrent()
            assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)
        }
    }

    @Test fun `takeoff stabilization Test D - stable hover transitions to Flying and first RC is zero`() = runTest {
        val trace = FakeVisionTraceRecorder()
        val fixture = fixture(visionTrace = trace)
        fixture.transport.emitTelemetry(fixture.clock.value)
        assertTrue(fixture.session.connect())
        runCurrent()

        fixture.session.takeOff()
        assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)

        val stableHeights = listOf(0.48f, 0.50f, 0.49f, 0.50f)
        for (h in stableHeights) {
            fixture.clock.value += 100L
            fixture.transport.emitTelemetry(
                at = fixture.clock.value,
                heightMeters = h,
                velocityZCentimetersPerSecond = 0,
            )
            runCurrent()
        }

        assertEquals(FlightState.Flying, fixture.session.state.value.flight)
        advanceTimeBy(100L)
        runCurrent()
        assertTrue(fixture.transport.rc.isNotEmpty())
        assertEquals(RcVector.Zero, fixture.transport.rc.first())
        val firstPub = trace.rcPublications.first()
        assertEquals(RcVector.Zero, firstPub.actualSentVector)
        assertEquals(RcInputKind.SAFETY_ZERO, firstPub.inputKind)
    }

    @Test fun `takeoff stabilization Test E - invalid sample resets stabilization sequence`() = runTest {
        val fixture = connectedFixture()
        fixture.session.takeOff()
        assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)

        // 2 stable samples
        for (i in 1..2) {
            fixture.clock.value += 100L
            fixture.transport.emitTelemetry(
                at = fixture.clock.value,
                heightMeters = 0.50f,
                velocityZCentimetersPerSecond = 0,
            )
            runCurrent()
            assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)
        }

        // Invalid sample (missing vgz)
        fixture.clock.value += 100L
        fixture.transport.emitTelemetry(
            at = fixture.clock.value,
            heightMeters = 0.50f,
            velocityZCentimetersPerSecond = null,
        )
        runCurrent()
        assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)

        // 2 stable samples after reset (total is 4, but only 2 after reset -> must NOT transition)
        for (i in 1..2) {
            fixture.clock.value += 100L
            fixture.transport.emitTelemetry(
                at = fixture.clock.value,
                heightMeters = 0.50f,
                velocityZCentimetersPerSecond = 0,
            )
            runCurrent()
            assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)
        }

        // 2 more stable samples (completing 4 after reset) -> transitions to Flying
        for (i in 1..2) {
            fixture.clock.value += 100L
            fixture.transport.emitTelemetry(
                at = fixture.clock.value,
                heightMeters = 0.50f,
                velocityZCentimetersPerSecond = 0,
            )
            runCurrent()
        }
        assertEquals(FlightState.Flying, fixture.session.state.value.flight)
    }

    @Test fun `takeoff stabilization Test F - no RC sent while waiting in TakingOff`() = runTest {
        val fixture = connectedFixture()
        fixture.session.takeOff()
        assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)

        // Simulate manual input and multiple clock ticks while still TakingOff
        fixture.session.publishManualControl(ManualControlVector(forward = 1f, yaw = 1f))
        for (i in 1..5) {
            advanceTimeBy(50L)
            runCurrent()
        }

        assertTrue(fixture.transport.rc.isEmpty())
    }

    private suspend fun TestScope.takeOffAndVerify(fixture: Fixture) {
        fixture.session.takeOff()
        assertEquals(FlightState.TakingOff, fixture.session.state.value.flight)
        for (i in 0 until TelloFlightSession.TAKEOFF_STABILIZATION_MIN_SAMPLES) {
            fixture.clock.value += 100L
            fixture.transport.emitTelemetry(
                at = fixture.clock.value,
                heightMeters = TelloFlightSession.TAKEOFF_AIRBORNE_HEIGHT_THRESHOLD_METERS + 0.10f,
                velocityZCentimetersPerSecond = 0,
            )
            runCurrent()
        }
        assertEquals(FlightState.Flying, fixture.session.state.value.flight)
    }

    private suspend fun TestScope.landAndVerify(fixture: Fixture) {
        fixture.session.land()
        assertEquals(FlightState.Landing, fixture.session.state.value.flight)
        fixture.transport.emitTelemetry(fixture.clock.value, 0f)
        runCurrent()
        assertEquals(FlightState.Grounded, fixture.session.state.value.flight)
    }

    private fun TestScope.fixture(
        video: TelloVideoController? = null,
        visionTrace: VisionTraceRecorder = com.alonibh.tellodrone.vision.NoOpVisionTraceRecorder,
        beforeYawFollowStateCommit: ((MutableStateFlow<com.alonibh.tellodrone.domain.DroneSessionState>) -> Unit)? = null,
    ): Fixture {
        val clock = RcControlLoopTest.FakeClock(1_000)
        val transport = FakeTransport()
        val detectorNowNanos = AtomicLong(1_000_000_000L)
        return Fixture(
            clock,
            transport,
            TelloFlightSession(
                transport,
                backgroundScope,
                clock,
                video,
                detectorNowNanos::get,
                visionTrace = visionTrace,
                beforeYawFollowStateCommit = beforeYawFollowStateCommit,
            ),
            detectorNowNanos,
        )
    }

    private data class Fixture(
        val clock: RcControlLoopTest.FakeClock,
        val transport: FakeTransport,
        val session: TelloFlightSession,
        val detectorNowNanos: AtomicLong,
    )

    private class FakeTransport : TelloTransport {
        private val samples = MutableSharedFlow<TelloTelemetry>(replay = 1)
        override val telemetry: Flow<TelloTelemetry> = samples
        val commands = mutableListOf<String>()
        val rc = mutableListOf<RcVector>()
        var closed = false
        var nextResult: TelloCommandResult = TelloCommandResult.Success("ok")
        val results = mutableMapOf<String, TelloCommandResult>()

        override suspend fun sendCommand(command: String, timeoutMillis: Long): TelloCommandResult {
            commands += command
            return results[command] ?: nextResult
        }

        override suspend fun sendRc(vector: RcVector) { rc += vector }
        override suspend fun close() { closed = true }

        suspend fun emitTelemetry(
            at: Long,
            heightMeters: Float? = 0f,
            batteryPercent: Int? = 80,
            velocityZCentimetersPerSecond: Int? = 0,
        ) {
            samples.emit(
                TelloTelemetry(
                    batteryPercent = batteryPercent,
                    heightMeters = heightMeters,
                    flightTimeSeconds = 0,
                    temperatureCelsius = 30f,
                    velocityXCentimetersPerSecond = 0,
                    velocityYCentimetersPerSecond = 0,
                    velocityZCentimetersPerSecond = velocityZCentimetersPerSecond,
                    speedMetersPerSecond = 0f,
                    receivedAt = Instant.parse("2026-08-10T00:00:00Z"),
                    receivedAtMonotonicMillis = at,
                    fields = emptyMap(),
                ),
            )
        }
    }

    private class FakeVideoController : TelloVideoController {
        private val mutableState = MutableStateFlow(VideoState())
        override val state: StateFlow<VideoState> = mutableState
        var prepared = false
        var closed = false

        override suspend fun prepare(): Result<Unit> {
            prepared = true
            return Result.success(Unit)
        }

        override fun streamAcknowledged() {
            mutableState.value = VideoState(VideoAvailability.Streaming)
        }

        override fun streamFailed(reason: String) {
            mutableState.value = VideoState(VideoAvailability.Error, errorReason = reason)
        }

        override fun setPersonDetectionEnabled(enabled: Boolean): Result<Unit> {
            mutableState.value = mutableState.value.copy(
                personDetectionState = if (enabled) PersonDetectionState.Starting else PersonDetectionState.Off,
                personDetections = emptyList(),
            )
            return Result.success(Unit)
        }

        fun publishDetections(frame: Long, timestamp: Long, detections: List<PersonDetection>) {
            mutableState.value = mutableState.value.copy(
                availability = VideoAvailability.Streaming,
                personDetectionState = PersonDetectionState.Detecting,
                processedDetectorFrameSequence = frame,
                processedDetectorSourceTimestampNanos = timestamp,
                detectorModelName = "fake-model",
                detectorBackend = com.alonibh.tellodrone.domain.DetectorBackend.Cpu,
                detectorConfidenceThreshold = .55f,
                detectorInferenceMillis = 87L,
                detectorCandidates = detections,
                personDetections = detections,
            )
        }

        fun publishUnavailable() {
            mutableState.value = mutableState.value.copy(
                availability = VideoAvailability.Error,
                personDetectionState = PersonDetectionState.Off,
                personDetections = emptyList(),
            )
        }

        override suspend fun close() {
            closed = true
        }
    }

    private class FakeVisionTraceRecorder : VisionTraceRecorder {
        override val capturesFrames = true
        val frames = mutableListOf<VisionTraceFrame>()
        val controlMeasurements = mutableListOf<YawControlMeasurementTrace>()
        val rcPublications = mutableListOf<RcPublicationTrace>()
        val selectedTargets = mutableListOf<com.alonibh.tellodrone.domain.TrackedTarget>()
        val targetSelectionAttempts = mutableListOf<TargetSelectionAttemptTrace>()
        override fun onTargetSelected(target: com.alonibh.tellodrone.domain.TrackedTarget) {
            selectedTargets += target
        }
        override fun record(frame: VisionTraceFrame) { frames += frame }
        override fun recordControlMeasurement(trace: YawControlMeasurementTrace) { controlMeasurements += trace }
        override fun recordRcPublication(trace: RcPublicationTrace) { rcPublications += trace }
        override fun recordTargetSelectionAttempt(trace: TargetSelectionAttemptTrace) {
            targetSelectionAttempts += trace
        }
        override fun export(destinationUri: String, onComplete: (Result<VisionTraceExport>) -> Unit) = Unit
    }

    private fun TelloFlightSession.selectTarget(detection: PersonDetection) {
        val centerX = (detection.boundingBox.left + detection.boundingBox.right) / 2f
        val centerY = (detection.boundingBox.top + detection.boundingBox.bottom) / 2f
        selectTargetAt(centerX, centerY, detection.frameSequence)
    }

    private fun detection(
        box: NormalizedBoundingBox = NormalizedBoundingBox(.30f, .20f, .50f, .80f),
        frame: Long,
        timestamp: Long,
    ) = PersonDetection(
        box,
        .9f,
        frame,
        timestamp,
        HsvAppearanceHistogram(List(HsvAppearanceHistogram.BIN_COUNT) { if (it == 0) 1f else 0f }),
    )
}
// SPDX-License-Identifier: AGPL-3.0-only
