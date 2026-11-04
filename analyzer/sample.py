"""生成示例放电数据，用于在没有真机时验证分析链路。

不参与生产，只是让 analyzer 有可跑的数据、让新用户看懂 CSV 长什么样。

    python analyzer/sample.py
    python analyzer/sample.py --out-dir data

产出两台虚拟机型：
    sample_healthy.csv  设计 5000 mAh / 实测约 4800 mAh（健康）
    sample_worn.csv     设计 5000 mAh / 实测约 3900 mAh（衰减）
"""

from __future__ import annotations

import argparse
import math
import os

# 采样间隔越密，电流阶跃（内阻测量）越容易捕捉到。
# 真机采集默认 1 Hz，这里用 10 s 折中文件体积，仍能落在 DCIR 的判定窗口内。
SAMPLE_STEP_S = 10.0


def build_session(target_mah: float, design_mah: float, temp_base: float,
                  dcir_ohm: float, seed: int) -> list[dict]:
    """构造一段从满电放到关机的数据。

    电流先按单位形状生成，再整体缩放，保证梯形积分结果精确等于 target_mah。
    """
    # 毫安时 → 在此电流下需要的秒数。先假定 1300 mA 基线。
    base_ma = 1300.0
    duration_s = target_mah / base_ma * 3600.0
    n = int(duration_s / SAMPLE_STEP_S)

    # 单位形状：围绕 1.0 波动，并人为插入两次短时电流阶跃，供 DCIR 估算使用。
    step_idx = {int(n * 0.35), int(n * 0.62)}
    shape: list[float] = []
    for k in range(n + 1):
        ratio = k / n
        noise = 0.04 * math.sin(k * 0.7 + seed) + 0.02 * math.sin(k * 2.3)
        step = 0.0
        if any(s <= k <= s + 2 for s in step_idx):
            step = 0.25  # 瞬态 +25% 电流，持续 3 个采样点
        shape.append(1.0 + noise + step)

    # 按形状积分出的"每 A 对应的 mAh"，反推缩放系数。
    raw_mah_per_amp = sum(
        (shape[i] + shape[i + 1]) / 2 * SAMPLE_STEP_S / 3.6
        for i in range(n)
    )
    # scale 的含义是"单位形状对应的安培数"，约等于 base_ma / 1000。
    scale = target_mah / raw_mah_per_amp

    rows: list[dict] = []
    for k in range(n + 1):
        ratio = k / n
        soc = 100.0 * (1.0 - ratio)
        current_ma = shape[k] * scale * 1000.0

        # 电压随 SOC 非线性下降，再叠加内阻压降与测量抖动。
        open_circuit = 3.30 + 1.05 * (0.18 * math.sqrt(soc / 100.0) + 0.82 * soc / 100.0)
        voltage_mv = (open_circuit - current_ma / 1000.0 * dcir_ohm) * 1000.0
        voltage_mv += 2.5 * math.sin(k * 1.9 + seed)

        temp = temp_base + 5.0 * ratio + 0.4 * math.sin(k * 0.3)

        rows.append({
            "t_s": f"{k * SAMPLE_STEP_S:.1f}",
            "current_ma": f"{current_ma:.1f}",
            "voltage_mv": f"{voltage_mv:.0f}",
            "soc_pct": f"{soc:.1f}",
            "temp_c": f"{temp:.2f}",
            "status": "discharging",
            # CHARGE_COUNTER 是**剩余**电荷，随放电线性递减；
            # v0.1 这里误写成恒定常数值，等于假装库仑计没在动。
            "charge_counter_mah": f"{target_mah * soc / 100.0:.1f}",
            "design_mah": f"{design_mah:.0f}",
            "cycle_count": "0",
        })
    return rows


def write_csv(path: str, rows: list[dict]) -> None:
    if not rows:
        raise ValueError("没有数据可写")
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write(",".join(rows[0].keys()) + "\n")
        for row in rows:
            fh.write(",".join(row.values()) + "\n")
    print(f"已写入 {path}（{len(rows)} 点）")


def main() -> None:
    parser = argparse.ArgumentParser(description="生成示例放电数据")
    parser.add_argument("--out-dir", default="data")
    args = parser.parse_args()

    healthy = build_session(target_mah=4800, design_mah=5000,
                            temp_base=25.0, dcir_ohm=0.062, seed=1)
    worn = build_session(target_mah=3900, design_mah=5000,
                         temp_base=30.0, dcir_ohm=0.115, seed=2)

    write_csv(os.path.join(args.out_dir, "sample_healthy.csv"), healthy)
    write_csv(os.path.join(args.out_dir, "sample_worn.csv"), worn)


if __name__ == "__main__":
    main()
