package com.tree0312.bhbench;

import static com.tree0312.bhbench.Ui.*;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 积分精度验证 —— v0.5.5，学安兔兔那条路。
 *
 * <p>安兔兔电池损耗测试的公开用法：插着充电器、测试页面保持在前台、期间不要
 * 用手机、充 10 分钟以上越久越准。网上对「要不要亮屏」说法不一，但真正的机制
 * 不是亮屏这个动作，而是 <b>Activity 常驻前台 = 进程始终可见</b>：Doze 判定
 * 「闲置应用」时根本不会把它列进去，国产 ROM 的 PowerKeeper 也无从冻结。
 *
 * <p>这正好是 rc2 失败后的正解。rc2 想在息屏放电时用唤醒锁 / 看门狗 /
 * specialUse 前台服务跟系统对抗，被 MIUI 8 秒收走唤醒锁（issue #1）。
 * 这里不与系统对抗：只在<b>页面可见期间</b>采样，用
 * {@link WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON} 让屏幕不自动熄灭
 * （官方推荐做法，不需要 WAKE_LOCK 权限，离开页面自动失效）。一旦用户主动息屏
 * 或切走，立刻停止并把这次标记为中断 —— 宁可数据短，也不要被冻过的数据。
 *
 * <p>为什么只在充电段做：放电时的电流取决于用户怎么用手机，不可复现；
 * 充电时电流由充电器决定。而且插电时手机本来就不会进 Doze。亮屏本身耗电，
 * 但库仑计 ΔCC 和 ∫I dt 量的都是同一段电池侧净电荷，两条路径同源，
 * 比值 k 不受屏幕功耗影响 —— 只是不能把这个电流当成「充电器输出」。
 *
 * <p>采样跑在页面自己的 Handler 上，不碰 SamplerService，不申请唤醒锁，
 * 不起前台服务。rc2 撤掉的东西一件都没回来。
 */
public class AccuracyActivity extends Activity {

    private static final int REQ_EXPORT = 72;
    /** 可选采样间隔（秒）。0.5 秒对 BatteryManager binder 已经够密了。 */
    private static final double[] INTERVALS_S = {0.5, 1, 2, 5};
    /** 可选时长（分钟）。安兔兔要求至少 10 分钟，越久越准。 */
    private static final int[] MINUTES = {10, 20, 30};

    private Palette pal;
    private BatteryManager bm;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Ui.Segmented segInterval;
    private Ui.Segmented segMinutes;
    private TextView tvHeadline;
    private TextView tvLive;
    private TextView tvState;
    private LinearLayout resultBox;
    private Button btnStart;
    private Button btnStop;

    private final List<AccuracyMath.Sample> points = new ArrayList<>();
    private boolean running = false;
    private boolean aborted = false;
    private boolean counterOk = false;
    private long t0Elapsed;
    private long intervalMs = 1000;
    private long durationMs = 10 * 60 * 1000L;
    private String lastCsv = "";
    private String lastCsvPath = "";
    private BroadcastReceiver screenOff;

    @Override protected void onCreate(Bundle saved) {
        ThemeMode.apply(this);
        super.onCreate(saved);
        pal = Palette.of(this);
        bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        setContentView(buildUi());
        Ui.applySystemBars(this, pal);

        screenOff = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (running) abort("屏幕熄灭，本次中断");
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenOff, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenOff, f);
        }
    }

    @Override protected void onStop() {
        // 页面不再可见，进程随时可能被降级 / 冻结。这时候继续采只会拿到空档，
        // 而空档恰恰是这个实验最不能有的东西。
        if (running) abort("页面切到后台，本次中断");
        super.onStop();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacks(tick);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try { if (screenOff != null) unregisterReceiver(screenOff); } catch (Exception ignored) {}
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
        scroll.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(14) + statusBarH(), dp(20), dp(S6));
        scroll.addView(root);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(head);
        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(22);
        back.setTextColor(pal.textPrimary);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, dp(S3), 0);
        back.setOnClickListener(v -> finish());
        head.addView(back);
        head.addView(Ui.title(this, pal, "积分精度验证"));

        // ---- 为什么常亮
        root.addView(Ui.section(this, pal, "为什么屏幕要一直亮着"));
        LinearLayout why = Ui.card(this, pal);
        addPara(why,
                "息屏放电时国产 ROM 会冻住后台进程（唤醒锁 8 秒就被收走），采样必然断档。"
                        + "安兔兔不跟系统对抗：让测试页面常驻前台，进程始终可见，"
                        + "系统压根不会把它判成闲置。这里照做 —— 只在页面可见期间采样，"
                        + "屏幕保持常亮，切走或息屏立刻停止。");
        addPara(why,
                "亮屏本身耗电，但库仑计 ΔCC 和 ∫I dt 量的都是同一段电池侧净电荷，"
                        + "两条路径同源，比值不受屏幕功耗影响。只是别把这个电流当成"
                        + "「充电器输出」。");
        addPara(why,
                "前置条件：插着充电器、正在充电（已充满不行）、期间别动手机。"
                        + "建议充 10 分钟以上，越久越准。");
        root.addView(why);

        // ---- 参数
        root.addView(Ui.section(this, pal, "参数"));
        LinearLayout cfg = Ui.card(this, pal);
        addLabel(cfg, "采样间隔");
        segInterval = Ui.segmented(this, pal,
                new String[]{"0.5 秒", "1 秒", "2 秒", "5 秒"}, 1, null);
        cfg.addView(segInterval.view);
        addHint(cfg, "一次测量会把同一段数据抽稀成多个尺度分别积分，所以选 1 秒也能看到"
                + "更疏的间隔下会差多少。");
        cfg.addView(Ui.divider(this, pal));
        addLabel(cfg, "测试时长");
        segMinutes = Ui.segmented(this, pal,
                new String[]{"10 分钟", "20 分钟", "30 分钟"}, 0, null);
        cfg.addView(segMinutes.view);
        root.addView(cfg);

        // ---- 实时
        root.addView(Ui.section(this, pal, "实时"));
        LinearLayout live = Ui.card(this, pal);
        tvHeadline = new TextView(this);
        tvHeadline.setText("∫I  —  /  ΔCC  —");
        tvHeadline.setTextSize(22);
        tvHeadline.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvHeadline.setTextColor(pal.textPrimary);
        tvHeadline.setGravity(Gravity.CENTER);
        live.addView(tvHeadline);
        tvLive = new TextView(this);
        tvLive.setText("还没开始");
        tvLive.setTextSize(12);
        tvLive.setTextColor(pal.textSecondary);
        tvLive.setGravity(Gravity.CENTER);
        tvLive.setPadding(0, dp(S3), 0, 0);
        live.addView(tvLive);
        tvState = new TextView(this);
        tvState.setText("屏幕常亮只在测试期间开启");
        tvState.setTextSize(11);
        tvState.setTextColor(pal.textTertiary);
        tvState.setGravity(Gravity.CENTER);
        tvState.setPadding(0, dp(S2), 0, 0);
        live.addView(tvState);
        root.addView(live);

        btnStart = Ui.primary(this, pal, "开始验证");
        btnStart.setOnClickListener(v -> startRun());
        btnStop = Ui.secondary(this, pal, "停止");
        btnStop.setEnabled(false);
        btnStop.setOnClickListener(v -> finishRun(false, "手动停止"));
        root.addView(Ui.buttonRow(this, S2, btnStart, btnStop));

        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultBox);
        return scroll;
    }

    private void addPara(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(pal.textSecondary);
        tv.setLineSpacing(dp(3), 1f);
        tv.setPadding(0, 0, 0, dp(S3));
        parent.addView(tv);
    }

    private void addLabel(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(pal.textTertiary);
        tv.setPadding(0, 0, 0, dp(S2));
        parent.addView(tv);
    }

    private void addHint(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(pal.textTertiary);
        tv.setLineSpacing(dp(3), 1f);
        tv.setPadding(0, dp(S2), 0, 0);
        parent.addView(tv);
    }

    // ------------------------------------------------------------ 采样

    /** 读一次电池状态。全部来自公开 API，符号方向不重要（只用绝对值）。 */
    private AccuracyMath.Sample read() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return null;
        int st = b.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);
        int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);

        long curUa = bm != null
                ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) : Integer.MIN_VALUE;
        long ccUah = bm != null
                ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) : Integer.MIN_VALUE;
        counterOk = ccUah != Integer.MIN_VALUE && ccUah > 0;

        AccuracyMath.Sample p = new AccuracyMath.Sample();
        p.t = (SystemClock.elapsedRealtime() - t0Elapsed) / 1000.0;
        p.currentMa = (curUa == Integer.MIN_VALUE) ? 0 : curUa / 1000.0;  // µA → mA
        p.voltageMv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        p.socPct = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        p.tempC = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250) / 10.0;
        p.counterMah = counterOk ? ccUah / 1000.0 : 0;                     // µAh → mAh
        p.plugged = plugged > 0;
        p.status = st == BatteryManager.BATTERY_STATUS_CHARGING ? "charging"
                : st == BatteryManager.BATTERY_STATUS_FULL ? "full" : "discharging";
        return p;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            AccuracyMath.Sample p = read();
            if (p != null) {
                if (!p.plugged) {
                    abort("充电器断开了，本次中断");
                    return;
                }
                // 充满不是故障，是这一段的自然终点：已经没有电流可积了，
                // 提前收尾并照常出报告，别把好好的数据标成「中断」。
                if ("full".equals(p.status)) {
                    finishRun(false, "已充满，提前结束");
                    return;
                }
                points.add(p);
                updateLive();
            }
            long elapsed = SystemClock.elapsedRealtime() - t0Elapsed;
            if (elapsed >= durationMs) {
                finishRun(false, "跑满设定时长");
                return;
            }
            ui.postDelayed(this, intervalMs);
        }
    };

    private void updateLive() {
        if (points.isEmpty()) return;
        AccuracyMath.Sample last = points.get(points.size() - 1);
        List<List<AccuracyMath.Sample>> blocks =
                AccuracyMath.splitBlocks(points, Math.max(10, intervalMs / 1000.0 * 3));
        double dcc = AccuracyMath.counterOf(blocks);
        double q = AccuracyMath.integrateAt(blocks, 0);
        double k = dcc > 0 ? q / dcc : 0;
        tvHeadline.setText(String.format(Locale.US, "∫I %.0f  /  ΔCC %.0f mAh", q, dcc));
        tvLive.setText(String.format(Locale.US,
                "k = %.3f   ·   %d 点   ·   %.0f 秒   ·   电流 %.0f mA   ·   %.0f%%",
                k, points.size(), last.t, Math.abs(last.currentMa), last.socPct));
    }

    private void startRun() {
        if (running) return;
        t0Elapsed = SystemClock.elapsedRealtime();
        AccuracyMath.Sample first = read();
        if (first == null || !first.plugged) {
            toast("先插上充电器，再开始验证");
            return;
        }
        if (!"charging".equals(first.status)) {
            toast("要处于充电中，已充满时电流太小测不出东西");
            return;
        }
        if (!counterOk) {
            toast("这台设备不给库仑计读数，没有基准就没法对账");
            return;
        }

        intervalMs = Math.round(INTERVALS_S[segInterval.selected()] * 1000);
        durationMs = MINUTES[segMinutes.selected()] * 60 * 1000L;
        points.clear();
        aborted = false;
        resultBox.removeAllViews();
        running = true;

        // 只在测试期间常亮。FLAG_KEEP_SCREEN_ON 由系统在当前窗口可见时维持，
        // 不需要 WAKE_LOCK 权限，Activity 一离开就自动失效。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        segInterval.view.setEnabled(false);
        segMinutes.view.setEnabled(false);
        tvState.setText("屏幕常亮中 · 别锁屏、别切应用、保持充电");

        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    private void abort(String why) {
        aborted = true;
        finishRun(true, why);
    }

    private void finishRun(boolean wasAborted, String why) {
        running = false;
        ui.removeCallbacks(tick);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        segInterval.view.setEnabled(true);
        segMinutes.view.setEnabled(true);
        tvState.setText(why + (wasAborted ? "（数据只到中断那一刻）" : ""));

        lastCsv = buildCsv();
        lastCsvPath = writeCsv(lastCsv);
        AccuracyMath.Report r = AccuracyMath.analyze(points, intervalMs / 1000.0);
        renderResult(r, wasAborted, why);
    }

    // ------------------------------------------------------------ 结果

    private void renderResult(AccuracyMath.Report r, boolean wasAborted, String why) {
        resultBox.removeAllViews();
        resultBox.addView(Ui.section(this, pal, "结论"));

        LinearLayout verdict = Ui.card(this, pal);
        TextView head = new TextView(this);
        head.setText(wasAborted ? "本次被中断：" + why : AccuracyMath.headline(r));
        head.setTextSize(15);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.setTextColor(wasAborted ? pal.bad : pal.textPrimary);
        verdict.addView(head);

        TextView note = new TextView(this);
        note.setText(r.valid ? r.note : "数据不足：" + r.note);
        note.setTextSize(13);
        note.setTextColor(r.valid && r.calibratable ? pal.good : pal.textSecondary);
        note.setLineSpacing(dp(4), 1f);
        note.setPadding(0, dp(S3), 0, 0);
        verdict.addView(note);
        resultBox.addView(verdict);

        if (!r.valid) {
            addFooter();
            return;
        }

        // ---- 采样质量
        resultBox.addView(Ui.section(this, pal, "采样质量"));
        LinearLayout quality = Ui.card(this, pal);
        addRow(quality, "有效点数", String.format(Locale.US, "%d 点 / %.1f 分钟",
                r.points, r.coveredS / 60.0));
        addRow(quality, "连续块 / 空档", String.format(Locale.US, "%d 块 / %d 处",
                r.blocks, r.gaps));
        addRow(quality, "跨尺度极差", String.format(Locale.US, "%.2f%%  %s",
                r.spreadPct, r.converged ? "已收敛" : "未收敛"));
        resultBox.addView(quality);

        // ---- 多尺度
        resultBox.addView(Ui.section(this, pal, "同一段数据，抽稀后再积分"));
        LinearLayout scales = Ui.card(this, pal);
        addRow(scales, "间隔 / 点数", "∫I dt (mAh)   偏差");
        for (AccuracyMath.Scale s : r.scales) {
            String label = String.format(Locale.US, "%.1f 秒 / %d 点", s.stepS, s.points);
            String value = String.format(Locale.US, "%.0f   %+.2f%%",
                    s.integralMah, s.errPct);
            addRow(scales, label, value);
        }
        resultBox.addView(scales);
        addFootNote("抽稀后积分若几乎不变，说明采样率已经不是误差来源；"
                + "偏差就只剩电流读数本身。");

        // ---- 分段 k
        resultBox.addView(Ui.section(this, pal, "每 2 分钟的 ∫I / ΔCC"));
        LinearLayout kv = Ui.card(this, pal);
        addRow(kv, "均值", String.format(Locale.US, "%.4f", r.kMean));
        addRow(kv, "标准差 / 变异系数", String.format(Locale.US, "%.4f  /  %.2f%%",
                r.kStd, r.kCvPct));
        addRow(kv, "范围", String.format(Locale.US, "%.4f ~ %.4f  (%d 个窗口)",
                r.kMin, r.kMax, r.windows.size()));
        if (r.calibratable) {
            addRow(kv, "标定系数", String.format(Locale.US, "× %.4f", r.calibration));
        }
        resultBox.addView(kv);
        addFootNote("基准是库仑计 ΔCC，不是绝对真值 —— 库仑计自己也有标定误差。"
                + "这里量的是两条独立路径的一致性，结论只保证这台机器、这一段充电成立；"
                + "换充电器或换 SOC 区间要重测。");

        // ---- 导出
        Button export = Ui.secondary(this, pal, "导出原始 CSV");
        export.setOnClickListener(v -> exportCsv());
        resultBox.addView(export);
        if (!lastCsvPath.isEmpty()) {
            TextView path = new TextView(this);
            path.setText("已自动存一份：" + lastCsvPath);
            path.setTextSize(11);
            path.setTextColor(pal.textTertiary);
            path.setPadding(0, dp(S2), 0, 0);
            resultBox.addView(path);
        }
        addFooter();
    }

    private void addRow(LinearLayout parent, String label, String value) {
        Ui.PrefRow row = Ui.prefRow(this, pal, label);
        row.value.setText(value);
        parent.addView(row.view);
    }

    private void addFootNote(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(pal.textTertiary);
        tv.setLineSpacing(dp(3), 1f);
        tv.setPadding(dp(S2), dp(S2), dp(S2), 0);
        resultBox.addView(tv);
    }

    private void addFooter() {
        TextView tv = new TextView(this);
        tv.setText("想判断这个偏差能不能推广到别的工况，换一个充电器、或换一段 SOC"
                + "区间再跑一次，看 k 还是不是同一个数。");
        tv.setTextSize(11);
        tv.setTextColor(pal.textTertiary);
        tv.setLineSpacing(dp(3), 1f);
        tv.setPadding(0, dp(S4), 0, 0);
        resultBox.addView(tv);
    }

    // ------------------------------------------------------------ CSV

    /** 与 analyzer/accuracy.py 的 CSV_HEADER 逐列对齐。 */
    private String buildCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("t_s,current_ma,voltage_mv,soc_pct,temp_c,charge_counter_mah,plugged,status\n");
        for (AccuracyMath.Sample p : points) {
            // 格式串 8 个占位符对应 8 列；改列数时必须同步改这里
            sb.append(String.format(Locale.US, "%.3f,%.1f,%.0f,%.0f,%.1f,%.0f,%d,%s%n",
                    p.t, p.currentMa, p.voltageMv, p.socPct, p.tempC,
                    p.counterMah, p.plugged ? 1 : 0,
                    p.status == null ? "" : p.status));
        }
        return sb.toString();
    }

    private String writeCsv(String text) {
        try {
            File dir = new File(getExternalFilesDir(null), "accuracy");
            if (!dir.exists() && !dir.mkdirs()) return "";
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date());
            File f = new File(dir, "accuracy_" + stamp + ".csv");
            try (BufferedWriter out = new BufferedWriter(new FileWriter(f, false))) {
                out.write(text);
            }
            return f.getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }

    private void exportCsv() {
        if (lastCsv.isEmpty()) { toast("没有数据可导出"); return; }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "accuracy_"
                + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date())
                + ".csv");
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            toast("这台设备没有文件保存界面");
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_EXPORT || result != RESULT_OK || data == null
                || data.getData() == null) return;
        Uri uri = data.getData();
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IllegalStateException("no stream");
            os.write(lastCsv.getBytes(StandardCharsets.UTF_8));
            toast("已导出 " + points.size() + " 个采样点");
        } catch (Exception e) {
            toast("导出失败：" + e.getMessage());
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
