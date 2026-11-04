package com.tree0312.bhbench;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * 实时功率曲线。
 *
 * 和 SocChartView 一样是零依赖手绘，但用途不同：这张图是**边采边画**的，
 * 200 ms 来一个点，用户盯着看充电器能不能冲上去、会不会掉下来。
 *
 * 采样进行中不做平滑，也不做重采样 —— 台阶本身就是要看的东西，
 * 平滑一下台阶就没了。
 */
public class PowerChartView extends View {

    private double[] ts;      // 秒
    private double[] ps;      // W

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private float density;
    /** 配色跟随系统深浅色。 */
    private final Palette p;

    public PowerChartView(Context c) {
        super(c);
        p = Palette.of(c);
        density = c.getResources().getDisplayMetrics().density;
        text.setTextSize(9 * density);
        text.setColor(p.chartLabel);
    }

    public void setData(double[] ts, double[] ps) {
        this.ts = ts;
        this.ps = ps;
        invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = resolveSize((int) (320 * density), wSpec);
        int h = resolveSize((int) (150 * density), hSpec);
        setMeasuredDimension(w, h);
    }

    @Override protected void onDraw(Canvas canvas) {
        float left = 34 * density;
        float right = getWidth() - 10 * density;
        float top = 10 * density;
        float bottom = getHeight() - 20 * density;
        float plotW = right - left;
        float plotH = bottom - top;
        if (plotW <= 0 || plotH <= 0) return;

        // Y 轴上限：跟着当前峰值走，但至少 10 W，免得空图一条线贴底
        double yMax = 10;
        double xMax = 1;
        if (ps != null && ps.length > 0) {
            for (double p : ps) yMax = Math.max(yMax, p);
            yMax *= 1.15;
            xMax = Math.max(1, ts[ts.length - 1]);
        }

        // 网格：四等分，标功率
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(p.chartGrid);
        text.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= 4; i++) {
            float y = bottom - plotH * i / 4f;
            canvas.drawLine(left, y, right, y, paint);
            double val = yMax * i / 4.0;
            canvas.drawText(String.format(java.util.Locale.US, "%.0f", val),
                    left - 4 * density, y + 3 * density, text);
        }
        text.setTextAlign(Paint.Align.LEFT);

        // 轴
        paint.setColor(p.chartAxis);
        canvas.drawLine(left, top, left, bottom, paint);
        canvas.drawLine(left, bottom, right, bottom, paint);

        if (ps == null || ps.length < 2) {
            text.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("还没开始", left + plotW / 2, top + plotH / 2, text);
            text.setTextAlign(Paint.Align.LEFT);
            return;
        }

        // 面积
        path.reset();
        path.moveTo(left, bottom);
        for (int i = 0; i < ps.length; i++) {
            float x = left + (float) (ts[i] / xMax * plotW);
            float y = bottom - (float) (ps[i] / yMax * plotH);
            path.lineTo(x, Math.max(top, Math.min(bottom, y)));
        }
        path.lineTo(left + (float) (ts[ps.length - 1] / xMax * plotW), bottom);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(p.fillOf(p.chartCounter, 26, 38));
        canvas.drawPath(path, paint);

        // 线
        path.reset();
        for (int i = 0; i < ps.length; i++) {
            float x = left + (float) (ts[i] / xMax * plotW);
            float y = bottom - (float) (ps[i] / yMax * plotH);
            y = Math.max(top, Math.min(bottom, y));
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f * density);
        paint.setColor(p.chartCounter);
        paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(path, paint);

        // X 轴：起点 0s、末点
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("0s", left, getHeight() - 6 * density, text);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(Math.round(xMax) + "s", right, getHeight() - 6 * density, text);
        text.setTextAlign(Paint.Align.LEFT);

        // 右上角功率单位
        canvas.drawText("W", right, top - 1, text);
    }
}
