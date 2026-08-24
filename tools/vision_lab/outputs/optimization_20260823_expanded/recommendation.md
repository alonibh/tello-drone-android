# Android port decision

**NOT READY**

Winning offline pipeline: `refine4_yolo11n_35` (`yolo11n`, LK enabled, cadence 1).

Held-out: 0 switch event(s), 0 wrong-person frames, 68.158% identity-safe continuity, 140 Lost, 45 Missing.

Largest blocker: The conservative identity gates materially reduce held-out continuity (68.158% aggregate; 27.143% on `courtyard_competitor`), below both the previous winner and the 90% port-readiness gate.

No Android production code was changed by this offline decision.
