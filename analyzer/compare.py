"""三法交叉校验。

同一个电池，用三种独立方法各算一遍容量，再互相对比，得出每种方法的置信度。
测试期靠这个决定「以后用哪种方法」——不是靠推断。

三种方法
--------
A  满电直读   充满那一刻的库仑计读数。零成本，但可能只是 BMS 的标定常数。
B  放电测量   满电放到低电量的电荷积分。假设最少，作为基准。
C  充电折算   低电量充到满的充入量 ÷ SOC 跨度。省时间，但引入了均匀假设。

用法
----
    python analyzer/compare.py data/session_*.csv --design 7000
    python analyzer/compare.py data/session_*.csv --design 7000 --snapshots data/snapshots.csv

快照文件由 App 在充满时自动写入，格式见 load_snapshots()。
"""

from __future__ import annotations

import argparse
import csv
import statistics
import sys
from pathlib import Path

try:
    from .core import analyze, load_csv
except ImportError:  # python analyzer/compare.py 直接跑
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from core import analyze, load_csv  # type: ignore


# ---------------------------------------------------------------- 快照

def load_snapshots(path: Path) -> list[dict]:
    """读满电快照。

    列：iso_time, counter_mah, soc_pct, temp_c
    只收 soc >= 99 的记录——没充满的快照不能代表满电容量。
    """
    rows: list[dict] = []
    if not path.is_file():
        return rows
    with path.open(newline="", encoding="utf-8-sig") as fh:
        for row in csv.DictReader(fh):
            try:
                soc = float(row.get("soc_pct") or 0)
                counter = float(row.get("counter_mah") or 0)
            except ValueError:
                continue
            if soc < 99 or counter <= 0:
                continue
            rows.append({
                "time": (row.get("iso_time") or "").strip(),
                "counter_mah": counter,
                "soc": soc,
                "temp": float(row.get("temp_c") or 0),
            })
    return rows


# ---------------------------------------------------------------- 置信度

def grade_a(diff_pct: float | None) -> tuple[str, str]:
    """满电直读的可信度，取决于它和放电基准差多少。"""
    if diff_pct is None:
        return "待验证", "缺放电基准，无法判断 counter 是否可信"
    if abs(diff_pct) < 2.0:
        return "高", f"与放电基准相差 {diff_pct:+.1f}%，counter 应是真实积分"
    if abs(diff_pct) < 5.0:
        return "中", f"相差 {diff_pct:+.1f}%，可能混入标定误差"
    return "低", f"相差 {diff_pct:+.1f}%，counter 疑似标定常数而非实测"


def grade_b(soc_start: float, soc_end: float, hours: float) -> tuple[str, str]:
    """放电测量的可信度，看覆盖区间是否完整。"""
    if soc_start >= 95 and soc_end <= 10 and hours >= 2:
        return "高", f"完整放电 {soc_start:.0f}%→{soc_end:.0f}%，无需折算"
    if soc_start >= 90 and soc_end <= 20:
        return "中", f"覆盖 {soc_start:.0f}%→{soc_end:.0f}%，区间略短"
    return "低", f"覆盖 {soc_start:.0f}%→{soc_end:.0f}%，折算误差会放大"


def grade_c(span: float, soc_end: float) -> tuple[str, str]:
    """充电折算的可信度，主要看 SOC 跨度和是否充满。

    误差模型：基础误差 2%（库仑计标定 + 温度归一），
    再加上折算假设带来的偏差，经验上约为 30 / 跨度 个百分点。
    跨度 85% → 约 ±2.4%；跨度 60% → 约 ±2.5%。
    """
    if span <= 0:
        return "低", "SOC 跨度为 0，无法折算"
    err = 2.0 + 30.0 / span
    if span >= 85 and soc_end >= 99:
        return "高", f"跨度 {span:.0f}%，折算误差约 ±{err:.1f}%"
    if span >= 60 and soc_end >= 95:
        return "中", f"跨度 {span:.0f}%，折算误差约 ±{err:.1f}%"
    return "低", f"跨度仅 {span:.0f}%，折算误差可达 ±{err:.1f}%"


# ---------------------------------------------------------------- 主流程

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="三法交叉校验")
    parser.add_argument("csv", nargs="+", help="session CSV，可给多个")
    parser.add_argument("--design", type=float, required=True, help="厂商标称容量 mAh")
    parser.add_argument("--snapshots", help="满电快照 CSV（App 自动生成）")
    parser.add_argument("--json", help="把结果写成 JSON")
    args = parser.parse_args(argv)

    discharge: list[dict] = []
    charge: list[dict] = []

    for path in args.csv:
        samples = load_csv(path)
        if not samples:
            continue
        try:
            r = analyze(samples, args.design)
        except ValueError:
            continue
        record = {
            "file": Path(path).name,
            "mah": r.normalized_mah,
            "raw": r.measured_mah,
            "temp": r.avg_temp_c,
            "soc_start": r.soc_start,
            "soc_end": r.soc_end,
            "hours": r.duration_s / 3600.0,
            "method": r.method,
        }
        if r.method.startswith("charge"):
            charge.append(record)
        else:
            discharge.append(record)

    snaps = load_snapshots(Path(args.snapshots)) if args.snapshots else []

    if not discharge and not charge and not snaps:
        print("没有可用的数据。至少需要一份 session CSV 或快照文件。", file=sys.stderr)
        return 2

    # 基准：优先用放电（假设最少）
    base = None
    if discharge:
        base = statistics.median(d["mah"] for d in discharge)
        base_label = "B 放电"
    elif charge:
        base = statistics.median(d["mah"] for d in charge)
        base_label = "C 充电（无放电基准，暂时代理）"
    else:
        base_label = "无从对比"

    line = "=" * 72
    print(line)
    print(f"三法交叉校验    标称 {args.design:.0f} mAh    基准：{base_label}")
    print(line)
    print(f"{'方法':<14}{'样本':>4}{'中位数':>10}{'范围':>18}{'健康度':>9}  置信度")
    print("-" * 72)

    rows_json = []

    def emit(name: str, records: list[dict], grade: tuple[str, str]) -> None:
        values = [r["mah"] for r in records]
        med = statistics.median(values)
        lo, hi = min(values), max(values)
        soh = med / args.design * 100 if args.design else 0
        print(f"{name:<14}{len(values):>4}{med:>9.0f} {f'{lo:.0f}–{hi:.0f}':>17}"
              f"{soh:>8.1f}%  {grade[0]}")
        print(f"{'':14}{grade[1]}")
        rows_json.append({
            "method": name, "samples": len(values), "median_mah": round(med, 1),
            "min_mah": round(lo, 1), "max_mah": round(hi, 1),
            "soh_pct": round(soh, 2), "confidence": grade[0], "reason": grade[1],
        })

    # A 满电直读
    if snaps:
        latest = snaps[-1]["counter_mah"]
        diff = ((latest - base) / base * 100) if base else None
        emit(f"A 满电直读", [{"mah": latest}], grade_a(diff))

    # B 放电
    if discharge:
        agg_start = statistics.median(d["soc_start"] for d in discharge)
        agg_end = statistics.median(d["soc_end"] for d in discharge)
        agg_hours = statistics.median(d["hours"] for d in discharge)
        emit("B 放电测量", discharge, grade_b(agg_start, agg_end, agg_hours))

    # C 充电
    if charge:
        spans = [d["soc_end"] - d["soc_start"] for d in charge]
        ends = [d["soc_end"] for d in charge]
        emit("C 充电折算", charge,
             grade_c(statistics.median(spans), statistics.median(ends)))

    # 偏差结论
    print("-" * 72)
    if base:
        for record in rows_json:
            if record["method"].startswith("A"):
                d = (record["median_mah"] - base) / base * 100
                if abs(d) < 2:
                    verdict = "counter 可信，以后充满读一次即可"
                elif abs(d) < 5:
                    verdict = "两边有偏差，做趋势参考可以，报绝对容量要谨慎"
                else:
                    verdict = "counter 明显偏离实测，多半是标定值，必须靠放电或充电折算"
                print(f"A 相对 B：{d:+.1f}%  → {verdict}")
            if record["method"].startswith("C"):
                d = (record["median_mah"] - base) / base * 100
                if abs(d) < 3:
                    verdict = "折算假设带来的偏差在预期内"
                else:
                    verdict = ("偏差偏大，先排除温度归一的影响"
                               "（两组数据平均温度差得多时会被放大）")
                print(f"C 相对 B：{d:+.1f}%  → {verdict}")
    if len(snaps) >= 3:
        first, last = snaps[0]["counter_mah"], snaps[-1]["counter_mah"]
        print(f"快照趋势：{len(snaps)} 次，{first:.0f} → {last:.0f} mAh"
              f"（{((last - first) / first * 100):+.2f}%）")
    elif snaps:
        print(f"快照只有 {len(snaps)} 条，攒到 3 条以上才能看趋势")
    print(line)

    if args.json:
        Path(args.json).write_text(
            __import__("json").dumps(rows_json, ensure_ascii=False, indent=2),
            encoding="utf-8")
        print(f"已写入 {args.json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
