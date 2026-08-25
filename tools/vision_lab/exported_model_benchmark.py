#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Validate an exported YOLO11n LiteRT model with the frozen safe pipeline."""

from __future__ import annotations

import argparse
import json
import statistics
import time
from dataclasses import asdict, replace
from pathlib import Path
from typing import Any

import cv2
import numpy as np
from ai_edge_litert.interpreter import Interpreter

import optimize_benchmark as lab


REFERENCE_CONTINUITY_PERCENT = 84.854
PERSON_CLASS = 0
RAW_CONFIDENCE_FLOOR = 0.02
NMS_IOU = 0.60
MAX_DETECTIONS = 300


def letterbox(image: np.ndarray, size: int = 320) -> tuple[np.ndarray, float, float, float]:
    height, width = image.shape[:2]
    scale = min(size / height, size / width)
    resized_width = round(width * scale)
    resized_height = round(height * scale)
    resized = cv2.resize(image, (resized_width, resized_height), interpolation=cv2.INTER_LINEAR)
    pad_x = (size - resized_width) / 2.0
    pad_y = (size - resized_height) / 2.0
    left = round(pad_x - 0.1)
    right = round(pad_x + 0.1)
    top = round(pad_y - 0.1)
    bottom = round(pad_y + 0.1)
    padded = cv2.copyMakeBorder(
        resized,
        top,
        bottom,
        left,
        right,
        cv2.BORDER_CONSTANT,
        value=(114, 114, 114),
    )
    return cv2.cvtColor(padded, cv2.COLOR_BGR2RGB), scale, float(left), float(top)


def intersection_over_union(box: np.ndarray, boxes: np.ndarray) -> np.ndarray:
    x1 = np.maximum(box[0], boxes[:, 0])
    y1 = np.maximum(box[1], boxes[:, 1])
    x2 = np.minimum(box[2], boxes[:, 2])
    y2 = np.minimum(box[3], boxes[:, 3])
    intersection = np.maximum(0.0, x2 - x1) * np.maximum(0.0, y2 - y1)
    box_area = max(0.0, box[2] - box[0]) * max(0.0, box[3] - box[1])
    areas = np.maximum(0.0, boxes[:, 2] - boxes[:, 0]) * np.maximum(0.0, boxes[:, 3] - boxes[:, 1])
    union = box_area + areas - intersection
    return np.divide(intersection, union, out=np.zeros_like(intersection), where=union > 0.0)


def nms(boxes: np.ndarray, scores: np.ndarray) -> list[int]:
    order = np.argsort(-scores, kind="stable")
    keep: list[int] = []
    while order.size and len(keep) < MAX_DETECTIONS:
        current = int(order[0])
        keep.append(current)
        if order.size == 1:
            break
        remaining = order[1:]
        order = remaining[intersection_over_union(boxes[current], boxes[remaining]) <= NMS_IOU]
    return keep


class LiteRtYolo11n:
    def __init__(self, model_path: Path, threads: int) -> None:
        self.interpreter = Interpreter(model_path=str(model_path), num_threads=threads)
        self.interpreter.allocate_tensors()
        self.input = self.interpreter.get_input_details()[0]
        self.output = self.interpreter.get_output_details()[0]
        if tuple(int(value) for value in self.input["shape"]) != (1, 320, 320, 3):
            raise ValueError(f"unexpected input tensor shape: {self.input['shape']}")

    @staticmethod
    def _quantize(values: np.ndarray, detail: dict[str, Any]) -> np.ndarray:
        dtype = detail["dtype"]
        if dtype == np.float32:
            return values.astype(np.float32, copy=False)
        scale, zero_point = detail["quantization"]
        if scale <= 0.0:
            raise ValueError("quantized input has no scale")
        limits = np.iinfo(dtype)
        return np.clip(np.rint(values / scale + zero_point), limits.min, limits.max).astype(dtype)

    @staticmethod
    def _dequantize(values: np.ndarray, detail: dict[str, Any]) -> np.ndarray:
        if values.dtype == np.float32:
            return values
        scale, zero_point = detail["quantization"]
        if scale <= 0.0:
            raise ValueError("quantized output has no scale")
        return (values.astype(np.float32) - zero_point) * scale

    def detect(self, frame: np.ndarray) -> list[lab.Detection]:
        rgb, scale, pad_x, pad_y = letterbox(frame)
        model_input = self._quantize(rgb.astype(np.float32) / 255.0, self.input)[None, ...]
        self.interpreter.set_tensor(self.input["index"], model_input)
        self.interpreter.invoke()
        output = self._dequantize(self.interpreter.get_tensor(self.output["index"]), self.output)
        predictions = output[0]
        if predictions.shape[0] == 84:
            predictions = predictions.T
        if predictions.ndim != 2 or predictions.shape[1] != 84:
            raise ValueError(f"unexpected output tensor shape: {output.shape}")

        class_scores = predictions[:, 4:]
        classes = np.argmax(class_scores, axis=1)
        scores = class_scores[np.arange(class_scores.shape[0]), classes]
        selected = (classes == PERSON_CLASS) & (scores >= RAW_CONFIDENCE_FLOOR)
        predictions = predictions[selected]
        scores = scores[selected]
        if predictions.size == 0:
            return []

        # This LiteRT export preserves Ultralytics' TF normalized xywh output contract.
        xywh = predictions[:, :4] * 320.0
        boxes = np.empty_like(xywh)
        boxes[:, 0] = xywh[:, 0] - xywh[:, 2] / 2.0
        boxes[:, 1] = xywh[:, 1] - xywh[:, 3] / 2.0
        boxes[:, 2] = xywh[:, 0] + xywh[:, 2] / 2.0
        boxes[:, 3] = xywh[:, 1] + xywh[:, 3] / 2.0
        boxes[:, [0, 2]] = (boxes[:, [0, 2]] - pad_x) / scale
        boxes[:, [1, 3]] = (boxes[:, [1, 3]] - pad_y) / scale
        height, width = frame.shape[:2]
        boxes[:, [0, 2]] = np.clip(boxes[:, [0, 2]], 0.0, float(width))
        boxes[:, [1, 3]] = np.clip(boxes[:, [1, 3]], 0.0, float(height))
        return [
            lab.Detection(lab.Box(*(float(value) for value in boxes[index])), float(scores[index]))
            for index in nms(boxes, scores)
        ]


def run_detector(
    videos: list[lab.VideoData], model_path: Path, threads: int
) -> tuple[dict[str, list[list[lab.Detection]]], dict[str, Any]]:
    started = time.perf_counter_ns()
    detector = LiteRtYolo11n(model_path, threads)
    initialization_ms = (time.perf_counter_ns() - started) / 1_000_000.0
    detections: dict[str, list[list[lab.Detection]]] = {}
    timings: list[float] = []
    for video in videos:
        video_detections: list[list[lab.Detection]] = []
        for frame in video.frames:
            started = time.perf_counter_ns()
            video_detections.append(detector.detect(frame))
            timings.append((time.perf_counter_ns() - started) / 1_000_000.0)
        detections[video.id] = video_detections
    return detections, {
        "frames": len(timings),
        "initialization_ms": round(initialization_ms, 3),
        "mean_ms": round(statistics.mean(timings), 3),
        "median_ms": round(statistics.median(timings), 3),
        "p95_ms": round(float(np.percentile(timings, 95)), 3),
    }


def safety_pass(metrics: dict[str, Any]) -> bool:
    aggregate = metrics["aggregate"]
    return (
        aggregate["identity_switch_events"] == 0
        and aggregate["wrong_person_frames"] == 0
        and aggregate["false_tracked_while_target_invisible"] == 0
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--benchmark-dir", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--threads", type=int, default=4)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    manifest, videos = lab.load_benchmark(args.benchmark_dir)
    detections, timing = run_detector(videos, args.model, args.threads)
    config = replace(
        lab.corrected_label_winner_config(),
        name="yolo11n_litert_export",
        family="yolo11n_litert_export",
        detector="yolo11n_litert",
    )
    detector_data = {"yolo11n_litert": detections}
    no_motion = {video.id: [None] * len(video.frames) for video in videos}
    tuning, _ = lab.evaluate_config(config, videos, detector_data, no_motion, "tuning")
    held_out, _ = lab.evaluate_config(config, videos, detector_data, no_motion, "held_out")
    continuity = held_out["aggregate"]["identity_safe_continuity_percent"]
    result = {
        "schema_version": 1,
        "model": {
            "path": args.model.name,
            "sha256": lab.sha256(args.model),
            "size_bytes": args.model.stat().st_size,
            "input": [1, 320, 320, 3],
            "raw_confidence_floor": RAW_CONFIDENCE_FLOOR,
            "person_confidence": config.high_confidence,
            "nms_iou": NMS_IOU,
        },
        "benchmark_manifest_sha256": lab.sha256(args.benchmark_dir / "manifest.json"),
        "detector_timing_desktop_only": timing,
        "config": asdict(config),
        "tuning": tuning,
        "held_out": held_out,
        "safety_pass": safety_pass(held_out),
        "reference_continuity_percent": REFERENCE_CONTINUITY_PERCENT,
        "continuity_delta_percentage_points": round(continuity - REFERENCE_CONTINUITY_PERCENT, 3),
    }
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    aggregate = held_out["aggregate"]
    report = "\n".join(
        (
            "# YOLO11n LiteRT exported-model validation",
            "",
            f"- Model SHA-256: `{result['model']['sha256']}`",
            f"- Model size: {result['model']['size_bytes']} bytes",
            f"- Identity switches: {aggregate['identity_switch_events']}",
            f"- Wrong-person frames: {aggregate['wrong_person_frames']}",
            f"- False tracks while target invisible: {aggregate['false_tracked_while_target_invisible']}",
            f"- Identity-safe continuity: {continuity:.3f}%",
            f"- Reference: {REFERENCE_CONTINUITY_PERCENT:.3f}% ({result['continuity_delta_percentage_points']:+.3f} pp)",
            f"- Safety gate: {'PASS' if result['safety_pass'] else 'FAIL'}",
            "",
            "Desktop timing is recorded only to compare exported artifacts; it is not a Teclast estimate.",
            "",
        )
    )
    (args.output_dir / "report.md").write_text(report, encoding="utf-8")
    print(json.dumps({"safety_pass": result["safety_pass"], "continuity": continuity, "timing": timing}))
    return 0 if result["safety_pass"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
