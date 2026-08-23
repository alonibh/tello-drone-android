# Offline identity-first tracker optimization

## Benchmark integrity

- Canonical data: 444 frames at 5 Hz from two SHA-256-pinned source videos.
- Identity ground truth was frozen before search. A 960 px proposal pass bootstrapped boxes; every canonical frame was rendered to review sheets, the exact person identity was visually checked, and false-visible ranges were manually corrected.
- Candidate detections are not accepted as runtime ground truth. Annotation hashes are stored in the canonical manifest and verified before evaluation.
- Tuning and held-out time intervals are fixed in `benchmark_spec.json`; held-out results are evaluated only for architecture finalists.

## Winner

`refine1_efficientdet_lite2_09`: `efficientdet_lite2` + fail-closed LK.

Held-out: **0 identity switches**, **0 wrong-person frames**, 0 false-track frames, 39 Lost frames, 12 Missing frames, **64.583% identity-safe continuity**, 0 localization-drift frames, mean tracked IoU 0.8264.

## Search and rejection reasons

- 56 tuning experiments were recorded. Search stopped because: refinement round 1 produced no meaningful tuning improvement
- Ranking is strict lexicographic priority: identity switches, wrong-person/false-track frames, Lost, Missing, identity-safe continuity, localization drift, IoU, jitter, then compute. Therefore no continuity gain can compensate for an identity switch.
- Rejected candidates rank lower for the first differing item in that tuple; detailed configurations and per-video metrics are in `experiment_history.jsonl` and `ranked_candidates.json`.
- Human-readable finalist loss reasons are in `rejection_reasons.md`.

## Desktop cost

- EfficientDet-Lite2: {"frames": 444, "mean_ms": 120.01, "median_ms": 111.204, "p95_ms": 208.495}
- YOLO11n 320 CPU/PyTorch: {"frames": 444, "mean_ms": 90.036, "median_ms": 70.061, "p95_ms": 197.978}
- Winner LK mean per processed frame: 30.790 ms.
- These desktop figures are directional only; they are not Teclast LiteRT measurements.

## Decision

The evidence in this report determines the detector/LK/low-confidence/camera-motion recommendation; see `recommendation.md` for the concise port decision and deployment blockers.
