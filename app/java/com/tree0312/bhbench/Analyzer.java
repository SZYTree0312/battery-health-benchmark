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
 * 两条数据通道：
 *   1. 有电流      → 梯形积分
 *   2. 只有库仑计  → 首尾相减（小米这类把 sysfs 封死的机器走这条）
 */
public class Analyzer {

    private static final double TEMP_COEFF = 0.008;
    private static final double TEMP_REF = 25.0;
    private static final double MIN_SECONDS = 300;

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
        public String method = "";
        public String grade = "";
        public final List<String> warnings = new ArrayList<>();

        public double sohPct() {
            return designMah > 0 ? normalizedMah / designMah * 100.0 : 0;
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

        // 取最长的连续放电段：中途插线充电会打断，只认最长那段。
        List<Row> best = null;
        List<Row> cur = new ArrayList<>();
        for (Row row : all) {
            if (isDischarging(row)) {
                cur.add(row);
            } else {
                if (best == null || cur.size() > best.size()) best = new ArrayList<>(cur);
                cur.clear();
            }
        }
        if (best == null || cur.size() > best.size()) best = cur;
        if (best == null || best.size() < 2) {
            r.warnings.add("没有找到放电段。测试期间是否一直插着充电线？");
            return r;
        }

        r.samples = best.size();
        r.durationS = best.get(best.size() - 1).t - best.get(0).t;
        r.socStart = (int) best.get(0).soc;
        r.socEnd = (int) best.get(best.size() - 1).soc;

        double q = 0, e = 0;
        boolean hasCurrent = false;
        for (Row row : best) if (Math.abs(row.current) > 1) hasCurrent = true;

        if (hasCurrent) {
            for (int i = 1; i < best.size(); i++) {
                Row a = best.get(i - 1), b = best.get(i);
                double dt = b.t - a.t;
                if (dt <= 0) continue;
                double iA = (a.current + b.current) / 2000.0;
                double vV = (a.voltage + b.voltage) / 2000.0;
                q += iA * dt / 3.6;
                e += vV * iA * dt / 3600.0;
            }
            r.method = "coulomb-integration";
        } else {
            double first = 0, last = 0;
            for (Row row : best) if (row.counter > 0) { first = row.counter; break; }
            for (int i = best.size() - 1; i >= 0; i--) {
                if (best.get(i).counter > 0) { last = best.get(i).counter; break; }
            }
            q = first - last;
            if (q <= 0) {
                r.warnings.add("库仑计读数没有下降，这段数据里电池可能并未放电");
                q = Math.abs(first - last);
            }
            r.method = "counter-delta";
            e = q / 1000.0 * avgVoltage(best);
        }

        r.measuredMah = q;
        r.avgVoltage = avgVoltage(best);
        r.energyWh = e;
        r.avgTemp = weightedTemp(best);
        r.peakTemp = peakTemp(best);
        r.avgCurrent = r.durationS > 0 ? q / (r.durationS / 3600.0) : 0;

        double factor = 1.0 + TEMP_COEFF * (r.avgTemp - TEMP_REF);
        r.normalizedMah = (factor > 0.5 && factor < 2.0) ? q / factor : q;

        double soh = r.sohPct();
        if (soh >= 90) r.grade = "良好";
        else if (soh >= 80) r.grade = "一般";
        else if (soh >= 70) r.grade = "衰减明显";
        else r.grade = "建议更换";

        if (r.durationS < MIN_SECONDS) {
            r.warnings.add(String.format(Locale.US,
                    "放电时长只有 %.0f 秒，低于 %.0f 秒下限，结论仅供参考",
                    r.durationS, MIN_SECONDS));
        }
        if (r.socEnd > 10) {
            r.warnings.add("结束时电量还有 " + r.socEnd + "%，没放完，容量会偏低");
        }
        if (r.socStart < 95) {
            r.warnings.add("开始时电量只有 " + r.socStart + "%，不是满电，容量会偏低");
        }
        if ("counter-delta".equals(r.method)) {
            r.warnings.add("数据取自系统库仑计，精度取决于厂商标定");
        }
        return r;
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
            // 文件读不了就当空数据，交给上层提示
        }
        return rows;
    }

    private static boolean isDischarging(Row r) {
        return r.status != null && r.status.toLowerCase(Locale.US).startsWith("discharg");
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
