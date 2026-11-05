"""充电器体检：没插充电器时不能算出充电功率。

真实 bug（v0.5.3 之前）：read() 里 pWatt = abs(V×I)，放电电流为负，
取绝对值就变成正的「充电功率」；analyze() 又只看 peakW > 0.5 就判有效。
结果是不插充电器点开始体检，90 秒后照样出峰值 / 稳态 / 充入 Wh，
还能存进充电器档案跟别的充电器比。

现在采集侧（Java ChargerPanel.read）和分析侧（Java ChargerProbe /
Python analyzer.probe）都加了 EXTRA_PLUGGED 判据，这个文件锁住分析侧。
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from analyzer.probe import (  # noqa: E402
    ProbePoint, analyze, is_charge_point, load_probe_csv, looks_charging,
)


def series(n: int, *, plugged: bool, status: str = "charging",
           ibat: float = -2.0, p_watt: float | None = None) -> list[ProbePoint]:
    """200 ms 一点。p_watt 不给时由 __post_init__ 按 plugged 算。"""
    pts = []
    for i in range(n):
        kw = dict(t_ms=i * 200.0, vbat=4.0, ibat=ibat, temp_c=30.0,
                  soc_pct=50, status=status, plugged=plugged)
        if p_watt is not None:
            kw["p_watt"] = p_watt
        pts.append(ProbePoint(**kw))
    return pts


class ChargePointTest(unittest.TestCase):

    def test_plugged_and_charging_both_required(self) -> None:
        self.assertTrue(is_charge_point(ProbePoint(0, 4.0, -2.0, 30, 50,
                                                   "charging", True)))
        self.assertTrue(is_charge_point(ProbePoint(0, 4.0, -2.0, 30, 50,
                                                   "full", True)))
        # status 说在充电，其实早拔线了（ROM 状态滞后）
        self.assertFalse(is_charge_point(ProbePoint(0, 4.0, -2.0, 30, 50,
                                                    "charging", False)))
        # 插着线但状态是放电，也不能算充电功率
        self.assertFalse(is_charge_point(ProbePoint(0, 4.0, 0.5, 30, 50,
                                                    "discharging", True)))

    def test_unplugged_point_never_yields_power(self) -> None:
        p = ProbePoint(0, 4.0, -2.0, 30, 50, "discharging", False)
        self.assertEqual(p.p_watt, 0.0,
                         "放电电流取绝对值会伪造出充电功率，必须直接给 0")


class AnalyzeRejectsDischargeTest(unittest.TestCase):

    def test_all_discharge_points_invalid(self) -> None:
        pts = series(100, plugged=False, status="discharging")
        r = analyze(pts)
        self.assertFalse(r.valid)
        self.assertEqual(r.peak_w, 0.0)
        self.assertEqual(r.energy_wh, 0.0)
        self.assertIn("没有插充电器", r.note)

    def test_forged_power_still_invalid(self) -> None:
        """就算 CSV 里被塞进了正功率，只要没插电也不该出结论。

        这是数据层兜底：不能指望 UI 永远记得拦。
        """
        pts = series(100, plugged=False, status="discharging", p_watt=8.0)
        r = analyze(pts)
        self.assertFalse(r.valid)
        self.assertEqual(r.peak_w, 0.0)

    def test_valid_charge_still_works(self) -> None:
        """别把正常充电也一起打死。"""
        pts = series(100, plugged=True, status="charging")
        r = analyze(pts)
        self.assertTrue(r.valid)
        self.assertAlmostEqual(r.peak_w, 8.0, delta=0.01)
        self.assertGreater(r.energy_wh, 0)

    def test_unplug_midway_invalidates_run(self) -> None:
        # 30 点充电 + 70 点拔线，有效点只有 30%，不能拿前半段出报告
        pts = series(30, plugged=True) + series(70, plugged=False,
                                                status="discharging")
        for i, p in enumerate(pts):
            p.t_ms = i * 200.0
        r = analyze(pts)
        self.assertFalse(r.valid)
        self.assertIn("断开", r.note)

    def test_looks_charging(self) -> None:
        self.assertTrue(looks_charging(series(10, plugged=True)))
        self.assertFalse(looks_charging(series(10, plugged=False,
                                               status="discharging")))


class ProbeCsvCompatTest(unittest.TestCase):

    def _load(self, text: str) -> list[ProbePoint]:
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "probe.csv"
            f.write_text(text, encoding="utf-8")
            return load_probe_csv(str(f))

    def test_new_csv_has_plugged_column(self) -> None:
        lines = ["t_s,vbat_v,ibat_a,temp_c,soc_pct,status,p_watt,plugged"]
        lines += [f"{i * 0.2:.1f},4.0,-2.0,30,50,discharging,0,0"
                  for i in range(6)]
        pts = self._load("\n".join(lines) + "\n")
        self.assertEqual(len(pts), 6)
        self.assertTrue(all(not p.plugged for p in pts))
        self.assertFalse(analyze(pts).valid)

    def test_old_csv_without_plugged_column(self) -> None:
        """老导出的 7 列 CSV 按 status 推断，别把历史数据全判废。"""
        # 30 点 × 200 ms = 6 秒，够 analyze 的 5 秒最短时长
        lines = ["t_s,vbat_v,ibat_a,temp_c,soc_pct,status,p_watt"]
        lines += [f"{i * 0.2:.1f},4.0,-2.0,30,50,charging,8.0"
                  for i in range(30)]
        pts = self._load("\n".join(lines) + "\n")
        self.assertEqual(len(pts), 30)
        self.assertTrue(all(p.plugged for p in pts))
        self.assertTrue(analyze(pts).valid)

    def test_old_csv_discharging_stays_invalid(self) -> None:
        lines = ["t_s,vbat_v,ibat_a,temp_c,soc_pct,status,p_watt"]
        lines += [f"{i * 0.2:.1f},4.0,-2.0,30,50,discharging,8.0"
                  for i in range(6)]
        pts = self._load("\n".join(lines) + "\n")
        self.assertFalse(analyze(pts).valid)

    def test_new_csv_column_order(self) -> None:
        """新版导出：temp_c 是 31.5，soc_pct 是 50，跟表头一致。"""
        lines = ["t_s,vbat_v,ibat_a,temp_c,soc_pct,status,p_watt,plugged"]
        lines += [f"{i * 0.2:.1f},4.0,-2.0,31.5,50,charging,8.0,1"
                  for i in range(30)]
        pts = self._load("\n".join(lines) + "\n")
        self.assertEqual(pts[0].temp_c, 31.5)
        self.assertEqual(pts[0].soc_pct, 50)

    def test_old_csv_reversed_temp_soc(self) -> None:
        """App 旧版把这两列写反了，读到时自动换回来。"""
        lines = ["t_s,vbat_v,ibat_a,temp_c,soc_pct,status,p_watt"]
        lines += [f"{i * 0.2:.1f},4.0,-2.0,50,31.5,charging,8.0"
                  for i in range(30)]
        pts = self._load("\n".join(lines) + "\n")
        self.assertEqual(pts[0].temp_c, 31.5)
        self.assertEqual(pts[0].soc_pct, 50)


if __name__ == "__main__":
    unittest.main()
