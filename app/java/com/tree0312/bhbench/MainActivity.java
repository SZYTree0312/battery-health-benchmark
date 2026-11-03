package com.tree0312.bhbench;

import android.Manifest;
import android.app.Activity;
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
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateLive();
            ui.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
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

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("电池容量测量");
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("放电测：充满开始，放到自动关机。\n"
                + "充电测：用到关机，插上充电器过夜，早上看结果。");
        hint.setTextSize(12);
        hint.setTextColor(Color.GRAY);
        hint.setLineSpacing(dp(3), 1f);
        hint.setPadding(0, dp(6), 0, dp(16));
        root.addView(hint);

        // 实时面板
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.rgb(245, 245, 245));
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.addView(panel);

        tvLive = new TextView(this);
        tvLive.setTextSize(14);
        tvLive.setTextColor(Color.rgb(30, 30, 30));
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

        // 按钮行
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(14), 0, dp(14));
        root.addView(row);

        btnStart = new Button(this);
        btnStart.setText("开始");
        btnStart.setOnClickListener(v -> startSampling());
        row.addView(btnStart, new LinearLayout.LayoutParams(0, dp(48), 1f));

        btnStop = new Button(this);
        btnStop.setText("停止");
        btnStop.setEnabled(false);
        btnStop.setOnClickListener(v -> stopSampling());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.leftMargin = dp(8);
        row.addView(btnStop, lp);

        btnAnalyze = new Button(this);
        btnAnalyze.setText("分析最新");
        btnAnalyze.setOnClickListener(v -> analyzeLatest());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp2.leftMargin = dp(8);
        row.addView(btnAnalyze, lp2);

        // 设备信息
        tvDevice = new TextView(this);
        tvDevice.setTextSize(11);
        tvDevice.setTextColor(Color.GRAY);
        tvDevice.setPadding(0, 0, 0, dp(10));
        tvDevice.setText(Build.MANUFACTURER + " " + Build.MODEL
                + "   Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")");
        root.addView(tvDevice);

        // 结果
        tvResult = new TextView(this);
        tvResult.setTextSize(13);
        tvResult.setTextColor(Color.rgb(20, 20, 20));
        tvResult.setLineSpacing(dp(5), 1f);
        tvResult.setPadding(dp(14), dp(14), dp(14), dp(14));
        tvResult.setBackgroundColor(Color.rgb(250, 250, 250));
        root.addView(tvResult);

        return scroll;
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
        } else if (SamplerService.currentFile != null) {
            sb.append("\n\n上次记录：").append(new File(SamplerService.currentFile).getName());
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

        Intent i = new Intent(this, SamplerService.class);
        i.setAction(SamplerService.ACTION_START);
        i.putExtra(SamplerService.EXTRA_DESIGN, (double) design);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        toast("已开始。现在可以拔掉充电线了");
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
