# Rejection reasons

- The frozen best-so-far candidate meets the 0-switch/0-wrong identity threshold but fails the no-material-continuity-regression criterion: 68.158% versus 84.854% for the previous winner.
- Configurations below 50% tuning continuity were ineligible, preventing a never-track configuration from winning by suppressing identity exposure.
- Among eligible configurations, the first differing strict rank item rejects a candidate: switches, wrong/false-track frames, per-section continuity shortfall, Lost, Missing, safe continuity, drift, IoU, jitter, then compute.
- Full configurations and metrics for all tuning experiments and held-out architecture controls are retained in `experiment_history.jsonl` and `ranked_candidates.json`.
