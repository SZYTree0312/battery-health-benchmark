package com.tree0312.bhbench;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 报告计算。
 *
 * 逻辑和 PC 端 analyzer/core.py 保持一致，好处是同一份 CSV 在手机和电脑上
 * 算出同一个数，谁也不用怀疑对方。
 *
 * v0.2 修订：优先用系统库仑计（CHARGE_COUNTER）的首尾差，而不是电流积分。
 * 原因：手机息屏进 Doze 后，前台服务的 5 秒采样会被推迟到几十分钟甚至几小时，
 * 醒来那一刻往往是唤醒/亮屏的高电流；拿梯形法把这个瞬时值外推到整个空档，
 * 真机上算出过 9507 mAh（实际 5795 mAh）。库仑计是硬件累计量，不受采样频率影响。
 *
 * 数据通道优先级：
 *   1. 库仑计可用且方向单调 → counter 首尾差
 *   2. 没有库仑计但采样连续 → 电流梯形积分
 *   3. 没有库仑计且采样稀疏 → 长空档改按 SOC 变化估算，并拒绝给健康度
 */
public class Analyzer {

    private static final double TEMP_COEFF = 0.002;
    private static final double TEMP_REF = 25.0;

    /** 放电最少跑这么久才算有效测量。 */
    private static final double MIN_DISCHARGE_S = 300;
    /** 充电更慢，但夜里充一整晚通常远超这个下限。 */
    private static final double MIN_CHARGE_S = 600;
    /** 充电折算要求 SOC 跨度够大，跨太小会把误差放大。 */
    private static final double MIN_CHARGE_SPAN = 60;
    private static final double MIN_DISCHARGE_SPAN = 85;
    private static final double MIN_SOC_SPAN = 10;
    /** 电流积分可信的最大采样间隔。 */
    private static final double MAX_GAP_S = 60;
    private static final double MIN_COVERAGE_PCT = 80;
    /** 库仑计反向变化上限，超了说明中途插拔过充电器。 */
    private static final double COUNTER_WRONG_WAY_LIMIT = 50;
    private static final double DIVERGENCE_WARN_PCT = 10;
    /** 健康度物理合理区间，超出就是数据有问题，绝不能给「良好」。 */
    private static final double SOH_MIN = 30, SOH_MAX = 115;

    public static class Row {
        double t, current, voltage, soc, temp, counter;
        String status;
    }

    public static class Report {
        public double designMah;
        public double measuredMah;      // SOC 折算后、实测温度下的容量
        public double normalizedMah;    // 再折算到 25°C 的等效容量
        public double rawChargeMah;     // 折算前的净电荷变化
        public double energyWh;
        public double avgVoltage;
        public double avgCurrent;
        public double avgTemp;
        public double peakTemp;
        public double durationS;
        public int socStart = -1;
        public int socEnd = -1;
        public double socSpan;
        public int samples;
        /** 采样完整性 */
        public double maxGapS;
        public double coveragePct = 100;
        public int longGaps;
        /** 两条算法的交叉校验 */
        public double crossCheckMah = -1;
        public double divergencePct = Double.NaN;
        /** counter-delta / coulomb-integration / charge-delta / charge-integration */
        public String method = "";
        /** discharge 或 charge */
        public String mode = "discharge";
        public boolean counterUsed = false;
        public String grade = "";
        /** 样本是否够格出结论。不足时只说事实，绝不给健康度。 */
        public boolean reliable = false;
        /**
         * 本次结论实际用到的那一段采样点（已切好、已排序）。
         * 结果页拿它画曲线 —— 不重新读一遍文件，也保证画的和算的是同一份数据。
         */
        public List<Row> segment;
        public final List<String> warnings = new ArrayList<>();

        public double sohPct() {
            return designMah > 0 ? normalizedMah / designMah * 100.0 : 0;
        }

        public boolean isChargeMode() {
            return "charge".equals(mode);
        }
    }

    public static Report analyze(File csv, double designMah) {
        Report r = new Report();
        r.designMah = designMah;

        List<Row> all = parse(csv);
        if (all.isEmpty()) {
            r.warnings.add("文件里没有有效数据");
            return r;
        }

        List<Row> discharge = longestSegment(all, false);
        List<Row> charge = longestSegment(all, true);

        boolean okDischarge = discharge != null && discharge.size() >= 2
                && usable(discharge, false) && socOk(discharge, false);
        boolean okCharge = charge != null && charge.size() >= 2
                && usable(charge, true) && socOk(charge, true);

        List<Row> seg;
        if (okDischarge) {
            seg = discharge;
            r.mode = "discharge";
        } else if (okCharge) {
            seg = charge;
            r.mode = "charge";
        } else {
            // 都不够格，仍然把最长的一段摊出来给用户看，但结论标"数据不足"
            seg = pickLonger(discharge, charge);
            if (seg == null || seg.size() < 2) {
                r.warnings.add("没有可用的充放电数据");
                return r;
            }
            r.mode = (seg == charge) ? "charge" : "discharge";
        }

        fillBasics(r, seg);
        r.segment = seg;
        computeNetCharge(r, seg);
        applySocScaling(r);

        double factor = 1.0 + TEMP_COEFF * (r.avgTemp - TEMP_REF);
        r.normalizedMah = (factor > 0.5 && factor < 2.0)
                ? r.measuredMah / factor : r.measuredMah;
        r.avgCurrent = r.durationS > 0
                ? r.rawChargeMah / (r.durationS / 3600.0) : 0;

        checkReliability(r);
        return r;
    }

    // ------------------------------------------------------------ 净电荷

    /**
     * 算出折算前的净电荷，并选定数据通道。
     * 有库仑计时一律以它为准，电流积分退为交叉校验值。
     */
    private static void computeNetCharge(Report r, List<Row> seg) {
        double net = counterDelta(seg, r.isChargeMode());
        double wrongWay = counterWrongWay(seg, r.isChargeMode());
        boolean counterOk = net >= 100 && wrongWay <= COUNTER_WRONG_WAY_LIMIT;

        if (wrongWay > COUNTER_WRONG_WAY_LIMIT) {
            r.warnings.add(String.format(Locale.US,
                    "库仑计出现 %.0f mAh 的反向变化（中途插拔充电器或计数器重标定），首尾差已不可信",
                    wrongWay));
        }

        // 原始梯形积分，用来和库仑计对账
        double ideal = 0;
        for (int i = 1; i < seg.size(); i++) {
            Row a = seg.get(i - 1), b = seg.get(i);
            double dt = b.t - a.t;
            if (dt <= 0) continue;
            ideal += (a.current + b.current) / 2000.0 * dt / 3.6;
        }
        double idealAbs = Math.abs(ideal);

        if (counterOk) {
            r.rawChargeMah = net;
            r.energyWh = net / 1000.0 * r.avgVoltage;
            r.method = r.isChargeMode() ? "charge-delta" : "counter-delta";
            r.counterUsed = true;
            r.crossCheckMah = idealAbs;
            r.divergencePct = (idealAbs - net) / net * 100.0;
            r.warnings.add("数据取自系统库仑计差值，与采样频率无关");
        } else {
            // 没有可信库仑计：连续段用电流积分，长空档按 SOC 变化估算
            double q = 0, e = 0;
            for (int i = 1; i < seg.size(); i++) {
                Row a = seg.get(i - 1), b = seg.get(i);
                double dt = b.t - a.t;
                if (dt <= 0) continue;
                double v = (a.voltage + b.voltage) / 2000.0;
                double dq;
                if (dt > MAX_GAP_S && r.designMah > 0) {
                    double delta = r.isChargeMode()
                            ? b.soc - a.soc : a.soc - b.soc;
                    dq = Math.max(0, delta) / 100.0 * r.designMah;
                } else {
                    dq = Math.abs((a.current + b.current) / 2000.0) * dt / 3.6;
                }
                q += dq;
                e += dq / 1000.0 * v;
            }
            r.rawChargeMah = q;
            r.energyWh = e;
            r.method = r.isChargeMode() ? "charge-integration" : "coulomb-integration";
            if (r.longGaps > 0) {
                r.warnings.add("没有可用的库仑计，长空档改按 SOC 变化估算；SOC 只有 1% 分辨率，结果精度有限");
            }
        }

        if (!Double.isNaN(r.divergencePct)
                && Math.abs(r.divergencePct) >= DIVERGENCE_WARN_PCT) {
            r.warnings.add(String.format(Locale.US,
                    "电流积分与库仑计相差 %+.0f%%：积分 %.0f mAh / 库仑计 %.0f mAh",
                    r.divergencePct, idealAbs, r.rawChargeMah));
            if (r.coveragePct < MIN_COVERAGE_PCT) {
                r.warnings.add(String.format(Locale.US,
                        "采样被打断 %d 次，最长空档 %.1f 小时，梯形积分把唤醒瞬间的高电流当成了整段平均值，会严重高估容量",
                        r.longGaps, r.maxGapS / 3600.0));
            }
        }
    }

    /** 按实际 SOC 跨度折算到满电容量，放电与充电对称处理。 */
    private static void applySocScaling(Report r) {
        double span = r.socSpan;
        r.measuredMah = r.rawChargeMah;
        if (span >= MIN_SOC_SPAN && span < 100) {
            r.measuredMah = r.rawChargeMah / (span / 100.0);
            r.warnings.add(String.format(Locale.US,
                    "只覆盖 %.0f%% 的 SOC 区间，已按跨度折算到满电（假设区间内电荷分布均匀）",
                    span));
        } else if (span < MIN_SOC_SPAN) {
            r.warnings.add(String.format(Locale.US,
                    "SOC 跨度只有 %.0f%%，太短，不做折算", span));
        }
    }

    // ------------------------------------------------------------ 样本质量

    private static void checkReliability(Report r) {
        boolean chargeMode = r.isChargeMode();
        double minSeconds = chargeMode ? MIN_CHARGE_S : MIN_DISCHARGE_S;

        if (r.durationS < minSeconds) {
            r.warnings.add(String.format(Locale.US,
                    "%s时长只有 %.0f 秒，低于 %.0f 秒下限",
                    chargeMode ? "充电" : "放电", r.durationS, minSeconds));
        }
        if (r.rawChargeMah < 100) {
            r.warnings.add(String.format(Locale.US,
                    "全程只测到 %.0f mAh 的电荷变化，数据量太少", r.rawChargeMah));
        }

        // 有库仑计时采样稀疏不致命；只有退回电流积分才要求覆盖率达标
        boolean dataOk = r.counterUsed
                || (r.coveragePct >= MIN_COVERAGE_PCT && r.maxGapS <= 300);
        if (r.coveragePct < MIN_COVERAGE_PCT) {
            r.warnings.add(String.format(Locale.US,
                    "有效采样覆盖率只有 %.0f%%（要求 ≥%.0f%%），最长 %.1f 小时没有采样点",
                    r.coveragePct, MIN_COVERAGE_PCT, r.maxGapS / 3600.0));
            if (r.counterUsed) {
                r.warnings.add("已用库仑计差值出结论，采样稀疏不影响该结果");
            }
        }

        if (chargeMode) {
            if (r.socSpan < MIN_CHARGE_SPAN) {
                r.warnings.add(String.format(Locale.US,
                        "充电区间只覆盖 %d%% → %d%%，不足 %d 个百分点，折算会放大误差",
                        r.socStart, r.socEnd, (int) MIN_CHARGE_SPAN));
            }
            if (r.socEnd < 95) {
                r.warnings.add("没有充到满电就停止了，结果会偏低");
            }
        } else {
            if (r.socStart < 95) {
                r.warnings.add("开始时电量只有 " + r.socStart + "%，不是满电状态");
            }
            if (r.socEnd > 10) {
                r.warnings.add("结束时电量还有 " + r.socEnd + "%，没放到关机");
            }
        }

        double soh = r.sohPct();
        boolean plausible = soh >= SOH_MIN && soh <= SOH_MAX;
        if (!plausible) {
            r.warnings.add(String.format(Locale.US,
                    "算出的健康度 %.0f%% 超出物理合理区间 %.0f–%.0f%%，请检查标称容量是否填对、电流单位是否为 mA、期间是否插过充电器",
                    soh, SOH_MIN, SOH_MAX));
            r.grade = "数据异常";
            r.reliable = false;
            return;
        }

        boolean spanOk = r.socSpan >= (chargeMode ? MIN_CHARGE_SPAN : MIN_DISCHARGE_SPAN);
        boolean endpointOk = chargeMode
                ? r.socEnd >= 95
                : (r.socStart >= 95 && r.socEnd <= 10);
        r.reliable = r.durationS >= minSeconds && spanOk && endpointOk
                && dataOk && r.rawChargeMah >= 100;

        if (!r.reliable) {
            r.warnings.add("本次数据不满足完整测量条件，不给出健康度结论");
            r.grade = "数据不足";
        } else if (soh >= 90) {
            r.grade = "良好";
        } else if (soh >= 80) {
            r.grade = "一般";
        } else if (soh >= 70) {
            r.grade = "衰减明显";
        } else {
            r.grade = "建议更换";
        }
    }

    // ------------------------------------------------------------ 工具

    private static void fillBasics(Report r, List<Row> seg) {
        r.samples = seg.size();
        r.durationS = seg.get(seg.size() - 1).t - seg.get(0).t;
        r.socStart = Math.round((float) seg.get(0).soc);
        r.socEnd = Math.round((float) seg.get(seg.size() - 1).soc);
        r.socSpan = r.isChargeMode()
                ? r.socEnd - r.socStart : r.socStart - r.socEnd;
        r.avgVoltage = avgVoltage(seg);
        r.avgTemp = weightedTemp(seg);
        r.peakTemp = peakTemp(seg);

        double total = 0, covered = 0;
        for (int i = 1; i < seg.size(); i++) {
            double dt = seg.get(i).t - seg.get(i - 1).t;
            if (dt <= 0) continue;
            total += dt;
            r.maxGapS = Math.max(r.maxGapS, dt);
            if (dt <= MAX_GAP_S) covered += dt;
            else r.longGaps++;
        }
        r.coveragePct = total > 0 ? covered / total * 100.0 : 100;
    }

    private static List<Row> pickLonger(List<Row> a, List<Row> b) {
        if (a == null) return b;
        if (b == null) return a;
        return span(a) >= span(b) ? a : b;
    }

    private static double span(List<Row> seg) {
        return seg.isEmpty() ? 0 : seg.get(seg.size() - 1).t - seg.get(0).t;
    }

    /** 切出连续的同向段（放电 / 充电），返回时间跨度最长的一段。 */
    private static List<Row> longestSegment(List<Row> all, boolean wantCharge) {
        List<Row> best = null;
        List<Row> cur = new ArrayList<>();
        for (Row row : all) {
            boolean match = wantCharge ? isCharging(row) : isDischarging(row);
            if (match) {
                cur.add(row);
            } else {
                if (best == null || span(cur) > span(best)) best = new ArrayList<>(cur);
                cur.clear();
            }
        }
        if (best == null || span(cur) > span(best)) best = new ArrayList<>(cur);
        return best.isEmpty() ? null : best;
    }

    private static boolean usable(List<Row> seg, boolean charge) {
        if (seg.size() < 2) return false;
        double dur = span(seg);
        if (dur < (charge ? MIN_CHARGE_S : MIN_DISCHARGE_S)) return false;
        double net = charge ? counterDelta(seg, true) : counterDelta(seg, false);
        if (net >= 100) return true;
        for (Row row : seg) if (Math.abs(row.current) > 1) return true;
        return false;
    }

    private static boolean socOk(List<Row> seg, boolean charge) {
        int start = Math.round((float) seg.get(0).soc);
        int end = Math.round((float) seg.get(seg.size() - 1).soc);
        if (charge) {
            return (end - start) >= MIN_CHARGE_SPAN && end >= 95;
        }
        return start >= 95 && end <= 10;
    }

    /** 库仑计净变化；充电段应为正（上升），放电段应为正（下降量）。 */
    private static double counterDelta(List<Row> seg, boolean charge) {
        List<Double> c = counters(seg);
        if (c.size() < 2) return 0;
        double first = c.get(0), last = c.get(c.size() - 1);
        return charge ? last - first : first - last;
    }

    /** 累计反向变化：放电段里上升、充电段里下降，都是中途插拔充电器的迹象。 */
    private static double counterWrongWay(List<Row> seg, boolean charge) {
        List<Double> c = counters(seg);
        double sum = 0;
        for (int i = 1; i < c.size(); i++) {
            double d = c.get(i) - c.get(i - 1);
            if (charge ? d < 0 : d > 0) sum += Math.abs(d);
        }
        return sum;
    }

    private static List<Double> counters(List<Row> seg) {
        List<Double> out = new ArrayList<>();
        for (Row row : seg) if (row.counter > 0) out.add(row.counter);
        return out;
    }

    private static List<Row> parse(File csv) {
        List<Row> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(csv))) {
            String line;
            boolean headerSkipped = false;
            while ((line = br.readLine()) != null) {
                if (!headerSkipped) { headerSkipped = true; continue; }
                if (line.trim().isEmpty()) continue;
                String[] p = line.split(",");
                if (p.length < 6) continue;
                try {
                    Row r = new Row();
                    r.t = Double.parseDouble(p[0]);
                    r.current = Double.parseDouble(p[1]);
                    r.voltage = Double.parseDouble(p[2]);
                    r.soc = Double.parseDouble(p[3]);
                    r.temp = Double.parseDouble(p[4]);
                    r.status = p[5].trim();
                    // 第 7 列是 CHARGE_COUNTER 剩余电荷。v0.1 表头误叫
                    // charge_full_mah，值是一样的，按位置读不受列名影响。
                    r.counter = p.length > 6 ? Double.parseDouble(p[6]) : 0;
                    rows.add(r);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception e) {
            // 文件读不了就当空数据
        }
        return rows;
    }

    private static boolean isDischarging(Row r) {
        if (r.status == null) return false;
        String s = r.status.toLowerCase(Locale.US);
        if (s.startsWith("discharg")) return true;
        return (s.isEmpty() || s.equals("unknown")) && r.current > 0;
    }

    private static boolean isCharging(Row r) {
        if (r.status == null) return false;
        String s = r.status.toLowerCase(Locale.US);
        return s.startsWith("charg") || s.equals("full");
    }

    private static double avgVoltage(List<Row> rows) {
        double total = 0, acc = 0;
        for (int i = 1; i < rows.size(); i++) {
            double dt = rows.get(i).t - rows.get(i - 1).t;
            if (dt <= 0) continue;
            total += dt;
            acc += dt * (rows.get(i).voltage + rows.get(i - 1).voltage) / 2.0;
        }
        return total > 0 ? acc / total / 1000.0 : 0;
    }

    private static double weightedTemp(List<Row> rows) {
        double total = 0, acc = 0;
        for (int i = 1; i < rows.size(); i++) {
            double dt = rows.get(i).t - rows.get(i - 1).t;
            if (dt <= 0) continue;
            total += dt;
            acc += dt * (rows.get(i).temp + rows.get(i - 1).temp) / 2.0;
        }
        return total > 0 ? acc / total : 0;
    }

    private static double peakTemp(List<Row> rows) {
        double m = -100;
        for (Row r : rows) if (r.temp > m) m = r.temp;
        return m;
    }
}
