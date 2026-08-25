#!/usr/bin/env python3
"""Bounded persistent single-object-tracker identity experiment.

LightTrack-Mobile and NanoTrackV3 own target localization after an explicit
selection. Cached person detections are read-only validation/safety evidence:
they can suppress an SOT result, but they can never supply or correct a target
box. Lost is latched for the remainder of an evaluated section.
"""

from __future__ import annotations

import argparse
import collections.abc
import csv
import hashlib
import json
import math
import os
import statistics
import sys
import time
import types
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Any, Protocol, Sequence

import cv2
import numpy as np

os.environ["YOLO_CONFIG_DIR"] = str(
    Path(__file__).resolve().parent / "work" / "ultralytics"
)
import optimize_benchmark as lab


MAX_TRACKERS = 2
MAX_CONFIGS_PER_TRACKER = 8
MAX_EXPERIMENTS = 16
MAX_REFINEMENT_PASSES = 1
BASELINE_CONTINUITY_PERCENT = 84.854
TARGET_CONTINUITY_PERCENT = 90.0
TRACKER_FAMILIES = ("lighttrack_mobile", "nanotrack_v3")

LIGHTTRACK_REPOSITORY = "https://github.com/researchmm/LightTrack.git"
LIGHTTRACK_COMMIT = "39c426f48ee674795cdf0e00301a0b4ad0785d2a"
LIGHTTRACK_WEIGHT_SHA256 = "b3ee9358386c0cb97948b1f68c8499f38929e9914663a6ef039c6ffa9117bef3"
LIGHTTRACK_PATH_NAME = (
    "back_04502514044521042540+cls_211000022+reg_100000111_ops_32"
)

NANOTRACK_REPOSITORY = "https://github.com/HonglinChu/SiamTrackers.git"
NANOTRACK_COMMIT = "248663fde6bf7c40190cf10ee396d5662919ecd3"
NANOTRACK_BACKBONE_SHA256 = (
    "50be1320abf9384fd3479ea829f95e244cf3f3838190c5ab60c750fb8c1ce828"
)
NANOTRACK_HEAD_SHA256 = (
    "75357903be3f4817c965af16cb5a11fd32b1467f597a408405a98635ac63f61d"
)


@dataclass(frozen=True)
class SafetyConfig:
    name: str
    tracker: str
    sot_confidence: float
    peak_margin: float
    template_similarity: float
    detector_confidence: float
    detector_iou: float
    detector_distance: float
    competitor_iou: float
    competitor_distance: float
    max_center_step: float
    max_frame_scale: float
    max_original_scale: float
    max_aspect_change: float
    detector_miss_grace_frames: int
    missing_ttl_s: float

    def __post_init__(self) -> None:
        if self.tracker not in TRACKER_FAMILIES:
            raise ValueError(f"unsupported tracker: {self.tracker}")
        if self.detector_miss_grace_frames < 0:
            raise ValueError("detector miss grace must be non-negative")


@dataclass(frozen=True)
class RawPrediction:
    box: lab.Box
    confidence: float
    peak_margin: float
    tracker_ms: float


@dataclass
class SectionTrajectory:
    selection_index: int
    stop_index: int
    selection_box: lab.Box
    predictions: dict[int, RawPrediction]
    init_ms: float


class SotTracker(Protocol):
    def initialize(self, frame: np.ndarray, box: lab.Box) -> float: ...

    def step(self, frame: np.ndarray) -> RawPrediction: ...


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_file(path: Path, expected_sha256: str) -> None:
    if not path.is_file():
        raise FileNotFoundError(
            f"missing tracker asset {path}; run fetch_sot_models.py first"
        )
    actual = file_sha256(path)
    if actual != expected_sha256:
        raise ValueError(f"tracker asset hash mismatch: {path}: {actual}")


def xywh(box: lab.Box) -> tuple[float, float, float, float]:
    return box.x1, box.y1, box.width, box.height


def box_from_center(
    center: np.ndarray, size: np.ndarray, width: int, height: int
) -> lab.Box:
    return lab.Box(
        float(center[0] - size[0] / 2),
        float(center[1] - size[1] / 2),
        float(center[0] + size[0] / 2),
        float(center[1] + size[1] / 2),
    ).clipped(width, height)


def crop_square(
    frame: np.ndarray,
    center: np.ndarray,
    output_size: int,
    source_size: float,
    channel_average: np.ndarray,
) -> np.ndarray:
    source_size = max(2, int(math.floor(source_size + 0.5)))
    half = (source_size + 1) / 2
    x1 = int(math.floor(center[0] - half + 0.5))
    y1 = int(math.floor(center[1] - half + 0.5))
    x2 = x1 + source_size - 1
    y2 = y1 + source_size - 1
    left = max(0, -x1)
    top = max(0, -y1)
    right = max(0, x2 - frame.shape[1] + 1)
    bottom = max(0, y2 - frame.shape[0] + 1)
    if left or top or right or bottom:
        padded = cv2.copyMakeBorder(
            frame,
            top,
            bottom,
            left,
            right,
            cv2.BORDER_CONSTANT,
            value=tuple(float(value) for value in channel_average),
        )
        x1 += left
        x2 += left
        y1 += top
        y2 += top
    else:
        padded = frame
    patch = padded[y1 : y2 + 1, x1 : x2 + 1]
    if patch.shape[:2] != (output_size, output_size):
        patch = cv2.resize(patch, (output_size, output_size))
    return patch


def response_peak_margin(response: np.ndarray, best_flat_index: int) -> float:
    response = np.asarray(response, dtype=np.float32)
    row, column = np.unravel_index(best_flat_index, response.shape)
    suppressed = response.copy()
    suppressed[
        max(0, row - 1) : min(response.shape[0], row + 2),
        max(0, column - 1) : min(response.shape[1], column + 2),
    ] = -math.inf
    second = float(np.max(suppressed)) if suppressed.size > 9 else 0.0
    return max(0.0, float(response[row, column]) - second)


class LightTrackMobile:
    """CPU adapter for the official LightTrack-Mobile checkpoint."""

    def __init__(self, repository: Path, threads: int) -> None:
        import torch

        require_file(
            repository / "snapshot" / "LightTrackM" / "LightTrackM.pth",
            LIGHTTRACK_WEIGHT_SHA256,
        )
        compatibility = types.ModuleType("torch._six")
        compatibility.container_abcs = collections.abc
        sys.modules.setdefault("torch._six", compatibility)
        sys.path.insert(0, str(repository))
        from lib.models.models import LightTrackM_Subnet

        torch.set_num_threads(threads)
        self.torch = torch
        self.model = LightTrackM_Subnet(LIGHTTRACK_PATH_NAME, stride=16)
        checkpoint = torch.load(
            repository / "snapshot" / "LightTrackM" / "LightTrackM.pth",
            map_location="cpu",
            weights_only=False,
        )
        state = {
            key.removeprefix("module."): value
            for key, value in checkpoint["state_dict"].items()
        }
        self.model.load_state_dict(state, strict=True)
        self.model.eval()
        self.mean = np.array([0.485, 0.456, 0.406], dtype=np.float32)[:, None, None]
        self.std = np.array([0.229, 0.224, 0.225], dtype=np.float32)[:, None, None]
        self.center = np.zeros(2, dtype=np.float64)
        self.size = np.zeros(2, dtype=np.float64)
        self.average = np.zeros(3, dtype=np.float64)
        self.frame_shape = (0, 0)
        self.instance_size = 256
        self.exemplar_size = 127
        self.penalty_k = 0.007
        self.window_influence = 0.225
        self.size_lr = 0.616

    def tensor(self, patch_rgb: np.ndarray) -> Any:
        values = patch_rgb.transpose(2, 0, 1).astype(np.float32) / 255.0
        values = (values - self.mean) / self.std
        return self.torch.from_numpy(values[None])

    def initialize(self, frame: np.ndarray, box: lab.Box) -> float:
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        self.center = np.array(box.center, dtype=np.float64)
        self.size = np.array([box.width, box.height], dtype=np.float64)
        self.average = np.mean(rgb, axis=(0, 1))
        self.frame_shape = frame.shape[:2]
        area_ratio = box.area / float(frame.shape[0] * frame.shape[1])
        self.instance_size = 288 if area_ratio < 0.004 else 256
        context_w = self.size[0] + 0.5 * float(np.sum(self.size))
        context_h = self.size[1] + 0.5 * float(np.sum(self.size))
        source_size = round(math.sqrt(context_w * context_h))
        patch = crop_square(
            rgb, self.center, self.exemplar_size, source_size, self.average
        )
        started = time.perf_counter_ns()
        with self.torch.inference_mode():
            self.model.template(self.tensor(patch))
        return (time.perf_counter_ns() - started) / 1_000_000.0

    def step(self, frame: np.ndarray) -> RawPrediction:
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        context_h = self.size[1] + 0.5 * float(np.sum(self.size))
        context_w = self.size[0] + 0.5 * float(np.sum(self.size))
        s_z = math.sqrt(context_w * context_h)
        scale_z = self.exemplar_size / s_z
        pad = ((self.instance_size - self.exemplar_size) / 2) / scale_z
        search_source_size = s_z + 2 * pad
        patch = crop_square(
            rgb,
            self.center,
            self.instance_size,
            search_source_size,
            self.average,
        )
        started = time.perf_counter_ns()
        with self.torch.inference_mode():
            cls_tensor, loc_tensor = self.model.track(self.tensor(patch))
            scores = self.torch.sigmoid(cls_tensor).squeeze().numpy()
            locations = loc_tensor.squeeze().numpy()
        tracker_ms = (time.perf_counter_ns() - started) / 1_000_000.0
        score_size = scores.shape[0]
        offset = np.arange(score_size) - math.floor(score_size / 2)
        grid_x, grid_y = np.meshgrid(offset, offset)
        grid_x = grid_x * 16 + self.instance_size // 2
        grid_y = grid_y * 16 + self.instance_size // 2
        x1 = grid_x - locations[0]
        y1 = grid_y - locations[1]
        x2 = grid_x + locations[2]
        y2 = grid_y + locations[3]
        predicted_w = np.maximum(1e-3, x2 - x1)
        predicted_h = np.maximum(1e-3, y2 - y1)

        def size_with_pad(width: np.ndarray, height: np.ndarray) -> np.ndarray:
            padding = (width + height) * 0.5
            return np.sqrt((width + padding) * (height + padding))

        target_scaled = self.size * scale_z
        size_change = np.maximum(
            size_with_pad(predicted_w, predicted_h)
            / size_with_pad(target_scaled[0], target_scaled[1]),
            size_with_pad(target_scaled[0], target_scaled[1])
            / size_with_pad(predicted_w, predicted_h),
        )
        ratio = (target_scaled[0] / target_scaled[1]) / (
            predicted_w / predicted_h
        )
        ratio_change = np.maximum(ratio, 1.0 / ratio)
        penalty = np.exp(-(ratio_change * size_change - 1) * self.penalty_k)
        window = np.outer(np.hanning(score_size), np.hanning(score_size))
        penalized = (
            penalty * scores * (1 - self.window_influence)
            + window * self.window_influence
        )
        best = int(np.argmax(penalized))
        row, column = np.unravel_index(best, penalized.shape)
        predicted_center = np.array(
            [(x1[row, column] + x2[row, column]) / 2,
             (y1[row, column] + y2[row, column]) / 2]
        )
        predicted_size = np.array(
            [predicted_w[row, column], predicted_h[row, column]]
        )
        displacement = (
            predicted_center - self.instance_size // 2
        ) / scale_z
        predicted_size /= scale_z
        confidence = float(scores[row, column])
        rate = float(penalty[row, column] * confidence * self.size_lr)
        first_size = predicted_size * rate + self.size * (1 - rate)
        self.size = self.size * (1 - rate) + first_size * rate
        self.center += displacement
        self.center[0] = np.clip(self.center[0], 0, frame.shape[1])
        self.center[1] = np.clip(self.center[1], 0, frame.shape[0])
        self.size[0] = np.clip(self.size[0], 10, frame.shape[1])
        self.size[1] = np.clip(self.size[1], 10, frame.shape[0])
        return RawPrediction(
            box_from_center(self.center, self.size, frame.shape[1], frame.shape[0]),
            confidence,
            response_peak_margin(penalized, best),
            tracker_ms,
        )


class NanoTrackV3:
    """OpenVINO adapter for the strongest repository-provided NanoTrack."""

    def __init__(self, repository: Path, threads: int) -> None:
        from openvino import Core

        backbone_path = (
            repository
            / "NanoTrack"
            / "models"
            / "nanotrackv3"
            / "nanotrack_backbone.onnx"
        )
        head_path = (
            repository
            / "NanoTrack"
            / "models"
            / "nanotrackv3"
            / "nanotrack_head.onnx"
        )
        require_file(backbone_path, NANOTRACK_BACKBONE_SHA256)
        require_file(head_path, NANOTRACK_HEAD_SHA256)
        core = Core()
        backbone = core.read_model(backbone_path)
        backbone.reshape({"input": [1, 3, -1, -1]})
        properties = {"INFERENCE_NUM_THREADS": str(threads)}
        self.backbone = core.compile_model(backbone, "CPU", properties)
        self.head = core.compile_model(core.read_model(head_path), "CPU", properties)
        self.center = np.zeros(2, dtype=np.float64)
        self.size = np.zeros(2, dtype=np.float64)
        self.average = np.zeros(3, dtype=np.float64)
        self.template_features: np.ndarray | None = None
        self.exemplar_size = 127
        self.instance_size = 255
        self.stride = 16
        self.output_size = 15
        self.penalty_k = 0.138
        self.window_influence = 0.455
        self.size_lr = 0.348

    @staticmethod
    def input_tensor(patch: np.ndarray) -> np.ndarray:
        return patch.transpose(2, 0, 1)[None].astype(np.float32)

    def initialize(self, frame: np.ndarray, box: lab.Box) -> float:
        self.center = np.array(box.center, dtype=np.float64)
        self.size = np.array([box.width, box.height], dtype=np.float64)
        self.average = np.mean(frame, axis=(0, 1))
        context_w = self.size[0] + 0.5 * float(np.sum(self.size))
        context_h = self.size[1] + 0.5 * float(np.sum(self.size))
        source_size = round(math.sqrt(context_w * context_h))
        patch = crop_square(
            frame, self.center, self.exemplar_size, source_size, self.average
        )
        started = time.perf_counter_ns()
        output = self.backbone(self.input_tensor(patch))
        self.template_features = output[self.backbone.output()]
        return (time.perf_counter_ns() - started) / 1_000_000.0

    def step(self, frame: np.ndarray) -> RawPrediction:
        if self.template_features is None:
            raise RuntimeError("NanoTrack was not initialized")
        context_w = self.size[0] + 0.5 * float(np.sum(self.size))
        context_h = self.size[1] + 0.5 * float(np.sum(self.size))
        s_z = math.sqrt(context_w * context_h)
        scale_z = self.exemplar_size / s_z
        search_source_size = s_z * self.instance_size / self.exemplar_size
        patch = crop_square(
            frame,
            self.center,
            self.instance_size,
            search_source_size,
            self.average,
        )
        started = time.perf_counter_ns()
        search_output = self.backbone(self.input_tensor(patch))
        search_features = search_output[self.backbone.output()]
        head_output = self.head(
            {
                self.head.input(0): self.template_features,
                self.head.input(1): search_features,
            }
        )
        values = list(head_output.values())
        cls = next(value for value in values if value.shape[1] == 2)
        loc = next(value for value in values if value.shape[1] == 4)
        tracker_ms = (time.perf_counter_ns() - started) / 1_000_000.0
        shifted = cls - np.max(cls, axis=1, keepdims=True)
        probabilities = np.exp(shifted)
        probabilities /= np.sum(probabilities, axis=1, keepdims=True)
        scores = probabilities[0, 1]
        offset = np.arange(self.output_size) - self.output_size // 2
        grid_x, grid_y = np.meshgrid(offset * self.stride, offset * self.stride)
        x1 = grid_x - loc[0, 0]
        y1 = grid_y - loc[0, 1]
        x2 = grid_x + loc[0, 2]
        y2 = grid_y + loc[0, 3]
        predicted_w = np.maximum(1e-3, x2 - x1)
        predicted_h = np.maximum(1e-3, y2 - y1)

        def size_with_pad(width: np.ndarray, height: np.ndarray) -> np.ndarray:
            padding = (width + height) * 0.5
            return np.sqrt((width + padding) * (height + padding))

        target_scaled = self.size * scale_z
        size_change = np.maximum(
            size_with_pad(predicted_w, predicted_h)
            / size_with_pad(target_scaled[0], target_scaled[1]),
            size_with_pad(target_scaled[0], target_scaled[1])
            / size_with_pad(predicted_w, predicted_h),
        )
        ratio = (target_scaled[0] / target_scaled[1]) / (
            predicted_w / predicted_h
        )
        ratio_change = np.maximum(ratio, 1.0 / ratio)
        penalty = np.exp(-(ratio_change * size_change - 1) * self.penalty_k)
        window = np.outer(
            np.hanning(self.output_size), np.hanning(self.output_size)
        )
        penalized = (
            penalty * scores * (1 - self.window_influence)
            + window * self.window_influence
        )
        best = int(np.argmax(penalized))
        row, column = np.unravel_index(best, penalized.shape)
        prediction = np.array(
            [
                (x1[row, column] + x2[row, column]) / 2,
                (y1[row, column] + y2[row, column]) / 2,
                predicted_w[row, column],
                predicted_h[row, column],
            ]
        ) / scale_z
        confidence = float(scores[row, column])
        rate = float(penalty[row, column] * confidence * self.size_lr)
        self.center += prediction[:2]
        self.size = self.size * (1 - rate) + prediction[2:] * rate
        self.center[0] = np.clip(self.center[0], 0, frame.shape[1])
        self.center[1] = np.clip(self.center[1], 0, frame.shape[0])
        self.size[0] = np.clip(self.size[0], 10, frame.shape[1])
        self.size[1] = np.clip(self.size[1], 10, frame.shape[0])
        return RawPrediction(
            box_from_center(self.center, self.size, frame.shape[1], frame.shape[0]),
            confidence,
            response_peak_margin(penalized, best),
            tracker_ms,
        )


def build_tracker(
    family: str, third_party_root: Path, threads: int
) -> SotTracker:
    if family == "lighttrack_mobile":
        return LightTrackMobile(third_party_root / "LightTrack", threads)
    if family == "nanotrack_v3":
        return NanoTrackV3(third_party_root / "SiamTrackers", threads)
    raise ValueError(f"unsupported tracker: {family}")


def interval_indices(
    video: lab.VideoData, intervals: Sequence[Sequence[float]]
) -> list[tuple[int, int]]:
    sections: list[tuple[int, int]] = []
    for start_s, end_s in intervals:
        start = next(
            index for index, timestamp in enumerate(video.timestamps)
            if timestamp >= start_s
        )
        stop = next(
            (
                index
                for index, timestamp in enumerate(video.timestamps)
                if timestamp >= end_s
            ),
            len(video.frames),
        )
        sections.append((start, stop))
    return sections


def selection_box(video: lab.VideoData, index: int) -> lab.Box:
    annotation = video.annotations[index]
    if not annotation["target_visible"]:
        raise ValueError(f"selection frame is not visible: {video.id}:{index}")
    return lab.Box.from_normalized(
        annotation["target_box_norm"],
        video.frames[index].shape[1],
        video.frames[index].shape[0],
    )


def precompute_trajectories(
    family: str,
    videos: Sequence[lab.VideoData],
    partition: str,
    third_party_root: Path,
    threads: int,
) -> tuple[dict[tuple[str, int, int], SectionTrajectory], dict[str, Any]]:
    tracker = build_tracker(family, third_party_root, threads)
    trajectories: dict[tuple[str, int, int], SectionTrajectory] = {}
    step_times: list[float] = []
    init_times: list[float] = []
    for video in videos:
        intervals = (
            video.tuning_intervals
            if partition == "tuning"
            else video.held_out_intervals
        )
        for start, stop in interval_indices(video, intervals):
            initial = selection_box(video, start)
            init_ms = tracker.initialize(video.frames[start], initial)
            predictions: dict[int, RawPrediction] = {}
            for index in range(start + 1, stop):
                prediction = tracker.step(video.frames[index])
                predictions[index] = prediction
                step_times.append(prediction.tracker_ms)
            init_times.append(init_ms)
            trajectories[(video.id, start, stop)] = SectionTrajectory(
                start, stop, initial, predictions, init_ms
            )
    timing = {
        "architecture": family,
        "partition": partition,
        "steps": len(step_times),
        "initializations": len(init_times),
        "mean_step_ms": round(statistics.mean(step_times), 3),
        "median_step_ms": round(statistics.median(step_times), 3),
        "p95_step_ms": round(float(np.percentile(step_times, 95)), 3),
        "mean_initialization_ms": round(statistics.mean(init_times), 3),
        "desktop_context": "Windows x86-64 CPU, configured thread count; excludes cached YOLO detector",
    }
    return trajectories, timing


def duplicate_detection(first: lab.Box, second: lab.Box) -> bool:
    return lab.iou(first, second) >= 0.70


def validate_prediction(
    video: lab.VideoData,
    index: int,
    raw: RawPrediction,
    previous_raw: lab.Box,
    original: lab.Box,
    frozen_histogram: np.ndarray | None,
    detections: Sequence[lab.Detection],
    detector_misses: int,
    config: SafetyConfig,
) -> tuple[list[str], int, dict[str, Any]]:
    frame = video.frames[index]
    height, width = frame.shape[:2]
    reasons: list[str] = []
    if raw.confidence < config.sot_confidence:
        reasons.append("weak SOT confidence")
    if raw.peak_margin < config.peak_margin:
        reasons.append("ambiguous SOT response")
    step = lab.center_distance(raw.box, previous_raw, width, height)
    frame_scale = max(
        raw.box.area / max(previous_raw.area, 1e-6),
        previous_raw.area / max(raw.box.area, 1e-6),
    )
    original_scale = max(
        raw.box.area / max(original.area, 1e-6),
        original.area / max(raw.box.area, 1e-6),
    )
    aspect_change = max(
        (raw.box.width / raw.box.height) / (original.width / original.height),
        (original.width / original.height) / (raw.box.width / raw.box.height),
    )
    if step > config.max_center_step:
        reasons.append("implausible center step")
    if frame_scale > config.max_frame_scale:
        reasons.append("implausible frame-to-frame scale")
    if original_scale > config.max_original_scale:
        reasons.append("implausible original-template scale")
    if aspect_change > config.max_aspect_change:
        reasons.append("implausible original-template aspect")
    appearance = lab.hist_similarity(
        frozen_histogram, lab.histogram(frame, raw.box)
    )
    if appearance < config.template_similarity:
        reasons.append("frozen-template appearance disagreement")

    eligible = [
        detection
        for detection in detections
        if detection.confidence >= config.detector_confidence
    ]
    matching = [
        detection
        for detection in eligible
        if lab.iou(raw.box, detection.box) >= config.detector_iou
        or lab.center_distance(raw.box, detection.box, width, height)
        <= config.detector_distance
    ]
    matched = max(
        matching,
        key=lambda item: (
            lab.iou(raw.box, item.box),
            -lab.center_distance(raw.box, item.box, width, height),
            item.confidence,
        ),
        default=None,
    )
    if matched is None:
        detector_misses += 1
        if eligible:
            reasons.append("strong detector/SOT disagreement")
        elif detector_misses > config.detector_miss_grace_frames:
            reasons.append("person detector absence")
    else:
        detector_misses = 0
        competitors = [
            detection
            for detection in eligible
            if detection is not matched
            and not duplicate_detection(detection.box, matched.box)
            and (
                lab.iou(detection.box, raw.box) >= config.competitor_iou
                or lab.iou(detection.box, matched.box) >= config.competitor_iou
                or lab.center_distance(detection.box, raw.box, width, height)
                <= config.competitor_distance
            )
        ]
        if len(matching) > 1 or competitors:
            reasons.append("competitor overlap uncertainty")
    evidence = {
        "confidence": round(raw.confidence, 6),
        "peak_margin": round(raw.peak_margin, 6),
        "template_similarity": round(appearance, 6),
        "center_step": round(step, 6),
        "frame_scale": round(frame_scale, 6),
        "original_scale": round(original_scale, 6),
        "aspect_change": round(aspect_change, 6),
        "eligible_detections": len(eligible),
        "matching_detections": len(matching),
        "matched_detector_box": asdict(matched.box) if matched else None,
        "detector_misses": detector_misses,
    }
    return reasons, detector_misses, evidence


def run_section(
    video: lab.VideoData,
    trajectory: SectionTrajectory,
    detections: Sequence[Sequence[lab.Detection]],
    config: SafetyConfig,
    trace: list[dict[str, Any]] | None = None,
) -> list[lab.Result]:
    results = [
        lab.Result("Unselected", "none", None, "outside evaluated section")
        for _ in video.frames
    ]
    start = trajectory.selection_index
    original = trajectory.selection_box
    frozen_histogram = lab.histogram(video.frames[start], original)
    results[start] = lab.Result(
        "Tracked", "explicit selection", original, "immutable SOT template initialized"
    )
    previous_raw = original
    last_accepted_s = video.timestamps[start]
    detector_misses = 0
    lost = False
    for index in range(start + 1, trajectory.stop_index):
        raw = trajectory.predictions[index]
        if lost:
            results[index] = lab.Result(
                "Lost", "none", None, "Lost latched; explicit reselection required"
            )
            if trace is not None:
                trace.append(
                    {
                        "video": video.id,
                        "canonical_index": index,
                        "timestamp_s": video.timestamps[index],
                        "state": "Lost",
                        "reasons": ["Lost latched; explicit reselection required"],
                        "raw_sot_box": asdict(raw.box),
                        "output_box": None,
                    }
                )
            previous_raw = raw.box
            continue
        reasons, detector_misses, evidence = validate_prediction(
            video,
            index,
            raw,
            previous_raw,
            original,
            frozen_histogram,
            detections[index],
            detector_misses,
            config,
        )
        previous_raw = raw.box
        if not reasons:
            last_accepted_s = video.timestamps[index]
            results[index] = lab.Result(
                "Tracked",
                "SOT",
                raw.box,
                "SOT accepted by safety validation",
                confidence=raw.confidence,
                tracker_quality=raw.peak_margin,
            )
        elif video.timestamps[index] - last_accepted_s >= config.missing_ttl_s:
            lost = True
            results[index] = lab.Result(
                "Lost", "none", None, "; ".join(reasons) + "; Lost latched"
            )
        else:
            results[index] = lab.Result(
                "Missing", "none", None, "; ".join(reasons)
            )
        if trace is not None:
            trace.append(
                {
                    "video": video.id,
                    "canonical_index": index,
                    "timestamp_s": video.timestamps[index],
                    "target_visible": bool(video.annotations[index]["target_visible"]),
                    "state": results[index].state,
                    "reasons": reasons,
                    "raw_sot_box": asdict(raw.box),
                    "output_box": asdict(results[index].box)
                    if results[index].box
                    else None,
                    **evidence,
                }
            )
    return results


def evaluate_config(
    config: SafetyConfig,
    videos: Sequence[lab.VideoData],
    detections: dict[str, list[list[lab.Detection]]],
    trajectories: dict[tuple[str, int, int], SectionTrajectory],
    partition: str,
    collect_trace: bool = False,
) -> tuple[dict[str, Any], dict[str, list[lab.Result]], list[dict[str, Any]]]:
    per_video: dict[str, dict[str, Any]] = {}
    all_results: dict[str, list[lab.Result]] = {}
    trace: list[dict[str, Any]] = []
    section_metrics: list[dict[str, Any]] = []
    for video in videos:
        intervals = (
            video.tuning_intervals
            if partition == "tuning"
            else video.held_out_intervals
        )
        combined = [
            lab.Result("Unselected", "none", None, "outside evaluated section")
            for _ in video.frames
        ]
        for (start_s, end_s), (start, stop) in zip(
            intervals, interval_indices(video, intervals), strict=True
        ):
            section_trace: list[dict[str, Any]] | None = [] if collect_trace else None
            section = run_section(
                video,
                trajectories[(video.id, start, stop)],
                detections[video.id],
                config,
                section_trace,
            )
            combined[start:stop] = section[start:stop]
            if section_trace is not None:
                trace.extend(section_trace)
            section_metrics.append(
                lab.evaluate_results(
                    video,
                    section,
                    partition,
                    intervals=[[start_s, end_s]],
                )
            )
        all_results[video.id] = combined
        per_video[video.id] = lab.evaluate_results(video, combined, partition)
    tracker_times = {video.id: 0.0 for video in videos}
    aggregate = lab.aggregate(per_video, tracker_times, section_metrics)
    aggregate.pop("rank_tuple", None)
    return {
        "config": asdict(config),
        "partition": partition,
        "per_video": per_video,
        "aggregate": aggregate,
    }, all_results, trace


def safety_errors(metrics: dict[str, Any]) -> int:
    aggregate = metrics["aggregate"]
    return (
        aggregate["identity_switch_events"]
        + aggregate["wrong_person_frames"]
        + aggregate["false_tracked_while_target_invisible"]
    )


def safety_eligible(metrics: dict[str, Any]) -> bool:
    return safety_errors(metrics) == 0


def rank_metrics(metrics: dict[str, Any]) -> tuple[Any, ...]:
    aggregate = metrics["aggregate"]
    return (
        safety_errors(metrics),
        -aggregate["identity_safe_continuity_percent"],
        aggregate["lost_visible_frames"],
        aggregate["missing_visible_frames"],
        aggregate["localization_drift_frames"],
        -aggregate["mean_iou_when_tracked"],
    )


def initial_configs(family: str) -> list[SafetyConfig]:
    return [
        SafetyConfig(
            f"{family}_lenient", family, 0.35, 0.00, 0.16, 0.20,
            0.05, 0.12, 0.18, 0.07, 0.18, 2.6, 8.0, 3.0, 3, 1.2,
        ),
        SafetyConfig(
            f"{family}_balanced", family, 0.45, 0.015, 0.25, 0.25,
            0.10, 0.10, 0.12, 0.06, 0.14, 2.2, 6.0, 2.5, 1, 1.0,
        ),
        SafetyConfig(
            f"{family}_appearance", family, 0.48, 0.015, 0.38, 0.25,
            0.10, 0.10, 0.10, 0.055, 0.13, 2.0, 5.0, 2.3, 1, 1.2,
        ),
        SafetyConfig(
            f"{family}_strict", family, 0.56, 0.035, 0.34, 0.30,
            0.15, 0.08, 0.08, 0.05, 0.10, 1.8, 4.0, 2.0, 0, 0.8,
        ),
    ]


def refinement_configs(best: SafetyConfig) -> list[SafetyConfig]:
    return [
        replace(
            best,
            name=f"{best.tracker}_refine_continuity",
            sot_confidence=max(0.25, best.sot_confidence - 0.05),
            peak_margin=max(0.0, best.peak_margin - 0.01),
            template_similarity=max(0.10, best.template_similarity - 0.04),
            detector_miss_grace_frames=min(4, best.detector_miss_grace_frames + 1),
            missing_ttl_s=min(1.6, best.missing_ttl_s + 0.2),
        ),
        replace(
            best,
            name=f"{best.tracker}_refine_identity",
            sot_confidence=min(0.70, best.sot_confidence + 0.05),
            peak_margin=min(0.08, best.peak_margin + 0.01),
            template_similarity=min(0.50, best.template_similarity + 0.04),
            detector_miss_grace_frames=0,
            missing_ttl_s=max(0.6, best.missing_ttl_s - 0.2),
        ),
    ]


def baseline_metrics(
    videos: Sequence[lab.VideoData],
    detections: dict[str, list[list[lab.Detection]]],
) -> dict[str, Any]:
    no_motion = {video.id: [None] * len(video.frames) for video in videos}
    metrics, _ = lab.evaluate_config(
        lab.corrected_label_winner_config(),
        list(videos),
        {"yolo11n": detections},
        no_motion,
        "held_out",
    )
    aggregate = metrics["aggregate"]
    expected = {
        "identity_switch_events": 0,
        "wrong_person_frames": 0,
        "false_tracked_while_target_invisible": 0,
        "identity_safe_continuity_percent": BASELINE_CONTINUITY_PERCENT,
    }
    for key, value in expected.items():
        if aggregate[key] != value:
            raise AssertionError(
                f"corrected baseline mismatch: {key}={aggregate[key]} != {value}"
            )
    return metrics


def compact_metrics(metrics: dict[str, Any]) -> dict[str, Any]:
    keep = (
        "visible_target_frames",
        "identity_safe_tracked_frames",
        "identity_switch_events",
        "wrong_person_frames",
        "false_tracked_while_target_invisible",
        "identity_safe_continuity_percent",
        "lost_visible_frames",
        "missing_visible_frames",
        "localization_drift_frames",
        "mean_iou_when_tracked",
        "jitter_rms_norm",
        "wrong_indices",
    )
    return {key: metrics[key] for key in keep if key in metrics}


def write_metrics_csv(
    path: Path,
    baseline: dict[str, Any],
    frozen: dict[str, dict[str, Any]],
) -> None:
    fields = [
        "candidate", "video", "visible_target_frames",
        "identity_safe_tracked_frames", "identity_switch_events",
        "wrong_person_frames", "false_tracked_while_target_invisible",
        "identity_safe_continuity_percent", "lost_visible_frames",
        "missing_visible_frames", "mean_iou_when_tracked",
    ]
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        entries = {"safe_baseline": baseline, **frozen}
        for candidate, metrics in entries.items():
            for video, item in metrics["per_video"].items():
                writer.writerow(
                    {"candidate": candidate, "video": video,
                     **{key: item[key] for key in fields[2:]}}
                )
            aggregate = metrics["aggregate"]
            writer.writerow(
                {"candidate": candidate, "video": "AGGREGATE",
                 **{key: aggregate[key] for key in fields[2:]}}
            )


def write_trace_csv(path: Path, rows: Sequence[dict[str, Any]]) -> None:
    fields = [
        "video", "canonical_index", "timestamp_s", "target_visible", "state",
        "reasons", "confidence", "peak_margin", "template_similarity",
        "center_step", "frame_scale", "original_scale", "aspect_change",
        "eligible_detections", "matching_detections", "detector_misses",
        "raw_sot_box", "matched_detector_box", "output_box",
    ]
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow(
                {
                    key: json.dumps(row.get(key))
                    if key in {"reasons", "raw_sot_box", "matched_detector_box", "output_box"}
                    else row.get(key)
                    for key in fields
                }
            )


def known_sequence_summary(
    video: lab.VideoData,
    results: Sequence[lab.Result],
    start: int = 124,
    stop: int = 160,
) -> dict[str, Any]:
    interval = [[video.timestamps[start], video.timestamps[stop] + 1e-6]]
    metrics = lab.evaluate_results(video, list(results), "known", interval)
    return {
        "canonical_frames": [start, stop],
        "metrics": compact_metrics(metrics),
        "state_counts": {
            state: sum(results[index].state == state for index in range(start, stop + 1))
            for state in ("Tracked", "Missing", "Lost")
        },
        "wrong_indices": metrics["wrong_indices"],
    }


def contiguous_runs(indices: Sequence[int]) -> list[list[int]]:
    runs: list[list[int]] = []
    for index in indices:
        if not runs or index != runs[-1][-1] + 1:
            runs.append([index])
        else:
            runs[-1].append(index)
    return runs


def failure_diagnostics(
    videos: Sequence[lab.VideoData],
    results: dict[str, list[lab.Result]],
) -> dict[str, Any]:
    wrong: dict[str, list[int]] = {}
    false_invisible: dict[str, list[int]] = {}
    for video in videos:
        wrong_indices: list[int] = []
        false_indices: list[int] = []
        for index, (annotation, result) in enumerate(
            zip(video.annotations, results[video.id], strict=True)
        ):
            if result.state != "Tracked" or result.box is None:
                continue
            if not annotation["target_visible"]:
                false_indices.append(index)
                continue
            truth = lab.Box.from_normalized(
                annotation["target_box_norm"],
                video.frames[index].shape[1],
                video.frames[index].shape[0],
            )
            if not (
                lab.center_distance(
                    result.box,
                    truth,
                    video.frames[index].shape[1],
                    video.frames[index].shape[0],
                )
                <= 0.12
                and lab.iou(result.box, truth) >= 0.05
            ):
                wrong_indices.append(index)
        wrong[video.id] = wrong_indices
        false_invisible[video.id] = false_indices
    return {
        "wrong_person_indices": wrong,
        "wrong_person_runs": {
            video: [[run[0], run[-1]] for run in contiguous_runs(indices)]
            for video, indices in wrong.items()
        },
        "false_tracking_while_invisible_indices": false_invisible,
        "false_tracking_while_invisible_runs": {
            video: [[run[0], run[-1]] for run in contiguous_runs(indices)]
            for video, indices in false_invisible.items()
        },
    }


def render_courtyard_contact_sheet(
    path: Path,
    video: lab.VideoData,
    results: dict[str, Sequence[lab.Result]],
) -> None:
    indices = [124, 128, 132, 136, 140, 144, 148, 152, 156, 160]
    cells: list[np.ndarray] = []
    colors = {"Tracked": (60, 210, 60), "Missing": (0, 190, 255), "Lost": (40, 40, 230)}
    for index in indices:
        frame = cv2.resize(video.frames[index], (480, 270))
        sx = 480 / video.frames[index].shape[1]
        sy = 270 / video.frames[index].shape[0]
        truth = selection_box(video, index)
        cv2.rectangle(
            frame,
            (round(truth.x1 * sx), round(truth.y1 * sy)),
            (round(truth.x2 * sx), round(truth.y2 * sy)),
            (255, 255, 255), 2,
        )
        lines = [f"frame {index}  white=GT"]
        y = 40
        for name, video_results in results.items():
            result = video_results[index]
            color = colors.get(result.state, (160, 160, 160))
            if result.box is not None:
                cv2.rectangle(
                    frame,
                    (round(result.box.x1 * sx), round(result.box.y1 * sy)),
                    (round(result.box.x2 * sx), round(result.box.y2 * sy)),
                    color, 2,
                )
            lines.append(f"{name}: {result.state}")
        for line in lines:
            cv2.putText(frame, line, (8, y), cv2.FONT_HERSHEY_SIMPLEX, 0.52, (0, 0, 0), 3, cv2.LINE_AA)
            cv2.putText(frame, line, (8, y), cv2.FONT_HERSHEY_SIMPLEX, 0.52, (255, 255, 255), 1, cv2.LINE_AA)
            y += 20
        cells.append(frame)
    sheet = np.vstack([np.hstack(cells[:5]), np.hstack(cells[5:])])
    cv2.imwrite(str(path), sheet)


def deployment_assessment() -> dict[str, Any]:
    return {
        "lighttrack_mobile": {
            "model_size_bytes": 8055107,
            "parameters": 1969397,
            "flops": "0.53 GFLOPs reported by the CVPR 2021 paper",
            "code_license": "MIT (official repository LICENSE)",
            "pretrained_weight_license": "unclear: checkpoint is committed beside the MIT code but has no explicit model-weight license or provenance terms",
            "proprietary_deployable": False,
            "android_path": "Export the frozen template/backbone/head graph, then use ncnn or ONNX Runtime Mobile; LiteRT conversion is unverified",
            "runtime_required": "ncnn preferred; ONNX Runtime Mobile possible after a verified export",
            "teclast_p50ai": "Small enough for the A733 CPU, but latency/thermal behavior must be measured on-device; no inference is made from desktop timing",
        },
        "nanotrack_v3": {
            "model_size_bytes": 2615375,
            "parameters": 541400,
            "flops": "115.6 MFLOPs reported by the NanoTrack repository",
            "code_license": "Apache-2.0 (NanoTrack directory LICENSE)",
            "pretrained_weight_license": "unclear: ONNX/PyTorch weights have no separate explicit license or provenance grant",
            "proprietary_deployable": False,
            "android_path": "Use the provided two-part ONNX graph with ONNX Runtime Mobile, or convert/validate NanoTrackV3 with ncnn; only V1 has a supplied ncnn Android demo",
            "runtime_required": "ONNX Runtime Mobile for V3 as-is; ncnn after conversion is the leaner likely prototype path",
            "teclast_p50ai": "The 2.62 MB/115.6 MFLOP graph is the more plausible A733 CPU candidate, but must be benchmarked on the actual tablet",
        },
        "tablet_context": "Teclast P50Ai uses Allwinner A733 (2x Cortex-A76 + 6x Cortex-A55), IMG BXM-4-64 MC1 GPU, and 3-TOPS NPU; generic NNAPI/NPU availability for these graphs is not established",
    }


def write_report(path: Path, payload: dict[str, Any]) -> None:
    baseline = payload["baseline_heldout"]["aggregate"]
    nano_courtyard_runs = ", ".join(
        f"{start}–{stop}"
        for start, stop in payload["families"]["nanotrack_v3"]["diagnostics"]
        ["wrong_person_runs"]["courtyard_competitor"]
    ) or "none"
    lines = [
        "# Persistent SOT identity experiment",
        "",
        "## Architecture",
        "",
        "```mermaid",
        "flowchart LR",
        "    A[Explicit user selection box] --> B[Freeze original SOT template]",
        "    B --> C[Dedicated SOT predicts target box]",
        "    C --> D{Fail-closed validation}",
        "    E[Person detector evidence] --> D",
        "    D -->|reliable and unambiguous| F[Tracked: SOT box only]",
        "    D -->|uncertain| G[Missing]",
        "    G -->|TTL elapsed| H[Lost latched]",
        "    H --> I[Explicit reselection required]",
        "```",
        "",
        "The detector never proposes, replaces, corrects, or reinitializes the target. It only validates person presence, SOT agreement, and competitor risk. The immutable selection histogram and immutable neural template are safety evidence; neither is updated. SOT continues internally through short Missing periods, while Lost is terminal within the section.",
        "",
        "## Bounded protocol",
        "",
        f"- Tracker families: {len(payload['families'])}/{MAX_TRACKERS}.",
        f"- Total configurations: {payload['limits']['actual_experiments']}/{MAX_EXPERIMENTS}; no family exceeded {MAX_CONFIGS_PER_TRACKER}.",
        f"- Refinement passes: {payload['limits']['refinement_passes']}/{MAX_REFINEMENT_PASSES}.",
        "- Configurations were ranked on tuning partitions safety-first. One candidate per family was frozen before held-out trajectories were evaluated.",
        "- Frozen annotation hashes and canonical JPEG hashes were verified by the existing benchmark loader; ground truth was not changed.",
        "",
        "## Held-out results",
        "",
    ]
    for family, item in payload["families"].items():
        tuning = item["tuning_summary"]
        lines.append(
            f"- `{family}` tuning: {tuning['safety_eligible_configs']}/{tuning['configs']} safety-eligible; continuity range {tuning['minimum_continuity_percent']:.3f}%–{tuning['maximum_continuity_percent']:.3f}%. The frozen safety-first diagnostic was tuning-eligible: **{tuning['frozen_was_safety_eligible']}**."
        )
    lines.extend([
        "",
        "| Candidate | Switches | Wrong | False invisible | Continuity | Delta vs baseline | Courtyard competitor | Accepted |",
        "|---|---:|---:|---:|---:|---:|---:|---|",
        f"| Safe baseline | {baseline['identity_switch_events']} | {baseline['wrong_person_frames']} | {baseline['false_tracked_while_target_invisible']} | {baseline['identity_safe_continuity_percent']:.3f}% | - | {payload['baseline_heldout']['per_video']['courtyard_competitor']['identity_safe_continuity_percent']:.3f}% | reference |",
    ])
    for family, item in payload["families"].items():
        aggregate = item["heldout"]["aggregate"]
        courtyard = item["heldout"]["per_video"]["courtyard_competitor"]
        lines.append(
            f"| {family} | {aggregate['identity_switch_events']} | {aggregate['wrong_person_frames']} | {aggregate['false_tracked_while_target_invisible']} | {aggregate['identity_safe_continuity_percent']:.3f}% | {aggregate['identity_safe_continuity_percent'] - BASELINE_CONTINUITY_PERCENT:+.3f} pp | {courtyard['identity_safe_continuity_percent']:.3f}% | {item['accepted']} |"
        )
    lines.extend(["", "### Per-video continuity", "", "| Video | Baseline | LightTrack-Mobile | NanoTrackV3 |", "|---|---:|---:|---:|"])
    for video in payload["baseline_heldout"]["per_video"]:
        lines.append(
            f"| {video} | {payload['baseline_heldout']['per_video'][video]['identity_safe_continuity_percent']:.3f}% | {payload['families']['lighttrack_mobile']['heldout']['per_video'][video]['identity_safe_continuity_percent']:.3f}% | {payload['families']['nanotrack_v3']['heldout']['per_video'][video]['identity_safe_continuity_percent']:.3f}% |"
        )
    lines.extend(["", "## Courtyard competitor: frames 124-160", ""])
    for family, item in payload["families"].items():
        known = item["courtyard_124_160"]
        metrics = known["metrics"]
        lines.append(
            f"- `{family}`: {known['state_counts']['Tracked']} Tracked, {known['state_counts']['Missing']} Missing, {known['state_counts']['Lost']} Lost; {metrics['identity_switch_events']} switch(es), {metrics['wrong_person_frames']} wrong frame(s), {metrics['identity_safe_continuity_percent']:.3f}% continuity. Wrong indices: {known['wrong_indices'] or 'none'}."
        )
    lines.extend([
        "",
        "During frames 152-160, LightTrack-Mobile suppressed frame 152 for competitor overlap and safely tracked 153-160; NanoTrackV3 suppressed frames 151-152 and safely tracked 153-160. Thus both fixed-template SOTs solve the earlier detector-association failure in this first crossing, but that local success does not generalize.",
        "",
        f"Across the complete courtyard held-out partition, LightTrack-Mobile stayed identity-safe but fell to {payload['families']['lighttrack_mobile']['heldout']['per_video']['courtyard_competitor']['identity_safe_continuity_percent']:.3f}% continuity. NanoTrackV3 later switched to the wrong person for frames {nano_courtyard_runs} and fell to {payload['families']['nanotrack_v3']['heldout']['per_video']['courtyard_competitor']['identity_safe_continuity_percent']:.3f}% continuity. Its very high SOT score and permissive frozen-template/detector checks did not establish original identity after the later overlap/separation.",
        "",
        "The contact sheet and per-frame traces retain the SOT raw box even when validation suppresses output, making it possible to distinguish tracker takeover from a validation veto. Frames 152-160 are explicitly included; the full courtyard traces also cover the later failure.",
        "",
        "## Other required sequences",
        "",
        f"- `single_person`: LightTrack-Mobile remained safety-clean but reached only {payload['families']['lighttrack_mobile']['heldout']['per_video']['single_person']['identity_safe_continuity_percent']:.3f}% continuity. NanoTrackV3 reached {payload['families']['nanotrack_v3']['heldout']['per_video']['single_person']['identity_safe_continuity_percent']:.3f}% but produced a wrong box at {payload['families']['nanotrack_v3']['diagnostics']['wrong_person_indices']['single_person']} and false tracking while invisible at {payload['families']['nanotrack_v3']['diagnostics']['false_tracking_while_invisible_indices']['single_person']}; it therefore did not handle leave/re-entry safely.",
        f"- `multi_person`: continuity was {payload['families']['lighttrack_mobile']['heldout']['per_video']['multi_person']['identity_safe_continuity_percent']:.3f}% for LightTrack-Mobile and {payload['families']['nanotrack_v3']['heldout']['per_video']['multi_person']['identity_safe_continuity_percent']:.3f}% for NanoTrackV3. Both were identity-safe there, but fail-closed overlap suppression caused material gaps.",
        "",
        "## Model cost, licensing, and Android path",
        "",
        "| Candidate | Model | Compute | Desktop SOT step | Code | Weights | Android path |",
        "|---|---:|---:|---:|---|---|---|",
    ])
    assessments = payload["deployment"]
    for family in TRACKER_FAMILIES:
        assessment = assessments[family]
        timing = payload["families"][family]["timing"]["held_out"]
        lines.append(
            f"| {family} | {assessment['model_size_bytes'] / 1_000_000:.3f} MB | {assessment['flops']} | {timing['mean_step_ms']:.3f} ms mean / {timing['p95_step_ms']:.3f} ms p95 | {assessment['code_license']} | **{assessment['pretrained_weight_license']}** | {assessment['runtime_required']} |"
        )
    lines.extend([
        "",
        f"- LightTrack-Mobile deployment: {assessments['lighttrack_mobile']['android_path']}. {assessments['lighttrack_mobile']['teclast_p50ai']}.",
        f"- NanoTrackV3 deployment: {assessments['nanotrack_v3']['android_path']}. {assessments['nanotrack_v3']['teclast_p50ai']}.",
        "- LiteRT/TFLite is not a ready path for either supplied artifact: LightTrack first needs a verified static export, while NanoTrackV3 would need ONNX-to-TensorFlow conversion and operator/numerical validation. ONNX Runtime Mobile can consume NanoTrackV3 most directly; ncnn is likely the smallest mobile-native path after converting and validating V3.",
        "",
        f"The desktop timings exclude the already-cached YOLO detector, whose retained benchmark cost was {payload['detector_timing_reference']['mean_ms']:.3f} ms mean / {payload['detector_timing_reference']['p95_ms']:.3f} ms p95. Costs were measured independently, not summed as a mobile estimate, and do not predict Android speed. The P50Ai's Allwinner A733 CPU makes NanoTrackV3 the lighter computational fit, but neither pretrained artifact is deployable in a proprietary app until the weight license is clarified in writing.",
        "",
        "Sources: [official LightTrack repository](https://github.com/researchmm/LightTrack), [LightTrack CVPR 2021 paper](https://openaccess.thecvf.com/content/CVPR2021/html/Yan_LightTrack_Finding_Lightweight_Neural_Networks_for_Object_Tracking_via_One-Shot_CVPR_2021_paper.html), [official NanoTrack code/training repository](https://github.com/HonglinChu/SiamTrackers/tree/master/NanoTrack), [NanoTrack Android/ncnn repository](https://github.com/HonglinChu/NanoTrack), [ncnn](https://github.com/Tencent/ncnn), and [Allwinner A733 specification](https://www.allwinnertech.com/uploads/download_source/20260303162657a4.pdf).",
        "",
        "## Recommendation",
        "",
        payload["recommendation"],
        "",
        "Complete machine-readable configs, metrics, hashes, timing, and sequence summaries are in `result.json`; per-video metrics are in `heldout_metrics.csv`; exact courtyard decisions are in the focused and full trace CSV files for each tracker.",
        "",
    ])
    path.write_text("\n".join(lines), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parent
    parser.add_argument(
        "--benchmark-dir", type=Path, default=root / "work" / "benchmark_expanded"
    )
    parser.add_argument(
        "--output-dir", type=Path, default=root / "outputs" / "sot_20260825"
    )
    parser.add_argument(
        "--third-party-root", type=Path, default=root / ".cache" / "third_party"
    )
    parser.add_argument(
        "--yolo-model", type=Path, default=root / ".cache" / "yolo11n.pt"
    )
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--max-experiments", type=int, default=12)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not 2 <= args.max_experiments <= MAX_EXPERIMENTS:
        raise ValueError(f"max experiments must be within 2..{MAX_EXPERIMENTS}")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    manifest, videos = lab.load_benchmark(args.benchmark_dir)
    detections, detector_timing = lab.detection_cache(
        videos,
        "yolo11n",
        args.yolo_model,
        args.benchmark_dir / "cache" / "yolo11n.json",
        args.threads,
        lab.sha256(args.benchmark_dir / "manifest.json"),
    )
    baseline = baseline_metrics(videos, detections)
    family_results: dict[str, Any] = {}
    experiment_history: list[dict[str, Any]] = []
    frozen_configs: dict[str, SafetyConfig] = {}
    tuning_trajectories: dict[str, dict[tuple[str, int, int], SectionTrajectory]] = {}
    timing: dict[str, dict[str, Any]] = {}

    for family in TRACKER_FAMILIES:
        print(f"precomputing {family} tuning trajectories", flush=True)
        trajectories, timing_item = precompute_trajectories(
            family, videos, "tuning", args.third_party_root, args.threads
        )
        tuning_trajectories[family] = trajectories
        timing[family] = {"tuning": timing_item}
        candidates = initial_configs(family)
        candidate_results: list[tuple[SafetyConfig, dict[str, Any]]] = []
        for config in candidates:
            metrics, _, _ = evaluate_config(
                config, videos, detections, trajectories, "tuning"
            )
            candidate_results.append((config, metrics))
            experiment_history.append(
                {"iteration": 1, "eligible": safety_eligible(metrics), **metrics}
            )
            item = metrics["aggregate"]
            print(
                f"{config.name}: safety={safety_errors(metrics)} "
                f"continuity={item['identity_safe_continuity_percent']:.3f}%",
                flush=True,
            )
        frozen_configs[family] = min(
            candidate_results, key=lambda item: rank_metrics(item[1])
        )[0]

    initial_safe = {
        family: any(
            item["eligible"]
            for item in experiment_history
            if item["config"]["tracker"] == family
        )
        for family in TRACKER_FAMILIES
    }
    refinement_passes = 0
    if any(initial_safe.values()) and len(experiment_history) + 4 <= args.max_experiments:
        refinement_passes = 1
        for family in TRACKER_FAMILIES:
            existing = [
                item for item in experiment_history
                if item["config"]["tracker"] == family
            ]
            for config in refinement_configs(frozen_configs[family]):
                metrics, _, _ = evaluate_config(
                    config,
                    videos,
                    detections,
                    tuning_trajectories[family],
                    "tuning",
                )
                entry = {"iteration": 2, "eligible": safety_eligible(metrics), **metrics}
                experiment_history.append(entry)
                existing.append(entry)
                item = metrics["aggregate"]
                print(
                    f"{config.name}: safety={safety_errors(metrics)} "
                    f"continuity={item['identity_safe_continuity_percent']:.3f}%",
                    flush=True,
                )
            best_entry = min(existing, key=lambda item: rank_metrics(item))
            frozen_configs[family] = SafetyConfig(**best_entry["config"])
    stop_reason = (
        "both architectures failed tuning identity safety; stopped before refinement"
        if not any(initial_safe.values())
        else "one bounded refinement pass completed"
        if refinement_passes
        else "experiment budget ended after initial pass"
    )
    if len(experiment_history) > args.max_experiments:
        raise AssertionError("experiment budget exceeded")
    for family in TRACKER_FAMILIES:
        count = sum(
            item["config"]["tracker"] == family for item in experiment_history
        )
        if count > MAX_CONFIGS_PER_TRACKER:
            raise AssertionError("per-tracker configuration budget exceeded")

    courtyard_video = next(video for video in videos if video.id == "courtyard_competitor")
    courtyard_results: dict[str, Sequence[lab.Result]] = {}
    for family in TRACKER_FAMILIES:
        print(f"opening held-out partition for frozen {family}", flush=True)
        trajectories, heldout_timing = precompute_trajectories(
            family, videos, "held_out", args.third_party_root, args.threads
        )
        timing[family]["held_out"] = heldout_timing
        metrics, results, trace = evaluate_config(
            frozen_configs[family],
            videos,
            detections,
            trajectories,
            "held_out",
            collect_trace=True,
        )
        aggregate = metrics["aggregate"]
        accepted = (
            safety_eligible(metrics)
            and aggregate["identity_safe_continuity_percent"]
            > BASELINE_CONTINUITY_PERCENT
        )
        ready = accepted and aggregate["identity_safe_continuity_percent"] >= TARGET_CONTINUITY_PERCENT
        known = known_sequence_summary(
            courtyard_video, results["courtyard_competitor"]
        )
        tuning_entries = [
            item
            for item in experiment_history
            if item["config"]["tracker"] == family
        ]
        frozen_tuning_entry = next(
            item
            for item in tuning_entries
            if item["config"]["name"] == frozen_configs[family].name
        )
        family_results[family] = {
            "frozen_config": asdict(frozen_configs[family]),
            "tuning_summary": {
                "configs": len(tuning_entries),
                "safety_eligible_configs": sum(
                    bool(item["eligible"]) for item in tuning_entries
                ),
                "minimum_continuity_percent": min(
                    item["aggregate"]["identity_safe_continuity_percent"]
                    for item in tuning_entries
                ),
                "maximum_continuity_percent": max(
                    item["aggregate"]["identity_safe_continuity_percent"]
                    for item in tuning_entries
                ),
                "frozen_was_safety_eligible": bool(
                    frozen_tuning_entry["eligible"]
                ),
            },
            "heldout": metrics,
            "accepted": accepted,
            "ready_for_android_accuracy": ready,
            "courtyard_124_160": known,
            "diagnostics": failure_diagnostics(videos, results),
            "timing": timing[family],
        }
        courtyard_results[family] = results["courtyard_competitor"]
        courtyard_trace = [
            row for row in trace
            if row["video"] == "courtyard_competitor"
            and 124 <= row["canonical_index"] <= 160
        ]
        write_trace_csv(
            args.output_dir / f"{family}_courtyard_124_160.csv",
            courtyard_trace,
        )
        write_trace_csv(
            args.output_dir / f"{family}_courtyard_full.csv",
            [row for row in trace if row["video"] == "courtyard_competitor"],
        )

    accuracy_ready = [
        family for family in TRACKER_FAMILIES
        if family_results[family]["ready_for_android_accuracy"]
    ]
    licensing_blocks = all(
        not item["proprietary_deployable"]
        for key, item in deployment_assessment().items()
        if key in TRACKER_FAMILIES
    )
    if accuracy_ready:
        recommendation = (
            f"{', '.join(accuracy_ready)} meets the offline accuracy bar and deserves an Android performance prototype only after pretrained-weight licensing is clarified. No production integration is authorized by this experiment."
        )
    else:
        recommendation = (
            "Neither architecture deserves an Android prototype: neither cleared the complete identity-safety and continuity acceptance gate. The missing capability is calibrated long-term target-presence/identity uncertainty that remains reliable after full occlusion or target exit; a fixed-template local SOT response plus person-detector agreement cannot prove that the object after separation is the originally selected person."
        )
    if licensing_blocks:
        recommendation += " Independently, both supplied pretrained artifacts are blocked for a proprietary app by unclear weight licensing."

    payload = {
        "schema_version": 1,
        "limits": {
            "maximum_tracker_architectures": MAX_TRACKERS,
            "maximum_configs_per_tracker": MAX_CONFIGS_PER_TRACKER,
            "maximum_experiments": MAX_EXPERIMENTS,
            "requested_max_experiments": args.max_experiments,
            "actual_experiments": len(experiment_history),
            "maximum_refinement_passes": MAX_REFINEMENT_PASSES,
            "refinement_passes": refinement_passes,
            "stop_reason": stop_reason,
        },
        "benchmark": {
            "manifest_sha256": lab.sha256(args.benchmark_dir / "manifest.json"),
            "annotation_sha256": {
                item["id"]: item["annotations_sha256"]
                for item in manifest["videos"]
            },
            "ground_truth_changed": False,
        },
        "assets": {
            "lighttrack": {
                "repository": LIGHTTRACK_REPOSITORY,
                "commit": LIGHTTRACK_COMMIT,
                "weight_sha256": LIGHTTRACK_WEIGHT_SHA256,
            },
            "nanotrack": {
                "repository": NANOTRACK_REPOSITORY,
                "commit": NANOTRACK_COMMIT,
                "backbone_sha256": NANOTRACK_BACKBONE_SHA256,
                "head_sha256": NANOTRACK_HEAD_SHA256,
            },
        },
        "detector_timing_reference": detector_timing,
        "baseline_heldout": baseline,
        "families": family_results,
        "deployment": deployment_assessment(),
        "recommendation": recommendation,
    }
    (args.output_dir / "experiment_history.jsonl").write_text(
        "".join(json.dumps(item) + "\n" for item in experiment_history),
        encoding="utf-8",
    )
    (args.output_dir / "result.json").write_text(
        json.dumps(payload, indent=2) + "\n", encoding="utf-8"
    )
    write_metrics_csv(
        args.output_dir / "heldout_metrics.csv",
        baseline,
        {family: family_results[family]["heldout"] for family in TRACKER_FAMILIES},
    )
    render_courtyard_contact_sheet(
        args.output_dir / "courtyard_competitor_124_160.jpg",
        courtyard_video,
        courtyard_results,
    )
    write_report(args.output_dir / "report.md", payload)
    print(json.dumps({
        "experiments": len(experiment_history),
        "stop_reason": stop_reason,
        "heldout": {
            family: compact_metrics(family_results[family]["heldout"]["aggregate"])
            for family in TRACKER_FAMILIES
        },
    }, indent=2), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
