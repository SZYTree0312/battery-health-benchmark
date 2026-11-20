package com.tree0312.bhbench;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * 电流对照小图：CURRENT_NOW 读数 vs 库仑计差分。
 *
 * 为什么不和电荷曲线叠在一张图上：电荷从 0 涨到几千 mAh，电流在几千 mA
 * 附近横着走，两条不同量纲的线挤在同一块画布上，真机截图里糊成一片。
 * 拆开之后纵轴按电流自身范围缩放，16% 的偏差一眼就能看见。
 *
 * 蓝短虚线 = CURRENT_NOW 同期平均（有系统性比例偏差）
 * 紫长虚线 = 库仑计 2 分钟差分（基准）
 */
public class CurrentChartView extends View {

    private double[] xs;
    private double[] read;
    private double[] counter;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Palette p;
    private float density;

    public CurrentChartView(Context c) {
        super(c);
        p = Palette.of(c);
        density = c.getResources().getDisplayMetrics().density;
        text.setTextSize(9 * density);
        text.setColor(p.chartLabel);
    }

    public void setData(double[] hours, double[] readingMa, double[] counterMa) {
        this.xs = hours;
        this.read = readingMa;
        this.counter = counterMa;
        invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(resolveSize((int) (320 * density), wSpec),
                resolveSize((int) (124 * density), hSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        if (xs == null || xs.length < 2 || counter == null || counter.length < 2) return;
        float left = 46 * density, right = getWidth() - 46 * density;
        float top = 16 * density, bottom = getHeight() - 20 * density;
        float plotW = right - left, plotH = bottom - top;
        if (plotW <= 0 || plotH <= 0) return;

        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double v : counter) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        if (read != null) for (double v : read) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        double pad = Math.max(20, (hi - lo) * 0.2);
        lo = Math.max(0, lo - pad);
        hi = hi + pad;
        if (hi - lo < 10) hi = lo + 10;

        double xMax = Math.max(xs[xs.length - 1], 0.01);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(p.chartGrid);
        text.setTextAlign(Paint.Align.RIGHT);
        for (int k = 0; k <= 2; k++) {
            float y = bottom - k / 2f * plotH;
            canvas.drawLine(left, y, right, y, paint);
            canvas.drawText(String.format(java.util.Locale.US, "%.0f", lo + (hi - lo) * k / 2),
                    left - 4 * density, y + 3 * density, text);
        }
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("mA", 2 * density, top - 3 * density, text);

        paint.setColor(p.chartAxis);
        canvas.drawLine(left, top, left, bottom, paint);
        canvas.drawLine(left, bottom, right, bottom, paint);

        paint.setStyle(Paint.Style.FILL);
        canvas.drawText(fmtH(xMax), left, getHeight() - 4 * density, text);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("0", left - 2 * density, getHeight() - 4 * density, text);
        text.setTextAlign(Paint.Align.LEFT);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * density);
        if (read != null && read.length > 0) {
            paint.setColor(p.dark ? 0xFFB5D4F4 : 0xFF378ADD);
            paint.setPathEffect(new DashPathEffect(new float[]{2 * density, 3 * density}, 0));
            stroke(canvas, read, lo, hi, left, top, bottom, plotW, xMax);
            paint.setPathEffect(null);
        }
        paint.setColor(p.dark ? 0xFFAFA9EC : 0xFF7F77DD);
        paint.setPathEffect(new DashPathEffect(new float[]{5 * density, 3 * density}, 0));
        stroke(canvas, counter, lo, hi, left, top, bottom, plotW, xMax);
        paint.setPathEffect(null);

        float lx = left, ly = 8 * density;
        int blue = p.dark ? 0xFFB5D4F4 : 0xFF378ADD;
        int purple = p.dark ? 0xFFAFA9EC : 0xFF7F77DD;
        if (read != null && read.length > 0) {
            lx = legend(canvas, lx, ly, blue, "读数电流");
            double avgR = avg(read), avgC = avg(counter);
            double ratio = avgC > 0 ? avgR / avgC : 0;
            text.setTextAlign(Paint.Align.RIGHT);
            text.setColor(p.textSecondary);
            canvas.drawText(String.format(java.util.Locale.US, "读数/库仑计 %.3f", ratio),
                    right, ly + 3 * density, text);
            text.setColor(p.chartLabel);
            text.setTextAlign(Paint.Align.LEFT);
        }
        legend(canvas, lx, ly, purple, "库仑计电流");
    }

    private static double avg(double[] a) {
        double s = 0;
        for (double v : a) s += v;
        return a.length > 0 ? s / a.length : 0;
    }

    private float legend(Canvas canvas, float x, float y, int color, String label) {
        paint.setColor(color);
        canvas.drawLine(x, y, x + 12 * density, y, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawText(label, x + 15 * density, y + 3 * density, text);
        paint.setStyle(Paint.Style.STROKE);
        return x + 18 * density + text.measureText(label) + 10 * density;
    }

    private void stroke(Canvas canvas, double[] ys, double lo, double hi,
                        float left, float top, float bottom, float plotW, double xMax) {
        path.reset();
        for (int i = 0; i < ys.length && i < xs.length; i++) {
            float x = left + (float) (xs[i] / xMax * plotW);
            float y = bottom - (float) Math.max(0, Math.min((ys[i] - lo) / (hi - lo), 1))
                    * (bottom - top);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        canvas.drawPath(path, paint);
    }

    private static String fmtH(double h) {
        return h >= 1 ? String.format(java.util.Locale.US, "%.1fh", h)
                : Math.round(h * 60) + "m";
    }
}
