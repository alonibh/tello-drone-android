# Offline vision lab

This development-only harness evaluates recorded vision sessions without being
referenced by the Android Gradle build. It never opens a drone connection and
does not alter the production detector, association engine, or flight state.

The input ZIP is canonical. `manifest.json` defines frame order and source
timestamps, `trace.jsonl` supplies the exact recorded EfficientDet-Lite2
baseline, and `frames/*.jpg` are the only evaluated pixels. A screen recording
may be inspected separately, but is never decoded as evaluation input.

The five-video optimizer is a separate reproducible benchmark path. It samples
the pinned MP4 inputs at 5 Hz, freezes reviewed target-identity annotations,
uses fixed tuning and held-out time sections, caches detector output, and runs
a deterministic bounded search. It does not import into or modify the Android
build.

## One-time setup

From the repository root in PowerShell:

```powershell
C:\path\to\python.exe -m venv tools\vision_lab\.venv
tools\vision_lab\.venv\Scripts\python.exe -m pip install -r tools\vision_lab\requirements.txt
New-Item -ItemType Directory -Force tools\vision_lab\.cache | Out-Null
Push-Location tools\vision_lab\.cache
..\.venv\Scripts\python.exe -c "from ultralytics import YOLO; YOLO('yolo11n.pt')"
Pop-Location
```

The official `yolo11n.pt` used for the recorded result has SHA-256
`0ebbc80d4a7680d14987a577cd21342b65ecfd94632bd9a8da63ae6417644ee1`.
After setup, evaluation is local/offline; the script refuses to run if the
checkpoint is absent or has the wrong hash.

## Run

```powershell
tools\vision_lab\.venv\Scripts\python.exe tools\vision_lab\evaluate.py `
  --session-zip "C:\path\to\tello-vision-session.zip" `
  --baseline-model app\src\main\assets\efficientdet_lite2_metadata_v1_int8.tflite `
  --output-dir tools\vision_lab\outputs\my_run
```

For the fixed five-video identity benchmark:

```powershell
tools\vision_lab\.venv\Scripts\python.exe tools\vision_lab\prepare_benchmark.py `
  --video-dir "C:\path\to\videos" `
  --output-dir tools\vision_lab\work\benchmark

tools\vision_lab\.venv\Scripts\python.exe tools\vision_lab\optimize_benchmark.py `
  --benchmark-dir tools\vision_lab\work\benchmark `
  --output-dir tools\vision_lab\work\optimization `
  --baseline-model app\src\main\assets\efficientdet_lite2_metadata_v1_int8.tflite
```

The benchmark spec, reviewed annotation snapshot, split explanation, experiment
history, ranked finalists, winning config, timelines, annotated winner videos,
and baseline comparisons are retained under `tools/vision_lab/benchmark` and
`tools/vision_lab/outputs/optimization_20260823_expanded`.

For the license-gated mobile detector replacement bake-off:

```powershell
tools\vision_lab\.venv\Scripts\python.exe tools\vision_lab\detector_bakeoff.py `
  --benchmark-dir tools\vision_lab\work\benchmark_expanded `
  --output-dir tools\vision_lab\outputs\detector_bakeoff_20260825 `
  --max-experiments 8
```

This path considers only official YOLOX-Nano and NanoDet-Plus artifacts. It
separately gates source-code and pretrained-weight licensing before any model
download or inference, enforces two families/four configurations per family/
eight experiments total, and reproduces the corrected YOLO11n safe reference.
An unclear pretrained-weight license is a mandatory pre-evaluation rejection;
research availability is not treated as permission to bundle a model in a
proprietary Android app. The detector swap never changes the frozen association,
tracking, Lost latch, or explicit-reselection semantics.

For the bounded persistent single-object-tracker architecture experiment:

```powershell
tools\vision_lab\.venv\Scripts\python.exe tools\vision_lab\fetch_sot_models.py

tools\vision_lab\.venv\Scripts\python.exe tools\vision_lab\sot_experiment.py `
  --benchmark-dir tools\vision_lab\work\benchmark_expanded `
  --output-dir tools\vision_lab\outputs\sot_20260825 `
  --max-experiments 12
```

This path evaluates only LightTrack-Mobile and NanoTrackV3. At each explicit
selection it freezes the neural template and original appearance evidence. The
SOT owns every target box; cached YOLO person detections can suppress an
unreliable or ambiguous SOT prediction but can never replace, correct, or
reinitialize it. Missing may recover only while the same SOT continues; Lost
is latched until explicit reselection. The script enforces two tracker families,
eight configurations per family, 16 experiments total, and at most one
refinement pass. Tuning candidates are frozen before held-out evaluation.

`sot_models.json` pins source commits, model hashes, and separate code/weight
license findings. The supplied pretrained-weight grants are unclear for a
proprietary app, so the assets are research-only unless their owners clarify
the model-weight terms.

For the reproducible corrected-winner Missing/Lost diagnosis and bounded
continuity search:

```powershell
tools\vision_lab\.venv\Scripts\python.exe tools\vision_lab\targeted_continuity_optimization.py `
  --benchmark-dir tools\vision_lab\work\benchmark_expanded `
  --output-dir tools\vision_lab\outputs\optimization_20260824_targeted `
  --max-experiments 40 `
  --max-rounds 2
```

This path first asserts the exact corrected-label baseline result, writes a
per-frame gate/detector explanation and contact sheets for every held-out
Missing/Lost run, then searches tuning partitions only. It enforces hard caps
of 40 new configurations and two rounds. Candidates with any tuning identity
switch, wrong-person frame, or false track while the target is invisible are
ineligible; held-out data never changes the frozen candidate.

For the fixed-model ReID and temporary IdentityGuard experiments, first fetch
and hash-check the Apache-2.0 Open Model Zoo artifacts, then run the guard:

```powershell
tools\vision_lab\.venv\Scripts\python.exe tools\vision_lab\download_reid_models.py

tools\vision_lab\.venv\Scripts\python.exe tools\vision_lab\identity_guard_experiment.py `
  --benchmark-dir tools\vision_lab\work\benchmark_expanded `
  --output-dir tools\vision_lab\outputs\identity_guard_20260825 `
  --max-experiments 12
```

This path fixes ReID to `person-reidentification-retail-0288`, keeps ordinary
detector association unchanged outside guarded periods, enforces one tuning
round and 12 experiments, and freezes the diagnostic configuration before
opening held-out data. ReID confirms Lost recovery; during IdentityGuard it can
only veto the normal association proposal. The output records every guard
entry, exit, veto, and reacquisition.

The run produces four annotated MP4 videos, `metrics.json`, `per_frame.csv`,
and `report.md`:

1. exact recorded EfficientDet-Lite2 baseline;
2. YOLO11n detector-only at 320 px;
3. recorded EfficientDet-Lite2 detections plus fail-closed visual tracking;
4. YOLO11n on a two-frame cadence (and immediately on weak flow) plus
   fail-closed visual tracking.

The visual bridge is sparse pyramidal Lucas-Kanade flow. It requires enough
features, forward/backward consistency, a robust affine inlier ratio, bounded
scale/translation, and a target-appearance/geometry gate for detector
corrections. An ambiguous correction or weak flow becomes Missing; Lost is
latched after 0.8 seconds and cannot auto-reacquire. These are lab semantics,
not changes to the Android implementation.

Videos use one output frame per canonical JPEG at the session's average FPS.
The metrics use the original nanosecond timestamps, including capture gaps.
