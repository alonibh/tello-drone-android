# Architecture and safety contracts

## Phase 3A runtime ownership

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

Phase 2 real flight authority is always `Manual`. Tracking controls remain disabled for real mode.

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

## Phase boundaries

Phase 3A ends at live Surface preview. It adds no decoded-frame/ML consumer, detector, person
tracking, target lock for real flight, PID, autonomous control, recording, screenshots, media
gallery, MCP, LLM, Python, or cloud integration. Future video and autonomous producers must preserve
the same session ownership and RC freshness contract; manual input remains the highest-priority
authority. Phase 3B does not begin automatically.
