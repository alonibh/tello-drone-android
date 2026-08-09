# Roadmap

## Phase 0/1 — complete

Android foundation, architecture contracts, adaptive tablet UI, and mock state/controller.

## Phase 2 — implemented; awaiting physical validation

- Android Wi-Fi selection/request with per-socket `Network` binding and API 37 local-network access
- testable UDP command transport, serialized acknowledgements, timeouts, and state receiver
- `connectedDevice` foreground service, real telemetry, takeoff/land, manual RC, STOP/HOVER, motor kill
- fixed-rate RC freshness/clamping, connection-loss behavior, wake lock, real/mock mode

See [the staged physical validation procedure](PHYSICAL_TESTING.md). Phase 2 is not considered
physically verified until those checks are completed with a real tablet and Tello.

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

Enable only after previous gates: detection only; PID calculations without output; yaw; yaw plus
altitude; then forward/back last. Include manual override, lost-target and stale-frame failsafes,
conservative speed limits, and open-area testing.

## Phase 7 — media + polish

- recording, screenshots, MediaStore, settings, diagnostics, reconnect UX
- performance/thermal tuning and release packaging

MCP and LLM functionality remain out of scope for every Android phase unless explicitly
reintroduced.
