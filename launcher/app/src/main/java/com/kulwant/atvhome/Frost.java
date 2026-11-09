package com.kulwant.atvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

/**
 * Frosted glass for the dock tray. Live blur is too slow for this TV's GPU, so each background (a
 * photo or a Top Shelf picture) is blurred once, small (see {@link #blurred}), and this view draws the
 * part of it that lies behind the tray, stretched back to screen size, under a dark-then-light tint.
 * A new background crossfades in.
 */
final class Frost extends View {
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF();
    private final Rect dst = new Rect();
    private final int[] loc = new int[2];
    private final float radius;
    private final int screenW, screenH;
    private Bitmap now, before;
    private float mix = 1f;
    private ValueAnimator fade;

    Frost(Context c, float radius, int screenW, int screenH) {
        super(c);
        this.radius = radius;
        this.screenW = screenW;
        this.screenH = screenH;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1, Ui.dp(c, 1)));
        edge.setColor(0x38FFFFFF);
    }

    /** A small, blurred copy of a full-screen background (call off the main thread). */
    static Bitmap blurred(Bitmap screen) {
        if (screen == null) return null;
        Bitmap small = Bitmap.createScaledBitmap(screen, Math.max(1, screen.getWidth() / 16),
                Math.max(1, screen.getHeight() / 16), true);
        if (small.getConfig() != Bitmap.Config.ARGB_8888) small = small.copy(Bitmap.Config.ARGB_8888, false);
        return Ui.blur(small, 3, 3);
    }

    /** Shows a new blurred background; crossfades over {@code ms} (0 = at once). */
    void setSource(Bitmap blurredScreen, long ms) {
        if (blurredScreen == null || blurredScreen == now) return;
        if (fade != null) fade.cancel();
        before = ms > 0 ? now : null;
        now = blurredScreen;
        if (before == null) {
            mix = 1f;
            invalidate();
            return;
        }
        mix = 0f;
        fade = ValueAnimator.ofFloat(0f, 1f);
        fade.setDuration(ms);
        fade.addUpdateListener(a -> {
            mix = (float) a.getAnimatedValue();
            invalidate();
        });
        fade.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        box.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(box, radius, radius, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas c) {
        c.save();
        c.clipPath(clip);
        if (now != null) {
            // the blurred screen, placed so the part behind the tray lines up with the real background
            getLocationInWindow(loc);
            dst.set(-loc[0], -loc[1], screenW - loc[0], screenH - loc[1]);
            if (before != null && mix < 1f) {
                image.setAlpha(255);
                c.drawBitmap(before, null, dst, image);
                image.setAlpha((int) (255 * mix));
            } else {
                image.setAlpha(255);
            }
            c.drawBitmap(now, null, dst, image);
        }
        // darken like the scrim behind the dock, then a light glass wash
        tint.setColor(0x8C000000);
        c.drawRect(box, tint);
        tint.setColor(0x24FFFFFF);
        c.drawRect(box, tint);
        c.restore();
        float h = edge.getStrokeWidth() / 2f;
        c.drawRoundRect(box.left + h, box.top + h, box.right - h, box.bottom - h, radius, radius, edge);
    }
}
