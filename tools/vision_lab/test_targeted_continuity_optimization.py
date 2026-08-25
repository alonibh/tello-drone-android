import unittest

from targeted_continuity_optimization import (
    MAX_EXPERIMENTS,
    MAX_REFINEMENT_ROUNDS,
    baseline_config,
    diagnosis_driven_round_two_configs,
    round_one_configs,
)


class TargetedContinuityOptimizationTest(unittest.TestCase):
    def test_search_bounds_and_candidate_scope(self) -> None:
        baseline = baseline_config()
        round_one = round_one_configs(baseline)
        round_two = diagnosis_driven_round_two_configs(baseline)

        self.assertEqual(len(round_one), 27)
        self.assertEqual(len(round_two), 13)
        self.assertEqual(len(round_one) + len(round_two), MAX_EXPERIMENTS)
        self.assertEqual(MAX_REFINEMENT_ROUNDS, 2)
        self.assertTrue(all(not config.use_lk for config in round_one))
        self.assertTrue(all(not config.persistent_identity_safety for config in round_one + round_two))

    def test_restored_baseline_is_the_corrected_detector_winner(self) -> None:
        config = baseline_config()

        self.assertEqual(config.detector, "yolo11n")
        self.assertFalse(config.use_lk)
        self.assertEqual(config.high_confidence, 0.30)
        self.assertEqual(config.low_confidence, 0.30)
        self.assertEqual(config.appearance_gate, 0.45)
        self.assertEqual(config.missing_ttl_s, 0.4)
        self.assertFalse(config.persistent_identity_safety)


if __name__ == "__main__":
    unittest.main()
# SPDX-License-Identifier: AGPL-3.0-only
