"""充电器体检的数据分析。

对应 App 侧 `ChargerProbe.java` + `ProtocolGuess.java`，公式必须一致。

⚠️ 物理边界（写在这里免得以后自己忘了）：

    手机能读到的是**电池侧**的电压电流，读不到 USB 输入侧 VBUS/IBUS。
    中间隔着充电管理芯片，效率 85–92%。
    所以「功率」是电池吃进去的功率，不是充电器输出功率。
    协议同理：Android 没有公开 API，只能从功率台阶反推档位区间。

评测充电器要的恰好就是这个：标称多少不重要，能给这台手机喂多少才重要。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Iterable, Optional, Sequence

# 采样与判据，和 Java 侧逐字对齐
INTERVAL_MS = 200
DURATION_MS = 90_000
STEP_MIN_W = 2.0
STEP_MIN_REL = 0.15
STEP_MERGE_MS = 1500
CHARGER_EFFICIENCY = 0.88


@dataclass
class ProbePoint:
    t_ms: float
    vbat: float          # V
    ibat: float          # A，充电为正
    temp_c: float
    soc_pct: int
    status: str = ""
    p_watt: float = 0.0

    def __post_init__(self) -> None:
        if self.p_watt == 0.0:
            self.p_watt = abs(self.vbat * self.ibat)


@dataclass
class ProbeResult:
    valid: bool = False
    note: str = ""
    points: int = 0
    duration_ms: float = 0.0
    peak_w: float = 0.0
    peak_at_ms: float = 0.0
    steady_w: float = 0.0
    stability_pct: float = 0.0
    steps: int = 0
    step_events: list[tuple[float, float]] = field(default_factory=list)
    energy_wh: float = 0.0
    avg_temp: float = 0.0
    peak_temp: float = 0.0
    peak_early: bool = False


def _median(v: Sequence[float]) -> float:
    if not v:
        return 0.0
    s = sorted(v)
    n = len(s)
    return s[n // 2] if n % 2 == 1 else (s[n // 2 - 1] + s[n // 2]) / 2.0


def detect_steps(pts: Sequence[ProbePoint]) -> list[tuple[float, float]]:
    """功率台阶：相邻点 ΔP ≥ max(2W, 15%)，1.5 秒内的合并成一次。"""
    out: list[tuple[float, float]] = []
    has_pending = False
    best_delta = 0.0
    best_ts = 0.0

    for i in range(1, len(pts)):
        prev, cur = pts[i - 1].p_watt, pts[i].p_watt
        dp = cur - prev
        if abs(dp) < STEP_MIN_W or abs(dp) / max(abs(prev), 1.0) < STEP_MIN_REL:
            continue

        ts = pts[i].t_ms - pts[0].t_ms
        if has_pending and ts - best_ts <= STEP_MERGE_MS:
            if abs(dp) > abs(best_delta):
                best_delta, best_ts = dp, ts
        else:
            if has_pending:
                out.append((best_ts / 1000.0, best_delta))
            best_delta, best_ts = dp, ts
            has_pending = True

    if has_pending:
        out.append((best_ts / 1000.0, best_delta))
    return out


def analyze(pts: Sequence[ProbePoint]) -> ProbeResult:
    r = ProbeResult()
    if pts is None or len(pts) < 5:
        r.note = "采样点太少，至少要有 5 个"
        return r

    r.points = len(pts)
    r.duration_ms = pts[-1].t_ms - pts[0].t_ms
    if r.duration_ms < 5000:
        r.note = "时长不足 5 秒，看不到协商和稳态"
        return r

    peak, peak_at = 0.0, 0.0
    temp_sum, temp_peak = 0.0, -100.0
    for p in pts:
        if p.p_watt > peak:
            peak, peak_at = p.p_watt, p.t_ms - pts[0].t_ms
        temp_sum += p.temp_c
        temp_peak = max(temp_peak, p.temp_c)

    r.peak_w = peak
    r.peak_at_ms = peak_at
    r.avg_temp = temp_sum / len(pts)
    r.peak_temp = temp_peak
    r.peak_early = peak_at < r.duration_ms * 0.1

    tail = sorted(p.p_watt for p in pts[len(pts) // 2:])
    med = _median(tail)
    r.steady_w = med
    if med > 0.5 and tail:
        n = len(tail)
        p5 = tail[max(0, int(n * 0.05))]
        p95 = tail[min(n - 1, int(n * 0.95) - 1 if int(n * 0.95) - 1 >= 0 else 0)]
        r.stability_pct = (p95 - p5) / med * 100.0

    wh = 0.0
    for i in range(1, len(pts)):
        dt = (pts[i].t_ms - pts[i - 1].t_ms) / 1000.0
        if dt <= 0:
            continue
        wh += (pts[i - 1].p_watt + pts[i].p_watt) / 2.0 * dt / 3600.0
    r.energy_wh = wh

    r.step_events = detect_steps(pts)
    r.steps = len(r.step_events)

    r.valid = r.peak_w > 0.5
    if not r.valid:
        r.note = "全程功率接近 0，没在充电？"
    return r


# ---------------------------------------------------------------- 协议推断

@dataclass
class Guess:
    tier: str = ""
    candidates: str = ""
    confidence: str = "低"
    reason: str = ""
    input_w: float = 0.0
    verdict: str = ""


def guess_protocol(r: ProbeResult, charging: bool = True) -> Guess:
    """由电池侧功率反推输入档位区间。

    置信度最高只给「中」。这不是保守，是这个测量方法的上限 ——
    看不到 VBUS 就分不清 20V/2.75A 和 11V/5A。
    """
    g = Guess()
    if r is None or not r.valid:
        g.verdict = "没采到有效的充电数据，判断不了"
        return g
    if not charging:
        g.verdict = "这段时间不在充电，协议无从谈起"
        return g

    g.input_w = r.peak_w / CHARGER_EFFICIENCY
    w = g.input_w

    tiers = [
        (7.5, "5V/1.5A 级", "BC1.2 基础档 / 电脑 USB 口", "中"),
        (12.5, "5V/2A 级", "BC1.2 上限 / Apple 2.4A / 5V 直充", "中"),
        (20.0, "9V/2A 级", "QC2 / PD 15–18W", "中"),
        (30.0, "9V/3A 级", "QC3 / PD 27W", "中"),
        (45.0, "11V/3A 或 15V/2A 级", "PD 30–40W / SCP 33W", "低"),
        (65.0, "20V/2.25A 或 11V/5A 级", "PD 45W / SCP 55W", "低"),
        (100.0, "20V/3.25A 或 10V/6.5A 级", "PD 65W / SCP 66W", "低"),
    ]
    for edge, tier, cand, conf in tiers:
        if w < edge:
            g.tier, g.candidates, g.confidence = tier, cand, conf
            break
    else:
        g.tier, g.candidates, g.confidence = "20V/5A 以上", "PD 100W+ / 私有大电流协议", "低"

    why = f"电池侧峰值 {r.peak_w:.1f} W，按 88% 效率反推输入约 {g.input_w:.1f} W"
    if r.steps > 0:
        why += f"；起始阶段检测到 {r.steps} 次功率台阶，说明发生过协商"
        if g.input_w < 12.5:
            g.confidence = "中"
            why += "，但功率停在 5V 区间，可能是协商没谈成"
    else:
        why += "；没有检测到台阶，可能是 5V 基础档，或采样开始得太晚错过了协商"
        if g.input_w >= 20:
            g.confidence = "低"
    g.reason = why

    if r.peak_early:
        g.verdict = (f"峰值 {r.peak_w:.1f} W 出现在第 {r.peak_at_ms / 1000:.0f} 秒，"
                     f"之后掉到 {r.steady_w:.1f} W —— 冲得上去但压不住，"
                     "多半是温度或输入功率限制介入")
    else:
        g.verdict = f"峰值 {r.peak_w:.1f} W，稳态 {r.steady_w:.1f} W，能维持在峰值附近"
    return g


def health_note(r: ProbeResult) -> str:
    if r is None or not r.valid:
        return ""
    parts: list[str] = []
    if r.stability_pct > 0:
        if r.stability_pct < 8:
            parts.append(f"输出很稳（稳态波动 {r.stability_pct:.1f}%）")
        elif r.stability_pct < 20:
            parts.append(f"输出有起伏（稳态波动 {r.stability_pct:.1f}%）")
        else:
            parts.append(f"输出抖得厉害（稳态波动 {r.stability_pct:.1f}%），"
                         "可能是线材压降或充电器保护在反复动作")
    if r.energy_wh > 0:
        parts.append(f"{r.duration_ms / 60000:.2f} 分钟充进 {r.energy_wh:.3f} Wh")
    if r.peak_temp > 0:
        parts.append(f"电池温度 {r.avg_temp:.1f} °C（峰值 {r.peak_temp:.1f} °C）")
    return "；".join(parts)


def load_probe_csv(path: str) -> list[ProbePoint]:
    """读 App 导出的 probe_*.csv。"""
    out: list[ProbePoint] = []
    with open(path, "r", encoding="utf-8") as f:
        for i, line in enumerate(f):
            line = line.strip()
            if not line or i == 0:
                continue
            p = line.split(",")
            if len(p) < 7:
                continue
            try:
                out.append(ProbePoint(
                    t_ms=float(p[0]) * 1000.0,
                    vbat=float(p[1]),
                    ibat=float(p[2]),
                    temp_c=float(p[3]),
                    soc_pct=int(float(p[4])),
                    status=p[5],
                    p_watt=float(p[6]),
                ))
            except ValueError:
                continue
    return out
