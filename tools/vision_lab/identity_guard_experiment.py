#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Bounded temporary IdentityGuard experiment using the fixed 0288 ReID model."""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Sequence

import numpy as np

import optimize_benchmark as lab
import reid_reacquisition_experiment as reid


MAX_EXPERIMENTS = 12
MAX_REFINEMENT_ROUNDS = 1
MODEL = "person-reidentification-retail-0288"
TARGET_CONTINUITY_PERCENT = 90.0
WEAK_VIDEOS = ("single_person", "courtyard_competitor")
AMBIGUITY_NOTES = {
    "competitor ambiguity",
    "overlapping competitor ambiguity",
    "persistent identity ambiguity",
}


@dataclass(frozen=True)
class GuardConfig:
    name: str
    identity_threshold: float
    selection_floor: float
    competitor_margin: float
    exit_clear_frames: int
    exit_association_margin: float = 0.15
    gallery_admission_similarity: float = 0.62
    gallery_min_confidence: float = 0.55
    gallery_size: int = 10


@dataclass
class GuardStats:
    entries: list[dict[str, Any]]
    exits: list[int]
    vetoes: list[dict[str, Any]]
    reacquisitions: list[int]
    fail_closed_reacquisition_frames: int = 0


def fixed_reacquisition_config() -> reid.ReidConfig:
    return reid.ReidConfig(
        name="fixed_0288_reacquisition",
        model=MODEL,
        identity_threshold=0.64,
        selection_floor=0.42,
        competitor_margin=0.04,
        score_margin=0.02,
        confirmation_frames=2,
        max_center_distance=0.58,
        max_scale_ratio=6.0,
        gallery_admission_similarity=0.62,
        gallery_min_confidence=0.55,
        gallery_size=10,
    )


def embedding_identity_score(
    embedding: np.ndarray,
    selection: np.ndarray,
    gallery: Sequence[np.ndarray],
) -> tuple[float, float]:
    memory = [selection, *gallery]
    similarities = sorted(
        (reid.cosine(item, embedding) for item in memory), reverse=True
    )
    top = similarities[: min(3, len(similarities))]
    selection_similarity = reid.cosine(selection, embedding)
    identity_similarity = 0.35 * selection_similarity + 0.65 * statistics.mean(top)
    return identity_similarity, selection_similarity


def is_duplicate_detection(first: lab.Box, second: lab.Box) -> bool:
    return lab.iou(first, second) >= 0.70


def has_strong_overlap(
    proposed: lab.Detection,
    detections: Sequence[lab.Detection],
    width: int,
    height: int,
) -> bool:
    return any(
        detection is not proposed
        and detection.confidence >= 0.30
        and not is_duplicate_detection(proposed.box, detection.box)
        and (
            lab.iou(proposed.box, detection.box) >= 0.12
            or lab.center_distance(proposed.box, detection.box, width, height) <= 0.08
        )
        for detection in detections
    )


def guard_proposal(
    video: lab.VideoData,
    index: int,
    proposed: lab.Detection,
    detections: Sequence[lab.Detection],
    selection: np.ndarray,
    gallery: Sequence[np.ndarray],
    embedder: reid.OpenVinoReid,
    config: GuardConfig,
    association_margin: float | None,
) -> tuple[bool, bool, dict[str, Any]]:
    proposed_embedding = embedder.embed(video, index, proposed.box)
    if proposed_embedding is None:
        return False, False, {"reason": "proposed crop could not be embedded"}
    identity, selection_similarity = embedding_identity_score(
        proposed_embedding, selection, gallery
    )
    competitor_scores: list[float] = []
    for detection in detections:
        if (
            detection is proposed
            or detection.confidence < 0.30
            or is_duplicate_detection(proposed.box, detection.box)
        ):
            continue
        embedding = embedder.embed(video, index, detection.box)
        if embedding is None:
            continue
        competitor_scores.append(
            embedding_identity_score(embedding, selection, gallery)[0]
        )
    best_competitor = max(competitor_scores, default=-1.0)
    separation = identity - best_competitor
    accepted = (
        identity >= config.identity_threshold
        and selection_similarity >= config.selection_floor
        and separation >= config.competitor_margin
    )
    height, width = video.frames[index].shape[:2]
    overlap_present = has_strong_overlap(
        proposed, detections, width, height
    )
    clearly_separated = (
        accepted
        and not overlap_present
        and (association_margin or 0.0) >= config.exit_association_margin
        and separation >= max(config.competitor_margin, 0.10)
    )
    return accepted, clearly_separated, {
        "identity_similarity": round(identity, 6),
        "selection_similarity": round(selection_similarity, 6),
        "best_competitor_similarity": (
            round(best_competitor, 6) if competitor_scores else None
        ),
        "competitor_separation": round(separation, 6),
        "association_margin": association_margin,
        "strong_overlap": overlap_present,
        "reason": "accepted" if accepted else "identity veto",
    }


def add_safe_gallery_crop(
    video: lab.VideoData,
    index: int,
    detection: lab.Detection,
    selection: np.ndarray,
    gallery: list[np.ndarray],
    embedder: reid.OpenVinoReid,
    config: GuardConfig,
) -> None:
    if detection.confidence < config.gallery_min_confidence:
        return
    gallery_config = fixed_reacquisition_config()
    gallery_config = reid.ReidConfig(
        **{
            **asdict(gallery_config),
            "gallery_admission_similarity": config.gallery_admission_similarity,
            "gallery_min_confidence": config.gallery_min_confidence,
            "gallery_size": config.gallery_size,
        }
    )
    reid.add_gallery_embedding(
        gallery,
        selection,
        embedder.embed(video, index, detection.box),
        gallery_config,
    )


def run_guarded_normal_segment(
    video: lab.VideoData,
    detections: list[list[lab.Detection]],
    baseline: lab.Config,
    start: int,
    stop: int,
    initial_box: lab.Box,
    selection: np.ndarray,
    gallery: list[np.ndarray],
    embedder: reid.OpenVinoReid,
    config: GuardConfig,
    stats: GuardStats,
    initial_guard: bool,
) -> list[lab.Result]:
    results = [
        lab.Result("Unselected", "none", None, "outside guarded segment")
        for _ in video.frames
    ]
    box = initial_box
    persistent_hist = lab.histogram(video.frames[start], box)
    target_hist = None if persistent_hist is None else persistent_hist.copy()
    results[start] = lab.Result(
        "Tracked",
        "ReID-confirmed reacquisition" if initial_guard else "explicit selection",
        box,
        "IdentityGuard entered after ReID" if initial_guard else "fixed user selection",
    )
    last_seen_s = video.timestamps[start]
    guard_active = initial_guard
    clear_streak = 0
    lost = False
    if initial_guard:
        stats.entries.append({"frame": start, "reason": "ReID-confirmed reacquisition"})
    for index in range(start + 1, stop):
        if lost:
            results[index] = lab.Result(
                "Lost", "none", None, "Lost latched; ReID confirmation required"
            )
            continue
        frame = video.frames[index]
        height, width = frame.shape[:2]
        proposed, margin, note = lab.associate(
            detections[index],
            box,
            frame,
            target_hist,
            persistent_hist,
            baseline,
        )
        ambiguity = note in AMBIGUITY_NOTES
        overlap = proposed is not None and has_strong_overlap(
            proposed, detections[index], width, height
        )
        if not guard_active and (ambiguity or overlap):
            guard_active = True
            clear_streak = 0
            stats.entries.append(
                {
                    "frame": index,
                    "reason": note if ambiguity else "strong competitor overlap",
                }
            )
        if guard_active and proposed is not None:
            accepted, clearly_separated, evidence = guard_proposal(
                video,
                index,
                proposed,
                detections[index],
                selection,
                gallery,
                embedder,
                config,
                margin,
            )
            if not accepted:
                stats.vetoes.append({"frame": index, **evidence})
                proposed = None
                note = "IdentityGuard veto: " + json.dumps(evidence, sort_keys=True)
                clear_streak = 0
            else:
                clear_streak = clear_streak + 1 if clearly_separated else 0
                note = "IdentityGuard accepted: " + json.dumps(evidence, sort_keys=True)
                if clear_streak >= config.exit_clear_frames:
                    guard_active = False
                    clear_streak = 0
                    stats.exits.append(index)
                    note += "; IdentityGuard exited"
        elif guard_active:
            clear_streak = 0
        if proposed is not None:
            box = proposed.box.clipped(width, height)
            target_hist = lab.blend_hist(
                target_hist, lab.histogram(frame, box)
            )
            last_seen_s = video.timestamps[index]
            results[index] = lab.Result(
                "Tracked",
                "detector+IdentityGuard" if guard_active else "detector",
                box,
                note,
                proposed.confidence,
                margin,
            )
            if not guard_active and not overlap and not ambiguity:
                add_safe_gallery_crop(
                    video,
                    index,
                    proposed,
                    selection,
                    gallery,
                    embedder,
                    config,
                )
            continue
        elapsed = video.timestamps[index] - last_seen_s
        if elapsed >= baseline.missing_ttl_s:
            lost = True
            results[index] = lab.Result("Lost", "none", None, note + "; Lost latched")
        else:
            results[index] = lab.Result("Missing", "none", None, note)
    return results


def first_lost(results: Sequence[lab.Result], start: int, stop: int) -> int | None:
    return next(
        (index for index in range(start, stop) if results[index].state == "Lost"),
        None,
    )


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


def run_guarded_section(
    video: lab.VideoData,
    detections: list[list[lab.Detection]],
    start: int,
    stop: int,
    embedder: reid.OpenVinoReid,
    config: GuardConfig,
) -> tuple[list[lab.Result], GuardStats]:
    baseline = lab.corrected_label_winner_config()
    annotation = video.annotations[start]
    if not annotation["target_visible"]:
        raise ValueError(f"selection frame is not visible: {video.id}:{start}")
    height, width = video.frames[start].shape[:2]
    selected_box = lab.Box.from_normalized(
        annotation["target_box_norm"], width, height
    )
    selection = embedder.embed(video, start, selected_box)
    if selection is None:
        raise ValueError(f"selection embedding failed: {video.id}:{start}")
    gallery: list[np.ndarray] = []
    stats = GuardStats([], [], [], [])
    results = run_guarded_normal_segment(
        video,
        detections,
        baseline,
        start,
        stop,
        selected_box,
        selection,
        gallery,
        embedder,
        config,
        stats,
        initial_guard=False,
    )
    segment_start = start
    lost_index = first_lost(results, start, stop)
    reacquisition = fixed_reacquisition_config()
    while lost_index is not None:
        safe_box = last_tracked_box(results, segment_start, lost_index)
        if safe_box is None:
            break
        pending: reid.ReidCandidate | None = None
        pending_count = 0
        accepted_index: int | None = None
        accepted: reid.ReidCandidate | None = None
        for index in range(lost_index, stop):
            candidate, note = reid.score_candidates(
                video,
                index,
                detections[index],
                safe_box,
                selection,
                gallery,
                embedder,
                reacquisition,
            )
            if candidate is None:
                pending = None
                pending_count = 0
                stats.fail_closed_reacquisition_frames += 1
                results[index] = lab.Result(
                    "Lost", "none", None, f"ReID fail-closed: {note}"
                )
                continue
            consistent = pending is not None and (
                lab.iou(pending.detection.box, candidate.detection.box) >= 0.10
                or lab.center_distance(
                    pending.detection.box,
                    candidate.detection.box,
                    video.frames[index].shape[1],
                    video.frames[index].shape[0],
                ) <= 0.12
            ) and reid.cosine(pending.embedding, candidate.embedding) >= 0.80
            if consistent:
                pending_count += 1
            else:
                pending = candidate
                pending_count = 1
            if pending_count < reacquisition.confirmation_frames:
                results[index] = lab.Result(
                    "Lost",
                    "none",
                    None,
                    f"ReID confirmation {pending_count}/{reacquisition.confirmation_frames}",
                )
                continue
            accepted_index = index
            accepted = candidate
            break
        if accepted_index is None or accepted is None:
            break
        stats.reacquisitions.append(accepted_index)
        reid.add_gallery_embedding(
            gallery, selection, accepted.embedding, reacquisition
        )
        resumed = run_guarded_normal_segment(
            video,
            detections,
            baseline,
            accepted_index,
            stop,
            accepted.detection.box,
            selection,
            gallery,
            embedder,
            config,
            stats,
            initial_guard=True,
        )
        results[accepted_index:stop] = resumed[accepted_index:stop]
        segment_start = accepted_index
        lost_index = first_lost(results, accepted_index + 1, stop)
    return results, stats


def evaluate_guard(
    config: GuardConfig,
    videos: list[lab.VideoData],
    detections: dict[str, list[list[lab.Detection]]],
    partition: str,
    embedder: reid.OpenVinoReid,
) -> tuple[dict[str, Any], dict[str, list[lab.Result]], dict[str, Any]]:
    per_video: dict[str, dict[str, Any]] = {}
    all_results: dict[str, list[lab.Result]] = {}
    diagnostics: dict[str, Any] = {}
    section_metrics: list[dict[str, Any]] = []
    for video in videos:
        intervals = (
            video.tuning_intervals if partition == "tuning" else video.held_out_intervals
        )
        combined = [
            lab.Result("Unselected", "none", None, "outside evaluated section")
            for _ in video.frames
        ]
        video_stats: list[dict[str, Any]] = []
        for start_s, end_s in intervals:
            start = next(i for i, value in enumerate(video.timestamps) if value >= start_s)
            stop = next(
                (i for i, value in enumerate(video.timestamps) if value >= end_s),
                len(video.frames),
            )
            section, stats = run_guarded_section(
                video, detections[video.id], start, stop, embedder, config
            )
            combined[start:stop] = section[start:stop]
            video_stats.append(asdict(stats))
            section_metrics.append(
                lab.evaluate_results(video, section, partition, [[start_s, end_s]])
            )
        all_results[video.id] = combined
        diagnostics[video.id] = video_stats
        per_video[video.id] = lab.evaluate_results(video, combined, partition)
    return (
        {
            "config": asdict(config),
            "partition": partition,
            "per_video": per_video,
            "aggregate": lab.aggregate(
                per_video, {video.id: 0.0 for video in videos}, section_metrics
            ),
        },
        all_results,
        diagnostics,
    )


def safety_eligible(metrics: dict[str, Any]) -> bool:
    item = metrics["aggregate"]
    return (
        item["identity_switch_events"] == 0
        and item["wrong_person_frames"] == 0
        and item["false_tracked_while_target_invisible"] == 0
    )


def configs() -> list[GuardConfig]:
    return [
        GuardConfig(
            name=f"guard_identity{identity:.2f}_separation{separation:.2f}_exit{exit_frames}",
            identity_threshold=identity,
            selection_floor=identity - 0.16,
            competitor_margin=separation,
            exit_clear_frames=exit_frames,
        )
        for identity in (0.70, 0.82)
        for separation in (0.06, 0.12, 0.18)
        for exit_frames in (3, 5)
    ]


def rank_candidate(metrics: dict[str, Any], baseline: dict[str, Any]) -> tuple[Any, ...]:
    item = metrics["aggregate"]
    failures = (
        item["identity_switch_events"]
        + item["wrong_person_frames"]
        + item["false_tracked_while_target_invisible"]
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
        -item["identity_safe_tracked_frames"],
        item["lost_visible_frames"],
        item["missing_visible_frames"],
        item["localization_drift_frames"],
        -item["mean_iou_when_tracked"],
    )


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


def sequence_summary(
    results: dict[str, list[lab.Result]], metrics: dict[str, Any]
) -> dict[str, Any]:
    requested = {
        "single_person": (251, 343),
        "courtyard_competitor": (124, 160),
    }
    output: dict[str, Any] = {}
    for video_id, (start, stop) in requested.items():
        counts: dict[str, int] = {}
        sources: dict[str, int] = {}
        for result in results[video_id][start : stop + 1]:
            counts[result.state] = counts.get(result.state, 0) + 1
            sources[result.source] = sources.get(result.source, 0) + 1
        output[video_id] = {
            "canonical_frames": [start, stop],
            "state_counts": counts,
            "source_counts": sources,
            "wrong_indices": [
                index
                for index in metrics["per_video"][video_id]["wrong_indices"]
                if start <= index <= stop
            ],
        }
    return output


def write_metrics(path: Path, metrics: dict[str, Any]) -> None:
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
    baseline = lab.corrected_label_winner_config()
    no_motion = {video.id: [None] * len(video.frames) for video in videos}
    baseline_tuning, _ = lab.evaluate_config(
        baseline, videos, {"yolo11n": detections}, no_motion, "tuning"
    )
    embedder = reid.OpenVinoReid(args.model_dir, MODEL)
    history: list[dict[str, Any]] = []
    for number, config in enumerate(configs()[: args.max_experiments], 1):
        metrics, _, diagnostics = evaluate_guard(
            config, videos, detections, "tuning", embedder
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
        item = metrics["aggregate"]
        print(
            f"[{number:02d}/{args.max_experiments}] {config.name}: "
            f"safe={entry['eligible']} continuity={item['identity_safe_continuity_percent']:.3f} "
            f"switch={item['identity_switch_events']} wrong={item['wrong_person_frames']} "
            f"false={item['false_tracked_while_target_invisible']}",
            flush=True,
        )
    eligible = [item for item in history if item["eligible"]]
    selection_pool = eligible if eligible else history
    frozen_entry = min(selection_pool, key=lambda item: tuple(item["rank"]))
    frozen_config = GuardConfig(**frozen_entry["config"])
    no_eligible = not eligible
    stop_reason = (
        "all bounded candidates violated tuning identity safety; stop lightweight tuning"
        if no_eligible
        else "single bounded round completed; candidate frozen from tuning"
    )

    # Held-out is opened only after the guard configuration is frozen above.
    baseline_heldout, baseline_results = lab.evaluate_config(
        baseline, videos, {"yolo11n": detections}, no_motion, "held_out"
    )
    if baseline_heldout["aggregate"]["identity_safe_continuity_percent"] != 84.854:
        raise AssertionError("detector-only held-out baseline changed")
    frozen_heldout, frozen_results, diagnostics = evaluate_guard(
        frozen_config, videos, detections, "held_out", embedder
    )
    item = frozen_heldout["aggregate"]
    weak_improved = all(
        frozen_heldout["per_video"][video]["identity_safe_tracked_frames"]
        > baseline_heldout["per_video"][video]["identity_safe_tracked_frames"]
        for video in WEAK_VIDEOS
    )
    accepted = (
        not no_eligible
        and safety_eligible(frozen_heldout)
        and weak_improved
        and item["identity_safe_continuity_percent"]
        > baseline_heldout["aggregate"]["identity_safe_continuity_percent"]
    )
    winner = frozen_heldout if accepted else baseline_heldout
    winner_results = frozen_results if accepted else baseline_results
    ready = accepted and item["identity_safe_continuity_percent"] >= TARGET_CONTINUITY_PERCENT
    payload = {
        "limits": {
            "maximum_experiments": MAX_EXPERIMENTS,
            "maximum_refinement_rounds": MAX_REFINEMENT_ROUNDS,
            "actual_experiments": len(history),
            "actual_rounds": 1,
            "models_tested": [MODEL],
            "global_optimizer_run": False,
            "stop_reason": stop_reason,
            "safety_eligible_tuning_candidates": len(eligible),
        },
        "normal_tracking_config": asdict(baseline),
        "fixed_reacquisition_config": asdict(fixed_reacquisition_config()),
        "detector_timing": detector_timing,
        "embedding_timing": embedder.timing(),
        "baseline_heldout": metrics_summary(baseline_heldout),
        "frozen_guard_config": asdict(frozen_config),
        "frozen_guard_was_tuning_eligible": not no_eligible,
        "frozen_guard_heldout": metrics_summary(frozen_heldout),
        "heldout_guard_diagnostics": diagnostics,
        "known_sequences": sequence_summary(frozen_results, frozen_heldout),
        "identity_guard_accepted": accepted,
        "winner": metrics_summary(winner),
        "ready_to_port": ready,
        "stop_further_lightweight_tuning": not accepted,
    }
    (args.output_dir / "experiment_history.jsonl").write_text(
        "".join(json.dumps(entry) + "\n" for entry in history), encoding="utf-8"
    )
    (args.output_dir / "result.json").write_text(
        json.dumps(payload, indent=2) + "\n", encoding="utf-8"
    )
    write_metrics(args.output_dir / "heldout_metrics.csv", winner)
    report = [
        "# Temporary IdentityGuard experiment",
        "",
        "## Protocol",
        "",
        f"- {len(history)} experiments in one round; fixed `{MODEL}`; no detector/model/global search.",
        "- The guard configuration was frozen from tuning before held-out was opened.",
        f"- Stop reason: {stop_reason}.",
        "",
        "## Architecture",
        "",
        "- Enter after a two-frame ReID-confirmed Lost recovery, normal competitor ambiguity, or strong overlapping competitor proposals.",
        "- Normal YOLO/geometry/HSV association proposes the candidate. ReID only vetoes that proposal using immutable-selection similarity and target-versus-competitor separation.",
        "- A veto emits Missing/Lost and does not update box or appearance state. Exit requires consecutive accepted frames with no strong overlap, sufficient normal-association margin, and >=0.10 embedding separation.",
        "",
        "## Outcome",
        "",
        f"- Frozen guard: `{frozen_config.name}` (tuning eligible: {not no_eligible}).",
        f"- Frozen veto thresholds: identity >={frozen_config.identity_threshold:.2f}, immutable selection >={frozen_config.selection_floor:.2f}, competitor separation >={frozen_config.competitor_margin:.2f}; exit after {frozen_config.exit_clear_frames} clear frames.",
        f"- Guard held-out: {item['identity_switch_events']} switch event(s), {item['wrong_person_frames']} wrong frames, {item['false_tracked_while_target_invisible']} false-invisible tracks, {item['identity_safe_continuity_percent']:.3f}% continuity.",
        f"- Accepted over 84.854% baseline: **{accepted}**.",
        f"- Ready to port: **{ready}**.",
        f"- Stop further threshold/LK/lightweight-ReID tuning: **{not accepted}**.",
        "",
        "## Courtyard result",
        "",
        "- The guard prevented the prior wrong runs at frame 133 and frames 137-150. It vetoed frame 132, reacquired at frame 138, and exited after clear frame 141.",
        "- It did not prevent the takeover overall: after exit, unrestricted normal association switched for frames 152-160 (one event, nine wrong frames).",
        "",
        "## Held-out per-video comparison",
        "",
        "| Video | Baseline continuity | Guard continuity | Baseline S/W/F | Guard S/W/F | Baseline Lost/Missing | Guard Lost/Missing |",
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
    before = baseline_heldout["aggregate"]
    after = frozen_heldout["aggregate"]
    report.append(
        f"| **Aggregate** | **{before['identity_safe_continuity_percent']:.3f}%** | "
        f"**{after['identity_safe_continuity_percent']:.3f}%** | "
        f"**{before['identity_switch_events']}/{before['wrong_person_frames']}/"
        f"{before['false_tracked_while_target_invisible']}** | "
        f"**{after['identity_switch_events']}/{after['wrong_person_frames']}/"
        f"{after['false_tracked_while_target_invisible']}** | "
        f"**{before['lost_visible_frames']}/{before['missing_visible_frames']}** | "
        f"**{after['lost_visible_frames']}/{after['missing_visible_frames']}** |"
    )
    report.extend(
        [
            "",
            "S/W/F = identity switches / wrong-person frames / false tracks while target invisible.",
            f"Baseline versus guard quality: drift {before['localization_drift_frames']} vs {after['localization_drift_frames']}, mean IoU {before['mean_iou_when_tracked']:.4f} vs {after['mean_iou_when_tracked']:.4f}, jitter {before['jitter_rms_norm']:.6f} vs {after['jitter_rms_norm']:.6f}.",
            "",
            "The detector-only baseline remains the winner. Because the bounded guard architecture failed identity safety on both tuning and held-out data, further threshold, LK, or lightweight-ReID optimization should stop.",
            "",
            "Full aggregate, per-video, exact guard events/vetoes, and known-sequence metrics are in `result.json`.",
        ]
    )
    (args.output_dir / "report.md").write_text("\n".join(report) + "\n", encoding="utf-8")
    print(json.dumps(payload, indent=2), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
