"""命令行入口。

用法
----
单次测量::

    python analyzer/cli.py data/sample_healthy.csv --design 5000

横向对比（多个文件）::

    python analyzer/cli.py data/a.csv data/b.csv --design 5000

导出 JSON 给别的工具消费::

    python analyzer/cli.py data/a.csv --design 5000 --json out.json

设计容量也可以在 CSV 里带一列 design_mah，那样就不必每次敲 --design。
"""

from __future__ import annotations

import argparse
import json
import sys

try:  # 支持 python analyzer/cli.py 直接跑，也支持 python -m analyzer.cli
    from .core import Result, analyze, load_csv
except ImportError:  # pragma: no cover
    from core import Result, analyze, load_csv  # type: ignore


def _fmt_duration(seconds: float) -> str:
    total_min = int(round(seconds / 60))
    return f"{total_min // 60}h {total_min % 60:02d}m"


def _design_from_csv(path: str) -> float | None:
    """CSV 第一行若带 design_mah 列，直接拿来用。"""
    import csv as _csv

    try:
        with open(path, newline="", encoding="utf-8-sig") as fh:
            reader = _csv.DictReader(fh)
            if not reader.fieldnames:
                return None
            lookup = {n.strip().lower(): n for n in reader.fieldnames}
            key = lookup.get("design_mah")
            if key is None:
                return None
            for row in reader:
                value = (row.get(key) or "").strip()
                if value:
                    return float(value)
    except (OSError, ValueError):
        return None
    return None


METHOD_LABEL = {
    "coulomb-integration": "电流积分",
    "counter-delta": "系统库仑计差值",
    "charge-delta": "系统库仑计差值（充电段）",
    "charge-integration": "充电电流积分",
}


def print_report(path: str, result: Result) -> None:
    charge_mode = result.method.startswith("charge")
    dur_label = "充电时长" if charge_mode else "放电时长"
    energy_label = "充入能量" if charge_mode else "放出能量"
    capacity_label = "等效容量" if not charge_mode else "等效容量(折算)"

    line = "=" * 64
    print(line)
    print("电池等效容量报告" + ("（充电段）" if charge_mode else ""))
    print(line)
    print(f"{'数据文件':<12}{path}")
    print(f"{'采样点':<12}{result.sample_count}")
    print(f"{dur_label:<12}{_fmt_duration(result.duration_s)}")
    print(f"{'SOC 区间':<12}{result.soc_start:.0f}% → {result.soc_end:.0f}%"
          f"   (跨度 {result.soc_span_pct:.0f}%)")
    print(f"{'数据来源':<12}{METHOD_LABEL.get(result.method, result.method)}")
    print(f"{'净电荷':<12}{result.raw_charge_mah:.0f} mAh")
    print(f"{'采样质量':<12}覆盖 {result.coverage_pct:.0f}% / 最长空档 "
          f"{result.max_gap_s / 60:.0f} 分钟 / 长空档 {result.long_gaps} 次")
    if result.cross_check_mah is not None and result.divergence_pct is not None:
        print(f"{'交叉校验':<12}电流积分 {result.cross_check_mah:.0f} mAh"
              f"  ({result.divergence_pct:+.0f}%)")
    print()
    print(f"{capacity_label:<12}{result.normalized_mah:>7.0f} mAh"
          f"   (实测 {result.measured_mah:.0f} mAh @ {result.avg_temp_c:.1f}°C)")
    print(f"{'设计容量':<12}{result.design_mah:>7.0f} mAh")
    print(f"{'健康度':<12}{result.soh_pct:>7.1f} %    {result.grade}")
    print()
    print(f"{energy_label:<12}{result.energy_wh:>7.2f} Wh")
    print(f"{'平均电压':<12}{result.avg_voltage_v:>7.2f} V")
    print(f"{'平均电流':<12}{result.avg_current_ma:>7.0f} mA")
    if result.dcir_mohm is not None:
        print(f"{'内阻 DCIR':<12}{result.dcir_mohm:>7.1f} mΩ")
    else:
        print(f"{'内阻 DCIR':<12}{'未测到':>7}")
    print(f"{'温度':<12}平均 {result.avg_temp_c:.1f}°C / 峰值 {result.peak_temp_c:.1f}°C")

    if result.warnings:
        print()
        print("测量质量提示：")
        for warning in result.warnings:
            print(f"  - {warning}")
    print()


def print_compare(rows: list[tuple[str, Result]]) -> None:
    headers = ["文件", "等效容量", "设计容量", "健康度", "内阻", "均温", "时长"]
    table = []
    for path, r in rows:
        table.append([
            path.split("/")[-1].split("\\")[-1],
            f"{r.normalized_mah:.0f} mAh",
            f"{r.design_mah:.0f} mAh",
            f"{r.soh_pct:.1f}%",
            f"{r.dcir_mohm:.0f} mΩ" if r.dcir_mohm else "-",
            f"{r.avg_temp_c:.1f}°C",
            _fmt_duration(r.duration_s),
        ])

    widths = [max(len(str(row[i])) for row in [headers] + table)
              for i in range(len(headers))]
    sep = "  "
    print(sep.join(h.ljust(w) for h, w in zip(headers, widths)))
    print(sep.join("-" * w for w in widths))
    for row in table:
        print(sep.join(str(c).ljust(w) for c, w in zip(row, widths)))
    print()
    print("健康度区间：>=90% 良好 / 80-90% 一般 / 70-80% 衰减明显 / <70% 建议更换")


def result_to_dict(path: str, result: Result) -> dict:
    return {
        "file": path,
        "sample_count": result.sample_count,
        "duration_s": round(result.duration_s, 1),
        "soc_start_pct": result.soc_start,
        "soc_end_pct": result.soc_end,
        "design_mah": result.design_mah,
        "measured_mah": round(result.measured_mah, 1),
        "normalized_mah": round(result.normalized_mah, 1),
        "soh_pct": round(result.soh_pct, 2),
        "grade": result.grade,
        "method": result.method,
        "reliable": result.reliable,
        "raw_charge_mah": round(result.raw_charge_mah, 1),
        "soc_span_pct": round(result.soc_span_pct, 1),
        "coverage_pct": round(result.coverage_pct, 1),
        "max_gap_s": round(result.max_gap_s, 1),
        "long_gaps": result.long_gaps,
        "cross_check_mah": (round(result.cross_check_mah, 1)
                            if result.cross_check_mah is not None else None),
        "divergence_pct": (round(result.divergence_pct, 1)
                           if result.divergence_pct is not None else None),
        "energy_wh": round(result.energy_wh, 3),
        "avg_voltage_v": round(result.avg_voltage_v, 3),
        "avg_current_ma": round(result.avg_current_ma, 1),
        "avg_temp_c": round(result.avg_temp_c, 2),
        "peak_temp_c": result.peak_temp_c,
        "dcir_mohm": round(result.dcir_mohm, 1) if result.dcir_mohm else None,
        "warnings": result.warnings,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="从放电采样数据计算电池等效容量与健康度")
    parser.add_argument("csv", nargs="+", help="采集到的 CSV 文件，可给多个做对比")
    parser.add_argument("--design", type=float,
                        help="设计容量 mAh；不给则尝试从 CSV 的 design_mah 列读取")
    parser.add_argument("--temp-coeff", type=float, default=0.002,
                        help="温度归一系数，默认 0.002（每°C 0.2%%），设 0 关闭归一")
    parser.add_argument("--json", metavar="PATH", help="把结果写成 JSON")
    args = parser.parse_args(argv)

    rows: list[tuple[str, Result]] = []
    for path in args.csv:
        # 显式传了 --design 就以它为准，哪怕是 0（0 会走到下面的报错），
        # 只有在完全没传时才回退到 CSV 里的 design_mah 列。
        design = args.design if args.design is not None else _design_from_csv(path)
        if not design or design <= 0:
            print(f"错误：{path} 没有可用的设计容量，请用 --design 指定厂商标称容量",
                  file=sys.stderr)
            return 2
        samples = load_csv(path)
        if not samples:
            print(f"错误：{path} 没解析出有效采样点", file=sys.stderr)
            return 3
        try:
            rows.append((path, analyze(samples, design, args.temp_coeff)))
        except ValueError as exc:
            print(f"错误：{path} {exc}", file=sys.stderr)
            return 4

    if len(rows) == 1:
        print_report(*rows[0])
    else:
        print_compare(rows)

    if args.json:
        payload = [result_to_dict(p, r) for p, r in rows]
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, ensure_ascii=False, indent=2)
        print(f"已写入 {args.json}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
