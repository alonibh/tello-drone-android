# Vision lab result

Canonical ZIP SHA-256: `a5ab94bc39bfe37fc78aa95de39123191ffa9209a2743ca4cd1889e5687268c2`

Frames: 85 at 320x240; capture span: 14.043s; one output frame per canonical JPEG.

The visible selected person remains in-frame for all canonical frames. The screen recording was not an evaluation input. One source frame was dropped during capture, so results are exact for the 85 canonical frames but cannot score the absent image.

## Continuity

| Approach | Tracked | Continuity | Missing | Lost | Detector frames | Tracker frames | Obvious drift |
|---|---:|---:|---|---|---:|---:|---|
| Recorded EfficientDet-Lite2 baseline | 8/85 | 9.41% | 1 / 0.999s total: #9-#14 (0.999s) | 1 / 11.846s total: #15-#85 (11.846s) | 8 | 0 | none |
| YOLO11n detector-only (320) | 85/85 | 100.00% | 0 | 0 | 85 | 0 | none |
| EfficientDet-Lite2 + fail-closed LK | 85/85 | 100.00% | 0 | 0 | 64 | 21 | none |
| YOLO11n cadence + uncertainty fallback + LK | 85/85 | 100.00% | 0 | 0 | 45 | 40 | none |

`Obvious drift` is an automated review flag: two or more consecutive tracked boxes with IoU < 0.25 versus the identity-gated YOLO11n detector-only box. It is not independently labeled ground truth.
Manual contact-sheet review found no obvious target drift in the three successful approaches. This single-person clip cannot validate competitor/identity-switch behavior; that remains a required adversarial test.

## Desktop cost

- Exact EfficientDet-Lite2 TFLite asset through MediaPipe Tasks, wall time: `{"min": 181.31, "p50": 279.796, "p95": 653.07, "max": 3183.553, "mean": 344.492, "accepted_person_frames": 59, "threads": "runtime default"}` ms.
- YOLO11n PyTorch CPU, model inference: `{"min": 81.738, "p50": 196.992, "p95": 270.551, "max": 452.936, "mean": 182.792}` ms; end-to-end wall: `{"min": 86.564, "p50": 205.176, "p95": 278.339, "max": 459.39, "mean": 190.646}` ms.
- Sparse LK tracker wall time in the EfficientDet hybrid: `{"min": 0.018, "p50": 12.892, "p95": 30.312, "max": 93.515, "mean": 16.213}` ms.
- Sparse LK tracker wall time in the YOLO hybrid: `{"min": 0.015, "p50": 16.446, "p95": 26.564, "max": 50.505, "mean": 17.152}` ms. YOLO detector cost is normally amortized over a two-frame cadence, with an immediate detector fallback whenever flow is rejected.

These are approximate CPU measurements on this desktop, not projected Teclast/Android latency. The recorded Android EfficientDet inference distribution is retained in `metrics.json` separately.

## Ranked recommendation

1. **YOLO11n + fail-closed visual bridge** — best practical pipeline to prototype on Android. Use detector correction on a cadence, LK between detections, hard appearance/geometry/competitor gates, and latch Lost until explicit reselection.
2. **YOLO11n detector-only** — strongest simple replacement and a useful control, but frame-by-frame detection alone does not satisfy the tracking requirement under future blur/occlusion/dropouts.
3. **EfficientDet-Lite2 + fail-closed visual bridge** — lowest-risk architecture experiment because it isolates the value of temporal tracking, but retains the weaker detector as its correction source.
4. **Current EfficientDet-Lite2 baseline** — fails this clip by design once detector confidence drops and Lost latches.

No production model or Android tracking code was changed. Before any production decision: export/quantize YOLO11n to LiteRT, benchmark on the target Teclast, add multi-person/occlusion/adversarial clips, and verify that competitor ambiguity always fails closed. Ultralytics licensing also needs product review before shipping weights or derived code.

## Model and deployment references

- [YOLO11 model documentation](https://docs.ultralytics.com/models/yolo11/) describes YOLO11 as the mature production line and supports export mode.
- [Ultralytics export documentation](https://docs.ultralytics.com/modes/export/) lists LiteRT/TFLite export and quantization options.
- [OpenCV Lucas-Kanade documentation](https://docs.opencv.org/4.x/d4/dee/tutorial_optical_flow.html) covers the C++/Python/Java primitive used by the visual bridge.
- [Ultralytics licensing](https://www.ultralytics.com/license) states that its code and trained models default to AGPL-3.0; proprietary embedding requires an appropriate commercial license. Treat that as a hard shipping gate, not a footnote.
