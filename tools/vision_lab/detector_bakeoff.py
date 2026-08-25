"""License-gated detector replacement bake-off for the frozen safe pipeline.

Candidates with unclear pretrained-weight terms are rejected before model
download or inference. The corrected YOLO11n baseline is still reproduced so
the stopped result remains tied to the frozen five-video benchmark.
"""

from __future__ import annotations

import argparse
import csv
import json
from dataclasses import asdict
from pathlib import Path
from typing import Any

import optimize_benchmark as lab


MAX_FAMILIES = 2
MAX_CONFIGURATIONS_PER_FAMILY = 4
MAX_EXPERIMENTS = 8
BASELINE_CONTINUITY_PERCENT = 84.854


def load_and_validate_candidates(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    candidates = payload.get("candidates", [])
    if not candidates or len(candidates) > MAX_FAMILIES:
        raise ValueError(f"candidate family count must be within 1..{MAX_FAMILIES}")
    families = [candidate["family"] for candidate in candidates]
    if len(families) != len(set(families)):
        raise ValueError("candidate families must be unique")
    total = 0
    for candidate in candidates:
        count = int(candidate["configuration_count"])
        if not 1 <= count <= MAX_CONFIGURATIONS_PER_FAMILY:
            raise ValueError(
                f"{candidate['family']} configuration count must be within "
                f"1..{MAX_CONFIGURATIONS_PER_FAMILY}"
            )
        total += count
    if total > MAX_EXPERIMENTS:
        raise ValueError(f"planned experiment count exceeds {MAX_EXPERIMENTS}")
    declared = payload.get("scope_limits", {})
    expected = {
        "max_families": MAX_FAMILIES,
        "max_configurations_per_family": MAX_CONFIGURATIONS_PER_FAMILY,
        "max_experiments": MAX_EXPERIMENTS,
    }
    if declared != expected:
        raise ValueError(f"manifest scope limits differ from enforced limits: {declared}")
    return payload


def deployment_eligible(candidate: dict[str, Any]) -> tuple[bool, str]:
    source = candidate["source_code"]
    weights = candidate["pretrained_weights"]
    if not source.get("proprietary_distribution_reasonably_permitted", False):
        return False, "source code is not suitable for proprietary distribution"
    if weights.get("status") != "clear" or not weights.get("license_spdx"):
        return False, "pretrained-weight license is unclear"
    if not weights.get("commercial_redistribution_explicit", False):
        return False, "pretrained weights lack an explicit commercial redistribution grant"
    return True, "license gate passed"


def assert_frozen_baseline_config() -> lab.Config:
    config = lab.corrected_label_winner_config()
    expected = {
        "detector": "yolo11n",
        "use_lk": False,
        "detector_cadence": 1,
        "high_confidence": 0.30,
        "low_confidence": 0.30,
        "iou_gate": 0.15,
        "distance_gate": 0.24,
        "appearance_gate": 0.45,
        "ambiguity_margin": 0.10,
        "motion_alpha": 0.0,
        "camera_compensation": False,
        "missing_ttl_s": 0.4,
        "persistent_identity_safety": False,
    }
    actual = asdict(config)
    for key, value in expected.items():
        if actual[key] != value:
            raise AssertionError(f"frozen baseline changed: {key}={actual[key]} != {value}")
    return config


def compact_metrics(metrics: dict[str, Any]) -> dict[str, Any]:
    keys = (
        "visible_target_frames",
        "identity_safe_tracked_frames",
        "identity_switch_events",
        "wrong_person_frames",
        "false_tracked_while_target_invisible",
        "identity_safe_continuity_percent",
        "lost_visible_frames",
        "missing_visible_frames",
        "mean_iou_when_tracked",
    )
    return {
        "partition": metrics["partition"],
        "per_video": {
            video_id: {key: values[key] for key in keys}
            for video_id, values in metrics["per_video"].items()
        },
        "aggregate": {key: metrics["aggregate"][key] for key in keys},
    }


def evaluate_frozen_baseline(
    benchmark_dir: Path,
    yolo_model: Path,
    threads: int,
) -> tuple[dict[str, Any], dict[str, Any], str]:
    _, videos = lab.load_benchmark(benchmark_dir)
    benchmark_hash = lab.sha256(benchmark_dir / "manifest.json")
    detections, timing = lab.detection_cache(
        videos,
        "yolo11n",
        yolo_model,
        benchmark_dir / "cache" / "yolo11n.json",
        threads,
        benchmark_hash,
    )
    no_motion = {video.id: [None] * len(video.frames) for video in videos}
    metrics, _ = lab.evaluate_config(
        assert_frozen_baseline_config(),
        videos,
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
            raise AssertionError(f"corrected baseline mismatch: {key}={aggregate[key]} != {value}")
    return compact_metrics(metrics), timing, benchmark_hash


def write_metrics_csv(
    path: Path,
    baseline: dict[str, Any],
    decisions: list[dict[str, Any]],
) -> None:
    columns = [
        "candidate",
        "video",
        "status",
        "switches",
        "wrong_person_frames",
        "false_invisible_tracks",
        "continuity_percent",
        "reason",
    ]
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns)
        writer.writeheader()
        for video_id, metrics in baseline["per_video"].items():
            writer.writerow(
                {
                    "candidate": "yolo11n_safe_baseline",
                    "video": video_id,
                    "status": "reference",
                    "switches": metrics["identity_switch_events"],
                    "wrong_person_frames": metrics["wrong_person_frames"],
                    "false_invisible_tracks": metrics["false_tracked_while_target_invisible"],
                    "continuity_percent": metrics["identity_safe_continuity_percent"],
                    "reason": "frozen corrected baseline",
                }
            )
        aggregate = baseline["aggregate"]
        writer.writerow(
            {
                "candidate": "yolo11n_safe_baseline",
                "video": "AGGREGATE",
                "status": "reference",
                "switches": aggregate["identity_switch_events"],
                "wrong_person_frames": aggregate["wrong_person_frames"],
                "false_invisible_tracks": aggregate["false_tracked_while_target_invisible"],
                "continuity_percent": aggregate["identity_safe_continuity_percent"],
                "reason": "frozen corrected baseline",
            }
        )
        for decision in decisions:
            writer.writerow(
                {
                    "candidate": decision["family"],
                    "video": "NOT_EVALUATED",
                    "status": decision["status"],
                    "reason": decision["reason"],
                }
            )


def render_report(result: dict[str, Any]) -> str:
    baseline = result["baseline"]
    aggregate = baseline["metrics"]["aggregate"]
    lines = [
        "# License-gated mobile detector bake-off",
        "",
        "## Decision",
        "",
        "**STOPPED BEFORE CANDIDATE INFERENCE. Neither candidate is eligible for a proprietary Android prototype because the official pretrained-weight terms are unclear.**",
        "",
        "The official source implementations are permissively licensed, but source-code licensing does not establish permission to redistribute separately published model weights. Per the experiment gate, no candidate model was downloaded, converted, timed, or evaluated. Candidate experiment count: **0/8**.",
        "",
        "The existing safe behavior remains frozen: explicit selection, normal safe association, Lost latched until explicit reselection, and no automatic long-term reacquisition. No tracking, association, threshold, production Android, or ground-truth changes were made.",
        "",
        "## License and deployment audit",
        "",
        "| Candidate | Code | Official pretrained weights | Proprietary distribution | Android path | Decision |",
        "|---|---|---|---|---|---|",
    ]
    for candidate in result["candidates"]:
        source = candidate["source_code"]
        weights = candidate["pretrained_weights"]
        deployment = candidate["android_deployment"]
        lines.append(
            f"| {candidate['display_name']} | {source['license_spdx']} | "
            f"{weights['status']}; no separate license/terms | Not established for weights | "
            f"{deployment['preferred_runtime']} ({deployment['alternative_runtime']} alternative) | Rejected before evaluation |"
        )
    lines.extend(
        [
            "",
            "### YOLOX-Nano",
            "",
            "- Code: Apache-2.0 at pinned repository commit `6ddff4824372906469a7fae2dc3206c7aa4bbaee`; the official ncnn C++ detector file carries BSD-3-Clause notices.",
            "- Weights: official `0.1.1rc0` ONNX/PyTorch release assets, but no weight-specific license, terms URL, or commercial bundling/redistribution statement. Upstream issue #1865 asks exactly this and remains unanswered.",
            "- Size/compute: official 0.91M parameters and 1.08 GFLOPs; ONNX asset 3,659,407 bytes (3.49 MiB), PyTorch asset 7,694,953 bytes (7.34 MiB), with 1.8 MB FP16 reported by NanoDet's official comparison.",
            "- Desktop cost: not measured because the candidate failed the pre-evaluation gate; YOLOX does not publish Nano desktop-CPU latency. A separate official NanoDet comparison reports 23.08 ms on a Kirin 980 4xA76 with ncnn, which is not a desktop or Teclast result.",
            "- Android: ncnn is the most direct path; ONNX Runtime Mobile is feasible from the official ONNX graph. This remains technically plausible but legally blocked for the supplied weights.",
            "",
            "### NanoDet-Plus-m 416",
            "",
            "- Code: main repository Apache-2.0 at pinned commit `be9b4a9001d7f9b6fc89c2df31ae8d428e35b4f0`. The bundled `demo_android_ncnn` subtree is separately GPL-3.0 and is unsuitable for copying into a proprietary app.",
            "- Weights: official `v1.0.0-alpha-1` ONNX/ncnn/checkpoint assets, but the model zoo and release give no weight license or commercial redistribution grant.",
            "- Size/compute: official 1.17M parameters, 1.52 GFLOPs, 2.3 MB FP16 / 1.2 MB INT8; ONNX asset 4,793,616 bytes (4.57 MiB), ncnn archive 2,218,050 bytes (2.12 MiB).",
            "- Desktop cost: not measured locally because the candidate failed the pre-evaluation gate. Official published inference is 8.32 ms with OpenVINO on an i7-8700; that is not this end-to-end benchmark.",
            "- Android: an independently written JNI adapter using the Apache-licensed ncnn demo logic and permissive ncnn runtime is the practical path; do not reuse the GPL Android demo. Official 4xA76 ncnn latency is 19.77 ms, but direct Teclast testing is still required.",
            "",
            "## Frozen held-out reference",
            "",
            "Candidate metrics are deliberately absent because both candidates were rejected before evaluation. The YOLO11n reference was reproduced from the existing hash-checked cache and frozen ground truth.",
            "",
            "| Video | Switches | Wrong-person | False-invisible | Continuity |",
            "|---|---:|---:|---:|---:|",
        ]
    )
    for video_id, metrics in baseline["metrics"]["per_video"].items():
        lines.append(
            f"| `{video_id}` | {metrics['identity_switch_events']} | "
            f"{metrics['wrong_person_frames']} | "
            f"{metrics['false_tracked_while_target_invisible']} | "
            f"{metrics['identity_safe_continuity_percent']:.3f}% |"
        )
    lines.extend(
        [
            f"| **Aggregate** | **{aggregate['identity_switch_events']}** | "
            f"**{aggregate['wrong_person_frames']}** | "
            f"**{aggregate['false_tracked_while_target_invisible']}** | "
            f"**{aggregate['identity_safe_continuity_percent']:.3f}%** |",
            "",
            f"Cached YOLO11n desktop CPU/PyTorch timing over {baseline['desktop_timing']['frames']} canonical frames: mean {baseline['desktop_timing']['mean_ms']:.3f} ms, median {baseline['desktop_timing']['median_ms']:.3f} ms, p95 {baseline['desktop_timing']['p95_ms']:.3f} ms. This is a reference only and does not predict Android performance.",
            "",
            "## Comparison and Teclast recommendation",
            "",
            "Neither candidate can be compared on identity metrics without violating the explicit license gate. Therefore neither should be ported to the Teclast now, and YOLO11n is not made deployable by this result. The detector blocker remains unresolved.",
            "",
            "The bounded next action is licensing remediation, not another architecture search: obtain an explicit commercial/redistribution grant from the model publisher, or train and document owned weights using a dataset with compatible terms. Only then rerun this same fixed detector-only bake-off and benchmark the selected runtime on the physical P50Ai.",
            "",
            "No production Android code was changed.",
        ]
    )
    return "\n".join(lines) + "\n"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--candidate-manifest",
        type=Path,
        default=Path(__file__).with_name("detector_candidates.json"),
    )
    parser.add_argument("--benchmark-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--yolo-model", type=Path, default=Path(__file__).parent / ".cache" / "yolo11n.pt")
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--max-experiments", type=int, default=MAX_EXPERIMENTS)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not 1 <= args.max_experiments <= MAX_EXPERIMENTS:
        raise ValueError(f"max experiments must be within 1..{MAX_EXPERIMENTS}")
    manifest = load_and_validate_candidates(args.candidate_manifest)
    decisions = []
    for candidate in manifest["candidates"]:
        eligible, reason = deployment_eligible(candidate)
        decisions.append(
            {
                "family": candidate["family"],
                "eligible": eligible,
                "status": "eligible" if eligible else "rejected_pre_evaluation",
                "reason": reason,
            }
        )
    eligible = [decision for decision in decisions if decision["eligible"]]
    if eligible:
        raise RuntimeError(
            "An eligible detector requires an output-normalization adapter; this bounded run "
            "contains no silently enabled implementation."
        )
    print("both detector candidates rejected by pretrained-weight license gate", flush=True)
    print("reproducing frozen YOLO11n held-out reference", flush=True)
    baseline_metrics, baseline_timing, benchmark_hash = evaluate_frozen_baseline(
        args.benchmark_dir, args.yolo_model, args.threads
    )
    result = {
        "schema_version": 1,
        "status": "stopped_no_license_safe_candidate",
        "experiment_budget": {
            "maximum": args.max_experiments,
            "candidate_experiments_run": 0,
            "refinement_passes": 0,
        },
        "benchmark_manifest_sha256": benchmark_hash,
        "frozen_pipeline_config": asdict(assert_frozen_baseline_config()),
        "ground_truth_changed": False,
        "production_android_changed": False,
        "candidates": manifest["candidates"],
        "decisions": decisions,
        "baseline": {
            "detector": "YOLO11n",
            "model_size_bytes": args.yolo_model.stat().st_size,
            "desktop_timing": baseline_timing,
            "metrics": baseline_metrics,
        },
        "recommendation": "port_neither",
    }
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "result.json").write_text(
        json.dumps(result, indent=2) + "\n", encoding="utf-8"
    )
    (args.output_dir / "license_audit.json").write_text(
        json.dumps(
            {
                "audited_at": manifest["audited_at"],
                "decisions": decisions,
                "candidates": manifest["candidates"],
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    (args.output_dir / "experiment_history.jsonl").write_text(
        "".join(
            json.dumps({"event": "license_gate", **decision}) + "\n"
            for decision in decisions
        ),
        encoding="utf-8",
    )
    write_metrics_csv(args.output_dir / "heldout_metrics.csv", baseline_metrics, decisions)
    (args.output_dir / "report.md").write_text(render_report(result), encoding="utf-8")
    print(json.dumps({"status": result["status"], "candidate_experiments_run": 0}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
# SPDX-License-Identifier: AGPL-3.0-only
