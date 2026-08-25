from __future__ import annotations

from dataclasses import replace
import unittest

import numpy as np

import sot_experiment as sot

lab = sot.lab


def config() -> sot.SafetyConfig:
    return replace(
        sot.initial_configs("nanotrack_v3")[0],
        sot_confidence=0.2,
        template_similarity=0.0,
        detector_confidence=0.2,
        detector_iou=0.05,
        detector_distance=0.10,
        detector_miss_grace_frames=0,
        missing_ttl_s=0.2,
    )


def video(frame_count: int = 4) -> lab.VideoData:
    frames = [np.full((100, 160, 3), 80, np.uint8) for _ in range(frame_count)]
    annotations = [
        {
            "target_visible": True,
            "target_box_norm": [0.25, 0.2, 0.5, 0.8],
            "reviewed_identity": True,
        }
        for _ in frames
    ]
    return lab.VideoData(
        "synthetic",
        frames,
        [index * 0.2 for index in range(frame_count)],
        annotations,
        0,
        [[0.0, 0.8]],
        [[0.0, 0.8]],
    )


class SotExperimentTests(unittest.TestCase):
    def test_experiment_limits_and_family_count(self) -> None:
        self.assertEqual(len(sot.TRACKER_FAMILIES), sot.MAX_TRACKERS)
        self.assertEqual(sot.MAX_TRACKERS, 2)
        self.assertEqual(
            sot.MAX_CONFIGS_PER_TRACKER * sot.MAX_TRACKERS,
            sot.MAX_EXPERIMENTS,
        )
        self.assertTrue(
            all(
                len(sot.initial_configs(family)) <= sot.MAX_CONFIGS_PER_TRACKER
                for family in sot.TRACKER_FAMILIES
            )
        )

    def test_response_peak_margin_suppresses_local_neighbors(self) -> None:
        response = np.zeros((5, 5), np.float32)
        response[2, 2] = 0.9
        response[2, 3] = 0.85
        response[0, 0] = 0.4
        self.assertAlmostEqual(sot.response_peak_margin(response, 12), 0.5)

    def test_detector_is_validation_only_and_cannot_replace_sot_box(self) -> None:
        item = video(2)
        selected = sot.selection_box(item, 0)
        raw_box = lab.Box(42, 21, 82, 81)
        detector_box = lab.Box(40, 20, 80, 80)
        trajectory = sot.SectionTrajectory(
            0,
            2,
            selected,
            {1: sot.RawPrediction(raw_box, 0.9, 0.5, 1.0)},
            1.0,
        )
        detections = [[], [lab.Detection(detector_box, 0.9)]]
        results = sot.run_section(item, trajectory, detections, config())
        self.assertEqual(results[1].state, "Tracked")
        self.assertEqual(results[1].source, "SOT")
        self.assertEqual(results[1].box, raw_box)
        self.assertNotEqual(results[1].box, detector_box)

    def test_detector_disagreement_and_competitor_overlap_fail_closed(self) -> None:
        item = video(2)
        selected = sot.selection_box(item, 0)
        raw = sot.RawPrediction(selected, 0.9, 0.5, 1.0)
        far = lab.Detection(lab.Box(120, 20, 150, 90), 0.9)
        reasons, _, _ = sot.validate_prediction(
            item,
            1,
            raw,
            selected,
            selected,
            lab.histogram(item.frames[0], selected),
            [far],
            0,
            config(),
        )
        self.assertIn("strong detector/SOT disagreement", reasons)

        match = lab.Detection(selected, 0.9)
        overlapping = lab.Detection(lab.Box(60, 20, 100, 80), 0.8)
        reasons, _, _ = sot.validate_prediction(
            item,
            1,
            raw,
            selected,
            selected,
            lab.histogram(item.frames[0], selected),
            [match, overlapping],
            0,
            replace(config(), competitor_iou=0.05, competitor_distance=0.20),
        )
        self.assertIn("competitor overlap uncertainty", reasons)

    def test_lost_latches_even_if_later_sot_and_detector_recover(self) -> None:
        item = video(4)
        selected = sot.selection_box(item, 0)
        raw = sot.RawPrediction(selected, 0.9, 0.5, 1.0)
        trajectory = sot.SectionTrajectory(
            0,
            4,
            selected,
            {1: raw, 2: raw, 3: raw},
            1.0,
        )
        match = lab.Detection(selected, 0.9)
        detections = [[], [], [match], [match]]
        results = sot.run_section(item, trajectory, detections, config())
        self.assertEqual(results[1].state, "Lost")
        self.assertEqual(results[2].state, "Lost")
        self.assertEqual(results[3].state, "Lost")


if __name__ == "__main__":
    unittest.main()
# SPDX-License-Identifier: AGPL-3.0-only
