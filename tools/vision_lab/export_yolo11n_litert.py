#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Reproduce the selected 320px FP32 YOLO11n LiteRT export."""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import tempfile
from pathlib import Path

os.environ.setdefault("YOLO_CONFIG_DIR", str(Path(__file__).resolve().parent / ".cache" / "ultralytics"))

from ultralytics import YOLO
from ultralytics.utils import checks
import onnx2tf


CHECKPOINT_SHA256 = "0ebbc80d4a7680d14987a577cd21342b65ecfd94632bd9a8da63ae6417644ee1"
MODEL_SHA256 = "3a4b2e9604487942c92ac1d00e0990e50dff55a1879a66c40906a579dad706e9"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if sha256(args.checkpoint) != CHECKPOINT_SHA256:
        raise SystemExit("Refusing to export an unpinned YOLO11n checkpoint")

    # Ultralytics 8.3.191 pins an obsolete conversion stack that has no Python 3.12
    # wheels. requirements-export.txt pins the validated successor stack instead.
    checks.check_requirements = lambda *unused_args, **unused_kwargs: True
    with tempfile.TemporaryDirectory(prefix="yolo11n-litert-") as directory:
        work = Path(directory)
        local_checkpoint = work / "yolo11n.pt"
        shutil.copyfile(args.checkpoint, local_checkpoint)
        exported_onnx = Path(
            YOLO(str(local_checkpoint)).export(
                format="onnx",
                imgsz=320,
                dynamic=False,
                simplify=True,
                nms=False,
                opset=18,
            ),
        )
        converted = work / "converted"
        onnx2tf.convert(
            input_onnx_file_path=str(exported_onnx),
            output_folder_path=str(converted),
            copy_onnx_input_output_names_to_tflite=True,
            enable_batchmatmul_unfold=False,
            non_verbose=True,
        )
        exported = converted / "yolo11n_float32.tflite"
        args.output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(exported, args.output)
    actual = sha256(args.output)
    print(f"{args.output} sha256={actual}")
    if actual != MODEL_SHA256:
        print(
            "Export serialization differs from the selected artifact; this output must pass "
            "exported_model_benchmark.py before it can replace the bundled model.",
        )


if __name__ == "__main__":
    main()
