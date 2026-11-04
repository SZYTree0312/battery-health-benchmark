"""连续段积分对账：把"采样稀疏"和"电流读数本身偏"分开。

这个指标回答的是一个一直被混淆的问题——电流积分不准，到底是因为
采样断了，还是因为 CURRENT_NOW 这个数本身就不对？

做法：只拿**没有空档**的连续块，在块内比 ∫I dt 与 ΔCC。块内时间连续，
采样率不构成怀疑对象，剩下的偏差只能归给电流读数。

用 `python -m unittest discover -s tests -v` 跑，零第三方依赖。
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from analyzer.core import (  # noqa: E402
    Sample, analyze, audit_integration, integrate,
)

DATA = ROOT / "data"


def build(minutes: float, current_ma: float, i_scale: float = 1.0,
          gap_after_min: float | None = None, gap_minutes: float = 0.0,
          gap_current_ma: float | None = None, step_s: float = 5.0) -> list[Sample]:
    """造一段充电采样。

    - 库仑计严格按真实电流累计（i_scale 只影响记录下来的电流值）
    - i_scale != 1 模拟"电流读数偏了"：库仑计是对的，电流读数是错的
    - gap_after_min 处插一段空档（模拟被系统冻结，期间没有任何采样点）。
      gap_current_ma 是空档期间的真实电流，默认与主段相同；给一个小值就
      模拟"息屏待机时电流掉下来"，全段积分会因此高估。
    """
    if gap_after_min is None:
        pieces = [(0.0, minutes * 60.0)]
    else:
        g0 = gap_after_min * 60.0
        pieces = [(0.0, g0), (g0 + gap_minutes * 60.0,
                              minutes * 60.0 + gap_minutes * 60.0)]

    gap_ma = current_ma if gap_current_ma is None else gap_current_ma
    out: list[Sample] = []
    counter = 1000.0
    for idx, (t0, t1) in enumerate(pieces):
        if idx > 0:
            counter += gap_ma * gap_minutes * 60.0 / 3600.0
        t = t0
        while t <= t1 + 1e-9:
            out.append(Sample(t=t, current_ma=-current_ma * i_scale, voltage_mv=4000,
                              soc_pct=50.0, temp_c=30.0, status="charging",
                              counter_mah=counter))
            counter += current_ma * step_s / 3600.0
            t += step_s
    return out


class AuditTest(unittest.TestCase):

    def test_consistent_current_has_no_error(self):
        """电流读得准 → 块内积分与库仑计几乎相同。"""
        seg = build(10, 2000)
        a = audit_integration(seg)
        self.assertEqual(a["blocks"], 1)
        self.assertAlmostEqual(a["err_pct"], 0.0, delta=0.5)

    def test_biased_current_is_caught_inside_continuous_block(self):
        """电流读数少报 25% → 连续块内就该看到 -25%，跟采样密不密无关。

        这是本项目的核心结论：真机充电段实测 -20~-43%，块内 5 秒一个点、
        完全没有空档，说明问题出在 CURRENT_NOW 而不是采样率。
        """
        seg = build(10, 2000, i_scale=0.75)
        a = audit_integration(seg)
        self.assertEqual(a["blocks"], 1)
        self.assertLess(a["err_pct"], -20.0)
        self.assertGreater(a["err_pct"], -30.0)

    def test_gaps_do_not_pollute_the_verdict(self):
        """有空档也只看连续块：两块各自准，结论就该是"准"。

        这里空档期间电流掉到 100 mA（息屏待机），而冻结前最后一个点是 2000 mA。
        全段积分会拿 2000 mA 去填那两小时，严重高估——看起来像"积分不可信"，
        其实只是采样断了。对账只统计连续块，不受这个影响。
        """
        seg = build(12, 2000, gap_after_min=6, gap_minutes=120,
                    gap_current_ma=100)
        a = audit_integration(seg)
        self.assertEqual(a["blocks"], 2)
        self.assertAlmostEqual(a["err_pct"], 0.0, delta=0.5)

        full, _ = integrate(seg)
        full_err = (abs(full) - a["counter_mah"]) / a["counter_mah"] * 100
        self.assertGreater(full_err, 50.0, "全段积分应该被空档的插值严重高估")

    def test_short_blocks_are_skipped(self):
        """块短于 5 分钟不参与对账——ΔCC 是 1 mAh 整数，短窗口全是量化噪声。"""
        seg = build(2, 2000)
        a = audit_integration(seg)
        self.assertEqual(a["blocks"], 0)
        self.assertIsNone(a["err_pct"])

    def test_report_carries_audit_and_warning(self):
        """analyze() 要把对账结果带出来，并写进 warning。"""
        report = analyze(build(120, 2000, i_scale=0.75), design_mah=5000)
        self.assertEqual(report.audit_blocks, 1)
        self.assertLess(report.audit_err_pct, -20.0)
        self.assertTrue(any("连续段对账" in w for w in report.warnings))
        self.assertTrue(any("CURRENT_NOW" in w for w in report.warnings))


class RealSessionTest(unittest.TestCase):
    """真机会话：充电 18%→100%，采样被打断 9 次。"""

    def setUp(self):
        path = DATA / "session_20261104_184944.csv"
        if not path.is_file():
            self.skipTest("真机 CSV 不在仓库里（已 gitignore）")

    def test_bias_shows_up_even_without_gaps(self):
        from analyzer.core import load_csv
        seg = load_csv(str(DATA / "session_20261104_184944.csv"))
        a = audit_integration(seg)
        if a["blocks"] == 0:
            self.skipTest("这份数据没有 ≥5 分钟的连续块")
        # 实测 -34%，与 PC 手算、App 结果页三方一致
        self.assertLess(a["err_pct"], -15.0)
        self.assertGreater(a["err_pct"], -50.0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
