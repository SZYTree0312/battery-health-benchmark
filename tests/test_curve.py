"""曲线拟合与图表的回归锁。

重点不是"能跑"，而是三个数在变：
  1. 完美直线的斜率和 R² 必须精确（算法错了这里立刻红）
  2. 稀疏真机会话的 R² 该低就低——长空档连出来的直线本来就是假的平滑
  3. 库仑计反推的 SOC 必须与起点对齐，不能从 0 跳上来

`python -m unittest discover -s tests -v`
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from analyzer.core import Sample, analyze, load_csv  # noqa: E402
from analyzer.curve import (  # noqa: E402
    counter_soc, gap_seconds, hours, linear_fit, max_deviation, soc, summarize,
    time_weights, to_svg,
)

FIXTURES = Path(__file__).resolve().parent / "fixtures"


def make(points: list[tuple[float, float, float]]) -> list[Sample]:
    """(t_s, soc, counter_mah) -> Sample 列表。"""
    return [Sample(t=t, current_ma=800, voltage_mv=4000, soc_pct=s,
                   temp_c=25.0, status="discharging", counter_mah=c)
            for t, s, c in points]


class LinearFitTest(unittest.TestCase):

    def test_perfect_line(self) -> None:
        xs = [0, 1, 2, 3, 4]
        ys = [100, 90, 80, 70, 60]
        line = linear_fit(xs, ys)
        self.assertTrue(line.valid)
        self.assertAlmostEqual(line.slope, -10.0, places=9)
        self.assertAlmostEqual(line.intercept, 100.0, places=9)
        self.assertAlmostEqual(line.r2, 1.0, places=9)
        self.assertAlmostEqual(line.rate_pct_per_h, 10.0, places=9)

    def test_noise_drops_r2(self) -> None:
        xs = [0, 1, 2, 3]
        ys = [100, 80, 95, 60]          # 明显不是直线
        line = linear_fit(xs, ys)
        self.assertTrue(line.valid)
        self.assertLess(line.r2, 0.85)

    def test_flat_line_is_r2_one(self) -> None:
        # SOC 全程不动：SS_tot=0，R² 无定义，按"完美"处理，不能变成 0 或 NaN
        line = linear_fit([0, 1, 2], [50, 50, 50])
        self.assertTrue(line.valid)
        self.assertAlmostEqual(line.slope, 0.0, places=9)
        self.assertAlmostEqual(line.r2, 1.0, places=9)

    def test_too_few_points(self) -> None:
        self.assertFalse(linear_fit([0, 1], [100, 90]).valid)
        self.assertFalse(linear_fit([0, 0, 0], [100, 90, 80]).valid)   # 时间没有跨度


class CounterSocTest(unittest.TestCase):

    def test_aligned_to_start(self) -> None:
        # 起点 80%，设计容量 5000 mAh，掉了 500 mAh → 应该反推到 70%
        seq = make([(0, 80, 4000), (3600, 75, 3750), (7200, 70, 3500)])
        out = counter_soc(seq, 5000)
        self.assertIsNotNone(out)
        self.assertAlmostEqual(out[0], 80.0, places=6)
        self.assertAlmostEqual(out[-1], 70.0, places=6)

    def test_charging_direction(self) -> None:
        seq = make([(0, 20, 1000), (3600, 40, 2000)])
        seq = [Sample(**{**s.__dict__, "status": "charging"}) for s in seq]
        out = counter_soc(seq, 5000, charge_mode=True)
        self.assertAlmostEqual(out[-1], 40.0, places=6)

    def test_missing_counter_returns_none(self) -> None:
        seq = make([(0, 80, 0), (3600, 75, 0)])
        self.assertIsNone(counter_soc(seq, 5000))

    def test_deviation_helper(self) -> None:
        self.assertAlmostEqual(max_deviation([10, 20], [12, 18]), 2.0)
        self.assertEqual(max_deviation(None, [1]), -1.0)


class RealSessionTest(unittest.TestCase):
    """真机稀疏会话：图能画，但 R² 必须诚实地低。"""

    @classmethod
    def setUpClass(cls) -> None:
        # 必须用分析器切出来的那一段，而不是整份 CSV ——
        # 整份里混着充电段，拿它算反推会把方向搞反。
        cls.result = analyze(load_csv(str(FIXTURES / "sparse_day.csv")), 7000)
        cls.seq = list(cls.result.segment)

    def test_basic_shape(self) -> None:
        s = summarize(self.seq, 7000)
        self.assertEqual(s["points"], len(self.seq))
        self.assertGreater(s["hours"], 16)
        self.assertLess(s["hours"], 19)
        # 拟合斜率（时间加权）落在 3.5–6 %/h。注意它不是端点斜率：
        # 96%→9% 端点连线是 5.04 %/h，拟合给 4.0 —— 差的这 1 个点就是曲线
        # 本身的弯曲量，骗不了人。测试锁的是"加权后不该退化成 3.5 以下"。
        self.assertGreater(s["rate_pct_per_h"], 3.5)
        self.assertLess(s["rate_pct_per_h"], 6)

    def test_time_weighting_pulls_toward_truth(self) -> None:
        """等权回归会被密集采样的活跃期带偏，时间加权必须更贴近端点斜率。

        这是 v0.4.5 的核心修正：同一份数据等权算 3.51 %/h、时间加权 3.99 %/h、
        端点连线 5.04 %/h。加权不是让数字变大，是让它别再被采样密度左右。
        """
        xs = hours(self.seq)
        ys = soc(self.seq)
        naive = linear_fit(xs, ys)
        weighted = linear_fit(xs, ys, time_weights(xs))
        endpoint = (ys[0] - ys[-1]) / xs[-1]      # 端点斜率，正数

        self.assertGreater(naive.rate_pct_per_h, 0)
        # 加权后的斜率应当比等权更靠近端点斜率
        self.assertLess(abs(weighted.rate_pct_per_h - endpoint),
                        abs(naive.rate_pct_per_h - endpoint))
        self.assertGreater(weighted.rate_pct_per_h, naive.rate_pct_per_h)

    def test_r2_is_honest(self) -> None:        # Doze 把采样拉出 7 小时空档，直线连过去必然失真。
        # 如果这里算出 0.999，说明我们把空档当成真实曲线了 —— 那是撒谎。
        s = summarize(self.seq, 7000)
        self.assertLess(s["r2"], 0.99)
        self.assertGreater(s["max_gap_s"], 6 * 3600)

    def test_counter_curve_matches_measured(self) -> None:
        # 这份真机上系统百分比和库仑计应该大体一致，偏差不该超过 10 个点
        s = summarize(self.seq, 7000)
        self.assertGreater(s["counter_dev_pct"], 0)
        self.assertLess(s["counter_dev_pct"], 10)

    def test_hours_start_at_zero(self) -> None:
        xs = hours(self.seq)
        self.assertAlmostEqual(xs[0], 0.0)
        self.assertEqual(len(xs), len(self.seq))

    def test_gaps_length(self) -> None:
        self.assertEqual(len(gap_seconds(self.seq)), len(self.seq) - 1)


class SvgTest(unittest.TestCase):

    def test_svg_contains_all_layers(self) -> None:
        seq = make([(0, 100, 5000), (3600, 90, 4500), (7200, 80, 4000)])
        svg = to_svg(seq, 5000)
        self.assertTrue(svg.startswith("<svg"))
        self.assertTrue(svg.rstrip().endswith("</svg>"))
        self.assertIn("#2654be", svg)        # 实测线
        self.assertIn("#969696", svg)        # 拟合虚线
        self.assertIn("#d68224", svg)        # 库仑计线
        self.assertIn("R²=", svg)

    def test_svg_without_counter(self) -> None:
        seq = make([(0, 100, 0), (3600, 90, 0), (7200, 80, 0)])
        svg = to_svg(seq, 5000)
        self.assertNotIn("#d68224", svg)     # 没有库仑计就不画那条线


if __name__ == "__main__":
    unittest.main()
