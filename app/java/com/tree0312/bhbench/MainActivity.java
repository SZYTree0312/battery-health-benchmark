package com.tree0312.bhbench;

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

    private TextView tvLive;
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12) + statusBarH(), dp(18), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("电池容量测量");
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("点开始后连续记录，充电、放电都记，中途不用停。\n"
                + "· 满电拔线用下去 —— 记放电段，到 15% 会提醒插电\n"
                + "· 插着充电器 —— 记充电段，充到 100% 即可\n"
                + "分析时优先用完整的放电段；不完整就自动退回用充电段。");
        hint.setTextSize(12);
        hint.setTextColor(pal.textTertiary);
        hint.setLineSpacing(dp(3), 1f);
        hint.setPadding(0, dp(6), 0, dp(16));
        root.addView(hint);

        // 实时面板
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(pal.surface);
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.addView(panel);

        tvLive = new TextView(this);
        tvLive.setTextSize(14);
        tvLive.setTextColor(pal.textPrimary);
        tvLive.setLineSpacing(dp(4), 1f);
        panel.addView(tvLive);

        // 设计容量
        TextView lblDesign = new TextView(this);
        lblDesign.setText("厂商标称容量（mAh）");
        lblDesign.setTextSize(13);
        lblDesign.setPadding(0, dp(18), 0, dp(4));
        root.addView(lblDesign);

        etDesign = new EditText(this);
        etDesign.setInputType(InputType.TYPE_CLASS_NUMBER);
        etDesign.setHint("例如 7000");
        etDesign.setText(String.valueOf(prefs.getInt("design", 0) > 0
                ? prefs.getInt("design", 0) : ""));
        root.addView(etDesign);

        // 按钮：两行。开始/停止是一组，分析/历史是另一组 —— 混在一行会把
        // 每个按钮挤到只剩图标宽度。
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(14), 0, 0);
        root.addView(row);

        btnStart = new Button(this);
        btnStart.setText("开始记录");
        btnStart.setOnClickListener(v -> startSampling());
        row.addView(btnStart, new LinearLayout.LayoutParams(0, dp(48), 1f));

        btnStop = new Button(this);
        btnStop.setText("停止");
        btnStop.setEnabled(false);
        btnStop.setOnClickListener(v -> stopSampling());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.leftMargin = dp(8);
        row.addView(btnStop, lp);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(8), 0, 0);
        root.addView(row2);

        btnAnalyze = new Button(this);
        btnAnalyze.setText("分析最新");
        btnAnalyze.setOnClickListener(v -> analyzeLatest());
        row2.addView(btnAnalyze, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button btnHistory = new Button(this);
        btnHistory.setText("历史记录");
        btnHistory.setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp2.leftMargin = dp(8);
        row2.addView(btnHistory, lp2);

        Button btnCharger = new Button(this);
        btnCharger.setText("充电器体检");
        btnCharger.setOnClickListener(v ->
                startActivity(new Intent(this, ChargerProbeActivity.class)));
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp3.topMargin = dp(8);
        root.addView(btnCharger, lp3);

        // 显示模式。按钮上直接写当前模式，不用点进去才知道。
        Button btnTheme = new Button(this);
        btnTheme.setText("显示模式 · " + ThemeMode.label(ThemeMode.get(this)));
        btnTheme.setOnClickListener(v -> showThemeDialog());
        LinearLayout.LayoutParams lp4 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        lp4.topMargin = dp(8);
        root.addView(btnTheme, lp4);

        // 设备信息
        tvDevice = new TextView(this);
        tvDevice.setTextSize(11);
        tvDevice.setTextColor(pal.textTertiary);
        tvDevice.setPadding(0, 0, 0, dp(10));
        tvDevice.setText(Build.MANUFACTURER + " " + Build.MODEL
                + "   Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")");
        root.addView(tvDevice);

        // 结果
        tvResult = new TextView(this);
        tvResult.setTextSize(13);
        tvResult.setTextColor(pal.textPrimary);
        tvResult.setLineSpacing(dp(5), 1f);
        tvResult.setPadding(dp(14), dp(14), dp(14), dp(14));
        tvResult.setBackgroundColor(pal.surfaceAlt);
        root.addView(tvResult);

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

        StringBuilder sb = new StringBuilder();
        sb.append("电量    ").append(soc).append("%\n");
        sb.append("电压    ").append(mv).append(" mV\n");
        sb.append("温度    ").append(String.format(Locale.US, "%.1f", temp / 10.0)).append(" °C\n");
        sb.append("库仑计  ").append(counter / 1000).append(" mAh\n");
        sb.append("电流    ").append(curUa == Integer.MIN_VALUE || curUa == 0
                ? "不可用（该机型不提供）"
                : String.format(Locale.US, "%.0f mA", Math.abs(curUa) / 1000.0)).append("\n");
        sb.append("状态    ").append(status);

        if (SamplerService.running) {
            long secs = (System.currentTimeMillis() - SamplerService.startedAt) / 1000;
            sb.append("\n\n正在记录  ")
              .append(String.format(Locale.US, "%d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60))
              .append("   ").append(SamplerService.sampleCount).append(" 点");
            // 把两段的进度都摆出来，用户一眼能看到充电段确实也在记
            sb.append("\n已记入    放电 ")
              .append(dur(Math.round(SamplerService.dischargeSeconds)))
              .append("   充电 ")
              .append(dur(Math.round(SamplerService.chargeSeconds)));
            sb.append("\n采样间隔  ").append(SamplerService.currentIntervalS).append(" 秒")
              .append(SamplerService.currentIntervalS > 5 ? "（电量没动，自动放宽省电）" : "");
        } else if (SamplerService.currentFile != null) {
            sb.append("\n\n上次记录：").append(new File(SamplerService.currentFile).getName());
        }

        // 充满时自动留一条快照，顺便把最近几条display出来
        SnapshotStore.maybeRecord(this, soc, counter, temp / 10.0);
        java.util.List<String> snaps = SnapshotStore.recent(this, 3);
        if (!snaps.isEmpty()) {
            sb.append("\n\n满电快照");
            for (String s : snaps) sb.append("\n  ").append(s);
        }

        tvLive.setText(sb.toString());
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
