import json
import tempfile
import unittest
from dataclasses import asdict
from pathlib import Path

import detector_bakeoff as bakeoff


class DetectorBakeoffTest(unittest.TestCase):
    def test_manifest_is_bounded_and_only_requested_families_are_present(self) -> None:
        manifest = bakeoff.load_and_validate_candidates(
            Path(__file__).with_name("detector_candidates.json")
        )
        self.assertEqual(
            [candidate["family"] for candidate in manifest["candidates"]],
            ["yolox_nano", "nanodet_plus"],
        )
        self.assertLessEqual(len(manifest["candidates"]), bakeoff.MAX_FAMILIES)
        self.assertLessEqual(
            sum(candidate["configuration_count"] for candidate in manifest["candidates"]),
            bakeoff.MAX_EXPERIMENTS,
        )

    def test_unclear_weight_terms_fail_before_evaluation(self) -> None:
        manifest = bakeoff.load_and_validate_candidates(
            Path(__file__).with_name("detector_candidates.json")
        )
        for candidate in manifest["candidates"]:
            eligible, reason = bakeoff.deployment_eligible(candidate)
            self.assertFalse(eligible)
            self.assertEqual(reason, "pretrained-weight license is unclear")

    def test_weight_gate_requires_license_and_commercial_redistribution(self) -> None:
        candidate = {
            "source_code": {"proprietary_distribution_reasonably_permitted": True},
            "pretrained_weights": {
                "status": "clear",
                "license_spdx": "Apache-2.0",
                "commercial_redistribution_explicit": False,
            },
        }
        eligible, reason = bakeoff.deployment_eligible(candidate)
        self.assertFalse(eligible)
        self.assertIn("commercial redistribution", reason)
        candidate["pretrained_weights"]["commercial_redistribution_explicit"] = True
        self.assertEqual(bakeoff.deployment_eligible(candidate), (True, "license gate passed"))

    def test_frozen_safe_pipeline_thresholds_are_unchanged(self) -> None:
        config = asdict(bakeoff.assert_frozen_baseline_config())
        self.assertEqual(config["detector"], "yolo11n")
        self.assertFalse(config["use_lk"])
        self.assertEqual(config["high_confidence"], 0.30)
        self.assertEqual(config["low_confidence"], 0.30)
        self.assertEqual(config["iou_gate"], 0.15)
        self.assertEqual(config["distance_gate"], 0.24)
        self.assertEqual(config["appearance_gate"], 0.45)
        self.assertEqual(config["ambiguity_margin"], 0.10)
        self.assertEqual(config["missing_ttl_s"], 0.4)
        self.assertFalse(config["persistent_identity_safety"])

    def test_scope_validator_rejects_more_than_eight_experiments(self) -> None:
        manifest_path = Path(__file__).with_name("detector_candidates.json")
        payload = json.loads(manifest_path.read_text(encoding="utf-8"))
        payload["candidates"][0]["configuration_count"] = 5
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "invalid.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "configuration count"):
                bakeoff.load_and_validate_candidates(path)


if __name__ == "__main__":
    unittest.main()
