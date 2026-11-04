"""库仑积分与电池健康度计算核心。

设计原则
--------
1. 零第三方依赖，只用标准库，任何装了 Python 3.9+ 的机器都能跑。
2. 只做计算，不碰设备。所有采样数据由 collector-adb 或采集 app 产出。
3. 所有结果都带"这个数是怎么来的"，拒绝黑盒总分。

数据约定
--------
CSV 每行一个采样点，至少包含以下列（列名大小写不敏感）：

    t_s                 自放电开始的秒数，单调递增
    current_ma          电池电流，放电为正，充电为负，单位 mA
    voltage_mv          电池电压，单位 mV
    soc_pct             系统报告的电量百分比 0-100
    temp_c              电池温度，摄氏度
    status              charging / discharging / not-charging / full
    charge_counter_mah  BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER，
                        当前**剩余电荷**（mAh），随放电递减

兼容列名：charge_full_mah / counter_mah。前者是 v0.1 的历史误名——
值其实是 CHARGE_COUNTER 的剩余电荷，不是"满电容量"，读的时候照旧认。

为什么优先用库仑计
------------------
current_ma 是**瞬时值**。手机息屏进 Doze、或国产 ROM 冻结后台后，前台服务的
5 秒采样会被推迟到几十分钟甚至几小时；醒来那一刻的电流往往是亮屏/唤醒的
高峰值，拿梯形法去外推整个空档会系统性高估。真机上见过 17 小时只采到 156 点、
最长空档 7.4 小时，电流积分算出 9507 mAh，而硬件库仑计首尾差只有 5795 mAh。

CHARGE_COUNTER 是硬件累计量，和采样频率无关，首尾相减就是净电荷变化。
所以只要这段数据里 counter 可用且单调，一律以它为准；电流积分退为交叉校验，
偏差过大时给出明确警告，而不是闷声输出一个虚假的大数。
"""

from __future__ import annotations

import csv
import statistics
from dataclasses import dataclass, field
from typing import Iterable, Optional, Sequence

# 温度归一系数：锂电池在 20-40 °C 区间内容量随温度变化很小，
# 经验上每偏离 25 °C 一度约 0.2%。v0.1 用的是 0.008（每度 0.8%），
# 手机正常工作温度 32 °C 就会被凭空折掉 6%，没有依据，已下调。
# 同一台机器纵向对比（恒温环境）建议直接传 0 关闭归一。
DEFAULT_TEMP_COEFF = 0.002
TEMP_REF_C = 25.0

# 有效放电段最短时长，低于这个值说明数据不完整，直接拒绝出结论。
MIN_DISCHARGE_SECONDS = 300.0
MIN_CHARGE_SECONDS = 600.0

# 电流积分的可信间隔上限。超过它的空档不能用两端瞬时电流线性外推。
MAX_INTEGRATION_GAP_S = 60.0
# 无库仑计时，低于这个覆盖率就判定采样质量不合格，不给健康度。
MIN_COVERAGE_PCT = 80.0
# 充放电折算所需的最小 SOC 跨度；跨度太小会把误差放大到没有意义。
MIN_SOC_SPAN_PCT = 10.0
MIN_CHARGE_SPAN_PCT = 60.0
# 库仑计中途上升超过这个量，说明插过充电器，首尾差不再等于净放电量。
COUNTER_RISE_LIMIT_MAH = 50.0
# 两种算法偏差超过这个比例就提示（正常应 <5%）。
DIVERGENCE_WARN_PCT = 10.0
# 健康度物理合理区间。超出就该怀疑数据/标称容量填错，而不是给个"良好"。
SOH_PLAUSIBLE_RANGE = (30.0, 115.0)

# 判定电流阶跃的门槛，用于内阻估算。太小的阶跃信噪比不够。
DCIR_STEP_MA = 300.0
# 内阻只在中间 SOC 区间取，避开两端电压平台，那里 dv/di 严重失真。
DCIR_SOC_RANGE = (20.0, 80.0)
# 内阻是瞬态量，取阶跃后第一个采样点的压降才有效。
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
    # 库仑计当前剩余电荷（mAh）。有的机型能直读；它比电流积分可靠得多。
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
    measured_mah: float          # 折算到满电 SOC 后、实测温度下的容量
    normalized_mah: float        # 再折算到 25 °C 后的等效容量
    energy_wh: float             # 实测放出/充入能量
    avg_voltage_v: float
    avg_current_ma: float
    avg_temp_c: float            # 时间加权平均温度
    peak_temp_c: float
    duration_s: float
    soc_start: float
    soc_end: float
    sample_count: int
    dcir_mohm: Optional[float]
    method: str = "coulomb-integration"   # counter-delta / coulomb-integration / charge-*
    warnings: list[str] = field(default_factory=list)
    # ---- v0.2 新增：数据与算法的可追溯性 ----
    raw_charge_mah: float = 0.0   # SOC 折算前的净电荷变化
    soc_span_pct: float = 0.0     # 实际覆盖的 SOC 跨度
    max_gap_s: float = 0.0        # 最长采样空档
    coverage_pct: float = 100.0   # 间隔 <= MAX_INTEGRATION_GAP_S 的时长占比
    long_gaps: int = 0            # 超过阈值的空档个数
    reliable: bool = True         # 这份数据够不够格下健康度结论
    cross_check_mah: Optional[float] = None   # 另一条算法算出的容量（交叉校验）
    divergence_pct: Optional[float] = None    # 两条算法的偏差 %
    # ---- 连续段积分对账 ----
    # 只在"没有空档"的连续块里比 ∫I dt 与 ΔCC，把"采样被打断"和"电流读数本身偏"
    # 两件事分开。全段积分做不到这一点：空档会被当成两端电流的平均值。
    audit_blocks: int = 0
    audit_minutes: float = 0.0
    audit_integral_mah: float = 0.0
    audit_counter_mah: float = 0.0
    audit_err_pct: Optional[float] = None
    audit_worst_pct: Optional[float] = None
    audit_best_pct: Optional[float] = None
    # 本次结论实际用到的那一段采样点。曲线图直接用，保证画的和算的是同一份数据。
    segment: Sequence[Sample] = field(default_factory=list)

    @property
    def soh_pct(self) -> float:
        """健康度 = 等效容量 / 设计容量。"""
        if self.design_mah <= 0:
            return 0.0
        return self.normalized_mah / self.design_mah * 100.0

    @property
    def grade(self) -> str:
        soh = self.soh_pct
        lo, hi = SOH_PLAUSIBLE_RANGE
        if soh > hi or soh < lo:
            # 电池不会比新的还新 15%，也不会只剩三成还能跑完整个测试。
            # 到这里说明数据源或标称容量有问题，绝不能给"良好"。
            # 放在 reliable 之前：异常是比"数据不足"更该先说的结论。
            return "数据异常"
        if not self.reliable:
            return "数据不足"
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
                        counter_mah=float(
                            col(row, "charge_counter_mah", "charge_full_mah",
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


def _charging_segments(samples: Sequence[Sample]) -> list[list[Sample]]:
    """切出连续的充电段（含已充满）。

    充电段是给"不想为测电池停用手机"的场景准备的：正常用到低电量、
    插上充电器过夜，第二天早上算充入量。充入的电荷同样等于电池容量，
    只是需要按 SOC 跨度折算一次。
    """
    segments: list[list[Sample]] = []
    current: list[Sample] = []
    for sample in samples:
        state = sample.status.lower()
        if state.startswith("charg") or state == "full":
            current.append(sample)
        else:
            if len(current) >= 2:
                segments.append(current)
            current = []
    if len(current) >= 2:
        segments.append(current)
    return segments


def _usable(seg: Optional[Sequence[Sample]], charge: bool) -> bool:
    """这一段数据够不够格算一次有效测量。

    注意：不能只认库仑计。有些机型既给电流又给 counter，有些只有电流。
    只要有一段够长、且能算出电荷变化，就先收进来；质量好不好由后面的
    gap_stats / reliable 判据把关，别在选段阶段就把数据丢掉。
    """
    if seg is None or len(seg) < 2:
        return False
    duration = seg[-1].t - seg[0].t
    floor = MIN_CHARGE_SECONDS if charge else MIN_DISCHARGE_SECONDS
    if duration < floor:
        return False
    counters = [s.counter_mah for s in seg if s.counter_mah > 0]
    if len(counters) >= 2:
        delta = (counters[-1] - counters[0]) if charge else (counters[0] - counters[-1])
        if delta >= 100:
            return True
    return any(abs(s.current_ma) > 1.0 for s in seg)


def _soc_ok(seg: Optional[Sequence[Sample]], charge: bool) -> bool:
    """SOC 跨度够不够。

    这一段决定「用哪种方式出结论」：从 100% 用到 15% 再充满，
    放电段结束在 15% 不算完整，但充电段覆盖 15%→100%，反而是合格的那条。
    """
    if seg is None or len(seg) < 2:
        return False
    start, end = seg[0].soc_pct, seg[-1].soc_pct
    if charge:
        return (end - start) >= MIN_CHARGE_SPAN_PCT and end >= 95.0
    return start >= 95.0 and end <= 10.0


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

    ⚠️ 只在采样连续时可信。调用方必须先看 gap_stats()，
    有长空档时不要用这个结果下结论（见 integrate_with_soc_fallback）。
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


def gap_stats(seg: Sequence[Sample]) -> tuple[float, float, int]:
    """采样完整性：返回 (最长空档秒, 有效覆盖率 %, 长空档个数)。

    有效覆盖率 = 间隔 <= MAX_INTEGRATION_GAP_S 的时长占总时长的比例。
    手机进 Doze 后采样会被批量推迟，这个值会掉得很低，是判断电流积分
    能不能用的唯一依据。
    """
    total = 0.0
    covered = 0.0
    max_gap = 0.0
    long_gaps = 0
    for a, b in zip(seg, seg[1:]):
        dt = b.t - a.t
        if dt <= 0:
            continue
        total += dt
        max_gap = max(max_gap, dt)
        if dt <= MAX_INTEGRATION_GAP_S:
            covered += dt
        else:
            long_gaps += 1
    return max_gap, (100.0 * covered / total if total > 0 else 100.0), long_gaps


AUDIT_BLOCK_MIN_S = 300.0      # 5 分钟：ΔCC 是 1 mAh 整数，窗口太短证明不了什么
AUDIT_BLOCK_MIN_MAH = 50.0


def audit_integration(seg: Sequence[Sample]) -> dict:
    """连续段积分对账：把采样按 MAX_INTEGRATION_GAP_S 切块，在每块内比 ∫I dt 与 |ΔCC|。

    与 integrate() 的区别——integrate 求全长，空档按两端电流插值，结果被拉低；
    这里只统计没有空档的块，所以偏差只能来自电流读数本身，跟采样率无关。

    返回 dict，块数为 0 时各值为 None（这块数据没法对账）。
    """
    out = dict(blocks=0, minutes=0.0, integral_mah=0.0, counter_mah=0.0,
               err_pct=None, worst_pct=None, best_pct=None)
    if len(seg) < 3:
        return out

    blocks: list[list[Sample]] = [[seg[0]]]
    for prev, now in zip(seg, seg[1:]):
        if now.t - prev.t > MAX_INTEGRATION_GAP_S:
            blocks.append([])
        blocks[-1].append(now)

    sum_q = sum_c = minutes = 0.0
    errs: list[tuple[float, float]] = []      # (ΔCC, 误差%) 用于加权
    for b in blocks:
        if len(b) < 2:
            continue
        dur = b[-1].t - b[0].t
        if dur < AUDIT_BLOCK_MIN_S:
            continue
        q = 0.0
        for a, c in zip(b, b[1:]):
            dt = c.t - a.t
            if dt > 0:
                q += (a.current_ma + c.current_ma) / 2000.0 * dt / 3.6
        q = abs(q)
        dcc = abs(b[-1].counter_mah - b[0].counter_mah)
        if dcc < AUDIT_BLOCK_MIN_MAH or q < 1:
            continue
        errs.append((dcc, (q - dcc) / dcc * 100.0))
        sum_q += q
        sum_c += dcc
        minutes += dur / 60.0

    if not errs:
        return out
    out.update(
        blocks=len(errs), minutes=minutes, integral_mah=sum_q, counter_mah=sum_c,
        err_pct=(sum_q - sum_c) / sum_c * 100.0 if sum_c > 0 else None,
        worst_pct=min(e for _, e in errs),
        best_pct=max(e for _, e in errs),
    )
    return out


def integrate_with_soc_fallback(seg: Sequence[Sample], design_mah: float,
                                charge: bool) -> tuple[float, float]:
    """没有库仑计时的保守积分：长空档改用 SOC 变化估算，不外推瞬时电流。

    SOC 是整数百分比，分辨率只有 1%（约 70 mAh），精度有限；但它至少是
    一个**守恒量**，不会像"拿醒来瞬间的 2 A 电流乘以 7 小时"那样凭空翻倍。
    """
    q_mah = 0.0
    e_wh = 0.0
    for a, b in zip(seg, seg[1:]):
        dt = b.t - a.t
        if dt <= 0:
            continue
        v_v = (a.voltage_mv + b.voltage_mv) / 2000.0
        if dt > MAX_INTEGRATION_GAP_S and design_mah > 0:
            soc_delta = (b.soc_pct - a.soc_pct) if charge else (a.soc_pct - b.soc_pct)
            dq = max(0.0, soc_delta) / 100.0 * design_mah
        else:
            i_a = abs((a.current_ma + b.current_ma) / 2000.0)
            dq = i_a * dt / 3.6
        q_mah += dq
        e_wh += dq / 1000.0 * v_v
    return q_mah, e_wh


def counter_delta(seg: Sequence[Sample], charge: bool) -> tuple[Optional[float], float]:
    """库仑计净变化，返回 (净电荷 mAh, 累计反向变化量 mAh)。

    放电段里 counter 应该单调下降，上升就是中途插过充电器；
    充电段恰好相反，应该单调上升，下降才是中途拔线放过电。
    累计反向变化超限时首尾差不再等于净电荷，调用方必须放弃 counter 通道。
    """
    counters = [s.counter_mah for s in seg if s.counter_mah > 0]
    if len(counters) < 2:
        return None, 0.0
    if charge:
        wrong_way = sum(max(0.0, counters[i - 1] - counters[i])
                        for i in range(1, len(counters)))
        net = counters[-1] - counters[0]
    else:
        wrong_way = sum(max(0.0, counters[i] - counters[i - 1])
                        for i in range(1, len(counters)))
        net = counters[0] - counters[-1]
    return net, wrong_way


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
    系数默认 0.002（每°C 0.2%），传 0 可完全关闭归一。
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

    数据通道优先级（v0.2 修订）：
      1. 库仑计可用且单调  → counter 首尾差（硬件累计量，抗采样稀疏）
      2. 无库仑计但采样连续 → 电流梯形积分
      3. 无库仑计且采样稀疏 → 长空档按 SOC 估算，并标"数据不足"
    电流积分在有 counter 时仍会算一遍，作为交叉校验值留在结果里。
    """
    if design_mah <= 0:
        raise ValueError("design_mah 必须大于 0，用厂商标称容量（mAh）")

    warnings: list[str] = []
    discharge = _pick_segment(_discharge_segments(samples))
    charge = _pick_segment(_charging_segments(samples))

    if _usable(discharge, charge=False) and _soc_ok(discharge, charge=False):
        seg, mode = discharge, "discharge"
    elif _usable(charge, charge=True) and _soc_ok(charge, charge=True):
        seg, mode = charge, "charge"
    else:
        # 都不够格：摊最长的一段给用户看，但结论会标"数据不足"
        seg = discharge if discharge is not None else charge
        if seg is None:
            raise ValueError("数据里没有可用的充放电段")
        mode = "discharge" if seg is discharge else "charge"

    is_charge = mode == "charge"
    duration = seg[-1].t - seg[0].t
    floor_s = MIN_CHARGE_SECONDS if is_charge else MIN_DISCHARGE_SECONDS
    soc_start, soc_end = seg[0].soc_pct, seg[-1].soc_pct
    soc_span = (soc_end - soc_start) if is_charge else (soc_start - soc_end)
    max_gap, coverage, long_gaps = gap_stats(seg)

    # ---- 1. 净电荷：优先库仑计 ----
    net, wrong_way = counter_delta(seg, is_charge)
    counter_ok = (net is not None and net >= 100
                  and wrong_way <= COUNTER_RISE_LIMIT_MAH)
    if wrong_way > COUNTER_RISE_LIMIT_MAH:
        warnings.append(
            f"库仑计出现 {wrong_way:.0f} mAh 的反向变化"
            "（中途插拔充电器或计数器重标定），首尾差已不可信"
        )

    fallback_q, fallback_e = integrate_with_soc_fallback(seg, design_mah, is_charge)
    ideal_q, ideal_e = integrate(seg)   # 原始梯形积分，仅作交叉校验
    ideal_abs = abs(ideal_q)
    audit = audit_integration(seg)

    if counter_ok:
        raw_charge, e_wh = net, net / 1000.0 * _time_weighted_voltage(seg)
        method = "charge-delta" if is_charge else "counter-delta"
        cross_check = ideal_abs
        divergence = ((cross_check - raw_charge) / raw_charge * 100.0
                      if raw_charge > 0 else None)
        warnings.append("数据取自系统库仑计差值，与采样频率无关")
    else:
        raw_charge, e_wh = fallback_q, fallback_e
        method = "charge-integration" if is_charge else "coulomb-integration"
        # counter 不可信时它不能当交叉校验值，别拿一个脏数去算偏差。
        cross_check = None
        divergence = None
        if long_gaps:
            warnings.append(
                "没有可用的库仑计，长空档改按 SOC 变化估算；"
                "SOC 只有 1% 分辨率，结果精度有限"
            )

    # ---- 2. 交叉校验：两条算法差太多，必须说清楚 ----
    if divergence is not None and abs(divergence) >= DIVERGENCE_WARN_PCT:
        warnings.append(
            f"电流积分与库仑计相差 {divergence:+.0f}%："
            f"积分 {ideal_abs:.0f} mAh / 库仑计 {raw_charge:.0f} mAh"
        )
        if coverage < MIN_COVERAGE_PCT:
            warnings.append(
                f"采样被打断 {long_gaps} 次，最长空档 {max_gap / 3600:.1f} 小时，"
                "梯形积分把唤醒瞬间的高电流当成了整段平均值，会严重高估容量"
            )

    # ---- 2.5 连续段对账：把"采样稀疏"和"读数本身偏"分开 ----
    if counter_ok and audit["blocks"] > 0 and audit["err_pct"] is not None:
        a_err = audit["err_pct"]
        if abs(a_err) >= 5:
            warnings.append(
                f"连续段对账：采样没断的 {audit['blocks']} 段"
                f"（共 {audit['minutes']:.0f} 分钟）里，电流积分仍比库仑计"
                f"{'少' if a_err < 0 else '多'} {abs(a_err):.0f}%。"
                "这不是采样稀疏造成的 —— 是 CURRENT_NOW 读数本身偏，加密采样也治不了"
            )
        else:
            warnings.append(
                f"连续段对账：{audit['blocks']} 段"
                f"（共 {audit['minutes']:.0f} 分钟）内电流积分与库仑计只差 "
                f"{a_err:+.0f}%，两条路互证"
            )

    # ---- 3. 采样质量判据 ----
    # 有库仑计时采样稀疏不致命（counter 是硬件累计量），
    # 只有退回电流积分才要求覆盖率达标。
    data_ok = counter_ok or (coverage >= MIN_COVERAGE_PCT and max_gap <= 300.0)
    if coverage < MIN_COVERAGE_PCT:
        warnings.append(
            f"有效采样覆盖率只有 {coverage:.0f}%（要求 ≥{MIN_COVERAGE_PCT:.0f}%），"
            f"最长 {max_gap / 3600:.1f} 小时没有采样点"
        )
        if counter_ok:
            warnings.append("已用库仑计差值出结论，采样稀疏不影响该结果")

    # ---- 4. SOC 跨度折算到满电 ----
    # 放电和充电对称处理：从 96% 用到 9% 只放出 87% 的电，
    # 直接拿这段电荷当"容量"会低估，跟充电段口径也不一致。
    q_mah = raw_charge
    if MIN_SOC_SPAN_PCT <= soc_span < 100.0:
        q_mah = raw_charge / (soc_span / 100.0)
        warnings.append(
            f"只覆盖 {soc_span:.0f}% 的 SOC 区间，已按跨度折算到满电"
            "（假设区间内电荷分布均匀）"
        )
    elif soc_span < MIN_SOC_SPAN_PCT:
        warnings.append(f"SOC 跨度只有 {soc_span:.0f}%，太短，不做折算")
    if is_charge and soc_span < MIN_CHARGE_SPAN_PCT:
        warnings.append(f"充电区间只覆盖 {soc_span:.0f} 个百分点，折算会放大误差")
    if soc_end < 95 and is_charge:
        warnings.append("没有充到满电就停止了，结果会偏低")
    if not is_charge:
        if soc_start < 95:
            warnings.append(f"放电开始时电量只有 {soc_start:.0f}%，不是满充状态")
        if soc_end > 10:
            warnings.append(f"放电结束时系统电量仍有 {soc_end:.0f}%，未放到关机")

    # ---- 5. 温度归一与最终判据 ----
    t_avg = time_weighted_temp(seg)
    q_norm = normalize_to_ref(q_mah, t_avg, temp_coeff)
    avg_i = raw_charge / (duration / 3600.0) if duration > 0 else 0.0

    span_ok = soc_span >= (MIN_CHARGE_SPAN_PCT if is_charge else 85.0)
    endpoint_ok = (soc_end >= 95) if is_charge else (soc_start >= 95 and soc_end <= 10)
    soh = q_norm / design_mah * 100.0
    lo, hi = SOH_PLAUSIBLE_RANGE
    reliable = (duration >= floor_s and span_ok and endpoint_ok
                and data_ok and raw_charge >= 100
                and lo <= soh <= hi)
    if not (lo <= soh <= hi):
        warnings.append(
            f"算出的健康度 {soh:.0f}% 超出物理合理区间 {lo:.0f}–{hi:.0f}%，"
            "请检查标称容量是否填对、电流单位是否为 mA、期间是否插过充电器"
        )
    if not reliable and duration >= floor_s:
        warnings.append("本次数据不满足完整测量条件，不给出健康度结论")

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
        dcir_mohm=estimate_dcir(seg),
        method=method,
        warnings=warnings,
        raw_charge_mah=raw_charge,
        soc_span_pct=soc_span,
        max_gap_s=max_gap,
        coverage_pct=coverage,
        long_gaps=long_gaps,
        reliable=reliable,
        cross_check_mah=cross_check,
        divergence_pct=divergence,
        audit_blocks=audit["blocks"],
        audit_minutes=audit["minutes"],
        audit_integral_mah=audit["integral_mah"],
        audit_counter_mah=audit["counter_mah"],
        audit_err_pct=audit["err_pct"],
        audit_worst_pct=audit["worst_pct"],
        audit_best_pct=audit["best_pct"],
        segment=list(seg),
    )
