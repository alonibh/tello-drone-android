# In-app tracking simulator

The app's **SIMULATOR** mode is a lightweight, deterministic way to exercise the production
closed-loop yaw-follow path without a physical aircraft. It runs entirely in the Android process.
It does not start `TelloDroneService`, request Wi-Fi or local-network permission, open a socket,
attach to a Tello network, decode video, copy pixels, run TensorFlow Lite, or send a physical
command.

## Architecture

`ControllerMode.Mock` remains the internal enum value to avoid an unrelated rename, but every
user-facing label says **SIMULATOR**. `MockDroneController` is the application-owned adapter. Each
start or reset creates a fresh runtime containing:

- `TelloFlightSession` configured with `ControllerMode.Mock`;
- `SimulatorTelloTransport`, which implements the normal SDK/telemetry/RC boundary;
- `SimulatorVideoController`, which publishes virtual frame identity and synthetic observations;
- the pure Kotlin deterministic `SimulatorPlant`; and
- a supervised coroutine scope owned outside the Activity and ViewModel.

Disconnect closes the session, video adapter, transport, and jobs. Reset closes the prior runtime
and connects a fresh grounded runtime; a closed session is never reused. Generation/identity checks
prevent an old runtime from writing into the new simulator state.

The feedback loop deliberately reuses production code:

```text
SimulatorPlant projection
  -> one synthetic PersonDetection
  -> TelloFlightSession target association
  -> TrackingErrorEngine
  -> YawFollowGate / ProductionYawController
  -> RcControlLoop final RcVector
  -> SimulatorTelloTransport
  -> SimulatorPlant next step
```

The plant does not import or call production tracking, controller, manual-vector conversion, or
sign-mapping functions. It receives the four final integer Tello axes field-for-field, making it an
independent sign oracle. The plant steps every 50 ms; virtual analysis results and telemetry publish
at 10 Hz. No image or video frames are created.

## User flow

1. While disconnected, choose **SIMULATOR** and press **START SIMULATOR**.
2. Take off. The simulator acknowledges first, then publishes airborne telemetry just like the real
   session transition.
3. Open Tracking and start **SYNTHETIC DETECTION**.
4. Tap the visible person box, then arm **SIMULATED YAW FOLLOW**.
5. Use **MOVE PERSON LEFT/RIGHT**, **CENTER PERSON**, **HIDE/SHOW PERSON**, and **RESET SCENARIO**.
6. Inspect **LATEST FINAL RC**. These are the exact axes received from `RcControlLoop`, not a
   simulator-only prediction.

Moving the person is rate-limited across multiple synthetic observations so the normal conservative
target association logic follows it. Hiding the person produces current empty detector results.
STOP/HOVER, manual override, loss, stale state, landing, and Emergency retain the production
fail-closed and explicit-rearm behavior.

## Independent axis contract

- Positive yaw turns the virtual camera right, so a stationary person moves left in the frame.
- Negative yaw turns the virtual camera left, so the person moves right.
- Positive lateral moves the drone right, so the person moves left.
- Positive vertical moves the drone up, so the person moves down.
- Positive forward moves toward the person, so the person box becomes larger.
- RC input is ignored while grounded.

The virtual world, height, distance, and projection are clamped. A visible projection is either a
finite, non-empty normalized box or no detection.

## Limits

This simulator validates app state transitions, target association, tracking-error/controller sign,
final RC selection, and a simple feedback response. It does **not** validate Wi-Fi selection or
binding, UDP behavior, firmware, command latency, inertia, drift, wind, camera vibration, real
detector accuracy, propellers, or physical safety. It does not replace a brief, controlled real-drone
physical validation before flight changes are trusted.
