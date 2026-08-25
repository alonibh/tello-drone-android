import unittest

import numpy as np

import identity_guard_experiment as guard
import optimize_benchmark as lab


def make_video() -> lab.VideoData:
    frame = np.zeros((100, 160, 3), np.uint8)
    return lab.VideoData(
        "unit",
        [frame],
        [0.0],
        [{"target_visible": True, "target_box_norm": [0.1, 0.1, 0.3, 0.9]}],
        0,
        [[0.0, 0.1]],
        [],
    )


class StubEmbedder:
    def __init__(self, values):
        self.values = values

    def embed(self, _video, _index, box):
        return self.values[round(box.x1)]


class IdentityGuardTests(unittest.TestCase):
    def test_search_bound_and_single_fixed_model(self):
        self.assertEqual(guard.MAX_EXPERIMENTS, len(guard.configs()))
        self.assertEqual(
            guard.MODEL, guard.fixed_reacquisition_config().model
        )

    def test_guard_vetoes_when_competitor_is_equally_similar(self):
        video = make_video()
        proposed = lab.Detection(lab.Box(20, 10, 50, 90), 0.9)
        competitor = lab.Detection(lab.Box(60, 10, 90, 90), 0.9)
        selection = np.array([1.0, 0.0], np.float32)
        embeddings = {
            20: np.array([0.94, 0.341174], np.float32),
            60: np.array([0.93, 0.36756], np.float32),
        }
        accepted, clear, evidence = guard.guard_proposal(
            video,
            0,
            proposed,
            [proposed, competitor],
            selection,
            [],
            StubEmbedder(embeddings),
            guard.GuardConfig("unit", 0.70, 0.50, 0.06, 3),
            0.2,
        )
        self.assertFalse(accepted)
        self.assertFalse(clear)
        self.assertEqual("identity veto", evidence["reason"])

    def test_duplicate_detector_proposal_is_not_a_competitor(self):
        proposed = lab.Detection(lab.Box(20, 10, 50, 90), 0.9)
        duplicate = lab.Detection(lab.Box(21, 11, 49, 89), 0.7)
        self.assertFalse(
            guard.has_strong_overlap(proposed, [proposed, duplicate], 160, 100)
        )


if __name__ == "__main__":
    unittest.main()
