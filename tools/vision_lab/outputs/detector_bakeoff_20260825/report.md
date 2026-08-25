# License-gated mobile detector bake-off

## Decision

**STOPPED BEFORE CANDIDATE INFERENCE. Neither candidate is eligible for a proprietary Android prototype because the official pretrained-weight terms are unclear.**

The official source implementations are permissively licensed, but source-code licensing does not establish permission to redistribute separately published model weights. Per the experiment gate, no candidate model was downloaded, converted, timed, or evaluated. Candidate experiment count: **0/8**.

The existing safe behavior remains frozen: explicit selection, normal safe association, Lost latched until explicit reselection, and no automatic long-term reacquisition. No tracking, association, threshold, production Android, or ground-truth changes were made.

## License and deployment audit

| Candidate | Code | Official pretrained weights | Proprietary distribution | Android path | Decision |
|---|---|---|---|---|---|
| YOLOX-Nano 416 | Apache-2.0 | unclear; no separate license/terms | Not established for weights | ncnn (ONNX Runtime Mobile alternative) | Rejected before evaluation |
| NanoDet-Plus-m 416 | Apache-2.0 | unclear; no separate license/terms | Not established for weights | ncnn (ONNX Runtime Mobile with a new output decoder alternative) | Rejected before evaluation |

### YOLOX-Nano

- Code: Apache-2.0 at pinned repository commit `6ddff4824372906469a7fae2dc3206c7aa4bbaee`; the official ncnn C++ detector file carries BSD-3-Clause notices.
- Weights: official `0.1.1rc0` ONNX/PyTorch release assets, but no weight-specific license, terms URL, or commercial bundling/redistribution statement. Upstream issue #1865 asks exactly this and remains unanswered.
- Size/compute: official 0.91M parameters and 1.08 GFLOPs; ONNX asset 3,659,407 bytes (3.49 MiB), PyTorch asset 7,694,953 bytes (7.34 MiB), with 1.8 MB FP16 reported by NanoDet's official comparison.
- Desktop cost: not measured because the candidate failed the pre-evaluation gate; YOLOX does not publish Nano desktop-CPU latency. A separate official NanoDet comparison reports 23.08 ms on a Kirin 980 4xA76 with ncnn, which is not a desktop or Teclast result.
- Android: ncnn is the most direct path; ONNX Runtime Mobile is feasible from the official ONNX graph. This remains technically plausible but legally blocked for the supplied weights.

### NanoDet-Plus-m 416

- Code: main repository Apache-2.0 at pinned commit `be9b4a9001d7f9b6fc89c2df31ae8d428e35b4f0`. The bundled `demo_android_ncnn` subtree is separately GPL-3.0 and is unsuitable for copying into a proprietary app.
- Weights: official `v1.0.0-alpha-1` ONNX/ncnn/checkpoint assets, but the model zoo and release give no weight license or commercial redistribution grant.
- Size/compute: official 1.17M parameters, 1.52 GFLOPs, 2.3 MB FP16 / 1.2 MB INT8; ONNX asset 4,793,616 bytes (4.57 MiB), ncnn archive 2,218,050 bytes (2.12 MiB).
- Desktop cost: not measured locally because the candidate failed the pre-evaluation gate. Official published inference is 8.32 ms with OpenVINO on an i7-8700; that is not this end-to-end benchmark.
- Android: an independently written JNI adapter using the Apache-licensed ncnn demo logic and permissive ncnn runtime is the practical path; do not reuse the GPL Android demo. Official 4xA76 ncnn latency is 19.77 ms, but direct Teclast testing is still required.

## Frozen held-out reference

Candidate metrics are deliberately absent because both candidates were rejected before evaluation. The YOLO11n reference was reproduced from the existing hash-checked cache and frozen ground truth.

| Video | Switches | Wrong-person | False-invisible | Continuity |
|---|---:|---:|---:|---:|
| `single_person` | 0 | 0 | 0 | 56.731% |
| `multi_person` | 0 | 0 | 0 | 100.000% |
| `courtyard_single_a` | 0 | 0 | 0 | 99.320% |
| `courtyard_single_b` | 0 | 0 | 0 | 100.000% |
| `courtyard_competitor` | 0 | 0 | 0 | 70.000% |
| **Aggregate** | **0** | **0** | **0** | **84.854%** |

Cached YOLO11n desktop CPU/PyTorch timing over 1365 canonical frames: mean 104.425 ms, median 70.547 ms, p95 232.676 ms. This is a reference only and does not predict Android performance.

## Comparison and Teclast recommendation

Neither candidate can be compared on identity metrics without violating the explicit license gate. Therefore neither should be ported to the Teclast now, and YOLO11n is not made deployable by this result. The detector blocker remains unresolved.

The bounded next action is licensing remediation, not another architecture search: obtain an explicit commercial/redistribution grant from the model publisher, or train and document owned weights using a dataset with compatible terms. Only then rerun this same fixed detector-only bake-off and benchmark the selected runtime on the physical P50Ai.

No production Android code was changed.
