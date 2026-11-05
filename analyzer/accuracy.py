"""积分精度验证 —— 对应 App 侧 `AccuracyMath.java`，公式与阈值必须一致。

背景：v0.5.3-rc2 试图在息屏放电时拿连续采样，被 MIUI PowerKeeper 8 秒
收走唤醒锁（issue #1）。v0.5.5 改走安兔兔那条路：充电时页面常驻前台、
屏幕常亮，进程不会被判闲置，采样连续这个前提才成立。

于是一次测量就能回答两个问题：
  1. 多尺度抽稀积分（1/2/5/10/30 秒）收不收敛 —— 采样率还重不重要
  2. 每 2 分钟窗口的 k = ∫I dt / ΔCC 稳不稳 —— 偏差能不能用一个系数标定

基准是库仑计 ΔCC，不是「真值」；量的是两条独立路径的一致性。
"""

from __future__ import annotations

from dataclasses import dataclass, field

# 与 Java 侧逐字对齐
SCALES_S: tuple[float, ...] = (1, 2, 5, 10, 30)
WINDOW_S = 120.0
WINDOW_MIN_MAH = 20.0
MIN_GAP_S = 10.0
CONVERGE_PCT = 2.0
STABLE_CV_PCT = 3.0


@dataclass
class AccSample:
    t_s: float
    current_ma: float
    voltage_mv: float
    soc_pct: float
    temp_c: float
    counter_mah: float
    plugged: bool = True
    status: str = "charging"


@dataclass
class Scale:
    step_s: float
    points: int
    integral_mah: float
    err_pct: float


@dataclass
class Window:
    t_start: float
    integral_mah: float
    counter_mah: float
    k: float
    avg_current_ma: float
    avg_temp_c: float


@dataclass
class AccuracyReport:
    valid: bool = False
    note: str = ""
    points: int = 0
    blocks: int = 0
    gaps: int = 0
    covered_s: float = 0.0
    counter_mah: float = 0.0
    integral_mah: float = 0.0
    k_mean: float = 0.0
    k_std: float = 0.0
    k_cv_pct: float = 0.0
    k_min: float = 0.0
    k_max: float = 0.0
    spread_pct: float = 0.0
    converged: bool = False
    calibratable: bool = False
    calibration: float = 0.0
    scales: list[Scale] = field(default_factory=list)
    windows: list[Window] = field(default_factory=list)


def split_blocks(pts: list[AccSample], max_gap_s: float) -> list[list[AccSample]]:
    """按空档切块。空档期间没有采样点，不能拿两端电流去插值。"""
    if not pts:
        return []
    out: list[list[AccSample]] = [[pts[0]]]
    for prev, cur in zip(pts, pts[1:]):
        if cur.t_s - prev.t_s > max_gap_s:
            out.append([])
        out[-1].append(cur)
    return out


def integrate(block: list[AccSample]) -> float:
    """梯形积分，mAh。mA·s / 3600 = mAh。"""
    q = 0.0
    for a, b in zip(block, block[1:]):
        dt = b.t_s - a.t_s
        if dt > 0:
            q += (a.current_ma + b.current_ma) / 2.0 * dt / 3600.0
    return abs(q)


def decimate(block: list[AccSample], step_s: float) -> list[AccSample]:
    """按真实时间戳抽稀，首尾必留。不能简单每 N 个取一个（间隔会抖）。"""
    if not block:
        return []
    out = [block[0]]
    nxt = block[0].t_s + step_s
    for p in block[1:-1]:
        if p.t_s >= nxt - 1e-9:
            out.append(p)
            nxt = p.t_s + step_s
    if len(block) > 1:
        out.append(block[-1])
    return out


def counter_of(blocks: list[list[AccSample]]) -> float:
    return sum(abs(b[-1].counter_mah - b[0].counter_mah)
               for b in blocks if len(b) >= 2)


def integrate_at(blocks: list[list[AccSample]], step_s: float) -> float:
    return sum(integrate(b if step_s <= 0 else decimate(b, step_s))
               for b in blocks)


def build_windows(blocks: list[list[AccSample]]) -> list[Window]:
    out: list[Window] = []
    for b in blocks:
        if len(b) < 3:
            continue
        frm = 0
        while frm < len(b) - 1:
            to = frm + 1
            while to < len(b) and b[to].t_s - b[frm].t_s < WINDOW_S:
                to += 1
            if to - frm < 3:
                break
            seg = b[frm:to]
            dcc = abs(seg[-1].counter_mah - seg[0].counter_mah)
            if dcc >= WINDOW_MIN_MAH:
                out.append(Window(
                    t_start=seg[0].t_s,
                    integral_mah=integrate(seg),
                    counter_mah=dcc,
                    k=integrate(seg) / dcc,
                    avg_current_ma=sum(abs(p.current_ma) for p in seg) / len(seg),
                    avg_temp_c=sum(p.temp_c for p in seg) / len(seg),
                ))
            if to >= len(b):
                break
            frm = to - 1
    return out


def _verdict(r: AccuracyReport) -> str:
    if not r.converged:
        return (f"跨尺度积分差 {r.spread_pct:.1f}%，还没收敛："
                "采样间隔仍在影响结果，先加密采样再说。")
    if len(r.windows) < 3:
        return (f"积分已跨尺度收敛（差 {r.spread_pct:.1f}%），但有效窗口只有 "
                f"{len(r.windows)} 个，还看不出比值稳不稳，建议跑满 10 分钟。")
    if r.calibratable:
        return (f"积分已收敛（跨尺度差 {r.spread_pct:.1f}%），且 {len(r.windows)} 个窗口的 "
                f"∫I/ΔCC 稳定在 {r.k_mean:.3f}±{r.k_std:.3f}"
                f"（变异系数 {r.k_cv_pct:.1f}%）。说明电流读数是固定比例偏差，"
                f"乘 {r.calibration:.3f} 就能与库仑计对齐——这台机器、这一段充电里可标定。")
    return (f"积分已收敛（跨尺度差 {r.spread_pct:.1f}%），但 ∫I/ΔCC 在 "
            f"{r.k_min:.3f}~{r.k_max:.3f} 之间漂"
            f"（变异系数 {r.k_cv_pct:.1f}%）。偏差随工况变，不能靠单一系数标定。")


def analyze(pts: list[AccSample], base_interval_s: float) -> AccuracyReport:
    r = AccuracyReport()
    if not pts or len(pts) < 10:
        r.note = "采样点太少，至少要 10 个"
        return r

    gap = max(MIN_GAP_S, base_interval_s * 3)
    all_blocks = split_blocks(pts, gap)
    usable = [b for b in all_blocks
              if len(b) >= 3 and b[-1].t_s - b[0].t_s >= WINDOW_S]
    r.blocks = len(all_blocks)
    r.gaps = len(all_blocks) - 1
    if not usable:
        r.note = "没有一段连续采样够 2 分钟，屏幕可能被熄过或页面切到后台了"
        return r

    r.covered_s = sum(b[-1].t_s - b[0].t_s for b in usable)
    r.points = sum(len(b) for b in usable)
    r.counter_mah = counter_of(usable)
    if r.counter_mah < WINDOW_MIN_MAH:
        r.note = "库仑计几乎没变，这一段的电荷量太小，对账没有意义"
        return r

    r.integral_mah = integrate_at(usable, 0)
    min_q = max_q = r.integral_mah
    r.scales.append(Scale(
        step_s=base_interval_s,
        points=r.points,
        integral_mah=r.integral_mah,
        err_pct=(r.integral_mah - r.counter_mah) / r.counter_mah * 100.0,
    ))
    for step in SCALES_S:
        # 更细的尺度没意义；等于原始间隔的已经有第一行了
        if step <= base_interval_s + 1e-9:
            continue
        q = integrate_at(usable, step)
        r.scales.append(Scale(
            step_s=step,
            points=sum(len(decimate(b, step)) for b in usable),
            integral_mah=q,
            err_pct=(q - r.counter_mah) / r.counter_mah * 100.0,
        ))
        min_q, max_q = min(min_q, q), max(max_q, q)
    r.spread_pct = (max_q - min_q) / max_q * 100.0 if max_q > 0 else 0.0
    r.converged = r.spread_pct <= CONVERGE_PCT

    r.windows = build_windows(usable)
    if r.windows:
        ks = [w.k for w in r.windows]
        r.k_mean = sum(ks) / len(ks)
        r.k_std = (sum((k - r.k_mean) ** 2 for k in ks) / len(ks)) ** 0.5
        r.k_cv_pct = r.k_std / r.k_mean * 100.0 if r.k_mean > 0 else 0.0
        r.k_min, r.k_max = min(ks), max(ks)
    r.calibratable = (len(r.windows) >= 3
                      and r.k_cv_pct <= STABLE_CV_PCT and r.converged)
    r.calibration = 1.0 / r.k_mean if r.k_mean > 0 else 0.0
    r.valid = True
    r.note = _verdict(r)
    return r


def headline(r: AccuracyReport) -> str:
    if not r.valid:
        return r.note
    k = r.integral_mah / r.counter_mah if r.counter_mah > 0 else 0
    return f"∫I {r.integral_mah:.0f} mAh  /  ΔCC {r.counter_mah:.0f} mAh  =  {k:.3f}"


# ---------------------------------------------------------------- CSV


CSV_HEADER = "t_s,current_ma,voltage_mv,soc_pct,temp_c,charge_counter_mah,plugged,status"


def to_csv(pts: list[AccSample]) -> str:
    lines = [CSV_HEADER]
    for p in pts:
        lines.append(
            f"{p.t_s:.3f},{p.current_ma:.1f},{p.voltage_mv:.0f},{p.soc_pct:.0f},"
            f"{p.temp_c:.1f},{p.counter_mah:.0f},{1 if p.plugged else 0},{p.status}"
        )
    return "\n".join(lines) + "\n"


def load_accuracy_csv(path: str) -> list[AccSample]:
    """读 App 导出的 accuracy_*.csv。缺 plugged 列时按 status 推断。"""
    out: list[AccSample] = []
    with open(path, "r", encoding="utf-8") as f:
        for i, line in enumerate(f):
            line = line.strip()
            if not line or i == 0:
                continue
            p = line.split(",")
            if len(p) < 6:
                continue
            try:
                st = p[7] if len(p) > 7 else "charging"
                plugged = (int(float(p[6])) > 0 if len(p) > 6
                           else st.lower().startswith(("charg", "full")))
                out.append(AccSample(
                    t_s=float(p[0]), current_ma=float(p[1]),
                    voltage_mv=float(p[2]), soc_pct=float(p[3]),
                    temp_c=float(p[4]), counter_mah=float(p[5]),
                    plugged=plugged, status=st,
                ))
            except ValueError:
                continue
    return out
