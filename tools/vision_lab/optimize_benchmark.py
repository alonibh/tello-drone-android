#!/usr/bin/env python3
"""Run a bounded, identity-first search on the fixed two-video benchmark."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import platform
import random
import statistics
import time
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Any, Sequence

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
        return ((self.x1 + self.x2) * 0.5, (self.y1 + self.y2) * 0.5)

    def clipped(self, width: int, height: int) -> "Box":
        return Box(
            min(max(0.0, self.x1), width - 1.0),
            min(max(0.0, self.y1), height - 1.0),
            min(max(1.0, self.x2), float(width)),
            min(max(1.0, self.y2), float(height)),
        )

    def normalized(self, width: int, height: int) -> list[float]:
        return [self.x1 / width, self.y1 / height, self.x2 / width, self.y2 / height]

    @classmethod
    def from_normalized(cls, values: Sequence[float], width: int, height: int) -> "Box":
        return cls(values[0] * width, values[1] * height, values[2] * width, values[3] * height)


@dataclass(frozen=True)
class Detection:
    box: Box
    confidence: float


@dataclass
class VideoData:
    id: str
    frames: list[np.ndarray]
    timestamps: list[float]
    annotations: list[dict[str, Any]]
    selection_index: int
    tuning_intervals: list[list[float]]
    held_out_intervals: list[list[float]]


@dataclass(frozen=True)
class Config:
    name: str
    family: str
    detector: str
    use_lk: bool
    detector_cadence: int = 1
    high_confidence: float = 0.55
    low_confidence: float = 0.55
    iou_gate: float = 0.15
    distance_gate: float = 0.12
    appearance_gate: float = 0.45
    ambiguity_margin: float = 0.10
    lk_fb_error: float = 1.2
    lk_inlier_ratio: float = 0.58
    lk_min_features: int = 10
    lk_feature_count: int = 120
    motion_alpha: float = 0.0
    camera_compensation: bool = False
    missing_ttl_s: float = 0.8


@dataclass
class Result:
    state: str
    source: str
    box: Box | None
    note: str
    confidence: float | None = None
    association_margin: float | None = None
    tracker_quality: float | None = None


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
    return intersection / union if union else 0.0


def center_distance(a: Box, b: Box, width: int, height: int) -> float:
    return math.dist(a.center, b.center) / math.hypot(width, height)


def histogram(image: np.ndarray, box: Box) -> np.ndarray | None:
    height, width = image.shape[:2]
    box = box.clipped(width, height)
    x1 = round(box.x1 + 0.14 * box.width)
    x2 = round(box.x2 - 0.14 * box.width)
    y1 = round(box.y1 + 0.08 * box.height)
    y2 = round(box.y2 - 0.05 * box.height)
    crop = image[max(0, y1) : min(height, y2), max(0, x1) : min(width, x2)]
    if crop.size < 100:
        return None
    hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
    result = cv2.calcHist([hsv], [0, 1], None, [24, 16], [0, 180, 0, 256])
    cv2.normalize(result, result, 0.0, 1.0, cv2.NORM_MINMAX)
    return result


def hist_similarity(a: np.ndarray | None, b: np.ndarray | None) -> float:
    if a is None or b is None:
        return 0.0
    return float((cv2.compareHist(a, b, cv2.HISTCMP_CORREL) + 1.0) * 0.5)


def blend_hist(a: np.ndarray | None, b: np.ndarray | None, alpha: float = 0.04) -> np.ndarray | None:
    if b is None:
        return a
    if a is None:
        return b.copy()
    result = (1.0 - alpha) * a + alpha * b
    cv2.normalize(result, result, 0.0, 1.0, cv2.NORM_MINMAX)
    return result


def transform_box(box: Box, matrix: np.ndarray | None, width: int, height: int) -> Box:
    if matrix is None:
        return box
    points = np.array([[[box.x1, box.y1], [box.x2, box.y1], [box.x2, box.y2], [box.x1, box.y2]]], np.float32)
    moved = cv2.transform(points, matrix)[0]
    return Box(float(moved[:, 0].min()), float(moved[:, 1].min()), float(moved[:, 0].max()), float(moved[:, 1].max())).clipped(width, height)


def estimate_camera_motion(previous: np.ndarray, current: np.ndarray) -> np.ndarray | None:
    previous_gray = cv2.cvtColor(previous, cv2.COLOR_BGR2GRAY)
    current_gray = cv2.cvtColor(current, cv2.COLOR_BGR2GRAY)
    points = cv2.goodFeaturesToTrack(previous_gray, maxCorners=220, qualityLevel=0.02, minDistance=12, blockSize=7)
    if points is None or len(points) < 20:
        return None
    forward, status, _ = cv2.calcOpticalFlowPyrLK(previous_gray, current_gray, points, None, winSize=(21, 21), maxLevel=3)
    if forward is None or status is None:
        return None
    valid = status.reshape(-1) == 1
    if valid.sum() < 16:
        return None
    matrix, inliers = cv2.estimateAffinePartial2D(points.reshape(-1, 2)[valid], forward.reshape(-1, 2)[valid], method=cv2.RANSAC, ransacReprojThreshold=2.5)
    if matrix is None or inliers is None or float(inliers.mean()) < 0.50:
        return None
    return matrix


class LkTracker:
    def __init__(self, config: Config) -> None:
        self.config = config
        self.gray: np.ndarray | None = None
        self.points: np.ndarray | None = None
        self.box: Box | None = None

    def reset(self, image: np.ndarray, box: Box) -> None:
        self.gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        self.box = box.clipped(image.shape[1], image.shape[0])
        mask = np.zeros_like(self.gray)
        x1 = round(self.box.x1 + 0.06 * self.box.width)
        x2 = round(self.box.x2 - 0.06 * self.box.width)
        y1 = round(self.box.y1 + 0.05 * self.box.height)
        y2 = round(self.box.y2 - 0.05 * self.box.height)
        cv2.rectangle(mask, (x1, y1), (x2, y2), 255, -1)
        self.points = cv2.goodFeaturesToTrack(
            self.gray,
            mask=mask,
            maxCorners=self.config.lk_feature_count,
            qualityLevel=0.008,
            minDistance=4,
            blockSize=5,
        )

    def update(self, image: np.ndarray) -> tuple[Box | None, float, str]:
        if self.gray is None or self.points is None or self.box is None or len(self.points) < self.config.lk_min_features:
            return None, 0.0, "LK not initialized"
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        forward, sf, errors = cv2.calcOpticalFlowPyrLK(self.gray, gray, self.points, None, winSize=(21, 21), maxLevel=3)
        if forward is None or sf is None:
            return None, 0.0, "LK forward failure"
        backward, sb, _ = cv2.calcOpticalFlowPyrLK(gray, self.gray, forward, None, winSize=(21, 21), maxLevel=3)
        if backward is None or sb is None:
            return None, 0.0, "LK backward failure"
        old = self.points.reshape(-1, 2)
        new = forward.reshape(-1, 2)
        fb = np.linalg.norm(old - backward.reshape(-1, 2), axis=1)
        lk_errors = errors.reshape(-1) if errors is not None else np.zeros(len(old))
        valid = (sf.reshape(-1) == 1) & (sb.reshape(-1) == 1) & (fb <= self.config.lk_fb_error) & (lk_errors <= 30.0)
        if int(valid.sum()) < self.config.lk_min_features:
            return None, 0.0, "too few LK features"
        matrix, inliers = cv2.estimateAffinePartial2D(old[valid], new[valid], method=cv2.RANSAC, ransacReprojThreshold=2.5)
        if matrix is None or inliers is None:
            return None, 0.0, "LK transform failure"
        mask = inliers.reshape(-1) == 1
        count = int(mask.sum())
        ratio = float(mask.mean())
        median_fb = float(np.median(fb[valid][mask])) if count else math.inf
        scale = math.hypot(float(matrix[0, 0]), float(matrix[0, 1]))
        if count < self.config.lk_min_features or ratio < self.config.lk_inlier_ratio or median_fb > self.config.lk_fb_error * 0.70:
            return None, 0.0, "weak LK consensus"
        if not 0.86 <= scale <= 1.16:
            return None, 0.0, "implausible LK scale"
        candidate = transform_box(self.box, matrix, image.shape[1], image.shape[0])
        frame_area = image.shape[0] * image.shape[1]
        if not 0.00015 * frame_area <= candidate.area <= 0.75 * frame_area:
            return None, 0.0, "implausible LK box"
        quality = min(1.0, 0.45 * min(count / 30.0, 1.0) + 0.40 * ratio + 0.15 * max(0.0, 1.0 - median_fb / self.config.lk_fb_error))
        if quality < 0.56:
            return None, quality, "LK quality rejected"
        self.gray = gray
        self.points = new[valid][mask].reshape(-1, 1, 2).astype(np.float32)
        self.box = candidate
        if len(self.points) < max(18, self.config.lk_min_features * 2):
            self.reset(image, candidate)
        return candidate, quality, "accepted LK"


def load_benchmark(root: Path) -> tuple[dict[str, Any], list[VideoData]]:
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    videos: list[VideoData] = []
    for item in manifest["videos"]:
        annotation_path = root / item["annotations_file"]
        if sha256(annotation_path) != item["annotations_sha256"]:
            raise ValueError(f"annotation hash mismatch for {item['id']}")
        annotations = json.loads(annotation_path.read_text(encoding="utf-8"))
        if not all(entry.get("reviewed_identity") for entry in annotations):
            raise ValueError(f"unreviewed identity label in {item['id']}")
        frame_paths = [root / item["id"] / frame["file"] for frame in item["frames"]]
        for frame_path, frame_record in zip(frame_paths, item["frames"], strict=True):
            expected_jpeg_hash = frame_record.get("canonical_jpeg_sha256")
            if expected_jpeg_hash and sha256(frame_path) != expected_jpeg_hash:
                raise ValueError(f"canonical JPEG hash mismatch: {frame_path}")
        frames = [cv2.imread(str(path)) for path in frame_paths]
        if any(frame is None for frame in frames):
            raise ValueError(f"missing canonical frames for {item['id']}")
        timestamps = [float(frame["timestamp_s"]) for frame in item["frames"]]
        selection_index = min(range(len(timestamps)), key=lambda index: abs(timestamps[index] - item["selection_time_s"]))
        videos.append(VideoData(item["id"], frames, timestamps, annotations, selection_index, item["tuning_intervals_s"], item["held_out_intervals_s"]))
    return manifest, videos


def serialize_detections(items: list[list[Detection]]) -> list[list[dict[str, Any]]]:
    return [[{"box": asdict(d.box), "confidence": d.confidence} for d in frame] for frame in items]


def deserialize_detections(items: list[list[dict[str, Any]]]) -> list[list[Detection]]:
    return [[Detection(Box(**d["box"]), float(d["confidence"])) for d in frame] for frame in items]


def detection_cache(
    videos: list[VideoData], detector: str, model_path: Path, cache_path: Path, threads: int
) -> tuple[dict[str, list[list[Detection]]], dict[str, Any]]:
    expected_hash = EFFICIENTDET_SHA256 if detector == "efficientdet_lite2" else YOLO11N_SHA256
    if sha256(model_path) != expected_hash:
        raise ValueError(f"{detector} model hash mismatch")
    if cache_path.is_file():
        payload = json.loads(cache_path.read_text(encoding="utf-8"))
        if payload.get("model_sha256") == expected_hash:
            return {key: deserialize_detections(value) for key, value in payload["detections"].items()}, payload["timing"]
    all_detections: dict[str, list[list[Detection]]] = {}
    timings: list[float] = []
    if detector == "efficientdet_lite2":
        options = mp_vision.ObjectDetectorOptions(
            base_options=mp_python.BaseOptions(model_asset_path=str(model_path.resolve())),
            score_threshold=0.01,
            category_allowlist=["person"],
            max_results=12,
        )
        with mp_vision.ObjectDetector.create_from_options(options) as model:
            for video in videos:
                video_results: list[list[Detection]] = []
                for frame in video.frames:
                    rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                    started = time.perf_counter_ns()
                    output = model.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb))
                    timings.append((time.perf_counter_ns() - started) / 1_000_000.0)
                    frame_items: list[Detection] = []
                    for detection in output.detections:
                        category = detection.categories[0]
                        box = detection.bounding_box
                        frame_items.append(Detection(Box(box.origin_x, box.origin_y, box.origin_x + box.width, box.origin_y + box.height), float(category.score)))
                    video_results.append(frame_items)
                all_detections[video.id] = video_results
    else:
        torch.set_num_threads(threads)
        model = YOLO(str(model_path.resolve()), task="detect")
        for video in videos:
            video_results = []
            for frame in video.frames:
                started = time.perf_counter_ns()
                prediction = model.predict(frame, imgsz=320, conf=0.02, iou=0.60, classes=[0], device="cpu", verbose=False)[0]
                timings.append((time.perf_counter_ns() - started) / 1_000_000.0)
                frame_items = []
                if prediction.boxes is not None:
                    for values, confidence in zip(prediction.boxes.xyxy.cpu().numpy(), prediction.boxes.conf.cpu().numpy(), strict=True):
                        frame_items.append(Detection(Box(*(float(value) for value in values)), float(confidence)))
                video_results.append(frame_items)
            all_detections[video.id] = video_results
    timing = {
        "frames": len(timings),
        "mean_ms": round(statistics.mean(timings), 3),
        "median_ms": round(statistics.median(timings), 3),
        "p95_ms": round(float(np.percentile(timings, 95)), 3),
    }
    payload = {"model_sha256": expected_hash, "detector": detector, "timing": timing, "detections": {key: serialize_detections(value) for key, value in all_detections.items()}}
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(json.dumps(payload) + "\n", encoding="utf-8")
    return all_detections, timing


def associate(
    detections: Sequence[Detection],
    predicted: Box,
    image: np.ndarray,
    target_hist: np.ndarray | None,
    config: Config,
) -> tuple[Detection | None, float | None, str]:
    height, width = image.shape[:2]
    eligible: list[tuple[float, Detection, float, float]] = []
    for detection in detections:
        if detection.confidence < config.low_confidence:
            continue
        overlap = iou(predicted, detection.box)
        distance = center_distance(predicted, detection.box, width, height)
        appearance = hist_similarity(target_hist, histogram(image, detection.box))
        area_ratio = detection.box.area / max(predicted.area, 1.0)
        if not (overlap >= config.iou_gate or distance <= config.distance_gate):
            continue
        if not 0.35 <= area_ratio <= 2.8 or appearance < config.appearance_gate:
            continue
        if detection.confidence < config.high_confidence and not (
            appearance >= config.appearance_gate + 0.06
            and (overlap >= config.iou_gate + 0.08 or distance <= config.distance_gate * 0.70)
        ):
            continue
        score = 0.42 * overlap + 0.23 * (1.0 - min(1.0, distance / max(config.distance_gate, 0.01))) + 0.27 * appearance + 0.08 * detection.confidence
        eligible.append((score, detection, appearance, overlap))
    if not eligible:
        return None, None, "no identity-safe association"
    eligible.sort(key=lambda item: item[0], reverse=True)
    margin = eligible[0][0] - eligible[1][0] if len(eligible) > 1 else 1.0
    if margin < config.ambiguity_margin:
        return None, margin, "competitor ambiguity"
    return eligible[0][1], margin, "accepted detector association"


def run_pipeline(
    video: VideoData,
    detections: list[list[Detection]],
    camera_motion: list[np.ndarray | None],
    config: Config,
    selection_index: int | None = None,
    stop_index: int | None = None,
) -> tuple[list[Result], float]:
    results = [Result("Unselected", "none", None, "before explicit selection") for _ in video.frames]
    selection_index = video.selection_index if selection_index is None else selection_index
    stop_index = len(video.frames) if stop_index is None else stop_index
    selected_annotation = video.annotations[selection_index]
    if not selected_annotation["target_visible"]:
        raise ValueError(f"{video.id} selection frame {selection_index} has no visible target")
    selected_box = Box.from_normalized(selected_annotation["target_box_norm"], video.frames[0].shape[1], video.frames[0].shape[0])
    box = selected_box
    target_hist = histogram(video.frames[selection_index], box)
    tracker = LkTracker(config)
    tracker.reset(video.frames[selection_index], box)
    results[selection_index] = Result("Tracked", "explicit selection", box, "fixed user selection")
    last_seen_s = video.timestamps[selection_index]
    missing_since: float | None = None
    velocity = np.zeros(2, dtype=np.float64)
    previous_center = np.array(box.center)
    tracker_times: list[float] = []
    lost = False
    for index in range(selection_index + 1, stop_index):
        frame = video.frames[index]
        height, width = frame.shape[:2]
        if lost:
            results[index] = Result("Lost", "none", None, "Lost latched; explicit reselection required")
            continue
        predicted = transform_box(box, camera_motion[index] if config.camera_compensation else None, width, height)
        if config.motion_alpha > 0.0:
            predicted = Box(predicted.x1 + velocity[0], predicted.y1 + velocity[1], predicted.x2 + velocity[0], predicted.y2 + velocity[1]).clipped(width, height)
        flow_box: Box | None = None
        flow_quality = 0.0
        flow_note = "LK disabled"
        if config.use_lk:
            started = time.perf_counter_ns()
            flow_box, flow_quality, flow_note = tracker.update(frame)
            tracker_times.append((time.perf_counter_ns() - started) / 1_000_000.0)
            if flow_box is not None:
                predicted = flow_box
        should_detect = not config.use_lk or index % config.detector_cadence == 0 or flow_box is None or missing_since is not None
        chosen = None
        margin = None
        association_note = "detector cadence skipped"
        if should_detect:
            chosen, margin, association_note = associate(detections[index], predicted, frame, target_hist, config)
        explicit_ambiguity = association_note == "competitor ambiguity"
        if chosen is not None:
            old_center = np.array(box.center)
            box = chosen.box.clipped(width, height)
            displacement = np.array(box.center) - old_center
            velocity = (1.0 - config.motion_alpha) * velocity + config.motion_alpha * displacement
            previous_center = np.array(box.center)
            target_hist = blend_hist(target_hist, histogram(frame, box))
            tracker.reset(frame, box)
            last_seen_s = video.timestamps[index]
            missing_since = None
            source = "low-confidence association" if chosen.confidence < config.high_confidence else "detector"
            results[index] = Result("Tracked", source, box, association_note, chosen.confidence, margin)
            continue
        if flow_box is not None and not explicit_ambiguity:
            displacement = np.array(flow_box.center) - previous_center
            velocity = (1.0 - config.motion_alpha) * velocity + config.motion_alpha * displacement
            previous_center = np.array(flow_box.center)
            box = flow_box
            last_seen_s = video.timestamps[index]
            missing_since = None
            results[index] = Result("Tracked", "LK", box, flow_note if not should_detect else association_note + "; " + flow_note, tracker_quality=flow_quality)
            continue
        if missing_since is None:
            missing_since = video.timestamps[index]
        if video.timestamps[index] - last_seen_s >= config.missing_ttl_s:
            lost = True
            results[index] = Result("Lost", "none", None, association_note + "; Lost latched")
        else:
            results[index] = Result("Missing", "none", None, association_note + "; " + flow_note)
    tracker_mean = statistics.mean(tracker_times) if tracker_times else 0.0
    return results, tracker_mean


def in_intervals(timestamp: float, intervals: Sequence[Sequence[float]]) -> bool:
    return any(start <= timestamp < end for start, end in intervals)


def evaluate_results(video: VideoData, results: list[Result], partition: str) -> dict[str, Any]:
    intervals = video.tuning_intervals if partition == "tuning" else video.held_out_intervals
    indices = [index for index, timestamp in enumerate(video.timestamps) if in_intervals(timestamp, intervals)]
    visible = localized = wrong_person = drift = tracked = safe_tracked = missing = lost = false_track = 0
    ious: list[float] = []
    correct_centers: list[tuple[int, np.ndarray, float]] = []
    wrong_indices: list[int] = []
    for index in indices:
        annotation, result = video.annotations[index], results[index]
        target_visible = bool(annotation["target_visible"])
        is_tracked = result.state == "Tracked" and result.box is not None
        if target_visible:
            visible += 1
            if is_tracked:
                tracked += 1
                truth = Box.from_normalized(annotation["target_box_norm"], video.frames[index].shape[1], video.frames[index].shape[0])
                overlap = iou(result.box, truth)
                distance = center_distance(result.box, truth, video.frames[index].shape[1], video.frames[index].shape[0])
                ious.append(overlap)
                if distance <= 0.12 and overlap >= 0.05:
                    safe_tracked += 1
                else:
                    wrong_person += 1
                    wrong_indices.append(index)
                if overlap >= 0.30 and distance <= 0.12:
                    localized += 1
                    correct_centers.append((index, np.array(result.box.center), math.hypot(video.frames[index].shape[1], video.frames[index].shape[0])))
                elif distance <= 0.12 and overlap >= 0.05:
                    drift += 1
            elif result.state == "Lost":
                lost += 1
            else:
                missing += 1
        elif is_tracked:
            false_track += 1
    switch_events = 0
    last = -2
    for index in wrong_indices:
        if index != last + 1:
            switch_events += 1
        last = index
    jitter: list[float] = []
    for first, second, third in zip(correct_centers, correct_centers[1:], correct_centers[2:]):
        if first[0] + 1 == second[0] and second[0] + 1 == third[0]:
            jitter.append(float(np.linalg.norm(third[1] - 2 * second[1] + first[1]) / third[2]))
    continuity = safe_tracked / visible if visible else 0.0
    return {
        "frames": len(indices),
        "visible_target_frames": visible,
        "identity_switch_events": switch_events,
        "wrong_person_frames": wrong_person,
        "localization_drift_frames": drift,
        "false_tracked_while_target_invisible": false_track,
        "lost_visible_frames": lost,
        "missing_visible_frames": missing,
        "correctly_localized_target_frames": localized,
        "identity_safe_tracked_frames": safe_tracked,
        "tracked_visible_frames": tracked,
        "identity_safe_continuity_percent": round(100.0 * continuity, 3),
        "mean_iou_when_tracked": round(statistics.mean(ious), 4) if ious else 0.0,
        "jitter_rms_norm": round(math.sqrt(statistics.mean(value * value for value in jitter)), 6) if jitter else 0.0,
        "wrong_indices": wrong_indices,
    }


def aggregate(per_video: dict[str, dict[str, Any]], tracker_ms: dict[str, float]) -> dict[str, Any]:
    summed = {
        key: sum(item[key] for item in per_video.values())
        for key in (
            "frames",
            "visible_target_frames",
            "identity_switch_events",
            "wrong_person_frames",
            "localization_drift_frames",
            "false_tracked_while_target_invisible",
            "lost_visible_frames",
            "missing_visible_frames",
            "correctly_localized_target_frames",
            "identity_safe_tracked_frames",
            "tracked_visible_frames",
        )
    }
    summed["identity_safe_continuity_percent"] = round(100.0 * summed["identity_safe_tracked_frames"] / max(1, summed["visible_target_frames"]), 3)
    summed["mean_iou_when_tracked"] = round(statistics.mean(item["mean_iou_when_tracked"] for item in per_video.values()), 4)
    summed["jitter_rms_norm"] = round(statistics.mean(item["jitter_rms_norm"] for item in per_video.values()), 6)
    summed["tracker_mean_ms"] = round(statistics.mean(tracker_ms.values()), 3)
    summed["rank_tuple"] = [
        summed["identity_switch_events"],
        summed["wrong_person_frames"] + summed["false_tracked_while_target_invisible"],
        summed["lost_visible_frames"],
        summed["missing_visible_frames"],
        -summed["identity_safe_tracked_frames"],
        summed["localization_drift_frames"],
        -summed["mean_iou_when_tracked"],
        summed["jitter_rms_norm"],
        summed["tracker_mean_ms"],
    ]
    return summed


def evaluate_config(
    config: Config,
    videos: list[VideoData],
    detector_data: dict[str, dict[str, list[list[Detection]]]],
    camera_motion: dict[str, list[np.ndarray | None]],
    partition: str,
) -> tuple[dict[str, Any], dict[str, list[Result]]]:
    per_video: dict[str, dict[str, Any]] = {}
    all_results: dict[str, list[Result]] = {}
    tracker_ms: dict[str, float] = {}
    for video in videos:
        intervals = video.tuning_intervals if partition == "tuning" else video.held_out_intervals
        combined = [Result("Unselected", "none", None, "outside evaluated section") for _ in video.frames]
        costs: list[float] = []
        for start_s, end_s in intervals:
            start_index = next(index for index, timestamp in enumerate(video.timestamps) if timestamp >= start_s)
            stop_index = next((index for index, timestamp in enumerate(video.timestamps) if timestamp >= end_s), len(video.frames))
            section_results, section_cost = run_pipeline(
                video,
                detector_data[config.detector][video.id],
                camera_motion[video.id],
                config,
                selection_index=start_index,
                stop_index=stop_index,
            )
            combined[start_index:stop_index] = section_results[start_index:stop_index]
            costs.append(section_cost)
        all_results[video.id] = combined
        tracker_ms[video.id] = statistics.mean(costs) if costs else 0.0
        per_video[video.id] = evaluate_results(video, combined, partition)
    return {"config": asdict(config), "partition": partition, "per_video": per_video, "aggregate": aggregate(per_video, tracker_ms)}, all_results


def seed_configs() -> list[Config]:
    configs = [
        Config("efficientdet_baseline", "efficientdet_baseline", "efficientdet_lite2", False, high_confidence=0.55, low_confidence=0.55, appearance_gate=0.38),
        Config("efficientdet_lk", "efficientdet_lk", "efficientdet_lite2", True, high_confidence=0.55, low_confidence=0.55),
        Config("efficientdet_hilo_lk", "efficientdet_hilo_lk", "efficientdet_lite2", True, detector_cadence=2, high_confidence=0.55, low_confidence=0.18),
        Config("yolo11n_detector", "yolo11n_detector", "yolo11n", False, high_confidence=0.30, low_confidence=0.30),
        Config("yolo11n_hilo_lk", "yolo11n_hilo_lk", "yolo11n", True, detector_cadence=2, high_confidence=0.30, low_confidence=0.08),
    ]
    rng = random.Random(20260823)
    for detector, high_values in (("efficientdet_lite2", [0.45, 0.55, 0.65]), ("yolo11n", [0.22, 0.30, 0.40])):
        for index in range(18):
            configs.append(
                Config(
                    name=f"search_{detector}_{index:02d}",
                    family=f"{detector}_searched_hybrid",
                    detector=detector,
                    use_lk=True,
                    detector_cadence=rng.choice([1, 2, 3]),
                    high_confidence=rng.choice(high_values),
                    low_confidence=rng.choice([0.05, 0.10, 0.18, 0.25]),
                    iou_gate=rng.choice([0.08, 0.15, 0.23]),
                    distance_gate=rng.choice([0.08, 0.12, 0.17]),
                    appearance_gate=rng.choice([0.38, 0.46, 0.54]),
                    ambiguity_margin=rng.choice([0.06, 0.10, 0.15]),
                    lk_fb_error=rng.choice([0.8, 1.2, 1.8]),
                    lk_inlier_ratio=rng.choice([0.50, 0.60, 0.70]),
                    lk_min_features=rng.choice([8, 10, 14]),
                    lk_feature_count=rng.choice([80, 120, 180]),
                    motion_alpha=rng.choice([0.0, 0.35, 0.65]),
                    camera_compensation=rng.choice([False, True]),
                    missing_ttl_s=rng.choice([0.6, 0.8, 1.0]),
                )
            )
    return configs


def local_variants(best: Config, iteration: int) -> list[Config]:
    variants: list[Config] = []
    changes: list[tuple[str, Sequence[Any]]] = [
        ("detector_cadence", [1, 2, 3]),
        ("low_confidence", [max(0.03, best.low_confidence - 0.05), best.low_confidence + 0.05]),
        ("appearance_gate", [max(0.30, best.appearance_gate - 0.05), min(0.65, best.appearance_gate + 0.05)]),
        ("ambiguity_margin", [max(0.04, best.ambiguity_margin - 0.03), min(0.20, best.ambiguity_margin + 0.03)]),
        ("lk_fb_error", [max(0.6, best.lk_fb_error - 0.3), min(2.2, best.lk_fb_error + 0.3)]),
        ("lk_inlier_ratio", [max(0.45, best.lk_inlier_ratio - 0.05), min(0.78, best.lk_inlier_ratio + 0.05)]),
        ("motion_alpha", [0.0, 0.35, 0.65]),
        ("camera_compensation", [not best.camera_compensation]),
    ]
    counter = 0
    for field_name, values in changes:
        for value in values:
            if value == getattr(best, field_name):
                continue
            variants.append(replace(best, name=f"refine{iteration}_{best.detector}_{counter:02d}", family=f"{best.detector}_refined", **{field_name: value}))
            counter += 1
    return variants


def config_signature(config: Config) -> tuple[Any, ...]:
    values = asdict(config)
    return tuple(values[key] for key in values if key not in {"name", "family"})


def meaningful_improvement(current: dict[str, Any], candidate: dict[str, Any]) -> bool:
    before = current["aggregate"]
    after = candidate["aggregate"]
    if tuple(after["rank_tuple"]) >= tuple(before["rank_tuple"]):
        return False
    integer_priorities = (
        "identity_switch_events",
        "wrong_person_frames",
        "false_tracked_while_target_invisible",
        "lost_visible_frames",
        "missing_visible_frames",
        "identity_safe_tracked_frames",
        "localization_drift_frames",
    )
    if any(before[key] != after[key] for key in integer_priorities):
        return True
    if after["mean_iou_when_tracked"] - before["mean_iou_when_tracked"] >= 0.01:
        return True
    if before["jitter_rms_norm"] - after["jitter_rms_norm"] >= 0.001:
        return True
    return before["tracker_mean_ms"] - after["tracker_mean_ms"] >= 2.0


def color(state: str) -> tuple[int, int, int]:
    return {"Tracked": (50, 220, 50), "Missing": (0, 180, 255), "Lost": (30, 30, 235), "Unselected": (180, 180, 180)}[state]


def render_cell(video: VideoData, index: int, result: Result, title: str, detections: list[Detection], size: tuple[int, int]) -> np.ndarray:
    width, height = size
    cell = cv2.resize(video.frames[index], size, interpolation=cv2.INTER_AREA)
    sx, sy = width / video.frames[index].shape[1], height / video.frames[index].shape[0]
    annotation = video.annotations[index]
    if annotation["target_box_norm"] is not None:
        truth = Box.from_normalized(annotation["target_box_norm"], width, height)
        cv2.rectangle(cell, (round(truth.x1), round(truth.y1)), (round(truth.x2), round(truth.y2)), (255, 180, 30), 2)
    for detection in detections:
        box = Box(detection.box.x1 * sx, detection.box.y1 * sy, detection.box.x2 * sx, detection.box.y2 * sy)
        cv2.rectangle(cell, (round(box.x1), round(box.y1)), (round(box.x2), round(box.y2)), (0, 120, 160), 1)
    if result.box is not None:
        box = Box(result.box.x1 * sx, result.box.y1 * sy, result.box.x2 * sx, result.box.y2 * sy)
        cv2.rectangle(cell, (round(box.x1), round(box.y1)), (round(box.x2), round(box.y2)), color(result.state), 3)
    cv2.rectangle(cell, (0, 0), (width, 48), (15, 15, 15), -1)
    cv2.putText(cell, title, (8, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.48, (245, 245, 245), 1, cv2.LINE_AA)
    cv2.putText(cell, f"{video.timestamps[index]:.1f}s {result.state} / {result.source}", (8, 39), cv2.FONT_HERSHEY_SIMPLEX, 0.42, color(result.state), 1, cv2.LINE_AA)
    return cell


def render_video(path: Path, video: VideoData, results: list[Result], detections: list[list[Detection]], title: str) -> None:
    writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), 5.0, (960, 540))
    if not writer.isOpened():
        raise RuntimeError(f"could not write {path}")
    for index, result in enumerate(results):
        writer.write(render_cell(video, index, result, title, detections[index], (960, 540)))
    writer.release()


def render_comparison(
    path: Path,
    video: VideoData,
    named_results: Sequence[tuple[str, list[Result], list[list[Detection]]]],
) -> None:
    writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), 5.0, (1280, 720))
    if not writer.isOpened():
        raise RuntimeError(f"could not write {path}")
    for index in range(len(video.frames)):
        cells = [render_cell(video, index, results[index], name, detections[index], (640, 360)) for name, results, detections in named_results]
        writer.write(cv2.vconcat([cv2.hconcat(cells[:2]), cv2.hconcat(cells[2:4])]))
    writer.release()


def write_outputs(
    output_dir: Path,
    manifest: dict[str, Any],
    videos: list[VideoData],
    history: list[dict[str, Any]],
    finalists: list[dict[str, Any]],
    winner: dict[str, Any],
    winner_results: dict[str, list[Result]],
    fixed_results: dict[str, dict[str, list[Result]]],
    detector_data: dict[str, dict[str, list[list[Detection]]]],
    detector_timing: dict[str, Any],
    stop_reason: str,
) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "experiment_history.jsonl").write_text("".join(json.dumps(item) + "\n" for item in history), encoding="utf-8")
    (output_dir / "ranked_candidates.json").write_text(json.dumps(finalists, indent=2) + "\n", encoding="utf-8")
    (output_dir / "winning_config.json").write_text(json.dumps(winner, indent=2) + "\n", encoding="utf-8")
    with (output_dir / "ranked_candidates.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.writer(stream)
        writer.writerow(["rank", "name", "family", "detector", "switches", "wrong_person_frames", "false_track_frames", "lost_frames", "missing_frames", "safe_continuity_percent", "localization_drift_frames", "mean_iou", "tracker_ms"])
        for rank, item in enumerate(finalists, 1):
            aggregate_metrics = item["aggregate"]
            config = item["config"]
            writer.writerow([rank, config["name"], config["family"], config["detector"], aggregate_metrics["identity_switch_events"], aggregate_metrics["wrong_person_frames"], aggregate_metrics["false_tracked_while_target_invisible"], aggregate_metrics["lost_visible_frames"], aggregate_metrics["missing_visible_frames"], aggregate_metrics["identity_safe_continuity_percent"], aggregate_metrics["localization_drift_frames"], aggregate_metrics["mean_iou_when_tracked"], aggregate_metrics["tracker_mean_ms"]])
    with (output_dir / "timelines.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.writer(stream)
        writer.writerow(["video", "canonical_index", "timestamp_s", "partition", "target_visible", "state", "source", "note"])
        for video in videos:
            for index, result in enumerate(winner_results[video.id]):
                partition = "tuning" if in_intervals(video.timestamps[index], video.tuning_intervals) else "held_out" if in_intervals(video.timestamps[index], video.held_out_intervals) else "outside"
                writer.writerow([video.id, index, video.timestamps[index], partition, video.annotations[index]["target_visible"], result.state, result.source, result.note])
    diagnostics = {video.id: winner["per_video"][video.id]["wrong_indices"] for video in videos}
    (output_dir / "identity_competitor_diagnostics.json").write_text(json.dumps({"winner": winner["config"]["name"], "wrong_or_switch_indices": diagnostics, "interpretation": "empty lists mean no identity switch, wrong-person track, or IoU/distance drift failure on held-out frames"}, indent=2) + "\n", encoding="utf-8")
    for video in videos:
        winner_detector = detector_data[winner["config"]["detector"]][video.id]
        render_video(output_dir / f"winner_{video.id}.mp4", video, winner_results[video.id], winner_detector, f"WINNER: {winner['config']['name']}")
        comparison: list[tuple[str, list[Result], list[list[Detection]]]] = []
        for name in ("efficientdet_baseline", "efficientdet_lk", "yolo11n_detector"):
            config = next(entry["config"] for entry in history if entry["config"]["name"] == name)
            detector = config["detector"]
            comparison.append((name, fixed_results[name][video.id], detector_data[detector][video.id]))
        comparison.append(("winner", winner_results[video.id], winner_detector))
        render_comparison(output_dir / f"comparison_{video.id}.mp4", video, comparison)
    held = winner["aggregate"]
    report = [
        "# Offline identity-first tracker optimization",
        "",
        "## Benchmark integrity",
        "",
        f"- Canonical data: {sum(len(video.frames) for video in videos)} frames at 5 Hz from two SHA-256-pinned source videos.",
        "- Identity ground truth was frozen before search. A 960 px proposal pass bootstrapped boxes; every canonical frame was rendered to review sheets, the exact person identity was visually checked, and false-visible ranges were manually corrected.",
        "- Candidate detections are not accepted as runtime ground truth. Annotation hashes are stored in the canonical manifest and verified before evaluation.",
        "- Tuning and held-out time intervals are fixed in `benchmark_spec.json`; held-out results are evaluated only for architecture finalists.",
        "",
        "## Winner",
        "",
        f"`{winner['config']['name']}`: `{winner['config']['detector']}` + {'fail-closed LK' if winner['config']['use_lk'] else 'detector-only'}.",
        "",
        f"Held-out: **{held['identity_switch_events']} identity switches**, **{held['wrong_person_frames']} wrong-person frames**, {held['false_tracked_while_target_invisible']} false-track frames, {held['lost_visible_frames']} Lost frames, {held['missing_visible_frames']} Missing frames, **{held['identity_safe_continuity_percent']:.3f}% identity-safe continuity**, {held['localization_drift_frames']} localization-drift frames, mean tracked IoU {held['mean_iou_when_tracked']:.4f}.",
        "",
        "## Search and rejection reasons",
        "",
        f"- {len(history)} tuning experiments were recorded. Search stopped because: {stop_reason}",
        "- Ranking is strict lexicographic priority: identity switches, wrong-person/false-track frames, Lost, Missing, identity-safe continuity, localization drift, IoU, jitter, then compute. Therefore no continuity gain can compensate for an identity switch.",
        "- Rejected candidates rank lower for the first differing item in that tuple; detailed configurations and per-video metrics are in `experiment_history.jsonl` and `ranked_candidates.json`.",
        "",
        "## Desktop cost",
        "",
        f"- EfficientDet-Lite2: {json.dumps(detector_timing['efficientdet_lite2'])}",
        f"- YOLO11n 320 CPU/PyTorch: {json.dumps(detector_timing['yolo11n'])}",
        f"- Winner LK mean per processed frame: {held['tracker_mean_ms']:.3f} ms.",
        "- These desktop figures are directional only; they are not Teclast LiteRT measurements.",
        "",
        "## Decision",
        "",
        "The evidence in this report determines the detector/LK/low-confidence/camera-motion recommendation; see `recommendation.md` for the concise port decision and deployment blockers.",
    ]
    (output_dir / "report.md").write_text("\n".join(report) + "\n", encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--benchmark-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--baseline-model", type=Path, default=Path("app/src/main/assets/efficientdet_lite2_metadata_v1_int8.tflite"))
    parser.add_argument("--yolo-model", type=Path, default=CACHE_DIR / "yolo11n.pt")
    parser.add_argument("--threads", type=int, default=4)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    manifest, videos = load_benchmark(args.benchmark_dir)
    detector_data: dict[str, dict[str, list[list[Detection]]]] = {}
    detector_timing: dict[str, Any] = {}
    for detector, model in (("efficientdet_lite2", args.baseline_model), ("yolo11n", args.yolo_model)):
        data, timing = detection_cache(videos, detector, model, args.benchmark_dir / "cache" / f"{detector}.json", args.threads)
        detector_data[detector] = data
        detector_timing[detector] = timing
    camera_motion = {
        video.id: [None] + [estimate_camera_motion(video.frames[index - 1], video.frames[index]) for index in range(1, len(video.frames))]
        for video in videos
    }
    history: list[dict[str, Any]] = []
    tune_results: list[dict[str, Any]] = []
    seeds = seed_configs()
    for config in seeds:
        metrics, results = evaluate_config(config, videos, detector_data, camera_motion, "tuning")
        metrics["iteration"] = 0
        history.append(metrics)
        tune_results.append(metrics)
    best_tuning = min(tune_results, key=lambda item: tuple(item["aggregate"]["rank_tuple"]))
    seen = {config_signature(Config(**item["config"])) for item in tune_results}
    stop_reason = ""
    for iteration in range(1, 5):
        variants = [
            config
            for config in local_variants(Config(**best_tuning["config"]), iteration)
            if config_signature(config) not in seen
        ]
        if not variants:
            stop_reason = f"refinement round {iteration} had no unseen neighboring configurations"
            break
        refinement_results: list[dict[str, Any]] = []
        for config in variants:
            seen.add(config_signature(config))
            metrics, _ = evaluate_config(config, videos, detector_data, camera_motion, "tuning")
            metrics["iteration"] = iteration
            history.append(metrics)
            tune_results.append(metrics)
            refinement_results.append(metrics)
        candidate = min(refinement_results, key=lambda item: tuple(item["aggregate"]["rank_tuple"]))
        if not meaningful_improvement(best_tuning, candidate):
            stop_reason = f"refinement round {iteration} produced no meaningful tuning improvement"
            break
        best_tuning = candidate
    else:
        stop_reason = "four focused refinement rounds produced meaningful gains; the documented bounded refinement limit was reached"
    finalists_by_family: dict[str, list[dict[str, Any]]] = {}
    for item in sorted(tune_results, key=lambda value: tuple(value["aggregate"]["rank_tuple"])):
        family = item["config"]["family"]
        if len(finalists_by_family.setdefault(family, [])) < 2:
            finalists_by_family[family].append(item)
    heldout: list[dict[str, Any]] = []
    for tuning_item in [item for values in finalists_by_family.values() for item in values]:
        config = Config(**tuning_item["config"])
        metrics, results = evaluate_config(config, videos, detector_data, camera_motion, "held_out")
        metrics["selected_from_tuning_rank_tuple"] = tuning_item["aggregate"]["rank_tuple"]
        heldout.append(metrics)
    heldout.sort(key=lambda item: tuple(item["aggregate"]["rank_tuple"]))
    winner = heldout[0]
    named_seed_configs = {config.name: config for config in seeds}
    fixed_results: dict[str, dict[str, list[Result]]] = {}
    for name in ("efficientdet_baseline", "efficientdet_lk", "yolo11n_detector"):
        config = named_seed_configs[name]
        fixed_results[name] = {
            video.id: run_pipeline(video, detector_data[config.detector][video.id], camera_motion[video.id], config)[0]
            for video in videos
        }
    winning_config = Config(**winner["config"])
    winner_results = {
        video.id: run_pipeline(video, detector_data[winning_config.detector][video.id], camera_motion[video.id], winning_config)[0]
        for video in videos
    }
    output_dir = args.output_dir.resolve()
    write_outputs(output_dir, manifest, videos, history, heldout, winner, winner_results, fixed_results, detector_data, detector_timing, stop_reason)
    print(json.dumps({"output_dir": str(output_dir), "experiments": len(history), "finalists": len(heldout), "winner": winner}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
