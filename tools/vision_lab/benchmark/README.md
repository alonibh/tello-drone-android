# Fixed identity benchmark

This directory is the reviewed metadata snapshot for the expanded 2026-08-23 offline optimization. Raw source videos and 1,365 canonical JPEGs are deliberately not committed. `prepare_benchmark.py` reconstructs them from five SHA-256-pinned MP4s at 5 Hz and records source indices, timestamps, decoded-pixel hashes, and canonical-JPEG hashes.

## Ground truth

The selected identity is fixed before optimization:

- `single_person`: the only clearly visible person;
- `multi_person`: the shirtless player in dark shorts at foreground/lower-left;
- `courtyard_single_a`: the only visible person;
- `courtyard_single_b`: the only visible person;
- `courtyard_competitor`: the shirtless man wearing white shorts; the woman is always a competitor.

A high-resolution proposal pass bootstrapped boxes. All 1,365 canonical frames were then rendered on 56 large focused sheets and independently checked for target identity and visibility before search. Reviewed corrections include the old multi-person crossing, single-person invisibility/exit spans, the courtyard-A disappearance, and 67 competitor-crossing boxes that were fixed to the selected man. Candidate outputs are never substituted for missing ground truth. The manifest pins every finalized annotation file by SHA-256, and the optimizer rejects unreviewed or hash-mismatched annotations.

## Split

Each interval is an independent clip and begins with explicit target selection. Lost remains latched for the remainder of that interval.

| Video | Tuning sections | Held-out sections |
|---|---|---|
| Single person | 5–20 s, 30–45 s | 20–30 s, 45–68.7 s |
| Multi person | 0–8 s, 12–16 s | 8–12 s, 16–19.9 s |
| Courtyard single A | 3–20 s, 35–45 s | 20–35 s, 45–59.4 s |
| Courtyard single B | 0–20 s, 35–50 s | 20–35 s, 50–65.7 s |
| Courtyard competitor | 0–18 s, 32–45 s | 18–32 s, 45–59 s |

Parameters and the winner are selected only from tuning results. Held-out metrics are calculated after the winning configuration is frozen and never feed back into parameter or architecture selection. A 50% aggregate tuning-continuity eligibility floor rejects the degenerate never-track solution; among eligible configurations, identity switches and wrong-person/false-track frames have strict first priority.

`review_expanded` contains the 56 final all-frame focused sheets used for the complete review. `review_audit.json` records per-video counts, correction totals, and frozen annotation hashes.
