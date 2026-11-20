"""SOC 曲线的拟合与反推。

对应 App 侧 `CurveFit.java`，公式必须一模一样 —— 同一份 CSV 在手机和电脑上
算出的斜率、R²、偏差如果对不上，那两边看到的图就是在讲两个故事。

三个量：
    linear_fit()      实测 SOC 的最小二乘直线（"按使用时长匀速掉电"的假设线）
    counter_soc()     由库仑计反推的 SOC（检验系统百分比有没有被平滑）
    max_deviation()   两条线的最大偏差

另附 `to_svg()`：零依赖画出 App 里那张同名图，用来在装机前先看效果。
"""

from __future__ import annotations

from dataclasses import dataclass, field
import math
from typing import Optional, Sequence

from .core import Sample


@dataclass
class Line:
    slope: float = 0.0
    intercept: float = 0.0
    r2: float = 0.0
    valid: bool = False
    span: float = 0.0
    segment_count: int = 0   # 分段拟合用到的连续段数，1 表示没有空档

    @property
    def rate_pct_per_h(self) -> float:
        """耗电率，取绝对值 —— 放电时斜率是负的，正数更好读。"""
        return abs(self.slope)


def hours(samples: Sequence[Sample]) -> list[float]:
    """时间轴，单位小时，从 0 开始。"""
    if not samples:
        return []
    t0 = samples[0].t
    return [(s.t - t0) / 3600.0 for s in samples]


def soc(seq: Sequence[Sample]) -> list[float]:
    return [s.soc_pct for s in seq]


def time_weights(xs: Sequence[float]) -> list[float]:
    """每个采样点代表多长时间。

    这一步不能省：Doze 会把 7 小时的采样压成两三个点，按点等权做回归时
    那 7 小时只算了两三票，而密集采样的活跃期每小时好几票 —— 拟合出来的斜率
    会被活跃期带跑，整条线比真实掉电慢一大截（真机数据：实际 5.0 %/h，
    等权回归给出 3.5 %/h）。按"这个点覆盖了多久"加权才是对时间积分。
    """
    n = len(xs)
    if n == 0:
        return []
    if n == 1:
        return [1.0]
    w = [0.0] * n
    for i in range(n):
        if i == 0:
            w[i] = (xs[1] - xs[0]) / 2.0
        elif i == n - 1:
            w[i] = (xs[-1] - xs[-2]) / 2.0
        else:
            w[i] = (xs[i + 1] - xs[i - 1]) / 2.0
    # 全是同一时刻的退化情形，退回等权
    if sum(w) <= 0:
        return [1.0] * n
    return w


def linear_fit(xs: Sequence[float], ys: Sequence[float],
               weights: Optional[Sequence[float]] = None) -> Line:
    """加权最小二乘直线 y = a + b·x，附带 R²。点数少于 3 直接判无效。

    weights 传 None 就是普通等权回归；拟合 SOC 曲线时应当传 time_weights(xs)。
    """
    n = min(len(xs), len(ys))
    if n < 3:
        return Line()

    w = list(weights[:n]) if weights is not None else [1.0] * n
    sw = sum(w)
    if sw <= 0:
        return Line()

    mx = sum(w[i] * xs[i] for i in range(n)) / sw
    my = sum(w[i] * ys[i] for i in range(n)) / sw

    sxx = sum(w[i] * (xs[i] - mx) ** 2 for i in range(n))
    sxy = sum(w[i] * (xs[i] - mx) * (ys[i] - my) for i in range(n))
    if sxx <= 1e-12:
        return Line()

    b = sxy / sxx
    a = my - b * mx

    ss_res = sum(w[i] * (ys[i] - (a + b * xs[i])) ** 2 for i in range(n))
    ss_tot = sum(w[i] * (ys[i] - my) ** 2 for i in range(n))
    if ss_tot <= 1e-12:
        r2 = 1.0 if ss_res <= 1e-12 else 0.0
    else:
        r2 = 1.0 - ss_res / ss_tot

    return Line(slope=b, intercept=a, r2=r2, valid=True, span=xs[n - 1] - xs[0])


def counter_soc(seq: Sequence[Sample], design_mah: float,
                charge_mode: bool = False) -> Optional[list[float]]:
    """由库仑计反推的 SOC。

        放电：soc(t) = soc0 - (counter0 - counter(t)) / design × 100
        充电：soc(t) = soc0 + (counter(t) - counter0) / design × 100

    counter 全程读不到时返回 None，调用方跳过这条线。
    """
    if len(seq) < 2 or design_mah <= 0:
        return None

    first = next((i for i, s in enumerate(seq) if s.counter_mah > 0), -1)
    if first < 0:
        return None

    c0 = seq[first].counter_mah
    soc0 = seq[first].soc_pct

    out: list[float] = []
    last = soc0
    for i, s in enumerate(seq):
        if s.counter_mah > 0:
            # 充电：counter 上升 → SOC 上升；放电：counter 下降 → SOC 下降
            delta = (s.counter_mah - c0) if charge_mode else -(c0 - s.counter_mah)
            last = soc0 + delta / design_mah * 100.0
        out.append(soc0 if i < first else last)
    return out


def counter_mah(seq: Sequence[Sample], charge_mode: bool = False) -> Optional[list[float]]:
    """从起点累计的电荷变化量（mAh），充放电都返回正值。

    不画 counter 绝对值：满电时系统会把 counter 重标定到 4605 mAh 附近，
    而真实放电量是 6561 mAh（首尾差）。只有相对起点的差值在单次测试内自洽。
    """
    first = next((i for i, s in enumerate(seq) if s.counter_mah > 0), -1)
    if first < 0 or len(seq) < 2:
        return None
    c0 = seq[first].counter_mah
    sign = 1.0 if charge_mode else -1.0
    out: list[float] = []
    last = 0.0
    for i, s in enumerate(seq):
        if s.counter_mah > 0:
            last = (s.counter_mah - c0) * sign
        out.append(0.0 if i < first else last)
    return out


def soc_mah(seq: Sequence[Sample], design_mah: float,
            charge_mode: bool = False) -> Optional[list[float]]:
    """SOC 折算成 mAh 当量，与 counter_mah 同轴可比。台阶 = design/100 mAh。"""
    if len(seq) < 2 or design_mah <= 0:
        return None
    s0 = seq[0].soc_pct
    sign = 1.0 if charge_mode else -1.0
    return [(s.soc_pct - s0) * design_mah / 100.0 * sign for s in seq]


@dataclass
class CurrentSeries:
    t_hours: list[float] = field(default_factory=list)
    ma: list[float] = field(default_factory=list)
    reading_ma: list[float] = field(default_factory=list)


def counter_current(seq: Sequence[Sample], window_s: float = 120.0) -> CurrentSeries:
    """库仑计滑动差分电流。窗口不跨空档（SPLIT_GAP_S）。

    1 mAh 量化在 5 秒窗口上是 720 mA 台阶（相对 21%），2 分钟窗口降到
    30 mA（0.9%）。这条路径不带 CURRENT_NOW 那个比例偏差。
    """
    out = CurrentSeries()
    if len(seq) < 4 or window_s <= 0:
        return out
    t0 = seq[0].t
    i = 0
    while i < len(seq) - 1:
        j = i + 1
        while j < len(seq) and seq[j].t - seq[i].t < window_s:
            j += 1
        if j >= len(seq):
            break
        # 窗口内只要有一处空档就整段跳过，从空档后的那个点重新起窗。
        # 不能只看 while 结束那一刻 —— 若 j 是被 window_s 顶停的，
        # 窗口中间照样可能藏着几小时的空档。
        gap_at = -1
        for k in range(i + 1, j + 1):
            if seq[k].t - seq[k - 1].t > SPLIT_GAP_S:
                gap_at = k
                break
        if gap_at > 0:
            i = gap_at
            continue
        dt = seq[j].t - seq[i].t
        dcc = abs(seq[j].counter_mah - seq[i].counter_mah)
        if dt > 0 and dcc >= 1:
            out.t_hours.append((seq[j].t - t0) / 3600.0)
            out.ma.append(dcc * 3600.0 / dt)
            out.reading_ma.append(
                sum(abs(seq[k].current_ma) for k in range(i, j + 1)) / (j - i + 1))
        i = j
    return out


def max_deviation(a: Optional[Sequence[float]],
                  b: Optional[Sequence[float]]) -> float:
    """两条等长序列的最大绝对差；任一条缺失返回 -1。"""
    if a is None or b is None:
        return -1.0
    n = min(len(a), len(b))
    return max((abs(a[i] - b[i]) for i in range(n)), default=0.0)


def evaluate(xs: Sequence[float], line: Line) -> list[float]:
    return [line.intercept + line.slope * x for x in xs]


def gap_seconds(seq: Sequence[Sample]) -> list[float]:
    return [seq[i].t - seq[i - 1].t for i in range(1, len(seq))]


# ---------------------------------------------------------------- 采样抖动

SPLIT_GAP_S = 60.0
"""间隔超过这个值就认为采样被系统打断了，切段从这里断开。"""


@dataclass
class GapStats:
    median_s: float = 0.0
    # P99 而不是 P90：绝大多数间隔都正常时 P90 就等于中位数，抖动完全看不见。
    p99_s: float = 0.0
    max_s: float = 0.0
    breaks: int = 0          # 被系统打断的次数
    jitter_pct: float = 0.0  # (P99 − 中位) / 中位 ×100


def gap_stats(gap_sec: Sequence[float]) -> GapStats:
    """采样间隔分布。

    只看「最长空档」不够：前台服务在国产 ROM 上常被掐，产物是一串几十秒到
    几分钟的小空档，最长空档看不出来，但曲线已经被打得稀碎。要看分布 ——
    中位说明「正常多久一个点」，P90 说明「最坏拖到多久」，两者之比就是抖动。
    """
    g = GapStats()
    if not gap_sec:
        return g
    s = sorted(gap_sec)
    g.median_s = s[len(s) // 2]
    g.p99_s = s[int(min(len(s) - 1, len(s) * 0.99))]
    g.max_s = s[-1]
    g.breaks = sum(1 for v in s if v > SPLIT_GAP_S)
    if g.median_s > 0:
        g.jitter_pct = (g.p99_s - g.median_s) / g.median_s * 100.0
    return g


def robust_fit(xs: Sequence[float], ys: Sequence[float]) -> Line:
    """带抖动处理的整体拟合。

    这里试过「分段各自拟合再按段时长加权平均」，真机数据上直接崩：
    slope 从 3.99 变成 -11.1 %/h，R² 从 0.78 变成 -1.73。原因是段内
    5 秒一个点、SOC 只有 1% 分辨率，一段几百秒里 SOC 纹丝不动，段内斜率
    全是量化噪声，按段加权把它放大成了主信号。

    所以斜率仍用整段时间加权拟合，抖动的影响单独量化（见 gap_stats）。
    抖动消除不了，只能如实报告。
    """
    base = linear_fit(xs, ys, time_weights(xs))
    if base.valid:
        base.segment_count = count_segments(xs)
    return base


def count_segments(xs: Sequence[float]) -> int:
    """按 SPLIT_GAP_S 切分，返回连续采样段的数量。"""
    if not xs:
        return 0
    n = 1
    for i in range(1, len(xs)):
        if xs[i] - xs[i - 1] >= SPLIT_GAP_S / 3600.0:
            n += 1
    return n


# ---------------------------------------------------------------- SVG

def to_svg(seq: Sequence[Sample], design_mah: float, charge_mode: bool = False,
           width: int = 680, height: int = 300,
           gap_threshold_s: float = 1800.0) -> str:
    """把和 App 里一样的图渲染成 SVG，装机前先在电脑上看效果。

    纵轴是「从起点累计的电荷 mAh」（左）与「库仑计差分电流 mA」（右），
    不再是 SOC 百分比 —— 百分比只有 1% 台阶（这台机 = 70 mAh），
    把库仑计 1 mAh 的细度压没了。
    """
    xs = hours(seq)
    design = design_mah if design_mah > 0 else 5000.0
    sign = 1.0 if charge_mode else -1.0
    s0 = seq[0].soc_pct if seq else 0.0
    line = robust_fit(xs, soc(seq))
    fit = evaluate(xs, line) if line.valid else None
    fit_m = [(v - s0) * design / 100.0 * sign for v in fit] if fit else None
    soc_m = soc_mah(seq, design, charge_mode)
    ctr_m = counter_mah(seq, charge_mode)
    cur = counter_current(seq)
    gaps = gap_seconds(seq)

    pad_l, pad_r, pad_t, pad_b = 52, 52, 26, 38
    plot_w = width - pad_l - pad_r
    plot_h = height - pad_t - pad_b
    x_max = max(xs[-1], 0.01) if xs else 1.0

    vals = [v for arr in (soc_m, ctr_m, fit_m) if arr for v in arr]
    q_max = max(100.0, math.ceil(max(vals, default=design * 0.2) / 100.0) * 100.0)
    if cur.ma:
        i_lo = max(0.0, min(cur.ma) * 0.85)
        i_hi = max(cur.ma) * 1.15
        if i_hi - i_lo < 10:
            i_lo = max(0.0, i_lo - 5)
            i_hi += 5
    else:
        i_lo, i_hi = 0.0, 1.0

    def px(x: float) -> float:
        return pad_l + x / x_max * plot_w

    def py_q(v: float) -> float:
        return pad_t + (1 - min(max(v, 0.0), q_max) / q_max) * plot_h

    def py_i(v: float) -> float:
        return pad_t + (1 - (v - i_lo) / (i_hi - i_lo)) * plot_h

    def poly_q(series: Sequence[float]) -> str:
        return " ".join(f"{px(xs[i]):.1f},{py_q(series[i]):.1f}"
                        for i in range(min(len(xs), len(series))))

    parts: list[str] = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" '
        f'font-family="system-ui,-apple-system,Segoe UI,sans-serif">',
        f'<rect width="{width}" height="{height}" fill="#ffffff"/>',
    ]

    for i in range(1, len(xs)):
        if gaps[i - 1] < gap_threshold_s:
            continue
        x0, x1 = px(xs[i - 1]), px(xs[i])
        parts.append(f'<rect x="{x0:.1f}" y="{pad_t}" width="{max(x1 - x0, 0.8):.1f}" '
                     f'height="{plot_h}" fill="#eeeeec"/>')

    for k in range(5):
        q = q_max * k / 4
        y = py_q(q)
        parts.append(f'<line x1="{pad_l}" y1="{y:.1f}" x2="{pad_l + plot_w}" '
                     f'y2="{y:.1f}" stroke="#e8e8e6" stroke-width="1"/>')
        parts.append(f'<text x="{pad_l - 8}" y="{y + 4:.1f}" font-size="11" '
                     f'fill="#828282" text-anchor="end">{q:.0f} mAh</text>')

    parts.append(f'<line x1="{pad_l}" y1="{pad_t}" x2="{pad_l}" y2="{pad_t + plot_h}" '
                 f'stroke="#bebebc" stroke-width="1"/>')
    parts.append(f'<line x1="{pad_l}" y1="{pad_t + plot_h}" x2="{pad_l + plot_w}" '
                 f'y2="{pad_t + plot_h}" stroke="#bebebc" stroke-width="1"/>')

    if fit_m and soc_m:
        fill_pts = poly_q(soc_m) + " " + " ".join(
            f"{px(xs[i]):.1f},{py_q(fit_m[i]):.1f}" for i in range(len(xs) - 1, -1, -1))
        parts.append(f'<polygon points="{fill_pts}" fill="#2654be" fill-opacity="0.07"/>')
        parts.append(f'<polyline points="{poly_q(fit_m)}" fill="none" stroke="#969696" '
                     f'stroke-width="1.5" stroke-dasharray="6 4"/>')

    if ctr_m:
        parts.append(f'<polyline points="{poly_q(ctr_m)}" fill="none" stroke="#d68224" '
                     f'stroke-width="1.8"/>')

    if cur.ma:
        pts = " ".join(f"{px(t):.1f},{py_i(v):.1f}"
                       for t, v in zip(cur.t_hours, cur.ma))
        parts.append(f'<polyline points="{pts}" fill="none" stroke="#7f77dd" '
                     f'stroke-width="1.5" stroke-dasharray="5 3"/>')
        if cur.reading_ma:
            pts_r = " ".join(f"{px(t):.1f},{py_i(v):.1f}"
                             for t, v in zip(cur.t_hours, cur.reading_ma))
            parts.append(f'<polyline points="{pts_r}" fill="none" stroke="#378add" '
                         f'stroke-width="1.5" stroke-dasharray="2 3"/>')
        for k in range(3):
            v = i_lo + (i_hi - i_lo) * k / 2
            y = py_i(v)
            parts.append(f'<text x="{pad_l + plot_w + 6}" y="{y + 4:.1f}" font-size="11" '
                         f'fill="#7f77dd">{v:.0f} mA</text>')

    if soc_m:
        parts.append(f'<polyline points="{poly_q(soc_m)}" fill="none" stroke="#2654be" '
                     f'stroke-width="2.6" stroke-linejoin="round" stroke-linecap="round"/>')

    for frac, label in ((0.0, "0"), (0.5, f"{x_max / 2:.1f}h"), (1.0, f"{x_max:.1f}h")):
        anchor = "start" if frac == 0 else ("end" if frac == 1 else "middle")
        parts.append(f'<text x="{pad_l + frac * plot_w:.1f}" y="{height - 14}" '
                     f'font-size="11" fill="#828282" text-anchor="{anchor}">{label}</text>')

    lx = pad_l
    entries = [("#2654be", "SOC 折算", False)]
    if fit_m:
        entries.append(("#969696", "匀速参考", True))
    if ctr_m:
        entries.append(("#d68224", "库仑计", False))
    if cur.ma:
        if cur.reading_ma:
            entries.append(("#378add", "读数电流", True))
        entries.append(("#7f77dd", "库仑计电流", True))
    for color, label, dashed in entries:
        dash = ' stroke-dasharray="5 3"' if dashed else ""
        parts.append(f'<line x1="{lx}" y1="13" x2="{lx + 16}" y2="13" stroke="{color}" '
                     f'stroke-width="2.4"{dash}/>')
        parts.append(f'<text x="{lx + 21}" y="17" font-size="11" fill="#828282">{label}</text>')
        lx += 30 + len(label) * 12

    if line.valid:
        summary = f"平均 {line.rate_pct_per_h:.1f} %/时 · R²={line.r2:.3f}"
        parts.append(f'<text x="{width - pad_r}" y="17" font-size="11" '
                     f'fill="#6b6b6b" text-anchor="end">{summary}</text>')

    parts.append("</svg>")
    return "\n".join(parts)


def summarize(seq: Sequence[Sample], design_mah: float,
              charge_mode: bool = False) -> dict:
    """一组便于打日志/写测试的标量。"""
    xs = hours(seq)
    ys = soc(seq)
    line = robust_fit(xs, ys)
    ctr = counter_soc(seq, design_mah, charge_mode)
    gaps = gap_stats(gap_seconds(seq))
    return {
        "points": len(seq),
        "hours": xs[-1] if xs else 0.0,
        "rate_pct_per_h": line.rate_pct_per_h,
        "r2": line.r2,
        "slope": line.slope,
        "segments": line.segment_count,
        "counter_dev_pct": max_deviation(ys, ctr),
        "max_gap_s": gaps.max_s,
        "gap_median_s": gaps.median_s,
        "gap_p99_s": gaps.p99_s,
        "breaks": gaps.breaks,
        "jitter_pct": gaps.jitter_pct,
    }
