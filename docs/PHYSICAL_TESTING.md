# Physical validation

## Phase 3A video validation — complete

Phase 3A was physically validated on the Blaupunkt BP_6010 tablet. Real live Tello video remained
stable at approximately 30 FPS during flight; telemetry and individual/combined joystick control
remained responsive; STOP/HOVER worked with video running; fullscreen/video UI passed; and normal
landing and disconnect completed cleanly.

The completed grounded procedure was:

1. Use the Blaupunkt BP_6010 tablet in landscape only; place the Tello on the floor and do not take off.
2. Connect normally and confirm the existing Connected/Grounded and fresh-telemetry state.
3. Confirm the dominant dashboard panel changes from `STARTING VIDEO…` to the live Tello preview.
4. Leave the preview running for at least 30 seconds and observe measured FPS and visual stability.
5. Disconnect cleanly while still grounded and confirm the preview and session stop without an error.

## Phase 3B grounded decoded-frame validation — complete

Phase 3B was physically validated on the Blaupunkt BP_6010 tablet. The bounded 320×240 analysis
feed ran with the real preview remaining stable at approximately 30 FPS; flight controls remained
responsive with video and analysis active; the immersive landscape UI passed; and the real takeoff
confirmation was verified without weakening the grounded safety gate.

The completed grounded procedure was:

1. Use the Blaupunkt BP_6010 tablet in landscape and place the Tello on the floor. Do not take off.
2. Connect normally, keep the live preview running on the **Dashboard**, and confirm the preview
   stays approximately 30 FPS.
3. Confirm the Dashboard exposes only the compact measured preview FPS. Phase 3B analysis metrics
   remain internal for future debugging/Phase 4 and are not part of normal operational UI.
4. Leave the Dashboard preview and internal analysis feed running for at least 60 seconds. Confirm
   there is no visible preview degradation, freeze, or growing lag.
5. Background the app once, foreground it once, return to the Dashboard, and confirm preview resumes
   without a visible reset problem.
6. While still grounded and takeoff-eligible in REAL mode, tap **TAKE OFF** once and confirm the
   confirmation dialog appears. Cancel it and verify the drone does not take off. Do not continue
   to a real takeoff for this Phase 3B grounded validation.
7. Disconnect cleanly while the Tello remains grounded and confirm the preview and physical session
   stop without an error.

Phase 3 and Phase 3B physical validation are complete.

## Phase 4A grounded person-detection validation — pending

Phase 4A has not yet been physically validated. Perform this first validation exactly as a grounded
test; do not request takeoff:

1. Blaupunkt landscape, Tello on floor.
2. Connect REAL and confirm normal ~30 FPS preview.
3. Enable DETECT PEOPLE.
4. Put one person clearly in camera view.
5. Confirm one correctly aligned PERSON box appears.
6. Move around frame and confirm box follows through new detections without growing lag.
7. Leave frame and confirm box disappears quickly.
8. Put two people in frame and confirm separate boxes where detector confidence permits.
9. Run for at least 60 seconds; preview should remain healthy and controls/telemetry responsive.
10. Disable detection and confirm boxes disappear immediately.
11. Disconnect grounded.

No takeoff is permitted for the first Phase 4A validation. Do not claim Phase 4A complete until this
exact grounded procedure passes.

## Landscape operational UI — complete

The tablet immersive landscape operational UI was physically validated on the Blaupunkt BP_6010.
`MainActivity` requests sensor landscape where the
platform honors a fixed orientation. Modern large-screen Android platforms may ignore that request;
if a portrait-sized window is received, the normal dashboard is replaced with **Rotate device to
landscape**. During an active flight that fallback keeps STOP/HOVER, LAND, and the existing
Emergency Motor Kill hold control available; it does not alter the service or session. Returning to
landscape restores the normal dashboard. The portrait-window fallback itself remains pending
physical validation.

Immersive fullscreen is reapplied on resumed window focus so initial launch, return from background,
and dismissal of transient system UI restore the edge-to-edge operational view.

## Grounded validation status

- **Xiaomi Mi A1, Android 9 / API 28:** manual TELLO Wi-Fi connection, SDK command-mode
  acknowledgement, fresh grounded telemetry, correct `Grounded` state, safe disconnect, and the
  adaptive phone UI check all passed. Flight validation remains unverified.
- **Blaupunkt BP_6010 tablet:** grounded connection / SDK handshake, 30+ seconds of fresh
  telemetry, safe disconnect, adaptive landscape UI check, and Takeoff → immediate Land passed.
  The state progression behaved correctly. At 10%, right-stick forward/back and lateral movement
  each released to hover; left-stick altitude and yaw each released to hover; brief movement followed
  by STOP/HOVER returned to hover; both sticks worked simultaneously; releasing one preserved the
  other; releasing both returned to hover; HOVER ACTIVE feedback was visible; final joystick
  ergonomics were usable; and safe landing passed.

## Connection-loss validation

With the Tello hovering normally, tablet Wi-Fi was deliberately turned off. The app transitioned to
Error / lost-control state and no stale manual control remained active. After several seconds, the
aircraft rotated briefly and then performed its own failsafe landing. The rotation is observed
aircraft behavior after link loss, not app-controlled behavior. Connection-loss behavior is
physically validated.

Emergency Motor Kill remains intentionally untested. Phase 4A person detection is implemented but
pending its first grounded physical validation; target lock, tracking, and autonomous flight remain
unimplemented. Mi A1 manual flight also remains unverified.

**Phase 2 physical validation: complete.**

After the dual-joystick update, validate manual movement only at 10% speed: take off; move the right
stick slightly forward for less than one second; release and confirm hover; repeat laterally; make a
brief left-stick altitude change, then a brief yaw; make one brief movement and press STOP/HOVER;
then land. Individual-axis and combined-stick validation are complete on the Blaupunkt. Mi A1 manual
flight remains pending.

Use a propeller guard, a fully charged phone or tablet and Tello, and an open indoor area free of people,
pets, loose objects, mirrors, fans, and ceiling hazards. Keep the Tello on the floor for steps 1–2.
Begin flight steps at 10% speed. Keep a second person ready to catch hazards by clearing the area,
not by grabbing a powered aircraft.

1. **Connection only:** select REAL, press CONNECT TELLO, grant only the requested nearby/local
   permissions, choose the `TELLO-…` network, and confirm Connected/Grounded. Cancel once and deny
   once first if practical; verify both show a clear error and do not start flight controls.
2. **Telemetry only:** leave grounded for at least 30 seconds. Compare battery/height/time with the
   Tello app if available. Confirm values update and no signal value is invented.
3. **Takeoff + immediate land:** set 10%, take off, do not touch movement, then land immediately.
   Confirm `TakingOff → Flying → Landing → Grounded` and do not continue if any state is uncertain.
4. **Takeoff + STOP/HOVER:** take off, briefly hold one direction, press STOP/HOVER, and confirm
   motion stops while motors remain running and flight state remains Flying. Land.
5. **Low-speed single axis:** at 10%, test lateral, forward/back, altitude, then yaw separately with
   very short holds. Confirm every release stops that axis on the next control cycle. Land between
   groups if battery or confidence drops.
6. **Combined manual controls:** in a large clear area, briefly hold two axes together, release one,
   then the other. Confirm the first release preserves only the still-held axis and final release
   hovers. Increase speed only after 10% behavior is correct; never exceed the app's 40% cap.
7. **Connection loss:** hover low over a clear floor and use the tablet Wi-Fi control to disconnect
   from Tello (do not power off the tablet). Verify the UI becomes stale, active movement zeros,
   then connection becomes Error/flight Unknown without automatic reconnect. The Tello's own
   firmware link-loss behavior is the final fallback; be ready for its configured landing behavior.
8. **Emergency—controlled last resort only:** do not test merely to prove the button works. If a
   dedicated test is required, use the lowest stable hover over a soft, completely clear landing
   area with propeller guards. Complete the 0.9-second hold and expect an immediate uncontrolled
   drop because motor power is cut. Verify the app enters Emergency and accepts no later flight
   commands until disconnect/reset.

Stop immediately if telemetry is stale, state is Unknown/Error, controls do not release to hover,
the room becomes unsafe, or battery is low. Never use Emergency as a normal landing method.
