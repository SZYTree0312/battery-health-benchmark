package com.tree0312.bhbench;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v0.5.2 的统一界面组件。
 *
 * 之前四个页面各画各的：系统默认灰色方角 Button、一整块没有层次的灰矩形、
 * 全靠字号区分层级。功能没毛病，但看着像毛坯房。这里把「圆角卡片 / 主次按钮 /
 * 分区标题 / 状态栏」收成一套组件，四个页面共用，配色统一从 Palette 取。
 *
 * 仍然是纯代码 View、零第三方依赖 —— 手工 aapt2 + javac + d8 的打包方式不变。
 * 不引 Material Components / AndroidX，RippleDrawable 和 GradientDrawable
 * 都是平台自带的（API 21+/1+，minSdk 26 够用）。
 */
public final class Ui {

    private Ui() {}

    // 间距体系：所有页面只用这几档，别再各写各的 3/7/13
    public static final int S1 = 4;
    public static final int S2 = 8;
    public static final int S3 = 12;
    public static final int S4 = 16;
    public static final int S5 = 24;
    public static final int S6 = 32;

    public static final float CARD_RADIUS_DP = 18f;
    public static final float BUTTON_RADIUS_DP = 13f;
    public static final float CHIP_RADIUS_DP = 999f;

    public static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** 圆角色块。卡片、按钮底、标签底都用它。 */
    public static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(Math.max(1f, radiusDp * c.getResources().getDisplayMetrics().density));
        return g;
    }

    /** 带涟漪的圆角色块，给自绘图标按钮用。 */
    public static Drawable roundRipple(Context c, Palette p, int color, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(p.ripple),
                rounded(c, color, radiusDp), rounded(c, Color.WHITE, radiusDp));
    }

    /** 带涟漪、带禁用态的圆角按钮背景。 */
    private static Drawable buttonBg(Context c, Palette p, boolean primary) {
        float r = BUTTON_RADIUS_DP;
        GradientDrawable normal = rounded(c, primary ? p.accent : p.buttonSecondary, r);
        GradientDrawable mask = rounded(c, Color.WHITE, r);
        RippleDrawable enabled = new RippleDrawable(
                ColorStateList.valueOf(p.ripple), normal, mask);

        StateListDrawable out = new StateListDrawable();
        out.addState(new int[]{-android.R.attr.state_enabled},
                rounded(c, p.buttonDisabled, r));
        out.addState(new int[]{}, enabled);
        return out;
    }

    /** 主按钮：强调色实心，一页里只放一个最重要的动作。 */
    public static Button primary(Activity a, Palette p, String text) {
        return button(a, p, text, true);
    }

    /** 次按钮：低对比底色，和主按钮并排时主次一眼可分。 */
    public static Button secondary(Activity a, Palette p, String text) {
        return button(a, p, text, false);
    }

    private static Button button(Activity a, Palette p, String text, boolean primary) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setSingleLine(true);
        b.setMinHeight(dp(a, 50));
        b.setMinimumHeight(dp(a, 50));
        b.setPadding(dp(a, S3), 0, dp(a, S3), 0);
        b.setBackground(buttonBg(a, p, primary));
        // 系统 Button 默认带 StateListAnimator，按下会抬升并投出方形阴影，
        // 和圆角背景叠在一起很难看
        b.setStateListAnimator(null);
        int enabledColor = primary ? p.onAccent : p.textPrimary;
        b.setTextColor(new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{}
                },
                new int[]{p.textTertiary, enabledColor}));
        return b;
    }

    /** 分区小标题：小号、三级色、上方留白，替代原来满屏同字号的正文。 */
    public static TextView section(Context c, Palette p, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        // 系统设置页的分区标题是次级文字色，不是强调色；蓝色小标题一眼就像自制皮肤
        tv.setTextColor(p.textSecondary);
        tv.setLetterSpacing(0.04f);
        tv.setPadding(0, dp(c, S5), 0, dp(c, S2));
        return tv;
    }

    /** 页面大标题。 */
    public static TextView title(Context c, Palette p, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(26);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(p.textPrimary);
        tv.setLetterSpacing(-0.01f);
        return tv;
    }

    /** 标题下面的副标题。 */
    public static TextView subtitle(Context c, Palette p, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(p.textSecondary);
        tv.setLineSpacing(dp(c, 3), 1f);
        tv.setPadding(0, dp(c, S1), 0, 0);
        return tv;
    }

    /** 小圆角标签（状态、方法名、档位）。 */
    public static TextView chip(Context c, Palette p, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(p.accent);
        tv.setPadding(dp(c, 10), dp(c, 5), dp(c, 10), dp(c, 5));
        tv.setBackground(rounded(c, p.accentSoft, CHIP_RADIUS_DP));
        return tv;
    }

    /** 卡片容器：圆角 + 统一内边距，调用方只管往里加内容。 */
    public static LinearLayout card(Context c, Palette p) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, S4), dp(c, S4), dp(c, S4), dp(c, S4));
        box.setBackground(rounded(c, p.surface, CARD_RADIUS_DP));
        return box;
    }

    /** 系统设置式的一行：左边标签，右边数值。调用方持有 value 直接刷新。 */
    public static class PrefRow {
        public final LinearLayout view;
        public final TextView value;

        PrefRow(LinearLayout v, TextView value) {
            this.view = v;
            this.value = value;
        }
    }

    public static PrefRow prefRow(Context c, Palette p, String label) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(c, S3), 0, dp(c, S3));

        TextView l = new TextView(c);
        l.setText(label);
        l.setTextSize(13);
        l.setTextColor(p.textSecondary);
        row.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(c);
        v.setTextSize(14);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(p.textPrimary);
        v.setGravity(Gravity.END);
        row.addView(v);
        return new PrefRow(row, v);
    }

    /** 行间细分割线。 */
    public static View divider(Context c, Palette p) {
        View line = new View(c);
        line.setBackgroundColor(p.border);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1) / 2)));
        return line;
    }

    /** 可点的设置式行：左标签、右数值、尾随箭头。 */
    public static class NavRow {
        public final LinearLayout view;
        public final TextView value;

        NavRow(LinearLayout v, TextView value) {
            this.view = v;
            this.value = value;
        }
    }

    public static NavRow navRow(Context c, Palette p, String label, String value) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, S3), 0, dp(c, S3));

        TextView l = new TextView(c);
        l.setText(label);
        l.setTextSize(14);
        l.setTextColor(p.textPrimary);
        row.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(13);
        v.setTextColor(p.textSecondary);
        v.setGravity(Gravity.END);
        row.addView(v);

        TextView arrow = new TextView(c);
        arrow.setText("›");
        arrow.setTextSize(18);
        arrow.setTextColor(p.textTertiary);
        arrow.setPadding(dp(c, S2), 0, 0, 0);
        row.addView(arrow);
        return new NavRow(row, v);
    }

    /** 分段控件：两项或三项，选中项浮起一层底色。用于页面内的功能切换。 */
    public interface OnSegment {
        void onSegment(int index);
    }

    public static final class Segmented {
        public final LinearLayout view;
        private final TextView[] items;
        private final Palette p;
        private final Context ctx;
        private int selected;

        Segmented(Context c, Palette p, LinearLayout v, TextView[] items, int sel) {
            this.ctx = c;
            this.p = p;
            this.view = v;
            this.items = items;
            this.selected = sel;
        }

        public int selected() {
            return selected;
        }

        /** 只改外观，不触发回调 —— 宿主自己控制流程时用。 */
        public void setSelected(int index) {
            if (index < 0 || index >= items.length) return;
            selected = index;
            for (int i = 0; i < items.length; i++) {
                boolean on = i == index;
                items[i].setBackground(on
                        ? rounded(ctx, p.surface, 10f) : null);
                items[i].setTextColor(on ? p.accent : p.textSecondary);
                items[i].setTypeface(Typeface.DEFAULT_BOLD);
            }
        }
    }

    public static Segmented segmented(Context c, Palette p, String[] labels,
                                      int selected, OnSegment cb) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dp(c, S1) / 2 + dp(c, 2);
        box.setPadding(pad, pad, pad, pad);
        box.setBackground(rounded(c, p.buttonSecondary, 12f));

        TextView[] items = new TextView[labels.length];
        int h = dp(c, 38);
        for (int i = 0; i < labels.length; i++) {
            TextView t = new TextView(c);
            t.setText(labels[i]);
            t.setTextSize(13);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, h, 1f);
            box.addView(t, lp);
            items[i] = t;
        }

        Segmented seg = new Segmented(c, p, box, items, selected);
        seg.setSelected(selected);
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            items[i].setOnClickListener(v -> {
                if (seg.selected == idx) return;
                seg.setSelected(idx);
                if (cb != null) cb.onSegment(idx);
            });
        }
        return seg;
    }

    /** 一行里等分的两个/三个按钮。 */
    public static LinearLayout buttonRow(Context c, int gapDp, View... children) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(c, S3), 0, 0);
        for (int i = 0; i < children.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(c, gapDp);
            row.addView(children[i], lp);
        }
        return row;
    }

    /**
     * 状态栏 / 导航栏跟随色板。
     *
     * 0.5.1 只靠 XML 的 windowLightStatusBar，在这台 MIUI 上强制浅色时
     * 状态栏仍是灰底白图标。这里显式再设一遍颜色和图标明暗，
     * 系统夜间模式开着但 App 强制浅色的场景也能对。
     */
    public static void applySystemBars(Activity a, Palette p) {
        Window w = a.getWindow();
        w.setStatusBarColor(p.bg);
        w.setNavigationBarColor(p.bg);
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (Build.VERSION.SDK_INT >= 23) {
            if (p.dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            if (p.dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }
}
