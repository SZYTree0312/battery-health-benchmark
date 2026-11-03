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
 * 三种测量方式，按优先级：
 *   1. 放电 + 有电流      → 梯形积分（最准）
 *   2. 放电 + 只有库仑计  → 计数器首尾差
 *   3. 充电 + 只有库仑计  → 充入量按 SOC 跨度折算
 *
 * 第 3 种是为工程现实加的：没人愿意为了测电池四五个小时不用手机。
 * 代价是要假设电荷在 SOC 区间上分布均匀，所以只接受跨度 ≥ 60% 的充电段，
 * 并且在报告里标注这是折算值。
 */
public class Analyzer {

    private static final double TEMP_COEFF = 0.008;
    private static final double TEMP_REF = 25.0;

    /** 放电最少跑这么久才算有效测量。 */
    private static final double MIN_DISCHARGE_S = 300;
    /** 充电更慢，但夜里充一整晚通常远超这个下限。 */
    private static final double MIN_CHARGE_S = 600;
    /** 充电折算要求 SOC 跨度够大，跨太小会把误差放大。 */
    private static final double MIN_CHARGE_SPAN = 60;

    public static class Row {
        double t, current, voltage, soc, temp, counter;
        String status;
    }

    public static class Report {
        public double designMah;
        public double measuredMah;
        public double normalizedMah;
        public double energyWh;
        public double avgVoltage;
        public double avgCurrent;
        public double avgTemp;
        public double peakTemp;
        public double durationS;
        public int socStart = -1;
        public int socEnd = -1;
        public int samples;
        /** coulomb-integration / counter-delta / charge-delta / charge-integration */
        public String method = "";
        /** discharge 或 charge */
        public String mode = "discharge";
        public String grade = "";
        /** 样本是否够格出结论。不足时只说事实，绝不给健康度。 */
        public boolean reliable = false;
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
                && usable(discharge, false);
        boolean okCharge = charge != null && charge.size() >= 2
                && usable(charge, true);

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

        if ("charge".equals(r.mode)) {
            computeFromCharge(r, seg);
        } else {
            computeFromDischarge(r, seg);
        }

        double factor = 1.0 + TEMP_COEFF * (r.avgTemp - TEMP_REF);
        r.normalizedMah = (factor > 0.5 && factor < 2.0)
                ? r.measuredMah / factor : r.measuredMah;
        r.avgCurrent = r.durationS > 0 ? r.measuredMah / (r.durationS / 3600.0) : 0;
        r.energyWh = r.measuredMah / 1000.0 * r.avgVoltage;

        checkReliability(r);
        return r;
    }

    // ------------------------------------------------------------ 两条计算通道

    private static void computeFromDischarge(Report r, List<Row> seg) {
        boolean hasCurrent = false;
        for (Row row : seg) if (Math.abs(row.current) > 1) hasCurrent = true;

        if (hasCurrent) {
            double q = 0;
            for (int i = 1; i < seg.size(); i++) {
                Row a = seg.get(i - 1), b = seg.get(i);
                double dt = b.t - a.t;
                if (dt <= 0) continue;
                q += (a.current + b.current) / 2000.0 * dt / 3.6;
            }
            r.measuredMah = q;
            r.method = "coulomb-integration";
        } else {
            double first = firstCounter(seg), last = lastCounter(seg);
            r.measuredMah = first - last;
            if (r.measuredMah <= 0) {
                r.measuredMah = Math.abs(first - last);
                r.warnings.add("库仑计读数没有下降，这段数据可能并非放电");
            }
            r.method = "counter-delta";
        }
    }

    private static void computeFromCharge(Report r, List<Row> seg) {
        double first = firstCounter(seg), last = lastCounter(seg);
        double raw = last - first;

        if (raw <= 0) {
            // 库仑计读不到，退回充电电流积分（充电时电流为负，取绝对值）
            double q = 0;
            boolean hasCurrent = false;
            for (int i = 1; i < seg.size(); i++) {
                Row a = seg.get(i - 1), b = seg.get(i);
                double dt = b.t - a.t;
                if (dt <= 0) continue;
                double ia = Math.abs((a.current + b.current) / 2000.0);
                if (ia > 0.001) hasCurrent = true;
                q += ia * dt / 3.6;
            }
            if (!hasCurrent) {
                r.measuredMah = 0;
                r.method = "charge-delta";
                return;
            }
            raw = q;
            r.method = "charge-integration";
        } else {
            r.method = "charge-delta";
        }

        // 充入量覆盖的是 [socStart, socEnd] 这段区间，按比例折算到满电
        double span = r.socEnd - r.socStart;
        r.measuredMah = (span >= 10 && span < 100) ? raw / (span / 100.0) : raw;
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
        if (r.measuredMah < 100) {
            r.warnings.add(String.format(Locale.US,
                    "全程只测到 %.0f mAh 的电荷变化，数据量太少", r.measuredMah));
        }

        if (chargeMode) {
            if (r.socEnd - r.socStart < MIN_CHARGE_SPAN) {
                r.warnings.add(String.format(Locale.US,
                        "充电区间只覆盖 %d%% → %d%%，不足 %d 个百分点，折算会放大误差",
                        r.socStart, r.socEnd, (int) MIN_CHARGE_SPAN));
            }
            if (r.socEnd < 95) {
                r.warnings.add("没有充到满电就停止了，结果会偏低");
            }
            r.warnings.add("充电段按 SOC 区间折算，假设区间内电荷分布均匀");
        } else {
            if (r.socStart < 95) {
                r.warnings.add("开始时电量只有 " + r.socStart + "%，不是满电状态");
            }
            if (r.socEnd > 10) {
                r.warnings.add("结束时电量还有 " + r.socEnd + "%，没放到关机");
            }
        }

        if ("counter-delta".equals(r.method) || "charge-delta".equals(r.method)) {
            r.warnings.add("数据取自系统库仑计，精度取决于厂商标定");
        }

        boolean chargeOk = r.durationS >= MIN_CHARGE_S
                && r.socEnd - r.socStart >= MIN_CHARGE_SPAN
                && r.socEnd >= 95
                && r.measuredMah >= 100;
        boolean dischargeOk = r.durationS >= MIN_DISCHARGE_S
                && r.socStart >= 95
                && r.socEnd <= 10
                && r.measuredMah >= 100;

        r.reliable = chargeMode ? chargeOk : dischargeOk;

        if (r.reliable) {
            double soh = r.sohPct();
            if (soh >= 90) r.grade = "良好";
            else if (soh >= 80) r.grade = "一般";
            else if (soh >= 70) r.grade = "衰减明显";
            else r.grade = "建议更换";
        } else {
            r.grade = "数据不足";
        }
    }

    // ------------------------------------------------------------ 工具

    private static void fillBasics(Report r, List<Row> seg) {
        r.samples = seg.size();
        r.durationS = seg.get(seg.size() - 1).t - seg.get(0).t;
        r.socStart = Math.round((float) seg.get(0).soc);
        r.socEnd = Math.round((float) seg.get(seg.size() - 1).soc);
        r.avgVoltage = avgVoltage(seg);
        r.avgTemp = weightedTemp(seg);
        r.peakTemp = peakTemp(seg);
    }

    private static List<Row> pickLonger(List<Row> a, List<Row> b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.size() >= b.size() ? a : b;
    }

    /** 切出连续的同向段（放电 / 充电），返回最长的一段。 */
    private static List<Row> longestSegment(List<Row> all, boolean wantCharge) {
        List<Row> best = null;
        List<Row> cur = new ArrayList<>();
        for (Row row : all) {
            boolean match = wantCharge ? isCharging(row) : isDischarging(row);
            if (match) {
                cur.add(row);
            } else {
                if (best == null || cur.size() > best.size()) best = new ArrayList<>(cur);
                cur.clear();
            }
        }
        if (best == null || cur.size() > best.size()) best = new ArrayList<>(cur);
        return best.isEmpty() ? null : best;
    }

    private static boolean usable(List<Row> seg, boolean charge) {
        if (seg.size() < 2) return false;
        double dur = seg.get(seg.size() - 1).t - seg.get(0).t;
        if (dur < (charge ? MIN_CHARGE_S : MIN_DISCHARGE_S)) return false;
        double q = charge
                ? lastCounter(seg) - firstCounter(seg)
                : firstCounter(seg) - lastCounter(seg);
        return q >= 100;
    }

    private static double firstCounter(List<Row> seg) {
        for (Row row : seg) if (row.counter > 0) return row.counter;
        return 0;
    }

    private static double lastCounter(List<Row> seg) {
        for (int i = seg.size() - 1; i >= 0; i--) {
            if (seg.get(i).counter > 0) return seg.get(i).counter;
        }
        return 0;
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
        return r.status != null && r.status.toLowerCase(Locale.US).startsWith("discharg");
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
