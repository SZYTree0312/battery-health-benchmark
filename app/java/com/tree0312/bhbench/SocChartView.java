package com.tree0312.bhbench;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * 电量变化曲线。v0.5.6 起纵轴换成电荷，不再是百分比。
 *
 * 为什么要换：系统 SOC 只有 1% 分辨率（这台 7000 mAh 的机器 = 70 mAh 一台阶），
 * 而库仑计是 1 mAh。画在百分比轴上，库仑计那点细度全被压没了。
 *
 *   左轴 mAh  蓝实线   SOC 折算的电荷（台阶）
 *   左轴 mAh  橙实线   库仑计 Δcounter（平滑）
 *   左轴 mAh  灰虚线   匀速参考
 *   右轴 mA   紫虚线   库仑计 2 分钟滑动差分电流
 *
 * 最后那条是 v0.5.5 实测的副产品：CURRENT_NOW 读数系统性低约 16%，
 * 而库仑计差分不带这个偏差。两条线摆在一起，偏差一眼可见。
 *
 * 差分窗口不能短：1 mAh 量化在 5 秒上就是 720 mA 台阶（相对 21%），
 * 2 分钟才降到 30 mA（0.9%）。所以这条线一律按 2 分钟窗口算。
 *
 * 灰色竖带 = 没采到点的空档。差分电流线在空档处不连线。
 */
public class SocChartView extends View {

    private double[] xs;            // 小时
    private double[] soc;           // 从起点累计的电荷 mAh（SOC 折算）
    private double[] fit;           // 匀速参考 mAh，可 null
    private double[] counter;       // 库仑计 Δcounter mAh，可 null
    private double[] curX;          // 差分电流的时间点（小时）
    private double[] curY;          // 库仑计差分电流 mA
    private double[] curRead;       // 同期 CURRENT_NOW 平均 mA，用来看偏差
    private long[] gaps;            // 相邻点的时间差（秒）

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private float density;
    private final Palette p;

    public SocChartView(Context c) {
        super(c);
        p = Palette.of(c);
        density = c.getResources().getDisplayMetrics().density;
        text.setTextSize(9 * density);
        text.setColor(p.chartLabel);
    }

    /**
     * @param hours      时间轴（小时，从 0 开始）
     * @param socMah     SOC 折算的累计电荷，可 null
     * @param fitMah     匀速参考的累计电荷，可 null
     * @param counterMah 库仑计累计电荷，可 null
     * @param curHours   差分电流的时间点，可 null
     * @param curMa      库仑计差分电流 mA，可 null
     * @param curReadMa  同期 CURRENT_NOW 平均 mA，可 null
     * @param gapSec     相邻点间隔（秒），可 null
     */
    public void setData(double[] hours, double[] socMah, double[] fitMah,
                        double[] counterMah, double[] curHours, double[] curMa,
                        double[] curReadMa, long[] gapSec) {
        this.xs = hours;
        this.soc = socMah;
        this.fit = fitMah;
        this.counter = counterMah;
        this.curX = curHours;
        this.curY = curMa;
        this.curRead = curReadMa;
        this.gaps = gapSec;
        invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = resolveSize((int) (320 * density), wSpec);
        int h = resolveSize((int) (188 * density), hSpec);
        setMeasuredDimension(w, h);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (xs == null || xs.length < 2 || soc == null) return;

        float left = 46 * density;
        float right = getWidth() - 46 * density;
        float top = 14 * density;
        float bottom = getHeight() - 22 * density;

        float plotW = right - left;
        float plotH = bottom - top;
        if (plotW <= 0 || plotH <= 0) return;

        final double xMax = Math.max(xs[xs.length - 1], 0.01);
        double qMax = 1;
        for (double[] arr : new double[][]{soc, fit, counter}) {
            if (arr == null) continue;
            for (double v : arr) qMax = Math.max(qMax, v);
        }
        qMax = Math.ceil(qMax / 100.0) * 100.0;

        double iMin = 0, iMax = 1;
        boolean hasCurrent = curY != null && curY.length > 0 && curX != null;
        if (hasCurrent) {
            for (double v : curY) iMax = Math.max(iMax, v);
            if (curRead != null) for (double v : curRead) iMax = Math.max(iMax, v);
            iMax *= 1.15;
        }

        if (gaps != null) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(p.chartGap);
            for (int i = 1; i < xs.length; i++) {
                if (i - 1 >= gaps.length) break;
                if (gaps[i - 1] < 1800) continue;
                float x0 = left + (float) (xs[i - 1] / xMax * plotW);
                float x1 = left + (float) (xs[i] / xMax * plotW);
                canvas.drawRect(x0, top, x1, bottom, paint);
            }
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(p.chartGrid);
        text.setTextAlign(Paint.Align.RIGHT);
        for (int k = 0; k <= 4; k++) {
            float y = bottom - k / 4f * plotH;
            canvas.drawLine(left, y, right, y, paint);
            canvas.drawText(String.format(java.util.Locale.US, "%.0f", qMax * k / 4),
                    left - 4 * density, y + 3 * density, text);
            if (hasCurrent && k % 2 == 0) {
                text.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(String.format(java.util.Locale.US, "%.0f",
                                iMin + (iMax - iMin) * k / 4),
                        right + 4 * density, y + 3 * density, text);
                text.setTextAlign(Paint.Align.RIGHT);
            }
        }
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("mAh", 2 * density, top - 2 * density, text);
        if (hasCurrent) {
            text.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("mA", getWidth() - 2 * density, top - 2 * density, text);
            text.setTextAlign(Paint.Align.LEFT);
        }

        paint.setColor(p.chartAxis);
        canvas.drawLine(left, top, left, bottom, paint);
        canvas.drawLine(left, bottom, right, bottom, paint);

        paint.setStyle(Paint.Style.FILL);
        canvas.drawText(fmtH(xMax), left, getHeight() - 6 * density, text);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("0", left - 2 * density, getHeight() - 6 * density, text);
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(fmtH(xMax / 2), left + plotW / 2, getHeight() - 6 * density, text);
        text.setTextAlign(Paint.Align.LEFT);

        if (fit != null) {
            path.reset();
            int n = Math.min(xs.length, soc.length);
            for (int i = 0; i < n; i++) {
                float x = left + (float) (xs[i] / xMax * plotW);
                float y = yq(soc[i], qMax, top, bottom);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            for (int i = n - 1; i >= 0; i--) {
                float x = left + (float) (xs[i] / xMax * plotW);
                path.lineTo(x, yq(fit[i], qMax, top, bottom));
            }
            path.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(p.fillOf(p.accent, 18, 30));
            canvas.drawPath(path, paint);
        }

        if (fit != null) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.4f * density);
            paint.setColor(p.chartFit);
            paint.setPathEffect(new DashPathEffect(new float[]{6 * density, 4 * density}, 0));
            strokeQ(canvas, fit, qMax, left, top, bottom, plotW, xMax);
            paint.setPathEffect(null);
        }

        if (counter != null) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f * density);
            paint.setColor(p.chartCounter);
            strokeQ(canvas, counter, qMax, left, top, bottom, plotW, xMax);
        }

        // 右轴两条电流线：CURRENT_NOW 读数 vs 库仑计差分。差多少一眼可见。
        if (hasCurrent) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * density);
            if (curRead != null) {
                paint.setColor(p.dark ? 0xFFB5D4F4 : 0xFF378ADD);
                paint.setPathEffect(new DashPathEffect(new float[]{2 * density, 3 * density}, 0));
                strokeCurrent(canvas, curRead, iMin, iMax, left, top, bottom, plotW, xMax);
                paint.setPathEffect(null);
            }
            // 固定紫色系，深浅主题各取一档：不能靠「chartFit 混透明度」凑合，
            // 真机上两条线会糊成一片。
            paint.setColor(p.dark ? 0xFFAFA9EC : 0xFF7F77DD);
            paint.setPathEffect(new DashPathEffect(new float[]{5 * density, 3 * density}, 0));
            strokeCurrent(canvas, curY, iMin, iMax, left, top, bottom, plotW, xMax);
            paint.setPathEffect(null);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.4f * density);
        paint.setColor(p.accent);
        paint.setStrokeCap(Paint.Cap.ROUND);
        strokeQ(canvas, soc, qMax, left, top, bottom, plotW, xMax);
        paint.setStrokeCap(Paint.Cap.BUTT);

        float lx = left;
        float ly = 8 * density;
        lx = legend(canvas, lx, ly, p.accent, 2.4f, false, "SOC");
        if (fit != null) lx = legend(canvas, lx, ly, p.chartFit, 1.4f, true, "匀速");
        if (counter != null) lx = legend(canvas, lx, ly, p.chartCounter, 1.8f, false, "库仑计");
        if (hasCurrent) {
            if (curRead != null) {
                int c = p.dark ? 0xFFB5D4F4 : 0xFF378ADD;
                lx = legend(canvas, lx, ly, c, 1.5f, true, "读数电流");
            }
            int c = p.dark ? 0xFFAFA9EC : 0xFF7F77DD;
            legend(canvas, lx, ly, c, 1.5f, true, "库仑计电流");
        }
    }

    /** 60 秒以上的间隔断开差分电流线，与 CurveFit.SPLIT_GAP_S 一致。 */
    private static final double SPLIT_H = 60.0 / 3600.0;

    private static float yq(double v, double qMax, float top, float bottom) {
        return bottom - (float) Math.max(0, Math.min(v / qMax, 1.0)) * (bottom - top);
    }

    private static float yi(double v, double lo, double hi, float top, float bottom) {
        return bottom - (float) Math.max(0, Math.min((v - lo) / (hi - lo), 1.0))
                * (bottom - top);
    }

    private float legend(Canvas canvas, float x, float y, int color, float w,
                         boolean dashed, String label) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(w * density);
        paint.setColor(color);
        if (dashed) paint.setPathEffect(new DashPathEffect(new float[]{5 * density, 3 * density}, 0));
        canvas.drawLine(x, y, x + 12 * density, y, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawText(label, x + 15 * density, y + 3 * density, text);
        return x + 18 * density + text.measureText(label) + 7 * density;
    }

    private void strokeCurrent(Canvas canvas, double[] ys, double lo, double hi,
                               float left, float top, float bottom, float plotW, double xMax) {
        path.reset();
        boolean started = false;
        double prevX = -1;
        for (int i = 0; i < ys.length && i < curX.length; i++) {
            float x = left + (float) (curX[i] / xMax * plotW);
            float y = yi(ys[i], lo, hi, top, bottom);
            if (!started || (prevX >= 0 && curX[i] - prevX > SPLIT_H)) {
                path.moveTo(x, y);
                started = true;
            } else {
                path.lineTo(x, y);
            }
            prevX = curX[i];
        }
        canvas.drawPath(path, paint);
    }

    private void strokeQ(Canvas canvas, double[] ys, double qMax, float left,
                         float top, float bottom, float plotW, double xMax) {
        path.reset();
        for (int i = 0; i < xs.length && i < ys.length; i++) {
            float x = left + (float) (xs[i] / xMax * plotW);
            if (i == 0) path.moveTo(x, yq(ys[i], qMax, top, bottom));
            else path.lineTo(x, yq(ys[i], qMax, top, bottom));
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
