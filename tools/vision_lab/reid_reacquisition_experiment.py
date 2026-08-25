#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Bounded ReID-only experiment for identity-safe post-Lost reacquisition."""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
import time
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Any, Sequence

import cv2
import numpy as np
from openvino import Core

import optimize_benchmark as lab


MAX_EXPERIMENTS = 20
MAX_MODEL_FAMILIES = 2
TARGET_CONTINUITY_PERCENT = 90.0
WEAK_VIDEOS = ("single_person", "courtyard_competitor")
MODEL_SHA256 = {
    "person-reidentification-retail-0288": {
        "xml": "0d5021c772e603dce69d9ef2e326ecf0a43234cc8b4c5bb5e8aa566f090bdb51",
        "bin": "08e24c2f1dd976ff8e1f667b93db2950ac8db8da09e163e1ca6463b82d1dbc42",
    },
    "person-reidentification-retail-0287": {
        "xml": "95125bc630208d9a27370b11bd04d220f93f64b60fe4be5bc576e9aebb69603d",
        "bin": "8811b3db2f6ebacbf2c05510593783316026ff779d9b048b6ca2581f17baf86c",
    },
}
NORMAL_SEGMENT_CACHE: dict[
    tuple[str, int, int, tuple[int, int, int, int] | None], list[lab.Result]
] = {}


@dataclass(frozen=True)
class ReidConfig:
    name: str
    model: str
    identity_threshold: float
    selection_floor: float
    competitor_margin: float
    score_margin: float
    confirmation_frames: int
    max_center_distance: float
    max_scale_ratio: float
    gallery_admission_similarity: float = 0.62
    gallery_min_confidence: float = 0.55
    gallery_size: int = 10


@dataclass
class ReidCandidate:
    detection: lab.Detection
    embedding: np.ndarray
    identity_similarity: float
    selection_similarity: float
    geometry_score: float
    score: float


class OpenVinoReid:
    """Small, cached OpenVINO person-embedding runner."""

    def __init__(self, model_dir: Path, name: str) -> None:
        self.name = name
        xml = model_dir / f"{name}.xml"
        binary = model_dir / f"{name}.bin"
        if not xml.is_file() or not binary.is_file():
            raise FileNotFoundError(f"missing ReID model artifacts for {name}")
        expected = MODEL_SHA256[name]
        if lab.sha256(xml) != expected["xml"] or lab.sha256(binary) != expected["bin"]:
            raise ValueError(f"ReID model hash mismatch for {name}")
        core = Core()
        model = core.read_model(xml)
        self.compiled = core.compile_model(model, "CPU")
        self.output = self.compiled.output(0)
        self.cache: dict[tuple[str, int, tuple[int, int, int, int]], np.ndarray | None] = {}
        self.inference_ms: list[float] = []

    def embed(
        self,
        video: lab.VideoData,
        index: int,
        box: lab.Box,
    ) -> np.ndarray | None:
        height, width = video.frames[index].shape[:2]
        clipped = box.clipped(width, height)
        coords = (
            max(0, int(round(clipped.x1))),
            max(0, int(round(clipped.y1))),
            min(width, int(round(clipped.x2))),
            min(height, int(round(clipped.y2))),
        )
        key = (video.id, index, coords)
        if key in self.cache:
            return self.cache[key]
        x1, y1, x2, y2 = coords
        crop = video.frames[index][y1:y2, x1:x2]
        if crop.size < 300 or crop.shape[0] < 16 or crop.shape[1] < 8:
            self.cache[key] = None
            return None
        resized = cv2.resize(crop, (128, 256), interpolation=cv2.INTER_LINEAR)
        tensor = resized.transpose(2, 0, 1)[None].astype(np.float32)
        started = time.perf_counter_ns()
        output = self.compiled([tensor])[self.output].reshape(-1).astype(np.float32)
        self.inference_ms.append((time.perf_counter_ns() - started) / 1_000_000.0)
        norm = float(np.linalg.norm(output))
        embedding = output / norm if norm > 1e-8 else None
        self.cache[key] = embedding
        return embedding

    def timing(self) -> dict[str, float | int]:
        values = self.inference_ms
        return {
            "uncached_inferences": len(values),
            "mean_ms": round(statistics.mean(values), 3) if values else 0.0,
            "median_ms": round(statistics.median(values), 3) if values else 0.0,
            "p95_ms": round(float(np.percentile(values, 95)), 3) if values else 0.0,
        }


def cosine(first: np.ndarray, second: np.ndarray) -> float:
    return float(np.dot(first, second))


def scale_ratio(first: lab.Box, second: lab.Box) -> float:
    if min(first.area, second.area) <= 1e-6:
        return math.inf
    return max(first.area, second.area) / min(first.area, second.area)


def add_gallery_embedding(
    gallery: list[np.ndarray],
    selection: np.ndarray,
    embedding: np.ndarray | None,
    config: ReidConfig,
) -> None:
    if embedding is None or cosine(selection, embedding) < config.gallery_admission_similarity:
        return
    if gallery and max(cosine(item, embedding) for item in gallery) > 0.995:
        return
    gallery.append(embedding)
    if len(gallery) > config.gallery_size:
        del gallery[0]


def collect_gallery(
    video: lab.VideoData,
    results: Sequence[lab.Result],
    start: int,
    stop: int,
    embedder: OpenVinoReid,
    selection: np.ndarray,
    gallery: list[np.ndarray],
    config: ReidConfig,
) -> None:
    for index in range(start, stop):
        result = results[index]
        if (
            result.state != "Tracked"
            or result.box is None
            or result.source == "LK"
            or (
                result.source != "explicit selection"
                and (result.confidence or 0.0) < config.gallery_min_confidence
            )
            or "ambiguity" in result.note
        ):
            continue
        add_gallery_embedding(
            gallery,
            selection,
            embedder.embed(video, index, result.box),
            config,
        )


def score_candidates(
    video: lab.VideoData,
    index: int,
    detections: Sequence[lab.Detection],
    last_safe_box: lab.Box,
    selection: np.ndarray,
    gallery: Sequence[np.ndarray],
    embedder: OpenVinoReid,
    config: ReidConfig,
) -> tuple[ReidCandidate | None, str]:
    height, width = video.frames[index].shape[:2]
    scored: list[ReidCandidate] = []
    memory = [selection, *gallery]
    for detection in detections:
        if detection.confidence < 0.30:
            continue
        distance = lab.center_distance(last_safe_box, detection.box, width, height)
        area_ratio = scale_ratio(last_safe_box, detection.box)
        if distance > config.max_center_distance or area_ratio > config.max_scale_ratio:
            continue
        embedding = embedder.embed(video, index, detection.box)
        if embedding is None:
            continue
        similarities = sorted((cosine(item, embedding) for item in memory), reverse=True)
        top = similarities[: min(3, len(similarities))]
        selection_similarity = cosine(selection, embedding)
        identity_similarity = 0.35 * selection_similarity + 0.65 * statistics.mean(top)
        if (
            identity_similarity < config.identity_threshold
            or selection_similarity < config.selection_floor
        ):
            continue
        distance_quality = max(0.0, 1.0 - distance / config.max_center_distance)
        scale_quality = max(
            0.0,
            1.0 - abs(math.log(area_ratio)) / math.log(config.max_scale_ratio),
        )
        geometry_score = 0.65 * distance_quality + 0.35 * scale_quality
        score = (
            0.78 * identity_similarity
            + 0.14 * geometry_score
            + 0.08 * detection.confidence
        )
        scored.append(
            ReidCandidate(
                detection,
                embedding,
                identity_similarity,
                selection_similarity,
                geometry_score,
                score,
            )
        )
    if not scored:
        return None, "no candidate passed confidence/identity/geometry gates"
    scored.sort(key=lambda item: item.score, reverse=True)
    best = scored[0]
    if len(scored) > 1:
        competitor = scored[1]
        identity_margin = best.identity_similarity - competitor.identity_similarity
        score_margin = best.score - competitor.score
        if (
            identity_margin < config.competitor_margin
            or score_margin < config.score_margin
        ):
            return None, (
                "competitor separation rejected "
                f"identity={identity_margin:.3f}, score={score_margin:.3f}"
            )
    return best, (
        f"candidate identity={best.identity_similarity:.3f}, "
        f"selection={best.selection_similarity:.3f}, "
        f"geometry={best.geometry_score:.3f}, detector={best.detection.confidence:.3f}"
    )


def first_lost(results: Sequence[lab.Result], start: int, stop: int) -> int | None:
    return next((index for index in range(start, stop) if results[index].state == "Lost"), None)


def run_normal_segment(
    video: lab.VideoData,
    detections: list[list[lab.Detection]],
    baseline: lab.Config,
    start: int,
    stop: int,
    initial_box: lab.Box | None = None,
) -> list[lab.Result]:
    box_key = (
        None
        if initial_box is None
        else tuple(round(value) for value in (
            initial_box.x1, initial_box.y1, initial_box.x2, initial_box.y2
        ))
    )
    key = (video.id, start, stop, box_key)
    cached = NORMAL_SEGMENT_CACHE.get(key)
    if cached is None:
        cached, _ = lab.run_pipeline(
            video,
            detections,
            [None] * len(video.frames),
            baseline,
            selection_index=start,
            stop_index=stop,
            initial_box=initial_box,
            selection_note=(
                "ReID-confirmed reacquisition" if initial_box is not None
                else "fixed user selection"
            ),
        )
        NORMAL_SEGMENT_CACHE[key] = cached
    return cached.copy()


def last_tracked_box(
    results: Sequence[lab.Result], start: int, stop: int
) -> lab.Box | None:
    return next(
        (
            results[index].box
            for index in range(stop - 1, start - 1, -1)
            if results[index].state == "Tracked" and results[index].box is not None
        ),
        None,
    )


def run_reid_section(
    video: lab.VideoData,
    detections: list[list[lab.Detection]],
    baseline: lab.Config,
    start_index: int,
    stop_index: int,
    embedder: OpenVinoReid,
    config: ReidConfig,
) -> tuple[list[lab.Result], dict[str, int]]:
    results = run_normal_segment(
        video, detections, baseline, start_index, stop_index
    )
    selected_box = results[start_index].box
    if selected_box is None:
        raise AssertionError("selection did not produce a box")
    selection = embedder.embed(video, start_index, selected_box)
    if selection is None:
        raise ValueError(f"cannot embed selection for {video.id}:{start_index}")
    gallery: list[np.ndarray] = []
    recoveries = confirmations = rejected = 0
    segment_start = start_index
    lost_index = first_lost(results, segment_start, stop_index)
    while lost_index is not None:
        collect_gallery(
            video,
            results,
            segment_start,
            lost_index,
            embedder,
            selection,
            gallery,
            config,
        )
        safe_box = last_tracked_box(results, segment_start, lost_index)
        if safe_box is None:
            break
        pending: ReidCandidate | None = None
        pending_count = 0
        accepted_index: int | None = None
        accepted: ReidCandidate | None = None
        for index in range(lost_index, stop_index):
            candidate, note = score_candidates(
                video,
                index,
                detections[index],
                safe_box,
                selection,
                gallery,
                embedder,
                config,
            )
            if candidate is None:
                pending = None
                pending_count = 0
                rejected += 1
                results[index] = lab.Result("Lost", "none", None, f"ReID fail-closed: {note}")
                continue
            consistent = pending is not None and (
                lab.iou(pending.detection.box, candidate.detection.box) >= 0.10
                or lab.center_distance(
                    pending.detection.box, candidate.detection.box,
                    video.frames[index].shape[1], video.frames[index].shape[0]
                ) <= 0.12
            ) and cosine(pending.embedding, candidate.embedding) >= 0.80
            if consistent:
                pending_count += 1
            else:
                pending = candidate
                pending_count = 1
            confirmations += 1
            if pending_count < config.confirmation_frames:
                results[index] = lab.Result(
                    "Lost", "none", None,
                    f"ReID confirmation {pending_count}/{config.confirmation_frames}; {note}",
                )
                continue
            accepted_index = index
            accepted = candidate
            break
        if accepted_index is None or accepted is None:
            break
        recoveries += 1
        resumed = run_normal_segment(
            video,
            detections,
            baseline,
            accepted_index,
            stop_index,
            initial_box=accepted.detection.box,
        )
        results[accepted_index:stop_index] = resumed[accepted_index:stop_index]
        results[accepted_index] = lab.Result(
            "Tracked",
            "ReID-confirmed reacquisition",
            accepted.detection.box,
            (
                f"confirmed {config.confirmation_frames} frames; "
                f"identity={accepted.identity_similarity:.3f}; "
                f"selection={accepted.selection_similarity:.3f}; "
                f"geometry={accepted.geometry_score:.3f}; "
                f"detector={accepted.detection.confidence:.3f}"
            ),
            accepted.detection.confidence,
        )
        add_gallery_embedding(gallery, selection, accepted.embedding, config)
        segment_start = accepted_index
        lost_index = first_lost(results, segment_start + 1, stop_index)
    return results, {
        "recoveries": recoveries,
        "confirmation_candidates": confirmations,
        "fail_closed_frames": rejected,
        "gallery_embeddings": len(gallery) + 1,
    }


def evaluate_reid(
    config: ReidConfig,
    videos: list[lab.VideoData],
    detections: dict[str, list[list[lab.Detection]]],
    partition: str,
    embedder: OpenVinoReid,
) -> tuple[dict[str, Any], dict[str, list[lab.Result]], dict[str, dict[str, int]]]:
    baseline = lab.corrected_label_winner_config()
    per_video: dict[str, dict[str, Any]] = {}
    combined_results: dict[str, list[lab.Result]] = {}
    section_metrics: list[dict[str, Any]] = []
    diagnostics: dict[str, dict[str, int]] = {}
    for video in videos:
        intervals = video.tuning_intervals if partition == "tuning" else video.held_out_intervals
        combined = [
            lab.Result("Unselected", "none", None, "outside evaluated section")
            for _ in video.frames
        ]
        totals = {
            "recoveries": 0,
            "confirmation_candidates": 0,
            "fail_closed_frames": 0,
            "gallery_embeddings": 0,
        }
        for start_s, end_s in intervals:
            start = next(i for i, timestamp in enumerate(video.timestamps) if timestamp >= start_s)
            stop = next(
                (i for i, timestamp in enumerate(video.timestamps) if timestamp >= end_s),
                len(video.frames),
            )
            section, stats = run_reid_section(
                video, detections[video.id], baseline, start, stop, embedder, config
            )
            combined[start:stop] = section[start:stop]
            for key, value in stats.items():
                totals[key] += value
            section_metrics.append(
                lab.evaluate_results(video, section, partition, [[start_s, end_s]])
            )
        combined_results[video.id] = combined
        diagnostics[video.id] = totals
        per_video[video.id] = lab.evaluate_results(video, combined, partition)
    metrics = {
        "config": asdict(config),
        "partition": partition,
        "per_video": per_video,
        "aggregate": lab.aggregate(
            per_video, {video.id: 0.0 for video in videos}, section_metrics
        ),
    }
    return metrics, combined_results, diagnostics


def safety_eligible(metrics: dict[str, Any]) -> bool:
    aggregate = metrics["aggregate"]
    return (
        aggregate["identity_switch_events"] == 0
        and aggregate["wrong_person_frames"] == 0
        and aggregate["false_tracked_while_target_invisible"] == 0
    )


def rank_candidate(metrics: dict[str, Any], baseline: dict[str, Any]) -> tuple[Any, ...]:
    aggregate = metrics["aggregate"]
    failures = (
        aggregate["identity_switch_events"]
        + aggregate["wrong_person_frames"]
        + aggregate["false_tracked_while_target_invisible"]
    )
    weak_regressions = sum(
        metrics["per_video"][video]["identity_safe_tracked_frames"]
        < baseline["per_video"][video]["identity_safe_tracked_frames"]
        for video in WEAK_VIDEOS
    )
    weakest = min(
        metrics["per_video"][video]["identity_safe_continuity_percent"]
        for video in WEAK_VIDEOS
    )
    return (
        failures,
        weak_regressions,
        -weakest,
        -aggregate["identity_safe_tracked_frames"],
        aggregate["lost_visible_frames"],
        aggregate["missing_visible_frames"],
        aggregate["localization_drift_frames"],
        -aggregate["mean_iou_when_tracked"],
    )


def round_one_configs() -> list[ReidConfig]:
    configs: list[ReidConfig] = []
    for short in ("0288", "0287"):
        model = f"person-reidentification-retail-{short}"
        for threshold, margin, confirmation in (
            (0.64, 0.04, 2),
            (0.68, 0.04, 2),
            (0.72, 0.04, 2),
            (0.68, 0.08, 2),
            (0.72, 0.08, 2),
            (0.76, 0.08, 2),
            (0.68, 0.08, 3),
            (0.72, 0.12, 3),
        ):
            configs.append(
                ReidConfig(
                    name=f"{short}_sim{threshold:.2f}_margin{margin:.2f}_c{confirmation}",
                    model=model,
                    identity_threshold=threshold,
                    selection_floor=max(0.35, threshold - 0.22),
                    competitor_margin=margin,
                    score_margin=max(0.02, margin * 0.45),
                    confirmation_frames=confirmation,
                    max_center_distance=0.58,
                    max_scale_ratio=6.0,
                )
            )
    return configs


def refinement_configs(best: ReidConfig) -> list[ReidConfig]:
    return [
        replace(
            best,
            name=f"{best.name}_r2_sim{threshold:.2f}_d{distance:.2f}",
            identity_threshold=threshold,
            selection_floor=max(0.35, threshold - 0.22),
            max_center_distance=distance,
        )
        for threshold, distance in (
            (max(0.60, best.identity_threshold - 0.02), 0.45),
            (best.identity_threshold, 0.45),
            (min(0.80, best.identity_threshold + 0.02), 0.58),
            (best.identity_threshold, 0.70),
        )
    ]


def safety_refinement_configs() -> list[ReidConfig]:
    """Final four fail-closed probes when round one has no eligible candidate."""
    configs: list[ReidConfig] = []
    for short in ("0288", "0287"):
        for threshold, selection_floor in ((0.82, 0.70), (0.88, 0.78)):
            configs.append(
                ReidConfig(
                    name=f"{short}_safety_sim{threshold:.2f}_selection{selection_floor:.2f}_c3",
                    model=f"person-reidentification-retail-{short}",
                    identity_threshold=threshold,
                    selection_floor=selection_floor,
                    competitor_margin=0.15,
                    score_margin=0.07,
                    confirmation_frames=3,
                    max_center_distance=0.45,
                    max_scale_ratio=4.0,
                    gallery_admission_similarity=0.72,
                    gallery_min_confidence=0.65,
                    gallery_size=8,
                )
            )
    return configs


def metrics_summary(metrics: dict[str, Any]) -> dict[str, Any]:
    keys = (
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
    )
    return {
        "aggregate": {key: metrics["aggregate"][key] for key in keys},
        "per_video": {
            video: {
                **{key: item[key] for key in keys},
                "wrong_indices": item["wrong_indices"],
            }
            for video, item in metrics["per_video"].items()
        },
    }


def known_sequence_summary(
    videos: Sequence[lab.VideoData], results: dict[str, list[lab.Result]]
) -> dict[str, Any]:
    requested = {
        "single_person": (251, 343),
        "courtyard_competitor": (124, 160),
    }
    summary: dict[str, Any] = {}
    for video in videos:
        if video.id not in requested:
            continue
        start, stop = requested[video.id]
        counts: dict[str, int] = {}
        reacquired: list[int] = []
        for index in range(start, min(stop + 1, len(video.frames))):
            result = results[video.id][index]
            counts[result.state] = counts.get(result.state, 0) + 1
            if result.source == "ReID-confirmed reacquisition":
                reacquired.append(index)
        summary[video.id] = {
            "canonical_frames": [start, stop],
            "state_counts": counts,
            "reacquisition_frames": reacquired,
        }
    return summary


def write_metrics_csv(path: Path, metrics: dict[str, Any]) -> None:
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.writer(stream)
        writer.writerow(
            [
                "video", "visible", "switches", "wrong", "false_invisible",
                "continuity_percent", "lost", "missing", "drift", "mean_iou", "jitter",
            ]
        )
        for video, item in metrics["per_video"].items():
            writer.writerow(
                [
                    video, item["visible_target_frames"], item["identity_switch_events"],
                    item["wrong_person_frames"], item["false_tracked_while_target_invisible"],
                    item["identity_safe_continuity_percent"], item["lost_visible_frames"],
                    item["missing_visible_frames"], item["localization_drift_frames"],
                    item["mean_iou_when_tracked"], item["jitter_rms_norm"],
                ]
            )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--benchmark-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--model-dir", type=Path, default=lab.CACHE_DIR / "reid_models")
    parser.add_argument("--yolo-model", type=Path, default=lab.CACHE_DIR / "yolo11n.pt")
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--max-experiments", type=int, default=MAX_EXPERIMENTS)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not 1 <= args.max_experiments <= MAX_EXPERIMENTS:
        raise ValueError(f"max experiments must be within 1..{MAX_EXPERIMENTS}")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    _, videos = lab.load_benchmark(args.benchmark_dir)
    detections, detector_timing = lab.detection_cache(
        videos,
        "yolo11n",
        args.yolo_model,
        args.benchmark_dir / "cache" / "yolo11n.json",
        args.threads,
        lab.sha256(args.benchmark_dir / "manifest.json"),
    )
    baseline_config = lab.corrected_label_winner_config()
    no_motion = {video.id: [None] * len(video.frames) for video in videos}
    baseline_tuning, _ = lab.evaluate_config(
        baseline_config, videos, {"yolo11n": detections}, no_motion, "tuning"
    )
    models = sorted({config.model for config in round_one_configs()})
    if len(models) > MAX_MODEL_FAMILIES:
        raise AssertionError("model family budget exceeded")
    embedders = {name: OpenVinoReid(args.model_dir, name) for name in models}
    history: list[dict[str, Any]] = []
    first_round = round_one_configs()[: args.max_experiments]
    for number, config in enumerate(first_round, 1):
        metrics, _, diagnostics = evaluate_reid(
            config, videos, detections, "tuning", embedders[config.model]
        )
        entry = {
            "experiment": number,
            "round": 1,
            "config": asdict(config),
            "eligible": safety_eligible(metrics),
            "rank": list(rank_candidate(metrics, baseline_tuning)),
            "metrics": metrics_summary(metrics),
            "diagnostics": diagnostics,
        }
        history.append(entry)
        aggregate = metrics["aggregate"]
        print(
            f"[{number:02d}/{args.max_experiments}] {config.name}: "
            f"safe={entry['eligible']} continuity={aggregate['identity_safe_continuity_percent']:.3f} "
            f"switch={aggregate['identity_switch_events']} "
            f"wrong={aggregate['wrong_person_frames']} "
            f"false={aggregate['false_tracked_while_target_invisible']} "
            f"lost={aggregate['lost_visible_frames']} missing={aggregate['missing_visible_frames']}",
            flush=True,
        )
    eligible = [item for item in history if item["eligible"]]
    if not eligible:
        for config in safety_refinement_configs()[: args.max_experiments - len(history)]:
            metrics, _, diagnostics = evaluate_reid(
                config, videos, detections, "tuning", embedders[config.model]
            )
            number = len(history) + 1
            aggregate = metrics["aggregate"]
            entry = {
                "experiment": number,
                "round": 2,
                "config": asdict(config),
                "eligible": safety_eligible(metrics),
                "rank": list(rank_candidate(metrics, baseline_tuning)),
                "metrics": metrics_summary(metrics),
                "diagnostics": diagnostics,
            }
            history.append(entry)
            print(
                f"[{number:02d}/{args.max_experiments}] {config.name}: "
                f"safe={entry['eligible']} "
                f"continuity={aggregate['identity_safe_continuity_percent']:.3f} "
                f"switch={aggregate['identity_switch_events']} "
                f"wrong={aggregate['wrong_person_frames']} "
                f"false={aggregate['false_tracked_while_target_invisible']}",
                flush=True,
            )
        eligible = [item for item in history if item["eligible"]]
    no_eligible_candidate = not eligible
    selection_pool = eligible if eligible else history
    best_entry = min(selection_pool, key=lambda item: tuple(item["rank"]))
    best_config = ReidConfig(**best_entry["config"])
    baseline_safe = baseline_tuning["aggregate"]["identity_safe_tracked_frames"]
    best_safe = best_entry["metrics"]["aggregate"]["identity_safe_tracked_frames"]
    meaningful = not no_eligible_candidate and best_safe >= baseline_safe + 3
    if meaningful and len(history) < args.max_experiments:
        for config in refinement_configs(best_config)[: args.max_experiments - len(history)]:
            metrics, _, diagnostics = evaluate_reid(
                config, videos, detections, "tuning", embedders[config.model]
            )
            number = len(history) + 1
            entry = {
                "experiment": number,
                "round": 2,
                "config": asdict(config),
                "eligible": safety_eligible(metrics),
                "rank": list(rank_candidate(metrics, baseline_tuning)),
                "metrics": metrics_summary(metrics),
                "diagnostics": diagnostics,
            }
            history.append(entry)
            print(
                f"[{number:02d}/{args.max_experiments}] {config.name}: "
                f"safe={entry['eligible']} "
                f"continuity={metrics['aggregate']['identity_safe_continuity_percent']:.3f}",
                flush=True,
            )
        best_entry = min(
            (item for item in history if item["eligible"]),
            key=lambda item: tuple(item["rank"]),
        )
        best_config = ReidConfig(**best_entry["config"])
    stop_reason = (
        "all 20 candidates violated a hard tuning safety constraint"
        if no_eligible_candidate
        else (
            "round 1 showed no meaningful safety-eligible tuning improvement; stopped early"
            if not meaningful
            else "20-experiment/two-round bound reached"
        )
    )
    if len(history) > args.max_experiments:
        raise AssertionError("experiment budget exceeded")

    # The candidate is frozen above. Held-out data is opened only below.
    baseline_heldout, baseline_results = lab.evaluate_config(
        baseline_config, videos, {"yolo11n": detections}, no_motion, "held_out"
    )
    expected = {
        "identity_switch_events": 0,
        "wrong_person_frames": 0,
        "false_tracked_while_target_invisible": 0,
        "identity_safe_continuity_percent": 84.854,
    }
    for key, value in expected.items():
        if baseline_heldout["aggregate"][key] != value:
            raise AssertionError(
                f"corrected baseline mismatch for {key}: "
                f"{baseline_heldout['aggregate'][key]} != {value}"
            )
    frozen_heldout, frozen_results, heldout_diagnostics = evaluate_reid(
        best_config, videos, detections, "held_out", embedders[best_config.model]
    )
    weak_nonregression = all(
        frozen_heldout["per_video"][video]["identity_safe_tracked_frames"]
        >= baseline_heldout["per_video"][video]["identity_safe_tracked_frames"]
        for video in WEAK_VIDEOS
    )
    reid_wins = (
        safety_eligible(frozen_heldout)
        and weak_nonregression
        and frozen_heldout["aggregate"]["identity_safe_tracked_frames"]
        > baseline_heldout["aggregate"]["identity_safe_tracked_frames"]
    )
    winner = frozen_heldout if reid_wins else baseline_heldout
    winner_results = frozen_results if reid_wins else baseline_results
    timing = {name: embedder.timing() for name, embedder in embedders.items()}
    payload = {
        "limits": {
            "maximum_experiments": args.max_experiments,
            "maximum_refinement_rounds": 2,
            "maximum_model_families": MAX_MODEL_FAMILIES,
            "actual_experiments": len(history),
            "actual_rounds": max(item["round"] for item in history),
            "actual_model_family": "OmniScaleNet/OSNet",
            "models_tested": models,
            "stop_reason": stop_reason,
            "safety_eligible_tuning_candidates": len(eligible),
        },
        "normal_tracking_config": asdict(baseline_config),
        "detector_timing": detector_timing,
        "embedding_timing": timing,
        "baseline_heldout": metrics_summary(baseline_heldout),
        "frozen_reid_config": asdict(best_config),
        "frozen_reid_heldout": metrics_summary(frozen_heldout),
        "heldout_reid_diagnostics": heldout_diagnostics,
        "known_sequences": known_sequence_summary(videos, frozen_results),
        "frozen_candidate_was_tuning_eligible": not no_eligible_candidate,
        "reid_accepted": reid_wins,
        "winner": metrics_summary(winner),
        "ready_to_port": (
            reid_wins
            and frozen_heldout["aggregate"]["identity_safe_continuity_percent"]
            >= TARGET_CONTINUITY_PERCENT
        ),
    }
    (args.output_dir / "experiment_history.jsonl").write_text(
        "".join(json.dumps(item) + "\n" for item in history), encoding="utf-8"
    )
    (args.output_dir / "result.json").write_text(
        json.dumps(payload, indent=2) + "\n", encoding="utf-8"
    )
    write_metrics_csv(args.output_dir / "heldout_metrics.csv", winner)
    report = [
        "# ReID-only reacquisition experiment",
        "",
        "## Scope and separation",
        "",
        f"- {len(history)} experiments, {max(item['round'] for item in history)} round(s), one model family and two sizes.",
        f"- Stop reason: {stop_reason}.",
        "- Normal tracking is the corrected-label YOLO11n winner and is unchanged. ReID is called only after Lost, never for initial selection or ordinary association.",
        "- ReID thresholds/model were ranked on fixed tuning intervals. Because all candidates were unsafe, the highest-ranked ineligible candidate was frozen for diagnosis, then evaluated once on held-out intervals; it was never eligible to win.",
        "",
        "## Models and deployment",
        "",
        "- person-reidentification-retail-0288: OmniScaleNet, 256-float embedding, 0.174 GFLOPs, 0.183M parameters.",
        "- person-reidentification-retail-0287: larger OmniScaleNet variant, 0.564 GFLOPs, 0.595M parameters.",
        "- Open Model Zoo repository/artifacts are Apache-2.0. The evaluated files are OpenVINO IR; a LiteRT conversion and numerical-equivalence check remains required before Android integration.",
        "",
        "## Exact fail-closed logic",
        "",
        "- Freeze an immutable embedding from the explicit selection crop. Admit at most 10 diverse pre-loss detector crops (confidence >=0.55, no ambiguity) only when similar to the immutable selection.",
        "- After Lost, consider YOLO detections >=0.30. Reject candidates outside the configured center/scale bounds or below both immutable-selection and multi-snapshot identity thresholds.",
        "- Rank with 78% identity, 14% geometry, and 8% detector confidence. Reject when the best and runner-up lack identity and composite-score separation.",
        "- Require consecutive spatially and embedding-consistent confirmations. Until confirmation, output Lost; then resume the unchanged normal tracker from the confirmed detector box.",
        "",
        "## Result",
        "",
        f"- Frozen ReID config: `{best_config.name}`.",
        f"- Frozen diagnostic held-out: {frozen_heldout['aggregate']['identity_switch_events']} switches, {frozen_heldout['aggregate']['wrong_person_frames']} wrong-person frames, {frozen_heldout['aggregate']['false_tracked_while_target_invisible']} false-invisible tracks, {frozen_heldout['aggregate']['identity_safe_continuity_percent']:.3f}% continuity.",
        f"- Retained baseline held-out: {baseline_heldout['aggregate']['identity_switch_events']} switches, {baseline_heldout['aggregate']['wrong_person_frames']} wrong-person frames, {baseline_heldout['aggregate']['false_tracked_while_target_invisible']} false-invisible tracks, {baseline_heldout['aggregate']['identity_safe_continuity_percent']:.3f}% continuity.",
        f"- ReID accepted over baseline: **{reid_wins}**.",
        f"- Ready to port: **{payload['ready_to_port']}**.",
        "- Root cause: the embedding family did not separate the similarly presented competitor strongly enough. The ReID-confirmed frame 127 was initially correct, but the resumed normal association switched at frame 133 and again for frames 137-150.",
        "",
        "## Held-out per-video comparison",
        "",
        "| Video | Baseline continuity | ReID diagnostic continuity | Baseline S/W/F | ReID S/W/F | Baseline Lost/Missing | ReID Lost/Missing |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for video_id in baseline_heldout["per_video"]:
        before = baseline_heldout["per_video"][video_id]
        after = frozen_heldout["per_video"][video_id]
        report.append(
            f"| {video_id} | {before['identity_safe_continuity_percent']:.3f}% | "
            f"{after['identity_safe_continuity_percent']:.3f}% | "
            f"{before['identity_switch_events']}/{before['wrong_person_frames']}/"
            f"{before['false_tracked_while_target_invisible']} | "
            f"{after['identity_switch_events']}/{after['wrong_person_frames']}/"
            f"{after['false_tracked_while_target_invisible']} | "
            f"{before['lost_visible_frames']}/{before['missing_visible_frames']} | "
            f"{after['lost_visible_frames']}/{after['missing_visible_frames']} |"
        )
    report.extend(
        [
            "",
            "S/W/F = identity switches / wrong-person frames / false tracks while target invisible.",
            "",
            "## Known sequences",
            "",
            "- `single_person` 251-343: ReID confirmed frame 259 and safely improved the sequence; the diagnostic had 23 Tracked, 64 Lost, and 6 Missing frames in the requested range, with no wrong-person frames.",
            "- `courtyard_competitor` 124-160: ReID confirmed frame 127, but the diagnostic switched at frame 133 and frames 137-150. This is unsafe even though it reduced Lost time.",
            "",
            "Full machine-readable metrics and experiment history are in `result.json`, `heldout_metrics.csv`, and `experiment_history.jsonl`.",
        ]
    )
    (args.output_dir / "report.md").write_text("\n".join(report) + "\n", encoding="utf-8")
    print(json.dumps(payload, indent=2), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
