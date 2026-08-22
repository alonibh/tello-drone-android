#!/usr/bin/env python3
"""Build a compact contact sheet for visual QA of annotated lab videos."""

from __future__ import annotations

import argparse
from pathlib import Path

import cv2


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("contact_sheet", type=Path)
    args = parser.parse_args()
    approaches = ["baseline", "yolo11n_detector", "efficientdet_lk", "yolo11n_lk"]
    picks = [0, 7, 8, 14, 30, 51, 63, 84]
    rows = []
    for approach in approaches:
        capture = cv2.VideoCapture(str(args.output_dir / f"{approach}.mp4"))
        cells = []
        for frame_index in picks:
            capture.set(cv2.CAP_PROP_POS_FRAMES, frame_index)
            available, frame = capture.read()
            if not available:
                raise RuntimeError(f"could not read {approach} frame {frame_index}")
            cells.append(cv2.resize(frame, (320, 267), interpolation=cv2.INTER_AREA))
        capture.release()
        rows.append(cv2.hconcat(cells))
    sheet = cv2.vconcat(rows)
    args.contact_sheet.parent.mkdir(parents=True, exist_ok=True)
    if not cv2.imwrite(str(args.contact_sheet), sheet):
        raise RuntimeError(f"could not write {args.contact_sheet}")
    print(args.contact_sheet.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
