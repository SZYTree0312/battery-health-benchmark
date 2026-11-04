package com.tree0312.bhbench;

import android.content.Context;
import android.graphics.Color;

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

    // 图表的专用色
    public final int chartFit;      // 拟合虚线
    public final int chartCounter;  // 库仑计反推 / 功率线
    public final int chartGrid;
    public final int chartAxis;
    public final int chartLabel;
    public final int chartGap;      // 空档带

    public static Palette of(Context c) {
        return new Palette(ThemeMode.isDark(c));
    }

    private Palette(boolean dark) {
        this.dark = dark;
        if (dark) {
            bg = Color.rgb(16, 16, 16);
            surface = Color.rgb(28, 28, 28);
            surfaceAlt = Color.rgb(36, 36, 36);
            textPrimary = Color.rgb(232, 232, 232);
            textSecondary = Color.rgb(168, 168, 168);
            textTertiary = Color.rgb(122, 122, 122);
            border = Color.rgb(46, 46, 46);

            accent = Color.rgb(111, 163, 240);
            good = Color.rgb(78, 203, 148);
            warn = Color.rgb(224, 169, 74);
            bad = Color.rgb(232, 112, 112);
            warnSurface = Color.rgb(42, 34, 22);

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

            accent = Color.rgb(38, 84, 190);
            good = Color.rgb(29, 158, 117);
            warn = Color.rgb(180, 110, 30);
            bad = Color.rgb(208, 78, 78);
            warnSurface = Color.rgb(253, 246, 236);

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
