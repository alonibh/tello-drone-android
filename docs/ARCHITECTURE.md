# Architecture and safety contracts

Phase 0/1 is a mock-only Compose app. `DroneController` is the UI boundary and
`MockDroneController` is its only implementation. Screens render immutable `StateFlow` state
from `DroneViewModel`; neither Compose nor the ViewModel owns a socket, decoder, or flight loop.

## Future flight-session contract

The production flight session will be owned by a long-lived Android **connected-device foreground
service**, not by an Activity, Compose tree, or ViewModel. The service must explicitly select the
Tello Wi-Fi `Network` and bind every Tello UDP socket to it; default routing is not sufficient.
When targeting API 37, Phase 2 must account for Android 17's `ACCESS_LOCAL_NETWORK` permission.

Blocking SDK commands/acknowledgements and continuous RC control are separate responsibilities.
A dedicated RC loop, independent of video rendering and inference, receives desired vectors from
manual/tracking systems and sends only fresh vectors. Every vector has TTL/freshness semantics;
an expired command is a zero/hover command, never a continued movement command.

## Video and ML contract

Video and inference use latest-frame semantics: bounded flows drop old frames rather than building
latency queues. The Phase 3 H.264 design must support both low-latency preview and sampled decoded
frames for analysis, without making ML frame access prohibitively expensive.

The app will define a `PersonDetector` interface. It must not depend directly on Ultralytics or
YOLO. The runtime/model is selected only after physical-tablet performance, latency/thermal, and
license review. A later dry-run mode will calculate/display control vectors without sending them,
and recorded video plus telemetry should support offline replay for detector/controller tuning.

## Authority and autonomy contract

Manual input always immediately preempts autonomous Follow. A production Follow session requires
explicit target lock; detection alone cannot receive flight authority. Target loss, stale frames,
or stale commands must fail safe to hover. Person bounding-box area is not a depth sensor: any
shown distance is estimated/calibrated, never authoritative.

Autonomous output is introduced only in validated gates: detection only; PID calculations with no
output; yaw only; yaw plus altitude; and forward/back only last. No obstacle-avoidance capability
may be claimed unless independently implemented and validated.

## Safety-action contract

**STOP/HOVER** is the normal, immediate safety action: it cancels motion/autonomy and commands a
hover in a future real controller. **EMERGENCY MOTOR KILL** is deliberately destructive and
separate; Phase 1 protects it with a 0.9-second press-and-hold. Its current implementation only
changes mock state and never accesses hardware.
