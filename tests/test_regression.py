"""回归测试：稀疏采样不得把容量吹到天上。

用 `python -m unittest discover -s tests -v` 跑，零第三方依赖。

fixtures/sparse_day.csv 是 2026-11-04 一台小米 17 的真实会话：
17.25 小时、156 个采样点、最长空档 7.4 小时、SOC 96%→9%。
v0.1 的梯形积分拿唤醒瞬间的电流外推整个空档，算出 9507 mAh（折算后
8959 mAh，健康度 128% 还标"良好"）；硬件库仑计首尾差只有 5795 mAh。
这份数据就是防止它复发的锁。
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from analyzer.core import (  # noqa: E402
    Sample, analyze, gap_stats, integrate, load_csv,
)

FIXTURES = Path(__file__).resolve().parent / "fixtures"
DATA = ROOT / "data"


class SparseSamplingTest(unittest.TestCase):
    """真实稀疏会话：必须走库仑计，且明确报告采样质量。"""

    @classmethod
    def setUpClass(cls) -> None:
        cls.samples = load_csv(str(FIXTURES / "sparse_day.csv"))
        cls.result = analyze(cls.samples, 7000)

    def test_capacity_is_plausible(self) -> None:
        # 旧版是 8959 mAh。修好后应落在 6200–7000 mAh。
        self.assertGreater(self.result.normalized_mah, 6200)
        self.assertLess(self.result.normalized_mah, 7000)
        self.assertIn(self.result.grade, ("良好", "一般"))
        self.assertTrue(self.result.reliable)

    def test_uses_hardware_counter(self) -> None:
        self.assertEqual(self.result.method, "counter-delta")
        self.assertAlmostEqual(self.result.raw_charge_mah, 5795, delta=5)
        self.assertAlmostEqual(self.result.soc_span_pct, 87, delta=1)

    def test_detects_gaps(self) -> None:
        self.assertLess(self.result.coverage_pct, 5)
        self.assertGreater(self.result.max_gap_s, 6 * 3600)
        self.assertGreaterEqual(self.result.long_gaps, 10)

    def test_cross_check_flags_bad_integration(self) -> None:
        q, _ = integrate(self.samples)
        self.assertGreater(q, 9000)                 # 旧算法确实会虚高
        self.assertGreater(self.result.divergence_pct or 0, 50)
        self.assertTrue(any("梯形积分" in w for w in self.result.warnings))

    def test_wrong_design_is_rejected_not_praised(self) -> None:
        # 标称填错（5000）时，旧版会给 179% 并标"良好"
        bad = analyze(self.samples, 5000)
        self.assertEqual(bad.grade, "数据异常")
        self.assertFalse(bad.reliable)
        self.assertTrue(any("物理合理区间" in w for w in bad.warnings))

    def test_gap_stats(self) -> None:
        max_gap, coverage, long_gaps = gap_stats(self.samples)
        self.assertGreater(max_gap, 26000)
        self.assertLess(coverage, 5)
        self.assertEqual(long_gaps, 14)


class SyntheticTest(unittest.TestCase):
    """三份合成数据：数值必须仍可预期。

    data/*.csv 被 .gitignore 排除，clone 下来是没有的。
    缺文件时现场生成，保证测试在干净仓库里也能直接跑。
    """

    @classmethod
    def setUpClass(cls) -> None:
        DATA.mkdir(exist_ok=True)
        if not (DATA / "sample_healthy.csv").is_file():
            from analyzer import sample
            sample.write_csv(str(DATA / "sample_healthy.csv"),
                             sample.build_session(4800, 5000, 25.0, 0.062, 1))
            sample.write_csv(str(DATA / "sample_worn.csv"),
                             sample.build_session(3900, 5000, 30.0, 0.115, 2))
        if not (DATA / "sample_charge.csv").is_file():
            from analyzer import sample_charge
            (DATA / "sample_charge.csv").write_text(
                "\n".join(sample_charge.build()) + "\n", encoding="utf-8")

    def _run(self, name: str, design: float = 5000):
        return analyze(load_csv(str(DATA / name)), design)

    def test_healthy(self) -> None:
        r = self._run("sample_healthy.csv")
        self.assertEqual(r.method, "counter-delta")
        self.assertAlmostEqual(r.measured_mah, 4800, delta=30)
        self.assertGreater(r.soh_pct, 90)

    def test_worn(self) -> None:
        r = self._run("sample_worn.csv")
        self.assertAlmostEqual(r.measured_mah, 3900, delta=30)
        self.assertLess(r.soh_pct, 80)

    def test_charge(self) -> None:
        r = self._run("sample_charge.csv")
        self.assertEqual(r.method, "charge-delta")
        self.assertAlmostEqual(r.measured_mah, 4600, delta=30)
        # 电流与库仑计在合成数据里守恒，交叉校验不该报警
        self.assertLess(abs(r.divergence_pct or 0), 5)

    def test_dense_sampling_is_high_quality(self) -> None:
        r = self._run("sample_healthy.csv")
        self.assertGreaterEqual(r.coverage_pct, 99)
        self.assertLessEqual(r.max_gap_s, 60)


class NoCounterSparseTest(unittest.TestCase):
    """把真实会话的库仑计抹掉，只留电流：必须拒绝给健康度，且不能虚高。"""

    @classmethod
    def setUpClass(cls) -> None:
        rows = load_csv(str(FIXTURES / "sparse_day.csv"))
        cls.stripped = [Sample(t=s.t, current_ma=s.current_ma,
                               voltage_mv=s.voltage_mv, soc_pct=s.soc_pct,
                               temp_c=s.temp_c, status=s.status,
                               counter_mah=0.0) for s in rows]
        cls.result = analyze(cls.stripped, 7000)

    def test_not_reliable(self) -> None:
        self.assertFalse(self.result.reliable)
        self.assertEqual(self.result.grade, "数据不足")

    def test_no_inflated_capacity(self) -> None:
        # 长空档改按 SOC 估算，不会像旧版那样把 2 A × 7 h 算进去
        self.assertLess(self.result.normalized_mah, 8000)
        self.assertTrue(any("SOC 变化估算" in w for w in self.result.warnings))


if __name__ == "__main__":
    unittest.main(verbosity=2)
