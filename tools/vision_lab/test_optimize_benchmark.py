import unittest

import numpy as np

from optimize_benchmark import (
    Box,
    Config,
    Detection,
    Result,
    VideoData,
    associate,
    aggregate,
    evaluate_results,
    histogram,
    iou,
    run_pipeline,
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
            None,
            config,
        )
        self.assertIsNone(chosen)
        self.assertEqual(reason, "competitor ambiguity")

    def test_persistent_selection_appearance_blocks_adaptive_identity_drift(self) -> None:
        selection = np.zeros((120, 160, 3), dtype=np.uint8)
        current = np.zeros_like(selection)
        selected_box = Box(10, 10, 50, 110)
        competitor_box = Box(12, 10, 52, 110)
        target_box = Box(100, 10, 140, 110)
        selection[10:60, 10:50] = (70, 130, 205)
        selection[60:110, 10:50] = (45, 35, 25)
        current[10:60, 12:52] = (145, 145, 145)
        current[60:110, 12:52] = (70, 70, 70)
        current[10:60, 100:140] = (70, 130, 205)
        current[60:110, 100:140] = (45, 35, 25)
        adaptive_hist = histogram(current, competitor_box)
        persistent_hist = histogram(selection, selected_box)
        config = Config(
            "test",
            "test",
            "yolo11n",
            False,
            low_confidence=0.1,
            high_confidence=0.1,
            appearance_gate=0.45,
            ambiguity_margin=0.01,
            persistent_appearance_weight=0.8,
        )
        detections = [
            Detection(competitor_box, 0.9),
            Detection(target_box, 0.9),
        ]
        drifted, _, _ = associate(
            detections, competitor_box, current, adaptive_hist, adaptive_hist, config
        )
        protected, _, reason = associate(
            detections, competitor_box, current, adaptive_hist, persistent_hist, config
        )
        self.assertEqual(drifted, detections[0])
        self.assertIsNone(protected)
        self.assertIn(reason, {"no identity-safe association", "persistent identity ambiguity"})

    def test_abrupt_scale_competitor_does_not_veto_continuous_target(self) -> None:
        selection = np.zeros((120, 200, 3), dtype=np.uint8)
        current = np.zeros_like(selection)
        selected_box = Box(10, 10, 60, 110)
        target_box = Box(12, 10, 62, 110)
        competitor_box = Box(150, 10, 160, 110)
        selection[10:60, 10:60] = (70, 130, 205)
        selection[60:110, 10:60] = (45, 35, 25)
        current[10:60, 12:62] = (75, 125, 195)
        current[60:110, 12:62] = (50, 40, 30)
        current[10:60, 150:160] = (70, 130, 205)
        current[60:110, 150:160] = (45, 35, 25)
        persistent_hist = histogram(selection, selected_box)
        config = Config(
            "test",
            "test",
            "yolo11n",
            False,
            low_confidence=0.1,
            high_confidence=0.1,
            distance_gate=0.8,
            appearance_gate=0.3,
            ambiguity_margin=0.01,
            persistent_conflict_margin=0.01,
        )
        target = Detection(target_box, 0.9)
        chosen, _, reason = associate(
            [target, Detection(competitor_box, 0.9)],
            target_box,
            current,
            persistent_hist,
            persistent_hist,
            config,
        )
        self.assertEqual(chosen, target)
        self.assertEqual(reason, "accepted detector association")

    def test_reacquisition_requires_consecutive_confirmation(self) -> None:
        frame = np.zeros((120, 160, 3), dtype=np.uint8)
        frame[10:60, 20:60] = (70, 130, 205)
        frame[60:110, 20:60] = (45, 35, 25)
        box = Box(20, 10, 60, 110)
        annotation = {
            "target_visible": True,
            "target_box_norm": box.normalized(160, 120),
        }
        video = VideoData(
            "test",
            [frame.copy() for _ in range(4)],
            [0.0, 0.2, 0.4, 0.6],
            [annotation.copy() for _ in range(4)],
            0,
            [[0.0, 1.0]],
            [[0.0, 1.0]],
        )
        config = Config(
            "test",
            "test",
            "yolo11n",
            False,
            low_confidence=0.1,
            high_confidence=0.1,
            appearance_gate=0.3,
            ambiguity_margin=0.2,
            reacquire_confirmation_frames=2,
            missing_ttl_s=1.0,
        )
        results, _ = run_pipeline(
            video,
            [
                [],
                [Detection(box, 0.9), Detection(Box(22, 10, 62, 110), 0.9)],
                [Detection(box, 0.9)],
                [Detection(box, 0.9)],
            ],
            [None, None, None, None],
            config,
        )
        self.assertEqual(results[1].state, "Missing")
        self.assertEqual(results[2].state, "Missing")
        self.assertEqual(results[2].note, "reacquisition confirmation 1/2; LK disabled")
        self.assertEqual(results[3].state, "Tracked")
        self.assertEqual(results[3].note, "confirmed detector reacquisition")

    def test_brief_unambiguous_miss_does_not_require_reacquisition_confirmation(self) -> None:
        frame = np.zeros((120, 160, 3), dtype=np.uint8)
        frame[10:60, 20:60] = (70, 130, 205)
        frame[60:110, 20:60] = (45, 35, 25)
        box = Box(20, 10, 60, 110)
        annotation = {
            "target_visible": True,
            "target_box_norm": box.normalized(160, 120),
        }
        video = VideoData(
            "test",
            [frame.copy() for _ in range(3)],
            [0.0, 0.2, 0.4],
            [annotation.copy() for _ in range(3)],
            0,
            [[0.0, 1.0]],
            [[0.0, 1.0]],
        )
        config = Config(
            "test",
            "test",
            "yolo11n",
            False,
            low_confidence=0.1,
            high_confidence=0.1,
            appearance_gate=0.3,
            ambiguity_margin=0.01,
            reacquire_confirmation_frames=2,
            missing_ttl_s=1.0,
        )
        results, _ = run_pipeline(
            video,
            [[], [], [Detection(box, 0.9)]],
            [None, None, None],
            config,
        )
        self.assertEqual(results[1].state, "Missing")
        self.assertEqual(results[2].state, "Tracked")
        self.assertEqual(results[2].note, "accepted detector association")

    def test_detector_identity_ambiguity_cannot_be_overridden_by_lk(self) -> None:
        frame = np.zeros((120, 160, 3), dtype=np.uint8)
        frame[10:60, 20:60] = (70, 130, 205)
        frame[60:110, 20:60] = (45, 35, 25)
        box = Box(20, 10, 60, 110)
        annotation = {
            "target_visible": True,
            "target_box_norm": box.normalized(160, 120),
        }
        video = VideoData(
            "test",
            [frame.copy(), frame.copy()],
            [0.0, 0.2],
            [annotation.copy(), annotation.copy()],
            0,
            [[0.0, 1.0]],
            [[0.0, 1.0]],
        )
        config = Config(
            "test",
            "test",
            "yolo11n",
            True,
            detector_cadence=1,
            low_confidence=0.1,
            high_confidence=0.1,
            appearance_gate=0.0,
            ambiguity_margin=0.2,
            missing_ttl_s=1.0,
        )
        results, _ = run_pipeline(
            video,
            [[], [Detection(box, 0.9), Detection(Box(22, 10, 62, 110), 0.9)]],
            [None, None],
            config,
        )
        self.assertEqual(results[1].state, "Missing")
        self.assertIn("competitor ambiguity", results[1].note)

    def test_overlapping_low_confidence_person_triggers_crowd_ambiguity(self) -> None:
        frame = np.zeros((120, 180, 3), dtype=np.uint8)
        frame[10:60, 20:100] = (70, 130, 205)
        frame[60:110, 20:100] = (45, 35, 25)
        selected_box = Box(20, 10, 100, 110)
        overlapping_box = Box(50, 10, 130, 110)
        persistent_hist = histogram(frame, selected_box)
        config = Config(
            "test",
            "test",
            "yolo11n",
            False,
            low_confidence=0.3,
            high_confidence=0.3,
            appearance_gate=0.3,
            ambiguity_margin=0.01,
        )
        chosen, _, reason = associate(
            [Detection(selected_box, 0.9), Detection(overlapping_box, 0.04)],
            selected_box,
            frame,
            persistent_hist,
            persistent_hist,
            config,
        )
        self.assertIsNone(chosen)
        self.assertEqual(reason, "overlapping competitor ambiguity")

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

    def test_never_track_is_ineligible(self) -> None:
        per_video = {
            "test": {
                "frames": 100,
                "visible_target_frames": 100,
                "identity_switch_events": 0,
                "wrong_person_frames": 0,
                "localization_drift_frames": 0,
                "false_tracked_while_target_invisible": 0,
                "lost_visible_frames": 100,
                "missing_visible_frames": 0,
                "correctly_localized_target_frames": 0,
                "identity_safe_tracked_frames": 0,
                "tracked_visible_frames": 0,
                "iou_sum": 0.0,
                "iou_sample_count": 0,
                "jitter_squared_sum": 0.0,
                "jitter_sample_count": 0,
            }
        }
        metrics = aggregate(per_video, {"test": 0.0})
        self.assertEqual(metrics["continuity_shortfall_frames"], 50)
        self.assertEqual(metrics["rank_tuple"][0], 50)


if __name__ == "__main__":
    unittest.main()
# SPDX-License-Identifier: AGPL-3.0-only
