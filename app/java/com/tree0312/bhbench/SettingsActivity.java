package com.tree0312.bhbench;

import static com.tree0312.bhbench.Ui.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

/**
 * 设置页。
 *
 * v0.5.3 之前，显示模式按钮、设备信息都长在主页面上，和「开始记录」挤在同一屏。
 * 这些东西一个月也未必碰一次，却占着最贵的屏幕位置，还把主流程搅乱。
 * 统一收到这里：显示模式、数据入口、版本与设备信息。
 *
 * 主题切换后要清掉整个 task 栈重开主界面 —— 只 recreate() 的话，返回栈里
 * 已经建好的结果页/历史页仍是旧配色，按返回键就会看到半套主题。
 */
public class SettingsActivity extends Activity {

    private Palette pal;

    @Override protected void onCreate(Bundle saved) {
        ThemeMode.apply(this);
        super.onCreate(saved);
        pal = Palette.of(this);
        setContentView(buildUi());
        Ui.applySystemBars(this, pal);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

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
        root.setPadding(dp(20), dp(14) + statusBarH(), dp(20), dp(S6));
        scroll.addView(root);

        // ---- 页头：返回箭头 + 标题（无 ActionBar，自己画）
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
        head.addView(Ui.title(this, pal, "设置"));

        // ---- 显示
        root.addView(Ui.section(this, pal, "显示"));
        LinearLayout displayCard = Ui.card(this, pal);
        Ui.NavRow themeRow = Ui.navRow(this, pal, "显示模式",
                ThemeMode.label(ThemeMode.get(this)));
        themeRow.view.setOnClickListener(v -> showThemeDialog(themeRow));
        displayCard.addView(themeRow.view);
        root.addView(displayCard);

        // ---- 数据
        root.addView(Ui.section(this, pal, "数据"));
        LinearLayout dataCard = Ui.card(this, pal);

        Ui.NavRow sessions = Ui.navRow(this, pal, "测量记录", sessionCount() + " 条");
        sessions.view.setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));
        dataCard.addView(sessions.view);
        root.addView(dataCard);

        // ---- 关于
        root.addView(Ui.section(this, pal, "关于"));
        LinearLayout about = Ui.card(this, pal);
        addInfo(about, "版本", versionName(), true);
        addInfo(about, "设备", Build.MANUFACTURER + " " + Build.MODEL, false);
        addInfo(about, "系统", "Android " + Build.VERSION.RELEASE
                + "（API " + Build.VERSION.SDK_INT + "）", false);
        root.addView(about);

        TextView note = new TextView(this);
        note.setText("容量测量靠库仑计首尾差算实际电荷量，不用电流积分 —— "
                + "息屏后系统会拉长采样间隔，积分会漏掉大段时间。\n"
                + "充电器体检只能读电池侧的电压电流，手机读不到 USB 输入侧的 VBUS/IBUS，"
                + "所以协议只给区间和置信度，不给名字。");
        note.setTextSize(11);
        note.setTextColor(pal.textTertiary);
        note.setLineSpacing(dp(3), 1f);
        note.setPadding(dp(S2), dp(S3), dp(S2), 0);
        root.addView(note);

        TextView repo = new TextView(this);
        repo.setText("github.com/SZYTree0312/battery-health-benchmark");
        repo.setTextSize(12);
        repo.setTextColor(pal.accent);
        repo.setPadding(dp(S2), dp(S4), dp(S2), 0);
        repo.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://github.com/SZYTree0312/battery-health-benchmark")));
            } catch (Exception ignored) {
                // 没装浏览器就算了
            }
        });
        root.addView(repo);

        TextView sig = new TextView(this);
        sig.setText("Tree0312 · MIT License");
        sig.setTextSize(11);
        sig.setTextColor(pal.textTertiary);
        sig.setPadding(dp(S2), dp(S2), dp(S2), 0);
        root.addView(sig);

        return scroll;
    }

    private void addInfo(LinearLayout card, String label, String value, boolean firstRow) {
        if (!firstRow) card.addView(Ui.divider(this, pal));
        // 关于区的行不带箭头，用普通 PrefRow 表示「只读」
        Ui.PrefRow r = Ui.prefRow(this, pal, label);
        r.value.setTextSize(13);
        r.value.setText(value);
        card.addView(r.view);
    }

    private int sessionCount() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        File[] files = new File(dir, "sessions")
                .listFiles((d, name) -> name.endsWith(".csv"));
        return files == null ? 0 : files.length;
    }

    /**
     * 只显示 versionName。versionCode 是构建序号（现在是 9），摆出来像「第 9 个提交
     * 版本」，容易被当成仓库提交数 —— 树当场否了，别再加回去。
     */
    private String versionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName == null ? "—" : pi.versionName;
        } catch (Exception e) {
            return "—";
        }
    }

    /**
     * 三态切换。选完清空整个 task 栈重开主界面 —— 光 recreate() 的话，
     * 后退栈里已经建好的结果页/历史页还留着旧主题，按返回键就会看到半套配色。
     * 前台采样服务不受 Activity 栈影响，不会被打断。
     */
    private void showThemeDialog(Ui.NavRow row) {
        final String[] labels = {"跟随系统", "浅色", "深色"};
        final int[] modes = {ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK};
        int cur = ThemeMode.get(this);
        int checked = cur == ThemeMode.LIGHT ? 1 : (cur == ThemeMode.DARK ? 2 : 0);

        new AlertDialog.Builder(this)
                .setTitle("显示模式")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    ThemeMode.set(this, modes[which]);
                    d.dismiss();
                    row.value.setText(ThemeMode.label(modes[which]));
                    Intent i = new Intent(this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
