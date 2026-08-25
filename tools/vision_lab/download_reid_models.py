#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Download and verify the bounded experiment's Open Model Zoo artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
import urllib.request
from pathlib import Path


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(__file__).resolve().parent / ".cache" / "reid_models",
    )
    args = parser.parse_args()
    manifest = json.loads(
        (Path(__file__).resolve().parent / "reid_models.json").read_text(
            encoding="utf-8"
        )
    )
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for model in manifest["models"]:
        for suffix, item in model["files"].items():
            destination = args.output_dir / f"{model['name']}.{suffix}"
            if not destination.is_file() or digest(destination) != item["sha256"]:
                temporary = destination.with_suffix(destination.suffix + ".download")
                urllib.request.urlretrieve(item["url"], temporary)
                if digest(temporary) != item["sha256"]:
                    temporary.unlink(missing_ok=True)
                    raise ValueError(f"hash mismatch: {destination.name}")
                temporary.replace(destination)
            print(f"verified {destination.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
