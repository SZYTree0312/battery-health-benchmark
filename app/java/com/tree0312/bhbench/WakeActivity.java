package com.tree0312.bhbench;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * 开 App 的唤醒动画 —— v0.5.3。
 *
 * 不是一张静态 splash 图。电池从 0 一格格充到**这台手机此刻的真实电量**，
 * 充完淡出进主界面。顺手把电量亮出来，比纯装饰有用。
 *
 * 为什么不做成 MainActivity 里的一层遮罩：那样每次从结果页按返回都会再播一遍。
 * 独立成 LAUNCHER Activity 后，只有冷启动（进程新建、从桌面点图标）才会走这里；
 * App 在后台被切回前台时系统直接恢复原 task，不会重新播。
 *
 * 全程约 1.26 秒，轻点任意处可跳过。动画对象只在 onCreate 创建，Activity 销毁
 * 时 cancel，不留回调。
 */
public class WakeActivity extends Activity {

    private static final long FADE_IN_MS = 180;
    private static final long CHARGE_MS = 880;
    private static final long FADE_OUT_MS = 200;

    private Palette pal;
    private WakeBatteryView battery;
    private TextView pct;
    private TextView brand;
    private TextView tip;
    private LinearLayout content;

    private AnimatorSet sequence;
    private boolean done = false;

    @Override protected void onCreate(Bundle saved) {
        ThemeMode.apply(this);
        super.onCreate(saved);
        pal = Palette.of(this);
        setContentView(buildUi());
        Ui.applySystemBars(this, pal);

        int soc = readSoc();
        tip.setText(readCharging()
                ? "正在充电 · 电池状态已就绪"
                : "电池状态已就绪");
        startSequence(soc);
    }

    private int dp(int v) { return Ui.dp(this, v); }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(pal.bg);
        root.setOnClickListener(v -> go());

        // 关键：竖排 LinearLayout 的默认 LayoutParams 宽度是 MATCH_PARENT，不是
        // WRAP_CONTENT。v0.5.3 初版就是踩了这个坑 —— 电池（显式 WRAP_CONTENT）
        // 居中，而下面几行文字各占满一行并左对齐，看起来像「整体被推到左上角、
        // 左边还被切掉半截」。这里每个子 View 都显式给 WRAP_CONTENT，
        // 外层 content 本身也用 WRAP_CONTENT + 居中，位置就由布局算，不靠猜。
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        // 文字统一左对齐，起点对齐电池图形的左边缘 —— 整块居中，块内左对齐。
        // 视觉上既有左边距（不会贴屏幕边），读起来也是齐的一条线。
        content.setGravity(Gravity.START);
        content.setAlpha(0f);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        root.addView(content, clp);

        battery = new WakeBatteryView(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = dp(28);
        content.addView(battery, blp);

        pct = new TextView(this);
        pct.setText("0 %");
        pct.setTextSize(40);
        pct.setTypeface(Typeface.DEFAULT_BOLD);
        pct.setTextColor(pal.textPrimary);
        pct.setGravity(Gravity.START);
        content.addView(pct, wrap());

        brand = new TextView(this);
        // 跟着字符串资源走，改了 app_name 这里不会留着旧名字
        brand.setText(R.string.app_name);
        brand.setTextSize(16);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setTextColor(pal.textSecondary);
        brand.setGravity(Gravity.START);
        brand.setPadding(0, dp(20), 0, 0);
        content.addView(brand, wrap());

        tip = new TextView(this);
        tip.setText("正在读取电池状态…");
        tip.setTextSize(12);
        tip.setTextColor(pal.textTertiary);
        tip.setGravity(Gravity.START);
        tip.setPadding(0, dp(8), 0, 0);
        content.addView(tip, wrap());

        TextView skip = new TextView(this);
        skip.setText("轻点任意处跳过");
        skip.setTextSize(11);
        skip.setTextColor(pal.textTertiary);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        slp.bottomMargin = dp(28 + 16);   // 避开手势导航条
        root.addView(skip, slp);

        return root;
    }

    /** 竖排 LinearLayout 的默认宽度是 MATCH_PARENT，文字行必须显式包住自己。 */
    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    /** 和主界面同源：BatteryManager 的 sticky 广播，不额外申请权限。 */
    private int readSoc() {
        Intent b = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return 50;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return 50;
        return Math.max(0, Math.min(100, Math.round(level * 100f / scale)));
    }

    private boolean readCharging() {
        Intent b = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return false;
        int st = b.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);
        return st == BatteryManager.BATTERY_STATUS_CHARGING
                || st == BatteryManager.BATTERY_STATUS_FULL;
    }

    private void startSequence(int soc) {
        // 入场：淡入 + 轻微放大
        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(content, "alpha", 0f, 1f);
        ObjectAnimator sx = ObjectAnimator.ofFloat(content, "scaleX", 0.92f, 1f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(content, "scaleY", 0.92f, 1f);
        AnimatorSet intro = new AnimatorSet();
        intro.playTogether(fadeIn, sx, sy);
        intro.setDuration(FADE_IN_MS);
        intro.setInterpolator(new DecelerateInterpolator());

        // 主体：电量从 0 充到真实值，减速收尾
        ValueAnimator charge = ValueAnimator.ofFloat(0f, (float) soc);
        charge.setDuration(CHARGE_MS);
        charge.setInterpolator(new DecelerateInterpolator(1.5f));
        charge.addUpdateListener(a -> {
            float v = (Float) a.getAnimatedValue();
            battery.setProgress(v);
            pct.setText(String.format(Locale.US, "%d %%", Math.round(v)));
        });

        // 退场：整体淡出，直接接主界面，不要系统默认转场
        ObjectAnimator fadeOut = ObjectAnimator.ofFloat(content, "alpha", 1f, 0f);
        fadeOut.setDuration(FADE_OUT_MS);

        sequence = new AnimatorSet();
        sequence.playSequentially(intro, charge, fadeOut);
        sequence.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { go(); }
        });
        sequence.start();
    }

    /** 跳过或动画结束都走这里，done 保证只进主界面一次。 */
    private void go() {
        if (done) return;
        done = true;
        if (sequence != null) sequence.cancel();
        startActivity(new Intent(this, MainActivity.class));
        finish();
        overridePendingTransition(0, 0);
    }

    @Override public void onBackPressed() { go(); }

    @Override protected void onDestroy() {
        if (sequence != null) sequence.cancel();
        super.onDestroy();
    }
}
