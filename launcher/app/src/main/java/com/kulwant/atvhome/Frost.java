package com.kulwant.atvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Frosted glass for the dock tray. Live blur is too slow for this TV's GPU, so each background (a
 * photo or a Top Shelf picture) is blurred once, small (see {@link #blurred}), and this view draws the
 * part of it that lies behind the tray, stretched back to screen size, under a dark-then-light tint.
 * A new background crossfades in.
 */
final class Frost extends View {
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imageBefore = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Matrix place = new android.graphics.Matrix();
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
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
        image.setShader(new android.graphics.BitmapShader(now,
                android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
        imageBefore.setShader(before == null ? null : new android.graphics.BitmapShader(before,
                android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
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
    }

    @Override
    protected void onDraw(Canvas c) {
        // the blurred screen as a texture on the rounded tray (no clip path: cheap on this GPU),
        // placed so the part behind the tray lines up with the real background
        if (now != null) {
            getLocationInWindow(loc);
            if (before != null && mix < 1f) {
                place(before);
                imageBefore.getShader().setLocalMatrix(place);
                c.drawRoundRect(box, radius, radius, imageBefore);
                image.setAlpha((int) (255 * mix));
            } else {
                image.setAlpha(255);
            }
            place(now);
            image.getShader().setLocalMatrix(place);
            c.drawRoundRect(box, radius, radius, image);
        }
        // darken like the scrim behind the dock, then a light glass wash
        tint.setColor(0x8C000000);
        c.drawRoundRect(box, radius, radius, tint);
        tint.setColor(0x24FFFFFF);
        c.drawRoundRect(box, radius, radius, tint);
        float h = edge.getStrokeWidth() / 2f;
        c.drawRoundRect(box.left + h, box.top + h, box.right - h, box.bottom - h, radius, radius, edge);
    }

    /** Maps a small blurred screen onto this view: stretched to screen size, shifted to our position. */
    private void place(Bitmap b) {
        place.setScale(screenW / (float) b.getWidth(), screenH / (float) b.getHeight());
        place.postTranslate(-loc[0], -loc[1]);
    }
}
