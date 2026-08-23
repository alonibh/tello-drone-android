# Recommendation

## Decision

Keep **EfficientDet-Lite2** and prototype the winning temporal architecture:
high/low-confidence identity-safe association, fail-closed LK, fixed-selection
HSV appearance plus geometry validation, competitor ambiguity rejection, and
camera-motion compensation. Do not port it to production Android yet.

The held-out winner (`refine1_efficientdet_lite2_09`) had zero identity switches,
zero wrong-person frames, zero false tracks while the target was invisible,
64.583% aggregate identity-safe continuity, and mean tracked IoU 0.8264. The
multi-person held-out result was 85.0% continuity with zero switches; the
single-person held-out result was 56.731%, primarily because fail-closed Lost
latched after difficult visibility loss.

## Component conclusions

- **Detector:** retain EfficientDet-Lite2. The best zero-switch YOLO11n hybrid
  reached only 43.056% held-out continuity. The standard YOLO detector and
  hybrid each made a held-out identity switch, despite slightly higher raw
  continuity in one configuration.
- **LK:** keep it. EfficientDet detector-only reached 10.417% held-out safe
  continuity; EfficientDet + strict LK reached 29.861%; the tuned hybrid reached
  64.583%, all with zero held-out switches.
- **Low-confidence association:** keep it only for maintenance of an already
  selected identity under strong temporal, geometry, appearance, and ambiguity
  gates. It materially reduced Lost duration; it must never be used for global
  reacquisition.
- **Camera-motion compensation:** keep it in the prototype. All top three
  held-out configurations used it, while the no-compensation refinement caused
  a tuning identity switch. More clips are needed before calling it universally
  necessary.
- **Motion prediction:** reject it for now. Both local motion-prediction
  refinements introduced tuning identity switches.
- **Appearance/ReID:** lightweight frozen-selection HSV appearance validation is
  necessary in this architecture. Deep ReID is not justified yet; first collect
  cases where geometry plus the existing appearance gate fails safely or loses
  too much continuity.

## Deployment and licensing

The winner introduces no new detector weight: it uses the existing production
EfficientDet-Lite2 asset. MediaPipe's repository is Apache-2.0, but the precise
provenance/license record for the already-bundled model asset should remain in
the product's dependency inventory. YOLO11n is not the winner and has a material
shipping blocker: Ultralytics states that its trained models are AGPL-3.0 by
default and proprietary embedding requires appropriate enterprise terms.

- MediaPipe license: https://github.com/google-ai-edge/mediapipe
- Ultralytics licensing: https://www.ultralytics.com/license

## Why it is not ready to port

The architecture is promising, but the evidence is not yet strong enough for a
production Android port. Before changing production code, obtain:

1. More independently reviewed, multi-person flying-camera clips with target
   disappearance/re-entry, close crossings, partial/full occlusion, similar
   clothing, scale changes, motion blur, backlighting, and edge-of-frame exits.
2. A second annotation review for all identity-switch challenge sections and a
   substantially larger held-out set. The current held-out evidence is only 144
   target-visible frames at 5 Hz, including 40 multi-person frames.
3. Target-device Teclast benchmarks of the exact EfficientDet cadence, LK,
   appearance checks, and camera compensation under the Android camera path.
   Desktop means (EfficientDet 120.010 ms, LK 30.790 ms) are not portable.
4. An Android-specific safety test proving Lost remains latched, ambiguity fails
   closed, low-confidence detections cannot reacquire globally, and frame stalls
   preserve RC TTL/STOP/HOVER/Emergency invariants.

Until those exist, keep this as offline R&D evidence and do not modify the
production detector/tracker or flight stack.
