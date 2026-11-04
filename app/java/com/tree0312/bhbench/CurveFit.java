package com.tree0312.bhbench;

import java.util.ArrayList;
import java.util.List;

/**
 * SOC 曲线的拟合与反推。
 *
 * 纯计算，不碰 UI、不读文件 —— 输入一段已经切好的采样点，输出几个数。
 * PC 端 analyzer/curve.py 是同一套公式，两边算出的斜率和 R² 必须一致，
 * 否则手机上看到的图和电脑上验证的图就打架了。
 *
 * 三个量：
 *   fitLine()        实测 SOC 的最小二乘直线 —— 这就是「按使用时长匀速掉电」的假设线
 *   counterSoc()     由库仑计反推的 SOC —— 检验系统那个百分比有没有平滑/滞后
 *   maxDeviation()   两条曲线的最大偏差，用来判断系统 SOC 可不可信
 */
public final class CurveFit {

    private CurveFit() {}

    /** 最小二乘直线 y = a + b·x，附带 R²。 */
    public static class Line {
        public double slope = 0;      // y 每个 x 单位的变化量
        public double intercept = 0;
        public double r2 = 0;
        public boolean valid = false;
        /** 这段回归覆盖的 x 跨度；太短的回归没有意义。 */
        public double span = 0;
        /** 分段拟合时用到的连续段数。1 表示全程没有空档。 */
        public int segmentCount = 0;
    }

    /**
     * 每个采样点代表多长时间。
     *
     * 这一步不能省。Doze 会把 7 小时的采样压成两三个点，按点等权做回归时
     * 那 7 小时只算了两三票，而密集采样的活跃期每小时好几票 —— 拟合出来的
     * 斜率会被活跃期带跑。真机数据：实际端点斜率 5.04 %/h，等权回归给 3.51，
     * 时间加权给 3.99。回归是对时间积分，权重就该是"这个点覆盖了多久"。
     */
    public static double[] timeWeights(double[] xs) {
        int n = xs.length;
        if (n == 0) return new double[0];
        double[] w = new double[n];
        if (n == 1) { w[0] = 1; return w; }
        for (int i = 0; i < n; i++) {
            if (i == 0) w[i] = (xs[1] - xs[0]) / 2.0;
            else if (i == n - 1) w[i] = (xs[n - 1] - xs[n - 2]) / 2.0;
            else w[i] = (xs[i + 1] - xs[i - 1]) / 2.0;
        }
        double sum = 0;
        for (double v : w) sum += v;
        if (sum <= 0) {                       // 全是同一时刻，退回等权
            for (int i = 0; i < n; i++) w[i] = 1;
        }
        return w;
    }

    /** 等权回归。等距采样时与加权版结果相同。 */
    public static Line fit(double[] xs, double[] ys) {
        return fit(xs, ys, null);
    }

    /** 加权最小二乘直线 y = a + b·x，附带 R²。weights 为 null 即等权。 */
    public static Line fit(double[] xs, double[] ys, double[] weights) {
        Line out = new Line();
        int n = Math.min(xs.length, ys.length);
        if (n < 3) return out;

        double sw = 0;
        for (int i = 0; i < n; i++) {
            double wi = (weights != null && i < weights.length) ? weights[i] : 1.0;
            sw += wi;
        }
        if (sw <= 0) return out;

        double sxw = 0, syw = 0;
        for (int i = 0; i < n; i++) {
            double wi = (weights != null && i < weights.length) ? weights[i] : 1.0;
            sxw += wi * xs[i];
            syw += wi * ys[i];
        }
        double mx = sxw / sw, my = syw / sw;

        double sxx = 0, sxy = 0;
        for (int i = 0; i < n; i++) {
            double wi = (weights != null && i < weights.length) ? weights[i] : 1.0;
            double dx = xs[i] - mx;
            sxx += wi * dx * dx;
            sxy += wi * dx * (ys[i] - my);
        }
        if (sxx <= 1e-12) return out;   // 所有点时间相同，没有斜率

        double b = sxy / sxx;
        double a = my - b * mx;

        double ssRes = 0, ssTot = 0;
        for (int i = 0; i < n; i++) {
            double wi = (weights != null && i < weights.length) ? weights[i] : 1.0;
            double pred = a + b * xs[i];
            double e = ys[i] - pred;
            ssRes += wi * e * e;
            double d = ys[i] - my;
            ssTot += wi * d * d;
        }
        // SS_tot 为 0 说明 SOC 全程没变 —— R² 无定义，按 1 处理成「拟合完美」
        double r2 = ssTot <= 1e-12 ? (ssRes <= 1e-12 ? 1.0 : 0.0) : 1.0 - ssRes / ssTot;

        out.slope = b;
        out.intercept = a;
        out.r2 = r2;
        out.span = xs[n - 1] - xs[0];
        out.valid = true;
        return out;
    }

    /** 时间轴（小时，从 0 开始）。 */
    public static double[] hours(List<Analyzer.Row> seg) {
        double[] out = new double[seg.size()];
        double t0 = seg.get(0).t;
        for (int i = 0; i < seg.size(); i++) out[i] = (seg.get(i).t - t0) / 3600.0;
        return out;
    }

    // ------------------------------------------------------------ 采样抖动

    /**
     * 采样间隔的分布。
     *
     * 光看「最长空档」不够 —— 前台服务在国产 ROM 上也常被掐，产物是一串
     * 几十秒到几分钟的小空档，最长空档看不出来，但曲线已经被打得稀碎。
     * 所以要看分布：中位数说明「正常该多久一个点」，P90 说明「最坏能拖到多久」，
     * 两者之比就是抖动程度。
     */
    public static class GapStats {
        public double medianS = 0;
        /**
         * P99 而不是 P90：绝大多数间隔都正常时，P90 就等于中位数，
         * 抖动完全看不见。真机数据 90% 的间隔是 5 秒，P90 也是 5 秒，
         * 但实际有 6 次被打断到几分钟以上 —— P99 才看得见。
         */
        public double p99S = 0;
        public double maxS = 0;
        /** 超过 SPLIT_GAP_S 的次数，也就是「被系统打断」的次数。 */
        public int breaks = 0;
        /** (P99 − 中位) / 中位 ×100。大于 100% 说明抖动已经很严重。 */
        public double jitterPct = 0;
    }

    /** 间隔超过这个值就认为采样被系统打断了，切段从这里断开。 */
    public static final double SPLIT_GAP_S = 60;

    public static GapStats gaps(long[] gapSec) {
        GapStats g = new GapStats();
        if (gapSec == null || gapSec.length == 0) return g;

        long[] sorted = gapSec.clone();
        java.util.Arrays.sort(sorted);
        g.medianS = sorted[sorted.length / 2];
        g.p99S = sorted[(int) Math.min(sorted.length - 1L,
                Math.floor(sorted.length * 0.99))];
        g.maxS = sorted[sorted.length - 1];
        for (long v : sorted) if (v > SPLIT_GAP_S) g.breaks++;
        if (g.medianS > 0) {
            g.jitterPct = (g.p99S - g.medianS) / g.medianS * 100.0;
        }
        return g;
    }

    /** 实测 SOC 序列。 */
    public static double[] soc(List<Analyzer.Row> seg) {
        double[] out = new double[seg.size()];
        for (int i = 0; i < seg.size(); i++) out[i] = seg.get(i).soc;
        return out;
    }

    /**
     * 由库仑计反推的 SOC。
     *
     * 把充电/放电的电荷变化按标称容量折算成百分点，再叠到起点 SOC 上：
     *   放电：soc(t) = soc0 - (counter0 - counter(t)) / design × 100
     *   充电：soc(t) = soc0 + (counter(t) - counter0) / design × 100
     *
     * 实测 SOC 是厂商算法平滑过的，库仑计是硬件累加的真实电荷 —— 两条线之间的
     * 距离就是「系统的百分比和真实电荷差多远」。差得越多，说明厂商在藏东西
     * （低电量时虚高、或者满电段不线性）。
     *
     * counter 全程读不到（=0）时返回 null，调用方跳过这条线。
     */
    public static double[] counterSoc(List<Analyzer.Row> seg, double designMah, boolean chargeMode) {
        if (seg == null || seg.size() < 2 || designMah <= 0) return null;

        int first = -1;
        for (int i = 0; i < seg.size(); i++) {
            if (seg.get(i).counter > 0) { first = i; break; }
        }
        if (first < 0) return null;

        double c0 = seg.get(first).counter;
        double soc0 = seg.get(first).soc;

        double[] out = new double[seg.size()];
        double last = soc0;
        for (int i = 0; i < seg.size(); i++) {
            double c = seg.get(i).counter;
            if (c > 0) {
                // 充电：counter 上升 → SOC 上升；放电：counter 下降 → SOC 下降
                double delta = chargeMode ? (c - c0) : (c0 - c);
                last = soc0 + delta / designMah * 100.0;
            }
            // 前几个点没有库仑计时沿用起点值，避免曲线从 0 跳上来
            out[i] = i < first ? soc0 : last;
        }
        return out;
    }

    /**
     * 带抖动处理的整体拟合。
     *
     * 这里试过「分段各自拟合再按段时长加权平均」，真机数据上直接崩：
     * slope 从 3.99 变成 -11.1 %/h，R² 从 0.78 变成 -1.73。原因是段内
     * 5 秒一个点、SOC 只有 1% 分辨率，一段几百秒里 SOC 纹丝不动，段内
     * 斜率全是量化噪声，按段加权把它放大成了主信号。
     *
     * 所以正确的处理是：斜率仍用整段的时间加权拟合（这是对非均匀采样的
     * 正确处理，见 timeWeights），抖动的影响单独量化、单独展示（见 gaps）。
     * 别指望用算法「消除」采样抖动 —— 消除不了，只能如实报告。
     *
     * 段数只用于告诉用户「这段数据被系统打断了几次」。
     */
    public static Line robustFit(double[] xs, double[] ys) {
        Line base = fit(xs, ys, timeWeights(xs));
        if (base.valid) base.segmentCount = countSegments(xs);
        return base;
    }

    /** 按 SPLIT_GAP_S 切分，返回连续采样段的数量。 */
    public static int countSegments(double[] xs) {
        if (xs.length == 0) return 0;
        int n = 1;
        for (int i = 1; i < xs.length; i++) {
            if (xs[i] - xs[i - 1] >= SPLIT_GAP_S / 3600.0) n++;
        }
        return n;
    }

    /** 两条等长序列的最大绝对偏差。 */
    public static double maxDeviation(double[] a, double[] b) {
        if (a == null || b == null) return -1;
        int n = Math.min(a.length, b.length);
        double m = 0;
        for (int i = 0; i < n; i++) m = Math.max(m, Math.abs(a[i] - b[i]));
        return m;
    }

    /** 拟合线在每个采样点上的取值，用来画参考线。 */
    public static double[] eval(double[] xs, Line line) {
        double[] out = new double[xs.length];
        for (int i = 0; i < xs.length; i++) out[i] = line.intercept + line.slope * xs[i];
        return out;
    }

    /**
     * 滑动窗口内的局部斜率极值，回答「哪一段掉得最快」。
     * 返回 {最快掉电的 %/h, 那一段起点的小时数}；点数太少返回 null。
     */
    public static double[] steepestWindow(double[] xs, double[] ys, int window) {
        if (xs.length < window || window < 3) return null;
        double bestSlope = 0, bestAt = 0;
        boolean found = false;
        for (int i = 0; i + window <= xs.length; i++) {
            Line l = new Line();
            l = fitRange(xs, ys, i, i + window);
            if (!l.valid) continue;
            if (!found || l.slope < bestSlope) {   // 放电时掉得快 = 斜率为负且更负
                bestSlope = l.slope;
                bestAt = xs[i];
                found = true;
            }
        }
        return found ? new double[]{bestSlope, bestAt} : null;
    }

    private static Line fitRange(double[] xs, double[] ys, int from, int to) {
        double[] sx = new double[to - from];
        double[] sy = new double[to - from];
        System.arraycopy(xs, from, sx, 0, sx.length);
        System.arraycopy(ys, from, sy, 0, sy.length);
        return fit(sx, sy, timeWeights(sx));
    }
}
