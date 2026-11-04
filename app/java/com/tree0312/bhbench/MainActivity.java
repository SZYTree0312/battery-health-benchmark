package com.tree0312.bhbench;

import static com.tree0312.bhbench.Ui.*;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * 主界面 —— v0.5.3 重排信息架构。
 *
 * 改之前：一屏从上到下堆「电池容量测量」的实时数据、标称容量、六个按钮
 * （开始/停止/分析/历史/充电器体检/充电器档案/显示模式）、设备信息。
 * 功能都在，但两件事被揉成一串按钮，用户得在心里先分类才知道点哪个。
 *
 * 改之后：
 *   1. 页面顶部一个分段控件，把**测电池容量**和**测充电器**并成同一页的两段 ——
 *      本来就是同一台手机的两项体检，切一下就到，不用跳页面；
 *   2. 显示模式、版本信息、设备信息这些「一整个月都不碰一次」的东西
 *      收进右上角设置（SettingsActivity）；
 *   3. 最近结果留在电池段，充电器档案入口留在充电器段，各归各位。
 *
 * 全部代码构建 View，不引 AndroidX —— 手工 aapt2 + javac + d8 的打包方式不变。
 */
public class MainActivity extends Activity {

    /** 实时数据用固定行复用，每秒只改数值，不重建 View。 */
    private TextView[] liveValues;
    private TextView liveExtra;
    private EditText etDesign;
    private Button btnStart;
    private Button btnStop;

    private BatteryManager bm;
    private SharedPreferences prefs;
    /** 配色跟随系统深浅色。 */
    private Palette pal;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ChargerPanel chargerPanel;
    private ScrollView batteryScroll;
    private ScrollView chargerScroll;
    private Ui.Segmented segmented;

    private static final String KEY_TAB = "home_tab";

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
        ui.removeCallbacks(refresh);
        ui.post(refresh);
    }

    @Override protected void onResume() {
        super.onResume();
        // 先撤再投：onCreate 已经投过一次，直接 post 会一秒跑两遍
        ui.removeCallbacks(refresh);
        ui.post(refresh);
        if (currentTab() == 1 && chargerPanel != null) chargerPanel.onShown();
    }

    @Override protected void onPause() {
        ui.removeCallbacks(refresh);
        // 充电器采样靠 UI 线程的定时器推进，退到后台必须停
        if (chargerPanel != null) chargerPanel.onHostPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (chargerPanel != null) chargerPanel.onHostDestroy();
        super.onDestroy();
    }

    /** 充电器档案导出走系统文件选择器，回执转给面板处理。 */
    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (chargerPanel != null) chargerPanel.onActivityResult(req, result, data);
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

    private int currentTab() {
        int t = prefs.getInt(KEY_TAB, 0);
        return (t == 1) ? 1 : 0;
    }

    private View buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(pal.bg);

        // ---- 固定的页头：标题 + 设置 + 分段。不随内容滚动，切段时位置不变
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(14) + statusBarH(), dp(20), dp(S3));
        page.addView(header);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(titleRow);

        TextView title = Ui.title(this, pal, "电池体检");
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout gearBtn = new FrameLayout(this);
        gearBtn.setBackground(Ui.roundRipple(this, pal, pal.accentSoft, Ui.CHIP_RADIUS_DP));
        gearBtn.setContentDescription("设置");
        gearBtn.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        gearBtn.addView(new GearIcon(this, pal.accent),
                new FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER));
        titleRow.addView(gearBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));

        TextView sub = Ui.subtitle(this, pal,
                "容量看电池还能装多少，体检看充电器能给多少 —— 都是这台手机的实测。");
        header.addView(sub);

        int tab = currentTab();
        segmented = Ui.segmented(this, pal,
                new String[]{"电池容量", "充电器体检"}, tab, this::showTab);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(S4);
        header.addView(segmented.view, slp);

        // ---- 内容区：两个面板叠在一起，切换靠 visibility
        FrameLayout content = new FrameLayout(this);
        page.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 内边距放在 ScrollView 身上，不放在里面的 LinearLayout 上。
        // 放里面时子 View 的测量宽度是「屏幕宽」，文字会贴着甚至压过右边缘
        // （实测右侧只剩 12dp，和左边 20dp 不对称）；放 ScrollView 上，
        // 它给子 View 的可用宽度就已经扣掉了两边，文字换行一定落在等距区域内。
        batteryScroll = new ScrollView(this);
        batteryScroll.setClipToPadding(false);
        batteryScroll.setPadding(dp(20), 0, dp(20), 0);
        batteryScroll.addView(buildBatteryPanel());
        content.addView(batteryScroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        chargerScroll = new ScrollView(this);
        chargerScroll.setClipToPadding(false);
        chargerScroll.setPadding(dp(20), 0, dp(20), 0);
        chargerPanel = new ChargerPanel(this, pal);
        chargerScroll.addView(chargerPanel.build());
        content.addView(chargerScroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        applyTab(tab);
        return page;
    }

    /** 电池容量段。 */
    private View buildBatteryPanel() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(S2), 0, dp(S6));

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

        // ---- 标称容量：测量的唯一输入项，紧贴操作区
        root.addView(Ui.section(this, pal, "标称容量"));
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
        ep.topMargin = dp(S1);
        setting.addView(etDesign, ep);

        TextView designHint = new TextView(this);
        designHint.setText("满电拔线用下去，到 15% 会提醒插电；插着充电器就一直充到 100%。"
                + "分析时优先用完整放电段，不完整自动退回充电段。");
        designHint.setTextSize(11);
        designHint.setTextColor(pal.textTertiary);
        designHint.setLineSpacing(dp(3), 1f);
        designHint.setPadding(0, dp(S3), 0, 0);
        setting.addView(designHint);
        root.addView(setting);

        // ---- 操作：一个主按钮，其余都是次按钮，主次一眼可分
        root.addView(Ui.section(this, pal, "操作"));

        btnStart = Ui.primary(this, pal, "开始记录");
        btnStart.setOnClickListener(v -> startSampling());
        btnStop = Ui.secondary(this, pal, "停止");
        btnStop.setEnabled(false);
        btnStop.setOnClickListener(v -> stopSampling());
        root.addView(Ui.buttonRow(this, S2, btnStart, btnStop));

        Button btnAnalyze = Ui.secondary(this, pal, "分析最新");
        btnAnalyze.setOnClickListener(v -> analyzeLatest());
        Button btnHistory = Ui.secondary(this, pal, "历史记录");
        btnHistory.setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));
        root.addView(Ui.buttonRow(this, S2, btnAnalyze, btnHistory));

        // 「最近一次结果」这张卡撤了：分析按钮直接进结果页，那一屏就是完整结论，
        // 主界面再复述一遍纯属占地方。要看历史去「测量记录」。
        return root;
    }

    private void showTab(int index) {
        prefs.edit().putInt(KEY_TAB, index).apply();
        applyTab(index);
    }

    private void applyTab(int index) {
        batteryScroll.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        chargerScroll.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        if (chargerPanel != null) {
            if (index == 1) chargerPanel.onShown();
            else chargerPanel.onHidden();
        }
        if (segmented != null) segmented.setSelected(index);
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
            toast("还没有记录文件，先跑一次测试");
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        File latest = files[0];

        int design = prefs.getInt("design", 0);
        if (design <= 0) {
            toast("先填厂商标称容量");
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
