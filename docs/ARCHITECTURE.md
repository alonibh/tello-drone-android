# Architecture and safety contracts

## Phase 3 runtime ownership

`DroneController` remains the UI boundary. `AppDroneController` selects either the retained
`MockDroneController` or `RealDroneController`; mode changes are allowed only while disconnected.
The application owns this adapter, so Activity recreation and screen rotation do not replace it.

`RealDroneController` is intentionally thin. The physical session, Wi-Fi request, UDP sockets,
command sequencing, telemetry receiver, health monitor, and RC loop are owned by
`TelloDroneService`. The service runs as a `connectedDevice` foreground service from the moment a
user starts real connection selection until a safe disconnect or terminal connection failure.
Compose and `DroneViewModel` never own these resources.

Phase 3A adds the physical video socket, bounded H.264 pipeline, and `MediaCodec` decoder to the
same service/session ownership boundary. Compose owns only the current `SurfaceView` display
surface and hands it through `DroneController`; a service gateway retains that display hand-off
across service startup without retaining an Activity. Surface loss never transfers ownership of
the socket or decoder to the UI.

Phase 3B adds a decoded-frame source inside that same service-owned video controller. Compose still
owns only the display surface; it does not own analysis frames, capture threads, or a vision
consumer. Manual flight authority and every Phase 2 safety path are independent of the decoded
frame source being available or healthy.

## Adaptive window UI

The Compose dashboard adapts to the current application window, not device type. Compact windows
are under 600 dp wide, medium windows are 600-839 dp, and expanded windows are 840 dp and wider.
Compact-height windows use a landscape flight layout that keeps video and critical controls
adjacent. Expanded windows preserve the dashboard rail, dominant video area, right-side controls,
and bottom manual controls. UI reflow only rearranges composables: it neither recreates the
application-owned controller nor publishes movement. Manual and emergency hold controls clear
their pressed state on pointer cancellation and disposal.

## Android network and permission contract

Permissions are requested only after **CONNECT TELLO** is pressed. Android 10–12 request the
platform-required coarse+fine location pair for the Wi-Fi selection API, Android 13+ use `NEARBY_WIFI_DEVICES` with
`neverForLocation`, and target/API 37 also requests `ACCESS_LOCAL_NETWORK`. Denial is visible and
does not start a physical session.

The service requests an SSID beginning `TELLO-` through `WifiNetworkSpecifier` and retains the
`Network` returned by `ConnectivityManager`. It does not bind the app process or change the
default route. Every command and state `DatagramSocket` is individually bound with
`Network.bindSocket`. User cancellation, request timeout, permission revocation, `onLost`, and
socket failure all become explicit UI errors and release the network callback.

The UDP 11111 video receiver is likewise created by the service and individually bound to the
selected Tello `Network` with `Network.bindSocket`. The app process is never globally bound.

## Tello transport and state contract

The UDP layer has a testable datagram endpoint. A mutex permits only one acknowledgement-bearing
SDK command at a time. RC datagrams use the same endpoint but do not wait for acknowledgements, so
a zero vector is not blocked behind a long takeoff/landing response wait. All socket work uses an
IO dispatcher.

A real connection is not reported until both `command` mode returns `ok` and a real state packet
has arrived. It is Grounded only when that packet includes a finite, non-negative height at or
below 0.20 m; absent, malformed, or otherwise invalid height remains Unknown. Takeoff/land enter
`TakingOff`/`Landing` on command submission, require their SDK acknowledgement, and complete only
after a later valid height sample verifies airborne/grounded state. A timeout or socket failure makes the result uncertain: connection becomes
`Error`, flight becomes `Unknown`, controls are inhibited, resources are closed, and there is no
automatic reconnect. A normal disconnect is allowed only when grounded (or after terminal
Emergency cleanup); users must explicitly land first.

## RC/manual safety contract

Real flight authority remains `Manual`. Phase 4A person detection is observational and cannot
publish RC, select a target, or acquire autonomous authority.

The service-owned RC loop runs at 20 Hz only while flying. Compose hold controls publish a full
desired vector and refresh it every 100 ms. The service stamps each publication with a monotonic
timestamp and accepts it for 250 ms. Each normalized axis is clamped to `-1..1`; the selected
manual magnitude is limited to 10–40 Tello RC units.

If refreshes stop for any reason, the next RC cycle sends zero. The 250 ms TTL expires at its exact
boundary. Releasing any control immediately
publishes the combined vector with that axis zeroed; releasing the final axis sends all zero.
Screen disposal also publishes zero. These are conveniences—the service TTL is the independent
backstop when UI/lifecycle delivery fails.

**STOP / HOVER** clears the desired vector, serializes its immediate `rc 0 0 0 0` after any
already-started packet, and prevents a selected non-zero vector from being sent afterwards. It cancels non-manual
authority state, and keeps `Flying`. It never sends `emergency`.

**EMERGENCY MOTOR KILL** first sends zero, locks the RC loop, sends the real Tello `emergency`
command, enters terminal `Emergency`, and refuses later flight commands. It remains protected by
the existing 0.9-second hold interaction.

## Telemetry and connection health

Tello state packets expose battery, height, flight time, low/high temperature, and `vgx/vgy/vgz`.
Speed is derived from those three velocity components. Unsupported or absent values remain null;
there is no fabricated signal strength.

Every sample has wall-clock and monotonic receipt timestamps. Exact Phase 2 health rules:

- At 1.5 seconds without telemetry, the sample becomes stale, desired RC is cleared, an immediate
  zero is attempted, non-zero RC output is inhibited, and flight controls requiring freshness are
  disabled.
- If telemetry resumes before terminal loss, it becomes fresh again, but the old movement does
  not resume because the desired vector was cleared and a neutral input is required before another
  non-zero vector is accepted.
- At 4.0 seconds without telemetry, the connection becomes `Error`, flight becomes `Unknown`, a
  final zero is attempted, sockets/network callbacks are released, the foreground session ends,
  and no reconnect is attempted.
- Command timeout, socket failure, Android `Network` loss, and service destruction take the same
  conservative zero/unknown/cleanup path. Service destruction performs best-effort zero and close.

A partial wake lock is held only while a real session is in `TakingOff`, `Flying`, `Landing`, or an
airborne `Unknown` state, and is released on grounded/disconnected cleanup.

## Phase 3A video lifecycle

After SDK command mode and the first telemetry packet establish the real session, the session opens
and network-binds the UDP 11111 receiver before sending `streamon`. `VideoAvailability.Streaming`
is published only after `streamon` returns `ok`. Receiver setup, command rejection/timeout, socket
failure, and decoder failure publish a separate `VideoAvailability.Error`; they do not turn a
healthy manual flight connection into a flight error.

Video receive and decode each run on a dedicated single thread, separate from command, telemetry,
and the service-owned 20 Hz RC loop. Datagram parsing never holds the command mutex. The receive
side uses a fixed 512 KiB access-unit buffer. Tello's packet-boundary rule is isolated in
`TelloH264AccessUnitAssembler`: 1460-byte datagrams continue the current access unit and a shorter
datagram terminates it. Datagram bytes are accumulated before Annex-B scanning, so three- or
four-byte start codes, NAL headers, and NAL payloads may be split across datagrams. Oversized or
malformed units are dropped through the next boundary.

The decoder hand-off retains at most three SPS/PPS/IDR recovery units plus one newest ordinary
picture. Ordinary pictures replace older pictures. This bounded recovery-plus-latest policy keeps
decoder bootstrap data while refusing to build latency. SPS/PPS are supplied as AVC codec-specific
data and decode resumes only from an IDR after codec or Surface restart.

The decoder uses Android `MediaCodec` with `video/avc` and direct Surface output. Normal platform
decoder selection is used, which chooses a hardware implementation when the device provides one.
API 30+ enables the codec low-latency feature only when the selected decoder reports support; API
28/29 use the same pipeline without that optional feature. Surface destruction releases the codec
and leaves the bounded receiver alive; recreation configures a fresh codec after SPS/PPS and an IDR.
Codec stop/release is contained on the decoder thread. FPS and last-frame time are updated when a
decoded output buffer is released for rendering to a valid Surface, not inferred from UDP traffic.

On normal grounded disconnect, RC/final-zero cleanup happens first, video receive/decode is closed,
and `streamoff` is attempted with a 750 ms outer bound before the command transport closes. On
network loss, command/session failure, or service destruction, video resources close immediately
and no `streamoff` acknowledgement is awaited. Video exceptions remain inside the video supervisor;
STOP/HOVER, landing, Emergency, telemetry health, stale-input zeroing, and connection-loss behavior
remain authoritative.

## Phase 3B decoded-frame feed

`DecodedFrameSource` isolates the analysis-frame contract from its capture technique. The Phase 3B
implementation uses asynchronous `PixelCopy` requests against the existing decoded preview
`Surface`; it does not add a second H.264 decoder or change the `MediaCodec` output surface. The
codec thread only posts a throttled render notification. Pixel copying runs on a dedicated capture
thread, and an optional consumer runs serially on a separate analysis thread. Neither thread is an
RC, command, telemetry, UDP receive, codec, or Compose thread.

The visible preview remains 960×720 at its native stream cadence. Analysis copies are scaled by
`PixelCopy` to 320×240 and capped at 8 capture requests per second. Each leased frame has
immutable metadata containing width, height, the original monotonic render timestamp, an
ever-increasing sequence, and `ARGB_8888_BITMAP` representation. The Phase 4A detector reads the
bitmap directly during its consumer callback and never retains the bitmap or frame after that
callback.

Capture uses a fixed pool of at most three 320×240 ARGB_8888 bitmaps: one may be in PixelCopy, one
may be executing in the consumer, and one may be pending. There is no per-frame full-resolution
allocation. The generic `LatestAnalysisFrameBuffer` has exactly one pending slot. A newer ordered
frame replaces and releases an unread older lease; an out-of-order frame is released; a slow
consumer therefore skips history and receives the most recent pending frame. With no consumer, the
single latest frame is retained for replacement and no analysis-consumer work is scheduled. If all
three buffers are temporarily busy, the analysis copy is dropped before any primary video or
flight path can be delayed.

Surface loss clears and releases the pending frame, invalidates any in-flight capture generation,
and resets measured diagnostics. Surface recreation starts cleanly while keeping frame sequence
ordering within the session. Disconnect, decoder failure, connection loss, and service destruction
close the handoff, stop the analysis executor, and recycle all returned bitmaps; a late PixelCopy or
consumer release recycles its bitmap instead of returning it to a closed pool. Cleanup never waits
indefinitely for a platform PixelCopy callback.

The Status destination reports only successful capture measurements: the approximately one-second
analysis FPS window, 320×240 dimensions after the first successful copy, and current frame age
computed from the frame's render timestamp on the same monotonic clock. The UI samples age every
250 ms while Status is visible, so a capture freeze makes age increase. Missing measurements remain
unavailable rather than being fabricated. These are feed diagnostics, not detector latency.

## Phase 4A person detection

The foreground-service-owned `AndroidTelloVideoController` owns `PersonDetectionPipeline` and the
`PersonDetector` lifecycle. `TfliteTaskPersonDetector` is the only class that imports TensorFlow
Lite Task Vision types. It uses the official Task ObjectDetector artifacts `0.4.4`, synchronous
image inference, a `person` label allowlist, `0.50` confidence threshold, and at most five results.
The old MediaPipe Tasks dependency and EfficientDet asset are no longer packaged.

The bundled model is TensorFlow's official SSD MobileNet V1 TFLite `metadata` variant, release v2,
trained on COCO and published under Apache License 2.0. It was downloaded from
`https://www.kaggle.com/api/v1/models/tensorflow/ssd-mobilenet-v1/tfLite/metadata/2/download`.
The checked-in asset is `ssd_mobilenet_v1_metadata_v2.tflite`, 4,185,175 bytes, SHA-256
`CBDECD08B44C5DEA3821F77C5468E2936ECFBF43CDE0795A2729FDB43401E58B`. Release v2 embeds the
Task-compatible model metadata and COCO label file; the app exposes only `person`.

`FallbackPersonDetectorFactory` implements two explicit backend selections. GPU PREFERRED creates
the official GPU delegate on the analysis thread; a GPU initialization or inference failure closes
that detector and retries once with the CPU backend. CPU COMPARE bypasses GPU and uses the supported
four-thread Task CPU configuration. The active backend, fallback state, model, detector FPS, and
latest inference milliseconds are shown in Tracking diagnostics. GPU creation is not treated as
proof of better performance: grounded validation must compare both selections on the target device.
Backend selection is allowed only while detection is OFF and does not modify target or authority.

Detector construction, every blocking `detect()` call, fallback, and release occur on the existing
`tello-analysis-consumer` thread. OFF publishes empty state immediately, then schedules release on
that same existing consumer executor. No asynchronous inference may outlive the short bitmap lease,
and no second frame queue is introduced.

Task ObjectDetector results are immediately converted to immutable app-domain `PersonDetection` values:
normalized finite `0..1` bounds, confidence, source frame sequence, and source monotonic timestamp.
Malformed boxes are rejected; partially out-of-range boxes are clamped. These are independent,
frame-local observations. `TrackedTarget` is not populated, `target` remains null, and Phase 4A
does not select a primary person or track identity across frames.

The Phase 3B single pending slot and drop-old policy remain unchanged. Slow inference blocks only
the analysis-consumer thread; PixelCopy, preview decode, UDP receive, commands, telemetry, and RC
continue independently. A newer zero-person result clears boxes immediately. Non-empty results
expire from state after 500 ms using the same monotonic clock domain as the source timestamp. OFF,
Surface loss, video failure, disconnect, and detector failure also clear results immediately.
Detector failure transitions only the detector state to ERROR/OFF behavior and publishes a concise
message; it does not change connection, flight, manual vector, or RC health.

`TelloVideoSurfaceView` fills its Compose video panel while presenting the fixed 960×720 Surface.
PixelCopy captures that complete displayed Surface and scales it to 320×240, so normalized analysis
coordinates map through independent X/Y fill-bounds scaling into the overlay viewport. The reusable
`VideoOverlayCoordinateMapper` centralizes that contract, including finite checks, clamping, and
empty-box rejection. It does not assume analysis pixels are screen pixels or invent camera
calibration.

Detection is explicit user state: OFF → STARTING → DETECTING, or ERROR on failure. It can start only
after a connected streaming preview has produced an analysis frame. Disabling, losing the Surface,
losing video, or disconnecting returns it to OFF and does not silently re-enable it. Target lock,
Follow, identity recognition, temporal tracking, PID, distance estimation, and autonomous RC remain
unavailable. Manual flight authority, RC TTL zeroing, STOP/HOVER, and Emergency remain unchanged.

## Phase 4B dry-run target association and normalized errors

Phase 4B adds a pure, observational pipeline only:

`Detection -> explicit selection -> association -> normalized tracking errors`

`PersonDetection` remains a frame-local observation. Nothing chooses the largest person, the
nearest-center person, or the highest-confidence person. A caller must explicitly pass a visible
`PersonDetection` to `TargetSelection.select`; that records normalized geometry plus the source
frame sequence and monotonic timestamp. Domain targets use `NormalizedBoundingBox`, never Compose
geometry or a wall-clock timestamp.

`TargetAssociationEngine` accepts a selected target, a newer source frame, and frame-local person
detections. It permits a match only when conservative normalized center displacement, IoU, and
area-ratio thresholds all pass. It scores eligible candidates, but close scores are `Ambiguous`
and leave the prior target unchanged. A larger, nearer, or more confident detection never steals
the lock by itself. Older sequence/timestamp input is ignored. Missing input is temporary for one
second of source-monotonic time, then becomes `Lost`; a lost target is never reacquired without a
new explicit selection.

`TrackingErrorEngine` is also pure and dry-run-only. It emits EMA-smoothed normalized yaw,
vertical, and target-area errors with alpha 0.4; its X/Y/area deadzones and desired area ratio are
documented normalized derivations from the former 960x720 reference. Positive yaw is target-right,
positive vertical is target-above, and positive area error means the target is smaller than desired.
It resets smoothing on loss and when selection changes. These values never enter PID or RC code.

The next dry-run-only planning stage is:

`Detection -> explicit target selection -> conservative association -> normalized errors -> dry-run PID/planner -> [HARD GATE] -> future RC integration`

`PidController` requires caller-supplied gains, limits, and monotonic `dt`; it has no clock or
production gains. `DryRunFollowPlanner` consumes only normalized errors, association state, and an
explicit config, returning `DryRunControlIntent` rather than a manual vector or RC command. It emits
non-zero diagnostic intent only for fresh `Matched` or initial `Selected` targets. Missing, stale,
ambiguous, lost, invalid-timing, and invalid-error input always emits non-actionable zero intent.
Lost and a new selection reset PID state. The named legacy simulation values are test-only and not
flight tuning. Real autonomous RC is NOT implemented; the Teclast detector benchmark remains the
hard gate before any future RC integration.

Phase 4C adds a pure `SHADOW AUTONOMY SAFETY GATE` after dry-run planning:
`Detection -> selection -> association -> normalized errors -> dry-run PID/planner -> SHADOW AUTONOMY SAFETY GATE -> [TECLAST + PHYSICAL HARD GATE] -> future RC integration`.
It never changes control authority or emits a command. Manual input, STOP/HOVER, ambiguity, loss,
telemetry/video/detector/connection failure, landing, and emergency fail closed; safety-significant
interruptions latch `RequiresRearm`, so healthy input alone can never re-engage shadow eligibility.

Mock mode exposes two selectable person boxes, selected/missing/lost state, and debug error values
in Tracking only. Real-mode target selection remains disabled pending the future Teclast detector
benchmark. There is no PID, autonomous RC, Follow mode, or non-manual control authority in Phase
4B; manual RC, its TTL/stale-input zeroing, STOP/HOVER, and Emergency retain their existing roles.

## Phase boundaries

## Phase 4D detector benchmark instrumentation

Tracking exposes a real-device, grounded-only 30-second benchmark when the real connection has a
streaming preview, an analysis frame, and person detection is OFF. Starting it enables the existing
`PersonDetectionPipeline` with the selected backend; it neither creates a frame queue nor changes
PixelCopy, decoding, model configuration, detection thresholds, or backend implementation. The
first three completed inferences are deterministic warm-up and are excluded from inference
percentiles. Detector creation time is measured separately with a monotonic clock.

The service-owned video controller observes the existing render, analysis, and detector callbacks
to report completed inferences, min/p50/p95/max steady-state inference, detector/preview FPS, and
analysis FPS only when it is actually observed. Android build metadata is limited to manufacturer,
model, Android/API level, supported ABIs, and available processor count. No device identifiers or
network/personal identifiers are collected. Completion, cancellation, detector failure, Surface
loss, and video loss stop/release the detector through the normal existing lifecycle. Benchmark
state has no imports or calls to RC control, manual vectors, control authority, flight commands,
or takeoff; it is observational instrumentation only.

Phase 4A ends at frame-local person boxes over the real preview. It adds no target selection, target
lock, identity or face recognition, cross-frame tracking, PID, autonomous control, Follow mode,
distance estimation, recording, screenshots, media gallery, MCP, LLM, Python, or cloud integration.
No later roadmap phase begins automatically.
