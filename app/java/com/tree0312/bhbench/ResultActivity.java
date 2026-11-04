package com.tree0312.bhbench;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * 结果页。
 *
 * 单独一个 Activity，而不是把一堆数字塞进主界面的 TextView：
 * 一次测量的结论值得一屏完整呈现 —— 大字号的健康度、分组的指标、
 * 能一眼看出有没有问题的警告区。
 *
 * 导出 CSV 放在页面底部，是附带功能，不是主要出口。
 */
public class ResultActivity extends Activity {

    public static final String EXTRA_FILE = "file";
    public static final String EXTRA_DESIGN = "design";

    private static final int REQ_EXPORT = 1001;

    private File csv;
    private Analyzer.Report report;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        String path = getIntent().getStringExtra(EXTRA_FILE);
        double design = getIntent().getDoubleExtra(EXTRA_DESIGN, 0);
        if (path == null) {
            finish();
            return;
        }
        csv = new File(path);
        report = Analyzer.analyze(csv, design);

        setContentView(buildUi());
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------ 构建界面

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(root);

        // 标题
        TextView title = new TextView(this);
        title.setText("测量结果");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(20, 20, 20));
        root.addView(title);

        TextView file = new TextView(this);
        file.setText(csv.getName());
        file.setTextSize(11);
        file.setTextColor(Color.rgb(150, 150, 150));
        file.setPadding(0, dp(4), 0, dp(20));
        root.addView(file);

        if (report.samples == 0) {
            root.addView(buildEmpty());
            root.addView(buildExportRow());
            return scroll;
        }

        root.addView(buildHeadline());
        root.addView(buildMetricRow());
        root.addView(buildDetails());
        if (!report.warnings.isEmpty()) root.addView(buildWarnings());
        root.addView(buildExportRow());

        return scroll;
    }

    /** 健康度大数字 + 等级。数据不足时不给数字，只给事实。 */
    private View buildHeadline() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, dp(8), 0, dp(24));

        TextView big = new TextView(this);
        TextView unit = new TextView(this);
        TextView grade = new TextView(this);

        if (report.reliable) {
            double soh = report.sohPct();
            big.setText(String.format(Locale.US, "%.1f", soh));
            big.setTextSize(64);
            big.setTextColor(gradeColor(soh));
            unit.setText(report.isChargeMode() ? "健康度 %（充电段折算）" : "健康度 %");
            grade.setText(report.grade);
            grade.setTextColor(gradeColor(soh));
        } else {
            big.setText("—");
            big.setTextSize(64);
            big.setTextColor(Color.rgb(150, 150, 150));
            unit.setText("本次净电荷 " + Math.round(report.rawChargeMah) + " mAh");
            grade.setText("数据异常".equals(report.grade)
                    ? "数据异常：结果超出物理合理范围，请核对下方提示"
                    : "数据不足，无法判定健康度");
            grade.setTextColor(Color.rgb(120, 120, 120));
        }

        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setGravity(Gravity.CENTER);
        box.addView(big);

        unit.setTextSize(14);
        unit.setTextColor(Color.rgb(110, 110, 110));
        unit.setGravity(Gravity.CENTER);
        box.addView(unit);

        grade.setTextSize(15);
        grade.setGravity(Gravity.CENTER);
        grade.setPadding(0, dp(8), 0, 0);
        box.addView(grade);

        return box;
    }

    /** 三张指标卡：等效容量 / 标称容量 / 放电时长。 */
    private View buildMetricRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        row.addView(metricCard(
                String.format(Locale.US, "%.0f", report.normalizedMah),
                report.reliable ? "mAh 等效容量" : "mAh 参考值(未采信)", true));
        row.addView(metricCard(
                String.format(Locale.US, "%.0f", report.designMah), "mAh 标称", true));
        row.addView(metricCard(durationText(), "放电时长", false));

        return row;
    }

    private View metricCard(String value, String label, boolean withGap) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.rgb(246, 246, 244));
        card.setPadding(dp(12), dp(14), dp(12), dp(14));
        card.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (withGap) lp.rightMargin = dp(8);
        card.setLayoutParams(lp);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(20);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(Color.rgb(25, 25, 25));
        v.setGravity(Gravity.CENTER);
        card.addView(v);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(11);
        l.setTextColor(Color.rgb(120, 120, 120));
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, dp(4), 0, 0);
        card.addView(l);

        return card;
    }

    private String durationText() {
        long min = Math.round(report.durationS / 60.0);
        return min >= 60 ? (min / 60) + "h" + (min % 60) : min + "m";
    }

    /** 明细：一行一项，标签在左、值在右。 */
    private View buildDetails() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(26), 0, 0);

        TextView header = new TextView(this);
        header.setText("明细");
        header.setTextSize(13);
        header.setTextColor(Color.rgb(130, 130, 130));
        header.setPadding(0, 0, 0, dp(10));
        box.addView(header);

        addRow(box, "测量方式",
                report.isChargeMode() ? "充电段 · 按 SOC 折算" : "放电段", false);
        addRow(box, "数据来源", sourceLabel(), false);
        addRow(box, "本次净电荷",
                String.format(Locale.US, "%.0f mAh", report.rawChargeMah), false);
        addRow(box, "折算后容量",
                String.format(Locale.US, "%.0f mAh @ %.1f °C",
                        report.measuredMah, report.avgTemp), false);
        addRow(box, report.isChargeMode() ? "充入能量" : "放出能量",
                String.format(Locale.US, "%.1f Wh", report.energyWh), false);
        addRow(box, "平均电压", String.format(Locale.US, "%.2f V", report.avgVoltage), false);
        addRow(box, "平均电流", String.format(Locale.US, "%.0f mA", report.avgCurrent), false);
        addRow(box, "峰值温度", String.format(Locale.US, "%.1f °C", report.peakTemp), false);
        addRow(box, "SOC 区间",
                report.socStart + "% → " + report.socEnd + "%"
                        + String.format(Locale.US, "（跨度 %.0f%%）", report.socSpan), false);
        addRow(box, "采样质量",
                String.format(Locale.US, "%d 点 / 有效覆盖 %.0f%% / 最长空档 %.1f h",
                        report.samples, report.coveragePct, report.maxGapS / 3600.0), false);
        if (report.crossCheckMah >= 0 && !Double.isNaN(report.divergencePct)) {
            addRow(box, "交叉校验(电流积分)",
                    String.format(Locale.US, "%.0f mAh (%+.0f%%)",
                            report.crossCheckMah, report.divergencePct), false);
        }
        addRow(box, "测量结论", report.reliable ? "可采信" : "不采信", true);

        return box;
    }

    /** 别再把库仑计差值误标成「电流积分」——v0.1 就是这么骗到人的。 */
    private String sourceLabel() {
        switch (report.method) {
            case "counter-delta":      return "系统库仑计差值";
            case "charge-delta":       return "系统库仑计差值（充电段）";
            case "charge-integration": return "充电电流积分";
            case "coulomb-integration":
            default:                   return "电流积分";
        }
    }

    private void addRow(LinearLayout parent, String label, String value, boolean last) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(11), 0, dp(11));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(14);
        l.setTextColor(Color.rgb(105, 105, 105));
        row.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(14);
        v.setTextColor(Color.rgb(25, 25, 25));
        v.setGravity(Gravity.END);
        row.addView(v);

        parent.addView(row);

        if (!last) {
            View line = new View(this);
            line.setBackgroundColor(Color.rgb(235, 235, 233));
            parent.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
    }

    private View buildWarnings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.rgb(253, 246, 236));
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        box.setLayoutParams(lp);

        TextView header = new TextView(this);
        header.setText("这次测量有什么问题");
        header.setTextSize(13);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(Color.rgb(140, 105, 10));
        header.setPadding(0, 0, 0, dp(8));
        box.addView(header);

        for (String w : report.warnings) {
            TextView line = new TextView(this);
            line.setText("· " + w);
            line.setTextSize(13);
            line.setTextColor(Color.rgb(110, 85, 20));
            line.setLineSpacing(dp(3), 1f);
            line.setPadding(0, dp(3), 0, dp(3));
            box.addView(line);
        }
        return box;
    }

    private View buildEmpty() {
        TextView tv = new TextView(this);
        tv.setText("这份记录里没有可用的放电数据。\n\n测试期间是否一直插着充电线？");
        tv.setTextSize(14);
        tv.setTextColor(Color.rgb(120, 120, 120));
        tv.setLineSpacing(dp(6), 1f);
        tv.setPadding(0, dp(20), 0, dp(24));
        return tv;
    }

    /** 底部操作：导出 CSV / 换一份看。 */
    private View buildExportRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(30);
        row.setLayoutParams(lp);

        Button export = new Button(this);
        export.setText("导出原始数据");
        export.setOnClickListener(v -> exportCsv());
        row.addView(export, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button close = new Button(this);
        close.setText("返回");
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp2.leftMargin = dp(10);
        row.addView(close, lp2);

        return row;
    }

    private int gradeColor(double soh) {
        if (soh >= 90) return Color.rgb(29, 158, 117);
        if (soh >= 80) return Color.rgb(239, 159, 39);
        if (soh >= 70) return Color.rgb(216, 90, 48);
        return Color.rgb(226, 75, 74);
    }

    // ------------------------------------------------------------ 导出

    /**
     * 用系统文件选择器导出，不申请存储权限。
     * 把 CSV 写到用户指定的位置，而不是丢在 app 私有目录里自娱自乐。
     */
    private void exportCsv() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, csv.getName());
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            toast("这台设备没有文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_EXPORT || result != RESULT_OK || data == null) return;
        Uri target = data.getData();
        if (target == null) return;

        try (InputStream in = new FileInputStream(csv);
             OutputStream out = getContentResolver().openOutputStream(target)) {
            if (out == null) throw new IllegalStateException("无法写入目标文件");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            toast("已导出 " + csv.getName());
        } catch (Exception e) {
            toast("导出失败：" + e.getMessage());
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
