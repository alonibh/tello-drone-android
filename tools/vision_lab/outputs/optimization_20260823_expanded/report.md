# Offline identity-first tracker optimization

## Benchmark integrity

- Canonical data: 1365 frames at 5 Hz from 5 SHA-256-pinned source videos.
- Identity ground truth was frozen before search. A bootstrap proposal pass supplied candidate boxes; every canonical frame was rendered at review resolution, the exact person identity and visibility were independently checked, and corrections were applied before candidate evaluation.
- Candidate detections are not accepted as runtime ground truth. Annotation hashes are stored in the canonical manifest and verified before evaluation.
- Tuning and held-out time intervals are fixed in `benchmark_spec.json`. The winning configuration is frozen from tuning rank before any held-out metric is used; held-out evaluation is validation only.
- A configuration must reach 50% aggregate identity-safe continuity on tuning data to be eligible. This rejects the degenerate never-track solution before identity-first ranking.

## Winner

`refine4_yolo11n_08`: `yolo11n` + detector-only.

Held-out: **1 identity-switch events**, **8 wrong-person frames**, 0 false-track frames, 76 Lost frames, 12 Missing frames, **83.618% identity-safe continuity**, 0 localization-drift frames, mean tracked IoU 0.9114.
Previous winner on the enlarged held-out set: 4 switches, 28 wrong-person frames, 101 Lost, 32 Missing, 72.526% identity-safe continuity, 8 drift frames, mean IoU 0.7727.

## Search and rejection reasons

- 184 tuning experiments were recorded. Search stopped because: loaded converged tuning history; refinement round 5 had produced no meaningful tuning improvement
- The winner was selected only by tuning rank. Held-out outcomes did not alter parameters, architecture, or winner selection.
- After the 50% tuning eligibility floor, ranking is strict lexicographic priority: identity switches, wrong-person/false-track frames, Lost, Missing, identity-safe continuity, localization drift, IoU, jitter, then compute. Therefore no further continuity gain can compensate for an identity switch among eligible configurations.
- Rejected candidates rank lower for the first differing item in that tuple; detailed configurations and per-video metrics are in `experiment_history.jsonl` and `ranked_candidates.json`.

## Desktop cost

- EfficientDet-Lite2: {"frames": 1365, "mean_ms": 147.672, "median_ms": 135.745, "p95_ms": 236.917}
- YOLO11n 320 CPU/PyTorch: {"frames": 1365, "mean_ms": 172.967, "median_ms": 134.544, "p95_ms": 346.903}
- Winner LK mean per processed frame: 0.000 ms.
- These desktop figures are directional only; they are not Teclast LiteRT measurements.

## Decision

The evidence in this report determines the detector/LK/low-confidence/camera-motion recommendation; see `recommendation.md` for the concise port decision and deployment blockers.
