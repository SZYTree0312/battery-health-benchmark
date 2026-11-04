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

from dataclasses import dataclass
from typing import Optional, Sequence

from .core import Sample


@dataclass
class Line:
    slope: float = 0.0
    intercept: float = 0.0
    r2: float = 0.0
    valid: bool = False
    span: float = 0.0

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


# ---------------------------------------------------------------- SVG

def to_svg(seq: Sequence[Sample], design_mah: float, charge_mode: bool = False,
           width: int = 680, height: int = 300,
           gap_threshold_s: float = 1800.0) -> str:
    """把和 App 里一样的图渲染成 SVG，装机前先在电脑上看效果。"""
    xs = hours(seq)
    ys = soc(seq)
    line = linear_fit(xs, ys, time_weights(xs))
    fit = evaluate(xs, line) if line.valid else None
    ctr = counter_soc(seq, design_mah, charge_mode)
    gaps = gap_seconds(seq)

    pad_l, pad_r, pad_t, pad_b = 52, 18, 26, 38
    plot_w = width - pad_l - pad_r
    plot_h = height - pad_t - pad_b
    x_max = max(xs[-1], 0.01) if xs else 1.0

    def px(x: float) -> float:
        return pad_l + x / x_max * plot_w

    def py(y: float) -> float:
        return pad_t + (1 - min(max(y, 0.0), 100.0) / 100.0) * plot_h

    def poly(series: Sequence[float]) -> str:
        return " ".join(f"{px(xs[i]):.1f},{py(series[i]):.1f}"
                        for i in range(min(len(xs), len(series))))

    parts: list[str] = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" '
        f'font-family="system-ui,-apple-system,Segoe UI,sans-serif">',
        f'<rect width="{width}" height="{height}" fill="#ffffff"/>',
    ]

    # 空档带
    for i in range(1, len(xs)):
        if gaps[i - 1] < gap_threshold_s:
            continue
        x0, x1 = px(xs[i - 1]), px(xs[i])
        parts.append(f'<rect x="{x0:.1f}" y="{pad_t}" width="{max(x1 - x0, 0.8):.1f}" '
                     f'height="{plot_h}" fill="#eeeeec"/>')

    # 网格与 Y 标签
    for pct in range(0, 101, 25):
        y = py(pct)
        parts.append(f'<line x1="{pad_l}" y1="{y:.1f}" x2="{pad_l + plot_w}" '
                     f'y2="{y:.1f}" stroke="#e8e8e6" stroke-width="1"/>')
        parts.append(f'<text x="{pad_l - 8}" y="{y + 4:.1f}" font-size="11" '
                     f'fill="#828282" text-anchor="end">{pct}%</text>')

    # 轴
    parts.append(f'<line x1="{pad_l}" y1="{pad_t}" x2="{pad_l}" y2="{pad_t + plot_h}" '
                 f'stroke="#bebebc" stroke-width="1"/>')
    parts.append(f'<line x1="{pad_l}" y1="{pad_t + plot_h}" x2="{pad_l + plot_w}" '
                 f'y2="{pad_t + plot_h}" stroke="#bebebc" stroke-width="1"/>')

    # 实测与拟合之间的填充
    if fit:
        fill_pts = poly(ys) + " " + " ".join(
            f"{px(xs[i]):.1f},{py(fit[i]):.1f}" for i in range(len(xs) - 1, -1, -1))
        parts.append(f'<polygon points="{fill_pts}" fill="#2654be" fill-opacity="0.07"/>')
        parts.append(f'<polyline points="{poly(fit)}" fill="none" stroke="#969696" '
                     f'stroke-width="1.5" stroke-dasharray="6 4"/>')

    if ctr:
        parts.append(f'<polyline points="{poly(ctr)}" fill="none" stroke="#d68224" '
                     f'stroke-width="1.8"/>')

    parts.append(f'<polyline points="{poly(ys)}" fill="none" stroke="#2654be" '
                 f'stroke-width="2.6" stroke-linejoin="round" stroke-linecap="round"/>')

    # X 轴标签
    for frac, label in ((0.0, "0"), (0.5, f"{x_max / 2:.1f}h"), (1.0, f"{x_max:.1f}h")):
        anchor = "start" if frac == 0 else ("end" if frac == 1 else "middle")
        parts.append(f'<text x="{pad_l + frac * plot_w:.1f}" y="{height - 14}" '
                     f'font-size="11" fill="#828282" text-anchor="{anchor}">{label}</text>')

    # 图例
    lx = pad_l
    entries = [("#2654be", "实测", False)]
    if fit:
        entries.append(("#969696", "匀速参考", True))
    if ctr:
        entries.append(("#d68224", "库仑计反推", False))
    for color, label, dashed in entries:
        dash = ' stroke-dasharray="5 3"' if dashed else ""
        parts.append(f'<line x1="{lx}" y1="13" x2="{lx + 16}" y2="13" stroke="{color}" '
                     f'stroke-width="2.4"{dash}/>')
        parts.append(f'<text x="{lx + 21}" y="17" font-size="11" fill="#828282">{label}</text>')
        lx += 30 + len(label) * 12

    # 右上角摘要
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
    line = linear_fit(xs, ys, time_weights(xs))
    ctr = counter_soc(seq, design_mah, charge_mode)
    return {
        "points": len(seq),
        "hours": xs[-1] if xs else 0.0,
        "rate_pct_per_h": line.rate_pct_per_h,
        "r2": line.r2,
        "slope": line.slope,
        "counter_dev_pct": max_deviation(ys, ctr),
        "max_gap_s": max(gap_seconds(seq), default=0.0),
    }
