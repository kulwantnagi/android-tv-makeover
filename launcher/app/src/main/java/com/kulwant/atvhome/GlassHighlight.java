package com.kulwant.atvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The single focus highlight, drawn below the tiles. Drawn directly so moving it never triggers a
 * layout pass. Two looks: glass (a light fill and edge, for the round buttons) and frame (a bright
 * white border just outside the enlarged app tile, easy to spot from the sofa).
 */
final class GlassHighlight extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float radius;
    private boolean tile;

    GlassHighlight(Context c) {
        super(c);
        fill.setColor(0x2EFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(c, 1.5f));
        stroke.setColor(0x70FFFFFF);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(Ui.dp(c, 3));
        frame.setColor(0xFFFFFFFF);
    }

    void set(RectF r, float radius) {
        rect.set(r);
        this.radius = radius;
        invalidate();
    }

    /** true for app tiles (white frame), false for the round buttons (glass). */
    void setLift(boolean tile) {
        if (this.tile == tile) return;
        this.tile = tile;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (rect.isEmpty()) return;
        if (tile) {
            float h = frame.getStrokeWidth() / 2f;
            c.drawRoundRect(rect.left + h, rect.top + h, rect.right - h, rect.bottom - h, radius, radius, frame);
            return;
        }
        c.drawRoundRect(rect, radius, radius, fill);
        float h = stroke.getStrokeWidth() / 2f;
        c.drawRoundRect(rect.left + h, rect.top + h, rect.right - h, rect.bottom - h, radius, radius, stroke);
    }
}
