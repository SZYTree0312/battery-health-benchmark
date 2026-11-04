"""把系统自己的电量曲线和 App 采样曲线放一起比。

结论先说，这个模块只解决「接了电脑」这一种场景：

    App 读不到系统电池历史。设置里那张「电池使用情况」图的数据来自
    com.android.internal.os.BatteryStatsImpl 的 history buffer，是 hidden API，
    非系统应用没有权限。但 adb shell dumpsys batterystats --history 能把它
    以文本导出来 —— 格式：

        11-04 18:00:00.116 008 status=charging ... charge=1346 ...
                            ^^^ 这一列就是电量百分比

    所以 PC 侧可以拿到真正同源的曲线，App 侧不行。这也是为什么这个功能
    做在 analyzer/ 而不是 App 里。

两条线的数据源其实是同一个 BatteryManager.EXTRA_LEVEL，所以数值不会差太多；
真正能比出来的是**采样时机**：系统按「状态变化 / 电量跳 1%」记事件，App 按
固定间隔记，Doze 和省电策略会把 App 的点掐出大空档。把两条线叠在一张图上，
空档里系统有没有掉电、App 有没有漏掉台阶，一眼就看得出来。

用法：
    adb shell dumpsys batterystats --history > history.txt
    python -m analyzer.system_history \
        --csv build/device-data/session_20261104_184944.csv \
        --history history.txt --out build/system-compare.svg
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import List, Optional, Sequence, Tuple

from .core import Sample, load_csv

# "  11-04 18:00:00.116 008 status=charging ..."
LINE_RE = re.compile(
    r"^\s*(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})\s+(\d{3})\b"
)

# session_20261104_184944.csv —— 文件名就是会话启动的墙上时钟
SESSION_RE = re.compile(r"session_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})")


@dataclass(frozen=True)
class Point:
    t: float          # 相对会话起点的秒数
    soc: float        # 系统报的电量百分比


def parse_session_start(csv_path: str | Path) -> datetime:
    m = SESSION_RE.search(Path(csv_path).name)
    if not m:
        raise ValueError(f"文件名不是 session_YYYYMMDD_HHMMSS.csv：{csv_path}")
    y, mo, d, h, mi, sec = map(int, m.groups())
    return datetime(y, mo, d, h, mi, sec)


def parse_history(text: str, year: int) -> List[Tuple[datetime, float]]:
    """解析 batterystats --history，返回 (墙上时钟, 电量%) 序列。

    history 是 4 MB 环形缓冲，一个 4 天的量能有 30 多万行；这里只读电量列，
    而且只保留电量真正变化的点，重复行直接丢掉。
    """
    out: List[Tuple[datetime, float]] = []
    last_soc: Optional[float] = None
    for line in text.splitlines():
        m = LINE_RE.match(line)
        if not m:
            continue
        mo, d, h, mi, sec, ms, soc_s = m.groups()
        soc = float(int(soc_s))          # "008" -> 8.0, "100" -> 100.0
        if soc == last_soc:
            continue
        last_soc = soc
        try:
            # history 不带年份，跨年时按 CSV 年份往回退一年
            ts = datetime(year, int(mo), int(d), int(h), int(mi), int(sec),
                          int(ms) * 1000)
            out.append((ts, soc))
        except ValueError:
            continue
    return out


def align(points: Sequence[Tuple[datetime, float]],
          start: datetime, span_s: float) -> List[Point]:
    """把墙上时钟换算成相对会话起点的秒数，并裁进测量窗口。

    跨年处理：history 里 12-31 而 CSV 是 01-01 时，那条属于上一年。
    """
    out: List[Point] = []
    for ts, soc in points:
        dt = ts - start
        if dt < timedelta(days=-1):
            dt += timedelta(days=365)      # 粗略够用，闰年差 1 天不影响看图
        elif dt > timedelta(days=1):
            dt -= timedelta(days=365)
        t = dt.total_seconds()
        if -60 <= t <= span_s + 60:
            out.append(Point(max(0.0, t), soc))
    out.sort(key=lambda p: p.t)
    return out


def interp(points: Sequence[Point], t: float) -> Optional[float]:
    """在系统曲线上线性插值出 t 时刻的电量。区间外返回 None。"""
    if not points:
        return None
    if t < points[0].t or t > points[-1].t:
        return None
    lo, hi = 0, len(points) - 1
    while lo < hi - 1:
        mid = (lo + hi) // 2
        if points[mid].t <= t:
            lo = mid
        else:
            hi = mid
    a, b = points[lo], points[hi]
    if b.t <= a.t:
        return a.soc
    return a.soc + (b.soc - a.soc) * (t - a.t) / (b.t - a.t)


def compare(seq: Sequence[Sample], sys_pts: Sequence[Point]) -> dict:
    """在每个 App 采样点上比系统插值，给出偏差与覆盖率。"""
    diffs: List[float] = []
    matched = 0
    for s in seq:
        v = interp(sys_pts, s.t)
        if v is None:
            continue
        matched += 1
        diffs.append(s.soc_pct - v)
    abs_diffs = [abs(d) for d in diffs]
    return {
        "app_points": len(seq),
        "system_points": len(sys_pts),
        "matched": matched,
        "match_pct": 100.0 * matched / len(seq) if seq else 0.0,
        "mean_abs_dev_pct": sum(abs_diffs) / len(abs_diffs) if abs_diffs else 0.0,
        "max_abs_dev_pct": max(abs_diffs, default=0.0),
        "mean_signed_dev_pct": sum(diffs) / len(diffs) if diffs else 0.0,
    }


def to_svg(seq: Sequence[Sample], sys_pts: Sequence[Point],
           width: int = 680, height: int = 300) -> str:
    """两条线叠在同一坐标系：系统灰蓝实线，App 橙色点线。"""
    pad_l, pad_r, pad_t, pad_b = 46, 14, 18, 30
    w = width - pad_l - pad_r
    h = height - pad_t - pad_b
    span = max(seq[-1].t if seq else 0, sys_pts[-1].t if sys_pts else 0, 1.0)

    def xy(t: float, soc: float) -> Tuple[float, float]:
        return (pad_l + t / span * w, pad_t + (100 - soc) / 100.0 * h)

    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" '
             f'height="{height}" viewBox="0 0 {width} {height}">',
             f'<rect width="{width}" height="{height}" fill="#fff"/>']
    for soc in (0, 25, 50, 75, 100):
        y = pad_t + (100 - soc) / 100.0 * h
        parts.append(f'<line x1="{pad_l}" y1="{y:.1f}" x2="{pad_l+w}" y2="{y:.1f}" '
                     f'stroke="#e8e8e6" stroke-width="1"/>')
        parts.append(f'<text x="{pad_l-8}" y="{y+4:.1f}" text-anchor="end" '
                     f'font-size="10" fill="#8a8a8a">{soc}</text>')
    for frac in (0, 0.25, 0.5, 0.75, 1.0):
        x = pad_l + frac * w
        parts.append(f'<text x="{x:.1f}" y="{height-10}" text-anchor="middle" '
                     f'font-size="10" fill="#8a8a8a">{span*frac/3600:.1f}h</text>')

    if sys_pts:
        pts = " ".join(f"{x:.1f},{y:.1f}" for x, y in
                       (xy(p.t, p.soc) for p in sys_pts))
        parts.append(f'<polyline points="{pts}" fill="none" stroke="#2c5aa0" '
                     f'stroke-width="2.4" opacity="0.85"/>')
    if seq:
        pts = " ".join(f"{x:.1f},{y:.1f}" for x, y in
                       (xy(s.t, s.soc_pct) for s in seq))
        parts.append(f'<polyline points="{pts}" fill="none" stroke="#d68224" '
                     f'stroke-width="1.6" stroke-dasharray="4 3"/>')

    parts.append(f'<text x="{pad_l}" y="12" font-size="11" fill="#2c5aa0">'
                 f'系统电池历史</text>')
    parts.append(f'<text x="{pad_l+110}" y="12" font-size="11" fill="#d68224">'
                 f'App 采样</text>')
    parts.append('</svg>')
    return "\n".join(parts)


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description="系统电池历史 vs App 采样点")
    ap.add_argument("--csv", required=True, help="App 导出的 session_*.csv")
    ap.add_argument("--history", required=True, help="dumpsys batterystats --history 的文本")
    ap.add_argument("--out", default="", help="输出 SVG 路径（留空则只打印统计）")
    ap.add_argument("--year", type=int, default=0, help="history 的年份，默认取会话文件")
    a = ap.parse_args(argv)

    seq = load_csv(a.csv)
    start = parse_session_start(a.csv)
    span = seq[-1].t if seq else 0.0
    raw = Path(a.history).read_text(encoding="utf-8", errors="replace")
    sys_pts = align(parse_history(raw, a.year or start.year), start, span)

    print(f"会话起点  {start}   跨度 {span/3600:.2f} h")
    print(f"App 采样  {len(seq)} 点")
    print(f"系统历史  电量变化 {len(sys_pts)} 次（窗口内）")
    if not sys_pts:
        print("窗口内没有系统电量记录 —— history 是 4MB 环形缓冲，"
              "长会话可能已被覆盖，或这台 ROM 没写电量事件。")
        return 1

    c = compare(seq, sys_pts)
    for k, v in c.items():
        print(f"  {k:20} {v if isinstance(v, int) else round(v, 3)}")

    if a.out:
        Path(a.out).write_text(to_svg(seq, sys_pts), encoding="utf-8")
        print(f"SVG → {a.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
