package com.tree0312.bhbench;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * 电量变化曲线。
 *
 * 系统「电池使用情况」里那张图无非是 SOC 随时间的折线。这里用我们自己采到的点
 * 画同一张图，并在上面叠两条参考线，让"曲线"变成能判断东西的工具：
 *
 *   粗实线  实测 SOC —— 厂商报的百分比，也就是系统那张图
 *   灰虚线  最小二乘直线 —— 「按使用时长匀速掉电」的理想假设
 *   橙细线  库仑计反推 SOC —— 硬件累加的真实电荷
 *
 * 实线和灰虚线之间的差 = 这段时间耗电比平均快还是慢。
 * 实线和橙线之间的差 = 厂商的百分比和真实电荷差多远（平滑、滞后、虚标）。
 *
 * 灰色竖带 = 这段时间没采到点（息屏进 Doze 后被系统拉长），曲线在那里是
 * 直线连过去的，不能当真实读数看。
 */
public class SocChartView extends View {

    private double[] xs;            // 小时
    private double[] soc;           // 实测 %
    private double[] fit;           // 拟合直线 %
    private double[] counter;       // 库仑计反推 %，可为 null
    private long[] gaps;            // 相邻点的时间差（秒），用于画空档

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private float density;
    /** 配色跟随系统深浅色。用户在系统里切换后 Activity 重建，这里会重新取。 */
    private final Palette p;

    public SocChartView(Context c) {
        super(c);
        p = Palette.of(c);
        density = c.getResources().getDisplayMetrics().density;
        text.setTextSize(9 * density);
        text.setColor(p.chartLabel);
    }

    /**
     * @param hours   时间轴（小时，从 0 开始）
     * @param soc     实测 SOC
     * @param fit     拟合直线，可 null
     * @param counter 库仑计反推 SOC，可 null
     * @param gapSec  相邻点间隔（秒），可 null
     */
    public void setData(double[] hours, double[] soc, double[] fit,
                        double[] counter, long[] gapSec) {
        this.xs = hours;
        this.soc = soc;
        this.fit = fit;
        this.counter = counter;
        this.gaps = gapSec;
        invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        // 宽度跟着父布局走；高度默认 188dp，但父布局给了具体值就听它的
        int w = resolveSize((int) (320 * density), wSpec);
        int h = resolveSize((int) (188 * density), hSpec);
        setMeasuredDimension(w, h);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (xs == null || xs.length < 2) return;

        float left = 30 * density;                 // 给 Y 轴标签留位置
        float right = getWidth() - 10 * density;
        float top = 12 * density;
        float bottom = getHeight() - 22 * density; // 给 X 轴标签留位置

        float plotW = right - left;
        float plotH = bottom - top;
        if (plotW <= 0 || plotH <= 0) return;

        final double xMax = Math.max(xs[xs.length - 1], 0.01);

        // ---- 空档带：先画，压在网格下面
        if (gaps != null) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(p.chartGap);
            for (int i = 1; i < xs.length; i++) {
                if (i - 1 >= gaps.length) break;
                if (gaps[i - 1] < 1800) continue;      // 30 分钟以内不算空档
                float x0 = left + (float) (xs[i - 1] / xMax * plotW);
                float x1 = left + (float) (xs[i] / xMax * plotW);
                canvas.drawRect(x0, top, x1, bottom, paint);
            }
        }

        // ---- 网格与 Y 轴刻度
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(p.chartGrid);
        for (int pct = 0; pct <= 100; pct += 25) {
            float y = bottom - (float) (pct / 100.0 * plotH);
            canvas.drawLine(left, y, right, y, paint);
            canvas.drawText(pct + "%", 2 * density, y + 3 * density, text);
        }

        // ---- 坐标轴
        paint.setColor(p.chartAxis);
        canvas.drawLine(left, top, left, bottom, paint);
        canvas.drawLine(left, bottom, right, bottom, paint);

        // ---- X 轴标签：起点 / 中点 / 终点
        paint.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(fmtH(xMax), left, getHeight() - 6 * density, text);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("0", left - 2 * density, getHeight() - 6 * density, text);
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(fmtH(xMax / 2), left + plotW / 2, getHeight() - 6 * density, text);
        text.setTextAlign(Paint.Align.LEFT);

        // ---- 实测与拟合之间的填充带：偏离平均耗电的部分
        if (fit != null) {
            path.reset();
            int n = xs.length;
            for (int i = 0; i < n; i++) {
                float x = left + (float) (xs[i] / xMax * plotW);
                float y = bottom - (float) (soc[i] / 100.0 * plotH);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            for (int i = n - 1; i >= 0; i--) {
                float x = left + (float) (xs[i] / xMax * plotW);
                float y = bottom - (float) (fit[i] / 100.0 * plotH);
                path.lineTo(x, y);
            }
            path.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(p.fillOf(p.accent, 18, 30));
            canvas.drawPath(path, paint);
        }

        // ---- 拟合虚线
        if (fit != null) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.4f * density);
            paint.setColor(p.chartFit);
            paint.setPathEffect(new DashPathEffect(new float[]{6 * density, 4 * density}, 0));
            strokeSeries(canvas, fit, left, top, bottom, plotW, plotH, xMax);
            paint.setPathEffect(null);
        }

        // ---- 库仑计反推线
        if (counter != null) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f * density);
            paint.setColor(p.chartCounter);
            strokeSeries(canvas, counter, left, top, bottom, plotW, plotH, xMax);
        }

        // ---- 实测主线
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.4f * density);
        paint.setColor(p.accent);
        paint.setStrokeCap(Paint.Cap.ROUND);
        strokeSeries(canvas, soc, left, top, bottom, plotW, plotH, xMax);
        paint.setStrokeCap(Paint.Cap.BUTT);

        // ---- 图例
        float lx = left;
        float ly = 8 * density;
        lx = legend(canvas, lx, ly, p.accent, 2.4f, false, "实测");
        if (fit != null) lx = legend(canvas, lx, ly, p.chartFit, 1.4f, true, "匀速参考");
        if (counter != null) legend(canvas, lx, ly, p.chartCounter, 1.6f, false, "库仑计反推");
    }

    private float legend(Canvas canvas, float x, float y, int color, float w, boolean dashed, String label) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(w * density);
        paint.setColor(color);
        if (dashed) paint.setPathEffect(new DashPathEffect(new float[]{5 * density, 3 * density}, 0));
        canvas.drawLine(x, y, x + 14 * density, y, paint);
        paint.setPathEffect(null);

        paint.setStyle(Paint.Style.FILL);
        canvas.drawText(label, x + 17 * density, y + 3 * density, text);
        return x + 20 * density + text.measureText(label) + 10 * density;
    }

    private void strokeSeries(Canvas canvas, double[] ys, float left, float top,
                              float bottom, float plotW, float plotH, double xMax) {
        path.reset();
        for (int i = 0; i < xs.length && i < ys.length; i++) {
            float x = left + (float) (xs[i] / xMax * plotW);
            float y = bottom - (float) (ys[i] / 100.0 * plotH);
            y = Math.max(top, Math.min(bottom, y));
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        canvas.drawPath(path, paint);
    }

    private static String fmtH(double h) {
        if (h >= 1.0) {
            long hh = (long) h;
            long mm = Math.round((h - hh) * 60);
            if (mm == 60) { hh++; mm = 0; }
            return hh + "h" + (mm > 0 ? mm + "m" : "");
        }
        return Math.round(h * 60) + "m";
    }
}
