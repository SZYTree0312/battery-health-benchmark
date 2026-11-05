package com.tree0312.bhbench;

import java.util.ArrayList;
import java.util.List;

/**
 * 充电器体检：高频采样充电过程，算出这台充电器「给这台手机」真实喂进去多少。
 *
 * ⚠️ 先说清楚物理边界，这个边界决定了这个工具能说什么、不能说什么：
 *
 *   手机能读到的是**电池侧**的电压电流（BatteryManager 的 V/I），
 *   读不到 USB 输入侧 VBUS/IBUS —— sysfs 被 SELinux 封了，App 没 root 进不去。
 *   中间还隔着充电管理芯片（buck/charge pump），效率 85–92%。
 *
 *   所以「峰值功率」是电池吃进去的功率，不是充电器标称的输出功率。
 *   好在评测充电器要的正是这个：标称 120W 的充电器，能不能让这台手机
 *   真的跑到极限、能跑多久、热了掉不掉队 —— 全在这条曲线上。
 *
 *   充电协议同理：Android 没有公开 API 能读出 PD/QC/SCP/VOOC。
 *   只能从功率台阶和稳态值反推档位区间，结论必须带置信度。
 *
 * 参考实现：analyzer/probe.py，两边公式必须一致。
 */
public final class ChargerProbe {

    private ChargerProbe() {}

    /** 采样间隔：200 ms。90 秒 450 个点，够看清协商台阶，又不至于压垮 binder。 */
    public static final long INTERVAL_MS = 200L;
    /** 默认体检时长。太短抓不到热衰减，太长用户等不住。 */
    public static final long DURATION_MS = 90_000L;

    /** 判为「功率台阶」的最小绝对跳变（W）。 */
    static final double STEP_MIN_W = 2.0;
    /** 判为「功率台阶」的最小相对跳变。 */
    static final double STEP_MIN_REL = 0.15;
    /** 同一次跃变的合并窗口，避免一个台阶被算成好几次。 */
    static final long STEP_MERGE_MS = 1500L;

    public static class Point {
        public long tMs;
        public double vbat;     // V
        public double ibat;     // A，充电为正
        public double tempC;
        public int soc;
        public String status = "";
        /** 是否插着充电器（EXTRA_PLUGGED > 0）。没插电时 pWatt 必须为 0。 */
        public boolean plugged = false;
        public double pWatt;    // vbat × ibat，且只在充电时非零
    }

    public static class Result {
        public boolean valid = false;
        public String note = "";
        public int points = 0;
        public long durationMs = 0;

        /** 峰值功率与它出现的时间。 */
        public double peakW = 0;
        public long peakAtMs = 0;
        /** 稳态功率：后半段中位数。避开起始爬升，代表"跑起来之后能维持多少"。 */
        public double steadyW = 0;
        /** 稳态波动：(P95-P5)/中位数 ×100，衡量这颗充电器输出稳不稳。 */
        public double stabilityPct = 0;
        /** 检测到的功率台阶（协商动作）。 */
        public int steps = 0;
        public final List<double[]> stepEvents = new ArrayList<>();  // {t_s, deltaW}
        /** 充进去的能量（Wh）。 */
        public double energyWh = 0;
        public double avgTemp = 0;
        public double peakTemp = 0;
        /** 峰值出现在前 10% 还是后段 —— 后段才说明"能维持"，前段只是瞬时冲击。 */
        public boolean peakEarly = false;
    }

    /**
     * 一个点能不能算「充电器在给手机喂电」。
     *
     * 两个条件都要：插着充电器（EXTRA_PLUGGED），且状态是 charging / full。
     * 只判电流方向不行 —— CURRENT_NOW 的正负号各厂商实现不统一，有的正号
     * 表示放电、有的反过来；只判 status 也不行 —— 有些 ROM 在拔线后的
     * 一两秒内还留着 charging。EXTRA_PLUGGED 是内核直接反映插拔的，最硬。
     */
    public static boolean isChargePoint(Point p) {
        if (p == null || !p.plugged) return false;
        String st = p.status == null ? "" : p.status.toLowerCase(java.util.Locale.US);
        return st.startsWith("charg") || st.startsWith("full");
    }

    public static Result analyze(List<Point> pts) {
        Result r = new Result();
        if (pts == null || pts.size() < 5) {
            r.note = "采样点太少，至少要有 5 个";
            return r;
        }

        // 先把非充电点摘掉。v0.5.3 之前这里直接拿全部点算，而 pWatt 是
        // abs(V×I) —— 放电时电流为负，绝对值一取就变成正的「充电功率」，
        // 于是没插充电器跑 90 秒也能出一份漂亮的峰值 / 稳态 / 充入 Wh，
        // 甚至能存进充电器档案参与对比。数据层必须自己挡住，不能只靠 UI。
        List<Point> use = new ArrayList<>();
        for (Point p : pts) if (isChargePoint(p)) use.add(p);
        r.points = use.size();
        if (use.size() < 5) {
            r.note = "这段时间没有插充电器，测不出充电器功率";
            return r;
        }
        if (use.size() < pts.size() * 0.8) {
            r.note = "中途断开过充电器，这次不作数，插好再测";
            return r;
        }
        r.durationMs = use.get(use.size() - 1).tMs - use.get(0).tMs;
        if (r.durationMs < 5000) {
            r.note = "有效充电时长不足 5 秒，看不到协商和稳态";
            return r;
        }

        // ---- 峰值
        double peak = 0;
        long peakAt = 0;
        double tempSum = 0, tempPeak = -100;
        for (Point p : use) {
            if (p.pWatt > peak) { peak = p.pWatt; peakAt = p.tMs - use.get(0).tMs; }
            tempSum += p.tempC;
            tempPeak = Math.max(tempPeak, p.tempC);
        }
        r.peakW = peak;
        r.peakAtMs = peakAt;
        r.avgTemp = tempSum / use.size();
        r.peakTemp = tempPeak;
        r.peakEarly = peakAt < r.durationMs * 0.1;

        // ---- 稳态：后半段中位数
        int from = use.size() / 2;
        List<Double> tail = new ArrayList<>();
        for (int i = from; i < use.size(); i++) tail.add(use.get(i).pWatt);
        java.util.Collections.sort(tail);
        double med = tail.size() % 2 == 1
                ? tail.get(tail.size() / 2)
                : (tail.get(tail.size() / 2 - 1) + tail.get(tail.size() / 2)) / 2.0;
        r.steadyW = med;
        if (med > 0.5) {
            int n = tail.size();
            double p5 = tail.get(Math.max(0, (int) Math.floor(n * 0.05)));
            double p95 = tail.get(Math.min(n - 1, (int) Math.ceil(n * 0.95) - 1));
            r.stabilityPct = (p95 - p5) / med * 100.0;
        }

        // ---- 能量
        double wh = 0;
        for (int i = 1; i < use.size(); i++) {
            Point a = use.get(i - 1), b = use.get(i);
            double dt = (b.tMs - a.tMs) / 1000.0;
            if (dt <= 0) continue;
            wh += (a.pWatt + b.pWatt) / 2.0 * dt / 3600.0;
        }
        r.energyWh = wh;

        // ---- 台阶
        r.steps = detectSteps(use, r.stepEvents);

        r.valid = r.peakW > 0.5;
        if (!r.valid) r.note = "已经充满或功率太小，测不出充电器能力";
        return r;
    }

    /**
     * 功率台阶检测。
     *
     * 快充协商会发生一次（或几次）明显的功率跃变：PD 从 5V 切到 9V/20V、
     * QC 分档、SCP 进入大电流。这些跃变是协议存在的唯一外部证据。
     *
     * 规则：相邻点 ΔP ≥ max(2W, 15%)，且 1.5 秒内只记一次（取最大 ΔP）。
     */
    private static int detectSteps(List<Point> pts, List<double[]> out) {
        boolean hasPending = false;
        double bestDelta = 0;
        long bestTs = 0;

        for (int i = 1; i < pts.size(); i++) {
            double prev = pts.get(i - 1).pWatt;
            double cur = pts.get(i).pWatt;
            double dp = cur - prev;
            boolean isStep = Math.abs(dp) >= STEP_MIN_W
                    && Math.abs(dp) / Math.max(Math.abs(prev), 1.0) >= STEP_MIN_REL;
            if (!isStep) continue;

            long ts = pts.get(i).tMs - pts.get(0).tMs;
            if (hasPending && ts - bestTs <= STEP_MERGE_MS) {
                // 同一个跃变的后续点，只保留幅度最大的那次
                if (Math.abs(dp) > Math.abs(bestDelta)) {
                    bestDelta = dp;
                    bestTs = ts;
                }
            } else {
                if (hasPending) out.add(new double[]{bestTs / 1000.0, bestDelta});
                bestDelta = dp;
                bestTs = ts;
                hasPending = true;
            }
        }
        if (hasPending) out.add(new double[]{bestTs / 1000.0, bestDelta});
        return out.size();
    }

    /** 检查器：这组点是不是在充电。 */
    public static boolean looksCharging(List<Point> pts) {
        if (pts == null || pts.isEmpty()) return false;
        int charging = 0;
        for (Point p : pts) {
            if (isChargePoint(p)) charging++;
        }
        return charging > pts.size() / 2;
    }
}
