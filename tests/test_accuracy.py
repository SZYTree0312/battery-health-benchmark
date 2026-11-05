"""积分精度验证的回归锁。

四组数据分别锁住四个结论：
  1. 读数与库仑计完全一致 → k=1、收敛、可标定
  2. 读数固定比例偏 25% → k=0.75、收敛、可标定、标定系数 1.333
  3. 偏差随 SOC 漂 → k 变异系数大、不可标定
  4. 电流剧烈方波 → 抽稀后积分失真，跨尺度不收敛

外加空档切块、抽稀、CSV 往返。合成数据里库仑计永远按「真实电流」累计，
记录的电流才乘偏差系数 —— 这样 k 的期望值可以直接算出来。

对应 App 侧 AccuracyMath.java，公式与阈值必须一致。
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from analyzer.accuracy import (  # noqa: E402
    AccSample, analyze, counter_of, decimate, integrate, load_accuracy_csv,
    split_blocks, to_csv,
)


def synth(minutes: float, current_ma: float = 3000.0, *,
          scale_fn=None, step_s: float = 1.0, pulse: bool = False,
          gap_after_s: float | None = None, gap_s: float = 0.0) -> list[AccSample]:
    """造一段充电数据。

    counter 按真实电流累计（硬件累计量），current_ma 是「读到的」电流，
    可以乘 scale_fn 模拟读数偏。pulse=True 时每 17 秒插一段 3 秒的电流尖峰
    （周期故意不整除任何抽稀尺度，否则会撞上采样混叠）。
    """
    pts: list[AccSample] = []
    t = 0.0
    counter = 1000.0
    total = minutes * 60.0
    while t <= total:
        real = current_ma
        if pulse and int(t) % 17 < 3:
            real = current_ma * 5
        scale = 1.0 if scale_fn is None else scale_fn(t / total if total else 0)
        pts.append(AccSample(t_s=t, current_ma=-real * scale,  # 充电为负
                             voltage_mv=4200, soc_pct=50, temp_c=30,
                             counter_mah=counter))
        dt = gap_s if gap_after_s is not None and abs(t - gap_after_s) < 1e-9 else step_s
        counter += real * dt / 3600.0
        t += dt
    return pts


class IdealTest(unittest.TestCase):

    def test_perfect_agreement(self) -> None:
        r = analyze(synth(12), 1.0)
        self.assertTrue(r.valid)
        self.assertAlmostEqual(r.integral_mah, r.counter_mah, delta=1.0)
        self.assertAlmostEqual(r.k_mean, 1.0, delta=0.01)
        self.assertTrue(r.converged)
        self.assertTrue(r.calibratable)
        self.assertAlmostEqual(r.calibration, 1.0, delta=0.01)

    def test_fixed_bias_is_calibratable(self) -> None:
        """读数固定少 25%：k=0.75，且跨尺度稳定，能给标定系数。"""
        r = analyze(synth(12, scale_fn=lambda _: 0.75), 1.0)
        self.assertTrue(r.valid)
        self.assertAlmostEqual(r.k_mean, 0.75, delta=0.01)
        self.assertTrue(r.converged, f"spread={r.spread_pct}")
        self.assertTrue(r.calibratable, r.note)
        self.assertAlmostEqual(r.calibration, 1.0 / 0.75, delta=0.02)
        self.assertIn("可标定", r.note)

    def test_drifting_bias_not_calibratable(self) -> None:
        """偏差从 0.6 漂到 1.0：k 一直变，单一系数没意义。"""
        r = analyze(synth(12, scale_fn=lambda f: 0.6 + 0.4 * f), 1.0)
        self.assertTrue(r.valid)
        self.assertTrue(r.converged)
        self.assertFalse(r.calibratable)
        self.assertGreater(r.k_cv_pct, 3.0)
        self.assertIn("不能靠单一系数", r.note)


class ConvergenceTest(unittest.TestCase):

    def test_square_wave_does_not_converge(self) -> None:
        """非周期电流尖峰：抽稀到 10/30 秒会漏掉尖峰，积分明显失真。"""
        r = analyze(synth(12, pulse=True), 1.0)
        self.assertTrue(r.valid)
        self.assertFalse(r.converged, f"spread={r.spread_pct}")
        self.assertGreater(r.spread_pct, 2.0)
        self.assertIn("还没收敛", r.note)

    def test_scales_are_ordered_and_include_raw(self) -> None:
        r = analyze(synth(12), 1.0)
        steps = [s.step_s for s in r.scales]
        self.assertEqual(steps[0], 1.0, "第一行应是原始采样")
        self.assertEqual(steps[1:], [2, 5, 10, 30])
        self.assertTrue(all(s.points >= 2 for s in r.scales))

    def test_short_run_has_too_few_windows(self) -> None:
        r = analyze(synth(3), 1.0)
        self.assertTrue(r.valid)
        self.assertLess(len(r.windows), 3)
        self.assertIn("跑满 10 分钟", r.note)


class GapTest(unittest.TestCase):

    def test_gap_splits_blocks_and_never_interpolates(self) -> None:
        pts = synth(12, gap_after_s=360, gap_s=300)
        blocks = split_blocks(pts, 10.0)
        self.assertEqual(len(blocks), 2)
        r = analyze(pts, 1.0)
        self.assertEqual(r.gaps, 1)
        self.assertTrue(r.valid)
        # 空档期间库仑计涨了，但那段没有采样点；ΔCC 只按块内首尾差求和，
        # 所以积分与 ΔCC 仍应一致（不能用全长首尾差）
        self.assertAlmostEqual(r.integral_mah, r.counter_mah, delta=1.0)

    def test_gap_counter_excludes_blank_charge(self) -> None:
        pts = synth(12, gap_after_s=360, gap_s=300)
        full_dcc = abs(pts[-1].counter_mah - pts[0].counter_mah)
        block_dcc = counter_of(split_blocks(pts, 10.0))
        self.assertGreater(full_dcc, block_dcc, "全长首尾差会算进空档的电荷")


class RealDeviceFixtureTest(unittest.TestCase):
    """真机 65 秒的一段（Xiaomi 25113PN0EC / Android 17，充电 85%）。

    这份数据是 v0.5.5 常亮页面跑出来的：用户切到后台后 onStop 立刻收尾，
    CSV 落盘。留作夹具锁住两件事：
      1. 常亮期间 5 秒采样严格连续（实测抖动约 3 ms），没有空档
      2. App 导出的 8 列格式 PC 端能原样读回来
    时长不足 2 分钟，所以正确行为是判「数据不足」，不是硬凑一个结论。
    """

    PATH = ROOT / "tests" / "fixtures" / "accuracy_real_65s.csv"

    def test_real_csv_parses_and_is_gapless(self) -> None:
        pts = load_accuracy_csv(str(self.PATH))
        self.assertEqual(len(pts), 14)
        gaps = [b.t_s - a.t_s for a, b in zip(pts, pts[1:])]
        self.assertTrue(all(4.9 < g < 5.1 for g in gaps),
                        f"常亮期间不该有空档，实际间隔 {gaps}")
        self.assertTrue(all(p.plugged for p in pts))
        self.assertTrue(all(p.status == "charging" for p in pts))
        self.assertAlmostEqual(pts[-1].counter_mah - pts[0].counter_mah, 18.0,
                               delta=1.0)

    def test_short_real_run_refuses_to_conclude(self) -> None:
        pts = load_accuracy_csv(str(self.PATH))
        r = analyze(pts, 5.0)
        self.assertFalse(r.valid)
        self.assertIn("没有一段连续采样够 2 分钟", r.note)
        # 结论不给，但原始积分量级仍应与 ΔCC 对得上（真机约 0.9，
        # 方向与「CURRENT_NOW 读数偏低」一致）
        q = integrate(pts)
        dcc = pts[-1].counter_mah - pts[0].counter_mah
        self.assertAlmostEqual(q / dcc, 0.9, delta=0.2)


class PrimitiveTest(unittest.TestCase):

    def test_decimate_keeps_endpoints(self) -> None:
        pts = synth(1)
        d = decimate(pts, 5.0)
        self.assertIs(d[0], pts[0])
        self.assertIs(d[-1], pts[-1])
        self.assertTrue(all(b.t_s - a.t_s >= 5.0 - 1e-9
                            for a, b in zip(d, d[1:-1])))

    def test_integrate_units(self) -> None:
        # 3600 mA 恒定 1 秒 = 1 mAh
        seg = [AccSample(0, 3600, 4200, 50, 30, 1000),
               AccSample(1, 3600, 4200, 50, 30, 1001)]
        self.assertAlmostEqual(integrate(seg), 1.0, delta=1e-9)

    def test_csv_round_trip(self) -> None:
        pts = synth(2)
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "accuracy.csv"
            f.write_text(to_csv(pts), encoding="utf-8")
            back = load_accuracy_csv(str(f))
        self.assertEqual(len(back), len(pts))
        self.assertAlmostEqual(back[0].t_s, pts[0].t_s)
        self.assertAlmostEqual(back[-1].counter_mah, pts[-1].counter_mah)
        self.assertTrue(all(p.plugged for p in back))
        r = analyze(back, 1.0)
        self.assertTrue(r.valid)


if __name__ == "__main__":
    unittest.main()
