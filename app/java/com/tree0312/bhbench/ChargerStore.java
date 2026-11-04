package com.tree0312.bhbench;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 充电器体检记录。
 *
 * 目的很具体：**同一台手机换着充电器充，看谁真的喂得动。**
 * 标称 120W 的充电器给别的手机可能只跑 25W，反过来也一样 ——
 * 光看充电器外壳上的字没用，得看这台手机实际吃进去多少。
 *
 * 每条记录只存结论（峰值/稳态/档位/名字），不存曲线 —— 曲线在 CSV 里，
 * SharedPreferences 存几千个点是给自己找麻烦。
 * 最多留 12 条，够覆盖手边的充电器了。
 */
public final class ChargerStore {

    private ChargerStore() {}

    private static final String KEY = "charger_probes";
    private static final int KEEP = 12;

    public static class Entry {
        public long ts;
        public String name = "未命名";
        public double peakW;
        public double steadyW;
        public String tier = "";
        public long durationS;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("bhbench", Context.MODE_PRIVATE);
    }

    public static List<Entry> load(Context c) {
        List<Entry> out = new ArrayList<>();
        String raw = prefs(c).getString(KEY, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String rec : raw.split(";;")) {
            String[] f = rec.split("\\|", -1);
            if (f.length < 6) continue;
            try {
                Entry e = new Entry();
                e.ts = Long.parseLong(f[0]);
                e.name = f[1].isEmpty() ? "未命名" : f[1];
                e.peakW = Double.parseDouble(f[2]);
                e.steadyW = Double.parseDouble(f[3]);
                e.tier = f[4];
                e.durationS = Long.parseLong(f[5]);
                out.add(e);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static void add(Context c, Entry e) {
        List<Entry> all = load(c);
        all.add(0, e);                       // 最新的在最前
        while (all.size() > KEEP) all.remove(all.size() - 1);

        StringBuilder sb = new StringBuilder();
        for (Entry x : all) {
            if (sb.length() > 0) sb.append(";;");
            sb.append(x.ts).append('|')
              .append(x.name.replace("|", " ").replace(";;", " ")).append('|')
              .append(String.format(Locale.US, "%.2f", x.peakW)).append('|')
              .append(String.format(Locale.US, "%.2f", x.steadyW)).append('|')
              .append(x.tier.replace("|", " ")).append('|')
              .append(x.durationS);
        }
        prefs(c).edit().putString(KEY, sb.toString()).apply();
    }

    public static void remove(Context c, long ts) {
        List<Entry> all = load(c);
        StringBuilder sb = new StringBuilder();
        for (Entry x : all) {
            if (x.ts == ts) continue;
            if (sb.length() > 0) sb.append(";;");
            sb.append(x.ts).append('|')
              .append(x.name).append('|')
              .append(String.format(Locale.US, "%.2f", x.peakW)).append('|')
              .append(String.format(Locale.US, "%.2f", x.steadyW)).append('|')
              .append(x.tier).append('|')
              .append(x.durationS);
        }
        prefs(c).edit().putString(KEY, sb.toString()).apply();
    }

    /** 和上一次不同充电器的对比，用来说明"这次和上次差多少"。 */
    public static String compareWithPrevious(Context c, Entry cur) {
        List<Entry> all = load(c);
        Entry prev = null;
        for (Entry e : all) {
            if (e.ts == cur.ts) continue;
            prev = e;
            break;
        }
        if (prev == null) return null;

        double dPeak = cur.peakW - prev.peakW;
        double dSteady = cur.steadyW - prev.steadyW;
        return String.format(Locale.US,
                "对比上次「%s」：峰值 %+.1f W（%.1f → %.1f），稳态 %+.1f W",
                prev.name, dPeak, prev.peakW, cur.peakW, dSteady);
    }
}
