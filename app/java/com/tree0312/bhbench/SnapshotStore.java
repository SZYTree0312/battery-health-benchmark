package com.tree0312.bhbench;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 满电快照。
 *
 * 每次充到 100% 自动记一条库仑计读数。这是三种测量方法里成本最低的一种：
 * 用户什么都不用做，几个月攒下来就是一条衰减曲线。
 *
 * 单条快照的意义有限 —— 它可能是真实容量，也可能只是 BMS 的标定常数。
 * 要靠 analyzer/compare.py 和放电/充电测量的结果对比，才能判断可信度。
 */
public class SnapshotStore {

    private static final String FILE = "snapshots.csv";
    private static final String PREF_DAY = "last_snapshot_day";

    /** 充满时调用。同一天只记一条，避免重复写。 */
    public static void maybeRecord(Context ctx, int soc, long counterUah, double tempC) {
        if (soc < 99 || counterUah <= 0) return;

        SharedPreferences prefs = ctx.getSharedPreferences("bhbench", Context.MODE_PRIVATE);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (today.equals(prefs.getString(PREF_DAY, ""))) return;

        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        if (dir == null) return;

        File file = new File(dir, FILE);
        boolean needHeader = !file.exists();
        try (FileWriter w = new FileWriter(file, true)) {
            if (needHeader) w.write("iso_time,counter_mah,soc_pct,temp_c\n");
            String iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                    .format(new Date());
            w.write(String.format(Locale.US, "%s,%.0f,%d,%.1f%n",
                    iso, counterUah / 1000.0, soc, tempC));
            prefs.edit().putString(PREF_DAY, today).apply();
        } catch (IOException ignored) {
            // 写不进去不影响测量主流程
        }
    }

    /** 最近的几条快照，新的在前。每条格式："11-03   4605 mAh"。 */
    public static List<String> recent(Context ctx, int limit) {
        List<String> out = new ArrayList<>();
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        if (dir == null) return out;

        File file = new File(dir, FILE);
        if (!file.isFile()) return out;

        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (first) { first = false; continue; }
                if (!line.trim().isEmpty()) lines.add(line.trim());
            }
        } catch (IOException e) {
            return out;
        }

        for (int i = lines.size() - 1; i >= 0 && out.size() < limit; i--) {
            String[] p = lines.get(i).split(",");
            if (p.length < 2) continue;
            String date = p[0].length() >= 10 ? p[0].substring(5, 10) : p[0];
            try {
                double mah = Double.parseDouble(p[1]);
                out.add(String.format(Locale.US, "%s   %.0f mAh", date, mah));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
}
