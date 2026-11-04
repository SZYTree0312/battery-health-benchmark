package com.tree0312.bhbench;

import static com.tree0312.bhbench.Ui.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
 * 充电器档案 —— v0.5.2。
 *
 * 体检页里原来只有一个「测过的充电器」小列表，能看不能管。这里做成完整的
 * 历史：按充电器名字聚合对比（同一台手机换着充，数字才可比）、改名、删除、
 * 清空、导出 CSV。
 *
 * 数据只存结论（峰值/稳态/档位/时长），不存 90 秒曲线 —— 曲线本身在体检页
 * 实时画完就丢，SharedPreferences 存几千个点是给自己找麻烦。最多留 12 条。
 */
public class ChargerHistoryActivity extends Activity {

    private static final int REQ_EXPORT = 21;

    private Palette pal;
    private TextView head;
    private LinearLayout compareBox;
    private LinearLayout listBox;

    @Override protected void onCreate(Bundle saved) {
        ThemeMode.apply(this);
        super.onCreate(saved);
        pal = Palette.of(this);
        setContentView(buildUi());
        Ui.applySystemBars(this, pal);
        reload();
    }

    @Override protected void onResume() {
        super.onResume();
        reload();
    }

    private int dp(int v) { return Ui.dp(this, v); }

    private int statusBarH() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(pal.bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16) + statusBarH(), dp(20), dp(S6));
        scroll.addView(root);

        root.addView(Ui.title(this, pal, "充电器档案"));
        root.addView(Ui.subtitle(this, pal,
                "同一台手机换着充电器测，数字才可比。存的是电池实际吃进去的功率，不是充电器标称。"));

        head = new TextView(this);
        head.setTextSize(12);
        head.setTextColor(pal.textTertiary);
        head.setPadding(0, dp(S3), 0, 0);
        root.addView(head);

        root.addView(Ui.section(this, pal, "功率对比"));
        compareBox = Ui.card(this, pal);
        root.addView(compareBox);

        root.addView(Ui.section(this, pal, "全部记录"));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

        LinearLayout actions = Ui.buttonRow(this, S2,
                Ui.secondary(this, pal, "导出 CSV"),
                Ui.secondary(this, pal, "清空档案"));
        actions.getChildAt(0).setOnClickListener(v -> doExport());
        actions.getChildAt(1).setOnClickListener(v -> doClear());
        actions.setPadding(0, dp(S5), 0, 0);
        root.addView(actions);

        TextView hint = new TextView(this);
        hint.setText("最多保留最近 12 次。记录存在应用私有目录，卸载会一起消失，要留的先导出。");
        hint.setTextSize(11);
        hint.setTextColor(pal.textTertiary);
        hint.setLineSpacing(dp(3), 1f);
        hint.setPadding(0, dp(S3), 0, 0);
        root.addView(hint);

        return scroll;
    }

    // ------------------------------------------------------------ 渲染

    private void reload() {
        List<ChargerStore.Entry> all = ChargerStore.load(this);
        head.setText(all.isEmpty()
                ? "还没有记录。去「充电器体检」跑一次 90 秒就能存一条。"
                : String.format(Locale.US, "共 %d 次体检 · %d 个充电器",
                        all.size(), group(all).size()));

        renderCompare(all);
        renderList(all);
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
            g.lastTier = e.tier;
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
        String lastTier = "";
    }

    private void renderCompare(List<ChargerStore.Entry> all) {
        compareBox.removeAllViews();
        Map<String, Group> groups = group(all);
        if (groups.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无数据");
            empty.setTextSize(13);
            empty.setTextColor(pal.textTertiary);
            compareBox.addView(empty);
            return;
        }

        double maxW = 1;
        for (Group g : groups.values()) maxW = Math.max(maxW, g.peak);

        // 图例
        LinearLayout legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        legend.addView(legendItem("峰值", pal.accent));
        legend.addView(legendItem("稳态", pal.chartCounter));
        compareBox.addView(legend);

        boolean first = true;
        for (Group g : groups.values()) {
            if (!first) {
                View line = new View(this);
                line.setBackgroundColor(pal.border);
                compareBox.addView(line, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)) {{
                    topMargin = dp(S3);
                    bottomMargin = dp(S3);
                }});
            }
            first = false;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView name = new TextView(this);
            name.setText(g.name + (g.count > 1 ? "  ×" + g.count : ""));
            name.setTextSize(13);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(pal.textPrimary);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView val = new TextView(this);
            val.setText(String.format(Locale.US, "%.1f / %.1f W", g.peak, g.steady));
            val.setTextSize(12);
            val.setTextColor(pal.textSecondary);
            row.addView(val);
            compareBox.addView(row);

            compareBox.addView(bar(g.peak, maxW, pal.accent));
            compareBox.addView(bar(g.steady, maxW, pal.chartCounter));
        }

        TextView note = new TextView(this);
        note.setText("峰值看冲不冲得上去，稳态看压不压得住。两条差得越远，越是「冲一下就掉队」。");
        note.setTextSize(11);
        note.setTextColor(pal.textTertiary);
        note.setLineSpacing(dp(3), 1f);
        note.setPadding(0, dp(S3), 0, 0);
        compareBox.addView(note);
    }

    private View legendItem(String text, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(0, 0, dp(S4), dp(S2));
        View dot = new View(this);
        dot.setBackgroundColor(color);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(12), dp(8));
        dlp.gravity = Gravity.CENTER_VERTICAL;
        dlp.rightMargin = dp(6);
        box.addView(dot, dlp);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(pal.textSecondary);
        box.addView(tv);
        return box;
    }

    /** 用 LinearLayout weight 画横条，不用 Canvas，深色浅色都跟着色板走。 */
    private View bar(double value, double maxW, int color) {
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(7));
        tlp.topMargin = dp(3);
        track.setLayoutParams(tlp);

        float w = (float) Math.max(0.02, Math.min(1.0, value / maxW));
        View fill = new View(this);
        fill.setBackground(Ui.rounded(this, color, 999f));
        track.addView(fill, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, w));
        View rest = new View(this);
        rest.setBackground(Ui.rounded(this, pal.border, 999f));
        track.addView(rest, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0f, 1f - w)));
        return track;
    }

    private void renderList(List<ChargerStore.Entry> all) {
        listBox.removeAllViews();
        if (all.isEmpty()) {
            LinearLayout empty = Ui.card(this, pal);
            TextView tv = new TextView(this);
            tv.setText("还没有体检记录。\n\n插上充电器，去体检页跑 90 秒，回来点「保存这次结果」。");
            tv.setTextSize(13);
            tv.setTextColor(pal.textTertiary);
            tv.setLineSpacing(dp(4), 1f);
            empty.addView(tv);
            listBox.addView(empty);
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        for (ChargerStore.Entry e : all) {
            LinearLayout card = Ui.card(this, pal);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = dp(S2);
            card.setLayoutParams(clp);

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            TextView name = new TextView(this);
            name.setText(e.name);
            name.setTextSize(15);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(pal.textPrimary);
            top.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView time = new TextView(this);
            time.setText(fmt.format(new Date(e.ts)));
            time.setTextSize(11);
            time.setTextColor(pal.textTertiary);
            top.addView(time);
            card.addView(top);

            if (e.tier != null && !e.tier.isEmpty()) {
                String tier = e.tier.length() > 18 ? e.tier.substring(0, 18) + "…" : e.tier;
                LinearLayout chipRow = new LinearLayout(this);
                chipRow.setPadding(0, dp(S2), 0, 0);
                chipRow.addView(Ui.chip(this, pal, tier));
                card.addView(chipRow);
            }

            double keep = e.peakW > 0 ? e.steadyW / e.peakW * 100 : 0;
            TextView nums = new TextView(this);
            nums.setText(String.format(Locale.US,
                    "峰值 %.1f W   稳态 %.1f W   保持 %.0f%%   %d 秒",
                    e.peakW, e.steadyW, keep, e.durationS));
            nums.setTextSize(12);
            nums.setTextColor(pal.textSecondary);
            nums.setPadding(0, dp(S3), 0, 0);
            card.addView(nums);

            LinearLayout ops = new LinearLayout(this);
            ops.setOrientation(LinearLayout.HORIZONTAL);
            ops.setPadding(0, dp(S3), 0, 0);
            ops.addView(action("重命名", pal.accent, v -> showRename(e)));
            ops.addView(action("删除", pal.bad, v -> confirmDelete(e)));
            card.addView(ops);

            listBox.addView(card);
        }
    }

    private TextView action(String text, int color, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(color);
        tv.setPadding(0, dp(S1), dp(S5), dp(S1));
        tv.setOnClickListener(l);
        return tv;
    }

    // ------------------------------------------------------------ 操作

    private void showRename(ChargerStore.Entry e) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(e.name);
        input.setSelection(input.getText().length());
        input.setPadding(dp(S4), dp(S3), dp(S4), dp(S3));
        new AlertDialog.Builder(this)
                .setTitle("给这次记录起个名字")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    ChargerStore.rename(this, e.ts, input.getText().toString());
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(ChargerStore.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle("删除这条记录")
                .setMessage(e.name + "\n峰值 " + String.format(Locale.US, "%.1f W", e.peakW))
                .setPositiveButton("删除", (d, w) -> {
                    ChargerStore.remove(this, e.ts);
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doClear() {
        int n = ChargerStore.load(this).size();
        if (n == 0) { toast("档案本来就是空的"); return; }
        new AlertDialog.Builder(this)
                .setTitle("清空充电器档案")
                .setMessage("将删除全部 " + n + " 条记录，不可恢复。\n要留的先导出 CSV。")
                .setPositiveButton("清空", (d, w) -> {
                    ChargerStore.clear(this);
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doExport() {
        if (ChargerStore.load(this).isEmpty()) { toast("没有记录可导出"); return; }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "charger_probes_"
                + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date()) + ".csv");
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (Exception ex) {
            toast("这台设备没有文件保存界面");
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_EXPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IllegalStateException("no stream");
            os.write(ChargerStore.toCsv(this).getBytes(StandardCharsets.UTF_8));
            toast("已导出 " + ChargerStore.load(this).size() + " 条记录");
        } catch (Exception ex) {
            toast("导出失败：" + ex.getMessage());
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
