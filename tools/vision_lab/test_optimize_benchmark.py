import unittest

import numpy as np

from optimize_benchmark import (
    Box,
    Config,
    Detection,
    Result,
    VideoData,
    associate,
    evaluate_results,
    iou,
)


class OptimizerTest(unittest.TestCase):
    def test_iou(self) -> None:
        self.assertAlmostEqual(iou(Box(0, 0, 10, 10), Box(5, 5, 15, 15)), 25 / 175)

    def test_competitor_ambiguity_fails_closed(self) -> None:
        image = np.full((100, 100, 3), 100, dtype=np.uint8)
        config = Config(
            "test",
            "test",
            "yolo11n",
            True,
            low_confidence=0.1,
            appearance_gate=0.0,
            ambiguity_margin=0.2,
        )
        chosen, _, reason = associate(
            [Detection(Box(20, 20, 50, 80), 0.8), Detection(Box(22, 20, 52, 80), 0.8)],
            Box(21, 20, 51, 80),
            image,
            None,
            config,
        )
        self.assertIsNone(chosen)
        self.assertEqual(reason, "competitor ambiguity")

    def test_wrong_track_outweighs_continuity(self) -> None:
        frame = np.zeros((100, 100, 3), dtype=np.uint8)
        annotation = {
            "target_visible": True,
            "target_box_norm": [0.1, 0.1, 0.3, 0.8],
        }
        video = VideoData(
            "test",
            [frame, frame],
            [0.0, 0.2],
            [annotation, annotation],
            0,
            [[0.0, 1.0]],
            [[0.0, 1.0]],
        )
        metrics = evaluate_results(
            video,
            [
                Result("Tracked", "detector", Box(10, 10, 30, 80), ""),
                Result("Tracked", "detector", Box(70, 10, 90, 80), ""),
            ],
            "held_out",
        )
        self.assertEqual(metrics["identity_switch_events"], 1)
        self.assertEqual(metrics["wrong_person_frames"], 1)
        self.assertEqual(metrics["correctly_localized_target_frames"], 1)


if __name__ == "__main__":
    unittest.main()
