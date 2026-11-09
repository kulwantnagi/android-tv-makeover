package com.kulwant.atvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The single focus highlight, drawn below the tiles. Drawn directly so moving it never triggers a
 * layout pass. Two looks: glass (a light fill and edge, for the round buttons) and lift (no outline,
 * just a soft shadow under the enlarged tile, as on Apple TV).
 */
final class GlassHighlight extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float inset, drop;
    private float radius;
    private boolean lift;

    GlassHighlight(Context c) {
        super(c);
        fill.setColor(0x2EFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(c, 1.5f));
        stroke.setColor(0x70FFFFFF);
        // the shape itself is hidden under the tile; only its shadow shows (hardware shadows: API 28+)
        shadow.setColor(0xFF000000);
        shadow.setShadowLayer(Ui.dp(c, 18), 0, Ui.dp(c, 10), 0xB3000000);
        inset = Ui.dp(c, 5);
        drop = Ui.dp(c, 2);
    }

    void set(RectF r, float radius) {
        rect.set(r);
        this.radius = radius;
        invalidate();
    }

    /** true for app tiles (shadow only), false for the round buttons (glass). */
    void setLift(boolean lift) {
        if (this.lift == lift) return;
        this.lift = lift;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (rect.isEmpty()) return;
        if (lift) {
            // rect is the tile plus a margin; the shadow sits under the tile itself
            float r = Math.max(0, radius - inset);
            c.drawRoundRect(rect.left + inset, rect.top + inset + drop, rect.right - inset, rect.bottom - inset,
                    r, r, shadow);
            return;
        }
        c.drawRoundRect(rect, radius, radius, fill);
        float h = stroke.getStrokeWidth() / 2f;
        c.drawRoundRect(rect.left + h, rect.top + h, rect.right - h, rect.bottom - h, radius, radius, stroke);
    }
}
