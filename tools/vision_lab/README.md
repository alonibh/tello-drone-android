# Offline vision lab

This development-only harness evaluates recorded vision sessions without being
referenced by the Android Gradle build. It never opens a drone connection and
does not alter the production detector, association engine, or flight state.

The input ZIP is canonical. `manifest.json` defines frame order and source
timestamps, `trace.jsonl` supplies the exact recorded EfficientDet-Lite2
baseline, and `frames/*.jpg` are the only evaluated pixels. A screen recording
may be inspected separately, but is never decoded as evaluation input.

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
