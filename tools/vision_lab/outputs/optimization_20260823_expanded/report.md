# Offline identity-first tracker optimization

## Benchmark integrity

- Canonical data: 1365 frames at 5 Hz from 5 SHA-256-pinned source videos.
- Identity ground truth was frozen before search. A bootstrap proposal pass supplied candidate boxes; every canonical frame was rendered at review resolution, the exact person identity and visibility were independently checked, and corrections were applied before candidate evaluation.
- Candidate detections are not accepted as runtime ground truth. Annotation hashes are stored in the canonical manifest and verified before evaluation.
- Tuning and held-out time intervals are fixed in `benchmark_spec.json`. The winning configuration is frozen from tuning rank before any held-out metric is used; held-out evaluation is validation only.
- A configuration must reach 50% aggregate identity-safe continuity on tuning data to be eligible. This rejects the degenerate never-track solution before identity-first ranking.

## Winner

`refine4_yolo11n_35`: `yolo11n` + fail-closed LK.

Held-out: **0 identity-switch events**, **0 wrong-person frames**, 1 false-track frames, 140 Lost frames, 45 Missing frames, **68.158% identity-safe continuity**, 1 localization-drift frames, mean tracked IoU 0.9318.
Previous winner on the enlarged held-out set: 0 switches, 0 wrong-person frames, 76 Lost, 12 Missing, 84.854% identity-safe continuity, 0 drift frames, mean IoU 0.9243.

## Root cause of the reported multi_person takeover

- Canonical frames 47-54 were not a runtime identity takeover. The old annotations had switched the selected identity to the central gray-shirt competitor at frames 37-46, so held-out initialization at frame 40 selected that competitor. The tracker then consistently followed the initialized person while the metric treated the corrected target boxes at frames 47-54 as a wrong-person run.
- The corrected review keeps the shirtless target selected at frames 37-54 and 60-69, and marks the target invisible at frames 55-59 and 70-77. The corrected manifest and review artifacts are retained with the benchmark.

## Generic identity-safety change

- Association now retains an immutable selection-time appearance histogram alongside the adaptive appearance model, applies scale-aware persistent-appearance conflict gating, and detects overlapping low-confidence competitor evidence before accepting a merged person box.
- After a competitor or appearance ambiguity, LK cannot override the fail-closed decision and reacquisition requires two consistent frames. Ordinary unambiguous one-frame detector misses can still use LK immediately. Uncertain cases remain Lost/Missing instead of selecting a competitor.

## Search and rejection reasons

- 188 tuning experiments were recorded. Search stopped because: user-directed early stop after completed refinement round 4; froze the best tuning candidate found so far
- The winner was selected only by tuning rank. Held-out outcomes did not alter parameters, architecture, or winner selection.
- After the 50% tuning eligibility floor, ranking is strict lexicographic priority: identity switches, wrong-person/false-track frames, per-section continuity shortfall, Lost, Missing, identity-safe continuity, localization drift, IoU, jitter, then compute. Therefore no further continuity gain can compensate for an identity switch among eligible configurations.
- Rejected candidates rank lower for the first differing item in that tuple; detailed configurations and per-video metrics are in `experiment_history.jsonl` and `ranked_candidates.json`.

## Desktop cost

- EfficientDet-Lite2: {"frames": 1365, "mean_ms": 96.272, "median_ms": 82.941, "p95_ms": 146.971}
- YOLO11n 320 CPU/PyTorch: {"frames": 1365, "mean_ms": 104.425, "median_ms": 70.547, "p95_ms": 232.676}
- Winner LK mean per processed frame: 78.842 ms.
- These desktop figures are directional only; they are not Teclast LiteRT measurements.

## Decision

The evidence in this report determines the detector/LK/low-confidence/camera-motion recommendation; see `recommendation.md` for the concise port decision and deployment blockers.
