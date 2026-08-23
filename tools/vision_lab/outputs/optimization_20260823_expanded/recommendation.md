# Android port decision

**NOT READY**

Winning offline pipeline: `refine4_yolo11n_08` (`yolo11n`, detector-only, cadence 1).

Held-out: 1 identity-switch event, 8 wrong-person frames, 83.618% identity-safe continuity, 76 Lost, and 12 Missing.

Largest blocker: the `multi_person` held-out partition still has one identity-switch event and eight consecutive wrong-person frames at canonical indices 47–54.

No Android production code was changed by this offline decision.
