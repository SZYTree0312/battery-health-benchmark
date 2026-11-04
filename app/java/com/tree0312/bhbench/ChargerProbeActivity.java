package com.tree0312.bhbench;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 充电器体检。
 *
 * 电池容量那套测的是「电池还能装多少」，这里测的是「充电器能给多少」。
 * 两件事共用同一套数据源，但关心的量完全不同：那边看电荷总量，这边看功率曲线。
 *
 * 采样 200 ms 一个点、跑 90 秒。为什么要这么密：快充协商是**瞬间**的事，
 * PD 从 5V 切到 9V 就是几十毫秒，5 秒一个点只能看到"结果"，看不到"过程"。
 * 而过程里才有协议的证据。
 */
public class ChargerProbeActivity extends Activity {

    /** 配色跟随系统深浅色。 */
    private Palette pal;

    private BatteryManager bm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final IntentFilter battFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);

    private boolean running = false;
    private long startTs = 0;
    private final List<ChargerProbe.Point> points = new ArrayList<>();
    private int tickSinceRedraw = 0;

    private TextView tvPower;
    private TextView tvDetail;
    private TextView tvHint;
    private TextView tvStatus;
    private PowerChartView chart;
    private LinearLayout resultBox;
    private LinearLayout historyBox;
    private Button btnStart;
    private Button btnStop;

    private ChargerProbe.Result lastResult;
    private SysfsProbe.Info lastSysfs;
    private ProtocolGuess.Guess lastGuess;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (running) {
                collect();
                long elapsed = System.currentTimeMillis() - startTs;
                if (elapsed >= ChargerProbe.DURATION_MS) {
                    stopProbe("体检完成");
                    return;
                }
                if (++tickSinceRedraw >= 5) {   // 每秒重绘一次，200 ms 一次太浪费
                    tickSinceRedraw = 0;
                    refreshLive(elapsed);
                    refreshChart();
                }
                tvStatus.setText(String.format(Locale.US, "%d / %d 秒",
                        elapsed / 1000, ChargerProbe.DURATION_MS / 1000));
                ui.postDelayed(this, ChargerProbe.INTERVAL_MS);
            } else {
                previewOnce();
                ui.postDelayed(this, 1000);
            }
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        pal = Palette.of(this);
        bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        setContentView(buildUi());
        ui.post(loop);
    }

    @Override protected void onPause() {
        super.onPause();
        if (running) stopProbe("退到后台，已停止");
    }

    @Override protected void onDestroy() {
        ui.removeCallbacks(loop);
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int statusBarH() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    // ------------------------------------------------------------ 界面

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(pal.bg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12) + statusBarH(), dp(18), dp(28));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("充电器体检");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(pal.textPrimary);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("测的是这台手机**实际吃进去**多少功率，不是充电器标称多少。"
                .replace("**", ""));
        sub.setTextSize(12);
        sub.setTextColor(pal.textTertiary);
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        // 实时大字
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(pal.surface);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.addView(panel);

        tvPower = new TextView(this);
        tvPower.setText("—");
        tvPower.setTextSize(40);
        tvPower.setTypeface(Typeface.DEFAULT_BOLD);
        tvPower.setTextColor(pal.textPrimary);
        tvPower.setGravity(Gravity.CENTER);
        panel.addView(tvPower);

        tvHint = new TextView(this);
        tvHint.setText("W  电池侧");
        tvHint.setTextSize(12);
        tvHint.setTextColor(pal.textTertiary);
        tvHint.setGravity(Gravity.CENTER);
        panel.addView(tvHint);

        tvDetail = new TextView(this);
        tvDetail.setText("电压 —  电流 —  电量 —");
        tvDetail.setTextSize(13);
        tvDetail.setTextColor(pal.textSecondary);
        tvDetail.setGravity(Gravity.CENTER);
        tvDetail.setPadding(0, dp(10), 0, 0);
        panel.addView(tvDetail);

        tvStatus = new TextView(this);
        tvStatus.setText("");
        tvStatus.setTextSize(12);
        tvStatus.setTextColor(pal.textTertiary);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, dp(6), 0, 0);
        panel.addView(tvStatus);

        chart = new PowerChartView(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        clp.topMargin = dp(10);
        root.addView(chart, clp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(14), 0, dp(10));
        root.addView(row);

        btnStart = new Button(this);
        btnStart.setText(String.format(Locale.US, "开始体检（%d 秒）",
                ChargerProbe.DURATION_MS / 1000));
        btnStart.setOnClickListener(v -> startProbe());
        row.addView(btnStart, new LinearLayout.LayoutParams(0, dp(48), 1f));

        btnStop = new Button(this);
        btnStop.setText("停止");
        btnStop.setEnabled(false);
        btnStop.setOnClickListener(v -> stopProbe("已停止"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.leftMargin = dp(8);
        row.addView(btnStop, lp);

        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultBox);

        historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(historyBox);
        renderHistory();

        return scroll;
    }

    // ------------------------------------------------------------ 采样

    /** 空闲时也显示实时功率 —— 插上充电器就能先看一眼，不用等体检。 */
    private void previewOnce() {
        ChargerProbe.Point p = read();
        if (p == null) return;
        tvPower.setText(String.format(Locale.US, "%.1f", p.pWatt));
        tvDetail.setText(String.format(Locale.US,
                "电压 %.2f V   电流 %.2f A   电量 %d%%   %.1f °C",
                p.vbat, Math.abs(p.ibat), p.soc, p.tempC));
    }

    private void collect() {
        ChargerProbe.Point p = read();
        if (p == null) return;
        p.tMs = p.tMs - startTs;
        points.add(p);
        refreshLive(p.tMs);
    }

    private void refreshLive(long elapsedMs) {
        if (points.isEmpty()) return;
        ChargerProbe.Point p = points.get(points.size() - 1);
        tvPower.setText(String.format(Locale.US, "%.1f", p.pWatt));
        tvDetail.setText(String.format(Locale.US,
                "电压 %.2f V   电流 %.2f A   电量 %d%%   %.1f °C",
                p.vbat, Math.abs(p.ibat), p.soc, p.tempC));
    }

    /** 读一次电池状态。电压电流都来自 BatteryManager，只有电池侧。 */
    private ChargerProbe.Point read() {
        Intent b = registerReceiver(null, battFilter);
        if (b == null) return null;

        int mv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int tempTenth = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250);
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int st = b.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);

        int curUa = bm != null
                ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) : 0;

        ChargerProbe.Point p = new ChargerProbe.Point();
        p.tMs = System.currentTimeMillis();
        p.vbat = mv / 1000.0;
        p.ibat = (curUa == Integer.MIN_VALUE) ? 0 : curUa / 1_000_000.0;
        p.tempC = tempTenth / 10.0;
        p.soc = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;
        p.status = statusName(st);
        p.pWatt = Math.abs(p.vbat * p.ibat);
        return p;
    }

    private void refreshChart() {
        int n = points.size();
        if (n < 2) return;
        double[] ts = new double[n];
        double[] ps = new double[n];
        for (int i = 0; i < n; i++) {
            ts[i] = points.get(i).tMs / 1000.0;
            ps[i] = points.get(i).pWatt;
        }
        chart.setData(ts, ps);
    }

    private static String statusName(int code) {
        switch (code) {
            case BatteryManager.BATTERY_STATUS_CHARGING:     return "charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING:  return "discharging";
            case BatteryManager.BATTERY_STATUS_FULL:         return "full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "not-charging";
            default:                                          return "unknown";
        }
    }

    // ------------------------------------------------------------ 流程

    private void startProbe() {
        if (running) return;
        points.clear();
        tickSinceRedraw = 0;
        startTs = System.currentTimeMillis();
        running = true;
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        resultBox.removeAllViews();
        chart.setData(new double[0], new double[0]);
        tvStatus.setText("0 / " + ChargerProbe.DURATION_MS / 1000 + " 秒");
        lastResult = null;
        lastGuess = null;
        lastSysfs = null;

        // 开始前先读一次 sysfs：插着充电器时才有值
        lastSysfs = SysfsProbe.read();

        ui.removeCallbacks(loop);
        ui.post(loop);
    }

    private void stopProbe(String why) {
        running = false;
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        tvStatus.setText(why);

        if (points.size() < 5) {
            toast("点太少，重测一次");
            return;
        }

        lastResult = ChargerProbe.analyze(points);
        boolean charging = ChargerProbe.looksCharging(points);
        lastGuess = ProtocolGuess.from(lastResult, charging);

        refreshChart();
        saveCsv();
        showResult();
    }

    // ------------------------------------------------------------ 结果

    private void showResult() {
        resultBox.removeAllViews();
        if (lastResult == null || !lastResult.valid) {
            addLine(resultBox, lastResult == null ? "没有数据" : lastResult.note,
                    13, pal.bad, dp(10));
            return;
        }

        ChargerProbe.Result r = lastResult;

        // 峰值 / 稳态
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setPadding(0, dp(6), 0, 0);
        addCard(cards, String.format(Locale.US, "%.1f", r.peakW), "W 峰值", true);
        addCard(cards, String.format(Locale.US, "%.1f", r.steadyW), "W 稳态", true);
        addCard(cards, String.format(Locale.US, "%.1f", r.energyWh), "Wh 充入", false);
        resultBox.addView(cards);

        TextView sub = new TextView(this);
        sub.setText(String.format(Locale.US,
                "峰值出现在第 %.0f 秒 · 稳态波动 %.1f%% · 电池温度 %.1f → %.1f °C",
                r.peakAtMs / 1000.0, r.stabilityPct, r.avgTemp, r.peakTemp));
        sub.setTextSize(12);
        sub.setTextColor(pal.textSecondary);
        sub.setPadding(0, dp(8), 0, dp(4));
        resultBox.addView(sub);

        // 协议推断
        sectionTitle("协议推断", resultBox);
        ProtocolGuess.Guess g = lastGuess;
        addLine(resultBox, g.tier + "　" + g.candidates, 16, pal.textPrimary, 6);
        addLine(resultBox, "置信度：" + g.confidence + "　（输入功率估算 "
                + String.format(Locale.US, "%.1f", g.inputW) + " W）",
                12, pal.textSecondary, 4);
        addLine(resultBox, g.reason, 12, pal.textSecondary, 6);
        if (!g.verdict.isEmpty()) {
            addLine(resultBox, g.verdict, 12, pal.textSecondary, 6);
        }
        String health = ProtocolGuess.healthNote(r);
        if (!health.isEmpty()) {
            addLine(resultBox, health, 12, pal.textSecondary, 6);
        }

        // 直读
        sectionTitle("内核节点直读", resultBox);
        String direct = SysfsProbe.directVerdict(lastSysfs, r);
        if (direct != null) {
            addLine(resultBox, direct, 12, pal.good, 6);
        }
        addLine(resultBox, SysfsProbe.describe(lastSysfs), 11, pal.textTertiary, 6);

        // 说明边界
        TextView caveat = new TextView(this);
        caveat.setText("上面这些是电池侧的电压电流。手机读不到 USB 输入侧的 VBUS/IBUS，"
                + "中间隔着充电管理芯片（效率按 88% 估算）—— 所以协议只能给区间和置信度，"
                + "不能给名字。要确定协议得用 USB 测试仪抓 CC 线报文。");
        caveat.setTextSize(11);
        caveat.setTextColor(pal.textTertiary);
        caveat.setLineSpacing(dp(3), 1f);
        caveat.setPadding(0, dp(14), 0, 0);
        resultBox.addView(caveat);

        // 保存
        Button save = new Button(this);
        save.setText("保存这次结果");
        save.setOnClickListener(v -> askName());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        slp.topMargin = dp(14);
        resultBox.addView(save, slp);
    }

    private void askName() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setHint("例如：小米原装 67W / Anker 65W / 车载充电器");
        new AlertDialog.Builder(this)
                .setTitle("这是什么充电器")
                .setMessage("起个名字，下次换充电器体检就能对比")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    String name = et.getText().toString().trim();
                    saveEntry(name.isEmpty() ? "未命名" : name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveEntry(String name) {
        ChargerStore.Entry e = new ChargerStore.Entry();
        e.ts = System.currentTimeMillis();
        e.name = name;
        e.peakW = lastResult.peakW;
        e.steadyW = lastResult.steadyW;
        e.tier = lastGuess != null ? lastGuess.tier : "";
        e.durationS = lastResult.durationMs / 1000;
        ChargerStore.add(this, e);

        String cmp = ChargerStore.compareWithPrevious(this, e);
        toast(cmp == null ? "已保存「" + name + "」" : "已保存 · " + cmp);
        renderHistory();
    }

    private void renderHistory() {
        historyBox.removeAllViews();
        List<ChargerStore.Entry> all = ChargerStore.load(this);
        if (all.isEmpty()) return;

        sectionTitle("测过的充电器", historyBox);
        for (ChargerStore.Entry e : all) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(8), 0, dp(8));

            TextView left = new TextView(this);
            left.setText(e.name + "\n" + e.tier);
            left.setTextSize(13);
            left.setTextColor(pal.textPrimary);
            left.setLineSpacing(dp(2), 1f);
            row.addView(left, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView right = new TextView(this);
            right.setText(String.format(Locale.US, "%.1f / %.1f W",
                    e.peakW, e.steadyW));
            right.setTextSize(13);
            right.setTextColor(pal.textSecondary);
            right.setGravity(Gravity.END);
            row.addView(right);

            historyBox.addView(row);

            View line = new View(this);
            line.setBackgroundColor(pal.border);
            historyBox.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }

        TextView hint = new TextView(this);
        hint.setText("峰值 / 稳态。同一台手机换充电器测，数字才可比。");
        hint.setTextSize(11);
        hint.setTextColor(pal.textTertiary);
        hint.setPadding(0, dp(8), 0, 0);
        historyBox.addView(hint);
    }

    // ------------------------------------------------------------ 导出

    private void saveCsv() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            File probes = new File(dir, "probes");
            if (!probes.exists() && !probes.mkdirs()) return;

            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date());
            File f = new File(probes, "probe_" + stamp + ".csv");

            try (BufferedWriter out = new BufferedWriter(new FileWriter(f, false))) {
                out.write("t_s,vbat_v,ibat_a,temp_c,soc_pct,status,p_watt\n");
                for (ChargerProbe.Point p : points) {
                    out.write(String.format(Locale.US, "%.2f,%.3f,%.3f,%d,%.1f,%s,%.2f%n",
                            p.tMs / 1000.0, p.vbat, p.ibat, p.soc, p.tempC,
                            p.status, p.pWatt));
                }
            }
        } catch (Exception ignored) {
            // 存不下就算了，结果还在屏幕上
        }
    }

    // ------------------------------------------------------------ 小工具

    private void sectionTitle(String s, LinearLayout parent) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(13);
        tv.setTextColor(pal.textTertiary);
        tv.setPadding(0, dp(20), 0, dp(6));
        parent.addView(tv);
    }

    private void addLine(LinearLayout parent, String s, int size, int color, int topPad) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(size);
        tv.setTextColor(color);
        tv.setLineSpacing(dp(3), 1f);
        tv.setPadding(0, topPad, 0, 0);
        parent.addView(tv);
    }

    private void addCard(LinearLayout row, String value, String label, boolean gap) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(pal.surfaceAlt);
        card.setPadding(dp(10), dp(12), dp(10), dp(12));
        card.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (gap) lp.rightMargin = dp(8);
        card.setLayoutParams(lp);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(19);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(pal.textPrimary);
        v.setGravity(Gravity.CENTER);
        card.addView(v);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(11);
        l.setTextColor(pal.textSecondary);
        l.setGravity(Gravity.CENTER);
        card.addView(l);

        row.addView(card);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
