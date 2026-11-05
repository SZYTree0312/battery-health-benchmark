package com.tree0312.bhbench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 积分精度验证的数学部分 —— 纯 Java，不依赖 Android SDK。
 *
 * <p>存在意义：v0.5.3-rc2 想在息屏放电时拿到连续采样，被 MIUI PowerKeeper
 * 8 秒就收走了唤醒锁（见仓库 issue #1）。v0.5.5 改成安兔兔那条路：充电时
 * 保持页面在前台、屏幕常亮，进程永远不算「闲置」，系统根本不会冻它。
 * 采样连续这个前提一旦成立，就可以正经回答「电流积分到底准不准」。
 *
 * <p>实验设计（一次测量回答两个问题，不用跑五遍）：
 * <ol>
 *   <li><b>多尺度抽稀</b>：把同一段原始数据抽成 1/2/5/10/30 秒间隔，
 *       各自做梯形积分。若结果随间隔变化，说明采样还不够密；
 *       若跨尺度收敛，采样率就不再是怀疑对象。</li>
 *   <li><b>分段比值 k</b>：每 2 分钟一个窗口，各算 k = ∫I dt / ΔCC。
 *       k 稳定 → 电流读数是固定比例偏差，可以用 1/k 标定；
 *       k 随 SOC/温度/电流档漂 → 标定没意义。</li>
 * </ol>
 *
 * <p>基准是库仑计 ΔCC，不是「真值」。库仑计自己也有标定误差，这里量的是
 * 两条独立路径的<b>一致性</b>：电流读数相对库仑计偏多少、稳不稳。
 *
 * <p>参考实现：analyzer/accuracy.py，两边公式与阈值必须一致。
 */
public final class AccuracyMath {

    private AccuracyMath() {}

    /** 抽稀用的尺度（秒）。1 秒是原始采样，其余是从同一段数据里抽出来的。 */
    public static final double[] SCALES_S = {1, 2, 5, 10, 30};
    /** 分段窗口长度（秒）。太短的话 ΔCC 的 1 mAh 量化噪声会淹没结论。 */
    public static final double WINDOW_S = 120;
    /** 窗口至少要累积这么多电荷（mAh）才纳入统计。 */
    public static final double WINDOW_MIN_MAH = 20;
    /** 判定「采样没断」的最大间隔：原始间隔的 3 倍，且不小于 10 秒。 */
    public static final double MIN_GAP_S = 10;
    /** 跨尺度积分最大/最小差超过这个百分比 → 还没收敛。 */
    public static final double CONVERGE_PCT = 2.0;
    /** k 的变异系数低于这个百分比 → 认为是固定比例偏差，可标定。 */
    public static final double STABLE_CV_PCT = 3.0;

    public static class Sample {
        public double t;            // 秒，相对起点
        public double currentMa;   // mA，符号依厂商而定，只用绝对值
        public double voltageMv;
        public double socPct;
        public double tempC;
        public double counterMah;  // 库仑计剩余电荷
        public boolean plugged;
        public String status = "";  // charging / full / discharging，导出 CSV 用
    }

    public static class Scale {
        public double stepS;
        public int points;
        public double integralMah;
        /** 相对库仑计 ΔCC 的偏差 % */
        public double errPct;
    }

    public static class Window {
        public double tStart;
        public double integralMah;
        public double counterMah;
        /** ∫I dt / ΔCC */
        public double k;
        public double avgCurrentMa;
        public double avgTempC;
    }

    public static class Report {
        public boolean valid;
        public String note = "";
        public int points;
        public int blocks;
        public int gaps;
        public double coveredS;
        /** 基准：各连续块的 |ΔCC| 之和 */
        public double counterMah;
        /** 原始采样间隔下的 ∫I dt */
        public double integralMah;
        public double kMean;
        public double kStd;
        public double kCvPct;
        public double kMin;
        public double kMax;
        /** 跨尺度积分极差 / 最大值 × 100 */
        public double spreadPct;
        public boolean converged;
        public boolean calibratable;
        /** 若可标定，把电流读数乘上这个系数就与库仑计对齐 */
        public double calibration;
        public final List<Scale> scales = new ArrayList<>();
        public final List<Window> windows = new ArrayList<>();
    }

    /**
     * 按空档切连续块。空档期间没有任何采样点，若还拿两端电流做梯形插值，
     * 等于凭空补了一段电流；ΔCC 也不能跨块取首尾差（那会把空档的电荷算进来）。
     */
    public static List<List<Sample>> splitBlocks(List<Sample> pts, double maxGapS) {
        List<List<Sample>> out = new ArrayList<>();
        if (pts == null || pts.isEmpty()) return out;
        List<Sample> cur = new ArrayList<>();
        cur.add(pts.get(0));
        for (int i = 1; i < pts.size(); i++) {
            Sample a = pts.get(i - 1), b = pts.get(i);
            if (b.t - a.t > maxGapS) {
                out.add(cur);
                cur = new ArrayList<>();
            }
            cur.add(b);
        }
        out.add(cur);
        return out;
    }

    /** 梯形积分，单位 mAh。mA·s / 3600 = mAh。 */
    public static double integrate(List<Sample> block) {
        if (block == null || block.size() < 2) return 0;
        double q = 0;
        for (int i = 1; i < block.size(); i++) {
            Sample a = block.get(i - 1), b = block.get(i);
            double dt = b.t - a.t;
            if (dt > 0) q += (a.currentMa + b.currentMa) / 2.0 * dt / 3600.0;
        }
        return Math.abs(q);
    }

    /**
     * 按目标时间间隔抽稀：每过 stepS 取一个最接近的点，首尾必留。
     * 用真实时间戳挑点，不能简单按「每 N 个取一个」——采样间隔会抖。
     */
    public static List<Sample> decimate(List<Sample> block, double stepS) {
        List<Sample> out = new ArrayList<>();
        if (block == null || block.isEmpty()) return out;
        out.add(block.get(0));
        double next = block.get(0).t + stepS;
        for (int i = 1; i < block.size() - 1; i++) {
            if (block.get(i).t >= next - 1e-9) {
                out.add(block.get(i));
                next = block.get(i).t + stepS;
            }
        }
        if (block.size() > 1) out.add(block.get(block.size() - 1));
        return out;
    }

    /** 各块 |ΔCC| 之和。库仑计是硬件累计量，块内首尾差不受采样率影响。 */
    public static double counterOf(List<List<Sample>> blocks) {
        double sum = 0;
        for (List<Sample> b : blocks) {
            if (b.size() < 2) continue;
            sum += Math.abs(b.get(b.size() - 1).counterMah - b.get(0).counterMah);
        }
        return sum;
    }

    public static double integrateAt(List<List<Sample>> blocks, double stepS) {
        double sum = 0;
        for (List<Sample> b : blocks) {
            List<Sample> d = stepS <= 0 ? b : decimate(b, stepS);
            sum += integrate(d);
        }
        return sum;
    }

    /** 每 WINDOW_S 一个窗口，在连续块内部切，窗口不跨空档。 */
    public static List<Window> buildWindows(List<List<Sample>> blocks) {
        List<Window> out = new ArrayList<>();
        for (List<Sample> b : blocks) {
            if (b.size() < 3) continue;
            int from = 0;
            while (from < b.size() - 1) {
                int to = from + 1;
                while (to < b.size() && b.get(to).t - b.get(from).t < WINDOW_S) to++;
                if (to - from < 3) break;
                List<Sample> seg = b.subList(from, to);
                double dcc = Math.abs(seg.get(seg.size() - 1).counterMah
                        - seg.get(0).counterMah);
                if (dcc >= WINDOW_MIN_MAH) {
                    Window w = new Window();
                    w.tStart = seg.get(0).t;
                    w.integralMah = integrate(seg);
                    w.counterMah = dcc;
                    w.k = w.integralMah / dcc;
                    double ci = 0, ct = 0;
                    for (Sample s : seg) { ci += Math.abs(s.currentMa); ct += s.tempC; }
                    w.avgCurrentMa = ci / seg.size();
                    w.avgTempC = ct / seg.size();
                    out.add(w);
                }
                if (to >= b.size()) break;
                from = to - 1;   // 相邻窗口重叠一个点，别丢边界
            }
        }
        return out;
    }

    public static Report analyze(List<Sample> pts, double baseIntervalS) {
        Report r = new Report();
        if (pts == null || pts.size() < 10) {
            r.note = "采样点太少，至少要 10 个";
            return r;
        }
        double gap = Math.max(MIN_GAP_S, baseIntervalS * 3);
        List<List<Sample>> all = splitBlocks(pts, gap);
        List<List<Sample>> usable = new ArrayList<>();
        for (List<Sample> b : all) {
            if (b.size() >= 3 && b.get(b.size() - 1).t - b.get(0).t >= WINDOW_S) {
                usable.add(b);
            }
        }
        r.blocks = all.size();
        r.gaps = all.size() - 1;
        if (usable.isEmpty()) {
            r.note = "没有一段连续采样够 2 分钟，屏幕可能被熄过或页面切到后台了";
            return r;
        }
        double covered = 0;
        for (List<Sample> b : usable) covered += b.get(b.size() - 1).t - b.get(0).t;
        r.coveredS = covered;
        for (List<Sample> b : usable) r.points += b.size();

        r.counterMah = counterOf(usable);
        if (r.counterMah < WINDOW_MIN_MAH) {
            r.note = "库仑计几乎没变，这一段的电荷量太小，对账没有意义";
            return r;
        }

        r.integralMah = integrateAt(usable, 0);
        double minQ = r.integralMah, maxQ = r.integralMah;
        // 第一行是原始采样，后面才是抽稀出来的尺度
        Scale raw = new Scale();
        raw.stepS = baseIntervalS;
        raw.integralMah = r.integralMah;
        raw.errPct = (r.integralMah - r.counterMah) / r.counterMah * 100.0;
        raw.points = r.points;
        r.scales.add(raw);
        for (double step : SCALES_S) {
            // 比原始间隔还细的尺度没意义；等于原始间隔的已经有了（第一行），跳过
            if (step <= baseIntervalS + 1e-9) continue;
            Scale sc = new Scale();
            sc.stepS = step;
            sc.integralMah = integrateAt(usable, step);
            sc.errPct = (sc.integralMah - r.counterMah) / r.counterMah * 100.0;
            int n = 0;
            for (List<Sample> b : usable) n += decimate(b, step).size();
            sc.points = n;
            r.scales.add(sc);
            minQ = Math.min(minQ, sc.integralMah);
            maxQ = Math.max(maxQ, sc.integralMah);
        }
        r.spreadPct = maxQ > 0 ? (maxQ - minQ) / maxQ * 100.0 : 0;
        r.converged = r.spreadPct <= CONVERGE_PCT;

        r.windows.addAll(buildWindows(usable));
        if (!r.windows.isEmpty()) {
            double sum = 0;
            r.kMin = Double.MAX_VALUE;
            for (Window w : r.windows) {
                sum += w.k;
                r.kMin = Math.min(r.kMin, w.k);
                r.kMax = Math.max(r.kMax, w.k);
            }
            r.kMean = sum / r.windows.size();
            double sq = 0;
            for (Window w : r.windows) sq += (w.k - r.kMean) * (w.k - r.kMean);
            r.kStd = Math.sqrt(sq / r.windows.size());
            r.kCvPct = r.kMean > 0 ? r.kStd / r.kMean * 100.0 : 0;
        }
        r.calibratable = r.windows.size() >= 3
                && r.kCvPct <= STABLE_CV_PCT && r.converged;
        r.calibration = r.kMean > 0 ? 1.0 / r.kMean : 0;
        r.valid = true;
        r.note = verdict(r);
        return r;
    }

    private static String verdict(Report r) {
        if (!r.converged) {
            return String.format(Locale.US,
                    "跨尺度积分差 %.1f%%，还没收敛：采样间隔仍在影响结果，先加密采样再说。",
                    r.spreadPct);
        }
        if (r.windows.size() < 3) {
            return String.format(Locale.US,
                    "积分已跨尺度收敛（差 %.1f%%），但有效窗口只有 %d 个，"
                            + "还看不出比值稳不稳，建议跑满 10 分钟。",
                    r.spreadPct, r.windows.size());
        }
        if (r.calibratable) {
            return String.format(Locale.US,
                    "积分已收敛（跨尺度差 %.1f%%），且 %d 个窗口的 ∫I/ΔCC 稳定在 %.3f±%.3f"
                            + "（变异系数 %.1f%%）。说明电流读数是固定比例偏差，"
                            + "乘 %.3f 就能与库仑计对齐——这台机器、这一段充电里可标定。",
                    r.spreadPct, r.windows.size(), r.kMean, r.kStd, r.kCvPct,
                    r.calibration);
        }
        return String.format(Locale.US,
                "积分已收敛（跨尺度差 %.1f%%），但 ∫I/ΔCC 在 %.3f~%.3f 之间漂"
                        + "（变异系数 %.1f%%）。偏差随工况变，不能靠单一系数标定。",
                r.spreadPct, r.kMin, r.kMax, r.kCvPct);
    }

    /** 给 UI 用的一行摘要。 */
    public static String headline(Report r) {
        if (!r.valid) return r.note;
        return String.format(Locale.US,
                "∫I %.0f mAh  /  ΔCC %.0f mAh  =  %.3f",
                r.integralMah, r.counterMah,
                r.counterMah > 0 ? r.integralMah / r.counterMah : 0);
    }

    /** 排序备用：把尺度按间隔升序（SCALES_S 本来就有序，留着给调用方）。 */
    public static List<Double> scalesList() {
        List<Double> out = new ArrayList<>();
        for (double d : SCALES_S) out.add(d);
        Collections.sort(out);
        return out;
    }
}
