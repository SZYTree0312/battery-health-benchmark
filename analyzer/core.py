"""库仑积分与电池健康度计算核心。

设计原则
--------
1. 零第三方依赖，只用标准库，任何装了 Python 3.9+ 的机器都能跑。
2. 只做计算，不碰设备。所有采样数据由 collector-adb 或采集 app 产出。
3. 所有结果都带"这个数是怎么来的"，拒绝黑盒总分。

数据约定
--------
CSV 每行一个采样点，至少包含以下列（列名大小写不敏感）：

    t_s        自放电开始的秒数，单调递增
    current_ma 电池电流，放电为正，充电为负，单位 mA
    voltage_mv 电池电压，单位 mV
    soc_pct    系统报告的电量百分比 0-100
    temp_c     电池温度，摄氏度
    status     charging / discharging / not-charging / full

不认识的多余列会被忽略。
"""

from __future__ import annotations

import csv
import statistics
from dataclasses import dataclass, field
from typing import Iterable, Optional, Sequence

# 温度归一系数：锂电池在 0-40 °C 区间内，容量随温度近似线性变化。
# 0.008 表示每偏离 25 °C 一度，可用容量变化约 0.8%。
# 这是经验值，同一台机器纵向对比时（恒温环境）可以设为 0 关闭归一。
DEFAULT_TEMP_COEFF = 0.008
TEMP_REF_C = 25.0

# 有效放电段最短时长，低于这个值说明数据不完整，直接拒绝出结论。
MIN_DISCHARGE_SECONDS = 300.0

# 判定电流阶跃的门槛，用于内阻估算。太小的阶跃信噪比不够。
DCIR_STEP_MA = 300.0
# 内阻只在中间 SOC 区间取，避开两端电压平台，那里 dv/di 严重失真。
DCIR_SOC_RANGE = (20.0, 80.0)
# 内阻是瞬态量，取阶跃后第一个采样点的压降才有效。
# 采样间隔越密越准；放宽到 15 s 是为了兼容 10 s 一批的采集器。
DCIR_MAX_GAP_S = 15.0


@dataclass
class Sample:
    """单个采样点。"""

    t: float
    current_ma: float
    voltage_mv: float
    soc_pct: float
    temp_c: float
    status: str = "discharging"
    # 库仑计当前剩余电荷（mAh）。有的机型能直读，此时它是比电流积分更准的源。
    counter_mah: float = 0.0

    @property
    def is_discharging(self) -> bool:
        # 以 status 为准。部分采集路径（dumpsys）拿不到电流，只能靠状态位判断。
        state = self.status.lower()
        if state.startswith("discharg"):
            return True
        if state in ("", "unknown"):
            return self.current_ma > 0
        return False


@dataclass
class Result:
    """一次完整测量的结论。"""

    design_mah: float
    measured_mah: float          # 原始结果（实测温度下）
    normalized_mah: float        # 折算到 25 °C 后的等效容量
    energy_wh: float             # 实测放出能量
    avg_voltage_v: float
    avg_current_ma: float
    avg_temp_c: float            # 时间加权平均温度
    peak_temp_c: float
    duration_s: float
    soc_start: float
    soc_end: float
    sample_count: int
    dcir_mohm: Optional[float]
    method: str = "coulomb-integration"   # 数据来源：coulomb-integration / counter-delta
    warnings: list[str] = field(default_factory=list)

    @property
    def soh_pct(self) -> float:
        """健康度 = 等效容量 / 设计容量。"""
        if self.design_mah <= 0:
            return 0.0
        return self.normalized_mah / self.design_mah * 100.0

    @property
    def grade(self) -> str:
        soh = self.soh_pct
        if soh >= 90:
            return "良好"
        if soh >= 80:
            return "一般"
        if soh >= 70:
            return "衰减明显"
        return "建议更换"


def load_csv(path: str) -> list[Sample]:
    """读 CSV，容错处理列名与坏行。"""
    rows: list[Sample] = []
    with open(path, newline="", encoding="utf-8-sig") as fh:
        reader = csv.DictReader(fh)
        if reader.fieldnames is None:
            raise ValueError(f"{path} 是空文件")

        lookup = {name.strip().lower(): name for name in reader.fieldnames}

        def col(row: dict, *aliases: str) -> Optional[str]:
            for alias in aliases:
                key = lookup.get(alias)
                if key is not None and row.get(key) not in (None, ""):
                    return str(row[key]).strip()
            return None

        for lineno, row in enumerate(reader, start=2):
            raw_t = col(row, "t_s", "time_s", "timestamp_s", "t")
            raw_i = col(row, "current_ma", "current_now_ma", "i_ma")
            raw_v = col(row, "voltage_mv", "voltage_now_mv", "v_mv")
            if raw_t is None or raw_i is None or raw_v is None:
                continue
            try:
                rows.append(
                    Sample(
                        t=float(raw_t),
                        current_ma=float(raw_i),
                        voltage_mv=float(raw_v),
                        soc_pct=float(col(row, "soc_pct", "capacity_pct", "soc") or 0),
                        temp_c=float(col(row, "temp_c", "temperature_c", "temp") or 25),
                        status=(col(row, "status", "state") or "discharging").lower(),
                        # 库仑计列可有可无；缺省为 0 表示该机型读不到。
                        counter_mah=float(
                            col(row, "charge_full_mah", "charge_counter_mah",
                                "counter_mah") or 0
                        ),
                    )
                )
            except ValueError:
                # 单行脏数据不该毁掉整次测量，跳过并继续。
                continue

    rows.sort(key=lambda s: s.t)
    return rows


def _discharge_segments(samples: Sequence[Sample]) -> list[list[Sample]]:
    """切出连续的放电段，充电或状态突变处断开。"""
    segments: list[list[Sample]] = []
    current: list[Sample] = []
    for sample in samples:
        if sample.is_discharging:
            current.append(sample)
        else:
            if len(current) >= 2:
                segments.append(current)
            current = []
    if len(current) >= 2:
        segments.append(current)
    return segments


def _pick_segment(segments: Iterable[list[Sample]]) -> Optional[list[Sample]]:
    """取最长的一段。一次测量里通常只有一段是完整放电。"""
    best: Optional[list[Sample]] = None
    best_span = -1.0
    for seg in segments:
        span = seg[-1].t - seg[0].t
        if span > best_span:
            best, best_span = seg, span
    return best


def integrate(seg: Sequence[Sample]) -> tuple[float, float]:
    """梯形法库仑积分，返回 (容量 mAh, 能量 Wh)。

    容量单位换算：I(A) × dt(s) / 3600 × 1000 = I(A) × dt(s) / 3.6 (mAh)
    """
    q_mah = 0.0
    e_wh = 0.0
    for a, b in zip(seg, seg[1:]):
        dt = b.t - a.t
        if dt <= 0:
            continue
        i_a = (a.current_ma + b.current_ma) / 2000.0
        v_v = (a.voltage_mv + b.voltage_mv) / 2000.0
        q_mah += i_a * dt / 3.6
        e_wh += v_v * i_a * dt / 3600.0
    return q_mah, e_wh


def time_weighted_temp(seg: Sequence[Sample]) -> float:
    """按放电时长加权的平均温度，比算术平均更贴近实际工况。"""
    total = 0.0
    acc = 0.0
    for a, b in zip(seg, seg[1:]):
        dt = b.t - a.t
        if dt <= 0:
            continue
        total += dt
        acc += dt * (a.temp_c + b.temp_c) / 2.0
    if total <= 0:
        return statistics.fmean(s.temp_c for s in seg)
    return acc / total


def normalize_to_ref(q_mah: float, temp_c: float,
                     coeff: float = DEFAULT_TEMP_COEFF) -> float:
    """把实测温度下的容量折算到 25 °C 基准。

    模型：Q(T) = Q25 × (1 + k × (T - 25))
    低温时 Q < Q25，归一化会把实测值放大回标准温度下的等效值。
    """
    factor = 1.0 + coeff * (temp_c - TEMP_REF_C)
    if factor <= 0.5 or factor >= 2.0:
        # 参数明显不合理，不强行归一，返回原值并交由调用方标记警告。
        return q_mah
    return q_mah / factor


def estimate_dcir(seg: Sequence[Sample]) -> Optional[float]:
    """用电流阶跃的瞬态压降估算直流内阻，单位 mΩ。"""
    readings: list[float] = []
    lo, hi = DCIR_SOC_RANGE
    for a, b in zip(seg, seg[1:]):
        if b.t - a.t > DCIR_MAX_GAP_S:
            continue
        di = b.current_ma - a.current_ma
        if abs(di) < DCIR_STEP_MA:
            continue
        if not (lo <= a.soc_pct <= hi):
            continue
        dv = b.voltage_mv - a.voltage_mv
        # 放电电流上升时电压下降，两者异号；同号说明不是真实内阻响应。
        if dv == 0 or (dv > 0) == (di > 0):
            continue
        readings.append(abs(dv / di) * 1000.0)
    if not readings:
        return None
    return statistics.median(readings)


def _time_weighted_voltage(seg: Sequence[Sample]) -> float:
    total = 0.0
    acc = 0.0
    for a, b in zip(seg, seg[1:]):
        dt = b.t - a.t
        if dt <= 0:
            continue
        total += dt
        acc += dt * (a.voltage_mv + b.voltage_mv) / 2000.0
    if total <= 0:
        return statistics.fmean(s.voltage_mv for s in seg) / 1000.0
    return acc / total


def analyze(samples: Sequence[Sample], design_mah: float,
            temp_coeff: float = DEFAULT_TEMP_COEFF) -> Result:
    """主入口：给采样序列和设计容量，产出完整结论。

    自动在两条数据通道之间择一：
      * 有可信电流 → 库仑积分（最通用）
      * 没有电流、但有库仑计读数 → 用计数值首尾差（更准，但依赖厂商标定）
    """
    if design_mah <= 0:
        raise ValueError("design_mah 必须大于 0，用厂商标称容量（mAh）")

    warnings: list[str] = []
    seg = _pick_segment(_discharge_segments(samples))
    if seg is None:
        raise ValueError("数据里没有可用的放电段（status=discharging）")

    duration = seg[-1].t - seg[0].t
    if duration < MIN_DISCHARGE_SECONDS:
        warnings.append(
            f"放电时长只有 {duration:.0f} 秒，低于 {MIN_DISCHARGE_SECONDS:.0f} 秒下限，"
            "结论仅作参考"
        )

    # 判据：电流有一处明显非零就走积分；否则看库仑计有没有变化。
    has_current = any(abs(s.current_ma) > 1.0 for s in seg)
    counters = [s.counter_mah for s in seg if s.counter_mah > 0]
    counter_span = (max(counters) - min(counters)) if len(counters) >= 2 else 0.0

    if has_current:
        q_mah, e_wh = integrate(seg)
        method = "coulomb-integration"
    elif counter_span > 50.0:
        # 库仑计是累计量，首尾相减即为放出电荷，不经过积分，不累积采样误差。
        q_mah = counters[0] - counters[-1]
        if q_mah <= 0:
            # 计数反而上升，说明段落里混进了充电，退回全程极差。
            q_mah = counter_span
        avg_v = _time_weighted_voltage(seg)
        e_wh = q_mah / 1000.0 * avg_v
        method = "counter-delta"
        warnings.append(
            "本机 sysfs 不可读，数据取自系统库仑计差值；"
            "精度取决于厂商对该计数器的标定，跨版本系统可能不同"
        )
    else:
        raise ValueError(
            "既没有可用电流，也没有库仑计变化，无法计算容量。"
            "确认采集时设备处于放电状态，且至少采到一次计数器跳变"
        )

    t_avg = time_weighted_temp(seg)
    q_norm = normalize_to_ref(q_mah, t_avg, temp_coeff)

    soc_start, soc_end = seg[0].soc_pct, seg[-1].soc_pct
    if soc_end > 10:
        warnings.append(
            f"放电结束时系统电量仍有 {soc_end:.0f}%，未放到关机，实测容量会偏低"
        )
    if soc_start < 95:
        warnings.append(
            f"放电开始时电量只有 {soc_start:.0f}%，不是满充状态，实测容量会偏低"
        )

    avg_i = (q_mah / (duration / 3600.0)) if duration > 0 else 0.0

    return Result(
        design_mah=design_mah,
        measured_mah=q_mah,
        normalized_mah=q_norm,
        energy_wh=e_wh,
        avg_voltage_v=_time_weighted_voltage(seg),
        avg_current_ma=avg_i,
        avg_temp_c=t_avg,
        peak_temp_c=max(s.temp_c for s in seg),
        duration_s=duration,
        soc_start=soc_start,
        soc_end=soc_end,
        sample_count=len(seg),
        dcir_mohm=estimate_dcir(seg) if has_current else None,
        method=method,
        warnings=warnings,
    )
