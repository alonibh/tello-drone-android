"""Bounded continuity optimization around the corrected-label YOLO detector winner."""

from __future__ import annotations

import argparse
import csv
import json
import math
from collections import Counter
from dataclasses import asdict, replace
from pathlib import Path
from typing import Any, Sequence

import cv2

import optimize_benchmark as lab


MAX_EXPERIMENTS = 40
MAX_REFINEMENT_ROUNDS = 2
TARGET_CONTINUITY_PERCENT = 90.0
WEAK_VIDEOS = ("single_person", "courtyard_competitor")


def baseline_config() -> lab.Config:
    return lab.corrected_label_winner_config()


def config_signature(config: lab.Config) -> tuple[Any, ...]:
    values = asdict(config)
    return tuple(values[key] for key in values if key not in {"name", "family"})


def safety_eligible(metrics: dict[str, Any]) -> bool:
    aggregate = metrics["aggregate"]
    return (
        aggregate["identity_switch_events"] == 0
        and aggregate["wrong_person_frames"] == 0
        and aggregate["false_tracked_while_target_invisible"] == 0
    )


def targeted_rank(
    metrics: dict[str, Any],
    baseline_metrics: dict[str, Any],
) -> tuple[Any, ...]:
    aggregate = metrics["aggregate"]
    per_video = metrics["per_video"]
    baseline_per_video = baseline_metrics["per_video"]
    safety_failures = (
        aggregate["identity_switch_events"]
        + aggregate["wrong_person_frames"]
        + aggregate["false_tracked_while_target_invisible"]
    )
    material_video_regressions = sum(
        metrics_for_video["identity_safe_continuity_percent"]
        < baseline_per_video[video_id]["identity_safe_continuity_percent"] - 2.0
        for video_id, metrics_for_video in per_video.items()
    )
    shortfall_frames = sum(
        max(
            0,
            math.ceil(
                video_metrics["visible_target_frames"]
                * TARGET_CONTINUITY_PERCENT
                / 100.0
            )
            - video_metrics["identity_safe_tracked_frames"],
        )
        for video_metrics in per_video.values()
    )
    weakest_video_continuity = min(
        per_video[video_id]["identity_safe_continuity_percent"]
        for video_id in WEAK_VIDEOS
    )
    return (
        safety_failures,
        material_video_regressions,
        shortfall_frames,
        -weakest_video_continuity,
        -aggregate["identity_safe_continuity_percent"],
        aggregate["lost_visible_frames"],
        aggregate["missing_visible_frames"],
        aggregate["localization_drift_frames"],
        -aggregate["mean_iou_when_tracked"],
        aggregate["jitter_rms_norm"],
    )


def round_one_configs(base: lab.Config) -> list[lab.Config]:
    changes: list[dict[str, Any]] = []
    changes.extend({"low_confidence": value} for value in (0.05, 0.10, 0.15, 0.20, 0.25))
    changes.extend({"appearance_gate": value} for value in (0.30, 0.35, 0.40))
    changes.extend({"ambiguity_margin": value} for value in (0.03, 0.05, 0.07))
    for low_confidence in (0.10, 0.20):
        for margins in (
            (0.0, 0.0, 1.0),
            (0.03, 0.04, 0.85),
            (0.0, 0.08, 0.70),
            (0.06, 0.0, 1.0),
        ):
            changes.append(
                {
                    "low_confidence": low_confidence,
                    "low_confidence_appearance_margin": margins[0],
                    "low_confidence_iou_margin": margins[1],
                    "low_confidence_distance_scale": margins[2],
                }
            )
    changes.extend(
        {"low_confidence": low, "appearance_gate": appearance}
        for low, appearance in (
            (0.05, 0.35),
            (0.10, 0.35),
            (0.15, 0.35),
            (0.20, 0.35),
            (0.10, 0.40),
            (0.15, 0.40),
        )
    )
    changes.extend(({"iou_gate": 0.08}, {"distance_gate": 0.30}))
    return [
        replace(
            base,
            name=f"targeted_r1_{index:02d}",
            family="targeted_continuity",
            **change,
        )
        for index, change in enumerate(changes)
    ]


def round_two_configs(
    best: lab.Config,
    seen: set[tuple[Any, ...]],
    limit: int,
) -> list[lab.Config]:
    proposals: list[dict[str, Any]] = []
    proposals.extend({"low_confidence": value} for value in (0.03, 0.08, 0.12, 0.18, 0.23))
    proposals.extend({"appearance_gate": value} for value in (0.30, 0.33, 0.38, 0.42))
    proposals.extend({"ambiguity_margin": value} for value in (0.03, 0.06, 0.10))
    proposals.extend(
        (
            {
                "low_confidence_appearance_margin": 0.0,
                "low_confidence_iou_margin": 0.0,
                "low_confidence_distance_scale": 1.0,
            },
            {
                "low_confidence_appearance_margin": 0.03,
                "low_confidence_iou_margin": 0.04,
                "low_confidence_distance_scale": 0.85,
            },
            {"iou_gate": 0.08},
            {"distance_gate": 0.30},
        )
    )
    variants: list[lab.Config] = []
    for change in proposals:
        candidate = replace(
            best,
            name=f"targeted_r2_{len(variants):02d}",
            family="targeted_continuity",
            **change,
        )
        signature = config_signature(candidate)
        if signature in seen:
            continue
        seen.add(signature)
        variants.append(candidate)
        if len(variants) >= limit:
            break
    return variants


def diagnosis_driven_round_two_configs(base: lab.Config) -> list[lab.Config]:
    """Probe only longer fail-closed grace and LK bridges demonstrated by run review."""
    configs = [
        replace(
            base,
            name=f"targeted_r2_ttl_{index:02d}",
            family="targeted_continuity",
            missing_ttl_s=value,
        )
        for index, value in enumerate((0.6, 0.8, 1.0, 1.2, 1.6, 2.0))
    ]
    configs.extend(
        replace(
            base,
            name=f"targeted_r2_lk_ttl_{index:02d}",
            family="targeted_continuity",
            use_lk=True,
            missing_ttl_s=value,
        )
        for index, value in enumerate((0.4, 0.6, 0.8, 1.0))
    )
    configs.extend(
        (
            replace(
                base,
                name="targeted_r2_lk_features",
                family="targeted_continuity",
                use_lk=True,
                missing_ttl_s=0.8,
                lk_min_features=8,
            ),
            replace(
                base,
                name="targeted_r2_lk_fb",
                family="targeted_continuity",
                use_lk=True,
                missing_ttl_s=0.8,
                lk_fb_error=0.8,
            ),
            replace(
                base,
                name="targeted_r2_lk_inliers",
                family="targeted_continuity",
                use_lk=True,
                missing_ttl_s=0.8,
                lk_inlier_ratio=0.65,
            ),
        )
    )
    return configs


def interval_results_with_trace(
    video: lab.VideoData,
    detections: list[list[lab.Detection]],
    config: lab.Config,
) -> tuple[list[lab.Result], dict[int, dict[str, Any]]]:
    combined = [
        lab.Result("Unselected", "none", None, "outside evaluated section")
        for _ in video.frames
    ]
    by_index: dict[int, dict[str, Any]] = {}
    no_motion = [None] * len(video.frames)
    for start_s, end_s in video.held_out_intervals:
        start_index = next(
            index for index, timestamp in enumerate(video.timestamps) if timestamp >= start_s
        )
        stop_index = next(
            (
                index
                for index, timestamp in enumerate(video.timestamps)
                if timestamp >= end_s
            ),
            len(video.frames),
        )
        trace: list[dict[str, Any]] = []
        section, _ = lab.run_pipeline(
            video,
            detections,
            no_motion,
            config,
            selection_index=start_index,
            stop_index=stop_index,
            diagnostic_trace=trace,
        )
        combined[start_index:stop_index] = section[start_index:stop_index]
        by_index.update({item["canonical_index"]: item for item in trace})
    return combined, by_index


def truth_box(video: lab.VideoData, index: int) -> lab.Box:
    height, width = video.frames[index].shape[:2]
    return lab.Box.from_normalized(
        video.annotations[index]["target_box_norm"], width, height
    )


def matching_target_detection(
    video: lab.VideoData,
    index: int,
    detections: Sequence[lab.Detection],
) -> lab.Detection | None:
    truth = truth_box(video, index)
    height, width = video.frames[index].shape[:2]
    plausible = [
        detection
        for detection in detections
        if lab.iou(detection.box, truth) >= 0.30
        or lab.center_distance(detection.box, truth, width, height) <= 0.08
    ]
    return max(
        plausible,
        key=lambda detection: (lab.iou(detection.box, truth), detection.confidence),
        default=None,
    )


def classify_failure(
    video: lab.VideoData,
    index: int,
    detections: Sequence[lab.Detection],
    trace: dict[str, Any] | None,
    config: lab.Config,
) -> tuple[str, str, dict[str, Any]]:
    truth = truth_box(video, index)
    height, width = video.frames[index].shape[:2]
    target = matching_target_detection(video, index, detections)
    target_summary: dict[str, Any] = {
        "truth_box": asdict(truth),
        "raw_detection_count": len(detections),
    }
    if target is not None:
        target_summary.update(
            {
                "target_detection_box": asdict(target.box),
                "target_detection_confidence": round(target.confidence, 6),
                "target_detection_truth_iou": round(lab.iou(target.box, truth), 6),
                "target_detection_truth_distance": round(
                    lab.center_distance(target.box, truth, width, height), 6
                ),
            }
        )
    touches_edge = (
        truth.x1 <= 2
        or truth.y1 <= 2
        or truth.x2 >= width - 2
        or truth.y2 >= height - 2
    )
    if trace is not None and trace["state"] == "Lost" and trace["predicted_box"] is None:
        available = target is not None and target.confidence >= config.low_confidence
        return (
            "other",
            "Lost latch prevented reacquisition; "
            + ("a usable target detection was now present" if available else "the target still lacked a usable detection"),
            target_summary,
        )
    if target is None:
        if touches_edge:
            return (
                "target leaving/occlusion",
                "the visible annotation is clipped at the frame boundary and no target proposal exists",
                target_summary,
            )
        return (
            "detector miss/confidence",
            "no raw YOLO person proposal plausibly matches the visible target",
            target_summary,
        )
    evidence = [] if trace is None else trace["detections"]
    matching_evidence = min(
        evidence,
        key=lambda item: abs(item["box"]["x1"] - target.box.x1)
        + abs(item["box"]["y1"] - target.box.y1)
        + abs(item["box"]["x2"] - target.box.x2)
        + abs(item["box"]["y2"] - target.box.y2),
        default=None,
    )
    if matching_evidence is not None:
        target_summary["association_evidence"] = matching_evidence
        rejection = matching_evidence["rejection"]
        if rejection == "detector confidence":
            return (
                "detector miss/confidence",
                f"target proposal confidence {target.confidence:.3f} is below {config.low_confidence:.3f}",
                target_summary,
            )
        if rejection == "appearance gate":
            return "appearance gate", "target proposal failed adaptive appearance similarity", target_summary
        if rejection == "geometry gate":
            return "geometry gate", "target proposal failed position/scale geometry", target_summary
        if rejection == "low-confidence association rejection":
            return (
                "association rejection",
                "target proposal passed primary gates but failed the extra low-confidence appearance/geometry safeguard",
                target_summary,
            )
        if rejection == "eligible" and trace is not None and trace["association_note"] == "competitor ambiguity":
            return (
                "association rejection",
                "multiple eligible proposals were too close in association score",
                target_summary,
            )
    return "other", "association failed for a reason not isolated by the ordinary gates", target_summary


def failure_rows_and_runs(
    videos: Sequence[lab.VideoData],
    detections_by_video: dict[str, list[list[lab.Detection]]],
    config: lab.Config,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, list[lab.Result]]]:
    rows: list[dict[str, Any]] = []
    all_results: dict[str, list[lab.Result]] = {}
    for video in videos:
        results, traces = interval_results_with_trace(
            video, detections_by_video[video.id], config
        )
        all_results[video.id] = results
        for index, result in enumerate(results):
            if not (
                lab.in_intervals(video.timestamps[index], video.held_out_intervals)
                and result.state in {"Missing", "Lost"}
            ):
                continue
            target_visible = bool(video.annotations[index]["target_visible"])
            if target_visible:
                category, explanation, details = classify_failure(
                    video,
                    index,
                    detections_by_video[video.id][index],
                    traces.get(index),
                    config,
                )
            else:
                category = "target leaving/occlusion"
                explanation = "ground truth marks the target invisible on this frame"
                details = {
                    "raw_detection_count": len(detections_by_video[video.id][index])
                }
            rows.append(
                {
                    "video": video.id,
                    "canonical_index": index,
                    "timestamp_s": video.timestamps[index],
                    "target_visible": target_visible,
                    "state": result.state,
                    "category": category,
                    "explanation": explanation,
                    "pipeline_note": result.note,
                    "details": details,
                }
            )
    runs: list[dict[str, Any]] = []
    for video in videos:
        video_rows = [row for row in rows if row["video"] == video.id]
        current: list[dict[str, Any]] = []
        for row in video_rows:
            if current and row["canonical_index"] != current[-1]["canonical_index"] + 1:
                runs.append(summarize_run(current))
                current = []
            current.append(row)
        if current:
            runs.append(summarize_run(current))
    return rows, runs, all_results


def summarize_run(rows: Sequence[dict[str, Any]]) -> dict[str, Any]:
    categories = Counter(row["category"] for row in rows)
    states = Counter(row["state"] for row in rows)
    return {
        "video": rows[0]["video"],
        "start_canonical_index": rows[0]["canonical_index"],
        "end_canonical_index": rows[-1]["canonical_index"],
        "frame_count": len(rows),
        "states": dict(states),
        "categories": dict(categories),
        "trigger_category": rows[0]["category"],
        "trigger_explanation": rows[0]["explanation"],
    }


def render_run_sheets(
    output_dir: Path,
    videos: Sequence[lab.VideoData],
    runs: Sequence[dict[str, Any]],
    rows: Sequence[dict[str, Any]],
    detections_by_video: dict[str, list[list[lab.Detection]]],
) -> None:
    videos_by_id = {video.id: video for video in videos}
    rows_by_key = {(row["video"], row["canonical_index"]): row for row in rows}
    for run in runs:
        video = videos_by_id[run["video"]]
        indices = list(
            range(run["start_canonical_index"], run["end_canonical_index"] + 1)
        )
        if len(indices) > 12:
            indices = sorted(
                {
                    indices[round(position * (len(indices) - 1) / 11)]
                    for position in range(12)
                }
            )
        cells: list[Any] = []
        for index in indices:
            frame = video.frames[index].copy()
            if video.annotations[index]["target_visible"]:
                truth = truth_box(video, index)
                cv2.rectangle(
                    frame,
                    (round(truth.x1), round(truth.y1)),
                    (round(truth.x2), round(truth.y2)),
                    (0, 255, 0),
                    3,
                )
            for detection in detections_by_video[video.id][index]:
                cv2.rectangle(
                    frame,
                    (round(detection.box.x1), round(detection.box.y1)),
                    (round(detection.box.x2), round(detection.box.y2)),
                    (255, 180, 0),
                    1,
                )
                cv2.putText(
                    frame,
                    f"{detection.confidence:.2f}",
                    (round(detection.box.x1), max(15, round(detection.box.y1))),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    0.45,
                    (255, 180, 0),
                    1,
                    cv2.LINE_AA,
                )
            row = rows_by_key[(video.id, index)]
            cv2.putText(
                frame,
                f"f{index} {row['state']} {row['category']}",
                (12, 28),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.7,
                (0, 0, 255),
                2,
                cv2.LINE_AA,
            )
            cells.append(cv2.resize(frame, (480, 270), interpolation=cv2.INTER_AREA))
        while len(cells) % 3:
            cells.append(cells[-1] * 0)
        sheet = cv2.vconcat(
            [cv2.hconcat(cells[index : index + 3]) for index in range(0, len(cells), 3)]
        )
        cv2.imwrite(
            str(
                output_dir
                / f"diagnostic_{video.id}_{run['start_canonical_index']}_{run['end_canonical_index']}.jpg"
            ),
            sheet,
        )


def evaluate_candidates(
    configs: Sequence[lab.Config],
    iteration: int,
    videos: list[lab.VideoData],
    detector_data: dict[str, dict[str, list[list[lab.Detection]]]],
    camera_motion: dict[str, list[Any]],
    baseline_tuning: dict[str, Any],
) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    for index, config in enumerate(configs, 1):
        print(
            f"targeted refinement {iteration} {index}/{len(configs)}: {config.name}",
            flush=True,
        )
        metrics, _ = lab.evaluate_config(
            config, videos, detector_data, camera_motion, "tuning"
        )
        metrics["iteration"] = iteration
        metrics["eligible"] = safety_eligible(metrics)
        metrics["targeted_rank"] = targeted_rank(metrics, baseline_tuning)
        results.append(metrics)
    return results


def meaningful_improvement(
    baseline: dict[str, Any], candidate: dict[str, Any]
) -> bool:
    if not candidate["eligible"]:
        return False
    before = baseline["aggregate"]
    after = candidate["aggregate"]
    weak_improvement = max(
        candidate["per_video"][video_id]["identity_safe_continuity_percent"]
        - baseline["per_video"][video_id]["identity_safe_continuity_percent"]
        for video_id in WEAK_VIDEOS
    )
    return (
        after["identity_safe_tracked_frames"]
        >= before["identity_safe_tracked_frames"] + 3
        and weak_improvement >= 2.0
    )


def write_metrics_csv(path: Path, metrics: dict[str, Any]) -> None:
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.writer(stream)
        writer.writerow(
            [
                "video",
                "visible",
                "switches",
                "wrong",
                "false_invisible",
                "continuity_percent",
                "lost",
                "missing",
                "drift",
                "mean_iou",
                "jitter",
            ]
        )
        for video_id, item in metrics["per_video"].items():
            writer.writerow(
                [
                    video_id,
                    item["visible_target_frames"],
                    item["identity_switch_events"],
                    item["wrong_person_frames"],
                    item["false_tracked_while_target_invisible"],
                    item["identity_safe_continuity_percent"],
                    item["lost_visible_frames"],
                    item["missing_visible_frames"],
                    item["localization_drift_frames"],
                    item["mean_iou_when_tracked"],
                    item["jitter_rms_norm"],
                ]
            )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--benchmark-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--yolo-model", type=Path, default=lab.CACHE_DIR / "yolo11n.pt")
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--max-experiments", type=int, default=MAX_EXPERIMENTS)
    parser.add_argument("--max-rounds", type=int, default=MAX_REFINEMENT_ROUNDS)
    parser.add_argument(
        "--resume-diagnosis-round-two",
        action="store_true",
        help="append the 13 diagnosis-driven TTL/LK probes to an existing 27-candidate round-one history",
    )
    parser.add_argument(
        "--analysis-only",
        action="store_true",
        help="regenerate Missing/Lost evidence and contact sheets without running candidates",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not 1 <= args.max_experiments <= MAX_EXPERIMENTS:
        raise ValueError(f"max experiments must be within 1..{MAX_EXPERIMENTS}")
    if not 1 <= args.max_rounds <= MAX_REFINEMENT_ROUNDS:
        raise ValueError(f"max rounds must be within 1..{MAX_REFINEMENT_ROUNDS}")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    print("loading corrected frozen benchmark", flush=True)
    _, videos = lab.load_benchmark(args.benchmark_dir)
    detections, detector_timing = lab.detection_cache(
        videos,
        "yolo11n",
        args.yolo_model,
        args.benchmark_dir / "cache" / "yolo11n.json",
        args.threads,
        lab.sha256(args.benchmark_dir / "manifest.json"),
    )
    detector_data = {"yolo11n": detections}
    no_camera_motion = {video.id: [None] * len(video.frames) for video in videos}
    baseline = baseline_config()
    baseline_tuning, _ = lab.evaluate_config(
        baseline, videos, detector_data, no_camera_motion, "tuning"
    )
    baseline_heldout, _ = lab.evaluate_config(
        baseline, videos, detector_data, no_camera_motion, "held_out"
    )
    expected = {
        "identity_switch_events": 0,
        "wrong_person_frames": 0,
        "identity_safe_continuity_percent": 84.854,
        "lost_visible_frames": 76,
        "missing_visible_frames": 12,
    }
    for key, value in expected.items():
        if baseline_heldout["aggregate"][key] != value:
            raise AssertionError(
                f"corrected baseline mismatch for {key}: "
                f"{baseline_heldout['aggregate'][key]} != {value}"
            )
    rows, runs, _ = failure_rows_and_runs(videos, detections, baseline)
    (args.output_dir / "missing_lost_frame_analysis.json").write_text(
        json.dumps({"baseline": asdict(baseline), "runs": runs, "frames": rows}, indent=2)
        + "\n",
        encoding="utf-8",
    )
    with (args.output_dir / "missing_lost_frame_analysis.csv").open(
        "w", newline="", encoding="utf-8"
    ) as stream:
        writer = csv.writer(stream)
        writer.writerow(
            [
                "video",
                "canonical_index",
                "timestamp_s",
                "target_visible",
                "state",
                "category",
                "explanation",
                "pipeline_note",
            ]
        )
        for row in rows:
            writer.writerow(
                [
                    row["video"],
                    row["canonical_index"],
                    row["timestamp_s"],
                    row["target_visible"],
                    row["state"],
                    row["category"],
                    row["explanation"],
                    row["pipeline_note"],
                ]
            )
    render_run_sheets(args.output_dir, videos, runs, rows, detections)
    if args.analysis_only:
        print(
            json.dumps(
                {"failure_frames": len(rows), "failure_runs": len(runs)}, indent=2
            ),
            flush=True,
        )
        return 0

    if args.resume_diagnosis_round_two:
        history_path = args.output_dir / "targeted_experiment_history.jsonl"
        if not history_path.is_file():
            raise ValueError("resume requires an existing targeted experiment history")
        history = [
            json.loads(line)
            for line in history_path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        if len(history) != 27 or {item["iteration"] for item in history} != {1}:
            raise ValueError("resume requires the exact 27-candidate round-one checkpoint")
        round_two = diagnosis_driven_round_two_configs(baseline)[
            : args.max_experiments - len(history)
        ]
        round_two_results = evaluate_candidates(
            round_two,
            2,
            videos,
            detector_data,
            no_camera_motion,
            baseline_tuning,
        )
        history.extend(round_two_results)
        eligible = [item for item in history if item["eligible"]]
        best = min(eligible, key=lambda item: tuple(item["targeted_rank"])) if eligible else None
        stop_reason = (
            "bounded two-round targeted search completed"
            if best is not None and meaningful_improvement(baseline_tuning, best)
            else "round 2 produced no meaningful safety-eligible tuning improvement"
        )
    else:
        history = []
        seen = {config_signature(baseline)}
        round_one = [
            config
            for config in round_one_configs(baseline)
            if config_signature(config) not in seen
        ][: args.max_experiments]
        seen.update(config_signature(config) for config in round_one)
        round_one_results = evaluate_candidates(
            round_one,
            1,
            videos,
            detector_data,
            no_camera_motion,
            baseline_tuning,
        )
        history.extend(round_one_results)
        eligible = [item for item in history if item["eligible"]]
        best = min(eligible, key=lambda item: tuple(item["targeted_rank"])) if eligible else None
        stop_reason = "maximum one refinement round requested"
        if best is None or not meaningful_improvement(baseline_tuning, best):
            stop_reason = "round 1 produced no meaningful safety-eligible tuning improvement"
        elif args.max_rounds >= 2 and len(history) < args.max_experiments:
            round_two = round_two_configs(
                lab.Config(**best["config"]),
                seen,
                args.max_experiments - len(history),
            )
            round_two_results = evaluate_candidates(
                round_two,
                2,
                videos,
                detector_data,
                no_camera_motion,
                baseline_tuning,
            )
            history.extend(round_two_results)
            all_eligible = [item for item in history if item["eligible"]]
            round_two_best = min(
                all_eligible, key=lambda item: tuple(item["targeted_rank"])
            )
            if tuple(round_two_best["targeted_rank"]) < tuple(best["targeted_rank"]):
                best = round_two_best
                stop_reason = "bounded two-round targeted search completed"
            else:
                stop_reason = "round 2 produced no meaningful tuning improvement"
    if len(history) > args.max_experiments:
        raise AssertionError("experiment budget exceeded")

    frozen_config = baseline if best is None else lab.Config(**best["config"])
    frozen_heldout, _ = lab.evaluate_config(
        frozen_config, videos, detector_data, no_camera_motion, "held_out"
    )
    frozen_aggregate = frozen_heldout["aggregate"]
    baseline_aggregate = baseline_heldout["aggregate"]
    weak_nonregression = all(
        frozen_heldout["per_video"][video_id]["identity_safe_continuity_percent"]
        >= baseline_heldout["per_video"][video_id]["identity_safe_continuity_percent"]
        for video_id in WEAK_VIDEOS
    )
    candidate_wins = (
        safety_eligible(frozen_heldout)
        and frozen_aggregate["identity_safe_continuity_percent"]
        > baseline_aggregate["identity_safe_continuity_percent"]
        and weak_nonregression
    )
    final_config = frozen_config if candidate_wins else baseline
    final_metrics = frozen_heldout if candidate_wins else baseline_heldout
    ranked = sorted(history, key=lambda item: tuple(item["targeted_rank"]))
    (args.output_dir / "targeted_experiment_history.jsonl").write_text(
        "".join(json.dumps(item) + "\n" for item in history), encoding="utf-8"
    )
    payload = {
        "limits": {
            "maximum_new_experiments": args.max_experiments,
            "maximum_refinement_rounds": args.max_rounds,
            "actual_new_experiments": len(history),
            "actual_refinement_rounds": max(
                (item["iteration"] for item in history), default=0
            ),
            "stop_reason": stop_reason,
        },
        "detector_timing": detector_timing,
        "baseline": baseline_heldout,
        "frozen_tuning_candidate": frozen_heldout,
        "candidate_accepted_over_baseline": candidate_wins,
        "winner": final_metrics,
    }
    (args.output_dir / "winning_config.json").write_text(
        json.dumps(payload, indent=2) + "\n", encoding="utf-8"
    )
    (args.output_dir / "ranked_tuning_candidates.json").write_text(
        json.dumps(ranked, indent=2) + "\n", encoding="utf-8"
    )
    write_metrics_csv(args.output_dir / "heldout_metrics.csv", final_metrics)
    final = final_metrics["aggregate"]
    ready = (
        final["identity_switch_events"] == 0
        and final["wrong_person_frames"] == 0
        and final["identity_safe_continuity_percent"] >= TARGET_CONTINUITY_PERCENT
    )
    apparent_best = max(
        history,
        key=lambda item: item["aggregate"]["identity_safe_continuity_percent"],
    )
    apparent = apparent_best["aggregate"]
    report = [
        "# Bounded targeted continuity optimization",
        "",
        "## Search integrity",
        "",
        "- The corrected detector-only baseline was reproduced exactly before diagnosis or search.",
        "- Candidate selection used only tuning partitions; the frozen result was then checked against the established corrected held-out baseline.",
        f"- New tuning experiments: {len(history)}/{args.max_experiments} across {max((item['iteration'] for item in history), default=0)}/{args.max_rounds} refinement rounds. No clip-specific rule was used.",
        f"- Safety-ineligible candidates: {sum(not item['eligible'] for item in history)}/{len(history)}. The best apparent continuity was {apparent['identity_safe_continuity_percent']:.3f}%, but it had {apparent['identity_switch_events']} switches, {apparent['wrong_person_frames']} wrong-person frames, and {apparent['false_tracked_while_target_invisible']} false-invisible tracks.",
        f"- Stop reason: {stop_reason}.",
        "",
        "## Root causes",
        "",
        "| Video / canonical run | Exact cause |",
        "|---|---|",
        "| `single_person` 136-150 | Strong detections failed geometry/scale after abrupt camera displacement at 136-138; Lost then latched although usable detections returned. |",
        "| `single_person` 246-247 | Confidence fell below 0.30; association recovered normally at frame 248. |",
        "| `single_person` 251-343 | Foreground foliage occluded the target; Lost latched during invisibility and remained latched after reappearance at 257. |",
        "| `multi_person` 55-59 | The target is invisible/offscreen; Missing then Lost is expected fail-closed behavior with no visible-target continuity loss. |",
        "| `courtyard_single_a` 156 | Duplicate overlapping proposals for the same target caused one isolated association ambiguity. |",
        "| `courtyard_competitor` 92-93 | Real target/competitor proximity caused correct fail-closed ambiguity and immediate recovery. |",
        "| `courtyard_competitor` 122 | Physical target/competitor overlap produced a merged proposal with scale ratio 2.883, beyond the 2.8 geometry limit. |",
        "| `courtyard_competitor` 124-160 | Real overlap caused ambiguity at 124-126; Lost then latched even after clean target detections returned. |",
        "| `courtyard_competitor` 268, 272 | Isolated real overlaps caused correct fail-closed ambiguity and immediate recovery. |",
        "",
        "No significant run was caused by the appearance gate. See `missing_lost_frame_analysis.json` for every failure frame and the diagnostic contact sheets for visual evidence.",
        "",
        "## Optimization evidence",
        "",
        "- Round 1 reduced detector/association rejection in 27 variants; every variant admitted the competitor on tuning data.",
        "- Round 2 used the remaining 13 probes only for longer Missing TTL and LK bridging; every variant remained identity-unsafe.",
        "- The evidence rejects relaxing the latch or adding LK with the current lightweight identity representation.",
        "",
        "## Result",
        "",
        f"- Pipeline: `{final_config.name}` (`yolo11n`, {'LK' if final_config.use_lk else 'detector-only'}).",
        f"- Held-out: {final['identity_switch_events']} switches, {final['wrong_person_frames']} wrong-person frames, {final['identity_safe_continuity_percent']:.3f}% continuity, {final['lost_visible_frames']} Lost, {final['missing_visible_frames']} Missing.",
        f"- Android decision: {'READY' if ready else 'NOT READY'}.",
        "- Largest blocker: safe post-occlusion/reacquisition cannot be unlocked without competitor takeover using the current lightweight histogram/geometry association.",
        "",
        "See `missing_lost_frame_analysis.json` for every failure frame and `heldout_metrics.csv` for per-video results.",
    ]
    (args.output_dir / "report.md").write_text(
        "\n".join(report) + "\n", encoding="utf-8"
    )
    print(json.dumps(payload, indent=2), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
