import unittest

import numpy as np

import optimize_benchmark as lab
import reid_reacquisition_experiment as reid


def video() -> lab.VideoData:
    frames = [np.zeros((100, 160, 3), np.uint8) for _ in range(2)]
    annotations = [
        {
            "target_visible": True,
            "target_box_norm": [0.1, 0.1, 0.3, 0.8],
            "reviewed_identity": True,
        }
        for _ in frames
    ]
    return lab.VideoData("unit", frames, [0.0, 0.1], annotations, 0, [[0.0, 0.2]], [])


class StubEmbedder:
    def __init__(self, values):
        self.values = values

    def embed(self, _video, _index, box):
        return self.values[round(box.x1)]


class ReidExperimentTests(unittest.TestCase):
    def test_normal_pipeline_can_restart_from_confirmed_detector_box(self):
        item = video()
        confirmed = lab.Box(40, 10, 75, 85)
        results, _ = lab.run_pipeline(
            item,
            [[], [lab.Detection(confirmed, 0.9)]],
            [None, None],
            lab.corrected_label_winner_config(),
            initial_box=confirmed,
            selection_note="ReID-confirmed reacquisition",
        )
        self.assertEqual(confirmed, results[0].box)
        self.assertEqual("ReID-confirmed reacquisition", results[0].note)

    def test_competitor_separation_fails_closed(self):
        item = video()
        selection = np.array([1.0, 0.0], np.float32)
        first = lab.Detection(lab.Box(20, 10, 50, 85), 0.9)
        second = lab.Detection(lab.Box(60, 10, 90, 85), 0.9)
        values = {
            20: np.array([0.94, 0.341174], np.float32),
            60: np.array([0.93, 0.36756], np.float32),
        }
        config = reid.ReidConfig(
            "unit", "person-reidentification-retail-0288", 0.6, 0.4,
            0.05, 0.03, 2, 0.8, 8.0,
        )
        chosen, note = reid.score_candidates(
            item,
            0,
            [first, second],
            lab.Box(30, 10, 60, 85),
            selection,
            [],
            StubEmbedder(values),
            config,
        )
        self.assertIsNone(chosen)
        self.assertIn("competitor separation rejected", note)

    def test_search_stays_within_declared_bounds(self):
        configs = reid.round_one_configs() + reid.safety_refinement_configs()
        self.assertEqual(reid.MAX_EXPERIMENTS, len(configs))
        self.assertEqual(1, len({config.model.rsplit("-", 1)[0] for config in configs}))


if __name__ == "__main__":
    unittest.main()
# SPDX-License-Identifier: AGPL-3.0-only
