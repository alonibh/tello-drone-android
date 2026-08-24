# Bounded targeted continuity optimization

## Search integrity

- The corrected detector-only baseline was reproduced exactly before diagnosis or search: 0 identity switches, 0 wrong-person frames, 84.854% held-out identity-safe continuity, 76 Lost, and 12 Missing.
- Candidate selection used only tuning partitions. The frozen tuning result was then checked against the established corrected held-out baseline.
- New tuning experiments: 40/40 across 2/2 refinement rounds. No clip-specific rule was used.
- All 40 new candidates were safety-ineligible on tuning data. Their best apparent continuity was 91.038%, but that candidate had 4 identity switches, 33 wrong-person frames, and 1 false track while the target was invisible.
- Search stopped because round 2 produced no meaningful safety-eligible tuning improvement.

## Frame-by-frame root cause summary

The complete per-frame evidence is in `missing_lost_frame_analysis.json` and `.csv`; the diagnostic contact sheets show ground truth in green and raw YOLO proposals in blue.

| Video / canonical run | Exact cause |
|---|---|
| `single_person` 136-150 | Frames 136-138 had strong target detections (confidence 0.821-0.898) but failed the generic geometry/scale gate after abrupt camera displacement; proposal/prediction area ratios were 4.56-6.72. Lost then latched for frames 139-150 even though every frame had a usable target detection. |
| `single_person` 246-247 | Target proposal confidence fell to 0.031 and 0.208, below 0.30. Association recovered normally at frame 248. Classified as detector miss/confidence. |
| `single_person` 251-343 | Foreground foliage fully occluded the target, which is annotated invisible at 251-256. Missing at 251-252 became latched Lost at 253. When the target reappeared at 257, Lost remained latched: 25 of the 28 visible Lost frames at 257-284 had a usable target detection. The target is invisible again at 285-343. Classified as target leaving/occlusion followed by `other` (fail-closed Lost latch). |
| `multi_person` 55-59 | The target is annotated invisible/offscreen. Missing then Lost is expected fail-closed behavior and contributes no visible-target continuity loss. |
| `courtyard_single_a` 156 | Two overlapping proposals for the same visible person produced a score-margin ambiguity. This was an isolated association rejection and recovered on the next frame. |
| `courtyard_competitor` 92-93 | The target and woman were spatially close and both eligible. Competitor-aware score ambiguity correctly returned Missing for two frames, then recovered. |
| `courtyard_competitor` 122 | The woman occluded/merged with the target; the target-associated proposal's area ratio was 2.883, just beyond the 2.8 scale limit. Classified as geometry gate with physical occlusion. |
| `courtyard_competitor` 124-160 | Real target/competitor overlap caused association ambiguity at 124-126. The 0.4-second TTL then latched Lost. Of the following 34 visible frames, 32 already had a usable target detection, but explicit reselection was required. |
| `courtyard_competitor` 268 and 272 | Two isolated real target/competitor overlaps caused correct fail-closed association ambiguity; both recovered immediately. |

No significant held-out Missing/Lost run was caused by the appearance gate. `multi_person` and `courtyard_single_b` had no visible Missing/Lost frames.

## Optimization evidence

- Round 1 tested 27 detector-only variants that reduced confidence, appearance, ambiguity, low-confidence-association, or geometry rejection. All became identity-unsafe, predominantly at `courtyard_competitor` tuning frames 179-222.
- Round 2 used the remaining 13 experiments only for the failure mechanisms demonstrated above: six longer Missing TTL values and seven LK/TTL bridge variants. All were identity-unsafe. Longer TTL reached 89.937% apparent tuning continuity but produced 5 switches, 35 wrong-person frames, and 5 false-invisible tracks. LK produced 4-5 switches, 33-35 wrong frames, and 31-35 false-invisible tracks.
- The evidence therefore rejects removing the fail-closed latch or adding LK with the current lightweight identity representation. Continuity cannot be improved safely by these targeted parameter changes.

## Final result

The retained winner is `previous_corrected_winner`: YOLO11n detector-only at every frame, confidence 0.30, IoU gate 0.15, distance gate 0.24, adaptive appearance gate 0.45, ambiguity margin 0.10, and 0.4-second Missing TTL. Persistent-memory safety, LK, motion prediction, and camera compensation are disabled.

Held-out: **0 switches, 0 wrong-person frames, 0 false-invisible tracks, 84.854% continuity, 76 Lost, 12 Missing, 0 drift, mean IoU 0.9243, jitter 0.039288.**

## Android decision

**NOT READY.** The aggregate remains below the 90% readiness target, and the weak videos remain at 56.731% (`single_person`) and 70.000% (`courtyard_competitor`). The single biggest blocker is safe post-occlusion/reacquisition: the lightweight histogram/geometry association cannot distinguish the target reliably enough to unlock Lost without competitor takeover.

No Android production code was changed.
