#!/usr/bin/env python3
"""Evaluate detector and fail-closed single-target tracking on a vision ZIP."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import platform
import statistics
import sys
import tempfile
import time
import zipfile
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Iterable, Sequence

LAB_DIR = Path(__file__).resolve().parent
CACHE_DIR = LAB_DIR / ".cache"
os.environ.setdefault("YOLO_CONFIG_DIR", str(CACHE_DIR / "ultralytics"))

import cv2  # noqa: E402
import mediapipe as mp  # noqa: E402
import numpy as np  # noqa: E402
import torch  # noqa: E402
from mediapipe.tasks import python as mp_python  # noqa: E402
from mediapipe.tasks.python import vision as mp_vision  # noqa: E402
from ultralytics import YOLO  # noqa: E402

YOLO11N_SHA256 = "0ebbc80d4a7680d14987a577cd21342b65ecfd94632bd9a8da63ae6417644ee1"
EFFICIENTDET_SHA256 = "6fd32c84ab1eb0f7e7f3a7a20a20d7df1530daa8378728f7c79571096286bd52"
MISSING_TTL_NS = 800_000_000
PERSON_CATEGORY = 0


@dataclass(frozen=True)
class Box:
    x1: float
    y1: float
    x2: float
    y2: float

    @property
    def width(self) -> float:
        return max(0.0, self.x2 - self.x1)

    @property
    def height(self) -> float:
        return max(0.0, self.y2 - self.y1)

    @property
    def area(self) -> float:
        return self.width * self.height

    @property
    def center(self) -> tuple[float, float]:
        return ((self.x1 + self.x2) / 2.0, (self.y1 + self.y2) / 2.0)

    def clipped(self, width: int, height: int) -> "Box":
        return Box(
            min(max(self.x1, 0.0), width - 1.0),
            min(max(self.y1, 0.0), height - 1.0),
            min(max(self.x2, 1.0), float(width)),
            min(max(self.y2, 1.0), float(height)),
        )

    def xywh(self) -> tuple[int, int, int, int]:
        return (round(self.x1), round(self.y1), round(self.width), round(self.height))

    def normalized(self, width: int, height: int) -> list[float]:
        return [self.x1 / width, self.y1 / height, self.x2 / width, self.y2 / height]

    @staticmethod
    def from_normalized(values: Sequence[float], width: int, height: int) -> "Box":
        return Box(values[0] * width, values[1] * height, values[2] * width, values[3] * height)


@dataclass(frozen=True)
class Detection:
    box: Box
    confidence: float


@dataclass
class FrameInput:
    capture_index: int
    frame_sequence: int
    timestamp_ns: int
    file: str
    image: np.ndarray
    trace: dict[str, Any]


@dataclass
class FrameResult:
    capture_index: int
    frame_sequence: int
    timestamp_ns: int
    state: str
    source: str
    box: Box | None
    confidence: float | None
    detections: list[Detection] = field(default_factory=list)
    detector_inference_ms: float | None = None
    detector_wall_ms: float | None = None
    tracker_ms: float | None = None
    tracker_quality: float | None = None
    note: str = ""


@dataclass
class TrackerUpdate:
    accepted: bool
    box: Box | None
    quality: float
    points: int
    inlier_ratio: float
    median_fb_error: float
    reason: str


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def iou(a: Box, b: Box) -> float:
    intersection = max(0.0, min(a.x2, b.x2) - max(a.x1, b.x1)) * max(
        0.0, min(a.y2, b.y2) - max(a.y1, b.y1)
    )
    union = a.area + b.area - intersection
    return intersection / union if union > 0.0 else 0.0


def center_distance(a: Box, b: Box, width: int, height: int) -> float:
    ax, ay = a.center
    bx, by = b.center
    return math.hypot((ax - bx) / width, (ay - by) / height)


def crop_histogram(image: np.ndarray, box: Box) -> np.ndarray | None:
    clipped = box.clipped(image.shape[1], image.shape[0])
    x, y, w, h = clipped.xywh()
    if w < 8 or h < 8:
        return None
    # The inner crop reduces background sensitivity around detector boxes.
    margin_x, margin_y = round(w * 0.12), round(h * 0.08)
    crop = image[y + margin_y : y + h - margin_y, x + margin_x : x + w - margin_x]
    if crop.size == 0:
        return None
    hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
    hist = cv2.calcHist([hsv], [0, 1], None, [24, 16], [0, 180, 0, 256])
    cv2.normalize(hist, hist, 0.0, 1.0, cv2.NORM_MINMAX)
    return hist


def histogram_similarity(a: np.ndarray | None, b: np.ndarray | None) -> float:
    if a is None or b is None:
        return 0.0
    return float((cv2.compareHist(a, b, cv2.HISTCMP_CORREL) + 1.0) / 2.0)


def blend_histogram(old: np.ndarray | None, new: np.ndarray | None, alpha: float = 0.08) -> np.ndarray | None:
    if new is None:
        return old
    if old is None:
        return new.copy()
    blended = (1.0 - alpha) * old + alpha * new
    cv2.normalize(blended, blended, 0.0, 1.0, cv2.NORM_MINMAX)
    return blended


def choose_identity_safe_detection(
    detections: Sequence[Detection],
    predicted_box: Box,
    image: np.ndarray,
    target_histogram: np.ndarray | None,
) -> tuple[Detection | None, str]:
    height, width = image.shape[:2]
    eligible: list[tuple[float, Detection]] = []
    for detection in detections:
        overlap = iou(predicted_box, detection.box)
        distance = center_distance(predicted_box, detection.box, width, height)
        area_ratio = detection.box.area / max(predicted_box.area, 1.0)
        appearance = histogram_similarity(target_histogram, crop_histogram(image, detection.box))
        geometry_ok = overlap >= 0.18 or distance <= 0.12
        if geometry_ok and 0.40 <= area_ratio <= 2.50 and appearance >= 0.46:
            score = 0.50 * overlap + 0.22 * (1.0 - min(distance / 0.25, 1.0)) + 0.20 * appearance + 0.08 * detection.confidence
            eligible.append((score, detection))
    if not eligible:
        return None, "no identity-safe detector correction"
    eligible.sort(key=lambda item: item[0], reverse=True)
    if len(eligible) > 1 and eligible[0][0] - eligible[1][0] < 0.10:
        return None, "ambiguous detector correction"
    return eligible[0][1], "identity-safe detector correction"


class FailClosedOpticalFlow:
    """Sparse LK box propagation with explicit uncertainty rejection."""

    def __init__(self) -> None:
        self.previous_gray: np.ndarray | None = None
        self.points: np.ndarray | None = None
        self.box: Box | None = None

    def reset(self, image: np.ndarray, box: Box) -> bool:
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        mask = np.zeros_like(gray)
        clipped = box.clipped(image.shape[1], image.shape[0])
        x, y, w, h = clipped.xywh()
        inset_x, inset_y = max(2, round(w * 0.05)), max(2, round(h * 0.04))
        cv2.rectangle(mask, (x + inset_x, y + inset_y), (x + w - inset_x, y + h - inset_y), 255, -1)
        points = cv2.goodFeaturesToTrack(
            gray,
            mask=mask,
            maxCorners=120,
            qualityLevel=0.008,
            minDistance=4,
            blockSize=5,
        )
        self.previous_gray = gray
        self.points = points
        self.box = clipped
        return points is not None and len(points) >= 10

    def update(self, image: np.ndarray) -> TrackerUpdate:
        started = time.perf_counter_ns()
        if self.previous_gray is None or self.points is None or self.box is None or len(self.points) < 8:
            return TrackerUpdate(False, None, 0.0, 0, 0.0, math.inf, "tracker not initialized")
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        forward, status_f, errors = cv2.calcOpticalFlowPyrLK(
            self.previous_gray,
            gray,
            self.points,
            None,
            winSize=(21, 21),
            maxLevel=3,
            criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 30, 0.01),
        )
        if forward is None or status_f is None:
            return TrackerUpdate(False, None, 0.0, 0, 0.0, math.inf, "forward flow failed")
        backward, status_b, _ = cv2.calcOpticalFlowPyrLK(
            gray,
            self.previous_gray,
            forward,
            None,
            winSize=(21, 21),
            maxLevel=3,
            criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 30, 0.01),
        )
        if backward is None or status_b is None:
            return TrackerUpdate(False, None, 0.0, 0, 0.0, math.inf, "backward flow failed")
        old = self.points.reshape(-1, 2)
        new = forward.reshape(-1, 2)
        back = backward.reshape(-1, 2)
        fb = np.linalg.norm(old - back, axis=1)
        lk_error = errors.reshape(-1) if errors is not None else np.zeros(len(old))
        valid = (status_f.reshape(-1) == 1) & (status_b.reshape(-1) == 1) & (fb <= 1.5) & (lk_error <= 25.0)
        old_valid, new_valid, fb_valid = old[valid], new[valid], fb[valid]
        if len(old_valid) < 8:
            return TrackerUpdate(False, None, 0.0, len(old_valid), 0.0, float(np.median(fb_valid)) if len(fb_valid) else math.inf, "too few consistent features")
        matrix, inliers = cv2.estimateAffinePartial2D(
            old_valid,
            new_valid,
            method=cv2.RANSAC,
            ransacReprojThreshold=2.5,
            maxIters=1000,
            confidence=0.99,
            refineIters=10,
        )
        if matrix is None or inliers is None:
            return TrackerUpdate(False, None, 0.0, len(old_valid), 0.0, float(np.median(fb_valid)), "robust transform failed")
        inlier_mask = inliers.reshape(-1) == 1
        inlier_ratio = float(np.mean(inlier_mask))
        inlier_count = int(np.sum(inlier_mask))
        median_fb = float(np.median(fb_valid[inlier_mask])) if inlier_count else math.inf
        scale = math.sqrt(float(matrix[0, 0] ** 2 + matrix[0, 1] ** 2))
        translation = math.hypot(float(matrix[0, 2]), float(matrix[1, 2]))
        diagonal = math.hypot(image.shape[1], image.shape[0])
        if inlier_count < 8 or inlier_ratio < 0.55 or median_fb > 0.85:
            return TrackerUpdate(False, None, 0.0, inlier_count, inlier_ratio, median_fb, "weak flow consensus")
        if not 0.88 <= scale <= 1.14 or translation > 0.16 * diagonal:
            return TrackerUpdate(False, None, 0.0, inlier_count, inlier_ratio, median_fb, "implausible transform")
        corners = np.array(
            [[[self.box.x1, self.box.y1], [self.box.x2, self.box.y1], [self.box.x2, self.box.y2], [self.box.x1, self.box.y2]]],
            dtype=np.float32,
        )
        transformed = cv2.transform(corners, matrix)[0]
        candidate = Box(
            float(np.min(transformed[:, 0])),
            float(np.min(transformed[:, 1])),
            float(np.max(transformed[:, 0])),
            float(np.max(transformed[:, 1])),
        ).clipped(image.shape[1], image.shape[0])
        if candidate.area < 0.015 * image.shape[0] * image.shape[1] or candidate.area > 0.75 * image.shape[0] * image.shape[1]:
            return TrackerUpdate(False, None, 0.0, inlier_count, inlier_ratio, median_fb, "implausible box area")
        quality = min(1.0, 0.45 * min(inlier_count / 30.0, 1.0) + 0.40 * inlier_ratio + 0.15 * max(0.0, 1.0 - median_fb / 0.85))
        if quality < 0.58:
            return TrackerUpdate(False, None, quality, inlier_count, inlier_ratio, median_fb, "quality below fail-closed threshold")
        self.previous_gray = gray
        self.points = new_valid[inlier_mask].reshape(-1, 1, 2).astype(np.float32)
        self.box = candidate
        if len(self.points) < 18:
            self.reset(image, candidate)
        elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000.0
        return TrackerUpdate(True, candidate, quality, inlier_count, inlier_ratio, median_fb, f"accepted flow ({elapsed_ms:.2f} ms)")


def validate_archive(archive: zipfile.ZipFile) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    names = set(archive.namelist())
    required = {"manifest.json", "trace.jsonl"}
    if not required.issubset(names):
        raise ValueError(f"session ZIP missing {sorted(required - names)}")
    manifest = json.loads(archive.read("manifest.json"))
    frames = manifest.get("frames", [])
    if not frames:
        raise ValueError("manifest contains no frames")
    missing = [item["file"] for item in frames if item["file"] not in names]
    if missing:
        raise ValueError(f"session ZIP missing frame files: {missing[:3]}")
    traces = [json.loads(line) for line in archive.read("trace.jsonl").decode("utf-8").splitlines() if line.strip()]
    trace_by_key = {(item["frameSequence"], item["sourceTimestampNanos"]): item for item in traces}
    ordered: list[dict[str, Any]] = []
    for frame in frames:
        key = (frame["frameSequence"], frame["sourceTimestampNanos"])
        if key not in trace_by_key:
            raise ValueError(f"no trace for canonical frame {key}")
        ordered.append(trace_by_key[key])
    return manifest, ordered


def load_session(session_zip: Path) -> tuple[list[FrameInput], dict[str, Any]]:
    with zipfile.ZipFile(session_zip) as archive:
        manifest, traces = validate_archive(archive)
        result: list[FrameInput] = []
        for item, trace in zip(manifest["frames"], traces, strict=True):
            encoded = np.frombuffer(archive.read(item["file"]), dtype=np.uint8)
            image = cv2.imdecode(encoded, cv2.IMREAD_COLOR)
            if image is None:
                raise ValueError(f"could not decode {item['file']}")
            if image.shape[1] != item["width"] or image.shape[0] != item["height"]:
                raise ValueError(f"dimension mismatch for {item['file']}")
            result.append(
                FrameInput(
                    capture_index=item["captureIndex"],
                    frame_sequence=item["frameSequence"],
                    timestamp_ns=item["sourceTimestampNanos"],
                    file=item["file"],
                    image=image,
                    trace=trace,
                )
            )
    return result, manifest


def trace_detections(frame: FrameInput, accepted_only: bool = True) -> list[Detection]:
    key = "acceptedDetections" if accepted_only else "candidates"
    height, width = frame.image.shape[:2]
    return [
        Detection(Box.from_normalized(item["box"], width, height), float(item["confidence"]))
        for item in frame.trace["detector"].get(key, [])
    ]


def selected_box(trace_target: dict[str, Any] | None, width: int, height: int) -> Box | None:
    return None if trace_target is None else Box.from_normalized(trace_target["box"], width, height)


def recorded_baseline(frames: Sequence[FrameInput]) -> list[FrameResult]:
    results: list[FrameResult] = []
    for frame in frames:
        height, width = frame.image.shape[:2]
        raw_state = frame.trace["associationState"]
        state = "Tracked" if raw_state == "Matched" else "Missing" if raw_state in {"TemporarilyMissing", "Ambiguous"} else "Lost"
        after = frame.trace.get("selectedTargetAfter")
        box = selected_box(after, width, height) if state == "Tracked" else None
        results.append(
            FrameResult(
                frame.capture_index,
                frame.frame_sequence,
                frame.timestamp_ns,
                state,
                "recorded EfficientDet-Lite2" if state == "Tracked" else "none",
                box,
                float(after["confidence"]) if after is not None and state == "Tracked" else None,
                trace_detections(frame, accepted_only=False),
                float(frame.trace["detector"].get("inferenceMillis", 0.0)),
                None,
                note=raw_state,
            )
        )
    return results


def benchmark_efficientdet(frames: Sequence[FrameInput], model_path: Path) -> dict[str, Any]:
    if sha256(model_path) != EFFICIENTDET_SHA256:
        raise ValueError("baseline model SHA-256 does not match the production EfficientDet-Lite2 asset")
    base_options = mp_python.BaseOptions(model_asset_path=str(model_path))
    options = mp_vision.ObjectDetectorOptions(
        base_options=base_options,
        score_threshold=0.0,
        category_allowlist=["person"],
        max_results=10,
    )
    wall_times: list[float] = []
    person_counts = 0
    with mp_vision.ObjectDetector.create_from_options(options) as detector:
        evaluation_frames = list(frames[:3]) + list(frames)
        for index, frame in enumerate(evaluation_frames):
            rgb = cv2.cvtColor(frame.image, cv2.COLOR_BGR2RGB)
            mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
            started = time.perf_counter_ns()
            output = detector.detect(mp_image)
            elapsed = (time.perf_counter_ns() - started) / 1_000_000.0
            if index < 3:
                continue
            wall_times.append(elapsed)
            if any(category.score >= 0.55 for detection in output.detections for category in detection.categories if category.category_name == "person"):
                person_counts += 1
    return timing_summary(wall_times) | {"accepted_person_frames": person_counts, "threads": "runtime default"}


def run_yolo(frames: Sequence[FrameInput], model_path: Path, image_size: int, threads: int) -> tuple[list[list[Detection]], dict[str, Any]]:
    if sha256(model_path) != YOLO11N_SHA256:
        raise ValueError("YOLO11n model is absent or has an unexpected SHA-256")
    torch.set_num_threads(threads)
    model = YOLO(str(model_path), task="detect")
    for frame in frames[:3]:
        model.predict(frame.image, imgsz=image_size, conf=0.05, classes=[PERSON_CATEGORY], device="cpu", verbose=False)
    detections_by_frame: list[list[Detection]] = []
    inference_times: list[float] = []
    wall_times: list[float] = []
    for frame in frames:
        started = time.perf_counter_ns()
        prediction = model.predict(
            frame.image,
            imgsz=image_size,
            conf=0.05,
            iou=0.60,
            classes=[PERSON_CATEGORY],
            device="cpu",
            verbose=False,
        )[0]
        wall_ms = (time.perf_counter_ns() - started) / 1_000_000.0
        items: list[Detection] = []
        if prediction.boxes is not None:
            boxes = prediction.boxes.xyxy.cpu().numpy()
            confidences = prediction.boxes.conf.cpu().numpy()
            for values, confidence in zip(boxes, confidences, strict=True):
                items.append(Detection(Box(*(float(value) for value in values)), float(confidence)))
        detections_by_frame.append(items)
        inference_times.append(float(prediction.speed.get("inference", math.nan)))
        wall_times.append(wall_ms)
    return detections_by_frame, {
        "model_inference_ms": timing_summary(inference_times),
        "end_to_end_wall_ms": timing_summary(wall_times),
        "threads": threads,
        "image_size": image_size,
    }


def initial_target(frames: Sequence[FrameInput]) -> tuple[Box, np.ndarray | None]:
    first = frames[0]
    height, width = first.image.shape[:2]
    before = first.trace.get("selectedTargetBefore")
    box = selected_box(before, width, height)
    if box is None:
        accepted = trace_detections(first)
        if not accepted:
            raise ValueError("session has no selected target anchor")
        box = accepted[0].box
    return box, crop_histogram(first.image, box)


def detector_only(
    frames: Sequence[FrameInput],
    detections_by_frame: Sequence[Sequence[Detection]],
    detector_timing: dict[str, Any],
) -> list[FrameResult]:
    target_box, target_hist = initial_target(frames)
    last_seen = frames[0].timestamp_ns
    lost_latched = False
    results: list[FrameResult] = []
    per_frame_inference = detector_timing.pop("per_frame_inference", None)
    per_frame_wall = detector_timing.pop("per_frame_wall", None)
    for index, (frame, detections) in enumerate(zip(frames, detections_by_frame, strict=True)):
        accepted = [item for item in detections if item.confidence >= 0.25]
        chosen, note = choose_identity_safe_detection(accepted, target_box, frame.image, target_hist)
        if lost_latched:
            state, box, source, confidence = "Lost", None, "none", None
            note = "Lost is latched; explicit reselection required"
        elif chosen is not None:
            target_box = chosen.box
            target_hist = blend_histogram(target_hist, crop_histogram(frame.image, chosen.box))
            last_seen = frame.timestamp_ns
            state, box, source, confidence = "Tracked", chosen.box, "detector", chosen.confidence
        elif frame.timestamp_ns - last_seen > MISSING_TTL_NS:
            lost_latched = True
            state, box, source, confidence = "Lost", None, "none", None
        else:
            state, box, source, confidence = "Missing", None, "none", None
        results.append(
            FrameResult(
                frame.capture_index,
                frame.frame_sequence,
                frame.timestamp_ns,
                state,
                source,
                box,
                confidence,
                list(detections),
                per_frame_inference[index] if per_frame_inference else None,
                per_frame_wall[index] if per_frame_wall else None,
                note=note,
            )
        )
    return results


def detector_plus_tracker(
    frames: Sequence[FrameInput],
    detections_by_frame: Sequence[Sequence[Detection]],
    detector_threshold: float,
    detector_interval: int,
    label: str,
) -> list[FrameResult]:
    target_box, target_hist = initial_target(frames)
    tracker = FailClosedOpticalFlow()
    tracker.reset(frames[0].image, target_box)
    last_seen = frames[0].timestamp_ns
    lost_latched = False
    results: list[FrameResult] = []
    for index, (frame, all_detections) in enumerate(zip(frames, detections_by_frame, strict=True)):
        tracker_started = time.perf_counter_ns()
        tracked = TrackerUpdate(True, target_box, 1.0, 0, 1.0, 0.0, "selection anchor") if index == 0 else tracker.update(frame.image)
        tracker_ms = (time.perf_counter_ns() - tracker_started) / 1_000_000.0
        # Normal cadence saves detector work; weak flow triggers an immediate
        # detector check instead of manufacturing an avoidable Missing frame.
        run_detector = index % detector_interval == 0 or not tracked.accepted
        visible_detections = list(all_detections) if run_detector else []
        accepted = [item for item in visible_detections if item.confidence >= detector_threshold]
        predicted = tracked.box if tracked.accepted and tracked.box is not None else target_box
        correction, correction_note = choose_identity_safe_detection(accepted, predicted, frame.image, target_hist) if accepted else (None, "detector not scheduled or below threshold")
        if lost_latched:
            state, box, source, confidence = "Lost", None, "none", None
            note = "Lost is latched; explicit reselection required"
        elif correction is not None:
            target_box = correction.box
            target_hist = blend_histogram(target_hist, crop_histogram(frame.image, correction.box))
            tracker.reset(frame.image, correction.box)
            last_seen = frame.timestamp_ns
            state, box, source, confidence = "Tracked", correction.box, "detector correction", correction.confidence
            note = correction_note
        elif tracked.accepted and tracked.box is not None:
            target_box = tracked.box
            last_seen = frame.timestamp_ns
            state, box, source, confidence = "Tracked", tracked.box, "visual tracker", tracked.quality
            note = tracked.reason
        elif frame.timestamp_ns - last_seen > MISSING_TTL_NS:
            lost_latched = True
            state, box, source, confidence = "Lost", None, "none", None
            note = tracked.reason
        else:
            state, box, source, confidence = "Missing", None, "none", None
            note = tracked.reason
        results.append(
            FrameResult(
                frame.capture_index,
                frame.frame_sequence,
                frame.timestamp_ns,
                state,
                source,
                box,
                confidence,
                visible_detections,
                tracker_ms=tracker_ms,
                tracker_quality=tracked.quality,
                note=f"{label}: {note}",
            )
        )
    return results


def timing_summary(values: Iterable[float]) -> dict[str, float | None]:
    finite = sorted(float(value) for value in values if math.isfinite(float(value)))
    if not finite:
        return {"min": None, "p50": None, "p95": None, "max": None, "mean": None}
    percentile_95 = finite[min(len(finite) - 1, math.ceil(0.95 * len(finite)) - 1)]
    return {
        "min": round(finite[0], 3),
        "p50": round(statistics.median(finite), 3),
        "p95": round(percentile_95, 3),
        "max": round(finite[-1], 3),
        "mean": round(statistics.fmean(finite), 3),
    }


def state_periods(results: Sequence[FrameResult], state: str, median_delta_ns: int) -> list[dict[str, Any]]:
    periods: list[dict[str, Any]] = []
    start = 0
    while start < len(results):
        if results[start].state != state:
            start += 1
            continue
        end = start
        while end + 1 < len(results) and results[end + 1].state == state:
            end += 1
        end_timestamp = results[end + 1].timestamp_ns if end + 1 < len(results) else results[end].timestamp_ns + median_delta_ns
        periods.append(
            {
                "start_capture_index": results[start].capture_index,
                "end_capture_index": results[end].capture_index,
                "start_frame_sequence": results[start].frame_sequence,
                "end_frame_sequence": results[end].frame_sequence,
                "frames": end - start + 1,
                "duration_seconds": round((end_timestamp - results[start].timestamp_ns) / 1_000_000_000.0, 3),
            }
        )
        start = end + 1
    return periods


def longest_run(results: Sequence[FrameResult], state: str) -> int:
    best = current = 0
    for result in results:
        current = current + 1 if result.state == state else 0
        best = max(best, current)
    return best


def drift_periods(results: Sequence[FrameResult], reference: Sequence[FrameResult], threshold: float = 0.25) -> list[dict[str, Any]]:
    flagged: list[FrameResult] = []
    for result, truth in zip(results, reference, strict=True):
        if result.box is not None and truth.box is not None and iou(result.box, truth.box) < threshold:
            flagged.append(result)
    sequences: list[dict[str, Any]] = []
    for item in flagged:
        if not sequences or item.capture_index != sequences[-1]["end_capture_index"] + 1:
            sequences.append({"start_capture_index": item.capture_index, "end_capture_index": item.capture_index, "frames": 1})
        else:
            sequences[-1]["end_capture_index"] = item.capture_index
            sequences[-1]["frames"] += 1
    return [item for item in sequences if item["frames"] >= 2]


def summarize_approach(
    results: Sequence[FrameResult],
    reference: Sequence[FrameResult],
    median_delta_ns: int,
) -> dict[str, Any]:
    tracked = sum(item.state == "Tracked" for item in results)
    agreements = [iou(item.box, truth.box) for item, truth in zip(results, reference, strict=True) if item.box is not None and truth.box is not None]
    tracker_times = [item.tracker_ms for item in results if item.tracker_ms is not None]
    return {
        "frames": len(results),
        "tracked_frames": tracked,
        "continuity_percent": round(100.0 * tracked / len(results), 2),
        "longest_continuous_tracked_run_frames": longest_run(results, "Tracked"),
        "missing_periods": state_periods(results, "Missing", median_delta_ns),
        "lost_periods": state_periods(results, "Lost", median_delta_ns),
        "detector_sourced_frames": sum(item.source in {"detector", "detector correction", "recorded EfficientDet-Lite2"} for item in results),
        "tracker_sourced_frames": sum(item.source == "visual tracker" for item in results),
        "reference_iou": timing_summary(agreements),
        "obvious_drift_periods_iou_below_0_25": drift_periods(results, reference),
        "tracker_wall_ms": timing_summary(tracker_times),
    }


def color_for_state(state: str) -> tuple[int, int, int]:
    return {"Tracked": (70, 220, 70), "Missing": (0, 170, 255), "Lost": (40, 40, 230)}[state]


def draw_box(image: np.ndarray, box: Box, color: tuple[int, int, int], label: str, thickness: int = 2) -> None:
    x, y, w, h = box.clipped(image.shape[1], image.shape[0]).xywh()
    cv2.rectangle(image, (x, y), (x + w, y + h), color, thickness, cv2.LINE_AA)
    cv2.putText(image, label, (x + 2, max(15, y - 5)), cv2.FONT_HERSHEY_SIMPLEX, 0.43, color, 1, cv2.LINE_AA)


def render_video(path: Path, title: str, frames: Sequence[FrameInput], results: Sequence[FrameResult], fps: float) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    output_size = (960, 800)
    writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), fps, output_size)
    if not writer.isOpened():
        raise RuntimeError(f"could not open video writer for {path}")
    first_timestamp = frames[0].timestamp_ns
    for frame, result in zip(frames, results, strict=True):
        canvas = cv2.resize(frame.image, (960, 720), interpolation=cv2.INTER_CUBIC)
        scale_x, scale_y = 960.0 / frame.image.shape[1], 720.0 / frame.image.shape[0]
        for detection in result.detections:
            scaled = Box(detection.box.x1 * scale_x, detection.box.y1 * scale_y, detection.box.x2 * scale_x, detection.box.y2 * scale_y)
            draw_box(canvas, scaled, (0, 220, 255), f"person {detection.confidence:.2f}", 1)
        if result.box is not None:
            scaled = Box(result.box.x1 * scale_x, result.box.y1 * scale_y, result.box.x2 * scale_x, result.box.y2 * scale_y)
            draw_box(canvas, scaled, color_for_state(result.state), f"TARGET {result.source}", 3)
        footer = np.full((80, 960, 3), 22, dtype=np.uint8)
        output = np.vstack([canvas, footer])
        elapsed = (frame.timestamp_ns - first_timestamp) / 1_000_000_000.0
        state_color = color_for_state(result.state)
        cv2.putText(output, title, (14, 745), cv2.FONT_HERSHEY_SIMPLEX, 0.64, (245, 245, 245), 2, cv2.LINE_AA)
        cv2.putText(output, f"frame {frame.capture_index:03d} / seq {frame.frame_sequence} / t={elapsed:5.2f}s", (14, 773), cv2.FONT_HERSHEY_SIMPLEX, 0.48, (210, 210, 210), 1, cv2.LINE_AA)
        cv2.putText(output, f"{result.state} | {result.source}", (570, 745), cv2.FONT_HERSHEY_SIMPLEX, 0.62, state_color, 2, cv2.LINE_AA)
        note = result.note[:82]
        cv2.putText(output, note, (390, 774), cv2.FONT_HERSHEY_SIMPLEX, 0.38, (185, 185, 185), 1, cv2.LINE_AA)
        writer.write(output)
    writer.release()


def serialize_frame(result: FrameResult, width: int, height: int) -> dict[str, Any]:
    data = asdict(result)
    data["box"] = result.box.normalized(width, height) if result.box else None
    data["detections"] = [
        {"box": detection.box.normalized(width, height), "confidence": detection.confidence}
        for detection in result.detections
    ]
    return data


def write_csv(path: Path, approaches: dict[str, Sequence[FrameResult]], width: int, height: int) -> None:
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.writer(stream)
        writer.writerow(["approach", "capture_index", "frame_sequence", "timestamp_ns", "state", "source", "confidence", "box_norm", "tracker_quality", "note"])
        for approach, results in approaches.items():
            for result in results:
                writer.writerow(
                    [
                        approach,
                        result.capture_index,
                        result.frame_sequence,
                        result.timestamp_ns,
                        result.state,
                        result.source,
                        "" if result.confidence is None else f"{result.confidence:.6f}",
                        "" if result.box is None else json.dumps(result.box.normalized(width, height), separators=(",", ":")),
                        "" if result.tracker_quality is None else f"{result.tracker_quality:.6f}",
                        result.note,
                    ]
                )


def period_text(periods: Sequence[dict[str, Any]]) -> str:
    if not periods:
        return "0"
    total = sum(item["duration_seconds"] for item in periods)
    details = ", ".join(f"#{item['start_capture_index']}-#{item['end_capture_index']} ({item['duration_seconds']:.3f}s)" for item in periods)
    return f"{len(periods)} / {total:.3f}s total: {details}"


def write_report(path: Path, metadata: dict[str, Any], metrics: dict[str, Any]) -> None:
    names = {
        "baseline": "Recorded EfficientDet-Lite2 baseline",
        "yolo11n_detector": "YOLO11n detector-only (320)",
        "efficientdet_lk": "EfficientDet-Lite2 + fail-closed LK",
        "yolo11n_lk": "YOLO11n cadence + uncertainty fallback + LK",
    }
    lines = [
        "# Vision lab result",
        "",
        f"Canonical ZIP SHA-256: `{metadata['session_sha256']}`",
        "",
        f"Frames: {metadata['frame_count']} at 320x240; capture span: {metadata['capture_span_seconds']:.3f}s; one output frame per canonical JPEG.",
        "",
        "The visible selected person remains in-frame for all canonical frames. The screen recording was not an evaluation input. One source frame was dropped during capture, so results are exact for the 85 canonical frames but cannot score the absent image.",
        "",
        "## Continuity",
        "",
        "| Approach | Tracked | Continuity | Missing | Lost | Detector frames | Tracker frames | Obvious drift |",
        "|---|---:|---:|---|---|---:|---:|---|",
    ]
    for key in names:
        item = metrics["approaches"][key]
        drift = item["obvious_drift_periods_iou_below_0_25"]
        lines.append(
            f"| {names[key]} | {item['tracked_frames']}/{item['frames']} | {item['continuity_percent']:.2f}% | {period_text(item['missing_periods'])} | {period_text(item['lost_periods'])} | {item['detector_sourced_frames']} | {item['tracker_sourced_frames']} | {'none' if not drift else str(drift)} |"
        )
    lines.extend(
        [
            "",
            "`Obvious drift` is an automated review flag: two or more consecutive tracked boxes with IoU < 0.25 versus the identity-gated YOLO11n detector-only box. It is not independently labeled ground truth.",
            "Manual contact-sheet review found no obvious target drift in the three successful approaches. This single-person clip cannot validate competitor/identity-switch behavior; that remains a required adversarial test.",
            "",
            "## Desktop cost",
            "",
            f"- Exact EfficientDet-Lite2 TFLite asset through MediaPipe Tasks, wall time: `{json.dumps(metrics['desktop_cost']['efficientdet_lite2_wall_ms'])}` ms.",
            f"- YOLO11n PyTorch CPU, model inference: `{json.dumps(metrics['desktop_cost']['yolo11n']['model_inference_ms'])}` ms; end-to-end wall: `{json.dumps(metrics['desktop_cost']['yolo11n']['end_to_end_wall_ms'])}` ms.",
            f"- Sparse LK tracker wall time in the EfficientDet hybrid: `{json.dumps(metrics['approaches']['efficientdet_lk']['tracker_wall_ms'])}` ms.",
            f"- Sparse LK tracker wall time in the YOLO hybrid: `{json.dumps(metrics['approaches']['yolo11n_lk']['tracker_wall_ms'])}` ms. YOLO detector cost is normally amortized over a two-frame cadence, with an immediate detector fallback whenever flow is rejected.",
            "",
            "These are approximate CPU measurements on this desktop, not projected Teclast/Android latency. The recorded Android EfficientDet inference distribution is retained in `metrics.json` separately.",
            "",
            "## Ranked recommendation",
            "",
            "1. **YOLO11n + fail-closed visual bridge** — best practical pipeline to prototype on Android. Use detector correction on a cadence, LK between detections, hard appearance/geometry/competitor gates, and latch Lost until explicit reselection.",
            "2. **YOLO11n detector-only** — strongest simple replacement and a useful control, but frame-by-frame detection alone does not satisfy the tracking requirement under future blur/occlusion/dropouts.",
            "3. **EfficientDet-Lite2 + fail-closed visual bridge** — lowest-risk architecture experiment because it isolates the value of temporal tracking, but retains the weaker detector as its correction source.",
            "4. **Current EfficientDet-Lite2 baseline** — fails this clip by design once detector confidence drops and Lost latches.",
            "",
            "No production model or Android tracking code was changed. Before any production decision: export/quantize YOLO11n to LiteRT, benchmark on the target Teclast, add multi-person/occlusion/adversarial clips, and verify that competitor ambiguity always fails closed. Ultralytics licensing also needs product review before shipping weights or derived code.",
            "",
            "## Model and deployment references",
            "",
            "- [YOLO11 model documentation](https://docs.ultralytics.com/models/yolo11/) describes YOLO11 as the mature production line and supports export mode.",
            "- [Ultralytics export documentation](https://docs.ultralytics.com/modes/export/) lists LiteRT/TFLite export and quantization options.",
            "- [OpenCV Lucas-Kanade documentation](https://docs.opencv.org/4.x/d4/dee/tutorial_optical_flow.html) covers the C++/Python/Java primitive used by the visual bridge.",
            "- [Ultralytics licensing](https://www.ultralytics.com/license) states that its code and trained models default to AGPL-3.0; proprietary embedding requires an appropriate commercial license. Treat that as a hard shipping gate, not a footnote.",
        ]
    )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session-zip", required=True, type=Path)
    parser.add_argument("--baseline-model", required=True, type=Path)
    parser.add_argument("--yolo-model", type=Path, default=CACHE_DIR / "yolo11n.pt")
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--image-size", type=int, default=320)
    parser.add_argument("--threads", type=int, default=4)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    (CACHE_DIR / "ultralytics").mkdir(parents=True, exist_ok=True)
    for path, label in ((args.session_zip, "session ZIP"), (args.baseline_model, "baseline model"), (args.yolo_model, "YOLO model")):
        if not path.is_file():
            raise FileNotFoundError(f"{label} not found: {path}")
    frames, manifest = load_session(args.session_zip)
    timestamps = [frame.timestamp_ns for frame in frames]
    deltas = [b - a for a, b in zip(timestamps, timestamps[1:])]
    median_delta_ns = round(statistics.median(deltas))
    fps = (len(frames) - 1) / ((timestamps[-1] - timestamps[0]) / 1_000_000_000.0)
    baseline = recorded_baseline(frames)
    baseline_desktop = benchmark_efficientdet(frames, args.baseline_model.resolve())
    yolo_detections, yolo_timing = run_yolo(frames, args.yolo_model.resolve(), args.image_size, args.threads)
    # Preserve aggregate timings while giving frame results only the available model timings.
    yolo_detector = detector_only(frames, yolo_detections, {})
    efficientdet_detections = [trace_detections(frame, accepted_only=True) for frame in frames]
    efficientdet_lk = detector_plus_tracker(frames, efficientdet_detections, 0.55, 1, "EfficientDet-Lite2 + LK")
    yolo_lk = detector_plus_tracker(frames, yolo_detections, 0.25, 2, "YOLO11n + LK")
    approaches: dict[str, Sequence[FrameResult]] = {
        "baseline": baseline,
        "yolo11n_detector": yolo_detector,
        "efficientdet_lk": efficientdet_lk,
        "yolo11n_lk": yolo_lk,
    }
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    titles = {
        "baseline": "Recorded EfficientDet-Lite2 baseline",
        "yolo11n_detector": "YOLO11n detector-only / identity gated",
        "efficientdet_lk": "EfficientDet-Lite2 + fail-closed LK tracker",
        "yolo11n_lk": "YOLO11n cadence + uncertainty fallback + fail-closed LK",
    }
    for key, results in approaches.items():
        render_video(output_dir / f"{key}.mp4", titles[key], frames, results, fps)
    reference = yolo_detector
    approach_metrics = {key: summarize_approach(results, reference, median_delta_ns) for key, results in approaches.items()}
    recorded_android_times = [float(frame.trace["detector"].get("inferenceMillis", 0.0)) for frame in frames]
    metadata = {
        "created_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "session_path": str(args.session_zip.resolve()),
        "session_sha256": sha256(args.session_zip),
        "frame_count": len(frames),
        "manifest_schema_version": manifest.get("schemaVersion"),
        "dropped_frame_count": manifest.get("droppedFrameCount"),
        "capture_span_seconds": round((timestamps[-1] - timestamps[0]) / 1_000_000_000.0, 6),
        "video_fps": round(fps, 6),
        "baseline_model_sha256": sha256(args.baseline_model),
        "yolo_model_sha256": sha256(args.yolo_model),
        "python": sys.version,
        "platform": platform.platform(),
        "opencv": cv2.__version__,
        "mediapipe": mp.__version__,
        "torch": torch.__version__,
        "ultralytics": __import__("ultralytics").__version__,
    }
    metrics = {
        "metadata": metadata,
        "semantics": {
            "visible_target_expected_frames": len(frames),
            "missing_ttl_ms": MISSING_TTL_NS / 1_000_000,
            "lost_requires_explicit_reselection": True,
            "yolo_confidence_threshold": 0.25,
            "yolo_hybrid_detector_interval_frames": 2,
            "yolo_hybrid_detector_on_tracker_rejection": True,
            "drift_reference": "identity-gated YOLO11n detector-only; not independently labeled ground truth",
        },
        "desktop_cost": {
            "efficientdet_lite2_wall_ms": baseline_desktop,
            "yolo11n": yolo_timing,
            "recorded_android_efficientdet_lite2_inference_ms": timing_summary(recorded_android_times),
        },
        "approaches": approach_metrics,
        "frames": {
            key: [serialize_frame(item, frames[0].image.shape[1], frames[0].image.shape[0]) for item in results]
            for key, results in approaches.items()
        },
    }
    (output_dir / "metrics.json").write_text(json.dumps(metrics, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    write_csv(output_dir / "per_frame.csv", approaches, frames[0].image.shape[1], frames[0].image.shape[0])
    write_report(output_dir / "report.md", metadata, metrics)
    print(json.dumps({"output_dir": str(output_dir), "approaches": approach_metrics}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
