package com.tree0312.bhbench;

import static com.tree0312.bhbench.Ui.*;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * 主界面。
 *
 * 不引 AndroidX、不写 XML 布局，全部代码构建 —— 目的只有一个：
 * 能用手工 aapt2 + javac + d8 打包出 APK，不依赖 Gradle 和一堆 Maven 依赖。
 * 界面朴素但够用，重点是把测量流程走通。
 */
public class MainActivity extends Activity {

    /** 实时数据用固定行复用，每秒只改数值，不重建 View。 */
    private TextView[] liveValues;
    private TextView liveExtra;
    private TextView tvResult;
    private TextView tvDevice;
    private EditText etDesign;
    private Button btnStart;
    private Button btnStop;
    private Button btnAnalyze;

    private BatteryManager bm;
    private SharedPreferences prefs;
    /** 配色跟随系统深浅色。 */
    private Palette pal;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateLive();
            ui.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        // 必须在 super.onCreate() 之前：窗口底色和状态栏图标由主题决定
        ThemeMode.apply(this);
        super.onCreate(saved);
        pal = Palette.of(this);
        bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        prefs = getSharedPreferences("bhbench", MODE_PRIVATE);
        setContentView(buildUi());
        Ui.applySystemBars(this, pal);
        askNotificationPermission();
        ui.post(refresh);
    }

    @Override protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    // ------------------------------------------------------------ 界面

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 状态栏高度。主题没开 fitsSystemWindows，标题会被状态栏压住。 */
    private int statusBarH() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(pal.bg);
        scroll.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16) + statusBarH(), dp(20), dp(S6));
        scroll.addView(root);

        root.addView(Ui.title(this, pal, "电池容量测量"));
        TextView sub = Ui.subtitle(this, pal,
                "连续记录，充电、放电都记，中途不用停。");
        sub.setTextColor(pal.textSecondary);
        root.addView(sub);

        TextView how = new TextView(this);
        how.setText("满电拔线用下去，到 15% 会提醒插电；插着充电器就一直充到 100%。\n"
                + "分析时优先用完整放电段，不完整自动退回充电段。");
        how.setTextSize(12);
        how.setTextColor(pal.textTertiary);
        how.setLineSpacing(dp(3), 1f);
        how.setPadding(0, dp(S3), 0, 0);
        root.addView(how);

        // ---- 实时数据
        root.addView(Ui.section(this, pal, "实时数据"));
        LinearLayout live = Ui.card(this, pal);
        String[] labels = {"电量", "电压", "温度", "库仑计", "电流", "状态"};
        liveValues = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) live.addView(Ui.divider(this, pal));
            Ui.PrefRow r = Ui.prefRow(this, pal, labels[i]);
            liveValues[i] = r.value;
            live.addView(r.view);
        }
        liveExtra = new TextView(this);
        liveExtra.setTextSize(12);
        liveExtra.setTextColor(pal.textSecondary);
        liveExtra.setLineSpacing(dp(3), 1f);
        liveExtra.setPadding(0, dp(S3), 0, 0);
        live.addView(liveExtra);
        root.addView(live);

        // ---- 设置
        root.addView(Ui.section(this, pal, "测量设置"));
        LinearLayout setting = Ui.card(this, pal);
        TextView lblDesign = new TextView(this);
        lblDesign.setText("厂商标称容量（mAh）");
        lblDesign.setTextSize(13);
        lblDesign.setTextColor(pal.textSecondary);
        setting.addView(lblDesign);

        etDesign = new EditText(this);
        etDesign.setInputType(InputType.TYPE_CLASS_NUMBER);
        etDesign.setHint("例如 7000");
        etDesign.setTextSize(18);
        etDesign.setText(String.valueOf(prefs.getInt("design", 0) > 0
                ? prefs.getInt("design", 0) : ""));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ep.topMargin = dp(S2);
        setting.addView(etDesign, ep);
        root.addView(setting);

        // ---- 操作：一个主按钮，其余都是次按钮，主次一眼可分
        root.addView(Ui.section(this, pal, "操作"));

        btnStart = Ui.primary(this, pal, "开始记录");
        btnStart.setOnClickListener(v -> startSampling());
        btnStop = Ui.secondary(this, pal, "停止");
        btnStop.setEnabled(false);
        btnStop.setOnClickListener(v -> stopSampling());
        root.addView(Ui.buttonRow(this, S2, btnStart, btnStop));

        btnAnalyze = Ui.secondary(this, pal, "分析最新");
        btnAnalyze.setOnClickListener(v -> analyzeLatest());
        Button btnHistory = Ui.secondary(this, pal, "历史记录");
        btnHistory.setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));
        root.addView(Ui.buttonRow(this, S2, btnAnalyze, btnHistory));

        Button btnCharger = Ui.secondary(this, pal, "充电器体检");
        btnCharger.setOnClickListener(v ->
                startActivity(new Intent(this, ChargerProbeActivity.class)));
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp3.topMargin = dp(S2);
        root.addView(btnCharger, lp3);

        Button btnChargerHistory = Ui.secondary(this, pal, "充电器档案");
        btnChargerHistory.setOnClickListener(v ->
                startActivity(new Intent(this, ChargerHistoryActivity.class)));
        LinearLayout.LayoutParams lph = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lph.topMargin = dp(S2);
        root.addView(btnChargerHistory, lph);

        Button btnTheme = Ui.secondary(this, pal,
                "显示模式 · " + ThemeMode.label(ThemeMode.get(this)));
        btnTheme.setMinHeight(dp(44));
        btnTheme.setOnClickListener(v -> showThemeDialog());
        LinearLayout.LayoutParams lp4 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp4.topMargin = dp(S2);
        root.addView(btnTheme, lp4);

        // ---- 最近一次结果
        root.addView(Ui.section(this, pal, "最近一次结果"));
        LinearLayout resultCard = Ui.card(this, pal);
        tvResult = new TextView(this);
        tvResult.setTextSize(13);
        tvResult.setTextColor(pal.textPrimary);
        tvResult.setLineSpacing(dp(5), 1f);
        resultCard.addView(tvResult);
        root.addView(resultCard);

        tvDevice = new TextView(this);
        tvDevice.setTextSize(11);
        tvDevice.setTextColor(pal.textTertiary);
        tvDevice.setPadding(0, dp(S5), 0, 0);
        tvDevice.setText(Build.MANUFACTURER + " " + Build.MODEL
                + "   Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")");
        root.addView(tvDevice);

        return scroll;
    }

    /**
     * 三态切换。选完清空整个 task 栈重开主界面 —— 光 recreate() 的话，
     * 后退栈里已经建好的结果页/历史页还留着旧主题，按返回键就会看到半套配色。
     * 前台采样服务不受 Activity 栈影响，不会被打断。
     */
    private void showThemeDialog() {
        final String[] labels = {"跟随系统", "浅色", "深色"};
        final int[] modes = {ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK};
        int cur = ThemeMode.get(this);
        int checked = cur == ThemeMode.LIGHT ? 1 : (cur == ThemeMode.DARK ? 2 : 0);

        new AlertDialog.Builder(this)
                .setTitle("显示模式")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    ThemeMode.set(this, modes[which]);
                    d.dismiss();
                    Intent i = new Intent(this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------ 状态

    private void updateLive() {
        Intent batt = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batt == null) return;

        int level = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batt.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int soc = (scale > 0) ? Math.round(level * 100f / scale) : -1;
        int mv = batt.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int temp = batt.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        int st = batt.getIntExtra(BatteryManager.EXTRA_STATUS, 0);

        long counter = bm != null
                ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) : 0;
        int curUa = bm != null
                ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) : 0;

        String status;
        switch (st) {
            case BatteryManager.BATTERY_STATUS_CHARGING: status = "充电中"; break;
            case BatteryManager.BATTERY_STATUS_DISCHARGING: status = "放电中"; break;
            case BatteryManager.BATTERY_STATUS_FULL: status = "已充满"; break;
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: status = "未充电"; break;
            default: status = "未知";
        }

        // 六行固定，只刷数值 —— 系统设置的电池页就是这个结构
        liveValues[0].setText(soc + " %");
        liveValues[1].setText(mv + " mV");
        liveValues[2].setText(String.format(Locale.US, "%.1f °C", temp / 10.0));
        liveValues[3].setText(counter / 1000 + " mAh");
        liveValues[4].setText(curUa == Integer.MIN_VALUE || curUa == 0
                ? "不可用"
                : String.format(Locale.US, "%.0f mA", Math.abs(curUa) / 1000.0));
        liveValues[5].setText(status);

        // 记录进度、快照这类不定长信息放在下面的次级文字里
        StringBuilder extra = new StringBuilder();
        if (SamplerService.running) {
            long secs = (System.currentTimeMillis() - SamplerService.startedAt) / 1000;
            extra.append("正在记录  ")
                 .append(String.format(Locale.US, "%d:%02d:%02d",
                         secs / 3600, (secs % 3600) / 60, secs % 60))
                 .append("   ").append(SamplerService.sampleCount).append(" 点")
                 .append("\n已记入  放电 ")
                 .append(dur(Math.round(SamplerService.dischargeSeconds)))
                 .append("   充电 ")
                 .append(dur(Math.round(SamplerService.chargeSeconds)))
                 .append("\n采样间隔  ").append(SamplerService.currentIntervalS).append(" 秒")
                 .append(SamplerService.currentIntervalS > 5 ? "（电量没动，自动放宽）" : "");
        } else if (SamplerService.currentFile != null) {
            extra.append("上次记录：").append(new File(SamplerService.currentFile).getName());
        }

        // 充满时自动留一条快照
        SnapshotStore.maybeRecord(this, soc, counter, temp / 10.0);
        java.util.List<String> snaps = SnapshotStore.recent(this, 3);
        if (!snaps.isEmpty()) {
            if (extra.length() > 0) extra.append("\n");
            extra.append("满电快照");
            for (String x : snaps) extra.append("\n  ").append(x);
        }
        liveExtra.setText(extra.toString());
        liveExtra.setVisibility(extra.length() == 0 ? View.GONE : View.VISIBLE);

        btnStart.setEnabled(!SamplerService.running);
        btnStop.setEnabled(SamplerService.running);
    }

    // ------------------------------------------------------------ 动作

    private void startSampling() {
        String raw = etDesign.getText().toString().trim();
        if (raw.isEmpty()) {
            toast("先填厂商标称容量");
            return;
        }
        int design;
        try {
            design = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            toast("容量要填数字");
            return;
        }
        if (design < 500 || design > 20000) {
            toast("容量看起来不对，应该在 500–20000 mAh");
            return;
        }
        prefs.edit().putInt("design", design).apply();

        boolean plugged = isPlugged();

        Intent i = new Intent(this, SamplerService.class);
        i.setAction(SamplerService.ACTION_START);
        i.putExtra(SamplerService.EXTRA_DESIGN, (double) design);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        // 充电中也能开始 —— 记的是充电段。以前这里写死"可以拔掉充电线了"，
        // 插着线的人会以为 App 不肯测充电，反复点、反复弹同一句。
        toast(plugged
                ? "已开始记录充电段。保持插着充到 100% 即可"
                : "已开始记录放电段。到 15% 左右再插充电器");
    }

    /** 当前是否插着充电器（充电中或已充满）。 */
    private boolean isPlugged() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return false;
        int st = b.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);
        return st == BatteryManager.BATTERY_STATUS_CHARGING
                || st == BatteryManager.BATTERY_STATUS_FULL;
    }

    private static String dur(long sec) {
        if (sec <= 0) return "—";
        long h = sec / 3600, m = (sec % 3600) / 60;
        if (h > 0) return h + " 时 " + m + " 分";
        if (m > 0) return m + " 分";
        return sec + " 秒";
    }

    private void stopSampling() {
        Intent i = new Intent(this, SamplerService.class);
        i.setAction(SamplerService.ACTION_STOP);
        startService(i);
        toast("已停止");
    }

    private void analyzeLatest() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        File sessions = new File(dir, "sessions");
        File[] files = sessions.listFiles((d, name) -> name.endsWith(".csv"));
        if (files == null || files.length == 0) {
            tvResult.setText("还没有记录文件。先跑一次测试。");
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        File latest = files[0];

        int design = prefs.getInt("design", 0);
        if (design <= 0) {
            tvResult.setText("请先填写厂商标称容量。");
            return;
        }

        // 报告交给专门的 ResultActivity 呈现。一次测量的结论值得一屏完整展示，
        // 而不是塞进主界面的一个 TextView 里堆成文字。
        Intent intent = new Intent(this, ResultActivity.class);
        intent.putExtra(ResultActivity.EXTRA_FILE, latest.getAbsolutePath());
        intent.putExtra(ResultActivity.EXTRA_DESIGN, (double) design);
        startActivity(intent);
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }
}
