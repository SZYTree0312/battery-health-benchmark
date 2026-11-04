package com.tree0312.bhbench;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 把每次测量的曲线质量攒起来，供下一次参考。
 *
 * 两个量值得记：
 *
 *   R²    拟合直线对实测 SOC 的解释力。R² 高 = 这段时间掉电确实接近匀速，
 *         曲线平滑可信；R² 低 = 使用强度忽轻忽重（或者采样被 Doze 拉出长空档），
 *         这种记录不该拿来预测剩余时间。
 *   耗电率 %/h  这次平均每小时掉几个点。攒够几次就有了「这台机器、这个人的
 *         典型强度」，下次测出来的数可以跟自己的历史比，而不是跟厂商的宣传比。
 *
 * 只留最近 12 次：手机用久了使用习惯会变，三年前的数据没参考价值。
 */
public final class TuningStore {

    private TuningStore() {}

    private static final String KEY_R2 = "tune_r2";
    private static final String KEY_RATE = "tune_rate";
    private static final int KEEP = 12;

    public static class Stats {
        public double medianR2 = -1;
        public double medianRate = -1;   // 放电：正数 = 每小时掉几个点
        public int count;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("bhbench", Context.MODE_PRIVATE);
    }

    /**
     * 记一次。r2 传 NaN 或负数表示这条曲线算不出拟合，不记；
     * rate 传 NaN 表示不参与耗电率统计（比如充电段）。
     */
    public static void record(Context c, double r2, double rate) {
        SharedPreferences p = prefs(c);
        if (!Double.isNaN(r2) && r2 >= 0 && r2 <= 1) {
            p.edit().putString(KEY_R2, push(p.getString(KEY_R2, ""), r2)).apply();
        }
        if (!Double.isNaN(rate) && rate > 0 && rate < 100) {
            p.edit().putString(KEY_RATE, push(p.getString(KEY_RATE, ""), rate)).apply();
        }
    }

    public static Stats stats(Context c) {
        SharedPreferences p = prefs(c);
        Stats s = new Stats();
        List<Double> r2 = parse(p.getString(KEY_R2, ""));
        List<Double> rate = parse(p.getString(KEY_RATE, ""));
        s.count = Math.max(r2.size(), rate.size());
        s.medianR2 = median(r2);
        s.medianRate = median(rate);
        return s;
    }

    /** 给结果页的一句话，没历史就返回 null，别拿空话凑数。 */
    public static String comment(Context c, double thisR2, double thisRate) {
        Stats s = stats(c);
        if (s.count < 2) return null;

        StringBuilder sb = new StringBuilder();
        if (thisR2 >= 0 && s.medianR2 > 0) {
            double d = (thisR2 - s.medianR2) * 100;
            sb.append(String.format(Locale.US,
                    "曲线贴合度 %.3f，你之前 %d 次的中位数是 %.3f（%+.1f 个百分点）",
                    thisR2, s.count, s.medianR2, d));
        }
        if (thisRate > 0 && s.medianRate > 0) {
            if (sb.length() > 0) sb.append("；");
            double pct = (thisRate - s.medianRate) / s.medianRate * 100;
            sb.append(String.format(Locale.US,
                    "这次 %.1f %%/时，你自己历史平均 %.1f %%/时（快 %.0f%%）",
                    thisRate, s.medianRate, Math.abs(pct)));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ------------------------------------------------------------ 内部

    private static String push(String csv, double v) {
        List<Double> list = parse(csv);
        list.add(v);
        while (list.size() > KEEP) list.remove(0);   // 只留最近的
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "%.4f", list.get(i)));
        }
        return sb.toString();
    }

    private static List<Double> parse(String csv) {
        List<Double> out = new ArrayList<>();
        if (csv == null || csv.isEmpty()) return out;
        for (String part : csv.split(",")) {
            try {
                out.add(Double.parseDouble(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static double median(List<Double> v) {
        if (v.isEmpty()) return -1;
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }
}
