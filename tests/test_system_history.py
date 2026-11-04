"""系统电池历史解析与对齐测试。

真机格式来自 `adb shell dumpsys batterystats --history`：
    11-04 18:00:00.116 008 status=charging ... charge=1346 ...
                        ^^^ 电量百分比（3 位，前导零）
"""

import sys
import unittest
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from analyzer.system_history import (  # noqa: E402
    Point, align, compare, interp, parse_history, parse_session_start, to_svg,
)
from analyzer.core import Sample  # noqa: E402

SAMPLE = """Battery History [Format: 2] (100% used, 4098KB used of 4096KB):
  11-04 18:00:00.116 008 status=charging health=good plug=ac charge=1346 +screen
  11-04 18:00:03.083 008 +wifi_scan
  11-04 18:00:03.110 008 -wifi_scan
  11-04 18:05:12.900 009 status=charging charge=1350
  11-04 18:09:44.000 010 +wake_lock
  11-04 19:20:00.000 100 status=full
  11-04 21:15:00.000 100
not a history line
"""


class ParseTest(unittest.TestCase):

    def test_parse_dedup(self) -> None:
        pts = parse_history(SAMPLE, 2026)
        # 重复电量行要丢掉，只留真正变化的 4 次
        self.assertEqual(len(pts), 4)
        self.assertEqual([p[1] for p in pts], [8.0, 9.0, 10.0, 100.0])

    def test_parse_timestamp(self) -> None:
        pts = parse_history(SAMPLE, 2026)
        self.assertEqual(pts[0][0], datetime(2026, 11, 4, 18, 0, 0, 116000))
        self.assertEqual(pts[-1][0], datetime(2026, 11, 4, 19, 20, 0, 0))

    def test_session_start(self) -> None:
        t = parse_session_start("session_20261104_184944.csv")
        self.assertEqual(t, datetime(2026, 11, 4, 18, 49, 44))

    def test_bad_session_name(self) -> None:
        with self.assertRaises(ValueError):
            parse_session_start("foo.csv")


class AlignTest(unittest.TestCase):

    def test_clip_to_window(self) -> None:
        start = datetime(2026, 11, 4, 18, 0, 0)
        raw = [(datetime(2026, 11, 4, 17, 59, 0), 5.0),
               (datetime(2026, 11, 4, 18, 1, 0), 8.0),
               (datetime(2026, 11, 4, 18, 10, 0), 20.0),
               (datetime(2026, 11, 4, 18, 40, 0), 60.0)]
        pts = align(raw, start, 900)       # 15 分钟窗口
        self.assertEqual([p.soc for p in pts], [5.0, 8.0, 20.0])
        self.assertEqual([p.t for p in pts], [0.0, 60.0, 600.0])

    def test_interp(self) -> None:
        pts = [Point(0, 10), Point(100, 20), Point(200, 30)]
        self.assertAlmostEqual(interp(pts, 50), 15.0)
        self.assertAlmostEqual(interp(pts, 0), 10.0)
        self.assertAlmostEqual(interp(pts, 200), 30.0)
        self.assertIsNone(interp(pts, 300))
        self.assertIsNone(interp([], 10))

    def test_compare_identical(self) -> None:
        seq = [Sample(t=0, soc_pct=10, current_ma=0, voltage_mv=0,
                      temp_c=25, status="charging", counter_mah=0),
               Sample(t=100, soc_pct=20, current_ma=0, voltage_mv=0,
                      temp_c=25, status="charging", counter_mah=0)]
        c = compare(seq, [Point(0, 10), Point(100, 20)])
        self.assertEqual(c["matched"], 2)
        self.assertAlmostEqual(c["mean_abs_dev_pct"], 0.0)
        self.assertAlmostEqual(c["max_abs_dev_pct"], 0.0)

    def test_compare_one_percent_quantization(self) -> None:
        """真机上两条线的差不会超过 1 个点，因为读的是同一个 EXTRA_LEVEL。"""
        seq = [Sample(t=t, soc_pct=v, current_ma=0, voltage_mv=0,
                      temp_c=25, status="charging", counter_mah=0)
               for t, v in ((0, 10), (50, 15), (100, 20))]
        c = compare(seq, [Point(0, 10.5), Point(100, 20.5)])
        self.assertAlmostEqual(c["mean_abs_dev_pct"], 0.5)
        self.assertLessEqual(c["max_abs_dev_pct"], 1.0)


class SvgTest(unittest.TestCase):

    def test_both_layers(self) -> None:
        seq = [Sample(t=t, soc_pct=v, current_ma=0, voltage_mv=0,
                      temp_c=25, status="charging", counter_mah=0)
               for t, v in ((0, 20), (60, 25), (120, 30))]
        svg = to_svg(seq, [Point(0, 20), Point(120, 30)])
        self.assertIn("<svg", svg)
        self.assertEqual(svg.count("<polyline"), 2)
        self.assertIn("系统电池历史", svg)
        self.assertIn("App 采样", svg)


if __name__ == "__main__":
    unittest.main()
