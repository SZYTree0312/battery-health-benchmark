"""充电器体检的回归锁。

三个判据各有各的坑：
  1. 台阶检测 — 波动大的曲线不能被当成"很多次协商"，否则协议全判错
  2. 稳态 — 必须避开起始爬升，否则"稳不稳定"根本无从谈起
  3. 峰值早不早 — 决定"冲得上去压不住"这句判词对不对

合成曲线比真机更适合锁这些：真机每次充电都不一样，合成数据能精确控制形状。

`python -m unittest discover -s tests -v`
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from analyzer.probe import (  # noqa: E402
    ProbePoint, analyze, detect_steps, guess_protocol, health_note, load_probe_csv,
)


def synth(segments, interval_ms: int = 200, temp: float = 30.0,
          soc: int = 50) -> list[ProbePoint]:
    """按 (秒数, 电压V, 电流A) 列表拼一条合成曲线。"""
    out: list[ProbePoint] = []
    t = 0.0
    for seconds, vbat, ibat in segments:
        n = int(seconds * 1000 / interval_ms)
        for _ in range(n):
            out.append(ProbePoint(t_ms=t, vbat=vbat, ibat=ibat,
                                  temp_c=temp, soc_pct=soc, status="charging",
                                  plugged=True))
            t += interval_ms
    return out


def with_noise(segments, amp: float, interval_ms: int = 200):
    """段内给电流叠方波噪声，模拟劣质充电器 / 线材压降。"""
    pts = synth(segments, interval_ms)
    for i, p in enumerate(pts):
        if i % 2 == 0:
            p.ibat *= (1 + amp)
        else:
            p.ibat *= (1 - amp)
        p.p_watt = abs(p.vbat * p.ibat)
    return pts


class StepDetectionTest(unittest.TestCase):

    def test_single_negotiation_step(self) -> None:
        # 3 秒 5V 基础档 → 协商 → 大电流
        pts = synth([(3, 4.2, 0.5), (60, 4.2, 5.5)])
        r = analyze(pts)
        self.assertTrue(r.valid)
        self.assertEqual(r.steps, 1, "一次跃变必须只算一个台阶")
        self.assertGreater(r.step_events[0][1], 15)   # 幅度约 21 W

    def test_ramp_is_not_a_step(self) -> None:
        # 缓慢爬升（每个点变化 < 15%）不该被当成协商
        pts = []
        t = 0.0
        for i in range(200):
            pts.append(ProbePoint(t_ms=t, vbat=4.2, ibat=1.0 + i * 0.01,
                                  plugged=True,
                                  temp_c=30, soc_pct=50, status="charging"))
            t += 200
        r = analyze(pts)
        self.assertEqual(r.steps, 0, "渐进爬升不是台阶")

    def test_noise_is_not_steps(self) -> None:
        """方波噪声幅度 25% 会被判成台阶 —— 这是已知局限，必须锁住行为。

        台阶判据是 15%，噪声 25% 超过阈值，所以会误判。真正规避靠识别
        "连续密集的跃变"，那样又会吃掉真实的多次协商。当前选择宁可多报，
        因为结果页把台阶数当"证据"用，不是当结论用。
        """
        pts = with_noise([(30, 4.2, 5.0)], amp=0.25)
        r = analyze(pts)
        self.assertGreater(r.steps, 5)
        # 关键：误判不影响峰值和稳态
        self.assertAlmostEqual(r.peak_w, 4.2 * 5.0 * 1.25, delta=0.5)

    def test_stable_output_has_no_steps(self) -> None:
        pts = synth([(60, 4.2, 5.5)])
        r = analyze(pts)
        self.assertEqual(r.steps, 0)


class SteadyStateTest(unittest.TestCase):

    def test_steady_ignores_early_ramp(self) -> None:
        # 前 10 秒只有 3 W，后面稳定 23 W：稳态要取后面
        pts = synth([(10, 4.2, 0.7), (60, 4.2, 5.5)])
        r = analyze(pts)
        self.assertAlmostEqual(r.steady_w, 4.2 * 5.5, delta=0.3)
        self.assertLess(r.stability_pct, 1.0)

    def test_unstable_charger_is_flagged(self) -> None:
        pts = with_noise([(60, 4.2, 4.0)], amp=0.4)
        r = analyze(pts)
        self.assertGreater(r.stability_pct, 20)
        self.assertIn("抖得厉害", health_note(r))

    def test_energy_accumulates(self) -> None:
        # 20 W 跑 60 秒 = 0.333 Wh
        pts = synth([(60, 4.0, 5.0)])
        r = analyze(pts)
        self.assertAlmostEqual(r.energy_wh, 0.333, delta=0.01)


class PeakShapeTest(unittest.TestCase):

    def test_peak_then_drop(self) -> None:
        pts = synth([(5, 4.2, 10.7), (60, 4.2, 4.3)])   # 45 W → 18 W
        r = analyze(pts)
        self.assertAlmostEqual(r.peak_w, 44.94, delta=0.5)
        self.assertAlmostEqual(r.steady_w, 18.06, delta=0.5)
        self.assertTrue(r.peak_early, "峰值出现在前 10% 应判为 peak_early")

    def test_peak_at_end_is_not_early(self) -> None:
        pts = synth([(40, 4.2, 3.0), (5, 4.2, 10.0)])
        r = analyze(pts)
        self.assertFalse(r.peak_early)

    def test_too_few_points(self) -> None:
        r = analyze(synth([(0.5, 4.2, 5.0)]))
        self.assertFalse(r.valid)
        self.assertIn("太少", r.note)

    def test_zero_power_rejected(self) -> None:
        pts = synth([(30, 4.2, 0.0)])
        r = analyze(pts)
        self.assertFalse(r.valid)


class ProtocolTierTest(unittest.TestCase):
    """档位边界。峰值换算是 peak / 0.88。"""

    def tier_for(self, peak_w: float, steps: int = 1) -> str:
        pts = synth([(1, 4.2, 0.1), (60, 4.2, peak_w / 4.2)])
        r = analyze(pts)
        g = guess_protocol(r, charging=True)
        return g.tier

    def test_boundaries(self) -> None:
        self.assertIn("5V/1.5A", self.tier_for(5))
        self.assertIn("5V/2A", self.tier_for(10))
        self.assertIn("9V/2A", self.tier_for(15))
        self.assertIn("9V/3A", self.tier_for(25))
        self.assertIn("20V/2.25A", self.tier_for(40))
        self.assertIn("20V/3.25A", self.tier_for(60))
        self.assertIn("20V/5A 以上", self.tier_for(100))

    def test_confidence_never_high(self) -> None:
        # 反推法的物理上限：看不到 VBUS，最高只能是「中」
        for peak in (5, 15, 25, 40, 70, 120):
            g = guess_protocol(analyze(synth([(60, 4.2, peak / 4.2)])), True)
            self.assertIn(g.confidence, ("中", "低"), f"peak={peak}")

    def test_not_charging(self) -> None:
        pts = synth([(30, 4.2, 5.0)])
        r = analyze(pts)
        g = guess_protocol(r, charging=False)
        self.assertIn("不在充电", g.verdict)

    def test_input_power_uses_efficiency(self) -> None:
        pts = synth([(60, 4.2, 5.0)])       # 21 W 电池侧
        g = guess_protocol(analyze(pts), True)
        self.assertAlmostEqual(g.input_w, 21 / 0.88, delta=0.1)


class CsvRoundTripTest(unittest.TestCase):

    def test_write_then_read(self) -> None:
        pts = synth([(2, 4.2, 0.5), (10, 4.2, 5.5)])
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "probe.csv"
            with path.open("w", encoding="utf-8") as f:
                f.write("t_s,vbat_v,ibat_a,temp_c,soc_pct,status,p_watt\n")
                for p in pts:
                    f.write(f"{p.t_ms / 1000:.2f},{p.vbat:.3f},{p.ibat:.3f},"
                            f"{p.temp_c:.1f},{p.soc_pct},{p.status},{p.p_watt:.2f}\n")

            back = load_probe_csv(str(path))
            self.assertEqual(len(back), len(pts))
            r = analyze(back)
            self.assertEqual(r.steps, 1)
            self.assertAlmostEqual(r.peak_w, 23.1, delta=0.5)


if __name__ == "__main__":
    unittest.main()
