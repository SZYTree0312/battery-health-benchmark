package com.tree0312.bhbench;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * 手画的齿轮图标。
 *
 * 为什么不直接用文字「⚙」或系统图标：这个 App 不引 AndroidX，没有
 * androidx 的 material icons；「⚙」在部分 ROM 上会被当 emoji 渲染成彩色图形，
 * 和单色界面不搭，深色模式下尤其突兀。二十行 Canvas 代码反而最稳。
 */
final class GearIcon extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    GearIcon(Context c, int color) {
        super(c);
        paint.setColor(color);
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float unit = Math.min(w, h);

        float ringR = unit * 0.30f;        // 齿根圆半径
        float toothH = unit * 0.16f;       // 齿高
        float toothW = unit * 0.15f;       // 齿宽
        float stroke = unit * 0.10f;

        // 8 个齿：绕中心转 45° 一圈，每个齿是从环外缘往外伸的圆角小方块
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 8; i++) {
            canvas.save();
            canvas.rotate(i * 45f, cx, cy);
            canvas.drawRoundRect(
                    cx - toothW / 2f,
                    cy - ringR - toothH + stroke / 2f,
                    cx + toothW / 2f,
                    cy - ringR + stroke / 2f,
                    toothW / 2f, toothW / 2f, paint);
            canvas.restore();
        }

        // 圆环：中心留空，齿轮才有「孔」的感觉
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        canvas.drawCircle(cx, cy, ringR, paint);
    }
}
