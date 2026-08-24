# ReID-only reacquisition experiment

## Scope and separation

- 20 experiments, 2 round(s), one model family and two sizes.
- Stop reason: all 20 candidates violated a hard tuning safety constraint.
- Normal tracking is the corrected-label YOLO11n winner and is unchanged. ReID is called only after Lost, never for initial selection or ordinary association.
- ReID thresholds/model were ranked on fixed tuning intervals. Because all candidates were unsafe, the highest-ranked ineligible candidate was frozen for diagnosis, then evaluated once on held-out intervals; it was never eligible to win.

## Models and deployment

- person-reidentification-retail-0288: OmniScaleNet, 256-float embedding, 0.174 GFLOPs, 0.183M parameters.
- person-reidentification-retail-0287: larger OmniScaleNet variant, 0.564 GFLOPs, 0.595M parameters.
- Open Model Zoo repository/artifacts are Apache-2.0. The evaluated files are OpenVINO IR; a LiteRT conversion and numerical-equivalence check remains required before Android integration.

## Exact fail-closed logic

- Freeze an immutable embedding from the explicit selection crop. Admit at most 10 diverse pre-loss detector crops (confidence >=0.55, no ambiguity) only when similar to the immutable selection.
- After Lost, consider YOLO detections >=0.30. Reject candidates outside the configured center/scale bounds or below both immutable-selection and multi-snapshot identity thresholds.
- Rank with 78% identity, 14% geometry, and 8% detector confidence. Reject when the best and runner-up lack identity and composite-score separation.
- Require consecutive spatially and embedding-consistent confirmations. Until confirmation, output Lost; then resume the unchanged normal tracker from the confirmed detector box.

## Result

- Frozen ReID config: `0287_sim0.64_margin0.04_c2`.
- Frozen diagnostic held-out: 2 switches, 15 wrong-person frames, 0 false-invisible tracks, 93.632% continuity.
- Retained baseline held-out: 0 switches, 0 wrong-person frames, 0 false-invisible tracks, 84.854% continuity.
- ReID accepted over baseline: **False**.
- Ready to port: **False**.
- Root cause: the embedding family did not separate the similarly presented competitor strongly enough. The ReID-confirmed frame 127 was initially correct, but the resumed normal association switched at frame 133 and again for frames 137-150.

## Held-out per-video comparison

| Video | Baseline continuity | ReID diagnostic continuity | Baseline S/W/F | ReID S/W/F | Baseline Lost/Missing | ReID Lost/Missing |
|---|---:|---:|---:|---:|---:|---:|
| single_person | 56.731% | 88.462% | 0/0/0 | 0/0/0 | 41/4 | 5/7 |
| multi_person | 100.000% | 100.000% | 0/0/0 | 0/0/0 | 0/0 | 0/0 |
| courtyard_single_a | 99.320% | 99.320% | 0/0/0 | 0/0/0 | 0/1 | 0/1 |
| courtyard_single_b | 100.000% | 100.000% | 0/0/0 | 0/0/0 | 0/0 | 0/0 |
| courtyard_competitor | 70.000% | 82.857% | 0/0/0 | 2/15/0 | 35/7 | 1/8 |

S/W/F = identity switches / wrong-person frames / false tracks while target invisible.

## Known sequences

- `single_person` 251-343: ReID confirmed frame 259 and safely improved the sequence; the diagnostic had 23 Tracked, 64 Lost, and 6 Missing frames in the requested range, with no wrong-person frames.
- `courtyard_competitor` 124-160: ReID confirmed frame 127, but the diagnostic switched at frame 133 and frames 137-150. This is unsafe even though it reduced Lost time.

Full machine-readable metrics and experiment history are in `result.json`, `heldout_metrics.csv`, and `experiment_history.jsonl`.
