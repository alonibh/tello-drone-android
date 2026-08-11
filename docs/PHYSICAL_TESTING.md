# Phase 2 physical validation

## Grounded validation status

- **Xiaomi Mi A1, Android 9 / API 28:** manual TELLO Wi-Fi connection, SDK command-mode
  acknowledgement, fresh grounded telemetry, correct `Grounded` state, safe disconnect, and the
  adaptive phone UI check all passed. Flight validation remains unverified.
- **Blaupunkt BP_6010 tablet:** grounded connection / SDK handshake, 30+ seconds of fresh
  telemetry, safe disconnect, adaptive landscape UI check, and Takeoff → immediate Land passed.
  The state progression behaved correctly. At 10%, right-stick forward/back and lateral movement
  each released to hover; left-stick altitude and yaw each released to hover; brief movement followed
  by STOP/HOVER returned to hover; both sticks worked simultaneously; releasing one preserved the
  other; releasing both returned to hover; and safe landing passed.

Connection-loss handling, Emergency Motor Kill, video, tracking, and autonomous flight are not
physically verified. Mi A1 manual flight also remains unverified.

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
