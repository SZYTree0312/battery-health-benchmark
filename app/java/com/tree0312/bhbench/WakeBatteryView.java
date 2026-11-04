package com.tree0312.bhbench;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 唤醒动画里的电池。
 *
 * 不引第三方库、不用帧动画 PNG —— 一个 Canvas 画到底，深色浅色都跟着 Palette，
 * 强调色也是从系统主题取的。填充宽度由外部 ValueAnimator 推进，View 本身
 * 不持有动画，Activity 销毁时不会有回调泄漏。
 */
public class WakeBatteryView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Palette pal;
    private final float density;

    /** 0–100，动画推进的目标。 */
    private float progress = 0f;
    /** 入场时的整体缩放。 */
    private float scale = 1f;

    public WakeBatteryView(Context c) {
        super(c);
        pal = Palette.of(c);
        density = c.getResources().getDisplayMetrics().density;
    }

    public void setProgress(float p) {
        progress = Math.max(0f, Math.min(100f, p));
        invalidate();
    }

    public void setScale(float s) {
        scale = s;
        invalidate();
    }

    private int dp(int v) { return Math.round(v * density); }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(resolveSize(dp(168), wSpec), dp(92));
    }

    /** 电量低时换警示色，其余用系统强调色 —— 不自己造第二种蓝。 */
    private int levelColor(float p) {
        if (p < 15f) return pal.bad;
        if (p < 40f) return pal.warn;
        return pal.accent;
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        c.save();
        c.scale(scale, scale, getWidth() / 2f, getHeight() / 2f);

        float stroke = dp(3);
        float bodyL = dp(6) + stroke / 2;
        float bodyT = dp(10);
        float bodyR = getWidth() - dp(26);
        float bodyB = getHeight() - dp(10);
        float radius = dp(10);

        RectF body = new RectF(bodyL, bodyT, bodyR, bodyB);

        // 电池外框：三级文字色描边，浅色/深色都能看清
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(pal.textTertiary);
        paint.setShader(null);
        c.drawRoundRect(body, radius, radius, paint);

        // 正极凸起
        float capL = bodyR + dp(1);
        float capR = capL + dp(9);
        float capT = getHeight() / 2f - dp(13);
        float capB = getHeight() / 2f + dp(13);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pal.textTertiary);
        c.drawRoundRect(new RectF(capL, capT, capR, capB), dp(3), dp(3), paint);

        // 内部填充
        float pad = dp(6);
        float innerL = bodyL + pad;
        float innerT = bodyT + pad;
        float innerB = bodyB - pad;
        float maxW = bodyR - bodyL - 2 * pad;
        float fillW = maxW * progress / 100f;

        if (fillW > dp(2)) {
            // 纯色，不叠渐变：原生组件大多是平涂，也更贴近各家 ROM 的观感
            paint.setColor(levelColor(progress));
            paint.setStyle(Paint.Style.FILL);
            c.drawRoundRect(new RectF(innerL, innerT, innerL + fillW, innerB),
                    dp(5), dp(5), paint);
        }

        // 充电中的一道高光，跟着填充右边缘走
        if (progress > 2f && progress < 99.5f && fillW > dp(10)) {
            paint.setColor(Color.argb(52, 255, 255, 255));
            c.drawRoundRect(new RectF(innerL + fillW - dp(3), innerT,
                            innerL + fillW, innerB),
                    dp(2), dp(2), paint);
        }

        c.restore();
    }
}
