# Finalist rejection reasons

Candidates are ordered by the first differing item in the safety-first rank
tuple. A lower-priority advantage never compensates for a higher-priority
failure.

| Rank | Candidate | Why it did not win |
|---:|---|---|
| 1 | `refine1_efficientdet_lite2_09` | Winner: 0 switches, 0 wrong/false tracks, 39 Lost, 12 Missing, 64.583% safe continuity. |
| 2 | `refine1_efficientdet_lite2_05` | Practical tie on safety, Lost, Missing, continuity, and IoU; ranked second by a negligible jitter difference. Treat as equivalent evidence, not a material loss. |
| 3 | `search_efficientdet_lite2_08` | Same held-out target metrics as ranks 1–2; marginally worse jitter/cost tie-break. |
| 4 | `search_efficientdet_lite2_04` | Still identity-safe, but 61 Lost and 15 Missing frames reduced safe continuity to 47.222%. |
| 5 | `search_yolo11n_12` | Best YOLO safety result had 0 switches, but 74 Lost frames and only 43.056% safe continuity. |
| 6 | `efficientdet_hilo_lk` | Conservative untuned hybrid stayed identity-safe but had 76 Lost frames and 41.667% safe continuity. |
| 7 | `efficientdet_lk` | Without low-confidence maintenance it had 88 Lost and 13 Missing frames, only 29.861% continuity. |
| 8 | `efficientdet_baseline` | Detector-only control stayed identity-safe by failing closed, but had 113 Lost frames and only 10.417% continuity. |
| 9 | `yolo11n_hilo_lk` | Rejected catastrophically: 1 held-out identity switch/wrong-person frame. Its 65.972% continuity cannot compensate. |
| 10 | `yolo11n_detector` | Rejected catastrophically: 1 held-out identity switch/wrong-person frame. |
| 11 | `search_yolo11n_01` | Rejected catastrophically: 1 switch and 3 wrong-person frames. |

The tuning history also rejected both motion-prediction refinements and the
no-camera-compensation refinement because each introduced a tuning identity
switch. Full per-video metrics and every parameter are retained in
`ranked_candidates.json` and `experiment_history.jsonl`.
