"""生成一段充电数据，用来验证「充电段折算」这条通道。

真实场景：手机用到 5% 插上充电器，夜里充到 100%，App 每 5 秒记一次。
这里造出同样形状的数据，并让结果可预期：

    设计容量 5000 mAh，实际容量 4600 mAh（健康度 92%）
    SOC 从 5% 充到 100%，库仑计从 230 mAh 涨到 4600 mAh
    → Δcounter = 4370 mAh，覆盖 95 个百分点
    → 折算满电 = 4370 / 0.95 = 4600 mAh  ✓
"""

from __future__ import annotations

import os

STEP_S = 60.0            # 60 秒一点，一晚 8 小时 = 480 点
FULL_MAH = 4600.0        # 电池实际容量
SOC_START, SOC_END = 5.0, 100.0
DCIR_OHM = 0.07


def build() -> list[str]:
    span = SOC_END - SOC_START
    total_s = 8 * 3600.0
    n = int(total_s / STEP_S)

    lines = ["t_s,current_ma,voltage_mv,soc_pct,temp_c,status,"
             "charge_full_mah,design_mah,cycle_count"]

    for k in range(n + 1):
        ratio = k / n
        soc = SOC_START + span * ratio
        counter = FULL_MAH * soc / 100.0
        # 快充前段电流大，80% 之后转恒压逐渐收尾
        if ratio < 0.55:
            current_ma = -3200.0 - 120.0 * (ratio / 0.55)
        elif ratio < 0.85:
            current_ma = -1600.0 * (1.0 - (ratio - 0.55) / 0.35 * 0.75)
        else:
            current_ma = -300.0 * (1.0 - (ratio - 0.85) / 0.15)

        voltage_mv = (3.62 + 0.78 * (soc / 100.0)
                      + abs(current_ma) / 1000.0 * DCIR_OHM) * 1000.0
        temp = 26.0 + 5.5 * min(1.0, ratio / 0.5)
        if ratio > 0.85:
            temp -= 2.0 * (ratio - 0.85) / 0.15

        lines.append(
            f"{k * STEP_S:.1f},{current_ma:.1f},{voltage_mv:.0f},"
            f"{soc:.1f},{temp:.2f},charging,{counter:.0f},5000,120"
        )
    return lines


def main() -> None:
    out_dir = os.path.join(os.path.dirname(__file__), "..", "data")
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, "sample_charge.csv")
    lines = build()
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write("\n".join(lines) + "\n")
    print(f"已写入 {path}（{len(lines) - 1} 点）")
    print("预期：Δcounter 4370 mAh / 95% → 折算满电 4600 mAh，健康度 92%")


if __name__ == "__main__":
    main()
