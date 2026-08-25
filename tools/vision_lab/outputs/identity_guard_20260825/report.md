# Temporary IdentityGuard experiment

## Protocol

- 12 experiments in one round; fixed `person-reidentification-retail-0288`; no detector/model/global search.
- The guard configuration was frozen from tuning before held-out was opened.
- Stop reason: all bounded candidates violated tuning identity safety; stop lightweight tuning.

## Architecture

- Enter after a two-frame ReID-confirmed Lost recovery, normal competitor ambiguity, or strong overlapping competitor proposals.
- Normal YOLO/geometry/HSV association proposes the candidate. ReID only vetoes that proposal using immutable-selection similarity and target-versus-competitor separation.
- A veto emits Missing/Lost and does not update box or appearance state. Exit requires consecutive accepted frames with no strong overlap, sufficient normal-association margin, and >=0.10 embedding separation.

## Outcome

- Frozen guard: `guard_identity0.70_separation0.06_exit3` (tuning eligible: False).
- Frozen veto thresholds: identity >=0.70, immutable selection >=0.54, competitor separation >=0.06; exit after 3 clear frames.
- Guard held-out: 1 switch event, 9 wrong frames, 0 false-invisible tracks, 91.566% continuity.
- Accepted over 84.854% baseline: **False**.
- Ready to port: **False**.
- Stop further threshold/LK/lightweight-ReID tuning: **True**.

## Courtyard result

- The guard prevented the prior wrong runs at frame 133 and frames 137-150. It vetoed frame 132, reacquired at frame 138, and exited after clear frame 141.
- It did not prevent the takeover overall: after exit, unrestricted normal association switched for frames 152-160 (one event, nine wrong frames).

## Held-out per-video comparison

| Video | Baseline continuity | Guard continuity | Baseline S/W/F | Guard S/W/F | Baseline Lost/Missing | Guard Lost/Missing |
|---|---:|---:|---:|---:|---:|---:|
| single_person | 56.731% | 79.808% | 0/0/0 | 0/0/0 | 41/4 | 10/11 |
| multi_person | 100.000% | 100.000% | 0/0/0 | 0/0/0 | 0/0 | 0/0 |
| courtyard_single_a | 99.320% | 99.320% | 0/0/0 | 0/0/0 | 0/1 | 0/1 |
| courtyard_single_b | 100.000% | 100.000% | 0/0/0 | 0/0/0 | 0/0 | 0/0 |
| courtyard_competitor | 70.000% | 80.714% | 0/0/0 | 1/9/0 | 35/7 | 12/6 |
| **Aggregate** | **84.854%** | **91.566%** | **0/0/0** | **1/9/0** | **76/12** | **22/18** |

S/W/F = identity switches / wrong-person frames / false tracks while target invisible.
Baseline versus guard quality: drift 0 vs 0, mean IoU 0.9243 vs 0.9079, jitter 0.039288 vs 0.038242.

The detector-only baseline remains the winner. Because the bounded guard architecture failed identity safety on both tuning and held-out data, further threshold, LK, or lightweight-ReID optimization should stop.

Full aggregate, per-video, exact guard events/vetoes, and known-sequence metrics are in `result.json`.
