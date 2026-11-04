package com.tree0312.bhbench;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 历史记录 + 会话文件管理。
 *
 * 一次真实测量往往要跑一整天，中间还会有几段不完整的尝试。
 * 光靠"分析最新"只能看到最后一份，前面跑废的那几次既删不掉也导不出，
 * 全堆在应用目录里 —— 这个页面就是解决这个的。
 *
 * 分析是后台线程做的：一份几 MB 的 CSV，十几份一起同步读会让界面卡死。
 * 列表先用文件元信息渲染出来，摘要一行行补进去。
 */
public class HistoryActivity extends Activity {

    private static final int REQ_EXPORT_ONE = 2001;
    private static final int REQ_EXPORT_DIR = 2002;

    /** 列表的一项。 */
    private static class Item {
        File file;
        boolean selected;
        boolean analyzing = true;
        String summary = "";
        TextView summaryView;
        TextView sizeView;
    }

    private final List<Item> items = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout listBox;
    /** 配色跟随系统深浅色。 */
    private Palette pal;
    private TextView tvHead;
    private Button btnSelectAll;
    private Button btnExport;
    private Button btnDelete;

    /** 导出目标：只导一份时走 CREATE_DOCUMENT，多份走目录树。 */
    private List<Item> pendingExport = new ArrayList<>();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        pal = Palette.of(this);
        setContentView(buildUi());
        reload();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 状态栏高度。主题没开 fitsSystemWindows，标题会被状态栏压住。 */
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
        root.setPadding(dp(18), dp(14) + statusBarH(), dp(18), dp(28));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("历史记录");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(pal.textPrimary);
        root.addView(title);

        tvHead = new TextView(this);
        tvHead.setTextSize(11);
        tvHead.setTextColor(pal.textTertiary);
        tvHead.setPadding(0, dp(4), 0, dp(16));
        root.addView(tvHead);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, 0, 0, dp(12));
        root.addView(bar);

        btnSelectAll = new Button(this);
        btnSelectAll.setText("全选");
        btnSelectAll.setOnClickListener(v -> toggleSelectAll());
        bar.addView(btnSelectAll, new LinearLayout.LayoutParams(0, dp(44), 1f));

        btnExport = new Button(this);
        btnExport.setText("导出");
        btnExport.setEnabled(false);
        btnExport.setOnClickListener(v -> startExport());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.leftMargin = dp(6);
        bar.addView(btnExport, lp);

        btnDelete = new Button(this);
        btnDelete.setText("删除");
        btnDelete.setEnabled(false);
        btnDelete.setOnClickListener(v -> confirmDelete());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp2.leftMargin = dp(6);
        bar.addView(btnDelete, lp2);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

        return scroll;
    }

    // ------------------------------------------------------------ 加载

    private File sessionsDir() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        return new File(dir, "sessions");
    }

    private void reload() {
        items.clear();
        listBox.removeAllViews();

        File[] files = sessionsDir().listFiles((d, n) -> n.endsWith(".csv"));
        if (files == null || files.length == 0) {
            tvHead.setText("还没有任何记录");
            TextView empty = new TextView(this);
            empty.setText("回到主界面点「开始记录」，跑一次就有了。\n"
                    + "一份完整的放电或充电记录都算一次。");
            empty.setTextSize(14);
            empty.setTextColor(pal.textTertiary);
            empty.setLineSpacing(dp(5), 1f);
            empty.setPadding(0, dp(20), 0, 0);
            listBox.addView(empty);
            updateBar();
            return;
        }

        // 最新的排在最上面
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());

        long totalBytes = 0;
        for (File f : files) {
            totalBytes += f.length();
            items.add(new Item());
            items.get(items.size() - 1).file = f;
        }
        tvHead.setText("共 " + files.length + " 份 · 合计 "
                + sizeText(totalBytes) + "　（存在应用私有目录，卸载会一起删掉）");

        for (Item it : items) listBox.addView(buildRow(it));

        updateBar();
        analyzeAll();
    }

    private View buildRow(Item it) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(pal.surfaceAlt);
        row.setPadding(dp(10), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(8);
        row.setLayoutParams(rlp);

        CheckBox cb = new CheckBox(this);
        cb.setChecked(it.selected);
        cb.setOnCheckedChangeListener((b, checked) -> {
            it.selected = checked;
            updateBar();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(cb, clp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(4);
        row.addView(text, tlp);

        TextView name = new TextView(this);
        name.setText(timeText(it.file) + "    " + durationHint(it.file));
        name.setTextSize(15);
        name.setTextColor(pal.textPrimary);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(name);

        TextView summary = new TextView(this);
        summary.setText("读取中…");
        summary.setTextSize(12);
        summary.setTextColor(pal.textSecondary);
        summary.setPadding(0, dp(3), 0, 0);
        text.addView(summary);
        it.summaryView = summary;

        TextView size = new TextView(this);
        size.setText(sizeText(it.file.length()));
        size.setTextSize(11);
        size.setTextColor(pal.textTertiary);
        size.setPadding(0, dp(3), 0, 0);
        text.addView(size);
        it.sizeView = size;

        // 点文字区看结果；复选框单独响应，不冲突
        View.OnClickListener open = v -> openReport(it);
        text.setOnClickListener(open);

        return row;
    }

    /** 后台逐份分析，算完一份刷新一行。 */
    private void analyzeAll() {
        final List<Item> snapshot = new ArrayList<>(items);
        final double design = getSharedPreferences("bhbench", MODE_PRIVATE).getInt("design", 0);
        // 只有服务真的在写的时候才保护那个文件；停了之后它跟别的记录一样可以删
        final String recordingPath = SamplerService.running ? SamplerService.currentFile : null;

        new Thread(() -> {
            for (final Item it : snapshot) {
                String text;
                try {
                    Analyzer.Report r = Analyzer.analyze(it.file, design);
                    text = summarize(r);
                } catch (Throwable t) {
                    text = "读取失败：" + t.getClass().getSimpleName();
                }
                final String done = text;
                ui.post(() -> {
                    if (it.summaryView == null) return;
                    it.analyzing = false;
                    it.summary = done;
                    it.summaryView.setText(done);
                });
            }
            ui.post(() -> {
                // 正在写的那个文件不允许删，标出来免得用户误删活的
                for (Item it : snapshot) {
                    if (recordingPath != null
                            && it.file.getAbsolutePath().equals(recordingPath)
                            && it.summaryView != null) {
                        it.summaryView.setText(it.summary + "　（正在记录，不能删）");
                    }
                }
            });
        }).start();
    }

    private String summarize(Analyzer.Report r) {
        if (r.samples == 0) return "空文件 · 没有数据";
        String mode = r.isChargeMode() ? "充电段" : "放电段";
        String dur = durText(r.durationS);
        if (r.reliable) {
            return String.format(Locale.US, "%s · 健康度 %.1f%% · %.0f mAh · %s",
                    mode, r.sohPct(), r.normalizedMah, dur);
        }
        // 不可信也要说人话：告诉他是哪里短，不要只说"数据不足"
        String why;
        if (r.durationS < (r.isChargeMode() ? 600 : 300)) why = "时间太短";
        else if (r.isChargeMode() && r.socEnd < 95) why = "没充到 100%";
        else if (!r.isChargeMode() && r.socStart < 95) why = "不是满电开始";
        else if (!r.isChargeMode() && r.socEnd > 10) why = "没放到低位";
        else if (r.rawChargeMah < 100) why = "电荷变化太小";
        else why = "样本不达标";
        return String.format(Locale.US, "%s · %s · %.0f mAh · %s",
                mode, why, r.rawChargeMah, dur);
    }

    // ------------------------------------------------------------ 选择

    private List<Item> selected() {
        List<Item> out = new ArrayList<>();
        for (Item it : items) if (it.selected) out.add(it);
        return out;
    }

    private void toggleSelectAll() {
        boolean allOn = selected().size() == items.size() && !items.isEmpty();
        for (Item it : items) it.selected = !allOn;
        // CheckBox 没有单独保存引用，靠遍历 row 太脆；重建列表最省事也最可靠
        rerender();
    }

    /** 只重画，不重新分析（摘要已有就复用）。 */
    private void rerender() {
        listBox.removeAllViews();
        for (Item it : items) {
            View row = buildRow(it);
            listBox.addView(row);
            if (!it.summary.isEmpty()) it.summaryView.setText(it.summary);
        }
        updateBar();
    }

    private void updateBar() {
        List<Item> sel = selected();
        int n = sel.size();
        btnExport.setEnabled(n > 0);
        btnDelete.setEnabled(n > 0);
        btnExport.setText(n > 0 ? "导出 " + n + " 份" : "导出");
        btnDelete.setText(n > 0 ? "删除 " + n + " 份" : "删除");
        btnSelectAll.setText(n == items.size() && !items.isEmpty() ? "取消全选" : "全选");
    }

    // ------------------------------------------------------------ 打开

    private void openReport(Item it) {
        int design = getSharedPreferences("bhbench", MODE_PRIVATE).getInt("design", 0);
        if (design <= 0) {
            toast("主界面还没填标称容量，先填一下再分析");
            return;
        }
        Intent i = new Intent(this, ResultActivity.class);
        i.putExtra(ResultActivity.EXTRA_FILE, it.file.getAbsolutePath());
        i.putExtra(ResultActivity.EXTRA_DESIGN, (double) design);
        startActivity(i);
    }

    // ------------------------------------------------------------ 导出

    private void startExport() {
        pendingExport = selected();
        if (pendingExport.isEmpty()) return;

        if (pendingExport.size() == 1) {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/csv");
            i.putExtra(Intent.EXTRA_TITLE, pendingExport.get(0).file.getName());
            try {
                startActivityForResult(i, REQ_EXPORT_ONE);
            } catch (Exception e) {
                toast("这台设备没有文件选择器");
            }
        } else {
            // 多份就选一个文件夹，一次全写进去，不用点 N 次
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            try {
                startActivityForResult(i, REQ_EXPORT_DIR);
            } catch (Exception e) {
                toast("这台设备不支持选择文件夹");
            }
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        Uri target = data.getData();
        if (target == null) return;

        if (request == REQ_EXPORT_ONE) {
            if (pendingExport.isEmpty()) return;
            File f = pendingExport.get(0).file;
            boolean ok = writeTo(target, f);
            toast(ok ? "已导出 " + f.getName() : "导出失败");
        } else if (request == REQ_EXPORT_DIR) {
            int ok = 0, fail = 0;
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                    target, DocumentsContract.getTreeDocumentId(target));
            for (Item it : pendingExport) {
                Uri doc;
                try {
                    doc = DocumentsContract.createDocument(
                            getContentResolver(), parent, "text/csv", it.file.getName());
                } catch (Exception e) {
                    doc = null;
                }
                if (doc != null && writeTo(doc, it.file)) ok++;
                else fail++;
            }
            toast("导出 " + ok + " 份" + (fail > 0 ? "，失败 " + fail : ""));
        }
    }

    private boolean writeTo(Uri target, File src) {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = getContentResolver().openOutputStream(target)) {
            if (out == null) return false;
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------ 删除

    private void confirmDelete() {
        final List<Item> sel = selected();
        if (sel.isEmpty()) return;

        StringBuilder names = new StringBuilder();
        for (int i = 0; i < sel.size() && i < 5; i++) {
            names.append("\n· ").append(timeText(sel.get(i).file));
        }
        if (sel.size() > 5) names.append("\n· …还有 ").append(sel.size() - 5).append(" 份");

        new AlertDialog.Builder(this)
                .setTitle("删除 " + sel.size() + " 份记录？")
                .setMessage(names.toString() + "\n\n删掉就找不回来了，想留的先导出。")
                .setPositiveButton("删除", (d, w) -> doDelete(sel))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doDelete(List<Item> sel) {
        String recording = SamplerService.running ? SamplerService.currentFile : null;
        int ok = 0, skipped = 0;
        for (Item it : sel) {
            if (recording != null && it.file.getAbsolutePath().equals(recording)) {
                skipped++;
                continue;
            }
            if (it.file.delete()) ok++;
        }
        if (skipped > 0) toast("正在记录的这份没删");
        else toast("已删除 " + ok + " 份");
        reload();
    }

    // ------------------------------------------------------------ 格式化

    private static String timeText(File f) {
        // session_yyyyMMdd_HHmmss.csv
        String n = f.getName();
        try {
            String raw = n.substring(8, n.length() - 4);
            return raw.substring(0, 4) + "-" + raw.substring(4, 6) + "-" + raw.substring(6, 8)
                    + "  " + raw.substring(9, 11) + ":" + raw.substring(11, 13);
        } catch (Exception e) {
            return new SimpleDateFormat("MM-dd HH:mm", Locale.US)
                    .format(new Date(f.lastModified()));
        }
    }

    /** 采样间隔 5 秒，粗略用文件大小估时长太重，这行只放"多久以前"。 */
    private static String durationHint(File f) {
        long ageMin = (System.currentTimeMillis() - f.lastModified()) / 60000L;
        if (ageMin < 1) return "刚刚";
        if (ageMin < 60) return ageMin + " 分钟前";
        long h = ageMin / 60;
        if (h < 24) return h + " 小时前";
        return (h / 24) + " 天前";
    }

    private static String durText(double sec) {
        long s = Math.round(sec);
        long h = s / 3600, m = (s % 3600) / 60;
        if (h > 0) return h + "h" + m + "m";
        return m + "m";
    }

    private static String sizeText(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        return Math.max(1, bytes / 1024) + " KB";
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
