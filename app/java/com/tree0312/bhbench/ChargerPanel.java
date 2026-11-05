package com.tree0312.bhbench;

import static com.tree0312.bhbench.Ui.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 充电器体检面板。
 *
 * v0.5.3 之前它是个独立 Activity（ChargerProbeActivity），主界面上要跳一下才进去，
 * 而「测电池」和「测充电器」本来是同一件事的两面 —— 都在这台手机上做体检，
 * 关心的都是同一组电压电流。于是收进主界面的第二段，切 tab 就到，不用来回跳。
 *
 * 面板只负责内容，不负责外壳：不建 ScrollView、不画页面标题、不加状态栏留白，
 * 那些由宿主（MainActivity）统一处理。
 *
 * 采样 200 ms 一个点、跑 90 秒。为什么要这么密：快充协商是**瞬间**的事，
 * PD 从 5V 切到 9V 就是几十毫秒，5 秒一个点只能看到"结果"，看不到"过程"。
 * 而过程里才有协议的证据。
 */
final class ChargerPanel {

    private final Activity act;
    private final Palette pal;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final IntentFilter battFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    private final BatteryManager bm;

    private boolean running = false;
    /** 采集中途拔过充电器 —— 这次整段作废，不能出结论、不能入档。 */
    private boolean aborted = false;
    /** 当前这段是否显示着。隐藏时不做空闲预览刷新，省掉无谓的广播读取。 */
    private boolean visible = true;
    private long startTs = 0;
    private final List<ChargerProbe.Point> points = new ArrayList<>();
    private int tickSinceRedraw = 0;

    private TextView tvPower;
    private TextView tvDetail;
    private TextView tvStatus;
    private PowerChartView chart;
    private LinearLayout resultBox;
    /** 充电器档案区（对比 + 全部记录）在第一次构建时创建，之后只重填内容。 */
    private LinearLayout compareBox;
    private LinearLayout archiveList;
    private TextView archiveHead;
    /** 导出走系统文件选择器，请求码要从宿主 Activity 转回来。 */
    static final int REQ_EXPORT = 71;
    private Button btnStart;
    private Button btnStop;

    private ChargerProbe.Result lastResult;
    private SysfsProbe.Info lastSysfs;
    private ProtocolGuess.Guess lastGuess;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (running) {
                if (!collect()) {          // 中途拔线，collect 已收尾
                    ui.postDelayed(this, 1000);   // 循环还得活着，回去刷空闲预览
                    return;
                }
                long elapsed = System.currentTimeMillis() - startTs;
                if (elapsed >= ChargerProbe.DURATION_MS) {
                    stopProbe("体检完成");
                    return;
                }
                if (++tickSinceRedraw >= 5) {   // 每秒重绘一次，200 ms 一次太浪费
                    tickSinceRedraw = 0;
                    refreshLive();
                    refreshChart();
                }
                tvStatus.setText(String.format(Locale.US, "%d / %d 秒",
                        elapsed / 1000, ChargerProbe.DURATION_MS / 1000));
                ui.postDelayed(this, ChargerProbe.INTERVAL_MS);
            } else {
                if (visible) previewOnce();
                ui.postDelayed(this, 1000);
            }
        }
    };

    ChargerPanel(Activity act, Palette pal) {
        this.act = act;
        this.pal = pal;
        this.bm = (BatteryManager) act.getSystemService(Activity.BATTERY_SERVICE);
    }

    // ------------------------------------------------------------ 界面

    LinearLayout build() {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        // 左右内边距由宿主 ScrollView 负责，这里只补上下留白
        root.setPadding(0, dp(S3), 0, dp(S6));

        TextView sub = new TextView(act);
        sub.setText("测的是这台手机实际吃进去多少功率，不是充电器标称值。"
                + "没插充电器时这里不显示功率。插上充电器点开始，跑 90 秒。");
        sub.setTextSize(12);
        sub.setTextColor(pal.textTertiary);
        sub.setLineSpacing(dp(3), 1f);
        sub.setPadding(0, 0, 0, dp(S3));
        root.addView(sub);

        // 实时大字
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.rounded(act, pal.surface, Ui.CARD_RADIUS_DP));
        panel.setPadding(dp(S4), dp(S4), dp(S4), dp(S4));
        root.addView(panel);

        tvPower = new TextView(act);
        tvPower.setText("—");
        tvPower.setTextSize(40);
        tvPower.setTypeface(Typeface.DEFAULT_BOLD);
        tvPower.setTextColor(pal.textPrimary);
        tvPower.setGravity(Gravity.CENTER);
        panel.addView(tvPower);

        TextView hint = new TextView(act);
        hint.setText("W  电池侧");
        hint.setTextSize(12);
        hint.setTextColor(pal.textTertiary);
        hint.setGravity(Gravity.CENTER);
        panel.addView(hint);

        tvDetail = new TextView(act);
        tvDetail.setText("电压 —  电流 —  电量 —");
        tvDetail.setTextSize(13);
        tvDetail.setTextColor(pal.textSecondary);
        tvDetail.setGravity(Gravity.CENTER);
        tvDetail.setPadding(0, dp(10), 0, 0);
        panel.addView(tvDetail);

        tvStatus = new TextView(act);
        tvStatus.setText("");
        tvStatus.setTextSize(12);
        tvStatus.setTextColor(pal.textTertiary);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, dp(6), 0, 0);
        panel.addView(tvStatus);

        chart = new PowerChartView(act);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        clp.topMargin = dp(S3);
        root.addView(chart, clp);

        btnStart = Ui.primary(act, pal, "开始体检");
        btnStart.setText(String.format(Locale.US, "开始体检（%d 秒）",
                ChargerProbe.DURATION_MS / 1000));
        btnStart.setOnClickListener(v -> startProbe());
        btnStop = Ui.secondary(act, pal, "停止");
        btnStop.setEnabled(false);
        btnStop.setOnClickListener(v -> stopProbe("已停止"));
        root.addView(Ui.buttonRow(act, S2, btnStart, btnStop));

        resultBox = new LinearLayout(act);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultBox);

        // 档案直接做在这一段里：体检完存一条，往下就能看到它被排进对比，
        // 不用再跳到另一个页面去核对刚才那一枪打得怎么样。
        root.addView(Ui.section(act, pal, "充电器档案"));

        archiveHead = new TextView(act);
        archiveHead.setTextSize(12);
        archiveHead.setTextColor(pal.textTertiary);
        archiveHead.setPadding(0, 0, 0, dp(S2));
        root.addView(archiveHead);

        compareBox = Ui.card(act, pal);
        root.addView(compareBox);

        // 对比卡片和下面的记录卡片原来贴在一起，两张圆角卡片的边线视觉上粘成一条
        // 长矩形，看着像重合。这里加一个小标题把两块分开，顺带说明下面是什么。
        //
        // 标题叫「体检记录」而不是「全部记录」：存储只留最近 12 条（ChargerStore.KEEP），
        // 更早的在写入时就被挤掉了。写「全部」是骗人，下面那行说明也照实写。
        root.addView(Ui.section(act, pal, "体检记录"));

        archiveList = new LinearLayout(act);
        archiveList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(S2);
        root.addView(archiveList, alp);

        Button export = Ui.secondary(act, pal, "导出 CSV");
        export.setOnClickListener(v -> doExport());
        Button clear = Ui.secondary(act, pal, "清空档案");
        clear.setOnClickListener(v -> doClear());
        root.addView(Ui.buttonRow(act, S2, export, clear));

        TextView archiveHint = new TextView(act);
        archiveHint.setText("只保留最近 12 次，更早的会在存新记录时被挤掉。"
                + "记录在应用私有目录，卸载即消失，要留的先导出 CSV。");
        archiveHint.setTextSize(11);
        archiveHint.setTextColor(pal.textTertiary);
        archiveHint.setLineSpacing(dp(3), 1f);
        archiveHint.setPadding(0, dp(S3), 0, 0);
        root.addView(archiveHint);

        reloadArchive();
        ui.post(loop);
        return root;
    }

    /** 切到这一段时刷新一遍显示：隐藏期间采样可能还在跑，视图是旧的。 */
    void onShown() {
        visible = true;
        if (running) {
            refreshLive();
            refreshChart();
        } else {
            previewOnce();
        }
    }

    /** 切到另一段：采样继续（不影响数据），只停掉空闲预览。 */
    void onHidden() {
        visible = false;
    }

    /** 宿主退到后台：采样必须停，否则前台服务没在跑，进程随时会被冻结。 */
    void onHostPause() {
        if (running) stopProbe("退到后台，已停止");
    }

    void onHostDestroy() {
        ui.removeCallbacks(loop);
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------ 采样

    /** 空闲时也显示实时功率 —— 插上充电器就能先看一眼，不用等体检。 */
    private void previewOnce() {
        if (running) return;
        ChargerProbe.Point p = read();
        if (p == null) return;
        if (!p.plugged) {
            // 放电时 CURRENT_NOW 是负的，abs(V×I) 会变成一个像模像样的
            // 「充电功率」。这里不显示任何数，直接说明没插充电器。
            tvPower.setText("—");
            tvDetail.setText("未连接充电器");
            tvStatus.setText("插上充电器才能测功率");
            return;
        }
        tvPower.setText(String.format(Locale.US, "%.1f", p.pWatt));
        tvDetail.setText(String.format(Locale.US,
                "电压 %.2f V   电流 %.2f A   电量 %d%%   %.1f °C",
                p.vbat, Math.abs(p.ibat), p.soc, p.tempC));
        tvStatus.setText("full".equals(p.status) ? "已充满，功率会接近 0" : "实时功率");
    }

    /** 返回 false = 采集中途拔线，调用方别再排下一次采样。 */
    private boolean collect() {
        ChargerProbe.Point p = read();
        if (p == null) return true;
        if (!p.plugged) {
            aborted = true;
            stopProbe("充电器已断开，本次作废");
            return false;
        }
        p.tMs = p.tMs - startTs;
        points.add(p);
        refreshLive();
        return true;
    }

    private void refreshLive() {
        if (points.isEmpty()) return;
        ChargerProbe.Point p = points.get(points.size() - 1);
        tvPower.setText(String.format(Locale.US, "%.1f", p.pWatt));
        tvDetail.setText(String.format(Locale.US,
                "电压 %.2f V   电流 %.2f A   电量 %d%%   %.1f °C",
                p.vbat, Math.abs(p.ibat), p.soc, p.tempC));
    }

    /** 读一次电池状态。电压电流都来自 BatteryManager，只有电池侧。 */
    private ChargerProbe.Point read() {
        Intent b = act.registerReceiver(null, battFilter);
        if (b == null) return null;

        int mv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int tempTenth = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250);
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int st = b.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);
        // EXTRA_PLUGGED 直接反映插拔，比 status 及时，也不受厂商对
        // CURRENT_NOW 正负号约定不一致的影响。没插电就不该产生功率。
        int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);

        int curUa = bm != null
                ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) : 0;

        ChargerProbe.Point p = new ChargerProbe.Point();
        p.tMs = System.currentTimeMillis();
        p.vbat = mv / 1000.0;
        p.ibat = (curUa == Integer.MIN_VALUE) ? 0 : curUa / 1_000_000.0;
        p.tempC = tempTenth / 10.0;
        p.soc = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;
        p.status = statusName(st);
        p.plugged = plugged > 0;
        p.pWatt = p.plugged ? Math.abs(p.vbat * p.ibat) : 0;
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
        ChargerProbe.Point first = read();
        if (first == null || !first.plugged) {
            toast("先插上充电器，再开始体检");
            return;
        }
        aborted = false;
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
            showInvalid(why, aborted
                    ? "采集中途断开了充电器，这次不作数"
                    : "采样点太少，重测一次");
            lastResult = null;
            lastGuess = null;
            return;
        }

        lastResult = ChargerProbe.analyze(points);
        boolean charging = ChargerProbe.looksCharging(points);
        lastGuess = ProtocolGuess.from(lastResult, charging);

        refreshChart();
        saveCsv();   // 原始点留作诊断，即使这次作废也不删
        if (aborted || !charging || lastResult == null || !lastResult.valid) {
            showInvalid(why, lastResult == null ? "没有数据" : lastResult.note);
            lastResult = null;   // 断掉误存的最后一道：结果都没了，保存按钮也不会出现
            lastGuess = null;
            return;
        }
        showResult();
    }

    /** 无效的一次：说清为什么，不给峰值、不给协议、不给保存。 */
    private void showInvalid(String why, String note) {
        resultBox.removeAllViews();
        String msg = aborted ? "采集中途断开了充电器，这次不作数"
                : (!note.isEmpty() ? note : why);
        addLine(resultBox, msg, 14, pal.bad, dp(10));
        addLine(resultBox, "插好充电器、保持连接，重新跑一次 90 秒。", 12,
                pal.textTertiary, dp(6));
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
        LinearLayout cards = new LinearLayout(act);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setPadding(0, dp(S3), 0, 0);
        addCard(cards, String.format(Locale.US, "%.1f", r.peakW), "W 峰值", true);
        addCard(cards, String.format(Locale.US, "%.1f", r.steadyW), "W 稳态", true);
        addCard(cards, String.format(Locale.US, "%.1f", r.energyWh), "Wh 充入", false);
        resultBox.addView(cards);

        TextView sub = new TextView(act);
        sub.setText(String.format(Locale.US,
                "峰值出现在第 %.0f 秒 · 稳态波动 %.1f%% · 电池温度 %.1f → %.1f °C",
                r.peakAtMs / 1000.0, r.stabilityPct, r.avgTemp, r.peakTemp));
        sub.setTextSize(12);
        sub.setTextColor(pal.textSecondary);
        sub.setPadding(0, dp(S2), 0, dp(S1));
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
        TextView caveat = new TextView(act);
        caveat.setText("上面这些是电池侧的电压电流。手机读不到 USB 输入侧的 VBUS/IBUS，"
                + "中间隔着充电管理芯片（效率按 88% 估算）—— 所以协议只能给区间和置信度，"
                + "不能给名字。要确定协议得用 USB 测试仪抓 CC 线报文。");
        caveat.setTextSize(11);
        caveat.setTextColor(pal.textTertiary);
        caveat.setLineSpacing(dp(3), 1f);
        caveat.setPadding(0, dp(S4), 0, 0);
        resultBox.addView(caveat);

        // 保存
        Button save = Ui.secondary(act, pal, "保存这次结果");
        save.setOnClickListener(v -> askName());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        slp.topMargin = dp(S4);
        resultBox.addView(save, slp);
    }

    private void askName() {
        EditText et = new EditText(act);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setHint("例如：小米原装 67W / Anker 65W / 车载充电器");
        new AlertDialog.Builder(act)
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
        ChargerStore.add(act, e);

        String cmp = ChargerStore.compareWithPrevious(act, e);
        toast(cmp == null ? "已保存「" + name + "」" : "已保存 · " + cmp);
        reloadArchive();
    }

    // ------------------------------------------------------------ 档案

    /**
     * 充电器档案 —— 原来是一个独立页面，v0.5.3 起直接长在体检这一段下面。
     *
     * 理由很实际：体检完要「保存这次结果」，保存完最想看的正是「和别的充电器比
     * 怎么样」。原来得再点一次「全部档案」跳过去，等于让用户为一次查看多走一步。
     * 现在保存完就地重排，刚才那一枪立刻出现在对比里。
     *
     * 数据只存结论（峰值/稳态/档位/时长），不存 90 秒曲线 —— 曲线在本段实时画完
     * 就丢，SharedPreferences 存几千个点是给自己找麻烦。最多留 12 条。
     */
    private void reloadArchive() {
        List<ChargerStore.Entry> all = ChargerStore.load(act);
        if (archiveHead == null) return;

        archiveHead.setText(all.isEmpty()
                ? "还没有记录。插上充电器跑一次 90 秒，点「保存这次结果」就会出现在这里。"
                : String.format(Locale.US, "共 %d 次体检 · %d 个充电器",
                        all.size(), group(all).size()));

        renderCompare(all);
        renderArchiveList(all);
    }

    /** 按名字聚合。峰值取该充电器跑出过的最好一次，稳态取平均。 */
    private Map<String, Group> group(List<ChargerStore.Entry> all) {
        Map<String, Group> out = new LinkedHashMap<>();
        for (ChargerStore.Entry e : all) {
            Group g = out.get(e.name);
            if (g == null) {
                g = new Group();
                g.name = e.name;
                out.put(e.name, g);
            }
            g.count++;
            g.peak = Math.max(g.peak, e.peakW);
            g.steadySum += e.steadyW;
        }
        for (Group g : out.values()) g.steady = g.steadySum / Math.max(1, g.count);
        return out;
    }

    private static class Group {
        String name;
        int count;
        double peak;
        double steady;
        double steadySum;
    }

    private void renderCompare(List<ChargerStore.Entry> all) {
        compareBox.removeAllViews();
        Map<String, Group> groups = group(all);
        if (groups.isEmpty()) {
            TextView empty = new TextView(act);
            empty.setText("暂无对比数据");
            empty.setTextSize(13);
            empty.setTextColor(pal.textTertiary);
            compareBox.addView(empty);
            return;
        }

        double maxW = 1;
        for (Group g : groups.values()) maxW = Math.max(maxW, g.peak);

        LinearLayout legend = new LinearLayout(act);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        legend.addView(legendItem("峰值", pal.accent));
        legend.addView(legendItem("稳态", pal.chartCounter));
        compareBox.addView(legend);

        boolean first = true;
        for (Group g : groups.values()) {
            if (!first) {
                View line = new View(act);
                line.setBackgroundColor(pal.border);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
                llp.topMargin = dp(S3);
                llp.bottomMargin = dp(S3);
                compareBox.addView(line, llp);
            }
            first = false;

            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView name = new TextView(act);
            name.setText(g.name + (g.count > 1 ? "  ×" + g.count : ""));
            name.setTextSize(13);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(pal.textPrimary);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView val = new TextView(act);
            val.setText(String.format(Locale.US, "%.1f / %.1f W", g.peak, g.steady));
            val.setTextSize(12);
            val.setTextColor(pal.textSecondary);
            row.addView(val);
            compareBox.addView(row);

            compareBox.addView(bar(g.peak, maxW, pal.accent));
            compareBox.addView(bar(g.steady, maxW, pal.chartCounter));
        }

        TextView note = new TextView(act);
        note.setText("峰值看冲不冲得上去，稳态看压不压得住。两条差得越远，越是「冲一下就掉队」。");
        note.setTextSize(11);
        note.setTextColor(pal.textTertiary);
        note.setLineSpacing(dp(3), 1f);
        note.setPadding(0, dp(S3), 0, 0);
        compareBox.addView(note);
    }

    private View legendItem(String text, int color) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(0, 0, dp(S4), dp(S2));
        View dot = new View(act);
        dot.setBackgroundColor(color);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(12), dp(8));
        dlp.gravity = Gravity.CENTER_VERTICAL;
        dlp.rightMargin = dp(6);
        box.addView(dot, dlp);
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(pal.textSecondary);
        box.addView(tv);
        return box;
    }

    /** 用 LinearLayout weight 画横条，不用 Canvas，深色浅色都跟着色板走。 */
    private View bar(double value, double maxW, int color) {
        LinearLayout track = new LinearLayout(act);
        track.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(7));
        tlp.topMargin = dp(3);
        track.setLayoutParams(tlp);

        float w = (float) Math.max(0.02, Math.min(1.0, value / maxW));
        View fill = new View(act);
        fill.setBackground(Ui.rounded(act, color, 999f));
        track.addView(fill, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, w));
        View rest = new View(act);
        rest.setBackground(Ui.rounded(act, pal.border, 999f));
        track.addView(rest, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0f, 1f - w)));
        return track;
    }

    private void renderArchiveList(List<ChargerStore.Entry> all) {
        archiveList.removeAllViews();
        if (all.isEmpty()) {
            LinearLayout card = Ui.card(act, pal);
            TextView tv = new TextView(act);
            tv.setText("插上充电器，点上面的「开始体检」，90 秒后把结果存进来。\n"
                    + "同一台手机换着充电器测，峰值和稳态才有可比性。");
            tv.setTextSize(13);
            tv.setTextColor(pal.textTertiary);
            tv.setLineSpacing(dp(4), 1f);
            card.addView(tv);
            archiveList.addView(card);
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        for (ChargerStore.Entry e : all) {
            LinearLayout card = Ui.card(act, pal);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = dp(S2);
            card.setLayoutParams(clp);

            LinearLayout top = new LinearLayout(act);
            top.setOrientation(LinearLayout.HORIZONTAL);
            TextView name = new TextView(act);
            name.setText(e.name);
            name.setTextSize(15);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(pal.textPrimary);
            top.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView time = new TextView(act);
            time.setText(fmt.format(new Date(e.ts)));
            time.setTextSize(11);
            time.setTextColor(pal.textTertiary);
            top.addView(time);
            card.addView(top);

            if (e.tier != null && !e.tier.isEmpty()) {
                String tier = e.tier.length() > 18 ? e.tier.substring(0, 18) + "…" : e.tier;
                LinearLayout chipRow = new LinearLayout(act);
                chipRow.setPadding(0, dp(S2), 0, 0);
                chipRow.addView(Ui.chip(act, pal, tier));
                card.addView(chipRow);
            }

            double keep = e.peakW > 0 ? e.steadyW / e.peakW * 100 : 0;
            TextView nums = new TextView(act);
            nums.setText(String.format(Locale.US,
                    "峰值 %.1f W   稳态 %.1f W   保持 %.0f%%   %d 秒",
                    e.peakW, e.steadyW, keep, e.durationS));
            nums.setTextSize(12);
            nums.setTextColor(pal.textSecondary);
            nums.setPadding(0, dp(S3), 0, 0);
            card.addView(nums);

            LinearLayout ops = new LinearLayout(act);
            ops.setOrientation(LinearLayout.HORIZONTAL);
            ops.setPadding(0, dp(S3), 0, 0);
            ops.addView(action("重命名", pal.accent, v -> showRename(e)));
            ops.addView(action("删除", pal.bad, v -> confirmDelete(e)));
            card.addView(ops);

            archiveList.addView(card);
        }
    }

    private TextView action(String text, int color, View.OnClickListener l) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(color);
        tv.setPadding(0, dp(S1), dp(S5), dp(S1));
        tv.setOnClickListener(l);
        return tv;
    }

    private void showRename(ChargerStore.Entry e) {
        final EditText input = new EditText(act);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(e.name);
        input.setSelection(input.getText().length());
        input.setPadding(dp(S4), dp(S3), dp(S4), dp(S3));
        new AlertDialog.Builder(act)
                .setTitle("给这次记录起个名字")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    ChargerStore.rename(act, e.ts, input.getText().toString());
                    reloadArchive();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(ChargerStore.Entry e) {
        new AlertDialog.Builder(act)
                .setTitle("删除这条记录")
                .setMessage(e.name + "\n峰值 "
                        + String.format(Locale.US, "%.1f W", e.peakW))
                .setPositiveButton("删除", (d, w) -> {
                    ChargerStore.remove(act, e.ts);
                    reloadArchive();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doClear() {
        int n = ChargerStore.load(act).size();
        if (n == 0) { toast("档案本来就是空的"); return; }
        new AlertDialog.Builder(act)
                .setTitle("清空充电器档案")
                .setMessage("将删除全部 " + n + " 条记录，不可恢复。\n要留的先导出 CSV。")
                .setPositiveButton("清空", (d, w) -> {
                    ChargerStore.clear(act);
                    reloadArchive();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doExport() {
        if (ChargerStore.load(act).isEmpty()) { toast("没有记录可导出"); return; }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "charger_probes_"
                + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date()) + ".csv");
        try {
            act.startActivityForResult(i, REQ_EXPORT);
        } catch (Exception ex) {
            toast("这台设备没有文件保存界面");
        }
    }

    /** 由宿主 Activity 转发：导出完成的回执落在面板里。 */
    void onActivityResult(int req, int result, Intent data) {
        if (req != REQ_EXPORT || result != Activity.RESULT_OK
                || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try (OutputStream os = act.getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IllegalStateException("no stream");
            os.write(ChargerStore.toCsv(act).getBytes(StandardCharsets.UTF_8));
            toast("已导出 " + ChargerStore.load(act).size() + " 条记录");
        } catch (Exception ex) {
            toast("导出失败：" + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ 导出

    private void saveCsv() {
        try {
            File dir = act.getExternalFilesDir(null);
            if (dir == null) dir = act.getFilesDir();
            File probes = new File(dir, "probes");
            if (!probes.exists() && !probes.mkdirs()) return;

            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date());
            File f = new File(probes, "probe_" + stamp + ".csv");

            try (BufferedWriter out = new BufferedWriter(new FileWriter(f, false))) {
                out.write("t_s,vbat_v,ibat_a,temp_c,soc_pct,status,p_watt,plugged\n");
                for (ChargerProbe.Point p : points) {
                    // 表头顺序是 temp_c,soc_pct —— 旧版把这两个值写反了，顺手对齐。
                    // 末尾 plugged 列别漏：格式串少一个 %d 的话 String.format 不报错，
                    // 只是静默丢掉这个参数，导出的 CSV 会缺列。
                    out.write(String.format(Locale.US, "%.2f,%.3f,%.3f,%.1f,%d,%s,%.2f,%d%n",
                            p.tMs / 1000.0, p.vbat, p.ibat, p.tempC, p.soc,
                            p.status, p.pWatt, p.plugged ? 1 : 0));
                }
            }
        } catch (Exception ignored) {
            // 存不下就算了，结果还在屏幕上
        }
    }

    // ------------------------------------------------------------ 小工具

    private void sectionTitle(String s, LinearLayout parent) {
        TextView tv = new TextView(act);
        tv.setText(s);
        tv.setTextSize(13);
        tv.setTextColor(pal.textTertiary);
        tv.setPadding(0, dp(S5), 0, dp(6));
        parent.addView(tv);
    }

    private void addLine(LinearLayout parent, String s, int size, int color, int topPad) {
        TextView tv = new TextView(act);
        tv.setText(s);
        tv.setTextSize(size);
        tv.setTextColor(color);
        tv.setLineSpacing(dp(3), 1f);
        tv.setPadding(0, topPad, 0, 0);
        parent.addView(tv);
    }

    private void addCard(LinearLayout row, String value, String label, boolean gap) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.rounded(act, pal.surfaceAlt, Ui.CARD_RADIUS_DP));
        card.setPadding(dp(10), dp(S3), dp(10), dp(S3));
        card.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (gap) lp.rightMargin = dp(S2);
        card.setLayoutParams(lp);

        TextView v = new TextView(act);
        v.setText(value);
        v.setTextSize(19);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(pal.textPrimary);
        v.setGravity(Gravity.CENTER);
        card.addView(v);

        TextView l = new TextView(act);
        l.setText(label);
        l.setTextSize(11);
        l.setTextColor(pal.textSecondary);
        l.setGravity(Gravity.CENTER);
        card.addView(l);

        row.addView(card);
    }

    private void toast(String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
    }
}
