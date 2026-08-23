# Fixed identity benchmark

This directory is the reviewed metadata snapshot for the 2026-08-23 offline
optimization. Raw source videos and 444 canonical JPEGs are deliberately not
committed. `prepare_benchmark.py` reconstructs them from two SHA-256-pinned
MP4s at 5 Hz and records source indices, timestamps, decoded-pixel hashes, and
canonical-JPEG hashes.

## Ground truth

The user-selected identity is fixed before candidate optimization:

- `single_person`: the only clearly visible person, selected at 5.0 s;
- `multi_person`: the shirtless player in dark shorts at foreground/lower-left,
  selected at 0.0 s.

A 960 px proposal pass bootstrapped target boxes. Every one of the 100
multi-person frames was then reviewed on 512x288 identity-focused sheets.
Frames 47–54 and 78 were manually replaced because the bootstrap had selected
a competitor or lost the partially off-screen target. The single-person review
marked frames 251–255 invisible during full plant occlusion and frames 285–343
invisible after final exit. Candidate outputs are never substituted for missing
ground truth at scoring time. The manifest pins the finalized annotation files
by SHA-256 and the optimizer refuses unreviewed or hash-mismatched annotations.

## Split

Each interval is an independent clip and begins with an explicit target
selection. Lost remains latched for the remainder of that interval.

| Video | Tuning sections | Held-out sections |
|---|---|---|
| Single person | 5–20 s, 30–45 s | 20–30 s, 45–68.7 s |
| Multi person | 0–8 s, 12–16 s | 8–12 s, 16–19.9 s |

Parameters are selected from tuning results. Only the top two configurations
per architecture family are evaluated on held-out sections. No parameter is
changed after held-out evaluation.

The `review` directory contains the final multi-person all-frame focused sheets
and the single-person proposal sheets used to check visibility and identity.
