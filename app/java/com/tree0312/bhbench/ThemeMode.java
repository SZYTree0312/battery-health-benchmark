package com.tree0312.bhbench;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * 显示模式：跟随系统 / 强制浅色 / 强制深色。
 *
 * 为什么需要这一层：v0.5 的深色模式只读系统 uiMode，用户没法在 App 里覆盖。
 * 系统夜间模式是自动的、按时间段切换，测电池往往一跑十几个小时，中途配色
 * 自己变了很干扰；也有人就是想在白天用深色。所以要给一个手动开关。
 *
 * 实现上有两个互不相干的通道，必须同时改，少一个就会出现「文字深色、底色浅色」
 * 之类的半吊子状态：
 *   1. Java 画的界面（四个 Activity + 两个 Chart View）走 Palette.of()，
 *      由这里的 isDark() 决定色板；
 *   2. XML 主题管窗口底色、状态栏图标颜色、系统 AlertDialog，
 *      由 apply() 在 super.onCreate() 之前 setTheme() 决定。
 *
 * 跟随系统时 apply() 什么都不做，让 values-night/ 的同名 style 自然生效；
 * 强制模式才显式指定 style —— 因为用户完全可能在系统白天时强制深色，
 * 这时候 -night 资源限定符帮不上忙。
 */
public final class ThemeMode {

    public static final int SYSTEM = 0;
    public static final int LIGHT = 1;
    public static final int DARK = 2;

    private static final String PREF = "bhbench";
    private static final String KEY = "theme_mode";

    private ThemeMode() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static int get(Context c) {
        int v = prefs(c).getInt(KEY, SYSTEM);
        return (v == LIGHT || v == DARK) ? v : SYSTEM;
    }

    public static void set(Context c, int mode) {
        int v = (mode == LIGHT || mode == DARK) ? mode : SYSTEM;
        prefs(c).edit().putInt(KEY, v).apply();
    }

    /** 必须在 super.onCreate() 之前调用，否则窗口底色已经定了。 */
    public static void apply(Activity a) {
        int m = get(a);
        if (m == LIGHT) a.setTheme(R.style.AppThemeLight);
        else if (m == DARK) a.setTheme(R.style.AppThemeDark);
        // SYSTEM：保留 Manifest 里的 @style/AppTheme，交给 -night 资源分支
    }

    /** 代码绘制部分按这个选色板。 */
    public static boolean isDark(Context c) {
        int m = get(c);
        if (m == LIGHT) return false;
        if (m == DARK) return true;
        int ui = c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return ui == Configuration.UI_MODE_NIGHT_YES;
    }

    public static String label(int mode) {
        switch (mode) {
            case LIGHT: return "浅色";
            case DARK: return "深色";
            default: return "跟随系统";
        }
    }
}
