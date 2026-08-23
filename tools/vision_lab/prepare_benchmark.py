#!/usr/bin/env python3
"""Extract canonical frames and bootstrap a fixed, reviewable identity benchmark."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any

LAB_DIR = Path(__file__).resolve().parent
CACHE_DIR = LAB_DIR / ".cache"
os.environ.setdefault("YOLO_CONFIG_DIR", str(CACHE_DIR / "ultralytics"))

import cv2  # noqa: E402
import numpy as np  # noqa: E402
import torch  # noqa: E402
from ultralytics import YOLO  # noqa: E402

YOLO11N_SHA256 = "0ebbc80d4a7680d14987a577cd21342b65ecfd94632bd9a8da63ae6417644ee1"


@dataclass(frozen=True)
class Box:
    x1: float
    y1: float
    x2: float
    y2: float

    @property
    def center(self) -> tuple[float, float]:
        return ((self.x1 + self.x2) * 0.5, (self.y1 + self.y2) * 0.5)

    @property
    def area(self) -> float:
        return max(0.0, self.x2 - self.x1) * max(0.0, self.y2 - self.y1)

    def normalized(self, width: int, height: int) -> list[float]:
        return [self.x1 / width, self.y1 / height, self.x2 / width, self.y2 / height]

    @classmethod
    def from_normalized(cls, values: list[float], width: int, height: int) -> "Box":
        return cls(values[0] * width, values[1] * height, values[2] * width, values[3] * height)


@dataclass(frozen=True)
class Proposal:
    box: Box
    confidence: float
    appearance: float


def sha256_file(path: Path) -> str:
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


def crop_histogram(image: np.ndarray, box: Box) -> np.ndarray | None:
    height, width = image.shape[:2]
    x1 = max(0, min(width - 1, round(box.x1 + 0.14 * (box.x2 - box.x1))))
    x2 = max(x1 + 1, min(width, round(box.x2 - 0.14 * (box.x2 - box.x1))))
    y1 = max(0, min(height - 1, round(box.y1 + 0.08 * (box.y2 - box.y1))))
    y2 = max(y1 + 1, min(height, round(box.y2 - 0.05 * (box.y2 - box.y1))))
    crop = image[y1:y2, x1:x2]
    if crop.size < 100:
        return None
    hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
    hist = cv2.calcHist([hsv], [0, 1], None, [24, 16], [0, 180, 0, 256])
    cv2.normalize(hist, hist, 0.0, 1.0, cv2.NORM_MINMAX)
    return hist


def similarity(a: np.ndarray | None, b: np.ndarray | None) -> float:
    if a is None or b is None:
        return 0.0
    return float((cv2.compareHist(a, b, cv2.HISTCMP_CORREL) + 1.0) * 0.5)


def extract_frames(video: Path, canonical_fps: float, output_dir: Path) -> tuple[list[np.ndarray], list[dict[str, Any]]]:
    capture = cv2.VideoCapture(str(video))
    source_fps = capture.get(cv2.CAP_PROP_FPS)
    source_count = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
    if source_fps <= 0 or source_count <= 0:
        raise ValueError(f"invalid video metadata: {video}")
    start_indices = np.arange(0.0, source_count / source_fps, 1.0 / canonical_fps) * source_fps
    indices = sorted({min(source_count - 1, round(value)) for value in start_indices})
    frames: list[np.ndarray] = []
    records: list[dict[str, Any]] = []
    frame_dir = output_dir / "frames"
    frame_dir.mkdir(parents=True, exist_ok=True)
    for canonical_index, source_index in enumerate(indices):
        capture.set(cv2.CAP_PROP_POS_FRAMES, source_index)
        ok, image = capture.read()
        if not ok:
            raise RuntimeError(f"could not decode {video.name} source frame {source_index}")
        path = frame_dir / f"{canonical_index:05d}.jpg"
        if not cv2.imwrite(str(path), image, [cv2.IMWRITE_JPEG_QUALITY, 94]):
            raise RuntimeError(f"could not write {path}")
        frames.append(image)
        records.append(
            {
                "canonical_index": canonical_index,
                "source_frame_index": source_index,
                "timestamp_s": round(source_index / source_fps, 6),
                "pixel_sha256": hashlib.sha256(image.tobytes()).hexdigest(),
                "file": str(path.relative_to(output_dir)).replace("\\", "/"),
                "canonical_jpeg_sha256": sha256_file(path),
            }
        )
    capture.release()
    return frames, records


def detect_people(
    frames: list[np.ndarray], model_path: Path, threads: int, image_size: int
) -> list[list[tuple[Box, float]]]:
    if sha256_file(model_path) != YOLO11N_SHA256:
        raise ValueError("YOLO11n bootstrap checkpoint hash mismatch")
    torch.set_num_threads(threads)
    model = YOLO(str(model_path), task="detect")
    result: list[list[tuple[Box, float]]] = []
    for index, frame in enumerate(frames):
        prediction = model.predict(frame, imgsz=image_size, conf=0.03, iou=0.55, classes=[0], device="cpu", verbose=False)[0]
        items: list[tuple[Box, float]] = []
        if prediction.boxes is not None:
            for values, confidence in zip(
                prediction.boxes.xyxy.cpu().numpy(), prediction.boxes.conf.cpu().numpy(), strict=True
            ):
                items.append((Box(*(float(value) for value in values)), float(confidence)))
        result.append(items)
        if (index + 1) % 50 == 0 or index + 1 == len(frames):
            print(f"bootstrap {index + 1}/{len(frames)} frames", flush=True)
    return result


def bootstrap_identity(
    frames: list[np.ndarray],
    records: list[dict[str, Any]],
    detections: list[list[tuple[Box, float]]],
    selection_time_s: float,
    selection_box_norm: list[float],
) -> list[dict[str, Any]]:
    height, width = frames[0].shape[:2]
    selection_index = min(range(len(records)), key=lambda i: abs(records[i]["timestamp_s"] - selection_time_s))
    selection_box = Box.from_normalized(selection_box_norm, width, height)
    candidates = detections[selection_index]
    if not candidates:
        raise RuntimeError("bootstrap detector found no person at the fixed selection frame")
    selected = max(candidates, key=lambda item: iou(item[0], selection_box))[0]
    anchor_hist = crop_histogram(frames[selection_index], selected)

    proposals: list[list[Proposal]] = []
    for frame, frame_detections in zip(frames, detections, strict=True):
        proposals.append(
            [Proposal(box, confidence, similarity(anchor_hist, crop_histogram(frame, box))) for box, confidence in frame_detections]
        )

    # A globally optimized path is used only to bootstrap manual review. The null
    # state is deliberately cheap so uncertainty produces absence, not a switch.
    null_penalty = -0.28
    all_states: list[list[Proposal | None]] = [[None] + items for items in proposals]
    costs: list[np.ndarray] = []
    back: list[np.ndarray] = []
    for frame_index, states in enumerate(all_states):
        emission = np.array(
            [
                null_penalty
                if state is None
                else 1.45 * state.appearance + 0.18 * state.confidence - (0.55 if state.appearance < 0.43 else 0.0)
                for state in states
            ],
            dtype=np.float64,
        )
        if frame_index == selection_index:
            emission[:] = -50.0
            emission[1 + max(range(len(proposals[frame_index])), key=lambda i: iou(proposals[frame_index][i].box, selected))] = 5.0
        if frame_index == 0:
            costs.append(emission)
            back.append(np.full(len(states), -1, dtype=np.int32))
            continue
        previous_states = all_states[frame_index - 1]
        transition = np.full((len(previous_states), len(states)), -2.5, dtype=np.float64)
        diagonal = math.hypot(width, height)
        for p_index, previous in enumerate(previous_states):
            for c_index, current in enumerate(states):
                if previous is None and current is None:
                    transition[p_index, c_index] = 0.0
                elif previous is None or current is None:
                    transition[p_index, c_index] = -0.20
                else:
                    px, py = previous.box.center
                    cx, cy = current.box.center
                    distance = math.hypot(px - cx, py - cy) / diagonal
                    area_ratio = max(previous.box.area, current.box.area) / max(1.0, min(previous.box.area, current.box.area))
                    transition[p_index, c_index] = (
                        0.78 * iou(previous.box, current.box)
                        - 3.2 * distance
                        - 0.18 * abs(math.log(area_ratio))
                    )
        combined = costs[-1][:, None] + transition
        chosen_previous = np.argmax(combined, axis=0)
        costs.append(emission + combined[chosen_previous, np.arange(len(states))])
        back.append(chosen_previous.astype(np.int32))

    # Force the selected anchor, then solve independently in both time directions
    # to prevent the future path from changing the user-selected identity.
    path = [-1] * len(frames)
    selected_state = 1 + max(range(len(proposals[selection_index])), key=lambda i: iou(proposals[selection_index][i].box, selected))
    path[selection_index] = selected_state
    state = selected_state
    for frame_index in range(selection_index, 0, -1):
        state = int(back[frame_index][state])
        path[frame_index - 1] = state
    # Forward Viterbi beginning at the fixed selected state.
    previous_scores = np.full(len(all_states[selection_index]), -1e9)
    previous_scores[selected_state] = 0.0
    forward_back: list[np.ndarray] = []
    for frame_index in range(selection_index + 1, len(frames)):
        previous_states, states = all_states[frame_index - 1], all_states[frame_index]
        transition = np.full((len(previous_states), len(states)), -2.5, dtype=np.float64)
        diagonal = math.hypot(width, height)
        for p_index, previous in enumerate(previous_states):
            for c_index, current in enumerate(states):
                if previous is None and current is None:
                    transition[p_index, c_index] = 0.0
                elif previous is None or current is None:
                    transition[p_index, c_index] = -0.20
                else:
                    distance = math.dist(previous.box.center, current.box.center) / diagonal
                    area_ratio = max(previous.box.area, current.box.area) / max(1.0, min(previous.box.area, current.box.area))
                    transition[p_index, c_index] = 0.78 * iou(previous.box, current.box) - 3.2 * distance - 0.18 * abs(math.log(area_ratio))
        emission = np.array(
            [
                null_penalty
                if item is None
                else 1.45 * item.appearance
                + 0.18 * item.confidence
                - (0.55 if item.appearance < 0.43 else 0.0)
                for item in states
            ],
            dtype=np.float64,
        )
        combined = previous_scores[:, None] + transition
        predecessor = np.argmax(combined, axis=0).astype(np.int32)
        forward_back.append(predecessor)
        previous_scores = emission + combined[predecessor, np.arange(len(states))]
    if forward_back:
        state = int(np.argmax(previous_scores))
        path[-1] = state
        for offset in range(len(forward_back) - 1, -1, -1):
            frame_index = selection_index + 1 + offset
            path[frame_index] = state
            state = int(forward_back[offset][state])

    annotations: list[dict[str, Any]] = []
    previous_box: Box | None = None
    for frame_index, state_index in enumerate(path):
        chosen = all_states[frame_index][state_index] if state_index >= 0 else None
        competitors = [proposal for proposal in proposals[frame_index] if chosen is None or proposal is not chosen]
        ambiguity = False
        reasons: list[str] = []
        if chosen is not None:
            nearest_margin = chosen.appearance - max((item.appearance for item in competitors), default=0.0)
            if nearest_margin < 0.08 and len(competitors) > 0:
                ambiguity = True
                reasons.append("small appearance margin")
            if chosen.appearance < 0.52:
                ambiguity = True
                reasons.append("weak anchor appearance")
            if previous_box is not None and math.dist(previous_box.center, chosen.box.center) / math.hypot(width, height) > 0.12:
                ambiguity = True
                reasons.append("large target jump")
            previous_box = chosen.box
        elif records[frame_index]["timestamp_s"] >= selection_time_s:
            ambiguity = True
            reasons.append("target absent or detector miss")
        annotations.append(
            {
                "canonical_index": frame_index,
                "timestamp_s": records[frame_index]["timestamp_s"],
                "target_visible": chosen is not None,
                "target_box_norm": None if chosen is None else chosen.box.normalized(width, height),
                "bootstrap_confidence": None if chosen is None else round(chosen.confidence, 6),
                "bootstrap_appearance": None if chosen is None else round(chosen.appearance, 6),
                "needs_review": ambiguity,
                "review_reasons": reasons,
                "reviewed_identity": False,
            }
        )
    return annotations


def draw_review_sheets(
    output_dir: Path,
    video_id: str,
    frames: list[np.ndarray],
    annotations: list[dict[str, Any]],
    detections: list[list[tuple[Box, float]]],
) -> None:
    cells: list[np.ndarray] = []
    for frame, annotation, frame_detections in zip(frames, annotations, detections, strict=True):
        cell = cv2.resize(frame, (256, 144), interpolation=cv2.INTER_AREA)
        sx, sy = 256 / frame.shape[1], 144 / frame.shape[0]
        for box, confidence in frame_detections:
            x1, y1, x2, y2 = round(box.x1 * sx), round(box.y1 * sy), round(box.x2 * sx), round(box.y2 * sy)
            cv2.rectangle(cell, (x1, y1), (x2, y2), (0, 160, 255), 1)
            cv2.putText(cell, f"{confidence:.2f}", (x1, max(10, y1 - 2)), cv2.FONT_HERSHEY_SIMPLEX, 0.28, (0, 160, 255), 1)
        values = annotation["target_box_norm"]
        if values is not None:
            target = Box.from_normalized(values, 256, 144)
            x1, y1, x2, y2 = map(round, (target.x1, target.y1, target.x2, target.y2))
            color = (0, 0, 255) if annotation["needs_review"] else (0, 255, 0)
            cv2.rectangle(cell, (x1, y1), (x2, y2), color, 2)
        color = (0, 0, 255) if annotation["needs_review"] else (255, 255, 255)
        cv2.putText(cell, f"#{annotation['canonical_index']} {annotation['timestamp_s']:.1f}s", (4, 13), cv2.FONT_HERSHEY_SIMPLEX, 0.35, color, 1)
        cells.append(cell)
    review_dir = output_dir / "review"
    review_dir.mkdir(parents=True, exist_ok=True)
    page_size = 40
    blank = np.zeros((144, 256, 3), dtype=np.uint8)
    for page, start in enumerate(range(0, len(cells), page_size)):
        page_cells = cells[start : start + page_size]
        page_cells.extend([blank] * (page_size - len(page_cells)))
        rows = [cv2.hconcat(page_cells[index : index + 5]) for index in range(0, page_size, 5)]
        cv2.imwrite(str(review_dir / f"{video_id}_{page:02d}.jpg"), cv2.vconcat(rows), [cv2.IMWRITE_JPEG_QUALITY, 92])


def draw_focused_review_sheets(
    output_dir: Path,
    video_id: str,
    frames: list[np.ndarray],
    annotations: list[dict[str, Any]],
    detections: list[list[tuple[Box, float]]],
) -> None:
    """Render larger all-frame sheets for independent identity/visibility review."""
    cells: list[np.ndarray] = []
    cell_width, cell_height = 384, 216
    for frame, annotation, frame_detections in zip(frames, annotations, detections, strict=True):
        cell = cv2.resize(frame, (cell_width, cell_height), interpolation=cv2.INTER_AREA)
        sx, sy = cell_width / frame.shape[1], cell_height / frame.shape[0]
        for box, confidence in frame_detections:
            x1, y1, x2, y2 = round(box.x1 * sx), round(box.y1 * sy), round(box.x2 * sx), round(box.y2 * sy)
            cv2.rectangle(cell, (x1, y1), (x2, y2), (0, 145, 210), 1)
            cv2.putText(cell, f"{confidence:.2f}", (x1, max(12, y1 - 2)), cv2.FONT_HERSHEY_SIMPLEX, 0.32, (0, 145, 210), 1)
        values = annotation["target_box_norm"]
        if values is not None:
            target = Box.from_normalized(values, cell_width, cell_height)
            cv2.rectangle(
                cell,
                (round(target.x1), round(target.y1)),
                (round(target.x2), round(target.y2)),
                (255, 255, 0),
                3,
            )
        cv2.rectangle(cell, (0, 0), (cell_width, 29), (0, 0, 0), -1)
        status = "manual" if annotation.get("manual_correction") else "reviewed"
        cv2.putText(
            cell,
            f"#{annotation['canonical_index']} {annotation['timestamp_s']:.1f}s {status}",
            (5, 20),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.48,
            (255, 255, 255),
            1,
            cv2.LINE_AA,
        )
        cells.append(cell)
    review_dir = output_dir / "review_focused"
    review_dir.mkdir(parents=True, exist_ok=True)
    page_size = 25
    blank = np.zeros((cell_height, cell_width, 3), dtype=np.uint8)
    for page, start in enumerate(range(0, len(cells), page_size)):
        page_cells = cells[start : start + page_size]
        page_cells.extend([blank] * (page_size - len(page_cells)))
        rows = [cv2.hconcat(page_cells[index : index + 5]) for index in range(0, page_size, 5)]
        cv2.imwrite(
            str(review_dir / f"{video_id}_{page:02d}.jpg"),
            cv2.vconcat(rows),
            [cv2.IMWRITE_JPEG_QUALITY, 92],
        )


def apply_completed_review(
    annotations: list[dict[str, Any]],
    invisible_ranges: list[list[int]],
    box_overrides: dict[str, list[float]] | None = None,
    fixed_appearance_detection_ranges: list[list[int]] | None = None,
    frames: list[np.ndarray] | None = None,
    detections: list[list[tuple[Box, float]]] | None = None,
    anchor_hist: np.ndarray | None = None,
) -> list[dict[str, Any]]:
    """Apply the fixed decisions made from the generated all-frame sheets."""
    invisible = {
        index
        for first, last in invisible_ranges
        for index in range(first, last + 1)
    }
    result: list[dict[str, Any]] = []
    box_overrides = box_overrides or {}
    fixed_appearance = {
        index
        for first, last in (fixed_appearance_detection_ranges or [])
        for index in range(first, last + 1)
    }
    for annotation in annotations:
        item = dict(annotation)
        item["bootstrap_needs_review"] = item.pop("needs_review")
        item["bootstrap_review_reasons"] = item.pop("review_reasons")
        if item["canonical_index"] in invisible:
            item["target_visible"] = False
            item["target_box_norm"] = None
            item["manual_correction"] = "target verified not visible"
        elif str(item["canonical_index"]) in box_overrides:
            item["target_visible"] = True
            item["target_box_norm"] = box_overrides[str(item["canonical_index"])]
            item["manual_correction"] = "identity-switching bootstrap box replaced by reviewed visual bounds"
        elif item["canonical_index"] in fixed_appearance:
            if frames is None or detections is None or anchor_hist is None:
                raise ValueError("fixed-appearance correction requires frames, detections, and anchor")
            frame_index = item["canonical_index"]
            candidates = detections[frame_index]
            if not candidates:
                raise ValueError(f"no detection for reviewed identity correction at frame {frame_index}")
            chosen, confidence = max(
                candidates,
                key=lambda candidate: similarity(
                    anchor_hist, crop_histogram(frames[frame_index], candidate[0])
                ),
            )
            height, width = frames[frame_index].shape[:2]
            item["target_visible"] = True
            item["target_box_norm"] = chosen.normalized(width, height)
            item["bootstrap_confidence"] = round(confidence, 6)
            item["bootstrap_appearance"] = round(
                similarity(anchor_hist, crop_histogram(frames[frame_index], chosen)), 6
            )
            item["manual_correction"] = (
                "reviewed crossing span replaced by highest fixed-target appearance proposal"
            )
        else:
            item["manual_correction"] = None
        item["reviewed_identity"] = True
        item["annotation_source"] = "high-resolution proposal with all-frame identity review"
        result.append(item)
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--video-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--spec", type=Path, default=LAB_DIR / "benchmark_spec.json")
    parser.add_argument("--yolo-model", type=Path, default=CACHE_DIR / "yolo11n.pt")
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--bootstrap-image-size", type=int, default=960)
    parser.add_argument(
        "--video-id",
        action="append",
        help="prepare only the named video; preserves other entries in an existing output manifest",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    spec = json.loads(args.spec.read_text(encoding="utf-8"))
    args.output_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = args.output_dir / "manifest.json"
    if args.video_id and manifest_path.is_file():
        benchmark_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if benchmark_manifest["canonical_fps"] != spec["canonical_fps"]:
            raise ValueError("existing manifest canonical FPS does not match the spec")
        selected_ids = set(args.video_id)
        benchmark_manifest["videos"] = [
            item for item in benchmark_manifest["videos"] if item["id"] not in selected_ids
        ]
    else:
        benchmark_manifest = {
            "schema_version": 1,
            "canonical_fps": spec["canonical_fps"],
            "videos": [],
        }
    requested = set(args.video_id or [])
    known_ids = {item["id"] for item in spec["videos"]}
    unknown_ids = requested - known_ids
    if unknown_ids:
        raise ValueError(f"unknown video ids: {sorted(unknown_ids)}")
    for video_spec in spec["videos"]:
        if requested and video_spec["id"] not in requested:
            continue
        video = args.video_dir / video_spec["filename"]
        actual_hash = sha256_file(video)
        if actual_hash.lower() != video_spec["sha256"].lower():
            raise ValueError(f"source hash mismatch for {video}")
        video_output = args.output_dir / video_spec["id"]
        frames, records = extract_frames(video, spec["canonical_fps"], video_output)
        print(f"preparing {video_spec['id']} ({len(frames)} canonical frames)", flush=True)
        detections = detect_people(
            frames, args.yolo_model, args.threads, args.bootstrap_image_size
        )
        annotations = bootstrap_identity(
            frames,
            records,
            detections,
            video_spec["selection_time_s"],
            video_spec["selection_box_norm"],
        )
        (video_output / "annotations.bootstrap.json").write_text(json.dumps(annotations, indent=2) + "\n", encoding="utf-8")
        height, width = frames[0].shape[:2]
        selection_index = min(
            range(len(records)),
            key=lambda index: abs(records[index]["timestamp_s"] - video_spec["selection_time_s"]),
        )
        selection_box = Box.from_normalized(video_spec["selection_box_norm"], width, height)
        anchor_box = max(
            detections[selection_index], key=lambda item: iou(item[0], selection_box)
        )[0]
        anchor_hist = crop_histogram(frames[selection_index], anchor_box)
        draw_review_sheets(video_output, video_spec["id"], frames, annotations, detections)
        annotations = apply_completed_review(
            annotations,
            video_spec.get("manual_invisible_canonical_ranges", []),
            video_spec.get("manual_box_overrides_norm", {}),
            video_spec.get("manual_fixed_appearance_detection_ranges", []),
            frames,
            detections,
            anchor_hist,
        )
        annotation_path = video_output / "annotations.json"
        annotation_path.write_text(json.dumps(annotations, indent=2) + "\n", encoding="utf-8")
        draw_focused_review_sheets(
            video_output, video_spec["id"], frames, annotations, detections
        )
        benchmark_manifest["videos"].append(
            {
                "id": video_spec["id"],
                "filename": video_spec["filename"],
                "source_sha256": actual_hash,
                "source_width": frames[0].shape[1],
                "source_height": frames[0].shape[0],
                "frame_count": len(frames),
                "selection_time_s": video_spec["selection_time_s"],
                "target_description": video_spec["target_description"],
                "tuning_intervals_s": video_spec["tuning_intervals_s"],
                "held_out_intervals_s": video_spec["held_out_intervals_s"],
                "annotations_file": str(annotation_path.relative_to(args.output_dir)).replace("\\", "/"),
                "annotations_sha256": sha256_file(annotation_path),
                "frames": records,
            }
        )
    spec_order = {item["id"]: index for index, item in enumerate(spec["videos"])}
    benchmark_manifest["videos"].sort(key=lambda item: spec_order[item["id"]])
    manifest_path.write_text(json.dumps(benchmark_manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output_dir": str(args.output_dir.resolve()), "videos": [{"id": item["id"], "frames": item["frame_count"]} for item in benchmark_manifest["videos"]]}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
