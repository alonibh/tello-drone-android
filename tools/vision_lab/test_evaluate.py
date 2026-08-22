import unittest

from evaluate import Box, FrameResult, iou, longest_run, state_periods


def result(index: int, state: str, timestamp_ns: int) -> FrameResult:
    return FrameResult(index, index, timestamp_ns, state, "none", None, None)


class VisionLabTest(unittest.TestCase):
    def test_iou(self) -> None:
        self.assertAlmostEqual(iou(Box(0, 0, 10, 10), Box(5, 5, 15, 15)), 25 / 175)

    def test_periods_use_source_timestamps(self) -> None:
        frames = [
            result(1, "Tracked", 0),
            result(2, "Missing", 100_000_000),
            result(3, "Missing", 350_000_000),
            result(4, "Tracked", 500_000_000),
        ]
        self.assertEqual(state_periods(frames, "Missing", 100_000_000)[0]["duration_seconds"], 0.4)

    def test_longest_run(self) -> None:
        frames = [result(1, "Tracked", 0), result(2, "Tracked", 1), result(3, "Lost", 2)]
        self.assertEqual(longest_run(frames, "Tracked"), 2)


if __name__ == "__main__":
    unittest.main()
