#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Fetch and hash-check the exact SOT research sources and checkpoints."""

from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

from sot_experiment import (
    LIGHTTRACK_COMMIT,
    LIGHTTRACK_REPOSITORY,
    LIGHTTRACK_WEIGHT_SHA256,
    NANOTRACK_BACKBONE_SHA256,
    NANOTRACK_COMMIT,
    NANOTRACK_HEAD_SHA256,
    NANOTRACK_REPOSITORY,
    file_sha256,
)


def run(*args: str, cwd: Path | None = None) -> None:
    subprocess.run(args, cwd=cwd, check=True)


def checkout(repository: str, commit: str, destination: Path) -> None:
    if not (destination / ".git").is_dir():
        destination.parent.mkdir(parents=True, exist_ok=True)
        run("git", "clone", "--no-checkout", repository, str(destination))
    run("git", "fetch", "--depth", "1", "origin", commit, cwd=destination)
    run("git", "checkout", "--detach", commit, cwd=destination)
    actual = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=destination, text=True
    ).strip()
    if actual != commit:
        raise ValueError(f"source commit mismatch: {destination}: {actual}")


def verify(path: Path, expected: str) -> None:
    actual = file_sha256(path)
    if actual != expected:
        raise ValueError(f"asset hash mismatch: {path}: {actual} != {expected}")
    print(f"verified {actual}  {path}")


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--destination", type=Path, default=root / ".cache" / "third_party"
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    lighttrack = args.destination / "LightTrack"
    nanotrack = args.destination / "SiamTrackers"
    checkout(LIGHTTRACK_REPOSITORY, LIGHTTRACK_COMMIT, lighttrack)
    checkout(NANOTRACK_REPOSITORY, NANOTRACK_COMMIT, nanotrack)
    verify(
        lighttrack / "snapshot" / "LightTrackM" / "LightTrackM.pth",
        LIGHTTRACK_WEIGHT_SHA256,
    )
    verify(
        nanotrack
        / "NanoTrack"
        / "models"
        / "nanotrackv3"
        / "nanotrack_backbone.onnx",
        NANOTRACK_BACKBONE_SHA256,
    )
    verify(
        nanotrack
        / "NanoTrack"
        / "models"
        / "nanotrackv3"
        / "nanotrack_head.onnx",
        NANOTRACK_HEAD_SHA256,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
