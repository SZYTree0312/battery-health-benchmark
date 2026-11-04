package com.tree0312.bhbench;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;

/**
 * 统一色板 —— 深色/浅色，支持用户手动覆盖系统设置。
 *
 * 为什么不用 XML 主题把颜色定义好：这个 App 的界面全是代码画的，没有布局文件，
 * 颜色散在四个 Activity 和两个自定义 View 里。用一套 Java 常量集中定义，
 * 改配色就改这一个文件，比在 XML 和代码之间来回对要省事。
 *
 * 深浅由 ThemeMode.isDark() 决定：用户选「跟随系统」时读 uiMode 的 NIGHT 位，
 * 选「浅色」/「深色」时直接按用户的选择。切换后 Activity 会重建，这里重新取值。
 */
public final class Palette {

    public final boolean dark;

    /** 页面底色。 */
    public final int bg;
    /** 卡片/面板底色，比 bg 稍微浮起来一点。 */
    public final int surface;
    /** 次级面板，用于指标卡这类再深一层的容器。 */
    public final int surfaceAlt;
    /** 主文字。 */
    public final int textPrimary;
    /** 次级文字：说明、标签。 */
    public final int textSecondary;
    /** 三级文字：脚注、单位。 */
    public final int textTertiary;
    /** 分割线。 */
    public final int border;

    /** 主色：图表主线、重点数字。 */
    public final int accent;
    /** 正常/健康。 */
    public final int good;
    /** 提醒（数据不足、注意）。 */
    public final int warn;
    /** 异常/错误。 */
    public final int bad;
    /** 提醒区块的底色（结果页的"这次测量有什么问题"）。 */
    public final int warnSurface;

    // v0.5.2 组件化 UI 用的补充色
    /** 强调色按钮上的文字。深色下 accent 变亮，文字要反过来用深色。 */
    public final int onAccent;
    /** 次级按钮背景。 */
    public final int buttonSecondary;
    /** 禁用态按钮背景。 */
    public final int buttonDisabled;
    /** 淡强调底色，用于标签 / chip / 选中行。 */
    public final int accentSoft;
    /** 涟漪反馈色。 */
    public final int ripple;

    // 图表的专用色
    public final int chartFit;      // 拟合虚线
    public final int chartCounter;  // 库仑计反推 / 功率线
    public final int chartGrid;
    public final int chartAxis;
    public final int chartLabel;
    public final int chartGap;      // 空档带

    public static Palette of(Context c) {
        return new Palette(c, ThemeMode.isDark(c));
    }

    /**
     * 系统强调色。各家 ROM 都会覆盖 frameworks 的 colorAccent（MIUI / ColorOS /
     * 原生 Android 的主题色各不相同），取它而不是自己定一个蓝，App 才会像
     * 「这台系统的应用」而不是套了一层自制皮肤。取不到才回退到内置色。
     */
    private static int systemAccent(Context c, int fallback) {
        try {
            TypedValue tv = new TypedValue();
            if (c.getTheme().resolveAttribute(android.R.attr.colorAccent, tv, true)
                    && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /** accent 上放文字时的自动对比色：亮底用深字，暗底用白字。 */
    private static int onAccentOf(int accent) {
        return Color.luminance(accent) > 0.45
                ? Color.rgb(16, 18, 22) : Color.rgb(250, 250, 250);
    }

    /** 把强调色按比例混到背景上，得到淡标签底 —— 不写死某一种蓝。 */
    private static int soften(int fg, int bg, float alpha) {
        return Color.rgb(
                (int) (Color.red(fg) * alpha + Color.red(bg) * (1 - alpha)),
                (int) (Color.green(fg) * alpha + Color.green(bg) * (1 - alpha)),
                (int) (Color.blue(fg) * alpha + Color.blue(bg) * (1 - alpha)));
    }

    private Palette(Context c, boolean dark) {
        this.dark = dark;
        if (dark) {
            bg = Color.rgb(16, 16, 16);
            surface = Color.rgb(28, 28, 28);
            surfaceAlt = Color.rgb(36, 36, 36);
            textPrimary = Color.rgb(232, 232, 232);
            textSecondary = Color.rgb(168, 168, 168);
            textTertiary = Color.rgb(122, 122, 122);
            border = Color.rgb(46, 46, 46);

            accent = systemAccent(c, Color.rgb(111, 163, 240));
            good = Color.rgb(78, 203, 148);
            warn = Color.rgb(224, 169, 74);
            bad = Color.rgb(232, 112, 112);
            warnSurface = Color.rgb(42, 34, 22);

            onAccent = onAccentOf(accent);
            buttonSecondary = Color.rgb(40, 42, 48);
            buttonDisabled = Color.rgb(38, 38, 38);
            accentSoft = soften(accent, Color.rgb(16, 16, 16), 0.18f);
            ripple = Color.argb(48, 255, 255, 255);

            chartFit = Color.rgb(138, 138, 138);
            chartCounter = Color.rgb(224, 169, 74);
            chartGrid = Color.rgb(42, 42, 42);
            chartAxis = Color.rgb(58, 58, 58);
            chartLabel = Color.rgb(138, 138, 138);
            chartGap = Color.rgb(30, 30, 30);
        } else {
            bg = Color.WHITE;
            surface = Color.rgb(246, 246, 244);
            surfaceAlt = Color.rgb(249, 249, 247);
            textPrimary = Color.rgb(24, 24, 24);
            textSecondary = Color.rgb(100, 100, 100);
            textTertiary = Color.rgb(140, 140, 140);
            border = Color.rgb(236, 236, 234);

            accent = systemAccent(c, Color.rgb(38, 84, 190));
            good = Color.rgb(29, 158, 117);
            warn = Color.rgb(180, 110, 30);
            bad = Color.rgb(208, 78, 78);
            warnSurface = Color.rgb(253, 246, 236);

            onAccent = onAccentOf(accent);
            buttonSecondary = Color.rgb(236, 239, 246);
            buttonDisabled = Color.rgb(232, 233, 236);
            accentSoft = soften(accent, Color.WHITE, 0.10f);
            ripple = Color.argb(36, 20, 40, 90);

            chartFit = Color.rgb(150, 150, 150);
            chartCounter = Color.rgb(214, 130, 36);
            chartGrid = Color.rgb(232, 232, 230);
            chartAxis = Color.rgb(190, 190, 188);
            chartLabel = Color.rgb(130, 130, 130);
            chartGap = Color.rgb(238, 238, 236);
        }
    }

    /** 给图表用的半透明填充色（面积图）。深色下透明度要低一点，否则发灰。 */
    public int fillOf(int color, int alphaLight, int alphaDark) {
        return Color.argb(dark ? alphaDark : alphaLight,
                Color.red(color), Color.green(color), Color.blue(color));
    }
}
