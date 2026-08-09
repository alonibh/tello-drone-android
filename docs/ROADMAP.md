# Roadmap

## Phase 0/1 — current

Android foundation, architecture contracts, adaptive tablet UI, and mock state/controller.

## Phase 2 — Tello connectivity + manual flight

- Android Wi-Fi selection/request and binding sockets to the Tello `Network`
- required permissions, UDP command transport, serialized command/response, telemetry receiver
- connected-device foreground service, takeoff/land/manual RC, STOP/HOVER, motor kill
- connection-loss, battery, and safety behavior; no video

## Phase 3 — video pipeline

- Tello H.264 UDP receiver, packet/NAL handling, `MediaCodec`, low-latency preview
- shared decoded-frame strategy, bounded drop-old frame flow, measured latency/FPS; no detector

## Phase 4 — person detection

- select model/runtime after licensing and tablet benchmarks
- `PersonDetector`, overlays, target selection/lock, dry-run and replay; no autonomous flight

## Phase 5 — tracking controller

- port validated target-selection concepts, EMA smoothing, deadzones, PID calculations
- visualize proposed RC output with zero actual autonomous output

## Phase 6 — autonomous flight

Enable only after previous gates: yaw; yaw plus altitude; then forward/back last. Include manual
override, lost-target and stale-frame failsafes, conservative speed limits, and open-area testing.

## Phase 7 — media + polish

- recording, screenshots, MediaStore, settings, diagnostics, reconnect UX
- performance/thermal tuning and release packaging

MCP and LLM functionality remain out of scope for every Android phase unless explicitly
reintroduced.
